package com.github.tvbox.osc.util.parser;

import android.util.Base64;

import com.github.avbox.core.ParseHealth;
import com.github.catvod.crawler.SpiderDebug;
import com.github.tvbox.osc.util.HeaderGuard;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 并发解析，直到获得第一个结果。
 *
 * 提速改造(2026-10):
 *  1) 复用主 OkHttp 的连接池/调度器 —— 原来每次解析都 new 一个 OkHttpClient,
 *     等于每次都丢掉连接池,同批解析站要重新 TCP+TLS 握手;
 *  2) 精确取消 —— 原来用 dispatcher().cancelAll() 会连带取消 App 内其它正在跑的请求,
 *     现在只取消本次解析自己发出的 Call;
 *  3) 竞速顺序按解析站健康度排(快的/上次赢过的排前面),熔断中的站直接不发起;
 *  4) 线程数随解析站数量自适应(上限 12),不再固定 8 —— 站多的时候不会被队列拖长;
 *  5) 每个站的成功/失败/耗时回写 ParseHealth,越用越准。
 */
public class JsonParallel {

    /** 单个解析站的超时:4s 内不出结果即作废,不阻塞其它已可用的结果 */
    private static final long PARSE_TIMEOUT_MS = 4_000L;
    /** 并发上限 */
    private static final int MAX_THREADS = 12;
    /** 单次解析总闸:所有站加起来最多等这么久 */
    private static final long TOTAL_BUDGET_MS = 5_000L;

    /**
     * 当前在途解析任务；并发调用互不覆盖,新一轮 parse 启动时自动取消上一个在途任务。
     */
    private static volatile Task currentTask;

    /** 共享竞速线程池:常驻 daemon 线程,不再每次解析 new 一个池 */
    private static volatile ExecutorService sharedPool;

    private static ExecutorService sharedPool() {
        ExecutorService p = sharedPool;
        if (p == null) {
            synchronized (JsonParallel.class) {
                p = sharedPool;
                if (p == null) {
                    final AtomicInteger seq = new AtomicInteger(1);
                    ThreadFactory tf = new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "json-parse-" + seq.getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        }
                    };
                    p = Executors.newCachedThreadPool(tf);
                    sharedPool = p;
                }
            }
        }
        return p;
    }

    private static final class Task {
        final OkHttpClient client;
        final List<Future<JSONObject>> futures = Collections.synchronizedList(new ArrayList<Future<JSONObject>>());
        final List<Call> calls = Collections.synchronizedList(new ArrayList<Call>());

        Task() {
            OkHttpClient base = null;
            try {
                base = OkGoHelper.getDefaultClient();
            } catch (Throwable ignored) {
            }
            // newBuilder 出来的客户端与主客户端共享 ConnectionPool 和 Dispatcher:
            // 连接复用(keep-alive)得以保留,只覆盖"解析专用"的激进超时
            OkHttpClient.Builder builder = base != null ? base.newBuilder() : new OkHttpClient.Builder();
            client = builder
                    .connectTimeout(PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .writeTimeout(PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build();
        }

        void cancel() {
            // 只掐本次解析发出的请求。原来调 dispatcher().cancelAll() 是全局取消,
            // 会把 App 内其它正常请求(封面、配置、爬虫)一起打断,换成精确取消。
            List<Call> snapshot;
            synchronized (calls) {
                snapshot = new ArrayList<>(calls);
                calls.clear();
            }
            for (Call c : snapshot) {
                try {
                    if (c != null && !c.isCanceled()) c.cancel();
                } catch (Throwable ignored) {
                    LOG.d("JsonParallel", "cancel call failed");
                }
            }
            List<Future<JSONObject>> fs;
            synchronized (futures) {
                fs = new ArrayList<>(futures);
                futures.clear();
            }
            for (Future<JSONObject> future : fs) {
                try {
                    future.cancel(true);
                } catch (Throwable ignored) {
                    LOG.d("JsonParallel", "cancel in-flight future failed");
                }
            }
        }
    }

    public static JSONObject parse(LinkedHashMap<String, String> jx, String url) {
        if (jx == null || jx.isEmpty()) return new JSONObject();
        ParseHealth.ensureRestored();
        Task task = new Task();
        cancelTasks(); // 取消上一个在途任务，避免多轮解析并存
        currentTask = task;
        try {
            // 竞速顺序:先摘掉熔断中的站,再按"快、稳、上次赢过"排序
            List<String> names = ParseHealth.sort(ParseHealth.filterCooling(new ArrayList<>(jx.keySet())));
            if (names.isEmpty()) return new JSONObject();

            int threads = Math.min(names.size(), MAX_THREADS);
            ExecutorService pool = sharedPool();
            CompletionService<JSONObject> completionService = new ExecutorCompletionService<>(pool);
            List<Future<JSONObject>> submitted = new ArrayList<>(names.size());

            for (final String jxName : names) {
                final String parseUrl = jx.get(jxName);
                if (parseUrl == null) continue;
                submitted.add(completionService.submit(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() {
                        long t0 = System.currentTimeMillis();
                        boolean ok = false;
                        try {
                            // 获取请求头，并从中取出实际url
                            HashMap<String, String> reqHeaders = getReqHeader(parseUrl);
                            String realUrl = reqHeaders.get("url");
                            reqHeaders.remove("url");
                            Headers headers = Headers.of(reqHeaders);
                            Request request = new Request.Builder()
                                    .url(realUrl + url)
                                    .headers(headers)
                                    .tag("ParseTag")
                                    .build();

                            Call call = task.client.newCall(request);
                            task.calls.add(call);
                            Response response = call.execute();
                            try {
                                if (response.body() == null) return null;
                                String json = response.body().string();
                                JSONObject taskResult = Utils.jsonParse(url, json);
                                if (taskResult == null) return null;
                                taskResult.put("jxFrom", jxName);
                                ok = true;
                                return taskResult;
                            } finally {
                                try {
                                    response.close();
                                } catch (Throwable ignored) {
                                }
                                task.calls.remove(call);
                            }
                        } catch (Throwable th) {
                            // 单个解析站失败不影响其它站
                            return null;
                        } finally {
                            // 被本轮竞速取消(已有结果)的不记分,避免冤枉好站
                            if (!Thread.currentThread().isInterrupted() && currentTask == task) {
                                ParseHealth.report(jxName, ok, System.currentTimeMillis() - t0);
                            } else if (ok) {
                                ParseHealth.report(jxName, true, System.currentTimeMillis() - t0);
                            }
                        }
                    }
                }));
            }
            synchronized (task.futures) {
                task.futures.addAll(submitted);
            }

            JSONObject winner = null;
            long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
            for (int i = 0; i < submitted.size(); ++i) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                Future<JSONObject> completed;
                try {
                    completed = completionService.poll(remain, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (completed == null) break;
                try {
                    JSONObject r = completed.get();
                    if (r != null && r.optString("url", "").length() > 0) {
                        winner = r;
                        break; // 拿到第一个可用结果即返回,其余全部取消
                    }
                } catch (Throwable t) {
                    SpiderDebug.log(t);
                }
            }
            if (winner != null) return winner;
        } catch (Throwable th) {
            SpiderDebug.log(th);
        } finally {
            task.cancel();
            if (currentTask == task) currentTask = null;
        }
        return new JSONObject();
    }

    public static void cancelTasks() {
        Task task = currentTask;
        if (task != null) {
            task.cancel();
        }
    }

    public static HashMap<String, String> getReqHeader(String url) {
        HashMap<String, String> reqHeaders = new HashMap<>();
        reqHeaders.put("url", url);
        if (url.contains("cat_ext")) {
            try {
                int start = url.indexOf("cat_ext=");
                // cat_ext 可能是末参数，此时 indexOf("&") 返回 -1，取串尾兜底
                int end = url.indexOf("&", start);
                if (end == -1) end = url.length();
                String ext = url.substring(start + 8, end);
                ext = new String(Base64.decode(ext, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP));
                String newUrl = url.substring(0, start);
                if (end < url.length()) newUrl += url.substring(end + 1); // 跳过后续参数前的分隔符
                if (newUrl.endsWith("&") || newUrl.endsWith("?")) {
                    newUrl = newUrl.substring(0, newUrl.length() - 1); // cat_ext 是唯一 query 参数时去掉残留分隔符
                }
                JSONObject jsonObject = new JSONObject(ext);
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    if (headerJson != null) {
                        Iterator<String> keys = headerJson.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            String value = headerJson.optString(key, "");
                            // 聚合解析器的 ext 头来自配置:非法字符会让 Headers.of 抛 IAE,该解析器静默失效
                            if (!HeaderGuard.isSendable(key, value)) {
                                LOG.d("JsonParallel", "drop illegal header: " + key);
                                continue;
                            }
                            reqHeaders.put(key, value);
                        }
                    }
                }
                reqHeaders.put("url", newUrl);
            } catch (Throwable th) {
                LOG.d("JsonParallel", "cat_ext param decode failed, ignore extended headers");
            }
        }
        return reqHeaders;
    }
}
