package com.github.tvbox.osc.player;

import android.content.Context;
import android.webkit.WebView;


import org.json.JSONObject;

import java.util.HashMap;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 播放调度层需要的"视图侧契约"(播放服务化 Spec §3-P1 出口条件,`skill/avbox-playback-service-spec.md`)。
 *
 * <p>动机:`PlaybackController` 接管"会话数据 / 取流解析 / 重试换线 / 超时 / 起播 / 预载 / 媒体会话"后,
 * 仍需要页面提供"播这个地址""把播放器释放掉""弹个提示""读当前播放状态""建一个嗅探 WebView 并挂上去"
 * 等动作 —— 这些在 P0–P1 由页面内的 `PlayContainer` 实现(匿名实现,不扩大容器公开面),
 * **P2 起由"服务 → 页面"的桥实现**(服务持有播放器,页面只提供显示宿主与提示层)。
 *
 * <p>约定:除 `currentXxx`/`isXxx`/`consumeXxx` 读值外,所有回调都可能由调度层同步调用;实现方需自行做
 * "页面已销毁"的判空(见 {@link #isPageAlive()})。
 */
public interface PlaybackViewBridge {

    // ---------- 页面状态 ----------

    /** 页面是否仍可用(未 finish/未销毁) */
    boolean isPageAlive();

    /** 主线程执行(页面已销毁时实现方应静默丢弃) */
    void runOnUi(Runnable action);

    /** 页面上下文(媒体通知/前台服务需要;禁止长期持有) */
    Context context();

    /** 媒体会话回调的宿主(通知栏播放/暂停/上一集/下一集/拖动都打到它) */
    PlaybackHostApi playbackHost();

    /** 轻提示(Toast) */
    void toast(CharSequence text);

    /** 播放器提示层(loading/error 文案;对应既有的 setTip(msg, loading, err)) */
    void showTip(String msg, boolean loading, boolean error);

    /** 收起提示层(主线程语义) */
    void hideTipOnUiThread();

    /** 解析/起播失败的重试入口(即既有的 errorWithRetry) */
    void showErrorWithRetry(String err, boolean finish);

    /** 申请通知权限(媒体通知兜底) */
    void requestNotificationPermission();

    // ---------- 播放状态读取 ----------

    /** 当前播放状态(dkplayer VideoView.STATE_*;无播放器时 -1) */
    int currentPlayState();

    /** 当前播放位置(毫秒;无播放器时 0) */
    long currentPosition();

    /** 当前媒体总时长(毫秒;未知时 0) */
    long duration();

    /** 是否正在播放 */
    boolean isPlaying();

    /** 当前内核实例(轨道信息读取用;无播放器时 null) */
    AbstractPlayer mediaPlayer();

    /** 内核是否停在错误态(无内核为 false):错误内核不能复用,必须强制重建 */
    boolean isKernelErrored();

    // ---------- 起播/释放 ----------

    /** 释放当前播放器实例(内核切换前 / 换线重播前 / 换内核时) */
    void releasePlayer();

    /** 顶部标题(播放器覆盖层) */
    void setTitle(String title);

    /** 停掉旁路资源(磁力/p2p/dash 代理;既有 mController.stopOther) */
    void stopOtherPlayers();

    /** 收起歌词视图(切集/换源) */
    void clearLyric();

    /** 清空封面 */
    void clearArtwork();

    /** 盖一层黑帧(复用播放器换集时避免上一集画面残留) */
    void clearVideoFrame();

    /** 外挂字幕视图显隐(起播前复位为隐藏,需要时由字幕决策链路再显示) */
    void setSubtitleViewVisible(boolean visible);

    /** 让页面复位"新一次播放开始"的侧写标记(exitingPreview) */
    void onNewPlayStarted();

    /** 把播放器配置下发到 dkplayer(内核只剩 EXO,forceKernel 不再改变结果;既有 PlayerHelper.updateCfg) */
    void applyPlayerConfigToView(int forceKernel);

    /** 纯音频 URL 起播时改用 TextureView 渲染 */
    void useTextureRenderForAudio();

    /** 纯音频轨道确认后热切 TextureView(既有 MyVideoView.switchRenderToTexture) */
    void switchRenderToTexture();

    /** 有视频轨时按用户设置恢复渲染视图(既有 MyVideoView.ensureRenderViewMatchesConfig) */
    void ensureRenderViewMatchesConfig();

    /** 用外部播放器播放(既有 PlayerHelper.runExternalPlayer) */
    boolean playExternalPlayer(int playerType, String url, String title, String subtitle,
                               HashMap<String, String> headers, long progress);

    /** M3U8 去广告代理链路(既有 mController.playM3u8) */
    void playM3u8(String url, HashMap<String, String> headers);

    /**
     * 同 {@link #playM3u8(String, HashMap)},但携带发起方的解析代际:净化在后台跑,完成后再回头起播,
     * 期间切集的话旧集地址会把新播放顶掉。实现方须在起播前用
     * {@link PlaybackController#isParseResultCurrent(int)} 校验,不一致则丢弃。
     */
    void playM3u8(String url, HashMap<String, String> headers, int gen);

    /**
     * 真正把地址交给播放器并起播(既有 goPlayUrl 的尾部连招):
     * hidePauseRoot → 判断可否复用 → 释放/设进度键/setUrl → 换线超时 → replay(复用)或 start → resetSpeed。
     */
    void startVideoPlayback(String url, HashMap<String, String> headers, boolean forceExoPlayer);

    /**
     * 自动重试时切换播放内核(既有 mController.switchPlayer 的语义)。
     *
     * @return true = 切换被跳过(未真正换内核,调用方不要再释放播放器)
     */
    boolean switchPlayerKernel();

    /** 把播放器配置刷到控制器 UI(内核回滚时用) */
    void applyPlayerConfig(JSONObject cfg);

    // ---------- 解析/嗅探 ----------

    /** 多地址数组(url 字段是 JSON 数组)时取第一个可用地址 */
    String firstUrlByArray(String url);

    /** 设置封面(页面把它挂到播放器;空串=清除) */
    void setArtwork(String url);

    /** 解析中标记(控制器菜单的"解析"状态) */
    void showParse(boolean show);

    /** URL 编码(字幕文件名等) */
    String encodeUrl(String url);

    /** 嗅探页 onPageFinished 后交给页面执行注入脚本 */
    void evaluateScript(String url, WebView webView);

    /** 新建 1×1 嗅探 WebView(页面用 Activity 上下文创建,保证 AutoSize/主题一致) */
    WebView newSniffWebView();

    /** 把嗅探 WebView 挂到页面内容视图(1×1) */
    void attachSniffWebView(WebView webView);

    // ---------- 预载(调度在控制器,视图资源在页面) ----------

    /** 由页面组装的"下一集预载"目标快照(需要页面上下文与真实播放器实例判内核) */
    PreloadCoordinator.Snapshot buildPreloadSnapshot();

    /** 显示「下一集已就绪」Toast(页面持有 Toast 实例,约 3s 自动撤下) */
    void showPreloadReadyTip();

    /** 立即撤下「下一集已就绪」Toast */
    void hidePreloadReadyTip();

    // ---------- 其它 ----------

    /** 线路耗尽后的换源兜底入口(页面实现;返回是否已接管) */
    boolean onLinesExhausted();
}
