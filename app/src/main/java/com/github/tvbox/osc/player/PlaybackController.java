package com.github.tvbox.osc.player;

import android.annotation.SuppressLint;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.CacheManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.EpisodeMatcher;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.PlaybackProgress;
import com.github.tvbox.osc.util.WatchProgressStore;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.sourcedata.SourceViewModel;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.player.VideoView;

/**
 * 播放会话与派生数据层:播什么(vod/sourceKey/sourceBean/播放器配置)、进度与缓存键、清晰度、
 * 投屏地址改写;视图交互经 {@link PlaybackViewBridge},取流/解析调度见 {@link PlayUrlResolver}。
 */
public class PlaybackController {

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    // ==================== 会话数据 ====================

    private VodInfo vod;
    private JSONObject playerCfg;
    private String sourceKey = "";
    private SourceBean sourceBean;

    /** 当前集进度键(源+片+线路+集+集名) */
    private String progressKey;
    /** 进度键的归属(源|片id),与 progressKey 同处更新;进度键取 MD5 后无法反推归属 */
    private String progressOwner;
    /** 当前集字幕缓存键 */
    private String subtitleCacheKey;
    private String playSubtitle;
    private String playLyric;
    private String lyricCacheKey;

    /** 当前清晰度列表原始结果(多清晰度源的 url 数组;null=该源无多清晰度) */
    private JSONObject qualityResult;

    /** 净化后的 m3u8 代理地址与其原始地址(投屏时换回源地址) */
    private String m3u8ProxyUrl;
    private String m3u8SourceUrl;

    /** 换源/换线时"接着看"的进度(新键无历史时才写入) */
    private String inheritProgressKey;
    private long inheritProgress;

    // ==================== 会话生命周期 ====================

    /**
     * 开启一次播放会话:接管页面组装的 {@link PlaybackSession} 并初始化播放器配置。
     * 调用方随后需自行把 {@link #playerCfg()} 刷到控制器。
     */
    public void startSession(PlaybackSession session) {
        // **会话边界清场**:在途的解析/嗅探/取流/超时属于上一个会话,其结果不得作用到新会话。
        // 收尾放在会话边界而非"页面销毁":"快速返回再进入"时旧页面会把新会话刚发起的取流一起撤掉。
        cancelInFlight();
        // 作废上一条"播完待撤会话"的待判消息,避免落到新会话上
        timeouts.cancelPendingCompletionDrop();
        // 代际复位:上一会话的迟到回调不得作用到新会话
        resolver.resetGen();
        // 封面属于上一个会话:换内容必须清(playArtwork 只写一次、currentArtwork 只在取流结果里覆盖),
        // 否则影视源不给 cover 时会残留上一首的值("音乐 → 影视 → 再进音乐页");同片接管不能清。
        if (currentSession == null
                || !TextUtils.equals(currentSession.playbackKey(), session.playbackKey())) {
            music.clearArtworks();
            // 换内容 ⇒ 上一份内容的"纯音频"确认作废(同片接管不清:内容没变)
            st.audioOnlyConfirmed = false;
        }
        this.currentSession = session;
        // 本次会话的内容尚未真正交给播放器:先清掉"已起播内容"标记 ——
        // 否则"切到 B 但取流失败(播放器里其实还是 A)"后重进 B,会被 D6 误判成同片接管(播错内容)
        startedPlaybackKey = null;
        // 会话级状态的统一复位:这些字段的复位点原本只在 play() 里,而 **D6 同片接管不走 play()** ⇒
        // playbackStarted 陈旧会吞掉续播失败(黑屏不重试)、switchStopPending 残留会丢在途取流结果、
        // m3u8 残留会让投屏拿到上一部的源地址。
        st.beginSession();
        clearM3u8ProxyUrl();
        this.vod = session.vod();
        this.sourceKey = session.sourceKey();
        this.sourceBean = ApiConfig.get().getSource(sourceKey);
        ApiConfig.get().setCurrentPlaySourceKey(sourceKey);
        initPlayerCfg();
    }

    public void initPlayerCfg() {
        config.initPlayerCfg();
    }

    // ==================== 进度与缓存键 ====================

    public long getSavedProgress(String url) {
        int st = (playerCfg == null) ? 0 : playerCfg.optInt("st", 0);
        long skip = st * 1000L;
        // 无痕:旧记录连读都不读 —— 只拦写的话,重进仍会从上次留下的位置接着播,隐身等于没开
        if (HistoryHelper.isIncognito()) return skip;
        WatchProgressStore.awaitWrites();
        Object theCache = CacheManager.getCache(MD5.string2MD5(url));
        if (theCache == null) {
            return skip;
        }
        long rec = 0;
        if (theCache instanceof Long) {
            rec = (Long) theCache;
        } else if (theCache instanceof String) {
            try {
                rec = Long.parseLong((String) theCache);
            } catch (NumberFormatException e) {
                LOG.i("echo-String value is not a valid long.");
            }
        } else {
            LOG.i("echo-Value cannot be converted to long.");
        }
        return Math.max(rec, skip);
    }

    /**
     * 记录"接着看"的进度(换源点击即停/自动换线时调用)。
     * 新键已有历史记录时不覆盖(见 {@link #inheritProgressIfNeeded()})。
     */
    public void inheritProgressFrom(String key, long position) {
        this.inheritProgressKey = key;
        this.inheritProgress = position;
    }

    /** 把"接着看"的进度写进新键(仅当新键无历史);无论结果如何都清掉待继承状态 */
    public void inheritProgressIfNeeded() {
        try {
            WatchProgressStore.inherit(progressOwner(), inheritProgressKey, progressKey, inheritProgress);
        } finally {
            inheritProgressKey = null;
            inheritProgress = 0;
        }
    }

    /** 进度索引的归属键(源|片id):与 {@link #progressKey()} 成对,未起播过则为 null(此时只落进度、不维护索引) */
    @Nullable
    public String progressOwner() {
        return progressOwner;
    }

    // 线路/剧集匹配见 EpisodeMatcher

    @Nullable
    public VodInfo.VodSeries currentSeries(String flag, int index) {
        if (flag == null || vod == null || vod.seriesMap == null) {
            return null;
        }
        List<VodInfo.VodSeries> currentList = vod.seriesMap.get(flag);
        if (currentList == null || currentList.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(index, currentList.size() - 1));
        return currentList.get(safeIndex);
    }

    /**
     * 取流结果是否已过期(切集/换线/换源后,旧源在途结果不得拉起播放)。
     */
    public boolean isStalePlayResult(JSONObject info) {
        if (vod == null || vod.seriesMap == null || TextUtils.isEmpty(progressKey)) return false;
        String resultKey = info.optString("proKey", "");
        if (!TextUtils.isEmpty(resultKey) && !progressKey.equals(resultKey)) return true;
        String resultFlag = info.optString("flag", "");
        if (!TextUtils.isEmpty(resultFlag) && !resultFlag.equals(vod.playFlag)) return true;
        String sourceUrl = info.optString("key", "");
        if (!TextUtils.isEmpty(sourceUrl)) {
            VodInfo.VodSeries vs = currentSeries(vod.playFlag, vod.playIndex);
            return vs != null && !sourceUrl.equals(vs.url);
        }
        return false;
    }

    // ==================== 清晰度 ====================

    /** 发布/清空清晰度列表(仅改内存态 + EventBus 广播,不启动播放) */
    public void publishQuality(JSONObject info) {
        try {
            JSONArray urls = new JSONArray(info == null ? "" : info.optString("url"));
            if (urls.length() < 4 || urls.length() % 2 != 0) throw new JSONException("invalid quality urls");
            qualityResult = new JSONObject(info.toString());
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, qualityResult));
        } catch (Throwable th) {
            qualityResult = null;
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PLAY_QUALITY, null));
        }
    }

    @Nullable
    public JSONObject quality() {
        return qualityResult;
    }

    // ==================== 投屏地址改写 ====================

    /** m3u8 代理地址 → 源地址;本地代理地址 → 局域网地址(配合 ControlManager 的地址发现) */
    public String getCastUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        if (isM3u8ProxyUrl(url) && !TextUtils.isEmpty(m3u8SourceUrl)) return m3u8SourceUrl;
        String local = ControlManager.get().getAddress(true);
        String server = ControlManager.get().getAddress(false);
        if (!TextUtils.isEmpty(local) && !TextUtils.isEmpty(server) && url.startsWith(local)) {
            return server + url.substring(local.length());
        }
        return url;
    }

    public boolean isM3u8ProxyUrl(String url) {
        return !TextUtils.isEmpty(m3u8ProxyUrl) && url.equals(m3u8ProxyUrl);
    }

    public void setM3u8Urls(String proxyUrl, String sourceUrl) {
        this.m3u8ProxyUrl = proxyUrl;
        this.m3u8SourceUrl = sourceUrl;
    }

    @Nullable
    public String m3u8SourceUrl() {
        return m3u8SourceUrl;
    }

    public void clearM3u8ProxyUrl() {
        m3u8ProxyUrl = null;
        m3u8SourceUrl = null;
    }

    // ==================== 播放请求头 ====================

    /** 提取播放请求头:与预载侧共用 `PlayerHelper.extractPlayHeaders`,两侧逐字一致才满足预载读盘守卫,否则预缓存不命中 */
    public static HashMap<String, String> extractHeaders(JSONObject object) {
        return PlayerHelper.extractPlayHeaders(object);
    }

    public static void putHeaders(JSONObject target, HashMap<String, String> headers) throws JSONException {
        if (target == null || headers == null) return;
        for (String key : headers.keySet()) {
            target.put(key, headers.get(key));
        }
    }

    @Nullable
    public static String headerValue(HashMap<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (String key : headers.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return headers.get(key);
            }
        }
        return null;
    }

    // ==================== 访问器 ====================

    @Nullable
    public VodInfo vod() {
        return vod;
    }

    @Nullable
    public JSONObject playerCfg() {
        return playerCfg;
    }

    @Nullable
    public SourceBean sourceBean() {
        return sourceBean;
    }

    public String sourceKey() {
        return sourceKey;
    }

    @Nullable
    public String progressKey() {
        return progressKey;
    }

    public void setProgressKey(String progressKey) {
        this.progressKey = progressKey;
        // 归属与键同处更新:换片时先 release 旧内核(那一刻视图里还是旧键),此处若按当前 vod 归属会把旧片的键记到新片名下
        this.progressOwner = WatchProgressStore.ownerOf(vod);
    }

    @Nullable
    public String subtitleCacheKey() {
        return subtitleCacheKey;
    }

    public void setSubtitleCacheKey(String subtitleCacheKey) {
        this.subtitleCacheKey = subtitleCacheKey;
    }

    @Nullable
    public String playSubtitle() {
        return playSubtitle;
    }

    public void setPlaySubtitle(String playSubtitle) {
        this.playSubtitle = playSubtitle;
    }

    @Nullable
    public String playLyric() {
        return playLyric;
    }

    public void setPlayLyric(String playLyric) {
        this.playLyric = playLyric;
    }

    @Nullable
    public String lyricCacheKey() {
        return lyricCacheKey;
    }

    public void setLyricCacheKey(String lyricCacheKey) {
        this.lyricCacheKey = lyricCacheKey;
    }

    // ==================== 调度:重试与换线决策 ====================
    // 一切视图交互都经 PlaybackViewBridge。

    /** 视图侧契约(页面内由 PlayContainer 提供匿名实现) */
    private PlaybackViewBridge view;

    public void setViewBridge(PlaybackViewBridge bridge) {
        this.view = bridge;
    }

    /** 三处超时(取流/换线/播完待撤)的定时消息投递 */
    private final PlaybackTimeouts timeouts = new PlaybackTimeouts(new PlaybackTimeouts.Callback() {
        @Override
        public void onResolvePlayUrlTimeout() {
            handleResolvePlayUrlTimeout();
        }

        @Override
        public void onSwitchLinePlayTimeout() {
            handleSwitchLinePlayTimeout();
        }

        @Override
        public void onPendingCompletionDrop() {
            music.handlePendingCompletionDrop();
        }
    });

    /** 播放器配置(见 PlaybackConfigDelegate) */
    private final PlaybackConfigDelegate config = new PlaybackConfigDelegate(new PlaybackConfigDelegate.Host() {
        @Override
        public VodInfo vod() {
            return PlaybackController.this.vod;
        }

        @Override
        public SourceBean sourceBean() {
            return PlaybackController.this.sourceBean;
        }

        @Override
        public JSONObject playerCfg() {
            return PlaybackController.this.playerCfg;
        }

        @Override
        public void setPlayerCfg(JSONObject cfg) {
            PlaybackController.this.playerCfg = cfg;
        }

        @Override
        public PlaybackAttemptState attemptState() {
            return st;
        }
    });

    /** 重试与换线策略(见 PlaybackRetryDelegate) */
    private final PlaybackRetryDelegate retry = new PlaybackRetryDelegate(new PlaybackRetryDelegate.Host() {
        @Override
        public PlaybackAttemptState attemptState() {
            return st;
        }

        @Override
        public PlaybackViewBridge view() {
            return PlaybackController.this.view;
        }

        @Override
        public JSONObject playerCfg() {
            return PlaybackController.this.playerCfg;
        }

        @Override
        public VodInfo vod() {
            return PlaybackController.this.vod;
        }

        @Override
        public VodInfo.VodSeries currentSeries(String flag, int index) {
            return PlaybackController.this.currentSeries(flag, index);
        }

        @Override
        public String progressKey() {
            return PlaybackController.this.progressKey;
        }

        @Override
        public long getSavedProgress(String url) {
            return PlaybackController.this.getSavedProgress(url);
        }

        @Override
        public void inheritProgressFrom(String key, long position) {
            PlaybackController.this.inheritProgressFrom(key, position);
        }

        @Override
        public String webPlayUrl() {
            return PlaybackController.this.webPlayUrl;
        }

        @Override
        public HashMap<String, String> webHeaderMap() {
            return PlaybackController.this.webHeaderMap;
        }

        @Override
        public boolean resolverHasFoundUrls() {
            return resolver.hasFoundUrls();
        }

        @Override
        public void resolverConsumeFoundUrl() {
            resolver.consumeFoundUrl();
        }

        @Override
        public void play(boolean reset) {
            PlaybackController.this.play(reset);
        }

        @Override
        public void playUrl(String url, HashMap<String, String> headers) {
            PlaybackController.this.playUrl(url, headers);
        }

        @Override
        public void stopParse() {
            PlaybackController.this.stopParse();
        }

        @Override
        public void initParseLoadFound() {
            PlaybackController.this.initParseLoadFound();
        }

        @Override
        public void cancelPlayRequest() {
            fetch.cancelPlayRequest();
        }

        @Override
        public void cancelPlayTimeout() {
            PlaybackController.this.cancelPlayTimeout();
        }

        @Override
        public boolean isPlaybackStarted() {
            return PlaybackController.this.isPlaybackStarted();
        }

        @Override
        public void stopMusicSessionForFailedPlayback() {
            music.stopMusicSessionForFailedPlayback();
        }

        @Override
        public boolean isCrossContentReuseAllowed() {
            return PlaybackController.this.isCrossContentReuseAllowed();
        }
    });

    /** 解析/嗅探调度(见 PlayUrlResolver) */
    private final PlayUrlResolver resolver = new PlayUrlResolver(new PlayUrlResolver.Host() {
        @Override
        public PlaybackViewBridge view() {
            return PlaybackController.this.view;
        }

        @Override
        public SourceBean sourceBean() {
            return PlaybackController.this.sourceBean();
        }

        @Override
        public HashMap<String, String> webHeaderMap() {
            return PlaybackController.this.webHeaderMap;
        }

        @Override
        public void setWebHeaderMap(HashMap<String, String> headers) {
            PlaybackController.this.webHeaderMap = headers;
        }

        @Override
        public String webUserAgent() {
            return PlaybackController.this.webUserAgent;
        }

        @Override
        public void setWebUserAgent(String userAgent) {
            PlaybackController.this.webUserAgent = userAgent;
        }

        @Override
        public void playUrl(String url, HashMap<String, String> headers) {
            PlaybackController.this.playUrl(url, headers);
        }

        @Override
        public void playUrl(int gen, String url, HashMap<String, String> headers) {
            PlaybackController.this.playUrl(gen, url, headers);
        }
    });

    /** 尝试/换线/解码/会话标记状态(见 PlaybackAttemptState) */
    private final PlaybackAttemptState st = new PlaybackAttemptState();

    // -------------------- 状态开关(供页面在既有流程点调用) --------------------

    /** 新一次播放的清场:重试阶梯 + 内核/解码自动态 + 起播标记(内容边界标记仍在调用方) */
    public void beginNewPlay() {
        st.beginNewPlay();
        // 新内容开始 ⇒ 上一条"播完待撤会话"的判定作废(否则那条迟到的消息会打到本次新会话上)
        timeouts.cancelPendingCompletionDrop();
        // 换内容(换集/换线/换源/重播)⇒ 上一次确认的"纯音频"作废,由新内容自己重新确认
        // (自动重试不走本方法,见 retryAfterStartedError:同一内容的确认必须留着)
        st.audioOnlyConfirmed = false;
    }

    /** 换源点击即停:清"播放中"标记与复用开关,并置"在途结果作废"标记(下一次 play 清除) */
    public void markStoppedForSourceSwitch() {
        st.stoppedForSourceSwitch();
    }

    /** 取出并复位"复用播放器"意图 */
    public boolean consumeReusePlayerOnSwitch() {
        return st.consumeReuseIntent();
    }

    public void setReusePlayerOnSwitch(boolean reuse) {
        st.setReuseIntent(reuse);
    }

    public void setReleasePlayerOnSwitch(boolean release) {
        st.setReleaseIntent(release);
    }

    /** 切集/换线:清空"已尝试线路" */
    public void clearTriedLines() {
        st.clearTriedLines();
    }

    public void setUserPickedLine(boolean picked) {
        st.userPickedLine = picked;
    }

    public void setAllowSwitchPlayer(boolean allow) {
        config.setAllowSwitchPlayer(allow);
    }

    public void setAllowDecodeFallback(boolean allow) {
        config.setAllowDecodeFallback(allow);
    }

    @Nullable
    public JSONObject playerCfgForPersist() {
        return config.playerCfgForPersist();
    }

    /** 用户自救(重播/切解析/切内核/切解码)后:允许再兜一次底 */
    public void resetAutoRetryState() {
        st.userSelfRescue();
    }

    public void setPlaybackStarted(boolean started) {
        st.playbackStarted = started;
    }

    public void setPlayTimeoutBasePosition(long position) {
        st.playTimeoutBasePosition = position;
    }

    /** 取流起播的基准位置(跳播/转圈判定用) */
    public long playTimeoutBasePosition() {
        return st.playTimeoutBasePosition;
    }

    public boolean isStartedPlayState(int state) {
        return state == VideoView.STATE_PREPARED || state == VideoView.STATE_BUFFERED || state == VideoView.STATE_PLAYING;
    }

    public void markPlaybackStarted() {
        st.playbackStarted = true;
        cancelPlayTimeout();
    }

    public boolean isPlaybackStarted() {
        if (st.playbackStarted) return true;
        if (view == null) return false;
        return isStartedPlayState(view.currentPlayState()) || hasPlaybackProgress(view.currentPosition()) || view.isPlaying();
    }

    private boolean hasPlaybackProgress(long progress) {
        return progress > Math.max(st.playTimeoutBasePosition, 0) + 1000;
    }

    // -------------------- 三处超时 --------------------

    public void startResolvePlayUrlTimeout() {
        timeouts.startResolvePlayUrlTimeout(getResolvePlayUrlTimeoutMs());
    }

    private long getResolvePlayUrlTimeoutMs() {
        if (sourceBean() == null) return PlaybackTimeouts.RESOLVE_PLAY_URL_TIMEOUT_MS;
        return Math.max(PlaybackTimeouts.RESOLVE_PLAY_URL_TIMEOUT_MS, (sourceBean().getPlayTimeoutSeconds() + 1L) * 1000L);
    }

    public void startSwitchLinePlayTimeout() {
        if (!st.allowAutoSwitchLine) {
            cancelPlayTimeout();
            return;
        }
        cancelPlayTimeout();
        LOG.i("echo-switchLinePlay start timeout");
        timeouts.startSwitchLinePlayTimeout();
    }

    public void cancelSwitchLinePlayTimeout() {
        cancelPlayTimeout();
    }

    public void cancelPlayTimeout() {
        timeouts.cancelPlayTimeout();
    }

    /** 只取消"取流超时"(取流结果已到达时;换线播放超时另计,不能一起取消) */
    public void cancelResolvePlayUrlTimeout() {
        timeouts.cancelResolvePlayUrlTimeout();
    }

    /** 预览态启用/全屏禁用自动换线(全屏时用户在看画面,不该被换线打断) */
    public void setAutoSwitchLineEnabled(boolean enabled) {
        // 值未变就直接返回:页面每次进入/重进都会下发一遍,重复的"禁用"不能再去动在途取流超时与换线记录
        if (st.allowAutoSwitchLine == enabled) return;
        st.allowAutoSwitchLine = enabled;
        if (!enabled) {
            cancelPlayTimeout();
            st.clearTriedLines();
        }
    }

    // -------------------- 重试与换线 --------------------
    // 实现见 PlaybackRetryDelegate

    public boolean retryAfterStartedError() {
        return retry.retryAfterStartedError();
    }

    public boolean autoRetry() {
        return retry.autoRetry();
    }

    public boolean tryNextLineIfEnabled() {
        return retry.tryNextLineIfEnabled();
    }

    public boolean tryNextLine() {
        return retry.tryNextLine();
    }

    public void handleResolvePlayUrlTimeout() {
        retry.handleResolvePlayUrlTimeout();
    }

    public void handleResolvePlayUrlFailed(String err) {
        retry.handleResolvePlayUrlFailed(err);
    }

    public void handleSwitchLinePlayTimeout() {
        retry.handleSwitchLinePlayTimeout();
    }



    // ==================== 取流状态与结果观察者 ====================


    /** 已解析出的可播地址与请求头(重试/换内核重播用) */
    private String webPlayUrl;
    private HashMap<String, String> webHeaderMap;
    private String webUserAgent;


    /**
     * 最近一次**通过校验**的起播请求所属的代际(仅主线程读写):{@link #goPlayUrl} 入口签发,
     * UI 落地闭包用它比对 —— 排队期(回调 → runOnUi)若换了集,排队中的旧地址会被丢弃。
     * 签发点必须在入口(不能用解析产物入口的字段):M3U8 净化是主线程直接进 goPlayUrl 的。
     */
    private int playUrlGeneration;

    /** 取流状态与结果观察者(见 PlaybackFetch) */
    private final PlaybackFetch fetch = new PlaybackFetch(this);

    /** 当前会话(页面 setData 交进来的那一份;D6 接管与"已起播内容"判定都基于它) */
    private PlaybackSession currentSession;
    /**
     * 最近一次**真正把内容交给播放器**的会话归属键(D6 接管的唯一可信依据):
     * `startSession` 只是登记要播什么,取流失败或被外部播放器接走时播放器里的内容不属于该会话,
     * D6 必须拒绝接管(真机 bug:点播页播着直播)。
     */
    private String startedPlaybackKey;
    /** 上一次真正起播时下发的进度键。与归属键的唯一区别:**会话边界不清** —— 换片时 {@link #startSession} 会先清归属键(D6 依据须即时作废),复用判定若读它则恒判不出"内核里是上一部片"。 */
    private String startedProgressKey;

    /** 内容真正起播(地址交给播放器)时调用:记录归属,供 D6 接管判定 */
    public void markContentStarted() {
        startedPlaybackKey = currentSession == null ? null : currentSession.playbackKey();
        // 与归属同处记录:此刻 progressKey 已是本次内容的键(起播点先 setProgressKey 再调本方法)
        startedProgressKey = progressKey;
    }

    /** 内容不再属于当前会话(直播接管等):清空归属标记 */
    public void clearStartedContent() {
        startedPlaybackKey = null;
        // 内核交出去后播放器里不再有"本控制器的内容",进度也没有可落盘的归属了(与上面同处清,保持两者同步)
        startedProgressKey = null;
    }

    @Nullable
    public String startedPlaybackKey() {
        return startedPlaybackKey;
    }

    /** 上一次真正起播的进度键(= 内核里那份内容的位置归属);null = 内核里没播过内容(未创建/预热空闲/已释放) */
    private String startedProgressKey() {
        return startedProgressKey;
    }

    /**
     * 内核里那份内容是否就是本次要播的这一集(= 同内容重播,不是换内容)。
     * 起播点据此决定要不要在 replay 前补落盘:换内容时进度键与起点都已属新内容,补落盘会污染新旧两个键。
     */
    public boolean isSameStartedContent() {
        return startedProgressKey != null && TextUtils.equals(startedProgressKey, progressKey());
    }

    /** 建立取流结果观察者 */
    public void initFetch() {
        fetch.init();
    }

    /** 页面销毁时注销观察者 */
    public void releaseFetch() {
        fetch.release();
    }

    /** 当前视图桥(取流观察者/预载调度读取) */
    PlaybackViewBridge viewBridge() {
        return view;
    }

    void setCurrentArtwork(String artwork) {
        music.setCurrentArtwork(artwork);
    }

    @Nullable
    public String webPlayUrl() {
        return webPlayUrl;
    }

    public void setWebPlayUrl(String webPlayUrl) {
        this.webPlayUrl = webPlayUrl;
    }

    @Nullable
    public HashMap<String, String> webHeaderMap() {
        return webHeaderMap;
    }

    public void setWebHeaderMap(HashMap<String, String> webHeaderMap) {
        this.webHeaderMap = webHeaderMap;
    }

    @Nullable
    public String webUserAgent() {
        return webUserAgent;
    }

    public void setWebUserAgent(String webUserAgent) {
        this.webUserAgent = webUserAgent;
    }

    // -------------------- 解析/嗅探门面(见 PlayUrlResolver) --------------------

    /** 按解析规则发起解析(直链/json/聚合/超级解析) */
    public void initParse(String flag, boolean useParse, String playUrl, final String url) {
        resolver.initParse(flag, useParse, playUrl, url);
    }

    /** 解析入口 */
    public void doParse(ParseBean pb) {
        resolver.doParse(pb);
    }

    /** 停止解析/嗅探 */
    public void stopParse() {
        resolver.stopParse();
    }

    /** 重置嗅探结果容器 */
    public void initParseLoadFound() {
        resolver.initParseLoadFound();
    }

    /** 本轮解析/嗅探是否仍有效(代际闸门) */
    public boolean isParseResultCurrent(int gen) {
        return resolver.isParseResultCurrent(gen);
    }

    public void stopLoadWebView(boolean destroy) {
        resolver.stopLoadWebView(destroy);
    }


    @SuppressLint("SetJavaScriptEnabled")

    // ==================== 取流入口 ====================
    // play/playUrl/goPlayUrl 是"调度 → 视图"的分界线:决策(外部播放器、dash 强制 EXO、纯音频渲染、
    // 进度继承、预载命中)在调度层,真正操作 MyVideoView 的连招交给 view.startVideoPlayback(...)。


    public boolean isSwitchStopPending() {
        return st.switchStopPending;
    }

    /** 换源点击即停时记下"接着看"的进度(play 时写进新键) */
    public void setPendingInherit(String key, long progress) {
        st.pendingInheritKey = key;
        st.pendingInheritProgress = progress;
    }

    /**
     * 把当前会话的标题下发到视图(播放器顶栏 / 暂停浮层)。
     * D6「同片接管」**不经过** {@link #play(boolean)},而标题原先只在 play() 里下发 ⇒ 接管路径必须自己补一次,
     * 否则"退出详情页 → 重新进入同一部"顶栏标题为空。
     */
    public void publishTitle() {
        if (view == null || vod() == null) return;
        VodInfo.VodSeries vs = currentSeries(vod().playFlag, vod().playIndex);
        if (vs == null) return;
        view.setTitle(vod().name + " " + vs.name);
    }

    /**
     * 播放当前集的唯一入口(切集/换线/换源/重播都走它)。
     *
     * @param reset true = 清除已有进度从头发起(重播)
     */
    public void play(boolean reset) {
        // 新播放是用户显式请求(换源落地/回滚重播):解除换源停播抑制
        st.switchStopPending = false;
        // 入口即失效(见 parseGeneration):上一集的在途结果不得再拉起播放。必须在下面的 early return 之前
        resolver.nextGen();
        // 预载失效事件(切集/换线/换源/重播):作废在途预解析与预载数据,稳定播放后重新评估
        invalidatePreload();
        if (view != null) view.hidePreloadReadyTip();
        if (vod() == null) return;
        // 起播前的复用判定走唯一入口(与各起播点同一函数):意图来自同片换集/换线/切歌;
        // 预热总闸开启后换片/换源也免意图复用 —— 否则会被这里的释放收走,预热与总闸双双落空
        boolean kernelPresent = view != null && view.mediaPlayer() != null;
        boolean idleKernelReused = isIdleKernelReusable(kernelPresent);
        boolean crossContentReuseAllowed = isCrossContentReuseAllowed();
        boolean reuseAllowed = consumeReusePlayerOnSwitch() || idleKernelReused || crossContentReuseAllowed;
        // 内核里躺着的还是本次这一集(= 同片换集,提示语可留着);假 → 换内容或空闲内核,须给"获取信息"反馈
        String startedKey = startedProgressKey();
        boolean sameContentReuse = startedKey != null
                && !KernelReusePolicy.isCrossContentSwitch(startedKey, progressKey());
        // "必须重建"标记与 dash 专用路径取流后才可知,交起播点判定;这里只决定"要不要先把内核释放掉"
        boolean reusePlayer = KernelReusePolicy.decide(kernelPresent, false, false, reuseAllowed)
                == KernelDecision.REUSE;
        st.switchingPlayback = true;
        st.audioPlayback = false;
        if (view != null) {
            view.onNewPlayStarted();
            view.clearArtwork();
        }
        // 逐级判空 + 集号 clamp(与 goPlayUrl 同源防护):历史恢复的线路在
        // 当前源不存在、或源更新后集数变少时,裸链式取值会 NPE/IOOBE 直接崩在主线程;
        // 走失败链路(自动换线兜底)而不是崩溃
        VodInfo.VodSeries vs = currentSeries(vod().playFlag, vod().playIndex);
        if (vs == null) {
            handleResolvePlayUrlFailed(str(R.string.player_get_info_error));
            return;
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, vod()));
        if (sameContentReuse) {
            // 复用播放器时提示已由上一集留着,这里强制写一次空态(走 view.showTip 而非页面 setTip):
            // 提示层状态归视图桥,页面/音乐页初始化都会先 hide(),旧态不会留给下一页
            if (view != null) view.showTip("", true, false);
        } else if (view != null) {
            // 空闲内核与换内容都没有"上一集的提示"可留,仍要给"获取播放信息"反馈
            view.showTip(str(R.string.player_getting_info), true, false);
        }
        publishTitle();

        stopParse();
        beginNewPlay();
        config.syncDecodeFromGlobal();
        setWebPlayUrl(null);
        setWebHeaderMap(null);
        initParseLoadFound();

        if (view != null) {
            view.stopOtherPlayers();
            view.resetDanmu();
            view.clearLyric();
            if (reusePlayer) {
                // 复用起播必经此处补落盘:同片换集有停播链路兜底(幂等),换内容(含音乐页换歌)则是唯一时机
                savePreviousContentProgress();
                view.clearVideoFrame();
            } else if (kernelPresent) {
                // 内核本来就不在时不空转 release(它会重复清"已起播内容"归属)
                view.releasePlayer();
            }
        }
        ImgUtil.clearMemoryCache();
        setSubtitleCacheKey(vod().sourceKey + "-" + vod().id + "-" + vod().playFlag + "-" + vod().playIndex + "-" + vs.name + "-subt");
        setProgressKey(vod().sourceKey + vod().id + vod().playFlag + vod().playIndex + vs.name);
        // 这一集是真的重新起播:删除时下的"作废"到此为止(否则用户重看一遍也不再记进度)
        WatchProgressStore.onPlayStart(progressKey());
        PlaybackProgress.onEpisodeStartNoScroll();
        startResolvePlayUrlTimeout();
        // 换源点击即停前记下的进度:新源进度键不同,写进新键缓存接着看(新键已有历史记录则不覆盖);
        // 回滚原源时键相同,停播 release 已落盘,该方法会直接跳过
        if (st.pendingInheritProgress > 0 && !TextUtils.isEmpty(st.pendingInheritKey)) {
            inheritProgressFrom(st.pendingInheritKey, st.pendingInheritProgress);
            LOG.i("echo-switchSource inherit progress " + st.pendingInheritProgress + "ms from " + st.pendingInheritKey);
        }
        st.pendingInheritKey = null;
        st.pendingInheritProgress = 0;
        // 重新播放清除现有进度
        if (reset) {
            // 重播不消费待继承进度,留着会被下一次非重播播放写进别的集
            inheritProgressKey = null;
            inheritProgress = 0;
            WatchProgressStore.clear(progressOwner(), progressKey());
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey()), 0);
        } else {
            inheritProgressIfNeeded();
            // 外挂字幕视图先复位为隐藏,真有字幕再由字幕决策链路(applyDefaultSubtitle/setSubtitlePath)显示
            if (view != null) view.setSubtitleViewVisible(false);
        }

        if (Jianpian.isJpUrl(vs.url)) {// 荐片地址特殊判断
            String jp_url = vs.url;
            if (view != null) view.showParse(false);
            if (vs.url.startsWith("tvbox-xg:")) {
                playUrl(Jianpian.JPUrlDec(jp_url.substring(9)), null);
            } else {
                playUrl(Jianpian.JPUrlDec(jp_url), null);
            }
            return;
        }
        // p2p 取流是异步回调(可能数十秒):同样带发起时的代际,切集后旧地址不得起播
        final int thunderGen = resolver.currentGen();
        if (Thunder.play(vs.url, new Thunder.ThunderCallback() {
            @Override
            public void status(int code, String info) {
                if (view != null) view.showTip(info, code >= 0, code < 0);
            }

            @Override
            public void list(Map<Integer, String> urlMap) {
            }

            @Override
            public void play(String url) {
                playUrl(thunderGen, url, null);
            }
        })) {
            if (view != null) view.showParse(false);
            return;
        }

        if (preload.consumeResult(progressKey())) return;
        SourceViewModel svm = fetch.sourceViewModel();
        if (svm != null) {
            svm.getPlay(sourceKey(), vod().playFlag, progressKey(), vs.url, subtitleCacheKey());
        }
    }

    /**
     * 未绑定内容的空闲内核(预热建的、或上次内容已停)可免意图复用:它没有内容语义要保护,复用只是 reset+换源。
     * 有内容的内核(暂停/在播)仍按"新内容先释放"处理。
     */
    private boolean isIdleKernelReusable(boolean kernelPresent) {
        if (!kernelPresent) return false;
        return view.currentPlayState() == VideoView.STATE_IDLE;
    }

    /**
     * 跨内容复用许可(换片/换源/换集/换线):复用在上界内(见引擎的空闲释放)才有收益,与预热开关无关。
     * ERROR 态返回 false:复用一个坏内核没有意义,强制重建兜底。
     */
    public boolean isCrossContentReuseAllowed() {
        if (view == null || view.mediaPlayer() == null) return false;
        return !view.isKernelErrored();
    }

    /**
     * 换内容前把上一段的位置落盘,**必须在下一次 {@link #setProgressKey} 之前**调 —— 之后进度键就易主了。
     * 复用起播走 replay、不经 release(该方法内部才有 saveProgress 兜底),漏了这一步就丢上一段的观看位置。
     */
    private void savePreviousContentProgress() {
        if (view == null) return;
        if (TextUtils.isEmpty(progressKey())) return;
        long position = view.currentPosition();
        if (position <= 0) return;
        WatchProgressStore.save(progressOwner(), progressKey(), position, view.duration());
    }

    /**
     * 解析/嗅探产物入口:入口校验挡"回调已跑起来"的旧结果(已切集时连 RefreshEvent 播放地址与换线超时都不该被改写);
     * {@link #goPlayUrl} 里那道校验挡"回调 → UI 线程排队"期间的切集。
     * 自动重试/重播兜底/自动换线走的都是 2 参 {@link #playUrl}(与 goPlayUrl 同帧同代际)⇒ 不会误杀。
     */
    private void playUrl(int gen, String url, HashMap<String, String> headers) {
        if (!resolver.isParseResultCurrent(gen)) {
            LOG.i("echo-ignore stale parse result");
            return;
        }
        playUrlGeneration = gen;
        playUrl(url, headers);
    }

    /** 取流结果入口:统一交给 goPlayUrl 起播 */
    public void playUrl(String url, HashMap<String, String> headers) {
        startSwitchLinePlayTimeout();
        url = attachProxySiteKey(url);
        if (!url.startsWith("data:application")) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, url));//更新播放地址
        }
        goPlayUrl(url, headers);
    }

    /** 真正起播一个可播地址(外部播放器 / dash 强制 EXO / 复用播放器换集都在这里分流) */
    public void goPlayUrl(String url, HashMap<String, String> headers) {
        LOG.i("echo-goPlayUrl:" + url);
        if (TextUtils.isEmpty(url)) {
            handleResolvePlayUrlFailed(str(R.string.player_play_url_empty));
            return;
        }
        if (view == null || !view.isPageAlive()) return;
        // 调用方与解析回调同帧或同线程 ⇒ 本地址归属当前轮,在排队前签发代际(见 playUrlGeneration 注释)
        playUrlGeneration = resolver.currentGen();
        final String finalUrl = url;
        view.runOnUi(new Runnable() {
            @Override
            public void run() {
                if (st.switchStopPending) {
                    // 换源点击即停后,已排队的取流结果(含嗅探/解析回调)不得再拉起播放
                    LOG.i("echo-ignore goPlayUrl while source switching");
                    return;
                }
                if (playUrlGeneration != resolver.currentGen()) {
                    // 上一轮的产物(排队期已切集/换线/换源/重播)⇒ 丢弃,并撤掉旧链超时(否则到期会触发一次换线)
                    LOG.i("echo-ignore goPlayUrl of stale parse result");
                    resolver.cancelParseTimeout();
                    return;
                }
                // 地址在归属确认之后才记录,否则被丢弃的旧地址会留在 webPlayUrl 上被 autoRetry 拿去重播
                setWebPlayUrl(finalUrl);
                stopParse();
                if (view == null || finalUrl == null) return;
                String url = finalUrl;
                try {
                    int playerType = playerCfg().getInt("pl");
                    if (playerType >= 10) {
                        view.releasePlayer();
                        // 历史恢复的线路在当前源不存在、或换源与切集交错时,
                        // seriesMap 链式取值可能 NPE,逐级判空后回退仅用片名
                        List<VodInfo.VodSeries> series = (vod() == null || vod().seriesMap == null) ? null : vod().seriesMap.get(vod().playFlag);
                        VodInfo.VodSeries vs = (series == null || vod().playIndex < 0 || vod().playIndex >= series.size()) ? null : series.get(vod().playIndex);
                        String playTitle = vod().name + (vs == null ? "" : " " + vs.name);
                        view.showTip(str(R.string.player_call_external_play, PlayerHelper.getPlayerName(playerType)), true, false);
                        long progress = getSavedProgress(progressKey());
                        boolean callResult = view.playExternalPlayer(playerType, url, playTitle, playSubtitle(), headers, progress);
                        view.showTip(str(R.string.player_call_external_result, PlayerHelper.getPlayerName(playerType), callResult ? str(R.string.common_success) : str(R.string.common_failed)), callResult, !callResult);
                        return;
                    }
                } catch (JSONException e) {
                    LOG.e("PlaybackController", e);
                }
                setPlayTimeoutBasePosition(getSavedProgress(progressKey()));
                boolean forceExoPlayer = url.startsWith("data:application/dash+xml;base64,")
                        || url.contains(".mpd") || url.contains("type=mpd");
                if (url.startsWith("data:application/dash+xml;base64,")) {
                    view.applyPlayerConfigToView(2);
                    App.getInstance().setDashData(url.split("base64,")[1]);
                    url = ControlManager.get().getAddress(true) + "dash/proxy.mpd";
                } else if (url.contains(".mpd") || url.contains("type=mpd")) {
                    view.applyPlayerConfigToView(2);
                } else {
                    view.applyPlayerConfigToView(0);
                }
                // 纯音频 URL 预判:音乐直链没有视频帧,SurfaceView 会"洞穿"应用窗口(任务快照变白/回前台透视桌面),
                // 改用 TextureView 起播补住「起播 → 轨道信息就绪」的空窗;误判无功能损失(详见 MyVideoView.switchRenderToTexture)。
                if (looksLikeAudioUrl(url)) {
                    view.useTextureRenderForAudio();
                }
                view.startVideoPlayback(url, headers, forceExoPlayer);
            }
        });
    }

    /** 本地代理地址补 siteKey(播放侧代理需要它定位源) */
    private String attachProxySiteKey(String url) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(sourceKey())) return url;
        if (!url.startsWith(ControlManager.get().getAddress(true) + "proxy?")) return url;
        if (url.contains("siteKey=")) return url;
        try {
            return url + (url.contains("?") ? "&" : "?") + "siteKey=" + URLEncoder.encode(sourceKey(), "UTF-8");
        } catch (Throwable th) {
            return url + (url.contains("?") ? "&" : "?") + "siteKey=" + sourceKey();
        }
    }


    private final PlaybackPreload preload = new PlaybackPreload(new PlaybackPreload.Host() {
        @Override
        public PlaybackViewBridge view() {
            return PlaybackController.this.view;
        }

        @Override
        public SourceViewModel sourceViewModel() {
            return fetch.sourceViewModel();
        }

        @Override
        public void ensureFetch() {
            initFetch();
        }

        @Override
        public void onPreloadedResult(JSONObject info) {
            st.usedPreloadedResult = true;
            fetch.deliver(info);
        }
    });

    /** 建立预载协调器与"下一集已就绪"回调(页面 init 时调用一次,须在 initFetch 之后) */
    public void initPreload() {
        preload.init();
    }

    /**
     * 播放状态变化驱动预载评估(页面状态回调里调用):STATE_PLAYING 延迟评估、STATE_BUFFERING 让路、
     * STATE_BUFFERED 补一次评估(dkplayer 的 STATE_PLAYING 只在首帧发一次,不补枪则拖一次进度条就永久停摆)。
     */
    public void onPlayerStateForPreload(int playState) {
        preload.onPlayerState(playState);
    }

    /** 切集/换线/换源/重播:作废在途预解析与预载数据(稳定播放后重新评估) */
    public void invalidatePreload() {
        preload.invalidate();
    }

    /** 页面销毁:停协调器 + 注销就绪回调(防页面销毁后回调/Toast 残留) */
    public void destroyPreload() {
        preload.destroy();
    }

    private final MusicSessionDelegate music = new MusicSessionDelegate(new MusicSessionDelegate.Host() {
        @Override
        public PlaybackViewBridge view() {
            return PlaybackController.this.view;
        }

        @Override
        public PlaybackAttemptState attemptState() {
            return st;
        }

        @Override
        public PlaybackTimeouts timeouts() {
            return timeouts;
        }

        @Override
        public VodInfo vod() {
            return PlaybackController.this.vod;
        }

        @Override
        public VodInfo.VodSeries currentSeries(String flag, int index) {
            return PlaybackController.this.currentSeries(flag, index);
        }

        @Override
        public JSONObject quality() {
            return qualityResult;
        }

        @Override
        public boolean isStartedPlayState(int state) {
            return PlaybackController.this.isStartedPlayState(state);
        }

        @Override
        public boolean retryAfterStartedError() {
            return PlaybackController.this.retryAfterStartedError();
        }

        @Override
        public void initParse(String flag, boolean useParse, String playUrl, String url) {
            PlaybackController.this.initParse(flag, useParse, playUrl, url);
        }

        @Override
        public void playUrl(String url, HashMap<String, String> headers) {
            PlaybackController.this.playUrl(url, headers);
        }
    });

    public void beginSwitchPlayback() {
        music.beginSwitchPlayback();
    }

    @Nullable
    public String playArtwork() {
        return music.playArtwork();
    }

    @Nullable
    public String currentArtwork() {
        return music.currentArtwork();
    }

    @Nullable
    public String playDanmu() {
        return music.playDanmu();
    }

    public void setPlayDanmu(String danmu) {
        music.setPlayDanmu(danmu);
    }

    public void cancelInFlight() {
        cancelPlayTimeout();
        cancelSwitchLinePlayTimeout();
        cancelResolvePlayUrlTimeout();
        fetch.cancelPlayRequest();
        stopParse();
    }

    public void stopPlaybackForPageExit() {
        st.clearSessionFlags();
        // 与 onHostDestroy 同属会话边界:一并作废"播完待撤会话"的待判消息
        timeouts.cancelPendingCompletionDrop();
        cancelInFlight();
        // 页面退出即"没有正在播的源"
        ApiConfig.get().setCurrentPlaySourceKey("");
        music.stopMusicSession();
    }

    public void stopMusicSessionForFailedPlayback() {
        music.stopMusicSessionForFailedPlayback();
    }

    public void stopMusicSession() {
        music.stopMusicSession();
    }

    /** 页面销毁:清会话标记 + 停通知 + 收预载 */
    public void onHostDestroy() {
        st.clearSessionFlags();
        timeouts.cancelPendingCompletionDrop();
        // 引擎已释放:三处超时消息若留着,到期仍会走"换线/报错"链路并打到视图桥(见 detach 的桥切换)
        cancelPlayTimeout();
        cancelResolvePlayUrlTimeout();
        stopParse();
        music.stopMusicSession();
        destroyPreload();
    }

    public boolean isConfirmedAudioOnly() {
        return music.isConfirmedAudioOnly();
    }

    public boolean handlePlayStateForMusicSession(int playState) {
        return music.handlePlayStateForMusicSession(playState);
    }



    public void ensureAudioOnlyRender() {
        music.ensureAudioOnlyRender();
    }

    public void updateMusicSession() {
        music.updateMusicSession();
    }

    public boolean selectQuality(int position) {
        return music.selectQuality(position);
    }
    
    public static boolean looksLikeAudioUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        int fragment = lower.indexOf('#');
        if (fragment >= 0) lower = lower.substring(0, fragment);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".oga") || lower.endsWith(".opus") || lower.endsWith(".wma");
    }
}
