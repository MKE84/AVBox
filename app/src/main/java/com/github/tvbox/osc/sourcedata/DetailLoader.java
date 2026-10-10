package com.github.tvbox.osc.sourcedata;

import android.os.Looper;
import android.util.Base64;

import androidx.lifecycle.MutableLiveData;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.BoundedCall;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 详情取数(detailContent)。
 *
 * <p>三类特殊入口都在这里:推送链接(push://→ 本地合成详情)、源已失效(返回空详情走空态,
 * 不在调用方 NPE)、换源回退(fallback,超时收紧到 6s)。
 */
final class DetailLoader {
    private final Gson gson;
    private final ConcurrentHashMap<String, String> extendCache;
    private final MutableLiveData<AbsXml> detailResult;
    private final SourceResultParser resultParser;

    DetailLoader(Gson gson, ConcurrentHashMap<String, String> extendCache, MutableLiveData<AbsXml> detailResult,
                 SourceResultParser resultParser) {
        this.gson = gson;
        this.extendCache = extendCache;
        this.detailResult = detailResult;
        this.resultParser = resultParser;
    }

    // detailContent
    void getDetail(String sourceKey, String urlid) {
        getDetail(sourceKey, urlid, false);
    }

    void getDetail(String sourceKey, String urlid, boolean fallback) {
        getDetail(sourceKey, urlid, fallback, null);
    }

    /**
     * @param requestToken 详情代次(V4):回包原样带回去,由页面判"是否属于当前这一代";
     *                     null = 不判代次(老调用点)。
     */
    void getDetail(String sourceKey, String urlid, boolean fallback, final Integer requestToken) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 同 getSort:t0/1/4 的 extend 拉取会阻塞
            final String key = sourceKey;
            final String id = urlid;
            SourceHelper.PREPARE_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    getDetail(key, id, fallback, requestToken);
                }
            });
            return;
        }
        if (urlid.startsWith("push://") && ApiConfig.get().getSource(PushUrlParser.PUSH_AGENT) != null) {
            String pushUrl = urlid.substring(7);
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    LOG.e("SourceViewModel", e);
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl);
            }
            sourceKey = PushUrlParser.isCastPushUrl(pushUrl) ? PushUrlParser.PUSH_FALLBACK : PushUrlParser.PUSH_AGENT;
            urlid = pushUrl;
        } else if (PushUrlParser.PUSH_AGENT.equals(sourceKey) && PushUrlParser.isCastPushUrl(urlid)) {
            sourceKey = PushUrlParser.PUSH_FALLBACK;
        }
        String id = urlid;
    
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (PushUrlParser.isPushFallback(sourceKey, sourceBean)) {
            detailResult.postValue(createPushDetail(urlid, sourceKey, requestToken));
            return;
        }
        if (sourceBean == null) {
            // 源已不存在(2026-09-13):典型场景 = 切到新源后加载完成前,从历史记录点进
            // 一条属于旧源(已失效 key)的条目;或订阅源被删。此处返回空 AbsXml(与末尾
            // 未知 type 分支同形状),详情页走空态,而不是在下面 sourceBean.getType() 处 NPE
            LOG.i("echo--getDetail--source-null--" + sourceKey);
            detailResult.postValue(createEmptyDetail(sourceKey, requestToken));
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            getDetailFromSpider(sourceBean, id, fallback, requestToken);
        } else if (type == 0 || type == 1|| type == 4) {
            getDetailFromApi(sourceBean, id, fallback, requestToken);
        } else {
            detailResult.postValue(createEmptyDetail(sourceKey, requestToken));
        }
    }


    /** type 3:爬虫 detailContent;换源回退(fallback)时超时收紧 */
    private void getDetailFromSpider(final SourceBean sourceBean, final String id, final boolean fallback, final Integer requestToken) {
        
        SourceHelper.SPIDER_POOL.execute(new Runnable() {
            @Override
            public void run() {
                String json = BoundedCall.call(new Callable<String>() {
                    @Override
                    public String call() {
                        List<String> ids = new ArrayList<>();
                        ids.add(id);
                        // getCSP 与 detailContent 都可能抛 Error(爬虫 <clinit> / 伪造 .so 加载):
                        // 必须一并接住,否则 Error 穿透 Callable → 虽被 BoundedCall 的 future.get()
                        // 包成 ExecutionException,但 getCSP 在旧版位于 try 外时一旦抛 Error,
                        // 空 Spider 被后续引用才崩,错位难查。catch Throwable 一次兜死。
                        try {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.detailContent(ids);
                        } catch (Throwable t) {
                            LOG.i("echo--getDetail--error: " + t);
                            return "";
                        }
                    }
                }, fallback ? 6_000L : sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getDetail--" + sourceBean.getKey());
//                    LOG.i("echo--getDetail--result:" + json);
                resultParser.json(detailResult, json, sourceBean.getKey(), "", requestToken);
            }
        });
    
    }

    /** type 0/1/4:站点接口(带 extend);type 0 走 XML */
    private void getDetailFromApi(final SourceBean sourceBean, final String id, final boolean fallback, final Integer requestToken) {
        // 回调里要按 type 分流 xml/json,值语义与调用点一致(原为捕获上层局部量)
        final int type = sourceBean.getType();
        
        String extend=sourceBean.getExt();
        extend=fallback ? SourceHelper.getFixUrl(extendCache, gson, extend, 6) : SourceHelper.getFixUrl(extendCache, gson, extend, sourceBean.getPlayTimeoutSeconds());

        GetRequest<String> request = SourceHelper.siteGet(sourceBean)
                .tag("detail")
                .params("ac", type == 0 ? "videolist" : "detail")
                .params("ids", id);
        // 当 extend 不为空且非空字符串时添加参数
        if (extend != null && !extend.isEmpty()) {
            request.params("extend", extend);
        }
        request.execute(new AbsCallback<String>() {

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException(SourceHelper.ERR_NETWORK);
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        if (type == 0) {
                            String xml = response.body();
                            resultParser.xml(detailResult, xml, sourceBean.getKey(), "", requestToken);
                        } else {
                            String json = response.body();
                            LOG.i(json);
                            resultParser.json(detailResult, json, sourceBean.getKey(), "", requestToken);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        resultParser.json(detailResult, "", sourceBean.getKey(), "", requestToken);
                    }
                });
    
    }

    /** 空详情(源不存在 / 未知 type):详情页按空态渲染,不带任何影片数据 */
    private static AbsXml createEmptyDetail(String sourceKey, Integer requestToken) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        data.detailToken = requestToken;
        return data;
    }

    private AbsXml createPushDetail(String url, String sourceKey, Integer requestToken) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        data.detailToken = requestToken;
        Movie movie = new Movie();
        movie.videoList = new ArrayList<>();
        Movie.Video video = new Movie.Video();
        video.id = url;
        video.name = url;
        // i18n: keep —— 以下是合成 Movie 的结构化数据(type/flag/`线路名$地址` 格式),会被持久化与比较,不能翻
        video.type = "推送";
        video.sourceKey = sourceKey;
        video.urlBean = new Movie.Video.UrlBean();
        video.urlBean.infoList = new ArrayList<>();
        Movie.Video.UrlBean.UrlInfo urlInfo = new Movie.Video.UrlBean.UrlInfo();
        urlInfo.flag = "推送"; // i18n: keep
        urlInfo.urls = "播放$" + url; // i18n: keep
        urlInfo.beanList = new ArrayList<>();
        urlInfo.beanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean("播放", url)); // i18n: keep
        video.urlBean.infoList.add(urlInfo);
        movie.videoList.add(video);
        data.movie = movie;
        return data;
    }

    /**
     * 站点级 header 作为播放请求的兜底头(fongmi 同语义):只补结果里没有的键,结果自带的头优先。
     * 没配 header 的源这里是空操作。
     */
}
