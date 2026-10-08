package com.github.tvbox.osc.sourcedata;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.request.GetRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 站点取数的公共支撑:线程池、站点级请求构造、豆瓣源识别、extend 解析、结果来源标注。
 *
 * <p>这些能力被多个 Loader 共用,放在这里而不是各自复制;extend 解析缓存归
 * {@link SourceRuntimeState},由调用方把自己的缓存传进来。
 */
final class SourceHelper {
    private SourceHelper() {
    }

    static final ExecutorService SPIDER_POOL = Executors.newFixedThreadPool(4); // 2026-09-11:单线程改 3 线程——原单线程被卡死的 spider 任务(不响应 interrupt)永久占用后,后续全部任务排队,首页永久骨架屏
    /** 取流专用池:与列表/详情/预载的爬虫任务隔离。取流是最关键的一跳,
     *  绝不能被其它卡死的爬虫任务(不响应 interrupt)挤在队列里 → 否则"正在获取播放信息"永久卡、
     *  且 15s 看护在任务内部,任务不启动永远不会超时 → 永远不弹错。 */
    static final ExecutorService PLAY_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "play-fetch");
        t.setDaemon(true);
        return t;
    });
    static final ExecutorService PREPARE_POOL = Executors.newFixedThreadPool(3);

    /** i18n: keep —— 只进日志(convertResponse → onError → LOG.i),无 UI 出口 */
    static final String ERR_NETWORK = "网络请求错误";

    /**
     * type 0/1/4 接口请求统一入口:带上站点级 header(fongmi 的 sites[].header)。
     * spider 的请求走 jar 内自有网络栈,注入不进去(fongmi 官方也标注 type 3 不套用)。
     */
    static GetRequest<String> siteGet(SourceBean sourceBean) {
        GetRequest<String> request = OkGo.<String>get(sourceBean.getApi());
        for (Map.Entry<String, String> entry : sourceBean.getHeader().entrySet()) {
            request.headers(entry.getKey(), entry.getValue());
        }
        return request;
    }

    static boolean isDoubanSource(SourceBean sourceBean) {
        if (sourceBean == null) return false;
        return containsDouban(sourceBean.getKey())
                || containsDouban(sourceBean.getName())
                || containsDouban(sourceBean.getApi())
                || containsDouban(sourceBean.getExt());
    }

    private static boolean containsDouban(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String lower = value.toLowerCase();
        return lower.contains("douban") || value.contains("\u8c46\u74e3");
    }

    /** 首页源判定:兜底源可能不是列表第 0 项(第 0 项被标 hide 时会往后挑),所以比首页源 key 而不是下标 0 */
    static boolean isHomeSource(String sourceKey) {
        return !TextUtils.isEmpty(sourceKey) && sourceKey.equals(ApiConfig.get().getHomeSourceBean().getKey());
    }

    static void absXml(AbsXml data, String sourceKey) {
        absXml(data, sourceKey, "");
    }

    static void absXml(AbsXml data, String sourceKey, String searchToken) {
        data.sourceKey = sourceKey;
        data.searchToken = searchToken;
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                        for (String s : str) {
                            String[] ss = s.split("\\$", 2);
                            if (ss.length > 0) {
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                } else {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                video.sourceKey = sourceKey;
            }
        }
    }

    /**
     * extend 解析:本地 127.0.0.1 走文件,其余走网络,结果压成单行 JSON 后进缓存。
     * 超时返回原值(不是空串),否则站点会收到被清空的 extend。
     */
    static String getFixUrl(final ConcurrentHashMap<String, String> extendCache, final Gson gson, final String extend, final long timeoutSeconds) {
        if (TextUtils.isEmpty(extend)) return "";
        if(!extend.startsWith("http"))return extend;
        final String key = MD5.string2MD5(extend);
        if (extendCache.containsKey(key)) {
            LOG.i("echo-getFixUrl Cache");
            return extendCache.get(key);
        }
        Future<String> future = SPIDER_POOL.submit(new Callable<String>() {
            @Override
            public String call() {
                String result = extend;
                if (extend.startsWith("http://127.0.0.1")) {
                    String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                    path = path.replaceAll("localhost/", "/");
                    result = FileUtils.readFileToString(path, "UTF-8");
                    result = tryMinifyJson(gson, result);
                    extendCache.putIfAbsent(key, result);
                } else if (extend.startsWith("http")) {
                    result = OkHttp.string(extend, null);
                    if (!result.isEmpty()) {
                        result = tryMinifyJson(gson, result);
                        if(result.length()>2500)result = extend;
                        extendCache.putIfAbsent(key, result);
                    }
                }
                return result;
            }
        });

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            LOG.e("SourceViewModel", te);
            future.cancel(true);
            return extend;
        } catch (Exception e) {
            LOG.e("SourceViewModel", e);
            return extend;
        }
    }

    /** 同 {@link #getFixUrl},但直接在当前线程取(调用方已在后台线程时用) */
    static String getFixUrlDirect(final ConcurrentHashMap<String, String> extendCache, Gson gson, final String extend) {
        if (TextUtils.isEmpty(extend)) return "";
        if (!extend.startsWith("http")) return extend;
        final String key = MD5.string2MD5(extend);
        if (extendCache.containsKey(key)) {
            return extendCache.get(key);
        }
        String result = extend;
        try {
            if (extend.startsWith("http://127.0.0.1")) {
                String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                path = path.replaceAll("localhost/", "/");
                result = FileUtils.readFileToString(path, "UTF-8");
                result = tryMinifyJson(gson, result);
                extendCache.putIfAbsent(key, result);
            } else {
                result = OkHttp.string(extend, null);
                if (!TextUtils.isEmpty(result)) {
                    result = tryMinifyJson(gson, result);
                    if (result.length() > 2500) result = extend;
                    extendCache.putIfAbsent(key, result);
                }
            }
        } catch (Throwable th) {
            LOG.e("SourceViewModel", th);
            return extend;
        }
        return result;
    }

    private static String tryMinifyJson(Gson gson, String raw) {
        try {
            raw = raw.trim();
            JsonElement jsonElement = JsonParser.parseString(raw);
            return gson.toJson(jsonElement);
        } catch (Exception e) {
            return raw;
        }
    }
}
