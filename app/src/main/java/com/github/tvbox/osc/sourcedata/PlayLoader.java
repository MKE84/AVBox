package com.github.tvbox.osc.sourcedata;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.lifecycle.MutableLiveData;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.BoundedCall;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.google.gson.Gson;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 取流(playerContent)与取流结果的组装。
 *
 * <p>两条通道各自持有请求序号:{@code play} 与 {@code preload}(下一集预解析)互不作废 ——
 * 预载请求比真实播放先发、后到,共用一个序号会把真实播放的结果顶掉。
 * 序号在**调用线程**先占位(准备过程可能长时间阻塞),结果回投时比对序号丢弃过期响应。
 */
final class PlayLoader {
    private final Gson gson;
    private final ConcurrentHashMap<String, String> extendCache;
    private final MutableLiveData<JSONObject> playResult;
    private final MutableLiveData<JSONObject> preloadResult;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger playRequestSeq = new AtomicInteger();
    private final AtomicInteger preloadRequestSeq = new AtomicInteger();

    PlayLoader(Gson gson, ConcurrentHashMap<String, String> extendCache, MutableLiveData<JSONObject> playResult,
               MutableLiveData<JSONObject> preloadResult) {
        this.gson = gson;
        this.extendCache = extendCache;
        this.playResult = playResult;
        this.preloadResult = preloadResult;
    }

    // playerContent
    void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        getPlayInternal(playRequestSeq, playResult, "play", sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    /** 下一集预解析（预载方案）:结果走 preloadResult 通道,seq 独立于真实播放请求,互不作废 */
    void getPlayForPreload(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        getPlayInternal(preloadRequestSeq, preloadResult, "playPreload", sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    private void getPlayInternal(AtomicInteger seqHolder, MutableLiveData<JSONObject> resultChannel, String requestTag,
                                 String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        final int requestSeq = seqHolder.incrementAndGet();
        // 取流准备(t4 拉 extend、爬虫调度)可能长时间阻塞:序号先在调用线程占住,
        // 保证随后到来的取消/切集能作废这次请求,实际准备挪到后台
        if (Looper.myLooper() == Looper.getMainLooper()) {
            SourceHelper.PREPARE_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    getPlayPrepared(seqHolder, resultChannel, requestSeq, requestTag, sourceKey, playFlag, progressKey, url, subtitleKey);
                }
            });
            return;
        }
        getPlayPrepared(seqHolder, resultChannel, requestSeq, requestTag, sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    private void getPlayPrepared(AtomicInteger seqHolder, MutableLiveData<JSONObject> resultChannel, int requestSeq, String requestTag,
                                 String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        boolean pushFallback = PushUrlParser.isPushFallback(sourceKey, sourceBean);
        PushUrlParser.PushUrl pushUrl = pushFallback ? PushUrlParser.parsePushUrl(url) : PushUrlParser.createPushUrl(url);
        String requestUrl = pushUrl.url;
        if (pushFallback) {
            postPlayResult(seqHolder, resultChannel, requestSeq, PushUrlParser.createPushPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag));
            return;
        }
        if (sourceBean == null) {
            // 源已不存在(2026-09-13):与 getDetail 同因(切源窗口期/源被删);
            // 走 null 结果 = 取流失败,播放器按"解析失败"处理,不再在下面 NPE
            LOG.i("echo--getPlay--source-null--" + sourceKey);
            postPlayResult(seqHolder, resultChannel, requestSeq, null);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            playFromSpider(seqHolder, resultChannel, requestSeq, sourceBean, requestUrl, url, progressKey, subtitleKey, playFlag, pushUrl);
        } else if (type == 0 || type == 1) {
            playFromApi(seqHolder, resultChannel, requestSeq, sourceBean, requestUrl, url, progressKey, subtitleKey, playFlag, pushUrl);
        } else if (type == 4) {
            playFromExtendedApi(seqHolder, resultChannel, requestSeq, requestTag, sourceBean, requestUrl, url, progressKey, subtitleKey, playFlag, pushUrl);
        } else {
            postPlayResult(seqHolder, resultChannel, requestSeq, null);
        }
    }

    /** type 3:爬虫 playerContent;返回空或缺 url 时回退成直连(见 shouldDirectPlay) */
    private void playFromSpider(final AtomicInteger seqHolder, final MutableLiveData<JSONObject> resultChannel, final int requestSeq,
                                 final SourceBean sourceBean, final String requestUrl, final String url,
                                 final String progressKey, final String subtitleKey, final String playFlag,
                                 final PushUrlParser.PushUrl pushUrl) {
        SourceHelper.SPIDER_POOL.execute(new Runnable() {
            @Override
            public void run() {
                String json = BoundedCall.call(new Callable<String>() {
                    @Override
                    public String call() {
                        Spider sp = ApiConfig.get().getCSP(sourceBean);
                        if (TextUtils.isEmpty(requestUrl)) return "";
                        try {
                            LOG.i("echo--getPlay--id: " + requestUrl);
                            return sp.playerContent(playFlag, requestUrl, ApiConfig.get().getVipParseFlags());
                        } catch (Exception e) {
                            LOG.i("echo--getPlay--error: " + e.getMessage());
                            return "";
                        }
                    }
                }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getPlay--" + sourceBean.getKey());
                LOG.i("echo--getPlay--result:" + json);
                if (json == null) {
                    // BoundedCall 返回 null = 爬虫超时/异常无响应。给出可见原因,
                    // 便于用户截图定位"卡在正在获取播放信息"是超时还是爬虫无返回。
                    postPlayResult(seqHolder, resultChannel, requestSeq,
                            failInfo("取流超时(爬虫 " + sourceBean.getPlayTimeoutSeconds() + "s 无响应)"));
                    return;
                }
                if (TextUtils.isEmpty(json)) {
                    postPlayResult(seqHolder, resultChannel, requestSeq,
                            failInfo("取流返回空(爬虫未给出播放地址)"));
                    return;
                }
                try {
                    JSONObject result = normalizePlayerResult(new JSONObject(json));
                    result.put("key", url);
                    PushUrlParser.mergePushHeaders(result, pushUrl);
                    mergeSiteHeaders(result, sourceBean);
                    result.put("proKey", progressKey);
                    result.put("subtKey", subtitleKey);
                    if (!result.has("flag"))
                        result.put("flag", playFlag);
                    if (TextUtils.isEmpty(result.optString("url", "")) && shouldDirectPlay(sourceBean, requestUrl)) {
                        postPlayResult(seqHolder, resultChannel, requestSeq, createDirectPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag, sourceBean));
                    } else {
                        postPlayResult(seqHolder, resultChannel, requestSeq, result);
                    }
                } catch (Exception e) {
                    LOG.i("echo--getPlay--error: " + e.getMessage());
                    postPlayResult(seqHolder, resultChannel, requestSeq, null);
                }
            }
        });
    
    }

    /** type 0/1:直接按地址起播或交给解析链(parse=0/1 由地址形态与站点 playerUrl 决定) */
    private void playFromApi(final AtomicInteger seqHolder, final MutableLiveData<JSONObject> resultChannel, final int requestSeq,
                             final SourceBean sourceBean, final String requestUrl, final String url,
                             final String progressKey, final String subtitleKey, final String playFlag,
                             final PushUrlParser.PushUrl pushUrl) {
        JSONObject result = new JSONObject();
        try {
            result.put("key", url);
            String playUrl = sourceBean.getPlayerUrl().trim();
            if (DefaultConfig.isVideoFormat(requestUrl) && playUrl.isEmpty()) {
                result.put("parse", 0);
                result.put("url", requestUrl);
            } else {
                result.put("parse", 1);
                result.put("url", requestUrl);
            }
            PushUrlParser.mergePushHeaders(result, pushUrl);
            mergeSiteHeaders(result, sourceBean);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("playUrl", playUrl);
            result.put("flag", playFlag);
            postPlayResult(seqHolder, resultChannel, requestSeq, result);
        } catch (Throwable th) {
            LOG.e("SourceViewModel", th);
            postPlayResult(seqHolder, resultChannel, requestSeq, null);
        }
    
    }

    /** type 4:带 extend 的取流接口,结果走 normalizePlayerResult 归一 */
    private void playFromExtendedApi(final AtomicInteger seqHolder, final MutableLiveData<JSONObject> resultChannel, final int requestSeq, final String requestTag,
                                    final SourceBean sourceBean, final String requestUrl, final String url,
                                    final String progressKey, final String subtitleKey, final String playFlag,
                                    final PushUrlParser.PushUrl pushUrl) {
        String extend=sourceBean.getExt();
        extend=SourceHelper.getFixUrl(extendCache, gson, extend, sourceBean.getPlayTimeoutSeconds());

        GetRequest<String> request = SourceHelper.siteGet(sourceBean)
                .tag(requestTag)
                .params("play", requestUrl)
                .params("flag" ,playFlag);
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
                    String json = response.body();
                    LOG.i(json);
                    try {
                        JSONObject result = normalizePlayerResult(new JSONObject(json));
                        result.put("key", url);
                        PushUrlParser.mergePushHeaders(result, pushUrl);
                        mergeSiteHeaders(result, sourceBean);
                        result.put("proKey", progressKey);
                        result.put("subtKey", subtitleKey);
                        if (!result.has("flag"))
                            result.put("flag", playFlag);
                        postPlayResult(seqHolder, resultChannel, requestSeq, result);
                    } catch (Throwable th) {
                        LOG.e("SourceViewModel", th);
                        postPlayResult(seqHolder, resultChannel, requestSeq, null);
                    }
                }

                @Override
                public void onError(Response<String> response) {
                    super.onError(response);
                    postPlayResult(seqHolder, resultChannel, requestSeq, null);
                }
            });
    
    }

    void cancelPlayRequest() {
        playRequestSeq.incrementAndGet();
    }

    /** 取流失败时给一个带 msg 的结果:PlaybackFetch.handlePlayResult 会把 msg 显示到界面,便于用户定位原因 */
    private JSONObject failInfo(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg", msg);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldDirectPlay(SourceBean sourceBean, String requestUrl) {
        return sourceBean != null
                && !TextUtils.isEmpty(requestUrl)
                && (requestUrl.startsWith("http://") || requestUrl.startsWith("https://"));
    }

    private JSONObject createDirectPlayResult(String rawUrl, PushUrlParser.PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag, SourceBean sourceBean) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("jx", 0);
            result.put("url", pushUrl.url);
            PushUrlParser.mergePushHeaders(result, pushUrl);
            mergeSiteHeaders(result, sourceBean);
            LOG.i("echo--getPlay--direct:" + pushUrl.url);
            return result;
        } catch (Throwable th) {
            LOG.e("SourceViewModel", th);
            return null;
        }
    }

    private JSONObject normalizePlayerResult(JSONObject result) {
        if (result == null) return null;
        try {
            String playUrl = result.optString("playUrl", "");
            String url = result.optString("url", "");
            if (TextUtils.isEmpty(url)) return result;
            if (url.startsWith("[") && url.endsWith("]")) {
                JSONArray array = new JSONArray(url);
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (item instanceof String) {
                        String str = (String) item;
                        if (str.startsWith("proxy://")) {
                            str = DefaultConfig.checkReplaceProxy(str);
                            array.put(i, str);
                        } else if (str.startsWith("video://")) {
                            str = str.substring(8);
                            array.put(i, str);
                        }
                    }
                }
                result.put("url", array.toString());
                result.put("parse", 0);
                return result;
            }
            if (url.startsWith("video://")) {
                url = url.substring(8);
                result.put("url", url);
                result.put("parse", 1);
            } else if (url.startsWith("proxy://")) {
                url = DefaultConfig.checkReplaceProxy(url);
                result.put("url", url);
                result.put("parse", 0);
            } else if (playUrl.length() == 0
                    && DefaultConfig.isVideoFormat(url)
                    && !result.has("parse")
                    && !result.has("jx")) {
                result.put("parse", 0);
            }
        } catch (Throwable th) {
            LOG.e("SourceViewModel", th);
        }
        return result;
    }

    private void mergeSiteHeaders(JSONObject result, SourceBean sourceBean) {
        if (result == null || sourceBean == null) return;
        Map<String, String> siteHeader = sourceBean.getHeader();
        if (siteHeader.isEmpty()) return;
        try {
            // 必须先按播放侧的同一口径解析(兼容 header/headers 的对象与 JSON 文本两种形态):
            // 直接看 optJSONObject 会把字符串形态当成"没有头",把源自带的头整块覆盖掉
            HashMap<String, String> merged = PlayerHelper.extractPlayHeaders(result);
            if (merged == null) merged = new HashMap<>();
            for (Map.Entry<String, String> entry : siteHeader.entrySet()) {
                if (!merged.containsKey(entry.getKey())) merged.put(entry.getKey(), entry.getValue());
            }
            JSONObject header = new JSONObject();
            for (Map.Entry<String, String> entry : merged.entrySet()) header.put(entry.getKey(), entry.getValue());
            result.put("header", header);
            // 合并结果统一放 header 一个键,避免 header/headers 两份来源被重复抽取
            result.remove("headers");
        } catch (Throwable th) {
            LOG.e("SourceViewModel", "merge site headers failed", th);
        }
    }

    /**
     * 结果归属判定:序号已被后续请求(取消/切集/下一次预载)顶掉 ⇒ 这条结果作废。
     * 抽成纯判定是为了让"双通道序号互不作废"这条不变量可被单测锁住(见 PlayLoaderSeqTest)。
     */
    static boolean isStaleResult(int requestSeq, AtomicInteger seqHolder) {
        return requestSeq != seqHolder.get();
    }

    private void postPlayResult(AtomicInteger seqHolder, MutableLiveData<JSONObject> resultChannel, int requestSeq, JSONObject result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isStaleResult(requestSeq, seqHolder)) {
                    LOG.i("echo--getPlay--ignore stale result");
                    return;
                }
                resultChannel.setValue(result);
            }
        });
    }

}
