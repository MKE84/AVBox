package com.github.tvbox.osc.sourcedata;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.RemoteTVBox;
import com.github.tvbox.osc.util.BoundedCall;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;

/**
 * 首页取数:站点分类(sort/分类列表)与首页推荐位。
 *
 * <p>带 homeContent 缓存(最多 5 个源),命中的判定与写入条件都在这里;缓存本体归
 * {@link SourceRuntimeState},这里只拿引用,便于统一清理。
 */
final class SortLoader {
    private final Gson gson;
    private final ConcurrentHashMap<String, String> extendCache;
    private final Map<String, AbsSortXml> sortCache;
    private final MutableLiveData<AbsSortXml> sortResult;
    private final ListLoader listLoader;
    private final SourceResultParser resultParser;

    SortLoader(Gson gson, ConcurrentHashMap<String, String> extendCache, Map<String, AbsSortXml> sortCache,
               MutableLiveData<AbsSortXml> sortResult, ListLoader listLoader, SourceResultParser resultParser) {
        this.gson = gson;
        this.extendCache = extendCache;
        this.sortCache = sortCache;
        this.sortResult = sortResult;
        this.listLoader = listLoader;
        this.resultParser = resultParser;
    }

    private void cacheSort(String sourceKey, AbsSortXml sortXml) {
        attachSortSource(sourceKey, sortXml);
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (!hasHomeRecVideos(sortXml)) {
            return;
        }
        if (!shouldBypassSortCache(sourceKey, sourceBean) && !hasActionSort(sortXml)) {
            synchronized (sortCache) {
                sortCache.put(sourceKey, sortXml);
            }
        }
    }

    private static AbsSortXml attachSortSource(String sourceKey, AbsSortXml sortXml) {
        if (sortXml != null) {
            sortXml.sourceKey = sourceKey;
        }
        return sortXml;
    }

    private void postSortResult(String sourceKey, AbsSortXml sortXml) {
        if (sortXml == null) {
            sortXml = new AbsSortXml();
        }
        sortResult.postValue(attachSortSource(sourceKey, sortXml));
    }

    /** 分类取数失败出口:空包 + loadFailed 标记,由 HomeViewModel 决定重试/错误态 */
    private void postSortFailure(String sourceKey) {
        AbsSortXml sortXml = new AbsSortXml();
        sortXml.loadFailed = true;
        sortResult.postValue(attachSortSource(sourceKey, sortXml));
    }

    private static boolean hasActionSort(AbsSortXml sortXml) {
        if (sortXml == null) return false;
        if (hasActionVideo(sortXml.videoList)) return true;
        return sortXml.list != null && hasActionVideo(sortXml.list.videoList);
    }

    private static boolean hasHomeRecVideos(AbsSortXml sortXml) {
        return sortXml != null && sortXml.videoList != null && !sortXml.videoList.isEmpty();
    }

    private static boolean hasActionVideo(List<Movie.Video> videos) {
        if (videos == null) return false;
        for (Movie.Video video : videos) {
            if (video != null && video.action != null) return true;
        }
        return false;
    }

    private static boolean shouldBypassSortCache(String sourceKey, SourceBean sourceBean) {
        return SourceHelper.isHomeSource(sourceKey) && SourceHelper.isDoubanSource(sourceBean);
    }

    // homeContent
    void getSort(final String sourceKey) {
        getSort(sourceKey, true);
    }

    /** withRec=false 跳过首页推荐那一次额外请求(豆瓣类 videolist / spider homeVideoContent),sorts 不必等它 */
    void getSort(final String sourceKey, final boolean withRec) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // t4 源要联网拉 extend 才能发 sort 请求,不能占着主线程等它
            SourceHelper.PREPARE_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    getSort(sourceKey, withRec);
                }
            });
            return;
        }
        if (sourceKey == null) {
            sortResult.postValue(new AbsSortXml());
            return;
        }

        // 优先检查缓存
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            LOG.i("echo--getSort-source-null--" + sourceKey);
            postSortResult(sourceKey, null);
            return;
        }
        if(sourceBean.getName().length()<=3 && sourceBean.getName().endsWith("搜")){ // i18n: keep
            postSortResult(sourceKey, null);
            return;
        }

        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            AbsSortXml cached;
            synchronized (sortCache) {
                cached = sortCache.get(sourceKey);
            }
            if (cached != null) {
                boolean shouldUseCache = cached.videoList != null && !cached.videoList.isEmpty();
                if (shouldUseCache) {
                    attachSortSource(sourceKey, cached);
                    postSortResult(sourceKey, cached);
                    return;
                }
            }
        }

        int type = sourceBean.getType();
        if (type == 3) {
            getSortFromSpider(sourceKey, sourceBean, withRec);
        } else if (type == 0 || type == 1) {
            getSortFromApi(sourceKey, sourceBean, withRec);
        } else if (type == 4) {
            getSortFromExtendedApi(sourceKey, sourceBean);
        } else {
            postSortResult(sourceKey, null);
        }
    }

    /** type 3:爬虫 homeContent,拿到 sorts 后再补一次首页推荐(推荐走 {@link ListLoader}) */
    private void getSortFromSpider(final String sourceKey, final SourceBean sourceBean, final boolean withRec) {
        Runnable waitResponse = new Runnable() {
            @Override
            public void run() {
                String sortJson = BoundedCall.call(new Callable<String>() {
                    @Override
                    public String call() {
                        // getCSP/homeContent 可能抛 Error(爬虫 <clinit> / 伪造 .so):接住,
                        // 与 DetailLoader/ListLoader 同口径
                        try {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeContent(true);
                        } catch (Throwable t) {
                            LOG.i("echo--getSort--error: " + t);
                            return "";
                        }
                    }
                }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getSort--" + sourceBean.getKey());
                if (sortJson != null) {
                    final AbsSortXml sortXml = resultParser.sortJson(sortResult, sortJson);
                    attachSortSource(sourceKey, sortXml);
                    if (sortXml != null) {
                        AbsXml absXml = resultParser.json(null, sortJson, sourceBean.getKey());
                        if (!withRec) {
                            postSortResult(sourceKey, sortXml);
                            cacheSort(sourceKey, sortXml);
                        } else if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                            sortXml.videoList = absXml.movie.videoList;
                            postSortResult(sourceKey, sortXml);
                            cacheSort(sourceKey, sortXml);
                        } else if (sortXml.classes != null) {
                            // homeContent 解析成功却没带推荐视频是常态(分类够用),不能当取数失败
                            postSortResult(sourceKey, sortXml);
                            cacheSort(sourceKey, sortXml);
                        } else {
                            listLoader.getHomeRecList(sourceBean, null, new ListLoader.HomeRecCallback() {
                                @Override
                                public void done(List<Movie.Video> videos) {
                                    sortXml.videoList = videos;
                                    postSortResult(sourceKey, sortXml);
                                    cacheSort(sourceKey, sortXml);
                                }
                            });
                        }
                    } else {
                        postSortFailure(sourceKey);
                    }
                } else {
                    LOG.i("echo--getSort-spider-null:" + sourceKey);
                    postSortFailure(sourceKey);
                }
            }
        };
        SourceHelper.PREPARE_POOL.execute(waitResponse);
    
    }

    /** type 0/1:站点 XML / JSON 接口,带站点级 header */
    private void getSortFromApi(final String sourceKey, final SourceBean sourceBean, final boolean withRec) {
        // 回调里要按 type 分流 xml/json,值语义与调用点一致(原为捕获 getSort 的局部量)
        final int type = sourceBean.getType();
        SourceHelper.siteGet(sourceBean)
                .tag(sourceBean.getKey() + "_sort")
                .execute(new AbsCallback<String>() {
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
                        AbsSortXml sortXml = null;
                        if (type == 0) {
                            String xml = response.body();
                            sortXml = resultParser.sortXml(sortResult, xml);
                        } else if (type == 1) {
                            String json = response.body();
                            sortXml = resultParser.sortJson(sortResult, json);
                        }
                        attachSortSource(sourceKey, sortXml);
                        if (withRec && sortXml != null && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                            ArrayList<String> ids = new ArrayList<>();
                            for (Movie.Video vod : sortXml.list.videoList) {
                                ids.add(vod.id);
                            }
                            final AbsSortXml finalSortXml = sortXml;
                            listLoader.getHomeRecList(sourceBean, ids, new ListLoader.HomeRecCallback() {
                                @Override
                                public void done(List<Movie.Video> videos) {
                                    finalSortXml.videoList = videos;
                                    postSortResult(sourceKey, finalSortXml);
                                    cacheSort(sourceKey, finalSortXml);
                                }
                            });
                        } else if (sortXml != null && sortXml.classes != null) {
                            // 分类已解析出来,推荐位缺失不影响首页可用性;postSortFailure 只留给真没解析出响应的情况
                            postSortResult(sourceKey, sortXml);
                            cacheSort(sourceKey, sortXml);
                        } else {
                            postSortFailure(sourceKey);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.i("echo--getSort-api-error:" + sourceKey + " code=" + response.code()
                                + " ex=" + response.getException());
                        postSortFailure(sourceKey);
                    }
                });

    }

    /** type 4:带 extend 的接口;extend 过长时改走 RemoteTVBox 的 POST(URL 长度限制) */
    private void getSortFromExtendedApi(final String sourceKey, final SourceBean sourceBean) {
        String extend=sourceBean.getExt();
        extend=SourceHelper.getFixUrl(extendCache, gson, extend, sourceBean.getPlayTimeoutSeconds());
        if(URLEncoder.encode(extend).length()<1000){
            GetRequest<String> request = SourceHelper.siteGet(sourceBean)
                    .tag(sourceBean.getKey() + "_sort")
                    .params("filter", "true");
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
                            String sortJson  = response.body();
                            if (sortJson != null) {
                                final AbsSortXml sortXml = resultParser.sortJson(sortResult, sortJson);
                                attachSortSource(sourceKey, sortXml);
                                if (sortXml != null) {
                                    AbsXml absXml = resultParser.json(null, sortJson, sourceBean.getKey());
                                    if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                        sortXml.videoList = absXml.movie.videoList;
                                        postSortResult(sourceKey, sortXml);
                                        cacheSort(sourceKey, sortXml);
                                    } else {
                                        listLoader.getHomeRecList(sourceBean, null, new ListLoader.HomeRecCallback() {
                                            @Override
                                            public void done(List<Movie.Video> videos) {
                                                sortXml.videoList = videos;
                                                postSortResult(sourceKey, sortXml);
                                                cacheSort(sourceKey, sortXml);
                                            }
                                        });
                                    }
                                } else {
                                    postSortFailure(sourceKey);
                                }
                            } else {
                                postSortFailure(sourceKey);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            LOG.i("echo--getSort-ext-error:" + sourceKey + " code=" + response.code()
                                    + " ex=" + response.getException());
                            postSortFailure(sourceKey);
                        }
                    });
        }else {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("filter","true");
                if (extend != null && !extend.isEmpty()) {
                    params.put("extend",extend);
                }
                RemoteTVBox.post(sourceBean.getApi(), params, sourceBean.getHeader(), new okhttp3.Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, IOException e) {
                        LOG.i("echo--getSort-post-fail:" + sourceKey + " ex=" + e);
                        postSortFailure(sourceKey);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                        assert response.body() != null;
                        String sortJson = response.body().string();
                        final AbsSortXml sortXml = resultParser.sortJson(sortResult, sortJson);
                        attachSortSource(sourceKey, sortXml);
                        if (sortXml != null) {
                            AbsXml absXml = resultParser.json(null, sortJson, sourceBean.getKey());
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                sortXml.videoList = absXml.movie.videoList;
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            } else if (sortXml.classes != null) {
                                // 同上:解析成功但无推荐要走成功出口,否则这条分支全程无回包、只能等超时
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            } else {
                                postSortFailure(sourceKey);
                            }
                        } else {
                            postSortFailure(sourceKey);
                        }
                    }
                });
            } catch (Exception ignored) {
                postSortFailure(sourceKey);
            }
        }
    
    }
}
