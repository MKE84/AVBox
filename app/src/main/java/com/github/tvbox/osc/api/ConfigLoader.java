package com.github.tvbox.osc.api;

import android.app.Activity;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.ApiLineSignal;
import com.github.tvbox.osc.util.BootGuard;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LocalSourceTree;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PermissionHelper;
import com.github.tvbox.osc.util.PySourcePack;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 点播/直播配置的拉取编排:快照回落、仓分流与本地源可读性判断 */
final class ConfigLoader {
    private final ApiConfig owner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService configLoadExecutor = Executors.newSingleThreadExecutor();

    ConfigLoader(ApiConfig owner) {
        this.owner = owner;
    }

    void loadConfig(boolean useCache, ApiConfig.LoadConfigCallback callback, Activity activity) {
        String apiUrl = KV.get(HawkConfig.API_URL, "");
        if (apiUrl.isEmpty()) {
            callback.error("-1");
            return;
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl));
        // 本地/局域网源不吃快照(与 useCachedConfig 同一口径):本地源失效时靠快照"加载成功"会让用户
        // 以为源正常、实则内容永不更新,而这一支在 fetch 之前就早退,后面的可读性判据拦不住
        if (useCache && cache.exists() && isRemoteSource(apiUrl)) {
            try {
                String json = readConfigFile(cache);
                if (switchApiCollectionIfNeeded(apiUrl, json)) {
                    loadConfig(false, callback, activity);
                    return;
                }
                clearApiLinesIfUnmatched(apiUrl);
                owner.parseJson(apiUrl, json);
                callback.success();
                return;
            } catch (Throwable th) {
                LOG.e("ApiConfig", th);
            }
        }
        ConfigParser.ConfigUrl resolved = ConfigParser.configUrl(apiUrl, ApiConfig::localFileBase);
        final String configUrl = resolved.url;
        final String configKey = resolved.key;

        fetchConfigAsync(apiUrl, configUrl, configKey, new ConfigFetchCallback() {
            @Override
            public void success(String json) {
                try {
//                            LOG.longI("echo-ConfigJson", json);
                    if (switchApiCollectionIfNeeded(apiUrl, json)) {
                        FileUtils.saveCache(cache,json);
                        loadConfig(false, callback, activity);
                        return;
                    }
                    clearApiLinesIfUnmatched(apiUrl);
                    owner.parseJson(apiUrl, json);
                    FileUtils.saveCache(cache,json);
                    callback.success();
                } catch (Throwable th) {
                    LOG.e("ApiConfig", th);
                    callback.error(ApiConfig.str(R.string.toast_config_parse_failed));
                }
            }

            @Override
            public void error(String error) {
                // 本地源无权限读不到文件时**不回落旧快照**:回落会让用户以为源正常、实则内容永不更新
                if (isLocalSourceUnreadable(apiUrl)) {
                    callback.error(localSourceUnreadableMsg());
                    return;
                }
                // 文件已被删除/改名(2026-09-17)同理:回落快照只会显示删除前的旧内容,且快照重启/清缓存都不掉
                if (isLocalSourceMissing(apiUrl)) {
                    callback.error(localSourceMissingMsg());
                    return;
                }
                if (cache.exists()) {
                    try {
                        String json = readConfigFile(cache);
                        if (switchApiCollectionIfNeeded(apiUrl, json)) {
                            loadConfig(false, callback, activity);
                            return;
                        }
                        clearApiLinesIfUnmatched(apiUrl);
                        owner.parseJson(apiUrl, json);
                        callback.success();
                        return;
                    } catch (Throwable th) {
                        LOG.e("ApiConfig", th);
                    }
                }
                callback.error(ApiConfig.str(R.string.toast_config_fetch_failed, error));
            }
        });
    }

    void loadLiveConfig(boolean useCache, ApiConfig.LoadConfigCallback callback) {
        String apiUrl = ApiConfig.getEffectiveLiveUrl();
        if (apiUrl.isEmpty()) {
            callback.error("-1");
            return;
        }
        final String liveApiUrl = apiUrl;
        ConfigParser.ConfigUrl resolvedLive = ConfigParser.configUrl(liveApiUrl, ApiConfig::localFileBase);
        String liveApiConfigUrl = resolvedLive.url;
        final String liveConfigKey = resolvedLive.key;
        File live_cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(liveApiUrl));
        LOG.i("echo-load live config "+liveApiUrl);
        // 同 loadConfig:本地/局域网直播源不吃快照,否则失效的本地源会被旧快照长期掩盖
        if (useCache && live_cache.exists() && isRemoteSource(liveApiUrl)) {
            try {
                String json = readConfigFile(live_cache);
                if (switchLiveApiCollectionIfNeeded(liveApiUrl, json)) {
                    loadLiveConfig(false, callback);
                    return;
                }
                clearLiveApiLinesIfUnmatched(liveApiUrl);
                owner.parseLiveConfigContent(liveApiUrl, json);
                if (owner.hasLiveConfigResult()) {
                    owner.loadedLiveConfigUrl = liveApiUrl;
                    callback.success();
                    return;
                }
            } catch (Throwable th) {
                LOG.e("ApiConfig", th);
            }
        }
        fetchConfigAsync(liveApiUrl, liveApiConfigUrl, liveConfigKey, new ConfigFetchCallback() {
            @Override
            public void success(String json) {
                try {
                    if (switchLiveApiCollectionIfNeeded(liveApiUrl, json)) {
                        FileUtils.saveCache(live_cache, json);
                        loadLiveConfig(false, callback);
                        return;
                    }
                    clearLiveApiLinesIfUnmatched(liveApiUrl);
                    owner.parseLiveConfigContent(liveApiUrl, json);
                    if (!owner.hasLiveConfigResult()) {
                        callback.error(ApiConfig.str(R.string.toast_live_config_parse_failed));
                        return;
                    }
                    owner.loadedLiveConfigUrl = liveApiUrl;
                    FileUtils.saveCache(live_cache, json);
                    callback.success();
                } catch (Throwable th) {
                    LOG.e("ApiConfig", th);
                    callback.error(ApiConfig.str(R.string.toast_live_config_parse_failed));
                }
            }

            @Override
            public void error(String error) {
                if (isLocalSourceUnreadable(liveApiUrl)) {
                    callback.error(localSourceUnreadableMsg());
                    return;
                }
                // 与点播同款(2026-09-17):本地直播源文件被删后不再静默回落旧快照
                if (isLocalSourceMissing(liveApiUrl)) {
                    callback.error(localSourceMissingMsg());
                    return;
                }
                if (live_cache.exists()) {
                    try {
                        String json = readConfigFile(live_cache);
                        if (switchLiveApiCollectionIfNeeded(liveApiUrl, json)) {
                            loadLiveConfig(false, callback);
                            return;
                        }
                        clearLiveApiLinesIfUnmatched(liveApiUrl);
                        owner.parseLiveConfigContent(liveApiUrl, json);
                        if (owner.hasLiveConfigResult()) {
                            owner.loadedLiveConfigUrl = liveApiUrl;
                            callback.success();
                            return;
                        }
                    } catch (Throwable th) {
                        LOG.e("ApiConfig", th);
                    }
                }
                callback.error(ApiConfig.str(R.string.toast_live_config_fetch_failed));
            }
        });
    }

    /** 本地源文件不可读的提示(UI 直接展示) */
    private static String localSourceUnreadableMsg() {
        return ApiConfig.str(R.string.toast_local_source_unreadable);
    }

    /** 本地源文件已不存在的提示(UI 直接展示) */
    private static String localSourceMissingMsg() {
        return ApiConfig.str(R.string.toast_local_source_missing);
    }

    /**
     * 本机文件源(`clan://localhost/` / `file://`)且应用此刻读不到 ⇒ 本地服务按原始路径读必然 EACCES。
     * 把"静默回落 filesDir 旧快照"改成明确报错,否则用户改了本地 json 不生效且毫无提示(2026-09-16)。
     * ⚠️ 只判这两种"本机文件"形态:`clan://<ip>/…` 是局域网 TVBox 服务地址,与本地存储权限无关。
     * ⚠️ 权限查询为 false 不等于读不到(部分 ROM 上二者不一致),有目录授权兜底时同理 —— 漏判会把读得到的源报成"读不到"。
     */
    private static boolean isLocalSourceUnreadable(String apiUrl) {
        String path = localSourcePath(apiUrl);
        if (path == null) return false;
        if (LocalSourceTree.INSTANCE.serves(App.getInstance(), path)) return false;
        return !PermissionHelper.isStorageGranted(App.getInstance()) && !new File(path).canRead();
    }

    /**
     * 本机文件源的**目标文件已不存在**(2026-09-17)。
     *
     * <p>为什么单独判:把本地 json 删掉后本地服务返回 "File ... not found",拉取失败会静默回落
     * filesDir 里的旧快照并报 success —— 与 {@link #isLocalSourceUnreadable} 要避免的情况完全一致
     * (用户以为源正常、实则内容永不更新),而快照在 getFilesDir 下,重启/清缓存都不会掉。
     *
     * <p>⚠️ 调用方**必须**先判 {@link #isLocalSourceUnreadable}:无存储权限时 File.exists 的结论不可信
     * (可能把"读不到"误报成"不存在")。只判解析得出真实路径的两种形态,`clan://<ip>/…` 无此概念。
     */
    private static boolean isLocalSourceMissing(String apiUrl) {
        String path = localSourcePath(apiUrl);
        // 目录授权兜底时 File.exists 同样不可信(可能把"读不到"误报成"已删除"),由本地服务判读不到即报 not found
        if (path == null || LocalSourceTree.INSTANCE.serves(App.getInstance(), path)) return false;
        return !new File(path).exists();
    }

    /** 只有 http(s) 远程源吃快照;本地/局域网(clan:// 本机、file://、局域网 IP)的改动必须立即生效 */
    private static boolean isRemoteSource(String apiUrl) {
        return apiUrl != null && (apiUrl.startsWith("http://") || apiUrl.startsWith("https://"));
    }

    /** 本机文件源地址 → 真实路径(与 {@code RemoteServer} 的 `/file/` 同一映射);非本机形态或解析不出返回 null */
    private static String localSourcePath(String apiUrl) {
        if (apiUrl == null) return null;
        String url = apiUrl;
        int pk = url.indexOf(";pk;");
        if (pk >= 0) url = url.substring(0, pk);
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        if (url.startsWith("clan://localhost/")) {
            return Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/" + Uri.decode(url.substring("clan://localhost/".length()));
        }
        if (url.startsWith("file://")) {
            // 手写的地址可能带百分号编码(中文目录),解码后再判存在,避免把"存在"误报成"已删除"
            return Uri.decode(url.substring("file://".length()));
        }
        return null;
    }

    /**
     * 仓里第一条**没被停用**的子源地址;全被停用则返回空串。
     *
     * <p>为什么不直接用第一条:被看门狗停用的坏子源还留在仓配置里,重新启用这个仓时若照旧改写到它,
     * 用户就又崩一次 —— 名单在这里才有意义。全被停用时不改写(调用方会退回"把仓 JSON 当普通配置解析"),
     * 结果是空配置而不是闪退。
     */
    private static String firstUsableApiLine(ArrayList<String> apiLines) {
        for (String line : apiLines) {
            String url = HistoryHelper.getApiLineUrl(line);
            if (!TextUtils.isEmpty(url) && !BootGuard.isDisabledSource(url)) {
                return url;
            }
        }
        return "";
    }

    private boolean switchApiCollectionIfNeeded(String apiUrl, String jsonStr) {
        ArrayList<String> apiLines = ConfigParser.parseApiCollection(jsonStr);
        if (apiLines.isEmpty()) {
            return false;
        }
        String firstApi = firstUsableApiLine(apiLines);
        if (TextUtils.isEmpty(firstApi) || firstApi.equals(apiUrl)) {
            return false;
        }
        KV.put(HawkConfig.API_LINE_LIST, apiLines);
        KV.put(HawkConfig.API_LINE_SOURCE, apiUrl);
        KV.put(HawkConfig.API_URL, firstApi);
        HistoryHelper.setApiHistory(apiUrl);
        // 作废内存旧配置(2026-09-13):本方法把 API_URL 换成了合集里的首条线路,
        // 若该线路随后拉取失败,不先作废就会残留合集旧数据、首页继续显示旧内容
        owner.invalidateVodConfig();
        String liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "");
        if (TextUtils.isEmpty(liveApiUrl) || liveApiUrl.equals(apiUrl)) {
            KV.put(HawkConfig.LIVE_API_URL, firstApi);
            HistoryHelper.setLiveApiHistory(firstApi);
            // 直播此时跟随点播(2026-09-21):点播换仓后直播源也被改写,
            // 旧的直播仓列表已不对应当前直播源,必须一起作废,否则「配置切换」会列出上一仓的子源
            HistoryHelper.clearLiveApiLineList();
        }
        // 仓关系刚成立:界面(换仓入口/使用中标记)靠这个信号就地重读,不然要等页面重建
        ApiLineSignal.INSTANCE.notifyChanged();
        return true;
    }

    /**
     * 直播源的"多仓"(仓库)分流(2026-09-21,对齐 FongMi 的 {@code LiveConfig.parseDepot})。
     *
     * <p>改前直播侧只认 {@code lives},仓地址(顶层只有 {@code urls})解析出空列表 ⇒ 报"直播配置解析失败"。
     *
     * <p>与点播 {@link #switchApiCollectionIfNeeded} 的两点差异:改的是 {@code LIVE_API_URL};
     * 跟随点播时把 {@code API_URL} 一起指向首仓(两地址不一致会被 {@link ApiConfig#isLiveFollowVod()} 判成已脱离跟随)。
     */
    private boolean switchLiveApiCollectionIfNeeded(String apiUrl, String jsonStr) {
        ArrayList<String> apiLines = ConfigParser.parseApiCollection(jsonStr);
        if (apiLines.isEmpty()) {
            return false;
        }
        String firstApi = firstUsableApiLine(apiLines);
        if (TextUtils.isEmpty(firstApi) || firstApi.equals(apiUrl)) {
            return false;
        }
        KV.put(HawkConfig.LIVE_API_LINE_LIST, apiLines);
        KV.put(HawkConfig.LIVE_API_LINE_SOURCE, apiUrl);
        // 跟随态必须在改写 LIVE_API_URL 之前判定:isLiveFollowVod 靠"LIVE_API_URL 是否等于 API_URL"成立
        boolean followLive = ApiConfig.isLiveFollowVod();
        KV.put(HawkConfig.LIVE_API_URL, firstApi);
        if (followLive) {
            KV.put(HawkConfig.API_URL, firstApi);
            HistoryHelper.setApiHistory(firstApi);
        }
        HistoryHelper.setLiveApiHistory(apiUrl);
        owner.loadedLiveConfigUrl = "";
        owner.clearLiveConfigResult();
        // 换子源后旧子源的 hosts 映射要立刻失效,不能等这次加载成功(失败则残留到下次)
        owner.clearLiveHosts();
        ApiLineSignal.INSTANCE.notifyChanged();
        return true;
    }

    /** 与直播仓列表对不上号就清掉,免得「配置切换」继续列上一仓的子源;空地址(跟随态)不清 */
    private void clearLiveApiLinesIfUnmatched(String apiUrl) {
        if (TextUtils.isEmpty(apiUrl)) return;
        if (!HistoryHelper.isLiveApiLineUrl(apiUrl) && !HistoryHelper.isLiveApiLineSource(apiUrl)) {
            HistoryHelper.clearLiveApiLineList();
        }
    }

    private void clearApiLinesIfUnmatched(String apiUrl) {
        ArrayList<String> apiLines = KV.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
        if (apiLines.isEmpty()) {
            return;
        }
        for (String apiLine : apiLines) {
            if (apiUrl.equals(HistoryHelper.getApiLineUrl(apiLine))) {
                return;
            }
        }
        HistoryHelper.clearApiLineList();
    }

    private interface ConfigFetchCallback {
        void success(String body);

        void error(String error);
    }

    private void fetchConfigAsync(final String apiUrl, final String requestUrl, final String configKey, final ConfigFetchCallback callback) {
        configLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String result = "";
                String error = "";
                okhttp3.Response response = null;
                long startCost = System.currentTimeMillis();
                try {
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(requestUrl)
                            .build();
                    okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
                    if (client == null) client = com.github.catvod.net.OkHttp.client();
                    response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        error = "HTTP " + response.code();
                    } else if (response.body() == null) {
                        error = "empty body";
                    } else {
                        result = ApiConfig.FindResult(response.body().string(), configKey);
                        // 包装必须早于下面的 clanContentFix:否则包装出的 clan:// api 不会被换成本机服务地址
                        String packedPy = PySourcePack.packUrl(apiUrl, result);
                        if (packedPy != null) result = packedPy;
                        if (apiUrl.startsWith("clan")) {
                            result = ConfigParser.clanContentFix(ConfigParser.clanToAddress(apiUrl, ApiConfig::localFileBase), result);
                        }
                        result = ConfigParser.fixContentPath(apiUrl, result, ApiConfig::localFileBase);
                    }
                } catch (Throwable th) {
                    error = th.getMessage();
                    if (TextUtils.isEmpty(error)) error = th.toString();
                } finally {
                    if (response != null) SpiderLoader.closeQuietly(response.body());
                }
                final String finalResult = result;
                final String finalError = error;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (TextUtils.isEmpty(finalError)) {
                            callback.success(finalResult);
                        } else {
                            callback.error(finalError);
                        }
                    }
                });
            }
        });
    }

    private String readConfigFile(File f) throws Throwable {
        // BugReview #27:close 放 finally/try-with-resources,读失败时防 FD 泄漏
        try (BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String s = "";
            while ((s = bReader.readLine()) != null) {
                sb.append(s + "\n");
            }
            return sb.toString();
        }
    }
}
