package com.github.tvbox.osc.sourcedata;

import android.text.TextUtils;

import androidx.lifecycle.MutableLiveData;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.ApiLog;
import com.github.tvbox.osc.util.BoundedCall;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全站/单源搜索(searchContent)。
 *
 * <p>t4 源的 extend 是阻塞拉取,所以整段搜索请求先挪到准备线程池;
 * 空结果与解析失败都走 {@link SourceResultParser#postEmptySearchResult} 保持同一形状。
 */
final class SearchLoader {
    private final Gson gson;
    private final ConcurrentHashMap<String, String> extendCache;
    private final MutableLiveData<AbsXml> searchResult;
    private final SourceResultParser resultParser;

    SearchLoader(Gson gson, ConcurrentHashMap<String, String> extendCache, MutableLiveData<AbsXml> searchResult,
                 SourceResultParser resultParser) {
        this.gson = gson;
        this.extendCache = extendCache;
        this.searchResult = searchResult;
        this.resultParser = resultParser;
    }

    // searchContent
    void getSearch(String sourceKey, String wd) {
        getSearch(sourceKey, wd, "");
    }

    void getSearch(String sourceKey, String wd, String searchToken) {
        getSearch(sourceKey, wd, searchToken, searchResult, "search");
    }

    private void getSearch(String sourceKey, String wd, String searchToken, MutableLiveData<AbsXml> result, String requestTag) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            resultParser.postEmptySearchResult(result, sourceKey, searchToken);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            searchFromSpider(sourceBean, wd, result, searchToken);
        } else if (type == 0 || type == 1) {
            searchFromApi(sourceBean, wd, result, searchToken, requestTag);
        } else if (type == 4) {
            searchFromExtendedApi(sourceBean, wd, result, searchToken, requestTag);
        } else {
            resultParser.postEmptySearchResult(result, sourceBean.getKey(), searchToken);
        }
    }


    /** type 3:爬虫 searchContent;空结果也回一条空 AbsXml,保持与其它分支同形状 */
    private void searchFromSpider(final SourceBean sourceBean, final String wd, final MutableLiveData<AbsXml> result, final String searchToken) {
        final String key = sourceBean.getKey();
        long timeoutMs = sourceBean.getPlayTimeoutSeconds() * 1000L;
        String tag = "echo--getSearch--" + key;
        try {
            String search = BoundedCall.call(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() throws Exception {
                    Spider sp = ApiConfig.get().getCSP(sourceBean);
                    return sp.searchContent(wd, false);
                }
            }, timeoutMs, tag);
            if (!TextUtils.isEmpty(search)) {
                resultParser.json(result, search, key, searchToken);
            } else {
                ApiLog.fail(ApiLog.kindOfKey(key), key, "搜索", "返回空");
                resultParser.json(result, "", key, searchToken);
            }
        } catch (Throwable th) {
            ApiLog.fail(ApiLog.kindOfKey(key), key, "搜索", th.getMessage());
            LOG.e("SourceViewModel", th);
            resultParser.json(result, "", key, searchToken);
        }
    
    }

    /** type 0/1:站点搜索接口(type 0 走 XML) */
    private void searchFromApi(final SourceBean sourceBean, final String wd, final MutableLiveData<AbsXml> result, final String searchToken, final String requestTag) {
        // 回调里要按 type 分流 xml/json,值语义与调用点一致(原为捕获上层局部量)
        final int type = sourceBean.getType();
        
        SourceHelper.siteGet(sourceBean)
                .params("wd", wd)
                .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                .tag(requestTag)
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
                            resultParser.xml(result, xml, sourceBean.getKey(), searchToken);
                        } else {
                            String json = response.body();
                            resultParser.json(result, json, sourceBean.getKey(), searchToken);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        resultParser.postEmptySearchResult(result, sourceBean.getKey(), searchToken);
                    }
                });
    
    }

    /** type 4:带 extend 的搜索;extend 是阻塞拉取,故整段挪到准备线程池 */
    private void searchFromExtendedApi(final SourceBean sourceBean, final String wd, final MutableLiveData<AbsXml> result, final String searchToken, final String requestTag) {
        
        final String searchWd = wd;
        SourceHelper.PREPARE_POOL.execute(new Runnable() {
            @Override
            public void run() {
        String extend=sourceBean.getExt();
        extend=SourceHelper.getFixUrlDirect(extendCache, gson, extend);
        String queryWd = searchWd;
        try {
            queryWd=URLEncoder.encode(queryWd, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            LOG.e("SourceViewModel", e);
        }

        GetRequest<String> request = SourceHelper.siteGet(sourceBean)
                .tag(requestTag)
                .params("wd", queryWd)
                .params("ac" ,"detail")
                .params("quick" ,"false");
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
                        LOG.i("echo-t4 search-网络请求错误");
                        throw new IllegalStateException(SourceHelper.ERR_NETWORK);
                    }
                }

                @Override
                public void onSuccess(Response<String> response) {
                        String json = response.body();
//                            LOG.i("echo-t4 search onSuccess"+json);
                        resultParser.json(result, json, sourceBean.getKey(), searchToken);
                }

                @Override
                public void onError(Response<String> response) {
                    LOG.i("echo-t4 search-onError");
                    super.onError(response);
                    resultParser.postEmptySearchResult(result, sourceBean.getKey(), searchToken);
                }
            });
            }
        });
    
    }
}
