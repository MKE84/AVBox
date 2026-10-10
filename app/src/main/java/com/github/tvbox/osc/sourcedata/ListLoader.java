package com.github.tvbox.osc.sourcedata;

import android.text.TextUtils;
import android.os.Looper;
import android.util.Base64;

import androidx.lifecycle.MutableLiveData;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.BoundedCall;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列表类取数:分类列表(categoryContent)与首页推荐(homeVideoContent)。
 *
 * <p>首页推荐是 sort 的附属请求(拿到分类后再补一次推荐位),由 {@link SortLoader} 调用。
 */
final class ListLoader {
    private final Gson gson;
    private final ConcurrentHashMap<String, String> extendCache;
    private final MutableLiveData<AbsXml> listResult;
    private final SourceResultParser resultParser;

    ListLoader(Gson gson, ConcurrentHashMap<String, String> extendCache, MutableLiveData<AbsXml> listResult,
               SourceResultParser resultParser) {
        this.gson = gson;
        this.extendCache = extendCache;
        this.listResult = listResult;
        this.resultParser = resultParser;
    }

    // categoryContent
    void getList(MovieSort.SortData sortData, int page) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 同 getSort:t4 源的 extend 拉取是阻塞动作
            SourceHelper.PREPARE_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    getList(sortData, page);
                }
            });
            return;
        }
        if (sortData == null) {
            LOG.i("echo-getList-sortData-null");
            listResult.postValue(null);
            return;
        }
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        int type = homeSourceBean.getType();
        if (type == 3) {
            getListFromSpider(homeSourceBean, sortData, page);
        } else if (type == 0 || type == 1) {
            getListFromApi(homeSourceBean, sortData, page);
        } else if (type == 4) {
            getListFromExtendedApi(homeSourceBean, sortData, page);
        } else {
            listResult.postValue(null);
        }
    }


    /** type 3:爬虫 categoryContent */
    private void getListFromSpider(final SourceBean homeSourceBean, final MovieSort.SortData sortData, final int page) {
        
        SourceHelper.SPIDER_POOL.execute(new Runnable() {
            @Override
            public void run() {
                String json = BoundedCall.call(new Callable<String>() {
                    @Override
                    public String call() {
                        // getCSP/categoryContent 可能抛 Error(爬虫 <clinit> / 伪造 .so):接住,
                        // 与 DetailLoader 同口径,避免异常穿透到 BoundedCall 之外的错位崩。
                        try {
                            Spider sp = ApiConfig.get().getCSP(homeSourceBean);
                            return sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect);
                        } catch (Throwable t) {
                            LOG.i("echo--getList--error: " + t);
                            return "";
                        }
                    }
                }, homeSourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getList--" + homeSourceBean.getKey());
//                    LOG.i("echo-categoryContent:"+json);
                if (json != null) {
                    resultParser.json(listResult, json, homeSourceBean.getKey());
                } else {
                    LOG.i("echo--list-spider-null:" + homeSourceBean.getKey() + " sort=" + sortData.id + " pg=" + page);
                    listResult.postValue(null);
                }
            }
        });
    
    }

    /** type 0/1:站点 XML / JSON 接口 */
    private void getListFromApi(final SourceBean homeSourceBean, final MovieSort.SortData sortData, final int page) {
        // 回调里要按 type 分流 xml/json,值语义与调用点一致(原为捕获上层局部量)
        final int type = homeSourceBean.getType();
        
        SourceHelper.siteGet(homeSourceBean)
                .tag(homeSourceBean.getApi())
                .params("ac", type == 0 ? "videolist" : "detail")
                .params("t", sortData.id)
                .params("pg", page)
                .params(sortData.filterSelect)
                .params("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString())
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
                        if (type == 0) {
                            String xml = response.body();
                            resultParser.xml(listResult, xml, homeSourceBean.getKey());
                        } else {
                            String json = response.body();
                            resultParser.json(listResult, json, homeSourceBean.getKey());
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.i("echo--list-api-error:" + homeSourceBean.getKey() + " t=" + sortData.id + " pg=" + page
                                + " code=" + response.code() + " ex=" + response.getException());
                        listResult.postValue(null);
                    }
                });

    }

    /** type 4:带 extend 的接口(filter 走 Base64 的 ext 参数) */
    private void getListFromExtendedApi(final SourceBean homeSourceBean, final MovieSort.SortData sortData, final int page) {
        
        String ext= "";
        String extend=homeSourceBean.getExt();
        extend=SourceHelper.getFixUrl(extendCache, gson, extend, homeSourceBean.getPlayTimeoutSeconds());
        if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
            try {
                String selectExt = new JSONObject(sortData.filterSelect).toString();
                ext = Base64.encodeToString(selectExt.getBytes("UTF-8"), Base64.DEFAULT |  Base64.NO_WRAP);
            } catch (UnsupportedEncodingException e) {
                LOG.e("SourceViewModel", e);
            }
        }else {
            ext = Base64.encodeToString("{}".getBytes(), Base64.DEFAULT |  Base64.NO_WRAP);
        }

        GetRequest<String> request = SourceHelper.siteGet(homeSourceBean)
                .tag(homeSourceBean.getApi())
                .params("ac", "detail")
                .params("filter", "true")
                .params("t", sortData.id)
                .params("pg", page)
                .params("ext", ext);
        // 当 extend 不为空且非空字符串时添加参数
        if (extend != null && !extend.isEmpty()) {
            request.params("extend", extend);
        }
        request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        try {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException(SourceHelper.ERR_NETWORK + "，response body 为 null"); // i18n: keep
                            }
                        } catch (Exception e) {
                            LOG.i("echo-list: convertResponse error"+ e.getMessage());
                            throw e;  // 重新抛出异常
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
//                            LOG.i("echo-list: " + json);
                        resultParser.json(listResult, json, homeSourceBean.getKey());
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.i("echo--list-ext-error:" + homeSourceBean.getKey() + " t=" + sortData.id + " pg=" + page
                                + " code=" + response.code() + " ex=" + response.getException());
                        listResult.postValue(null);
                    }
                });


    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }
//    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    String sortJson = BoundedCall.call(new Callable<String>() {
                        @Override
                        public String call() {
                            // 同 getListFromSpider:接住 Error,避免穿透
                            try {
                                Spider sp = ApiConfig.get().getCSP(sourceBean);
                                return sp.homeVideoContent();
                            } catch (Throwable t) {
                                LOG.i("echo--getHomeRecList--error: " + t);
                                return "";
                            }
                        }
                    }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getHomeRecList--" + sourceBean.getKey());
                    if (sortJson != null) {
                        AbsXml absXml = resultParser.json(null, sortJson, sourceBean.getKey());
                        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                            callback.done(absXml.movie.videoList);
                        } else {
                            callback.done(null);
                        }
                    } else {
                        callback.done(null);
                    }
                }
            };
            SourceHelper.SPIDER_POOL.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            SourceHelper.siteGet(sourceBean)
                    .tag("detail")
                    .params("ac", sourceBean.getType() == 0 ? "videolist" : "detail")
                    .params("ids", TextUtils.join(",", ids))
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
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = response.body();
                                absXml = resultParser.xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                absXml = resultParser.json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }
}
