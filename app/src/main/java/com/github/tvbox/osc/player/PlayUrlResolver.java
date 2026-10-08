package com.github.tvbox.osc.player;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.ApiLog;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HeaderGuard;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.parser.SuperParse;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 解析/嗅探调度:WebView 嗅探 + json/聚合/超级解析 + 代际闸门;宿主能力见 {@link Host}。
 *
 * <p>坑:代际必须"发起时捕获值 vs 当前值"比较;写成当前值自比较即恒真闸门(等于没装)。
 * 解析超时由本类 Handler 管,与宿主 timeoutHandler 各自独立。
 * <p>坑2:解析线程池里碰视图一律走 {@code view.runOnUi} —— 非主线程写视图会让 ViewGroup.mChildren
 * 出 null 洞,下次 traversal 必崩。
 */
final class PlayUrlResolver {

    interface Host {

        PlaybackViewBridge view();

        SourceBean sourceBean();

        HashMap<String, String> webHeaderMap();

        void setWebHeaderMap(HashMap<String, String> headers);

        String webUserAgent();

        void setWebUserAgent(String userAgent);

        /** 嗅探命中:与回调同帧,不校验代际 */
        void playUrl(String url, HashMap<String, String> headers);

        /** 解析产物:入口校验代际 */
        void playUrl(int gen, String url, HashMap<String, String> headers);
    }

    private final Host host;

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    private final Handler parseHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            if (msg.what == MSG_PARSE_TIMEOUT) {
                stopParse();
                ApiLog.fail(ApiLog.KIND_API, "-", "嗅探/解析", "超时(" + PARSE_TIMEOUT_MS + "ms)");
                // 嗅探超时也自动切换下一个解析器(不提示),全部试完才弹错误
                errorWithRetry(str(R.string.player_error_sniff), false);
                return true;
            }
            return false;
        }
    });

    PlayUrlResolver(Host host) {
        this.host = host;
    }

    // ==================== 代际(宿主入口也用它) ====================

    int nextGen() {
        return parseGeneration.incrementAndGet();
    }

    void resetGen() {
        parseGeneration.set(0);
    }

    int currentGen() {
        return parseGeneration.get();
    }

    void cancelParseTimeout() {
        parseHandler.removeMessages(MSG_PARSE_TIMEOUT);
    }

    boolean hasFoundUrls() {
        return loadFoundVideoUrls != null && !loadFoundVideoUrls.isEmpty();
    }

    void consumeFoundUrl() {
        autoRetryFromLoadFoundVideoUrls();
    }

    // ==================== 字段与常量 ====================

    private String webUrl;
    private String parseFlag;
    /** 解析/嗅探代际:发起时捕获、起播前比对,不一致即丢弃(旧集地址不得拉起新播放)。
     *  stopParse 只能"不再新增",拦不住已在跑的爬虫/迟到 WebView 请求,故不可省;线程:主线程写、网络线程读 ⇒ AtomicInteger。 */
    private final AtomicInteger parseGeneration = new AtomicInteger(0);
    /** 嗅探 WebView(1×1 挂在页面内容视图上,视图来自 host.view()) */
    private WebView mSysWebView;
    /** 当前嗅探页所属代际(投递导航前赋值,网络线程读故 volatile;-1 = 无嗅探页):用于丢弃旧页在途请求 */
    private volatile int webSniffGeneration = -1;
    // ⚠️ 非 UI 线程且不保证串行:以下集合必须并发安全
    private final Map<String, Boolean> loadedUrls = new ConcurrentHashMap<>();
    private volatile Queue<String> loadFoundVideoUrls = new ConcurrentLinkedQueue<>();
    private volatile Map<String, HashMap<String, String>> loadFoundVideoUrlsHeader = new ConcurrentHashMap<>();
    private final AtomicInteger loadFoundCount = new AtomicInteger(0);
    private final AtomicInteger sniffMissLogged = new AtomicInteger(0);
    /** 可见诊断:最近嗅探到的候选地址 + 判定结果(实时显示在提示条,便于用户截图定位) */
    private final java.util.concurrent.ConcurrentLinkedDeque<String> sniffDiag = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int SNIFF_DIAG_MAX = 10;
    private ExecutorService parseThreadPool;
    private static final int MSG_PARSE_TIMEOUT = 100;
    private static final long PARSE_TIMEOUT_MS = 20 * 1000;

    // ==================== 成员 ====================

    /** 按解析规则发起解析(直链/json/聚合/超级解析) */
    public void initParse(String flag, boolean useParse, String playUrl, final String url) {
        parseFlag = flag;
        webUrl = url;
        ParseBean parseBean = null;
        if (host.view() != null) host.view().showParse(useParse);
        if (useParse) {
            parseBean = ApiConfig.get().getDefaultParse();
        } else {
            if (playUrl.startsWith("json:")) {
                parseBean = new ParseBean();
                parseBean.setType(1);
                parseBean.setUrl(playUrl.substring(5));
            } else if (playUrl.startsWith("parse:")) {
                String parseRedirect = playUrl.substring(6);
                for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                    if (pb.getName().equals(parseRedirect)) {
                        parseBean = pb;
                        break;
                    }
                }
            }
            if (parseBean == null) {
                parseBean = new ParseBean();
                parseBean.setType(0);
                parseBean.setUrl(playUrl);
            }
        }
        doParse(parseBean);
    }

    JSONObject jsonParse(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        JSONObject playData = jsonPlayData.optJSONObject("data");
        if (playData == null) {
            playData = jsonPlayData;
        }
        String url = playData.optString("url", jsonPlayData.optString("url", ""));
        if (url.startsWith("//")) {
            url = "http:" + url;
        }
        boolean parse = false;
        if (url.startsWith("video://")) {
            url = url.substring(8);
            parse = true;
        }
        url = DefaultConfig.checkReplaceProxy(url);
        if (!url.startsWith("http") && !url.startsWith("data:application")) {
            return null;
        }
        parse = parse || playData.optInt("parse", jsonPlayData.optInt("parse", 0)) == 1;
        JSONObject headers = new JSONObject();
        HashMap<String, String> headerMap = PlaybackController.extractHeaders(jsonPlayData);
        HashMap<String, String> dataHeaderMap = PlaybackController.extractHeaders(playData);
        if (headerMap != null) PlaybackController.putHeaders(headers, headerMap);
        if (dataHeaderMap != null) PlaybackController.putHeaders(headers, dataHeaderMap);
        String ua = playData.optString("user-agent", jsonPlayData.optString("user-agent", ""));
        if (ua.trim().length() > 0) {
            headers.put("User-Agent", " " + ua);
        }
        String referer = playData.optString("referer", jsonPlayData.optString("referer", ""));
        if (referer.trim().length() > 0) {
            headers.put("Referer", " " + referer);
        }
        JSONObject taskResult = new JSONObject();
        taskResult.put("header", headers);
        taskResult.put("url", url);
        taskResult.put("parse", parse ? 1 : 0);
        return taskResult;
    }

    /** 停止解析/嗅探:取消解析超时、停 WebView、取消 OkGo 请求(含 M3U8 净化)、关线程池 */
    public void stopParse() {
        parseHandler.removeMessages(MSG_PARSE_TIMEOUT);
        stopLoadWebView(false);
        OkGo.getInstance().cancelTag("play");
        OkGo.getInstance().cancelTag("json_jx");
        // M3U8 净化在途请求也要撤:结果被代际丢弃后没人取消会一直持网到超时
        OkGo.getInstance().cancelTag("m3u8-1");
        OkGo.getInstance().cancelTag("m3u8-2");
        if (parseThreadPool != null) {
            try {
                // shutdownNow:真中断正在跑的阻塞任务(原 shutdown 只拒绝新任务,卡住的线程永不释放,
                // 反复切解析器会堆积线程 → "正在嗅探播放地址"假死)。同时清掉 PlayCore 排队任务。
                parseThreadPool.shutdownNow();
                com.github.avbox.core.PlayCore.purge();
                parseThreadPool = null;
            } catch (Throwable th) {
                LOG.e("PlayUrlResolver", th);
            }
        }
    }

    /** 重置嗅探结果容器(整引用替换,不动旧对象) */
    public void initParseLoadFound() {
        loadFoundCount.set(0);
        loadFoundVideoUrls = new ConcurrentLinkedQueue<>();
        loadFoundVideoUrlsHeader = new ConcurrentHashMap<>();
    }

    /** 消费一个嗅探到的地址并起播(取到 null 即放弃:队列可能被并发消费/重置) */
    void autoRetryFromLoadFoundVideoUrls() {
        String videoUrl = loadFoundVideoUrls.poll();
        if (videoUrl == null) return;
        HashMap<String, String> header = loadFoundVideoUrlsHeader.get(videoUrl);
        ApiLog.ok(ApiLog.KIND_API, "-", "嗅探命中", 0);
        if (host.view() != null) host.playUrl(videoUrl, header);
    }

    /** 本轮解析是否仍有效(gen = 发起时捕获值;页面桥校验 M3U8 净化结果也用它)。
     *  逐请求调用故不打日志,需要日志的调用方自行打。 */
    public boolean isParseResultCurrent(int gen) {
        return gen == parseGeneration.get();
    }

    public void doParse(ParseBean pb) {
        // 入口自增:作废上一轮解析;下面各回调统一捕获 gen
        final int gen = parseGeneration.incrementAndGet();
        stopParse();
        initParseLoadFound();
        sniffMissLogged.set(0);
        sniffDiag.clear();
        if (pb.getType() == 4) {
            parseMix(pb, true, gen);
        } else if (pb.getType() == 0) {
            if (host.view() != null) host.view().showTip(str(R.string.player_sniffing_url), true, false);
            parseHandler.removeMessages(MSG_PARSE_TIMEOUT);
            parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS);
            if (pb.getExt() != null) {
                try {
                    HashMap<String, String> reqHeaders = new HashMap<>();
                    JSONObject jsonObject = new JSONObject(pb.getExt());
                    HashMap<String, String> headerMap = PlaybackController.extractHeaders(jsonObject);
                    if (headerMap != null) {
                        for (String key : headerMap.keySet()) {
                            String value = headerMap.get(key);
                            // 解析器的 ext 头来自配置,非法字符会让 okhttp 构造请求时抛异常
                            if (!HeaderGuard.isSendable(key, value)) {
                                LOG.i("echo-ext-header-skip:" + key);
                                continue;
                            }
                            if (key.equalsIgnoreCase("user-agent")) {
                                host.setWebUserAgent(value.trim());
                            } else {
                                reqHeaders.put(key, value);
                            }
                        }
                        if (reqHeaders.size() > 0) host.setWebHeaderMap(reqHeaders);
                    }
                } catch (Throwable e) {
                    LOG.e("PlayUrlResolver", e);
                }
            }
            ApiLog.ok(ApiLog.KIND_API, pb.getName(), "嗅探启动:" + webUrl, 0);
            loadWebView(pb.getUrl() + webUrl);
        } else if (pb.getType() == 1) { // json 解析
            if (host.view() != null) host.view().showTip(str(R.string.player_resolving_url), true, false);
            HttpHeaders reqHeaders = new HttpHeaders();
            try {
                JSONObject jsonObject = new JSONObject(pb.getExt());
                HashMap<String, String> headerMap = PlaybackController.extractHeaders(jsonObject);
                if (headerMap != null) {
                    for (String key : headerMap.keySet()) {
                        if (!HeaderGuard.isSendable(key, headerMap.get(key))) {
                            LOG.i("echo-ext-header-skip:" + key);
                            continue;
                        }
                        reqHeaders.put(key, headerMap.get(key));
                    }
                }
            } catch (Throwable e) {
                LOG.e("PlayUrlResolver", e);
            }
            OkGo.<String>get(pb.getUrl() + (host.view() == null ? webUrl : host.view().encodeUrl(webUrl)))
                    .tag("json_jx")
                    .headers(reqHeaders)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误"); // i18n: keep(异常消息,只进日志)
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            // 旧请求(切集/换源/换解析后)的结果不得再驱动播放
                            if (!isParseResultCurrent(gen)) return;
                            String json = response.body();
                            try {
                                JSONObject rs = jsonParse(webUrl, json);
                                HashMap<String, String> headers = PlaybackController.extractHeaders(rs);
                                if (rs.optInt("parse", 0) == 1) {
                                    host.setWebHeaderMap(headers);
                                    if (headers != null) {
                                        host.setWebUserAgent(PlaybackController.headerValue(headers, "user-agent"));
                                        if (host.webUserAgent() != null) host.setWebUserAgent(host.webUserAgent().trim());
                                    }
                                    loadWebView(DefaultConfig.checkReplaceProxy(rs.getString("url")));
                                } else {
                                    if (host.view() != null) host.playUrl(gen, rs.getString("url"), headers);
                                }
                            } catch (Throwable e) {
                                LOG.e("PlayUrlResolver", e);
                                errorWithRetry(str(R.string.player_parse_error), false);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            ApiLog.fail(ApiLog.KIND_API, "-", "解析(json:" + pb.getName() + ")", "网络错误");
                            errorWithRetry(str(R.string.player_parse_error), false);
                        }
                    });
        } else if (pb.getType() == 2) { // json 扩展
            if (host.view() != null) host.view().showTip(str(R.string.player_resolving_url), true, false);
            parseThreadPool = Executors.newSingleThreadExecutor();
            LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                if (p.getType() == 1) {
                    jxs.put(p.getName(), p.mixUrl());
                }
            }
            parseThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    // jsonExt 是阻塞爬虫(可跑数十秒):结果到达时先校验本轮
                    if (!isParseResultCurrent(gen)) return;
                    JSONObject rs = ApiConfig.get().jsonExt(pb.getUrl(), jxs, webUrl);
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                        if (isParseResultCurrent(gen) && host.view() != null) {
                            PlaybackViewBridge bridge = host.view();
                            bridge.runOnUi(() -> errorWithRetry(str(R.string.player_parse_error), false));
                        }
                    } else {
                        HashMap<String, String> headers = PlaybackController.extractHeaders(rs);
                        if (rs.has("jxFrom") && host.view() != null) {
                            final String jxFrom = rs.optString("jxFrom");
                            host.view().runOnUi(() -> host.view().toast(str(R.string.player_parse_from, jxFrom)));
                        }
                        boolean parseWV = rs.optInt("parse", 0) == 1;
                        if (parseWV) {
                            String wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                            loadUrl(gen, wvUrl);
                        } else {
                            if (host.view() != null) host.playUrl(gen, rs.optString("url", ""), headers);
                        }
                    }
                }
            });
        } else if (pb.getType() == 3) { // json 聚合
            parseMix(pb, false, gen);
        }
    }

    /** 当前自动切换索引(跨实例共用计数,0=默认第一个) */
    private static int autoParseIndex = 0;

    /** 解析失败自动尝试下一个解析器(不提示),全部试完仍失败才弹错误 */
    private void errorWithRetry(String err, boolean finish) {
        if (finish) {
            if (host.view() != null) host.view().showErrorWithRetry(err, true);
            return;
        }
        try {
            java.util.List<ParseBean> list = ApiConfig.get().getParseBeanList();
            if (list == null || list.isEmpty()) {
                if (host.view() != null) host.view().showErrorWithRetry(err, false);
                return;
            }
            // 服务端聚合(type 3/4)已内部并行试完所有线路,失败即真失败,不在此级联
            if (list.size() <= 1) {
                if (host.view() != null) host.view().showErrorWithRetry(err, false);
                return;
            }
            int size = list.size();
            int start = autoParseIndex;                   // 本轮起点(已试到哪)
            ParseBean next = null;
            for (int k = 1; k <= size; k++) {             // 只探索一轮,必回起点
                int idx = (start + k) % size;             // 从 start 的下一个开始
                if (idx == start) break;                  // 绕回起点:一圈已试完
                ParseBean pb = list.get(idx);
                int t = pb.getType();
                // 按列表顺序换下一个解析器,跳过超级解析(type 4,仅一个,无需再试)。
                if (t != 4) {
                    next = pb;
                    autoParseIndex = idx;                 // 游标前移
                    break;
                }
            }
            if (next == null) {
                if (host.view() != null) host.view().showErrorWithRetry(err, false);
                return;
            }
            ApiLog.ok(ApiLog.KIND_API, next.getName(), "自动切换解析", 0);
            doParse(next);
        } catch (Throwable th) {
            if (host.view() != null) host.view().showErrorWithRetry(err, false);
        }
    }

    /** 聚合解析(type 3/4):超级解析 = 嗅探与 json 并发;普通聚合 = jsonExtMix */
    private void parseMix(ParseBean pb, boolean isSuper, final int gen) {
        if (host.view() != null) host.view().showTip(str(R.string.player_resolving_url), true, false);
        // 聚合/超级解析的执行是"阻塞爬虫",jsonExtMix(公共聚合)可能挂起数十秒且不设超时;
        // 这里在跑之前就装上统一看护超时,超时统一走 MSG_PARSE_TIMEOUT->errorWithRetry(自动切下一个解析器),
        // 彻底杜绝"一直转圈、永不弹错"。后台线程结果到达时由 isParseResultCurrent(gen) 按代际丢弃。
        parseHandler.removeMessages(MSG_PARSE_TIMEOUT);
        parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS);
        parseThreadPool = Executors.newSingleThreadExecutor();
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        LinkedHashMap<String, String> json_jxs = new LinkedHashMap<>();
        String extendName = "";
        for (ParseBean p : ApiConfig.get().getParseBeanList()) {
            HashMap<String, String> data = new HashMap<String, String>();
            data.put("url", p.getUrl());
            if (p.getUrl().equals(pb.getUrl())) {
                extendName = p.getName();
            }
            data.put("type", p.getType() + "");
            data.put("ext", p.getExt());
            jxs.put(p.getName(), data);

            if (p.getType() == 1) {
                json_jxs.put(p.getName(), p.mixUrl());
            }
        }
        String finalExtendName = extendName;
        // 解析器按调用传递,不得改回静态字段跨线程读
        SuperParse.ParseTargets parseTargets = SuperParse.buildTargets(jxs, parseFlag + "123");
        parseThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                // 阻塞爬虫(可跑数十秒):结果到达时先校验本轮
                if (!isParseResultCurrent(gen)) return;
                if (isSuper) {
                    // 超级解析同为阻塞爬虫,套 PlayCore 真超时,超时即切下一个解析器
                    final SuperParse.ParseTargets pt = parseTargets;
                    final String sf = parseFlag + "123";
                    JSONObject rs = com.github.avbox.core.PlayCore.callWithTimeout(
                            () -> SuperParse.parse(jxs, sf, webUrl, pt),
                            com.github.avbox.core.ParserCore.PARSE_TIMEOUT_MS + 12_000L,
                            "superParse");
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                        if (isParseResultCurrent(gen) && host.view() != null) {
                            PlaybackViewBridge bridge = host.view();
                            bridge.runOnUi(() -> errorWithRetry(str(R.string.player_parse_error), false));
                        }
                    } else {
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                host.setWebUserAgent(rs.optString("ua").trim());
                            }
                            if (host.view() != null) {
                                PlaybackViewBridge bridge = host.view();
                                bridge.runOnUi(() -> bridge.showTip(str(R.string.player_super_parsing), true, false));
                            }
                            final String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                            if (host.view() != null) {
                                host.view().runOnUi(() -> {
                                    // 排队期可能已切集:旧页不得替换当前嗅探页、更不得带着旧超时
                                    if (!isParseResultCurrent(gen)) return;
                                    stopParse();
                                    parseHandler.removeMessages(MSG_PARSE_TIMEOUT);
                                    parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS);
                                    loadWebView(mixParseUrl);
                                });
                            }
                            parseThreadPool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    JSONObject res = SuperParse.doJsonJx(parseTargets.jsonJx, webUrl);
                                    rsJsonJX(gen, res, true);
                                }
                            });
                        } else {
                            rsJsonJX(gen, rs, false);
                        }
                    }
                } else {
                    // 阻塞爬虫(可跑数十秒):用 PlayCore 套真墙钟超时,超时即返回 null,
                    // 不再靠被占满的线程池干等;底层网络读写超时会让线程自行退出。
                    final String mixFlag = parseFlag + "111";
                    final String mixKey = pb.getUrl();
                    JSONObject rs = com.github.avbox.core.PlayCore.callWithTimeout(
                            () -> ApiConfig.get().jsonExtMix(mixFlag, mixKey, finalExtendName, jxs, webUrl),
                            com.github.avbox.core.ParserCore.PARSE_TIMEOUT_MS + 12_000L,
                            "jsonExtMix");
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                        if (isParseResultCurrent(gen) && host.view() != null) {
                            PlaybackViewBridge bridge = host.view();
                            // 聚合失败也走自动切换,别停死在这(否则不再试剩余解析器)
                            bridge.runOnUi(() -> errorWithRetry(str(R.string.player_parse_error), false));
                        }
                    } else {
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                host.setWebUserAgent(rs.optString("ua").trim());
                            }
                            final String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                            if (host.view() != null) {
                                host.view().runOnUi(() -> {
                                    // 同上:排队期可能已切集
                                    if (!isParseResultCurrent(gen)) return;
                                    stopParse();
                                    host.view().showTip(str(R.string.player_sniffing_url), true, false);
                                    parseHandler.removeMessages(MSG_PARSE_TIMEOUT);
                                    parseHandler.sendEmptyMessageDelayed(MSG_PARSE_TIMEOUT, PARSE_TIMEOUT_MS);
                                    loadWebView(mixParseUrl);
                                });
                            }
                        } else {
                            rsJsonJX(gen, rs, false);
                        }
                    }
                }
            }
        });
    }

    private void rsJsonJX(int gen, JSONObject rs, boolean isSuper) {
        // 先校验本轮:否则切集后,上一轮遗留的 jsonJx 回调会关掉新集的嗅探页 / 起播旧地址
        if (!isParseResultCurrent(gen)) return;
        if (isSuper) {
            if (rs == null || !rs.has("url")) return;
            stopLoadWebView(false);
        }
        HashMap<String, String> headers = PlaybackController.extractHeaders(rs);
        if (rs.has("jxFrom") && host.view() != null) {
            final String jxFrom = rs.optString("jxFrom");
            host.view().runOnUi(() -> host.view().toast(str(R.string.player_parse_from, jxFrom)));
        }
        // 聚合/超级解析已成功拿到地址,撤销 parseMix 入口装配的看护超时,避免 6s 到点误杀已起播
        parseHandler.removeMessages(MSG_PARSE_TIMEOUT);
        if (host.view() != null) host.playUrl(gen, rs.optString("url", ""), headers);
    }

    void loadWebView(String url) {
        if (mSysWebView == null) {
            initWebView();
        }
        loadUrl(url);
    }

    void initWebView() {
        if (host.view() == null) return;
        mSysWebView = host.view().newSniffWebView();
        // 无页面(仅引擎/服务)时没有内容视图:取流前的嗅探只有页面在时才有意义
        if (mSysWebView == null) return;
        configWebViewSys(mSysWebView);
    }

    void loadUrl(String url) {
        if (host.view() == null || !host.view().isPageAlive()) return;
        // 本次导航所属代际(在投递前赋值,不能放 runnable 内:否则旧页请求与新一轮导航之间有窗口期)
        webSniffGeneration = parseGeneration.get();
        host.view().runOnUi(new Runnable() {
            @Override
            public void run() {
                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    if (host.webUserAgent() != null) {
                        mSysWebView.getSettings().setUserAgentString(host.webUserAgent());
                    }
                    if (host.webHeaderMap() != null) {
                        mSysWebView.loadUrl(url, host.webHeaderMap());
                    } else {
                        mSysWebView.loadUrl(url);
                    }
                }
            }
        });
    }

    /** 带代际的嗅探页导航:后台线程发起,排队期若已切集不得把新集嗅探页换掉 */
    private void loadUrl(int gen, String url) {
        if (host.view() == null || !host.view().isPageAlive()) return;
        webSniffGeneration = gen;
        host.view().runOnUi(new Runnable() {
            @Override
            public void run() {
                if (!isParseResultCurrent(gen)) return;
                loadUrl(url);
            }
        });
    }

    /** 当前请求是否属于正在嗅探的页面(旧页迟到请求一律丢弃,否则会把上一集地址塞进新队列)。
     *  ⚠️ 必须比"页面所属代际";写成 {@code isParseResultCurrent(parseGeneration.get())} 是恒真自比较,闸门等于没装。 */
    private boolean isSniffRequestOfCurrentRound() {
        return webSniffGeneration >= 0 && webSniffGeneration == parseGeneration.get();
    }

    public void stopLoadWebView(boolean destroy) {
        if (host.view() == null) return;
        host.view().runOnUi(new Runnable() {
            @Override
            public void run() {
                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    mSysWebView.loadUrl("about:blank");
                    if (destroy) {
                        mSysWebView.clearCache(true);
                        mSysWebView.removeAllViews();
                        mSysWebView.destroy();
                        mSysWebView = null;
                        webSniffGeneration = -1;
                    }
                }
            }
        });
    }

    /** 可见诊断:收集最近嗅探到的候选地址及其判定结果,实时显示在提示条上。
     *  标记:√=命中可播 / ×=判定非视频 / F=被过滤规则拦 / A=被广告规则拦。
     *  用户看到"一直卡在正在嗅探"时,直接截图即可知道嗅探拦截到了什么、卡在哪一步。 */
    private void showSniffDiag(String url, String mark) {
        try {
            if (host.view() == null) return;
            String tail = url;
            try {
                int q = url.indexOf('?');
                String noQ = q > 0 ? url.substring(0, q) : url;
                int slash = noQ.lastIndexOf('/');
                tail = slash >= 0 ? noQ.substring(slash + 1) : noQ;
                if (tail.isEmpty()) tail = noQ;
            } catch (Throwable ignored) {
            }
            if (tail.length() > 44) tail = "…" + tail.substring(tail.length() - 42);
            if (sniffDiag.size() >= SNIFF_DIAG_MAX) sniffDiag.pollFirst();
            sniffDiag.addLast(mark + " " + tail);
            final StringBuilder sb = new StringBuilder(str(R.string.player_sniffing_url)).append('\n');
            for (String s : sniffDiag) sb.append(s).append('\n');
            final String msg = sb.toString().trim();
            host.view().runOnUi(() -> {
                if (host.view() != null) host.view().showTip(msg, true, false);
            });
        } catch (Throwable ignored) {
        }
    }

    /** 是否"长得像视频"(含视频后缀/常见参数),用于减少广告/过滤诊断的噪声 */
    private static boolean looksLikeVideo(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv")
                || u.contains(".ts") || u.contains(".mkv") || u.contains(".m3u")
                || u.contains("playurl") || u.contains("vurl") || u.contains("m3u8");
    }

    boolean checkVideoFormat(String url) {
        try {
            // 中转页(内嵌 url=http 参数)不是真实视频流,不当作命中
            if (url.contains("url=http")) {
                return false;
            }
            // 只按路径判断是否是网页(避免误杀 query 里含 .html 的视频直链):
            // 原实现 url.contains(".html") 会把 .../video.m3u8?refer=/x.html 也当网页丢弃。
            String path = android.net.Uri.parse(url).getPath();
            String lowerPath = path == null ? "" : path.toLowerCase();
            if (lowerPath.endsWith(".html") || lowerPath.endsWith(".htm") || lowerPath.endsWith(".php")) {
                return false;
            }
            if (host.sourceBean() != null && host.sourceBean().getType() == 3) {
                Spider sp = ApiConfig.get().getCSP(host.sourceBean());
                if (sp != null && sp.manualVideoCheck()) {
                    boolean ok9 = sp.isVideoFormat(url);
                    if (!ok9) logSniffMiss(url);
                    return ok9;
                }
            }
            boolean ok = VideoParseRuler.checkIsVideoForParse(webUrl, url);
            if (!ok) logSniffMiss(url);
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 嗅探"未命中"诊断:仅当 URL 长得像视频(含视频后缀/参数)却未被判定为视频时记录,
     * 用于定位 py/js 源卡"正在嗅探"是否因解析站页面的真实视频地址匹配不上判定规则。
     * 每个解析进程最多记 6 条,避免刷屏。
     */
    private void logSniffMiss(String url) {
        try {
            if (sniffMissLogged.get() >= 6) return;
            String u = url == null ? "" : url.toLowerCase();
            boolean videoish = u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv")
                    || u.contains(".ts") || u.contains("video") || u.contains("playurl")
                    || u.contains("vurl") || u.contains(".webm") || u.contains("getvkey");
            if (!videoish) return;
            if (sniffMissLogged.incrementAndGet() > 6) return;
            ApiLog.fail(ApiLog.KIND_API, parseNameForLog(), "嗅探未命中", abbrev(url) + " | webUrl=" + abbrev(webUrl));
        } catch (Throwable ignored) {
        }
    }

    private String parseNameForLog() {
        try {
            if (host.sourceBean() != null && host.sourceBean().getName() != null) {
                return host.sourceBean().getName();
            }
        } catch (Throwable ignored) {
        }
        return "-";
    }

    private String abbrev(String s) {
        if (s == null) return "null";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private void configWebViewSys(WebView webView) {
        if (webView == null) {
            return;
        }
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        if (host.view() == null || !host.view().isPageAlive()) return;
        host.view().attachSniffWebView(webView);
        final WebSettings settings = webView.getSettings();
        settings.setNeedInitialFocus(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        settings.setBlockNetworkImage(true);
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUserAgentString(webView.getSettings().getUserAgentString());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return true;
            }
        });
        SysWebClient mSysWebClient = new SysWebClient();
        webView.setWebViewClient(mSysWebClient);
        webView.setBackgroundColor(Color.BLACK);
    }

    private class SysWebClient extends WebViewClient {

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView webView, SslErrorHandler sslErrorHandler, SslError sslError) {
            sslErrorHandler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            LOG.i("echo-onPageFinished url:" + url);
            if (!url.equals("about:blank") && host.view() != null) {
                host.view().evaluateScript(url, view);
            }
        }

        WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers) {
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return new WebResourceResponse("image/x-icon", "UTF-8", null);
                }
                return null;
            }

            // 旧页迟到请求不得进入新一轮结果队列
            if (!isSniffRequestOfCurrentRound()) {
                return null;
            }
            boolean isFilter = VideoParseRuler.isFilter(webUrl, url);
            if (isFilter) {
                LOG.i("shouldInterceptLoadRequest filter:" + url);
                showSniffDiag(url, "F");
                return null;
            }

            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = Boolean.TRUE.equals(loadedUrls.get(url));
            }

            if (!ad) {
                boolean hit = checkVideoFormat(url);
                // 可见诊断:显示每个候选地址的判定结果(√命中/×非视频)
                showSniffDiag(url, hit ? "√" : "×");
                if (hit) {
                    loadFoundVideoUrls.add(url);
                    loadFoundVideoUrlsHeader.put(url, headers);
                    LOG.i("echo-loadFoundVideoUrl:" + url);
                    ApiLog.ok(ApiLog.KIND_API, "嗅探命中", "起播", 0);
                    if (loadFoundCount.incrementAndGet() == 1) {
                        stopLoadWebView(false);
                        SuperParse.stopJsonJx();
                        url = loadFoundVideoUrls.poll();
                        // ⚠️ 队列可能已被并发消费或被新一轮重置(字段 volatile):
                        // poll 为 null 时若继续走 getCookie/playUrl 会 NPE
                        if (url == null) return null;
                        parseHandler.removeMessages(MSG_PARSE_TIMEOUT);
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", " " + cookie);//携带cookie
                        if (host.view() != null) host.playUrl(url, headers);
                    }
                }
            } else if (looksLikeVideo(url)) {
                // 被广告规则拦下、但本体长得像视频:很可能误杀,显示出来便于定位
                showSniffDiag(url, "A");
            }

            return ad || loadFoundCount.get() > 0 ?
                    AdBlocker.createEmptyResource() :
                    null;
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return null;
        }

        @Nullable
        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            LOG.i("echo-shouldInterceptRequest url:" + url);
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null && hds.keySet().size() > 0) {
                for (String k : hds.keySet()) {
                    if (k.equalsIgnoreCase("user-agent")
                            || k.equalsIgnoreCase("referer")
                            || k.equalsIgnoreCase("origin")) {
                        webHeaders.put(k, " " + hds.get(k));
                    }
                }
            }
            return checkIsVideo(url, webHeaders);
        }
    }
}
