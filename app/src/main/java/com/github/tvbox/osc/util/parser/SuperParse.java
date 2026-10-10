package com.github.tvbox.osc.util.parser;

import android.util.Base64;

import com.github.avbox.core.DirectParse;
import com.github.avbox.core.ParseHealth;
import com.github.catvod.crawler.SpiderDebug;
import com.github.tvbox.osc.util.LOG;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicInteger;

public class SuperParse {
    // BugReview #21:loadHtml 仅读、parse 写,改并发容器防 HashMap 并发写损坏
    public static final ConcurrentHashMap<String, ArrayList<String>> flagWebJx = new ConcurrentHashMap<>();
    private static HashMap<String, ArrayList<String>> configs = null;
    private static final Object configsLock = new Object();

    /**
     * 一次解析会话的目标解析器。
     * BugReview #21:原实现把 jsonJx/webJx 存静态字段、doJsonJx(url) 跨线程读取,
     * 并发解析互相覆盖走错解析接口;改为构建后按调用传递(构建后只读,线程安全)。
     */
    public static final class ParseTargets {
        public final LinkedHashMap<String, String> jsonJx;
        public final ArrayList<String> webJx;
        /** type=0 解析站 名字→地址(直出快速路的竞速目标) */
        public final LinkedHashMap<String, String> webJxNamed = new LinkedHashMap<>();
        /** type=0 解析站 名字→ext(含 header,直出时按站带 UA/Referer,防盗链站也能出地址) */
        public final LinkedHashMap<String, String> webJxExt = new LinkedHashMap<>();

        ParseTargets(LinkedHashMap<String, String> jsonJx, ArrayList<String> webJx) {
            this.jsonJx = jsonJx;
            this.webJx = webJx;
        }
    }

    /** 并行竞速用的小线程池:两个 racer(json 解析 / 直出解析)各占一条,常驻不新建 */
    private static volatile ExecutorService racePool;

    private static ExecutorService racePool() {
        ExecutorService p = racePool;
        if (p == null) {
            synchronized (SuperParse.class) {
                p = racePool;
                if (p == null) {
                    final AtomicInteger seq = new AtomicInteger(1);
                    ThreadFactory tf = new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "superparse-race-" + seq.getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        }
                    };
                    p = Executors.newFixedThreadPool(4, tf);
                    racePool = p;
                }
            }
        }
        return p;
    }

    private static void ensureConfigs(LinkedHashMap<String, HashMap<String, String>> jx) {
        synchronized (configsLock) {
            if (configs != null) return;
            HashMap<String, ArrayList<String>> built = new HashMap<>();
            for (Map.Entry<String, HashMap<String, String>> entry : jx.entrySet()) {
                String key = entry.getKey();
                HashMap<String, String> parseBean = entry.getValue();
                if (parseBean == null) {
                    continue;
                }
                String type = parseBean.get("type");
                if (type == null) {
                    continue;
                }
                if ("1".equals(type) || "0".equals(type)) {
                    try {
                        String ext = parseBean.get("ext");
                        if (ext == null) {
                            continue;
                        }
                        JSONArray flagsArray = new JSONObject(ext).getJSONArray("flag");
                        for (int j = 0; j < flagsArray.length(); j++) {
                            String flagKey = flagsArray.getString(j);
                            ArrayList<String> flagJx = built.get(flagKey);
                            if (flagJx == null) {
                                flagJx = new ArrayList<>();
                                built.put(flagKey, flagJx);
                            }
                            flagJx.add(key);
                        }
                    } catch (Exception e) {
                        SpiderDebug.log(e);
                    }
                }
            }
            configs = built;
        }
    }

    /**
     * 把单个解析站按类型收进目标表。
     * type=1 → json 聚合(走 HTTP 拿 JSON);
     * type=0 → 拼 URL 型,既进 WebView 嗅探队列,也进直出快速路队列。
     */
    private static void collect(Map.Entry<String, HashMap<String, String>> entry,
                                LinkedHashMap<String, String> jsonJx,
                                ArrayList<String> webJx,
                                LinkedHashMap<String, String> webJxNamed,
                                LinkedHashMap<String, String> webJxExt) {
        String key = entry.getKey();
        HashMap<String, String> parseBean = entry.getValue();
        if (parseBean == null) {
            return;
        }
        String type = parseBean.get("type");
        String urlValue = parseBean.get("url");
        if (type == null || urlValue == null || urlValue.isEmpty()) {
            return;
        }
        if ("1".equals(type)) {
            String ext = parseBean.get("ext");
            if (ext != null) {
                jsonJx.put(key, mixUrl(urlValue, ext));
            }
        } else if ("0".equals(type)) {
            webJx.add(urlValue);
            webJxNamed.put(key, urlValue);
            String ext = parseBean.get("ext");
            if (ext != null && !ext.trim().isEmpty()) {
                webJxExt.put(key, ext);
            }
        }
    }

    /** 构建本次解析会话的目标解析器(json 聚合 + web 嗅探 + 直出快速路) */
    public static ParseTargets buildTargets(LinkedHashMap<String, HashMap<String, String>> jx, String flag) {
        ensureConfigs(jx);
        LinkedHashMap<String, String> jsonJx = new LinkedHashMap<>();
        ArrayList<String> webJx = new ArrayList<>();
        LinkedHashMap<String, String> webJxNamed = new LinkedHashMap<>();
        LinkedHashMap<String, String> webJxExt = new LinkedHashMap<>();
        List<String> targetKeys = configs.get(flag);
        if (targetKeys != null && !targetKeys.isEmpty()) {
            for (String key : targetKeys) {
                HashMap<String, String> parseBean = jx.get(key);
                if (parseBean == null) continue;
                collect(new java.util.AbstractMap.SimpleEntry<>(key, parseBean), jsonJx, webJx, webJxNamed, webJxExt);
            }
        } else {
            for (Map.Entry<String, HashMap<String, String>> entry : jx.entrySet()) {
                collect(entry, jsonJx, webJx, webJxNamed, webJxExt);
            }
        }
        ParseTargets targets = new ParseTargets(jsonJx, webJx);
        targets.webJxNamed.putAll(webJxNamed);
        targets.webJxExt.putAll(webJxExt);
        return targets;
    }

    public static JSONObject parse(LinkedHashMap<String, HashMap<String, String>> jx, String flag, String url) {
        return parse(jx, flag, url, buildTargets(jx, flag));
    }

    public static JSONObject parse(LinkedHashMap<String, HashMap<String, String>> jx, String flag, String url, ParseTargets targets) {
        // 0) 短缓存命中:同一集重播 / 切集切回来直接秒开,连 WebView 都不用建
        JSONObject cached = ParseResultCache.get(flag, url);
        if (cached != null && cached.optString("url", "").length() > 0) {
            LOG.i("echo-superparse-cache-hit");
            return cached;
        }
        try {
            if (!targets.webJx.isEmpty()) {
                // 嗅探队列按健康度排序:快的、稳的站排在前面,iframe 更早吐地址
                ArrayList<String> ordered;
                try {
                    ordered = new ArrayList<>(ParseHealth.sort(targets.webJx, flag));
                } catch (Throwable th) {
                    ordered = targets.webJx;
                }
                flagWebJx.put(flag, ordered);
                JSONObject webResult = new JSONObject();
                webResult.put("url", "proxy://go=SuperParse&flag=" + flag + "&url=" + Base64.encodeToString(url.getBytes(), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP));
                webResult.put("parse", 1);
                webResult.put("ua", Utils.UaWinChrome);
                return webResult;
            }
        } catch (Exception e) {
            LOG.i("echo-result" + e.getMessage());
        }
        return new JSONObject();
    }

    public static JSONObject doJsonJx(LinkedHashMap<String, String> json_jxs, String url) {
        LOG.i("echo-jsonJx1" + json_jxs.toString());
        return JsonParallel.parse(json_jxs, url);
    }

    /**
     * 超级解析的并行段:json 聚合 与 HTTP 直出**同时开跑**,谁先出地址用谁。
     *
     * 设计要点:直出快速路与 WebView 嗅探是并行的两条腿,不互相等 ——
     * WebView 照常在 parse() 返回后立刻开始加载,直出这边同步竞速,
     * 直出先撞到地址就立刻起播并关掉嗅探页(不需要等站点自己的播放器跑起来),
     * 直出没撞到就完全不影响原链路。相比"先等直出、失败再嗅探"没有额外等待。
     */
    public static JSONObject doRaceJx(final ParseTargets targets, final String url, final String flag) {
        if (targets == null) return new JSONObject();
        final boolean hasJson = targets.jsonJx != null && !targets.jsonJx.isEmpty();
        final boolean hasWeb = targets.webJxNamed != null && !targets.webJxNamed.isEmpty();
        if (!hasJson && !hasWeb) return new JSONObject();
        if (!hasJson) {
            JSONObject r = DirectParse.race(targets.webJxNamed, targets.webJxExt, url, flag, DirectParse.DEFAULT_BUDGET_MS);
            if (r != null && r.optString("url", "").length() > 0) {
                ParseResultCache.put(flag, url, r);
                return r;
            }
            return new JSONObject();
        }
        if (!hasWeb) {
            JSONObject r = JsonParallel.parse(targets.jsonJx, url);
            if (r != null && r.optString("url", "").length() > 0) {
                ParseResultCache.put(flag, url, r);
                return r;
            }
            return new JSONObject();
        }
        CompletionService<JSONObject> cs = new ExecutorCompletionService<>(racePool());
        List<Future<JSONObject>> futures = new ArrayList<>(2);
        try {
            futures.add(cs.submit(new Callable<JSONObject>() {
                @Override
                public JSONObject call() {
                    return DirectParse.race(targets.webJxNamed, targets.webJxExt, url, flag, DirectParse.DEFAULT_BUDGET_MS);
                }
            }));
            futures.add(cs.submit(new Callable<JSONObject>() {
                @Override
                public JSONObject call() {
                    return JsonParallel.parse(targets.jsonJx, url);
                }
            }));
            // json 侧最慢 4s,直出侧最慢 3.2s;留一点余量总闸 4.8s
            long deadline = System.currentTimeMillis() + 4_800L;
            for (int i = 0; i < futures.size(); i++) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                Future<JSONObject> done;
                try {
                    done = cs.poll(remain, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (done == null) break;
                JSONObject r;
                try {
                    r = done.get();
                } catch (Throwable th) {
                    continue;
                }
                if (r != null && r.optString("url", "").length() > 0) {
                    ParseResultCache.put(flag, url, r);
                    cancelAll(futures);
                    return r;
                }
            }
        } catch (Throwable th) {
            SpiderDebug.log(th);
        } finally {
            cancelAll(futures);
        }
        return new JSONObject();
    }

    private static void cancelAll(List<Future<JSONObject>> futures) {
        for (Future<JSONObject> f : futures) {
            try {
                if (!f.isDone()) f.cancel(true);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void stopJsonJx() {
        JsonParallel.cancelTasks();
    }

    /** 清空解析结果短缓存(用户点重试 / 换解析器时调用,保证重试真的会重新解析) */
    public static void clearResultCache() {
        ParseResultCache.clear();
    }

    private static String mixUrl(String url, String ext) {
        if (ext.trim().length() > 0) {
            int idx = url.indexOf("?");
            if (idx > 0) {
                return url.substring(0, idx + 1) + "cat_ext=" + Base64.encodeToString(ext.getBytes(), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP) + "&" + url.substring(idx + 1);
            }
        }
        return url;
    }

    public static Object[] loadHtml(String flag, String url) {
        try {
            url = new String(Base64.decode(url, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
            String html = "\n" +
                    "<!doctype html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "<title>解析</title>\n" + // i18n: keep(注入 HTML 片段)
                    "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n" +
                    "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=EmulateIE10\" />\n" +
                    "<meta name=\"renderer\" content=\"webkit|ie-comp|ie-stand\">\n" +
                    "<meta name=\"viewport\" content=\"width=device-width\">\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "<script>\n" +
                    "var apiArray=[#jxs#];\n" +
                    "var urlPs=\"#url#\";\n" +
                    "var iframeHtml=\"\";\n" +
                    "for(var i=0;i<apiArray.length;i++){\n" +
                    "var URL=apiArray[i]+urlPs;\n" +
                    "iframeHtml=iframeHtml+\"<iframe sandbox='allow-scripts allow-same-origin allow-forms' frameborder='0' allowfullscreen='true' webkitallowfullscreen='true' mozallowfullscreen='true' src=\"+URL+\"></iframe>\";\n" +
                    "}\n" +
                    "document.write(iframeHtml);\n" +
                    "</script>\n" +
                    "</body>\n" +
                    "</html>";

            StringBuilder jxs = new StringBuilder();
            if (flagWebJx.containsKey(flag)) {
                ArrayList<String> jxUrls = flagWebJx.get(flag);
                for (int i = 0; i < jxUrls.size(); i++) {
                    jxs.append("\"");
                    jxs.append(jxUrls.get(i));
                    jxs.append("\"");
                    if (i < jxUrls.size() - 1) {
                        jxs.append(",");
                    }
                }
            }
            html = html.replace("#url#", url).replace("#jxs#", jxs.toString());
            Object[] result = new Object[3];
            result[0] = 200;
            result[1] = "text/html; charset=\"UTF-8\"";
            ByteArrayInputStream baos = new ByteArrayInputStream(html.toString().getBytes("UTF-8"));
            result[2] = baos;
            return result;
        } catch (Throwable th) {
            LOG.e("SuperParse", th);
        }
        return null;
    }
}
