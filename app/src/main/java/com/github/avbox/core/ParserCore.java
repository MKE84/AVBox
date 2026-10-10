package com.github.avbox.core;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ParserCore —— 内置解析引擎。
 * 独立的视频地址解析层,不依赖 PlayUrlResolver(也不和 UI/播放器耦合)。
 *
 * 特性:
 *  1) 解析白名单 —— 只信任 type=3(聚合/超级解析) / playm3u8.cn(盘古) / ckplayer.vip(解析4),
 *     与 ApiConfig 的过滤规则保持一致,避免"卡在正在嗅探播放地址"。
 *  2) 单次解析最多等待 PARSE_TIMEOUT_MS(默认 8s),超时即放弃本解析器。
 *  3) 多解析器按序自动切换 / 全部失败时放行原始地址(由外层决定是否再尝试)。
 *
 * 本类纯字符串/JSON 处理,不持有任何 UI 引用,可被 jar 源或 service 复用。
 */
public final class ParserCore {

    /** 单次解析超时(ms) — 比 PlayUrlResolver 的 20s 更激进,避免长期假死 */
    public static final long PARSE_TIMEOUT_MS = 8_000L;

    /** 解析失败后是否仍返回原始 url(保底,不弹错) */
    private static final boolean FALLBACK_RAW = true;

    private final List<String> parseUrls;      // 已白名单过滤的解析器地址
    private final AtomicBoolean cancelled;

    public ParserCore() {
        this(new ArrayList<String>(), false);
    }

    public ParserCore(List<String> parseUrls) {
        this(parseUrls, false);
    }

    public ParserCore(List<String> parseUrls, boolean cancelledFlag) {
        this.parseUrls = parseUrls == null ? new ArrayList<String>() : parseUrls;
        this.cancelled = new AtomicBoolean(cancelledFlag);
    }

    // ==================== 静态白名单判定 ====================

    /**
     * 是否放行该解析器。
     *
     * 改造(2026-10):原实现只放行 playm3u8.cn / ckplayer.vip 两个域名 —— 这是把
     * "历史上比较好用的一小撮站"当成了**唯一准入**,结果配置里几十个解析站全被挡在门外,
     * 表现成"解析池看起来很大、实际只有两个站在跑",失败率自然高。
     *
     * 现在的口径:解析站地址本身只做**格式合法性**校验(必须是 http(s) 且不是占位符),
     * 质量好坏交给 ParseHealth 的实绩评分与熔断去筛 —— 好站自然排前面,坏站自然被冷落,
     * 而不是靠一份写死的域名名单一刀切。
     */
    public static boolean acceptable(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        if (u.isEmpty()) return false;
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        // 配置里的占位(如 "Web" 系列)不是真解析站
        if (u.length() < 12) return false;
        return true;
    }

    /** 历史优先站(只用于排序加成,不再作为准入条件) */
    public static boolean isPreferred(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return u.contains("playm3u8.cn") || u.contains("ckplayer.vip");
    }

    /** 是否聚合类解析(type=3/超级解析) */
    public static boolean isAggregate(int type) {
        return type == 3 || type == 4;
    }

    // ==================== 入口 ====================

    /**
     * 尝试用内置解析器把原始播放地址解析为真实可播地址。
     *
     * @param sourceUrl 原始播放地址(url/直链)
     * @return 解析成功返回真实地址;失败返回 null(可回退到 sourceUrl)
     */
    public String resolve(String sourceUrl) {
        return resolve(sourceUrl, null);
    }

    /**
     * 带可选附加 flag 的解析(转发 parse 参数等)。
     */
    @SuppressWarnings("unused")
    public String resolve(String sourceUrl, String flag) {
        if (sourceUrl == null) return null;
        String src = sourceUrl.trim();
        // 已经是直链(常见视频后缀),不需要解析,直接放行
        if (isDirect(src)) return src;
        // 无解析器时,按保底策略放行原始地址
        if (parseUrls.isEmpty()) return FALLBACK_RAW ? src : null;

        for (String parseUrl : parseUrls) {
            if (cancelled.get()) return FALLBACK_RAW ? src : null;
            if (parseUrl == null || parseUrl.isEmpty()) continue;
            String result = tryOne(parseUrl, src);
            if (result != null && !result.equals(src)) return result;
        }
        return FALLBACK_RAW ? src : null;
    }

    /** 取消当前(及未开始)的解析 */
    public void cancel() {
        cancelled.set(true);
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unused")
    private String tryOne(String parseUrl, String src) {
        // 标准 TVBox 解析拼接: get_url/extend.php?url=<需解析的地址>
        String target;
        try {
            // 已经带 query 的解析器在 url 后追加;否则直接拼 encode
            boolean hasQuery = parseUrl.contains("?");
            target = parseUrl + (hasQuery ? "&" : "?") + "url=" + URLEncoder.encode(src, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        // 超时看护:在单线程 + 一个 guard 上有界等待
        final String[] holder = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                String body = OkHttp.string(target, PARSE_TIMEOUT_MS);
                holder[0] = tryExtractRealUrl(body);
            } catch (Exception ignored) {
                // 网络错误/超时:视为该解析器失败
            } finally {
                latch.countDown();
            }
        }, "parser-core");
        t.setDaemon(true);
        t.start();
        try {
            // 等最多 PARSE_TIMEOUT_MS + 冗余缓冲
            if (!latch.await(PARSE_TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)) {
                t.interrupt();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return holder[0];
    }

    /** 从解析接口返回体里提取真实播放地址 */
    private String tryExtractRealUrl(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            // 解析接口通常返回 JSON { "url": "...", "parse": 1 } 或纯跳转 url
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                // 部分还回 { "msg": "http...", "url": "..." }
                String url = obj.optString("url", "");
                if (url.isEmpty()) url = obj.optString("msg", "");
                return url.isEmpty() ? null : url;
            }
            // 纯文本 URL(直接可播)
            if (isDirect(trimmed)) return trimmed;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 简单判定是否直链可播(常见视频/直播标签) */
    public static boolean isDirect(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        if (u.startsWith("http") && !u.contains("{")) return true; // 通用放宽
        // 常见后缀兜底
        String[] sufs = {".m3u8", ".mp4", ".flv", ".mkv", ".m4s", ".m3u"};
        for (String s : sufs) if (u.contains(s)) return true;
        return u.contains("json:");
    }

    /** 把 parseBean 列表(可能为 null/空)映射为解析器 URL 列表 */
    @SuppressWarnings("unused")
    public static List<String> urlsOf(JSONArray parseBeans) {
        List<String> out = new ArrayList<>();
        if (parseBeans == null) return out;
        for (int i = 0; i < parseBeans.length(); i++) {
            try {
                JSONObject o = parseBeans.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                int type = o.optInt("type", 1);
                if (!url.isEmpty() && (acceptable(url) || isAggregate(type))) {
                    out.add(url);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}