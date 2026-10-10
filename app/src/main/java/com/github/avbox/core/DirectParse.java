package com.github.avbox.core;

import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * DirectParse —— type=0(拼 URL 型)解析站的 HTTP 直出快速路。
 *
 * 原理:配置里绝大多数解析站是"把播放页地址拼在自己后面"的形态,例如
 *   https://jx.xxx.com/?url=  +  https://v.qq.com/x/cover/xxx.html
 * 这类站返回的页面里通常**已经写着最终 m3u8/mp4 地址**(常在 JS 变量或 iframe 里),
 * 根本不需要 WebView 去嗅探。
 *
 * 原来的超级解析对这类站一律走 WebView 多 iframe 嗅探:要先建内核、加载几十个 iframe、
 * 等站点自己的播放器跑起来,再拦截请求 —— 首播普遍 5~10s。
 * 本类把"页面里已带最终地址"的这部分站先用短超时 HTTP 并发打一遍,
 * 谁先吐地址谁赢,命中即直接起播,完全不碰 WebView。
 * 打不出来的站(需要真跑 JS 的)自动回落到原有的 WebView 嗅探,能力只增不减。
 *
 * 另外顺带做两件事,都是"更强":<br>
 *   1) 一次中转跟随 —— 页面里是嵌套播放页(iframe)时,再跟一跳继续找地址;
 *   2) 多种载荷兜底 —— 转义斜杠、HTML 实体、base64(atob)、JSON 包裹都能识别。
 */
public final class DirectParse {

    /** 快速路总预算:超过这个时间还没撞到结果,就交给 WebView 嗅探,不再让用户干等 */
    public static final long DEFAULT_BUDGET_MS = 3_200L;
    /** 单个站的连接/读取超时 —— 比 WebView 路径快一个数量级 */
    private static final long CONNECT_TIMEOUT_MS = 2_400L;
    private static final long READ_TIMEOUT_MS = 2_800L;
    /** 并发上限:再多也没用(不同站点带宽/连接数有限),反而拖慢整体 */
    private static final int MAX_PARALLEL = 14;
    /** 用做兜底 UA 的常量(从解析站 ext 没取到 header 时使用) */
    private static final String FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";

    /** 直链形态:必须有媒体后缀,避免把播放页自己的地址当结果 */
    private static final Pattern MEDIA = Pattern.compile(
            "https?://[^\\s\"'<>\\\\)\\]]+?\\.(?:m3u8|mp4|flv|mkv|m4s|mp3|m4a)(?:\\?[^\\s\"'<>\\\\)\\]]*)?",
            Pattern.CASE_INSENSITIVE);

    /** 页面里嵌套的下一跳播放页 */
    private static final Pattern NEXT_IFRAME = Pattern.compile(
            "<iframe[^>]+?src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_JSON = Pattern.compile(
            "[\"'](?:url|file|src|video|playUrl|videoUrl|m3u8)[\"']\\s*:\\s*[\"'](https?://[^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** player 配置里的相对路径片段(如 ?url=xxx.svg 之类的伪地址要滤掉) */
    private static final Pattern JUNK = Pattern.compile(
            "\\.(?:js|css|html?|png|jpe?g|gif|svg|ico|woff2?|ttf)(?:\\?|$)", Pattern.CASE_INSENSITIVE);

    private DirectParse() {
    }

    private static volatile ExecutorService POOL;

    private static ExecutorService pool() {
        ExecutorService p = POOL;
        if (p == null) {
            synchronized (DirectParse.class) {
                p = POOL;
                if (p == null) {
                    final AtomicInteger seq = new AtomicInteger(1);
                    ThreadFactory tf = new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "direct-parse-" + seq.getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        }
                    };
                    p = Executors.newCachedThreadPool(tf);
                    POOL = p;
                }
            }
        }
        return p;
    }

    /**
     * 并发竞速所有 type=0 解析站,第一个拿到可播地址的即返回。
     *
     * @param nameToUrl  解析站名字 → 解析站地址(带 ?url= 前缀)
     * @param nameToExt  解析站名字 → ext JSON 文本(可含 header)
     * @param videoUrl   需要解析的播放页地址
     * @param flag       当前线路 flag(用于健康度"在这个站点赢过"的记忆)
     * @return 命中的 {url, jxFrom, header...};全都没命中返回 null
     */
    public static JSONObject race(LinkedHashMap<String, String> nameToUrl,
                                  LinkedHashMap<String, String> nameToExt,
                                  String videoUrl,
                                  String flag,
                                  long budgetMs) {
        if (nameToUrl == null || nameToUrl.isEmpty() || videoUrl == null || videoUrl.isEmpty()) {
            return null;
        }
        ParseHealth.ensureRestored();

        List<String> names = new ArrayList<>(nameToUrl.keySet());
        // 熔断中的站先摘掉;全被熔断则退回到原列表(宁可试也别空手)
        List<String> alive = ParseHealth.filterCooling(names);
        // 排序:快的/稳的/在这个站点赢过的排前面 —— 竞速里排前面就意味着更早撞到结果
        alive = ParseHealth.sort(alive, flag);
        if (alive.size() > MAX_PARALLEL) {
            alive = new ArrayList<>(alive.subList(0, MAX_PARALLEL));
        }
        if (alive.isEmpty()) return null;

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Map<String, Call> active = new ConcurrentHashMap<>();
        ExecutorService pool = pool();
        CompletionService<Object[]> cs = new ExecutorCompletionService<>(pool);
        List<Future<Object[]>> futures = new ArrayList<>(alive.size());

        for (String name : alive) {
            final String stationName = name;
            final String stationUrl = nameToUrl.get(name);
            final String stationExt = nameToExt == null ? null : nameToExt.get(name);
            try {
                futures.add(cs.submit(new Callable<Object[]>() {
                    @Override
                    public Object[] call() {
                        long t0 = System.currentTimeMillis();
                        try {
                            String found = probe(stationName, stationUrl, stationExt, videoUrl, active, cancelled);
                            long cost = System.currentTimeMillis() - t0;
                            if (cancelled.get()) return null;
                            if (found != null && !found.isEmpty()) {
                                ParseHealth.report(stationName, true, cost);
                                ParseHealth.markWinner(stationName, flag);
                                return new Object[]{stationName, found, stationExt};
                            }
                            ParseHealth.report(stationName, false, cost);
                            return null;
                        } catch (Throwable th) {
                            if (!cancelled.get()) {
                                ParseHealth.report(stationName, false, System.currentTimeMillis() - t0);
                            }
                            return null;
                        }
                    }
                }));
            } catch (Throwable ignored) {
            }
        }

        long deadline = System.currentTimeMillis() + Math.max(500L, budgetMs);
        try {
            for (int i = 0; i < futures.size(); i++) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                Future<Object[]> done;
                try {
                    done = cs.poll(remain, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (done == null) break;
                Object[] r;
                try {
                    r = done.get();
                } catch (Throwable th) {
                    continue;
                }
                if (r == null) continue;
                // 命中:立刻掐掉还在跑的其它站,把带宽和线程让给播放器
                cancelled.set(true);
                cancelActive(active);
                return buildResult((String) r[0], (String) r[1], (String) r[2]);
            }
        } catch (Throwable th) {
            LOG.d("DirectParse", "race failed: " + th);
        } finally {
            cancelled.set(true);
            cancelActive(active);
        }
        return null;
    }

    private static void cancelActive(Map<String, Call> active) {
        for (Iterator<Map.Entry<String, Call>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Call> e = it.next();
            try {
                Call c = e.getValue();
                if (c != null && !c.isCanceled()) c.cancel();
            } catch (Throwable ignored) {
            }
            it.remove();
        }
    }

    private static JSONObject buildResult(String name, String url, String ext) {
        try {
            JSONObject out = new JSONObject();
            out.put("url", url);
            out.put("jxFrom", name);
            out.put("parse", 0);
            JSONObject header = headerOf(ext);
            if (header != null && header.length() > 0) {
                out.put("header", header);
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从解析站 ext 里取 header(UA/Referer 等):这些头对防盗链站点是必需的 */
    private static JSONObject headerOf(String ext) {
        if (ext == null || ext.trim().isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(ext);
            JSONObject header = json.optJSONObject("header");
            if (header == null) header = json.optJSONObject("headers");
            return header;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================== 单个站的探测 ====================

    /**
     * 探一个站:先请求"解析站地址 + 播放页地址",页面里直接有媒体地址就用;
     * 没有则从页面里找嵌套播放页,再跟一跳。
     */
    private static String probe(String name, String stationUrl, String ext, String videoUrl,
                                Map<String, Call> active, AtomicBoolean cancelled) {
        if (stationUrl == null || stationUrl.isEmpty()) return null;
        // 解析站地址本身就是占位(如 "Web")时跳过
        if (!stationUrl.startsWith("http")) return null;

        String first = stationUrl + videoUrl;
        OkHttpClient http = client();
        JSONObject header = headerOf(ext);

        String body = get(http, name, first, header, null, active, cancelled);
        if (cancelled.get()) return null;

        String media = extractMedia(body, videoUrl);
        if (media != null) return media;

        // 一次中转:页面里套着播放页时继续跟一跳
        String next = extractNext(body, first, videoUrl);
        if (next == null || next.equals(first)) return null;
        if (cancelled.get()) return null;

        String body2 = get(http, name, next, header, first, active, cancelled);
        if (cancelled.get()) return null;
        return extractMedia(body2, videoUrl);
    }

    /**
     * 共享 OkHttp 实例的连接池/调度器:只加"解析专用短超时",
     * 复用主客户端的连接池 → 同一批解析站连续解析时能直接走 keep-alive,省掉 TCP/TLS 握手。
     */
    private static OkHttpClient client() {
        OkHttpClient base = null;
        try {
            base = OkGoHelper.getDefaultClient();
        } catch (Throwable ignored) {
        }
        OkHttpClient.Builder builder = base != null ? base.newBuilder() : new OkHttpClient.Builder();
        return builder
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private static String get(OkHttpClient client, String tag, String url, JSONObject header,
                              String referer, Map<String, Call> active, AtomicBoolean cancelled) {
        if (cancelled.get()) return null;
        Response response = null;
        try {
            Request.Builder rb = new Request.Builder().url(url);
            rb.header("User-Agent", FALLBACK_UA);
            if (header != null) {
                Iterator<String> keys = header.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = header.optString(k, "");
                    if (k == null || k.isEmpty() || v == null || v.isEmpty()) continue;
                    try {
                        rb.header(k, v);
                    } catch (Throwable ignored) {
                        // 非法头名会让 Request.Builder 抛异常,单条丢弃,不影响整次请求
                    }
                }
            }
            if (referer != null && !referer.isEmpty()) {
                try {
                    rb.header("Referer", referer);
                } catch (Throwable ignored) {
                }
            }
            final Call call = client.newCall(rb.build());
            active.put(tag + "@" + url.hashCode(), call);
            response = call.execute();
            ResponseBody body = response.body();
            if (body == null) return null;
            String contentType = body.contentType() == null ? "" : body.contentType().toString().toLowerCase();
            // 直接就是媒体流:这个解析站地址本身就是可播地址。
            // octet-stream 只在 URL 明确带媒体后缀时才认 —— 否则错误页/压缩包也会被当成播放地址。
            boolean isStream = contentType.startsWith("video/") || contentType.contains("mpegurl");
            if (!isStream && contentType.contains("octet-stream")) {
                isStream = MEDIA.matcher(url).find();
            }
            if (isStream && isUsable(url, null)) {
                return "@@SELF@@" + url;
            }
            long len = body.contentLength();
            if (len > 3L * 1024L * 1024L) {
                // 超大响应不读正文(正常解析页几 KB ~ 几百 KB),读了纯属浪费带宽
                return null;
            }
            return body.string();
        } catch (Throwable th) {
            return null;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================== 载荷解析 ====================

    /** 从响应正文里挖出媒体地址;挖不到返回 null */
    static String extractMedia(String body, String origin) {
        if (body == null || body.isEmpty()) return null;
        if (body.startsWith("@@SELF@@")) return body.substring("@@SELF@@".length());

        String text = normalize(body);
        String fallback = null;

        Matcher m = MEDIA.matcher(text);
        while (m.find()) {
            String u = clean(m.group());
            if (!isUsable(u, origin)) continue;
            // HLS 优先:同页出现 mp4 预告片和 m3u8 正片时,正片才是用户要的
            if (u.toLowerCase().contains(".m3u8")) return u;
            if (fallback == null) fallback = u;
        }
        if (fallback != null) return fallback;

        // 兜底:base64 里藏着地址(atob('...') / base64 编码的播放配置)
        String b64 = extractBase64(text);
        if (b64 != null) {
            Matcher m2 = MEDIA.matcher(b64);
            while (m2.find()) {
                String u = clean(m2.group());
                if (isUsable(u, origin)) return u;
            }
        }
        return null;
    }

    /** 页面里嵌套的下一跳播放页地址 */
    static String extractNext(String body, String current, String origin) {
        if (body == null || body.isEmpty()) return null;
        String text = normalize(body);
        String cand = firstGroup(NEXT_IFRAME, text);
        if (cand == null) cand = firstGroup(NEXT_JSON, text);
        if (cand == null) return null;
        String u = clean(cand);
        if (u.startsWith("//")) u = "https:" + u;
        if (!u.startsWith("http")) return null;
        if (u.equals(current) || u.equals(origin)) return null;
        if (JUNK.matcher(u).find()) return null;
        return u;
    }

    private static String firstGroup(Pattern p, String text) {
        try {
            Matcher m = p.matcher(text);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 统一各种转义:JSON 的 \/ 、HTML 实体、URL 编码的斜杠 */
    private static String normalize(String body) {
        String text = body;
        try {
            text = text.replace("\\/", "/");
            text = text.replace("\\u0026", "&").replace("\\u003d", "=").replace("\\u002F", "/");
            text = text.replace("&amp;", "&").replace("&#38;", "&");
            if (text.contains("%3A%2F%2F") || text.contains("%3a%2f%2f")) {
                text = text.replace("%3A%2F%2F", "://").replace("%3a%2f%2f", "://");
            }
        } catch (Throwable ignored) {
        }
        return text;
    }

    /** 去掉粘连的尾部标点/引号 */
    private static String clean(String url) {
        if (url == null) return null;
        String u = url.trim();
        while (!u.isEmpty()) {
            char c = u.charAt(u.length() - 1);
            if (c == '"' || c == '\'' || c == ')' || c == ']' || c == ',' || c == ';' || c == '\\') {
                u = u.substring(0, u.length() - 1);
            } else {
                break;
            }
        }
        if (u.startsWith("//")) u = "https:" + u;
        return u;
    }

    /** 过滤明显不是"可播地址"的命中:解析页自身、非媒体后缀、站点自己的播放器脚本 */
    private static boolean isUsable(String url, String origin) {
        if (url == null || url.length() < 16) return false;
        String low = url.toLowerCase();
        if (!low.startsWith("http")) return false;
        if (JUNK.matcher(low).find()) return false;
        if (low.contains("url=http") || low.contains("url=https")) return false;
        if (low.contains(".js?") || low.contains(".css?")) return false;
        // 不能是"刚请求的这个播放页"本身,否则等于没解析
        if (origin != null && !origin.isEmpty() && low.startsWith(origin.toLowerCase())) return false;
        return true;
    }

    /** 从正文里抓一段看似 base64 的长串并解码(部分解析站把播放配置整段编码) */
    private static String extractBase64(String text) {
        try {
            Pattern p = Pattern.compile("(?:atob\\s*\\(\\s*[\"']|base64,)([A-Za-z0-9+/=]{80,})");
            Matcher m = p.matcher(text);
            if (!m.find()) return null;
            String raw = m.group(1);
            byte[] decoded = android.util.Base64.decode(raw, android.util.Base64.DEFAULT);
            if (decoded == null || decoded.length == 0) return null;
            return new String(decoded, "UTF-8");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 解码 URL 参数(部分解析站会把地址再包一层编码) */
    static String safeDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable ignored) {
            return s;
        }
    }
}
