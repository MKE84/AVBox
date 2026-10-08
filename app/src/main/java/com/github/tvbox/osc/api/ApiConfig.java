package com.github.tvbox.osc.api;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.ProxyRule;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HeaderGuard;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.SourceManager;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.github.tvbox.osc.util.KV;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class ApiConfig {
    // volatile:DCL 单例必须(2026-09-12 修复 Bug)。首次构造可能发生在 AppBootstrap 的 IO 协程上,
    // 而主线程 Compose 同时也在调 get() —— 构造函数很重(clearLoader + loadDefaultConfig 解析大 JSON),
    // 无 volatile 时其他线程可能读到未完全初始化的实例
    private static volatile ApiConfig instance;
    private final LinkedHashMap<String, SourceBean> sourceBeanList;
    // 排序/缓存判定会在后台线程读,配置加载线程写
    private volatile SourceBean mHomeSource;
    private ParseBean mDefaultParse;
    private final List<LiveChannelGroup> liveChannelGroupList;
    private final List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    // 点播/直播两套 hosts 分开存:合并视图见 getMyHost,避免直播配置把点播的覆盖掉。
    // volatile:DNS 解析在 OkHttp 线程读,配置解析在主线程写
    private volatile Map<String,String> vodHosts;
    private volatile Map<String,String> liveHosts;
    String loadedLiveConfigUrl = "";
    private String danmaku = "";
    private volatile String configLogo = ""; // 配置级头像(接口 JSON 顶层 "logo")

    public String getConfigLogo() {
        return configLogo == null ? "" : configLogo;
    }

    private final SourceBean emptyHome = new SourceBean();

    /** 爬虫装载:jar/js/py 加载器与 jar 下载链路 */
    private final SpiderLoader spiderLoader = new SpiderLoader();
    /** /proxy 请求路由(jar/js/py 爬虫与直连回退) */
    private final ProxyEntry proxyEntry = new ProxyEntry(this, spiderLoader);
    /** spider 预热队列(独占线程 + 单项限时) */
    private final WarmQueue warmQueue = new WarmQueue(this, spiderLoader);
    /** 配置拉取编排(快照回落/仓分流/本地源判断) */
    private final ConfigLoader configLoader = new ConfigLoader(this);
    private final Gson gson;

    private ApiConfig() {
        clearLoader();
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
        searchSourceBeanList = new ArrayList<>();
        gson = new Gson();
        KV.put(HawkConfig.LIVE_GROUP_LIST,new JsonArray());
        loadDefaultConfig();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            if (AES.isJson(content)) return content;
            Pattern pattern = getPattern("[A-Za-z0-9]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if(matcher.find()){
                content=content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            content = content.trim();
            if (content.startsWith("2423")) {
                content = content.replaceAll("\\s+", "");
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            }else if (configKey !=null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            }
            else{
                json = content;
            }
        } catch (Exception e) {
            LOG.e("ApiConfig", e);
        }
        return json;
    }

    /** 本机服务基址:用 Supplier 传给解析器,保证只在 clan://localhost/ 地址上才求值 */
    static String localFileBase() {
        return ControlManager.get().getAddress(true);
    }

    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        configLoader.loadConfig(useCache, callback, activity);
    }

    /**
     * 实际生效的直播配置地址(2026-09-12 点播/直播拆分):
     * 独立直播源(LIVE_API_URL)优先;未单独配置时回落到当前点播源(API_URL)。
     * 直播侧全部走这个方法取地址,避免各处重复写"空则回落"的判断。
     */
    public static String getEffectiveLiveUrl() {
        String liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "");
        return TextUtils.isEmpty(liveApiUrl) ? KV.get(HawkConfig.API_URL, "") : liveApiUrl;
    }

    /**
     * 直播是否跟随点播源(2026-09-12 点播/直播拆分):
     * LIVE_API_URL 为空,或与 API_URL 相同 —— 后者是旧版"切源双写"留下的存量状态,语义与"跟随"等价,
     * 因此无需数据迁移:老用户升级后行为与升级前完全一致,且点播换源时直播会继续跟随。
     */
    public static boolean isLiveFollowVod() {
        String liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "");
        if (TextUtils.isEmpty(liveApiUrl)) {
            return true;
        }
        // 两者都非空才比较;API_URL 为空(未配置点播)时直播源独立存在,不算跟随
        return liveApiUrl.equals(KV.get(HawkConfig.API_URL, ""));
    }

    public void loadLiveConfig(boolean useCache, LoadConfigCallback callback) {
        configLoader.loadLiveConfig(useCache, callback);
    }

    /**
     * 资源文案;App 未就绪(极早调用/单测)返回空串,不抛异常。
     * 走 {@link LanguageManager#localized}:Application 的 base 只在进程启动时挂一次,切语言后
     * 直接用 app.getString 会停在旧语言。
     */
    static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    boolean hasLiveConfigResult() {
        return liveChannelGroupList != null && !liveChannelGroupList.isEmpty();
    }

    public boolean shouldReloadLiveConfig() {
        String apiUrl = getEffectiveLiveUrl();
        return liveChannelGroupList == null || liveChannelGroupList.isEmpty() || !apiUrl.equals(loadedLiveConfigUrl);
    }

    /**
     * 作废已加载的直播内存态(2026-09-12 点播/直播拆分):点播源或直播源变更后调用,
     * 让直播页下次进入必然重载。只清内存与"已加载来源"标记,不动 KV 与磁盘缓存(离线仍可用缓存兜底)。
     */
    public void invalidateLiveConfig() {
        liveChannelGroupList.clear();
        loadedLiveConfigUrl = "";
    }

    public static String getLiveGroupIndexKey() {
        String liveApiUrl = KV.get(HawkConfig.LIVE_API_URL, "");
        if (liveApiUrl == null || liveApiUrl.length() == 0) {
            return HawkConfig.LIVE_GROUP_INDEX;
        }
        return HawkConfig.LIVE_GROUP_INDEX + "_" + liveApiUrl;
    }

    public static int getLiveGroupIndex() {
        return KV.get(getLiveGroupIndexKey(), 0);
    }

    public static void setLiveGroupIndex(int index) {
        KV.put(getLiveGroupIndexKey(), index);
    }

    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        spiderLoader.loadJar(useCache, spider, callback);
    }

    /** 直播配置数据清场,不动 KV 与仓列表 —— 供"换仓后重新拉取"先丢弃旧结果用 */
    void clearLiveConfigResult() {
        liveChannelGroupList.clear();
        spiderLoader.setLiveSpider("");
        spiderLoader.resetCurrentLiveSpider();
        initLiveSettings();
        KV.put(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
    }

    private void resetConfigData() {
        warmQueue.bumpGeneration();
        clearSpiderCache();
        proxyEntry.setCurrentPlaySourceKey("");
        configLogo = "";
        sourceBeanList.clear();
        liveChannelGroupList.clear();
        parseBeanList.clear();
        searchSourceBeanList = new ArrayList<>();
        KV.put(HawkConfig.LIVE_GROUP_LIST,new JsonArray());
        // 只清点播那份 hosts:独立直播源的映射由直播配置自己维护
        vodHosts = null;
        // 跟随态下 liveHosts 就是点播 hosts 的副本,一并清掉才不会让被删源的映射继续生效
        if (isLiveFollowVod()) liveHosts = null;
        OkGoHelper.refreshHosts();
    }

    /**
     * 清空全部配置(点播 + 直播)。
     * 2026-09-12 点播/直播拆分后,业务侧通常应改用 {@link #clearVodConfig()} / {@link #clearLiveConfig()} ——
     * 删空点播源不应连坐清掉用户单独配置的直播源。
     */
    public void clearConfig() {
        clearVodConfig();
        clearLiveConfig();
    }

    /**
     * 清空点播配置(2026-09-12,配置管理页删光点播源时调用):
     * 内存源数据与 KV 点播地址一并清空,回到「尚未配置订阅接口」的初始状态;
     * 调用方随后执行 AppBootstrap.retry() 即可让各页按未配置刷新(getHomeSourceBean 有 emptyHome 兜底)。
     * **独立直播源不受影响**;直播若处于跟随态则 LIVE_API_URL 一并置空(否则会变成指向旧点播源的陈旧快照)。
     */
    public void clearVodConfig() {
        boolean followLive = isLiveFollowVod(); // 必须在清空 API_URL 之前判定
        resetConfigData();
        mHomeSource = null;
        KV.put(HawkConfig.API_URL, "");
        KV.put(HawkConfig.HOME_API, "");
        HistoryHelper.clearApiLineList();
        if (followLive) {
            KV.put(HawkConfig.LIVE_API_URL, "");
            // 跟随态下直播源就是点播源(2026-09-21):点播仓列表已清,直播仓列表同理作废
            HistoryHelper.clearLiveApiLineList();
        }
        invalidateLiveConfig();
    }

    /** 清空独立直播源并回到「跟随点播源」(2026-09-12):点播配置完全不受影响 */
    public void clearLiveConfig() {
        KV.put(HawkConfig.LIVE_API_URL, "");
        // 仓列表跟着被清掉的直播源一起作废(2026-09-21):留着会在「配置切换」里列出已失效的子源
        HistoryHelper.clearLiveApiLineList();
        clearLiveHosts();
        invalidateLiveConfig();
    }

    /** 直播源被换掉/切回跟随时清直播侧 hosts:否则旧源的 DNS 映射会一直生效到下次加载成功 */
    public void clearLiveHosts() {
        liveHosts = null;
        OkGoHelper.refreshHosts();
    }

    /**
     * 作废内存里的点播配置(**不**动 KV 地址,2026-09-13)。
     *
     * 存在的理由:[loadConfig] 失败时走的是 `callback.error(...)`,**根本不会调用 [parseJson]**,
     * 而清场动作 `resetConfigData()` 只在 parseJson 开头执行 —— 于是单例里的
     * `sourceBeanList` / `mHomeSource` / `parseBeanList` 全部保留着**上一个源**的数据,
     * 首页套用旧源继续正常显示与播放,可 KV 里的 `API_URL` 已经指向新源:
     * 表现就是"运行中用旧源、重启后才发现新源不可用"的状态不一致。
     *
     * 因此在**切换点播源之前**调用本方法:新源拉取成功会由 parseJson 重新填充;
     * 拉取失败时首页自然落到空态/未配置引导态,与「配置加载失败」弹窗一致,
     * 不会再拿旧源冒充新源(同类修复先例:2026-09-11「删空订阅列表仍用着被删的源」)。
     */
    public void invalidateVodConfig() {
        resetConfigData();
        mHomeSource = null;
        invalidateLiveConfig();
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

    void parseJson(String apiUrl, String jsonStr) {
        // 源列表格式归一化:裸源数组(uzVideo 风格)自动包装成标准 sites 对象
        jsonStr = ConfigParser.normalizeSourceListConfig(jsonStr);
        resetConfigData();
        // 规则表等新配置到手再清:换源失败时旧规则要留给仍在播的旧源,清早了会让广告回归/click 失效
        VideoParseRuler.clearRule();
        LOG.i("echo-apiurl:" + apiUrl);
        JsonObject infoJson = gson.fromJson(jsonStr, JsonObject.class);
        // 配置级头像(2026-09-10):接口 JSON 顶层 "logo",胶囊头像的兜底来源(站点级 icon 优先)
        configLogo = DefaultConfig.safeJsonString(infoJson, "logo", "");
        // spider
        spiderLoader.setSpider(DefaultConfig.safeJsonString(infoJson, "spider", ""));
        spiderLoader.setJarCache(DefaultConfig.safeJsonString(infoJson, "jarCache", "true"));
        danmaku = DefaultConfig.safeJsonString(infoJson, "danmaku", "");
        // 远端站点源
        List<SourceBean> sites = ConfigParser.parseSites(infoJson);
        // 过滤用户手动隐藏(删除)的源,避免订阅刷新后"失效源复活"
        java.util.List<SourceBean> visible = new java.util.ArrayList<>();
        SourceManager sm = SourceManager.get();
        for (SourceBean sb : sites) {
            if (sm.isHidden(sb.getKey())) {
                LOG.i("echo-skip hidden source: " + sb.getName() + " (" + sb.getKey() + ")");
                continue;
            }
            visible.add(sb);
        }
        for (SourceBean sb : visible) {
            sourceBeanList.put(sb.getKey(), sb);
        }
        SourceBean firstSite = firstVisibleSite(visible);
        if (sourceBeanList != null && sourceBeanList.size() > 0) {
            String home = KV.get(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null) {
                assert firstSite != null;
                setSourceBean(firstSite);
            }
            else
                setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        parseBeanList.clear();
        List<ParseBean> parsedParses = ConfigApplier.parseParseBeans(infoJson);
        if (!parsedParses.isEmpty()) {
            // 白名单放宽(2026-10-08):只剔除超级解析(type=4,常在"正在嗅探"假死),
            // 其余解析(type0 WebView嗅探 / type1 json / type3 聚合)全部保留 ——
            // 原"只留盘古/解析4/聚合"过窄:默认的盘古那条是"跨域 iframe 套娃"解析站,
            // 失败后没有可用兜底,只能卡到超时。
            List<ParseBean> filtered = new java.util.ArrayList<>();
            for (ParseBean pb : parsedParses) {
                if (pb.getType() != 4) filtered.add(pb);
            }
            parseBeanList.addAll(filtered);
            // 超级解析(type=4)彻底关闭:它默认排在首位,常卡在"正在嗅探播地址"假死,
            // 用户要求不再默认使用它,统一走普通 URL 解析(默认第一个=盘古)与 type=3 聚合。
            // addSuperParse(); // 已移除
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = KV.get(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }

        // 直播源(已移除:无直播 UI 入口,不读 lives、不拉取直播网络,避免落后源拖慢配置加载)
        String live_api_url = KV.get(HawkConfig.LIVE_API_URL, "");
        if(live_api_url.isEmpty() || apiUrl.equals(live_api_url)){
            LOG.i("echo-load-config_live");
            initLiveSettings();
            // 不再解析 lives / 不再调用 loadLiveApi(直播功能已删,这里纯读取+拉网络无意义)
        }

        // 写完立即刷新:下方 rules/ads 段若抛异常,快照不会停在上一条配置的映射上
        vodHosts = infoJson.has("hosts") ? ConfigParser.parseHosts(infoJson.getAsJsonArray("hosts")) : null;
        OkGoHelper.refreshHosts();

        loadProxyRules(infoJson);

        ConfigApplier.applyHostRules(infoJson);

        ConfigApplier.applyDoh(infoJson);
        LOG.i("echo-api-config-----------load");
        ConfigApplier.applyAds(infoJson);
    }

    private void loadDefaultConfig() {
        JsonObject defaultJson = gson.fromJson(FileUtils.getAsOpen("default_config.json"), JsonObject.class);
        if (defaultJson == null) {
            LOG.e("ApiConfig: default_config.json unavailable");
            return;
        }
        // 广告地址
        if(AdBlocker.isEmpty()){
            //默认广告拦截
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.getAsString());
            }
        }
        LOG.i("echo-default-config-----------load");
    }
    private void parseLiveConfigContent(String apiUrl, File f) throws Throwable {
        // BugReview #27:close 放 finally/try-with-resources,读失败时防 FD 泄漏
        String content;
        try (BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String s = "";
            while ((s = bReader.readLine()) != null) {
                sb.append(s + "\n");
            }
            content = sb.toString();
        }
        parseLiveConfigContent(apiUrl, content);
    }

    void parseLiveConfigContent(String apiUrl, String content) {
        String jsonContent = ConfigParser.trimJsonObject(content);
        if (!TextUtils.isEmpty(jsonContent)) {
            try {
                JsonObject infoJson = gson.fromJson(jsonContent, JsonObject.class);
                if (infoJson != null && infoJson.has("lives")) {
                    parseLiveJson(apiUrl, jsonContent);
                    return;
                }
            } catch (Throwable ignored) {
                LOG.d("ApiConfig", "live config json parse failed, fallback to text");
            }
        }
        if (ConfigParser.isLiveJsonContent(content)) {
            parseLiveJson(apiUrl, jsonContent);
        } else {
            parseLiveText(apiUrl, content);
        }
    }

    private void parseLiveText(String apiUrl, String content) {
        liveChannelGroupList.clear();
        spiderLoader.setLiveSpider("");
        spiderLoader.resetCurrentLiveSpider();
        initLiveSettings();
        KV.put(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
        KV.put(HawkConfig.EPG_URL, ConfigParser.extractLiveTextEpg(content));
        KV.put(HawkConfig.LIVE_WEB_HEADER, null);
        // 文本直播配置没有 hosts 字段:清掉上一份直播源留下的映射,否则会继续生效
        liveHosts = null;
        OkGoHelper.refreshHosts();
        JsonArray livesArray = TxtSubscribe.parseToJsonArray(content);
        loadLives(livesArray);
        LOG.i("echo-live-text-config-----------load:" + apiUrl);
    }

    private void parseLiveJson(String apiUrl, String jsonStr) {
        liveChannelGroupList.clear();
        JsonObject infoJson = gson.fromJson(jsonStr, JsonObject.class);
        // spider
        spiderLoader.setLiveSpider(DefaultConfig.safeJsonString(infoJson, "spider", ""));
        // 直播源(已移除:无直播 UI 入口,不解析 lives、不拉取直播网络)
        initLiveSettings();

        liveHosts = infoJson.has("hosts") ? ConfigParser.parseHosts(infoJson.getAsJsonArray("hosts")) : null;
        // DNS 只认 OkGoHelper.myHosts 快照,写完必须刷新,否则直播 hosts 实际不生效
        OkGoHelper.refreshHosts();
        LOG.i("echo-api-live-config-----------load");
    }

    private final List<LiveSettingGroup> liveSettingGroupList = new ArrayList<>();
    private void initLiveSettings() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList(
                str(R.string.live_group_line), str(R.string.live_group_scale), str(R.string.live_group_decoder),
                str(R.string.live_group_timeout), str(R.string.settings_preference_title),
                str(R.string.live_group_multi_source), str(R.string.live_group_config_switch)));
        ArrayList<ArrayList<String>> itemsArrayList = new ArrayList<>();
        ArrayList<String> sourceItems = new ArrayList<>();
        ArrayList<String> scaleItems = new ArrayList<>(Arrays.asList(
                str(R.string.common_default), "16:9", "4:3",
                str(R.string.player_scale_fill), str(R.string.player_scale_origin), str(R.string.player_scale_crop)));
        ArrayList<String> playerDecoderItems = new ArrayList<>(Arrays.asList(
                str(R.string.player_decode_hard), str(R.string.player_decode_soft)));
        ArrayList<String> timeoutItems = new ArrayList<>(Arrays.asList("5s", "10s", "15s", "20s", "25s", "30s"));
        ArrayList<String> personalSettingItems = new ArrayList<>(Arrays.asList(
                str(R.string.live_setting_show_time), str(R.string.live_setting_show_speed),
                str(R.string.live_setting_reverse), str(R.string.live_setting_cross_group)));
        ArrayList<String> yumItems = new ArrayList<>();
        ArrayList<String> liveApiHistoryItems = new ArrayList<>();

        itemsArrayList.add(sourceItems);
        itemsArrayList.add(scaleItems);
        itemsArrayList.add(playerDecoderItems);
        itemsArrayList.add(timeoutItems);
        itemsArrayList.add(personalSettingItems);
        itemsArrayList.add(yumItems);
        itemsArrayList.add(liveApiHistoryItems);

        liveSettingGroupList.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            LiveSettingGroup liveSettingGroup = new LiveSettingGroup();
            ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
            liveSettingGroup.setGroupIndex(i);
            liveSettingGroup.setGroupName(groupNames.get(i));
            for (int j = 0; j < itemsArrayList.get(i).size(); j++) {
                LiveSettingItem liveSettingItem = new LiveSettingItem();
                liveSettingItem.setItemIndex(j);
                liveSettingItem.setItemName(itemsArrayList.get(i).get(j));
                liveSettingItemList.add(liveSettingItem);
            }
            liveSettingGroup.setLiveSettingItems(liveSettingItemList);
            liveSettingGroupList.add(liveSettingGroup);
        }
        refreshLiveApiHistoryItems();
    }

    public List<LiveSettingGroup> getLiveSettingGroupList() {
        return liveSettingGroupList;
    }

    /**
     * 刷新直播设置「配置切换」组(第 6 组):第 0 项固定为合成的「跟随点播源」(无条件占位,避免下标漂移),
     * 其后为候选项 —— 第 i 项的 itemIndex = i + 1。
     *
     * <p>2026-09-21 多仓:当前直播源来自仓列表时,第 1 项起改列**仓里的子源**而不是配置历史。
     */
    public void refreshLiveApiHistoryItems() {
        if (liveSettingGroupList.size() < 7) return;
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        LiveSettingItem followItem = new LiveSettingItem();
        followItem.setItemIndex(0);
        followItem.setItemName(str(R.string.live_follow_vod_source));
        liveSettingItemList.add(followItem);
        ArrayList<String> entries = getLiveConfigEntries();
        for (int i = 0; i < entries.size(); i++) {
            LiveSettingItem liveSettingItem = new LiveSettingItem();
            liveSettingItem.setItemIndex(i + 1);
            liveSettingItem.setItemName(HistoryHelper.getApiLineName(entries.get(i)));
            liveSettingItemList.add(liveSettingItem);
        }
        liveSettingGroupList.get(6).setLiveSettingItems(liveSettingItemList);
    }

    /** 「配置切换」当前列的是仓列表还是配置历史 —— UI 点击/删除时据此取值 */
    public boolean isLiveApiLineMode() {
        return HistoryHelper.isLiveApiLineUrl(KV.get(HawkConfig.LIVE_API_URL, ""));
    }

    /** 「配置切换」第 1 项起的条目:仓模式给仓列表,否则给配置历史(与上面刷新用的是同一份) */
    public ArrayList<String> getLiveConfigEntries() {
        return HistoryHelper.isLiveApiLineUrl(KV.get(HawkConfig.LIVE_API_URL, ""))
                ? HistoryHelper.getLiveApiLines()
                : KV.get(HawkConfig.LIVE_API_HISTORY, new ArrayList<String>());
    }

    /**
     * 同 {@link #getLiveConfigEntries()},但剥成纯地址列表。
     *
     * <p>条目是 {@code "名字\t链接"} 的行,而选中判定要比对地址 —— 直接拿整行去 indexOf 永远匹配不上
     * (表现为「配置切换」当前项不高亮)。
     */
    public ArrayList<String> getLiveConfigUrls() {
        ArrayList<String> urls = new ArrayList<>();
        for (String entry : getLiveConfigEntries()) {
            String url = HistoryHelper.getApiLineUrl(entry);
            if (!TextUtils.isEmpty(url)) urls.add(url);
        }
        return urls;
    }

    /** 「配置切换」组第 {@code position} 项对应的直播源地址(第 0 项是「跟随点播源」,返回空串) */
    public String getLiveApiHistoryUrl(int position) {
        ArrayList<String> urls = getLiveConfigUrls();
        int index = position - 1;
        if (index < 0 || index >= urls.size()) return "";
        return urls.get(index);
    }

    public void loadLives(JsonArray livesArray) {
        liveChannelGroupList.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            String[] splitGroupName = groupName.split("_", 2);
            liveChannelGroup.setGroupName(splitGroupName[0]);
            if (splitGroupName.length > 1)
                liveChannelGroup.setGroupPassword(splitGroupName[1]);
            else
                liveChannelGroup.setGroupPassword("");
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                // 没有地址的频道点了必崩(频道地址表为空),与点不开的站点一样整条跳过
                if (urls.isEmpty()) {
                    LOG.i("echo-skip live channel without url: " + obj);
                    continue;
                }
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelLogo(DefaultConfig.safeJsonString(obj, "logo", ""));
                liveChannelItem.setChannelEpg(DefaultConfig.safeJsonString(obj, "epg", ""));
                liveChannelItem.setChannelUa(DefaultConfig.safeJsonString(obj, "ua", ""));
                liveChannelItem.setChannelClick(DefaultConfig.safeJsonString(obj, "click", ""));
                liveChannelItem.setChannelFormat(DefaultConfig.safeJsonString(obj, "format", ""));
                liveChannelItem.setChannelOrigin(DefaultConfig.safeJsonString(obj, "origin", ""));
                liveChannelItem.setChannelReferer(DefaultConfig.safeJsonString(obj, "referer", ""));
                liveChannelItem.setChannelTvgId(DefaultConfig.safeJsonString(obj, "tvg-id", ""));
                liveChannelItem.setChannelTvgName(DefaultConfig.safeJsonString(obj, "tvg-name", ""));
                if (obj.has("parse")) {
                    try {
                        liveChannelItem.setChannelParse(obj.get("parse").getAsInt());
                    } catch (Throwable ignored) {
                        LOG.d("ApiConfig", "channel parse flag not an int, use default");
                    }
                }
                JsonObject catchupObj = ConfigParser.parseLiveCatchup(obj);
                if (catchupObj != null) liveChannelItem.setChannelCatchup(catchupObj);
                if (obj.has("header") && obj.get("header").isJsonObject()) {
                    JsonObject headerObj = obj.getAsJsonObject("header");
                    HashMap<String, String> channelHeader = new HashMap<>();
                    for (Map.Entry<String, JsonElement> entry : headerObj.entrySet()) {
                        if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) continue;
                        String value = entry.getValue().getAsString();
                        if (!HeaderGuard.isSendable(entry.getKey(), value)) {
                            LOG.i("echo-channel-header-skip:" + entry.getKey());
                            continue;
                        }
                        channelHeader.put(entry.getKey(), value);
                    }
                    liveChannelItem.setChannelHeader(channelHeader);
                }
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add(str(R.string.live_source_index_name, sourceIndex));
                    sourceIndex++;
                }
                String channelName = ConfigParser.parseLiveChannelName(obj, sourceUrls);
                if (channelName.isEmpty()) {
                    LOG.i("echo-skip live channel without name/url: " + obj);
                    continue;
                }
                liveChannelItem.setChannelName(channelName);
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                if (mergeLiveChannel(liveChannelGroup.getLiveChannels(), liveChannelItem)) {
                    liveChannelItem.setChannelIndex(channelIndex++);
                    liveChannelItem.setChannelNum(++channelNum);
                }
            }
            liveChannelGroupList.add(liveChannelGroup);
        }
    }

    private boolean mergeLiveChannel(ArrayList<LiveChannelItem> channelItems, LiveChannelItem newItem) {
        LiveChannelItem oldItem = findLiveChannel(channelItems, newItem.getChannelName());
        if (oldItem == null) {
            channelItems.add(newItem);
            return true;
        }
        mergeLiveChannelUrls(oldItem, newItem);
        return false;
    }

    private LiveChannelItem findLiveChannel(ArrayList<LiveChannelItem> channelItems, String channelName) {
        for (LiveChannelItem item : channelItems) {
            if (channelName != null && channelName.equals(item.getChannelName())) return item;
        }
        return null;
    }

    private void mergeLiveChannelUrls(LiveChannelItem oldItem, LiveChannelItem newItem) {
        ArrayList<String> oldUrls = oldItem.getChannelUrls();
        ArrayList<String> oldSourceNames = oldItem.getChannelSourceNames();
        if (oldUrls == null) {
            oldUrls = new ArrayList<>();
            oldItem.setChannelUrls(oldUrls);
        }
        if (oldSourceNames == null) {
            oldSourceNames = new ArrayList<>();
            oldItem.setChannelSourceNames(oldSourceNames);
        }
        while (oldSourceNames.size() < oldUrls.size()) {
            oldSourceNames.add(str(R.string.live_source_index_name, oldSourceNames.size() + 1));
        }
        ArrayList<String> newUrls = newItem.getChannelUrls();
        ArrayList<String> newSourceNames = newItem.getChannelSourceNames();
        if (newUrls == null) return;
        for (int i = 0; i < newUrls.size(); i++) {
            String url = newUrls.get(i);
            if (oldUrls.contains(url)) continue;
            oldUrls.add(url);
            if (newSourceNames != null && i < newSourceNames.size()) {
                oldSourceNames.add(newSourceNames.get(i));
            } else {
                oldSourceNames.add(str(R.string.live_source_index_name, oldSourceNames.size() + 1));
            }
        }
        oldItem.setChannelUrls(oldUrls);
        oldItem.setChannelSourceNames(oldSourceNames);
    }

    public void loadLiveApi(JsonObject livesOBJ) {
        try {
            LOG.i("echo-loadLiveApi");
            liveChannelGroupList.clear();
            spiderLoader.resetCurrentLiveSpider();
            String lives = livesOBJ.toString();
            int index = lives.indexOf("proxy://");
            String url;
            if (index != -1) {
                int endIndex = lives.lastIndexOf("\"");
                url = lives.substring(index, endIndex);
                url = DefaultConfig.checkReplaceProxy(url);
                String extUrl = Uri.parse(url).getQueryParameter("ext");
                if (extUrl != null && !extUrl.isEmpty()) {
                    String extUrlFix;
                    if(extUrl.startsWith("http") || extUrl.startsWith("clan://")){
                        extUrlFix = extUrl;
                    }else {
                        extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                    }
                    extUrlFix = Base64.encodeToString(extUrlFix.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                    url = url.replace(extUrl, extUrlFix);
                }
            } else {
                String api = livesOBJ.has("api") ? livesOBJ.get("api").getAsString().trim() : "";
                String type = livesOBJ.has("type") ? livesOBJ.get("type").getAsString() : (SpiderLoader.isLiveSpiderApi(api) ? "3" : "0");
                if(type.equals("0") || type.equals("3")){
                    url = livesOBJ.has("url")?livesOBJ.get("url").getAsString():"";
                    if(url.isEmpty())url=api;
                    LOG.i("echo-liveurl"+url);
                    if(!url.startsWith("http://127.0.0.1")){
                        if(url.startsWith("http")){
                            url = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                        }
                        url ="http://127.0.0.1:9978/proxy?do=live&type=txt&ext="+url;
                    }
                    if(type.equals("3")){
                        String jarUrl = livesOBJ.has("jar")?livesOBJ.get("jar").getAsString().trim():"";
                        spiderLoader.loadLiveSpider(api, jarUrl, livesOBJ);
                    }
                } else {
                    // fongmi 的 lives 无 type 字段,TVBox 上游同样只认 0/3:未知取值保持拒载
                    LOG.i("echo-live-unsupported-type:" + type + " api:" + api);
                    resetLiveKvOnUnsupportedLine();
                    return;
                }
            }
            //设置epg
            if(livesOBJ.has("epg")){
                String epg =livesOBJ.get("epg").getAsString();
                KV.put(HawkConfig.EPG_URL,epg);
            }else {
                KV.put(HawkConfig.EPG_URL,"");
            }
            //设置UA
            if(livesOBJ.has("timeout")){
                int timeout = Math.max(5, Math.min(30, livesOBJ.get("timeout").getAsInt()));
                KV.put(HawkConfig.LIVE_CONNECT_TIMEOUT, (timeout + 4) / 5 - 1);
            }
            if(livesOBJ.has("header") && livesOBJ.get("header").isJsonObject()) {
                JsonObject headerObj = livesOBJ.getAsJsonObject("header");
                HashMap<String, String> liveHeader = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : headerObj.entrySet()) {
                    if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) continue;
                    String value = entry.getValue().getAsString();
                    if (!HeaderGuard.isSendable(entry.getKey(), value)) {
                        LOG.i("echo-live-header-skip:" + entry.getKey());
                        continue;
                    }
                    liveHeader.put(entry.getKey(), value);
                }
                KV.put(HawkConfig.LIVE_WEB_HEADER, liveHeader);
            } else if(livesOBJ.has("ua")) {
                String ua = DefaultConfig.safeJsonString(livesOBJ, "ua", "");
                HashMap<String,String> liveHeader = new HashMap<>();
                liveHeader.put("User-Agent", ua);
                KV.put(HawkConfig.LIVE_WEB_HEADER, liveHeader);
            }else {
                KV.put(HawkConfig.LIVE_WEB_HEADER,null);
            }
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setGroupName(url);
            liveChannelGroupList.clear();
            liveChannelGroupList.add(liveChannelGroup);
        } catch (Throwable th) {
            LOG.e("ApiConfig", th);
        }
    }

    /** 线路被拒载时的 KV 复位:与文本直播分支保持同一套"无直播配置"状态,避免沿用上一条线路的 EPG/UA */
    private void resetLiveKvOnUnsupportedLine() {
        KV.put(HawkConfig.EPG_URL, "");
        KV.put(HawkConfig.LIVE_WEB_HEADER, null);
    }

    public void setLiveJar(String liveJar) {
        spiderLoader.setLiveJar(liveJar);
    }

    public String getSpider() {
        return spiderLoader.getSpider();
    }

    public String getDanmaku() {
        return danmaku == null ? "" : danmaku;
    }

    public Spider getCSP(SourceBean sourceBean) {
        return spiderLoader.getCSP(sourceBean);
    }

    public void warmSearchSpiders() {
        warmQueue.warmSearchSpiders(new ArrayList<>(sourceBeanList.values()), getHomeSourceBean());
    }

    public Spider getPyCSP(String url) {
        return spiderLoader.getPyCSP(url);
    }

    public Spider getJsCSP(String url) {
        return spiderLoader.getJsCSP(url);
    }

    public Spider getLiveCSP(String url) {
        return spiderLoader.getLiveCSP(url);
    }

    public void searchDanmuUi(String name, String episode, boolean longClick) {
        spiderLoader.searchDanmuUi(name, episode, longClick);
    }

    public boolean hasDanmuSearchUi() {
        return spiderLoader.hasDanmuSearchUi();
    }

    public int getLiveConnectTimeoutSeconds() {
        return (KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5;
    }

    public Object[] proxyLocal(Map<String, String> param) {
        return proxyEntry.proxyLocal(param);
    }

    public void setCurrentPlaySourceKey(String sourceKey) {
        proxyEntry.setCurrentPlaySourceKey(sourceKey);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return spiderLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return spiderLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        void success();

        void error(String msg);
        void notice(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        if (!sourceBeanList.containsKey(key)) {
            if ("push_agent".equals(key)) {
                SourceBean sourceBean = new SourceBean();
                sourceBean.setKey("push_agent");
                sourceBean.setName(str(R.string.source_push_agent));
                sourceBean.setType(-1);
                return sourceBean;
            }
            return null;
        }
        return sourceBeanList.get(key);
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        KV.put(HawkConfig.HOME_API, sourceBean.getKey());
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        KV.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        return new ArrayList<>(sourceBeanList.values());
    }
    public List<SourceBean> getSwitchSourceBeanList() {
        // 标 hide 的站点不进切换列表;当前首页源例外,否则列表里没有高亮项
        List<SourceBean> filteredList = new ArrayList<>();
        String homeKey = getHomeSourceBean().getKey();
        for (SourceBean bean : sourceBeanList.values()) {
            if (bean.isHidden() && !bean.getKey().equals(homeKey)) continue;
            filteredList.add(bean);
        }
        return filteredList;
    }

    /** 首页兜底源:优先第一个未标 hide 的站点(否则首页会选中一个不在切换列表里的源);全是 hide 时退回第一条 */
    private static SourceBean firstVisibleSite(List<SourceBean> sites) {
        for (SourceBean bean : sites) {
            if (!bean.isHidden()) return bean;
        }
        return sites.isEmpty() ? null : sites.get(0);
    }

    private List<SourceBean> searchSourceBeanList;
    public List<SourceBean> getSearchSourceBeanList() {
        if(searchSourceBeanList.isEmpty()){
            LOG.i("echo-第一次getSearchSourceBeanList");
            searchSourceBeanList = new ArrayList<>();
            for (SourceBean bean : sourceBeanList.values()) {
                if (bean.isSearchable()) {
                    searchSourceBeanList.add(bean);
                }
            }
        }
        return searchSourceBeanList;
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    /** 点播/直播两套 hosts 的合并视图(点播优先):DNS 解析只认这一份 */
    public Map<String,String> getMyHost() {
        Map<String,String> merged = new HashMap<>();
        if (liveHosts != null) merged.putAll(liveHosts);
        if (vodHosts != null) merged.putAll(vodHosts);
        return merged;
    }

    private void loadProxyRules(JsonObject infoJson) {
        if (!infoJson.has("proxy")) {
            OkGoHelper.setProxyList(null);
            return;
        }
        try {
            OkGoHelper.setProxyList(ProxyRule.arrayFrom(infoJson.get("proxy")));
        } catch (Throwable th) {
            LOG.e("ApiConfig", th);
            OkGoHelper.setProxyList(null);
        }
    }

    public void clearJarLoader() {
        spiderLoader.clearJarLoader();
    }

    public void clearLoader() {
        spiderLoader.clearLoader();
    }

    public void clearSpiderCache() {
        spiderLoader.clearSpiderCache();
    }
}
