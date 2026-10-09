package com.github.tvbox.osc.ui.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import android.widget.FrameLayout;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.CacheManager;
import com.github.tvbox.osc.dlna.CastVideo;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.player.PreloadCoordinator;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.PageHost;
import com.github.tvbox.osc.player.PlaybackEngine;
import com.github.tvbox.osc.player.PlaybackService;
import com.github.tvbox.osc.player.PlaybackController;
import com.github.tvbox.osc.player.PlaybackHostApi;
import com.github.tvbox.osc.player.PlaybackPage;
import com.github.tvbox.osc.player.PlaybackSession;
import com.github.tvbox.osc.player.PlaybackViewBridge;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.controller.ComposeVideoController;
import com.github.tvbox.osc.player.controller.PlayerControlApi;
import com.github.tvbox.osc.player.state.CastSheetState;
import com.github.tvbox.osc.player.state.PlayerUiState;
import com.github.tvbox.osc.player.state.SelectDialogState;
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState;
import com.github.tvbox.osc.player.state.SubtitleSheetState;
import me.jessyan.autosize.internal.CustomAdapt;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.TrackMemory;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.sourcedata.SubtitleViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.CaptionStyleCompat;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.util.HashMap;
import java.util.List;

import me.jessyan.autosize.AutoSize;
import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;

public class PlayContainer extends FrameLayout implements CustomAdapt, PlaybackHostApi, PlaybackPage {

    private final TrackSelectorDelegate trackSelector = new TrackSelectorDelegate(new TrackSelectorDelegate.Host() {
        @Override
        public MyVideoView player() {
            return mVideoView;
        }

        @Override
        public Context context() {
            return mContext;
        }

        @Override
        public PlayerUiState uiState() {
            return mController.getUiState();
        }
    });

    PlaybackController scheduler;
    private FrameLayout surfaceSlot;
    private PlaybackEngine engine;
    PageHost pageHost;
    Activity mActivity;
    private final Context mContext;

    /** 存入字段而不是每次写 lambda:hostDestroy 要按"是不是自己"摘监听 */
    private final TipStateListener tipStateListener = this::onTipStateChanged;

    public PlayContainer(@NonNull Activity activity) {
        super(activity);
        mActivity = activity;
        mContext = activity;
        engine = PlaybackService.engine(activity);
        scheduler = engine.controller();
        AutoSize.autoConvertDensity(activity, getSizeInDp(), isBaseOnWidth());
        LayoutInflater.from(activity).inflate(R.layout.view_play_container, this, true);
        PlayerTipBridge.hide();
        init();
        // 提示层(加载/错误遮罩)画在控制器 Compose 层:状态要桥进控制层,并收起位置在控制器之上的提示视图。
        // 挂监听在 init() 之后与 hide() 之后(免旧容器残留回调)
        PlayerTipBridge.setTipStateListener(tipStateListener);
        scheduler.setViewBridge(viewBridge);
        if (engine != null) engine.attach(this);
    }

    /** 提示层状态变化:桥入控制层状态(遮罩在视频面之上、顶栏/底栏之下),并让提示视图让位 */
    private void onTipStateChanged(PlayerTipState tip) {
        if (mHandler == null) return;
        boolean showing = tip.getLoading() || tip.getErr();
        // 提示可能由调度/取流线程写入(setTip 会从解析链路直接调用),控制层状态统一回主线程
        mHandler.post(() -> {
            if (mController != null) {
                mController.getUiState().applyTip(tip.getMsg(), tip.getLoading(), tip.getErr());
            }
        });
    }

    public PlaybackViewBridge viewBridge() {
        return viewBridge;
    }

    @Override
    public ViewGroup renderSlot() {
        return surfaceSlot;
    }

    public void onServiceStopped() {
        mVideoView = null;
        engine = null;
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }

    boolean isAttached() {
        if (pageHost != null) return pageHost.isPageAlive();
        return mActivity != null && !mActivity.isFinishing();
    }

    public void setPageHost(PageHost host) {
        this.pageHost = host;
    }

    /** 详情页选集面板显隐(面板状态在 DetailViewModel,这里只做投影,供底栏冻结自动收起用) */
    public void setEpisodeSheetOpen(boolean open) {
        if (mController != null) mController.getUiState().setEpisodeSheetOpen(open);
    }

    /** 清晰度切换结果回调:仅在受理后回调一次,与 `selectQuality` 同线程返回;页面必须从主线程调它 */
    public interface OnQualitySelectedListener {
        void onQualitySelected(int position);
    }

    private OnQualitySelectedListener qualitySelectedListener;

    public void setOnQualitySelectedListener(OnQualitySelectedListener listener) {
        qualitySelectedListener = listener;
    }

    private final PlaybackViewBridge viewBridge = new PlayContainerViewBridge(this);

    /** 控制器回调:切解码重播等复用路径要直接触发,故存字段 */
    private final PlayContainerControlListener controlListener = new PlayContainerControlListener(this);

    private boolean lifecyclePaused;
    private String ownedPlaybackKey;

    public void hostResume() {
        exitingPreview = false;
        if (mController != null) mController.setLifecyclePaused(false);
        reattachIfOwnedByOther();
        if (mVideoView != null && lifecyclePaused) {
            lifecyclePaused = false;
            if (ownsEngineContent()) {
                mVideoView.resume();
            }
        }
    }

    private void reattachIfOwnedByOther() {
        if (engine == null || surfaceSlot == null) return;
        if (engine.attachedPage() == this) return;
        if (engine.isReleased()) return;
        if (engine.isLiveMode()) engine.exitLive();
        engine.attach(this);
        // 重新接管后本页恢复"退出即停播"的职责:交接标记是给"交出去后本页就销毁"准备的,
        // 音乐页返回(影视内容)这条路径本页仍存活,不清掉会让 hostDestroy 漏掉 detach —— 退出后声音不停
        handedOver = false;
        if (!ownsEngineContent() && mVideoView != null) {
            // 内核内容已被别的页面换走(或对方尚未销毁):它的进度只有 detach 落盘这一个时点,
            // 而那次落盘可能晚于本页新起播改写 progressKey —— 接管时先按现键存一次,两边时序就都无害了
            mVideoView.saveCurrentProgress();
        }
        if (mVideoView != null && mController != null) {
            mVideoView.setVideoController((BaseVideoController) mController);
            int state = mVideoView.getCurrentPlayState();
            if (mVideoView.getMediaPlayer() != null
                    && state != VideoView.STATE_IDLE && state != VideoView.STATE_ERROR
                    && ownsEngineContent()) {
                rebindPlaybackOverlay();
            }
        }
        if (ownsEngineContent()) {
            // 接管的是引擎里既有的会话(直播回切/音乐页交还),页面自己没走过 setData,数据要在这里补同步
            syncSessionVod();
        }
        LOG.i("echo-p4 re-attach after live/other page");
    }

    public void hostPause() {
        if (mVideoView != null && !exitingPreview && !scheduler.isConfirmedAudioOnly()) {
            // 传 isPlaying() 而非恒 true:标记语义 = 回前台会续播(与 hostResume 同一判据),手动暂停后离开须为 false
            lifecyclePaused = mVideoView.isPlaying();
            if (mController != null) mController.setLifecyclePaused(lifecyclePaused);
            mVideoView.pause();
        }
    }

    private boolean handedOver;

    /** 交给音乐播放页接管:引擎摘视图但不停播,随后的 hostDestroy 不得再 detach(会停掉刚交接的音频) */
    public void handOverToNextPage() {
        if (engine == null) return;
        handedOver = true;
        engine.detachForHandover(this);
    }

    public void hostDestroy() {
        LOG.i("echo-music destroy: hostDestroy enter");
        PlayerTipBridge.clearTipStateListener(tipStateListener);
        // 页面回调随页面一起摘掉,不留方法引用
        qualitySelectedListener = null;
        if (engine != null && !handedOver) engine.detach(this);
        cancelPreloadToast();
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        trackSelector.invalidatePendingSwitch();
        mVideoView = null;
        if (mController != null) mController.stopOther();
        mActivity = null;
        LOG.i("echo-music destroy: hostDestroy done");
    }

    @Override
    public float getSizeInDp() {
        return (mActivity instanceof CustomAdapt) ? ((CustomAdapt) mActivity).getSizeInDp() : 0;
    }

    @Override
    public boolean isBaseOnWidth() {
        return !(mActivity instanceof CustomAdapt) || ((CustomAdapt) mActivity).isBaseOnWidth();
    }

    private static final int MSG_PARSE_TIMEOUT = 100;
    private static final long PRELOAD_TOAST_REFRESH_DELAY_MS = 1000L;
    MyVideoView mVideoView;
    PlayerControlApi mController;
    private Toast preloadReadyToast;
        private Handler mHandler;
    boolean exitingPreview = false;
    private boolean previewMode;
    private final List<Cue> exoCues = new ArrayList<>();
    private boolean exoInternalSubtitle;

    /** 字幕决定代际:用户每次选字幕/每轮起播决策自增;在途的在线字幕解析只在这期间没变时才允许落地 */
    private int subtitleDecisionSeq;

    private final long videoDuration = -1;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            applySubtitleTextSize();
        }
    }

    private void init() {
        initView();
    }

    private void initView() {
        EventBus.getDefault().register(this);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_PARSE_TIMEOUT:
                        scheduler.stopParse();
                        errorWithRetry(mContext.getString(R.string.player_error_sniff), false);
                        break;
                }
                return false;
            }
        });
        surfaceSlot = findViewById(R.id.surfaceSlot);
        mController = new ComposeVideoController(mActivity);
        mController.setKernelProvider(() -> mVideoView);

        mController.getLyricView().setTextSize(previewMode ? 16 : 24);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        mVideoView = engine == null ? null : engine.player();
        mController.setListener(controlListener);
        if (mVideoView != null) mVideoView.setVideoController((BaseVideoController) mController);
    }

    public void showCast() {
        showCastDialog();
    }

    void showCastDialog() {
        if (TextUtils.isEmpty(scheduler.webPlayUrl())) {
            Toast.makeText(mContext, mContext.getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isAttached()) return;
        HashMap<String, String> headers = scheduler.webHeaderMap() == null ? null : new HashMap<>(scheduler.webHeaderMap());
        CastVideo video = new CastVideo(scheduler.getCastUrl(scheduler.webPlayUrl()), getCastTitle(), headers, getCastPosition());
        PlayerUiState uiState = mController.getUiState();
        uiState.setCastSheet(new CastSheetState(video, () -> {
            if (mVideoView != null) mVideoView.pause();
            return kotlin.Unit.INSTANCE;
        }));
    }

    /**
     * 把引擎当前会话的影片数据同步给控制层:选集入口可见性由它派生 ——
     * 同片接管(退出页面后快速重进)与页面重新接管都不走 prepare,只在 prepare 时计算会漏掉这些会话。
     */
    private void syncSessionVod() {
        if (mController == null || scheduler == null) return;
        mController.getUiState().setSessionVod(scheduler.vod());
    }

    private String getCastTitle() {
        if (scheduler.vod() == null) return "TVBox";
        try {
            VodInfo.VodSeries series = scheduler.vod().seriesMap.get(scheduler.vod().playFlag).get(scheduler.vod().playIndex);
            return scheduler.vod().name + " " + series.name;
        } catch (Exception e) {
            return TextUtils.isEmpty(scheduler.vod().name) ? "TVBox" : scheduler.vod().name;
        }
    }

    private long getCastPosition() {
        try {
            return mVideoView == null ? 0 : mVideoView.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    void setSubtitle(String path) {
        if (path != null && path .length() > 0) {
            subtitleDecisionSeq++;
            hideExoInternalSubtitle();
            mController.getSubtitleView().setVisibility(View.GONE);
            mController.getSubtitleView().setSubtitlePath(path);
            setSubtitleViewTextStyle(KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0));
            mController.getSubtitleView().setVisibility(View.VISIBLE);
        }
    }

    void selectMySubtitle() {
        try {
            if (!isAttached() || mVideoView == null) return;
            PlayerUiState uiState = mController.getUiState();
            AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
            boolean hasInternal = mController.getSubtitleView().hasInternal || hasExoInternalSubtitle(mediaPlayer);
            boolean exoInternal = mediaPlayer instanceof ExoPlayer && exoInternalSubtitle;
            uiState.setSubtitleSheet(new SubtitleSheetState(
                    exoInternal,
                    hasInternal,
                    () -> {
                        selectMyInternalSubtitle();
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        openLocalSubtitleChooser();
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        openSubtitleSearchSheet();
                        return kotlin.Unit.INSTANCE;
                    },
                    style -> {
                        KV.put(HawkConfig.SUBTITLE_TEXT_STYLE, style);
                        setSubtitleViewTextStyle(style);
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        applySubtitleTextSize();
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> {
                        SubtitleHelper.reset();
                        setSubtitleViewTextStyle(0);
                        applySubtitleTextSize();
                        return kotlin.Unit.INSTANCE;
                    }));
        } catch (Exception e) {
            LOG.e("PlayContainer", e);
        }
    }

    private void openLocalSubtitleChooser() {
        if (pageHost != null) pageHost.launchLocalSubtitlePicker();
    }

    public void onLocalSubtitlePicked(android.net.Uri uri) {
        final android.app.Activity activity = mActivity;
        if (activity == null || activity.isFinishing()) return;
        new Thread(() -> {
            try {
                String name = queryDisplayName(activity, uri);
                if (name == null || !name.contains(".")) name = "local_subtitle.srt";
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                File dst = new File(activity.getCacheDir(), "subtitle_" + System.currentTimeMillis() + "_" + name);
                try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                String path = dst.getAbsolutePath();
                activity.runOnUiThread(() -> {
                    if (!isAttached()) return;
                    LOG.i("echo-Local Subtitle Path: " + path);
                    // 本地文件在整部片里通用,记进片级记忆(文件被系统清掉时按失效回落)
                    TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.subtitleLocal(path));
                    setSubtitle(path);
                });
            } catch (Exception e) {
                LOG.e("echo-Local Subtitle copy err: " + e);
                activity.runOnUiThread(() -> {
                    if (isAttached()) {
                        android.widget.Toast.makeText(activity, activity.getString(R.string.toast_subtitle_read_failed), android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private String queryDisplayName(android.app.Activity activity, android.net.Uri uri) {
        try (android.database.Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
            LOG.d("PlayContainer", "query display name failed, keep null");
        }
        return null;
    }

    private void openSubtitleSearchSheet() {
        if (!isAttached()) return;
        String word = (scheduler.vod().playFlag.contains("Ali") || scheduler.vod().playFlag.contains("parse"))
                ? scheduler.vod().playNote : scheduler.vod().name;
        PlayerUiState uiState = mController.getUiState();
        uiState.setSubtitleSearchSheet(new SubtitleSearchSheetState(word == null ? "" : word, (subtitle, releaseUrl) -> {
            if (!isAttached()) return kotlin.Unit.INSTANCE;
            mActivity.runOnUiThread(() -> {
                String zimuUrl = subtitle.getUrl();
                LOG.i("echo-Remote Subtitle Url: " + zimuUrl);
                // 只记发布页 + 文件名(直链只对当集有效):换集按集号回同一发布页取本集文件
                TrackMemory.saveSubtitle(trackMemoryKey(),
                        TrackMemory.subtitleOnline(releaseUrl, subtitle.getName()));
                setSubtitle(zimuUrl);
            });
            return kotlin.Unit.INSTANCE;
        }));
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    void setSubtitleViewTextStyle(int style) {
        if (style == 0) {
            mController.getSubtitleView().setTextColor(getContext().getResources().getColorStateList(R.color.color_FFFFFF));
        } else if (style == 1) {
            mController.getSubtitleView().setTextColor(getContext().getResources().getColorStateList(R.color.color_FFB6C1));
        }
        applyExoSubtitleStyle();
    }

    void selectMyAudioTrack() {
        trackSelector.selectAudioTrack();
    }

    void selectMyVideoTrack() {
        trackSelector.selectVideoTrack();
    }

    void selectMyInternalSubtitle() {
        if (mVideoView == null) return;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof ExoPlayer) {
            trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        }
        if (trackInfo == null) {
            Toast.makeText(mContext, mContext.getString(R.string.player_no_internal_subtitle), Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) return;
        List<String> names = new ArrayList<>();
        for (TrackInfoBean item : bean) names.add(item.name);
        mController.getUiState().setSelectDialog(new SelectDialogState(
                mContext.getString(R.string.player_switch_internal_subtitle),
                names,
                trackInfo.getSubtitleSelected(false),
                pos -> {
                    if (pos < 0 || pos >= bean.size()) return kotlin.Unit.INSTANCE;
                    TrackInfoBean value = bean.get(pos);
                    try {
                        // 在途的在线字幕解析作废:别让它回头盖掉用户这一手
                        subtitleDecisionSeq++;
                        for (TrackInfoBean subtitle : bean) {
                            subtitle.selected = TrackSelectorDelegate.isSameTrack(subtitle, value);
                        }
                        if (mediaPlayer instanceof ExoPlayer) {
                            mController.getSubtitleView().setVisibility(View.GONE);
                            mController.getSubtitleView().destroy();
                            mController.getSubtitleView().clearSubtitleCache();
                            mController.getSubtitleView().isInternal = false;
                            exoInternalSubtitle = true;
                            ((ExoPlayer) mediaPlayer).setTrack(value);
                            ((ExoPlayer) mediaPlayer).setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
                            mController.getExoSubtitleView().setVisibility(View.VISIBLE);
                            applyExoSubtitleSettings();
                        }
                    } catch (Exception e) {
                        LOG.e("echo-switch-internal-subtitle-error:" + e.getMessage());
                    }
                    return kotlin.Unit.INSTANCE;
                }));
    }

    private boolean hasExoInternalSubtitle(AbstractPlayer mediaPlayer) {
        if (!(mediaPlayer instanceof ExoPlayer)) return false;
        TrackInfo trackInfo = ((ExoPlayer) mediaPlayer).getTrackInfo();
        return trackInfo != null && !trackInfo.getSubtitle().isEmpty();
    }

    private void hideExoInternalSubtitle() {
        exoInternalSubtitle = false;
        exoCues.clear();
        if (mController != null && mController.getExoSubtitleView() != null) {
            mController.getExoSubtitleView().setCues(exoCues);
            mController.getExoSubtitleView().setVisibility(View.GONE);
        }
    }

    private void onExoCues(List<Cue> cues) {
        if (!isAttached() || !exoInternalSubtitle) return;
        exoCues.clear();
        if (cues != null) exoCues.addAll(cues);
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyExoSubtitleSettings();
            }
        });
    }

    private void applyExoSubtitleSettings() {
        if (!exoInternalSubtitle || mController == null || mController.getExoSubtitleView() == null) return;
        applyExoSubtitleStyle();
        float scale = SubtitleHelper.getExoSubtitleScale() / 100f;
        float position = SubtitleHelper.getExoSubtitlePosition();
        mController.getExoSubtitleView().setFractionalTextSize(0.0533f * scale);
        mController.getExoSubtitleView().setBottomPaddingFraction(limit(0.08f + position / 100f, 0f, 0.9f));

        List<Cue> displayCues = new ArrayList<>();
        for (Cue cue : exoCues) {
            if (cue.bitmap == null) {
                displayCues.add(cue);
                continue;
            }
            Cue.Builder builder = cue.buildUpon();
            if (cue.size != Cue.DIMEN_UNSET) {
                builder.setSize(limit(cue.size * scale, 0f, 1f));
            }
            if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                builder.setBitmapHeight(limit(cue.bitmapHeight * scale, 0f, 1f));
            }
            if (cue.line != Cue.DIMEN_UNSET) {
                builder.setLine(limit(cue.line - position / 100f, 0f, 1f), cue.lineType);
            }
            displayCues.add(builder.build());
        }
        mController.getExoSubtitleView().setCues(displayCues);
    }

    private void applyExoSubtitleStyle() {
        if (mController == null || mController.getExoSubtitleView() == null) return;
        int style = KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0);
        int textColor = getContext().getResources().getColorStateList(
                style == 1 ? R.color.color_FFB6C1 : R.color.color_FFFFFF).getDefaultColor();
        mController.getExoSubtitleView().setStyle(new CaptionStyleCompat(
                textColor,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT_BOLD));
    }

    private float limit(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    void setTip(String msg, boolean loading, boolean err) {
        if (!isAttached()) return;
        PlayerTipBridge.setTip(msg, loading, err);
    }

    void hideTip() {
        PlayerTipBridge.hide();
    }

    void hideTipOnUiThread() {
        if (!isAttached()) return;
        PlayerTipBridge.hide();
    }

    void showPreloadReady() {
        final Activity activity = mActivity;
        if (activity == null || !isAttached() || mHandler == null) return;
        if (preloadReadyToast != null) preloadReadyToast.cancel();
        preloadReadyToast = Toast.makeText(activity, activity.getString(R.string.player_next_episode_ready), Toast.LENGTH_SHORT);
        preloadReadyToast.show();
        mHandler.removeCallbacks(refreshPreloadToastRunnable);
        mHandler.postDelayed(refreshPreloadToastRunnable, PRELOAD_TOAST_REFRESH_DELAY_MS);
    }

    private final Runnable refreshPreloadToastRunnable = new Runnable() {
        @Override
        public void run() {
            if (preloadReadyToast != null) preloadReadyToast.show();
        }
    };

    void hidePreloadReady() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelPreloadToast();
        } else if (mActivity != null) {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelPreloadToast();
                }
            });
        }
    }

    private void cancelPreloadToast() {
        if (mHandler != null) mHandler.removeCallbacks(refreshPreloadToastRunnable);
        if (preloadReadyToast != null) {
            preloadReadyToast.cancel();
            preloadReadyToast = null;
        }
    }

    /**
     * 回调线程可能不是主线程:本方法会走"释放内核 + 重起播"这条**增删播放器子视图**的链路,必须整段在主线程,
     * 非主线程增删子视图会让 {@code ViewGroup.mChildren} 出 null 洞(下次 traversal 崩)——不能只把提示文案 post 出去。
     */
    void errorWithRetry(String err, boolean finish) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post(() -> errorWithRetry(err, finish));
            return;
        }
        if (scheduler.isPlaybackStarted()) {
            scheduler.cancelPlayTimeout();
            hideTipOnUiThread();
            if (scheduler.retryAfterStartedError()) return;
            scheduler.stopMusicSessionForFailedPlayback();
            if (!isAttached()) return;
            setTip(err, false, true);
            if (finish) {
                Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!scheduler.autoRetry()) {
            scheduler.stopMusicSessionForFailedPlayback();
            if (!isAttached()) return;
            setTip(err, false, true);
            if (finish) {
                Toast.makeText(mContext, err, Toast.LENGTH_SHORT).show();
            }
        }
    }

                    void initSubtitleView() {
        if (mVideoView == null) return;
        TrackInfo trackInfo = null;
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        mController.getLyricView().setTextSize(previewMode ? 16 : 24);
        applySubtitleTextSize();
        mController.getLyricView().setVisibility(View.GONE);
        mController.getLyricView().reset();
        mController.getLyricView().bindToMediaPlayer(mediaPlayer);
        mController.getLyricView().setMergeSameTime(true);
        mController.getLyricView().setLyricMode(true);
        mController.getLyricView().setPlaySubtitleCacheKey(scheduler.lyricCacheKey());
        mController.getSubtitleView().hasInternal = false;
        mController.getSubtitleView().isInternal = false;
        hideExoInternalSubtitle();
        String memoryKey = trackMemoryKey();
        if (mediaPlayer instanceof ExoPlayer) {
            ExoPlayer exoPlayer = (ExoPlayer) mediaPlayer;
            exoPlayer.setContentKey(memoryKey);
            trackInfo = exoPlayer.getTrackInfo();
            if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                mController.getSubtitleView().hasInternal = true;
                exoInternalSubtitle = true;
                mController.getExoSubtitleView().setVisibility(View.VISIBLE);
                exoPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
                exoPlayer.setOnCuesListener(new ExoPlayer.OnCuesListener() {
                    @Override
                    public void onCues(List<Cue> cues) {
                        onExoCues(cues);
                    }
                });
                applyExoSubtitleSettings();
            }
            exoPlayer.restoreTracks();
        }
        // 歌词来源:内联 data: 在内存里、毫秒级;URL 歌词优先吃本集缓存,否则每次起播都要走网络(快慢全看源站,慢链还要等满 10s 超时)
        String lyric = scheduler.playLyric();
        String lyricPath = lyric;
        if (TextUtils.isEmpty(lyric) || !lyric.startsWith("data:")) {
            String cachedLyric = cachedPlayPath(scheduler.lyricCacheKey());
            if (!TextUtils.isEmpty(cachedLyric)) lyricPath = cachedLyric;
        }
        if (!TextUtils.isEmpty(lyricPath)) {
            mController.getLyricView().setSubtitlePath(lyricPath);
            mController.getLyricView().setVisibility(View.VISIBLE);
        }
        mController.getSubtitleView().bindToMediaPlayer(mVideoView.getMediaPlayer());
        mController.getSubtitleView().setPlaySubtitleCacheKey(scheduler.subtitleCacheKey());
        applySubtitleDecision(mediaPlayer, trackInfo);
    }

    /**
     * 字幕决策:本片记忆(用户显式选择)优先,其次本集缓存 → 源站字幕 → 内置默认。
     *
     * <p>显式选择压过源站每集给的字幕(点过来源就是明确意图);任一步拿不到就落到默认链,不新增"没字幕"的空档。
     */
    private void applySubtitleDecision(AbstractPlayer mediaPlayer, TrackInfo trackInfo) {
        final String memoryKey = trackMemoryKey();
        // 新一轮决策:上一轮在途的在线字幕解析作废
        subtitleDecisionSeq++;
        String record = TrackMemory.loadSubtitle(memoryKey);
        if (TrackMemory.isSubtitleOff(record)) {
            closeSubtitleViews();
            return;
        }
        if (TrackMemory.isSubtitleLocal(record)) {
            String path = TrackMemory.localPath(record);
            if (!TextUtils.isEmpty(path) && new File(path).exists()) {
                setSubtitle(path);
                return;
            }
            LOG.i("echo-track-memory local subtitle gone, fallback: " + path);
        } else if (TrackMemory.isSubtitleOnline(record)) {
            final AbstractPlayer player = mediaPlayer;
            final TrackInfo info = trackInfo;
            resolveRememberedOnlineSubtitle(memoryKey, record, () -> applyDefaultSubtitle(player, info));
            return;
        } else if (TrackMemory.isSubtitleTrack(record) && mController.getSubtitleView().hasInternal) {
            // 轨道已由播放器按指纹还原(定位失败也会退默认选轨),这里只补"内置字幕在显示"的视图状态
            showInternalSubtitle(mediaPlayer);
            return;
        }
        applyDefaultSubtitle(mediaPlayer, trackInfo);
    }

    /** 无记忆(或记忆失效)时的既有链路:本集缓存 → 源站字幕 → 内置字幕 */
    private void applyDefaultSubtitle(AbstractPlayer mediaPlayer, TrackInfo trackInfo) {
        String subtitlePathCache = cachedPlayPath(scheduler.subtitleCacheKey());
        if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
            hideExoInternalSubtitle();
            mController.getSubtitleView().setSubtitlePath(subtitlePathCache);
            return;
        }
        if (scheduler.playSubtitle() != null && scheduler.playSubtitle() .length() > 0) {
            hideExoInternalSubtitle();
            mController.getSubtitleView().setSubtitlePath(scheduler.playSubtitle());
            return;
        }
        if (!mController.getSubtitleView().hasInternal) return;
        ensureInternalSubtitleTrackSelected(mediaPlayer, trackInfo);
        showInternalSubtitle(mediaPlayer);
    }

    /** 让内置字幕显示出来(选哪条轨由播放器负责,这里只管视图与延时) */
    private void showInternalSubtitle(AbstractPlayer mediaPlayer) {
        if (mediaPlayer instanceof ExoPlayer) {
            ((ExoPlayer) mediaPlayer).setInternalSubtitleDelay(SubtitleHelper.getTimeDelay());
            exoInternalSubtitle = true;
            mController.getExoSubtitleView().setVisibility(View.VISIBLE);
            applyExoSubtitleSettings();
        }
    }

    /**
     * 补一次默认内置选轨。
     *
     * <p>只在"外挂字幕落地失败回落"这条路上需要:那时播放器一条内置轨都没选过(EXO 只自动选带 DEFAULT
     * 标记的轨),光把视图置为显示态会得到整集无字幕。
     * ⚠️ 不要在"按指纹还原"那条分支上加这个调用:EXO 的 getCurrentTracks 读不到刚下发到播放线程的
     * setParameters,会把刚还原好的用户选择当成"没选",再顶成默认轨。
     */
    private void ensureInternalSubtitleTrackSelected(AbstractPlayer mediaPlayer, TrackInfo trackInfo) {
        if (mediaPlayer instanceof ExoPlayer) {
            ((ExoPlayer) mediaPlayer).ensureSubtitleTrackSelected();
        }
    }

    /**
     * 还原"在线字幕"选择:同一发布页里按集号找本集文件,取不到就回落默认链(直链只对当集有效,入库的是发布页)。
     *
     * <p>发布页 + 直链是两跳异步请求,回来时可能已换集/换源/用户自己选过字幕,故落地前必须过
     * {@link #isSubtitleResultCurrent} 的三道守卫。
     */
    private void resolveRememberedOnlineSubtitle(String memoryKey, String record, Runnable fallback) {
        String releaseUrl = TrackMemory.onlineRelease(record);
        // ViewModel 挂在宿主 Activity 上(与字幕面板同一实例);拿不到就回落,不猜
        if (TextUtils.isEmpty(releaseUrl) || !(mActivity instanceof ViewModelStoreOwner)) {
            runOnUi(fallback);
            return;
        }
        VodInfo.VodSeries series = scheduler.vod() == null ? null
                : scheduler.currentSeries(scheduler.vod().playFlag, scheduler.vod().playIndex);
        String episodeName = series == null ? "" : series.name;
        String fileNameHint = TrackMemory.onlineFileName(record);
        final String episodeKey = scheduler.progressKey();
        final int decisionSeq = subtitleDecisionSeq;
        LOG.i("echo-track-memory online subtitle: release=" + releaseUrl + " episode=" + episodeName);
        new ViewModelProvider((ViewModelStoreOwner) mActivity).get(SubtitleViewModel.class).pickEpisodeSubtitle(
                releaseUrl, episodeName, fileNameHint,
                subtitle -> runOnUi(() -> {
                    if (!isSubtitleResultCurrent(memoryKey, episodeKey, decisionSeq)) return;
                    String url = subtitle == null ? null : subtitle.getUrl();
                    if (TextUtils.isEmpty(url)) { // 302 头缺失等同失败:必须回落,否则这个片永远没字幕
                        LOG.i("echo-track-memory online subtitle empty url, fallback");
                        fallback.run();
                        return;
                    }
                    LOG.i("echo-track-memory online subtitle picked: " + subtitle.getName());
                    setSubtitle(url);
                }),
                () -> runOnUi(() -> {
                    if (!isSubtitleResultCurrent(memoryKey, episodeKey, decisionSeq)) return;
                    LOG.i("echo-track-memory online subtitle miss, fallback");
                    fallback.run();
                }));
    }

    /** 在途字幕结果是否仍然有效(换源 / 换集 / 用户中途自己选过字幕 ⇒ 作废) */
    private boolean isSubtitleResultCurrent(String memoryKey, String episodeKey, int decisionSeq) {
        if (!isAttached() || subtitleDecisionSeq != decisionSeq) return false;
        if (!TextUtils.equals(memoryKey, trackMemoryKey())) return false;
        return TextUtils.equals(episodeKey, scheduler.progressKey());
    }

    /** 回调线程不确定,统一回 UI 线程再动视图 */
    private void runOnUi(Runnable action) {
        Activity activity = mActivity;
        if (activity == null) return;
        activity.runOnUiThread(action);
    }

    /** 关闭字幕视图(内置 + 外挂);歌词是独立功能(独立缓存键),不跟着关 */
    private void closeSubtitleViews() {
        try {
            hideExoInternalSubtitle();
            mController.getSubtitleView().setVisibility(View.GONE);
            mController.getSubtitleView().destroy();
            mController.getSubtitleView().clearSubtitleCache();
            mController.getSubtitleView().isInternal = false;
        } catch (Exception e) {
            LOG.e("echo-close-subtitle-error:" + e.getMessage());
        }
    }

    /** 长按字幕按钮:关闭全部字幕并记住"这个片不要字幕"(换集不再自动开) */
    public void closeSubtitles() {
        if (mVideoView == null) return;
        closeSubtitleViews();
        subtitleDecisionSeq++;
        TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.SUBTITLE_OFF);
    }

    /** 本片记忆键;直播/无剧集信息时为空串 ⇒ 记忆读写全部跳过 */
    String trackMemoryKey() {
        VodInfo vod = scheduler == null ? null : scheduler.vod();
        if (vod == null) return "";
        return TrackMemory.contentKey(vod.sourceKey, vod.id);
    }

    private void rebindPlaybackOverlay() {
        initSubtitleView();
    }

    /**
     * 某集已落盘的字幕/歌词来源:内联 data: 直接可用;本地文件要确认还在(系统可能清 /zimu/ 缓存目录,否则会静默无字幕);
     * 其余情况返回空,由调用方回退到本次起播的新地址。
     */
    private String cachedPlayPath(String cacheKey) {
        if (TextUtils.isEmpty(cacheKey)) return "";
        Object cached = CacheManager.getCache(MD5.string2MD5(cacheKey));
        if (!(cached instanceof String)) return "";
        String path = (String) cached;
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("data:")) return path;
        return new File(path).exists() ? path : "";
    }

            void clearLyricView() {
        if (mController == null || mController.getLyricView() == null) return;
        mController.getLyricView().setVisibility(View.GONE);
        mController.getLyricView().destroy();
        mController.getLyricView().setText("");
    }

    void releasePlayerKernel() {
        if (engine != null) {
            engine.releasePlayer();
        } else if (mVideoView != null) {
            mVideoView.release();
        }
    }

    boolean reviveEngineIfReleased() {
        if (engine != null && !engine.isReleased()) return false;
        if (mActivity == null || surfaceSlot == null) return false;
        if (scheduler != null) scheduler.stopPlaybackForPageExit();
        engine = PlaybackService.engine(mActivity);
        scheduler = engine.controller();
        mVideoView = engine.player();
        engine.attach(this);
        handedOver = false;
        if (mVideoView != null) {
            mVideoView.setVideoController((BaseVideoController) mController);
        }
        LOG.i("echo-p2 revive engine after release");
        return true;
    }

    @Override
    public void play(boolean reset) {
        reviveEngineIfReleased();
        scheduler.play(reset);
    }

    @Override
    public boolean selectQuality(int position) {
        boolean accepted = scheduler != null && scheduler.selectQuality(position);
        if (accepted && qualitySelectedListener != null) qualitySelectedListener.onQualitySelected(position);
        return accepted;
    }
                @Override
    public void setData(PlaybackSession session) {
        if (engine == null || engine.isReleased()) {
            if (!reviveEngineIfReleased()) {
                LOG.i("echo-p5 setData skipped: engine released");
                return;
            }
        }
        if (isSamePlaybackOwned(session)) {
            LOG.i("echo-p3 take over same playback: " + session.playbackKey());
            engine.setData(session);
            syncSessionVod();
            mController.setPlayerConfig(scheduler.playerCfg());
            scheduler.markContentStarted();
            scheduler.publishTitle();
            scheduler.clearTriedLines();
            scheduler.setUserPickedLine(session.userPickedLine());
            rebindPlaybackOverlay();
            ownedPlaybackKey = session.playbackKey();
            if (alignInstanceConfigOnTakeover()) return;
            if (mVideoView != null && !mVideoView.isPlaying()) mVideoView.start();
            return;
        }
        // 同片同线路换集(选集面板点集走的就是这条):内核可复用,省一次重建;换片/换线路仍走重建
        boolean sameVodSwitch = isSameVodEpisodeSwitch(session);
        engine.setData(session);
        syncSessionVod();
        mController.setPlayerConfig(scheduler.playerCfg());
        scheduler.clearTriedLines();
        scheduler.setUserPickedLine(session.userPickedLine());
        ownedPlaybackKey = session.playbackKey();
        if (sameVodSwitch) scheduler.setReusePlayerOnSwitch(true);
        playViaScheduler(false);
    }

    /** 引擎里已起播的是不是同一部片的同一线路(只是换集) —— 归属键前两段(源|片id)相同、线路相同即可 */
    private boolean isSameVodEpisodeSwitch(PlaybackSession session) {
        if (scheduler == null || mVideoView == null || mVideoView.getMediaPlayer() == null) return false;
        String started = scheduler.startedPlaybackKey();
        if (TextUtils.isEmpty(started)) return false;
        String key = session.playbackKey();
        int cut = key.lastIndexOf('|');
        return cut > 0 && started.startsWith(key.substring(0, cut + 1));
    }

    void playViaScheduler(boolean reset) {
        reviveEngineIfReleased();
        scheduler.play(reset);
    }

    void replayCurrentAddress() {
        String url = scheduler.webPlayUrl();
        if (url != null && !url.isEmpty()) {
            scheduler.stopParse();
            scheduler.initParseLoadFound();
            // 重播/切播放器/切解码共走本方法:总闸下重播不必重建内核;切外部播放器不在此处(内核交不出去,由 pl≥10 分支先释放)
            if (!scheduler.isCrossContentReuseAllowed()) releasePlayerKernel();
            scheduler.goPlayUrl(url, scheduler.webHeaderMap());
        } else {
            playViaScheduler(false);
        }
    }

    /**
     * D6 同片接管时对齐实例级配置:缩放直接下发;渲染方式与解码方式都必须重建内核才生效
     * (复用内核不重建渲染视图,media3 也不给复用内核重选解码器),此处改走既有"重播"链路
     * 并返回 true,调用方不要再 resume。
     */
    private boolean alignInstanceConfigOnTakeover() {
        if (mVideoView == null || scheduler == null) return false;
        JSONObject cfg = scheduler.playerCfg();
        if (cfg == null) return false;
        mVideoView.setScreenScaleType(cfg.optInt("sc", 0));
        // 外部播放器由 goPlayUrl 交给第三方,内核重建/重播不由这里发起(与 trySoftDecodeFallback 同一判据)
        if (cfg.optInt("pl", 2) >= 10) return false;
        // 纯音频会话最终总会热切 Texture(见 ensureAudioOnlyRender),按用户设置重建只会白断一次声音
        boolean renderChanged = !scheduler.isConfirmedAudioOnly()
                && mVideoView.needsRenderRebuild(cfg.optInt("pr", 1));
        boolean decodeChanged = !PlayerHelper.isExoDecodeApplied(cfg);
        if (!renderChanged && !decodeChanged) return false;
        LOG.i(renderChanged ? "echo-render-changed: rebuild kernel on takeover"
                : "echo-exo-decode-changed: rebuild kernel on takeover");
        // 重建后按配置值重新起播一次:重试阶梯(含自动软解额度)随之复位,起播失败时仍能自动回退
        scheduler.beginNewPlay();
        controlListener.replay(false);
        return true;
    }

    public boolean hasClaimedPlayback() {
        return !TextUtils.isEmpty(ownedPlaybackKey);
    }

    public boolean ownsEngineContent() {
        if (!hasClaimedPlayback()) return false;
        return TextUtils.equals(ownedPlaybackKey,
                scheduler == null ? null : scheduler.startedPlaybackKey());
    }

    private boolean isSamePlaybackOwned(PlaybackSession session) {
        // 无痕:停着的那份是旧痕迹,不接管(重进从片头起播);正在播的(音频在后台)是活状态,照常接管不打断
        if (HistoryHelper.isIncognito() && (mVideoView == null || !mVideoView.isPlaying())) return false;
        if (!TextUtils.equals(scheduler.startedPlaybackKey(), session.playbackKey())) return false;
        if (engine.isLiveMode()) return false;
        if (mVideoView == null || mVideoView.getMediaPlayer() == null) return false;
        int state = mVideoView.getCurrentPlayState();
        return state != VideoView.STATE_ERROR && state != VideoView.STATE_IDLE;
    }

    public boolean onBackPressed() {
        return mController.onBackPressed();
    }

    public boolean isPortraitVideo() {
        return mVideoView != null && mVideoView.isPortraitVideo();
    }

    public void setExitingPreview(boolean exitingPreview) {
        this.exitingPreview = exitingPreview;
    }

        public void resumeFromMediaSession() {
        if (mVideoView != null) {
            mVideoView.start();
            scheduler.updateMusicSession();
        }
    }

    public void pauseFromMediaSession() {
        if (mVideoView != null) {
            mVideoView.pause();
            scheduler.updateMusicSession();
        }
    }

    public void stopFromMediaSession() {
        if (mVideoView != null) mVideoView.pause();
        scheduler.stopMusicSession();
    }

    public void seekFromMediaSession(long position) {
        if (mVideoView != null) {
            mVideoView.seekTo(position);
            scheduler.updateMusicSession();
        }
    }

                
    public void playNext(boolean isProgress) {
        scheduler.clearTriedLines();
        boolean hasNext;
        if (scheduler.vod() == null || scheduler.vod().seriesMap.get(scheduler.vod().playFlag) == null) {
            hasNext = false;
        } else {
            hasNext = scheduler.vod().playIndex + 1 < scheduler.vod().seriesMap.get(scheduler.vod().playFlag).size();
        }
        if (!hasNext) {
            Toast.makeText(mActivity, mActivity.getString(R.string.player_last_episode), Toast.LENGTH_SHORT).show();
            return;
        }else {
            scheduler.vod().playIndex++;
        }
        scheduler.setReusePlayerOnSwitch(true);
        playViaScheduler(false);
    }

    public void playPrevious() {
        scheduler.clearTriedLines();
        boolean hasPre = true;
        if (scheduler.vod() == null || scheduler.vod().seriesMap.get(scheduler.vod().playFlag) == null) {
            hasPre = false;
        } else {
            hasPre = scheduler.vod().playIndex - 1 >= 0;
        }
        if (!hasPre) {
            Toast.makeText(mActivity, mActivity.getString(R.string.player_first_episode), Toast.LENGTH_SHORT).show();
            return;
        }
        scheduler.vod().playIndex--;
        scheduler.setReusePlayerOnSwitch(true);
        playViaScheduler(false);
    }

    public void setPlayTitle(boolean show) {
        if (!show) {
            mController.setTitle("");
            return;
        }
        VodInfo vod = scheduler.vod();
        VodInfo.VodSeries vs = vod == null ? null : scheduler.currentSeries(vod.playFlag, vod.playIndex);
        mController.setTitle(vod == null ? "" : (vs == null ? vod.name : vod.name + " " + vs.name));
    }

        PreloadCoordinator.Snapshot buildPreloadSnapshot() {
        try {
            if (scheduler.vod() == null || scheduler.vod().seriesMap == null) return null;
            List<VodInfo.VodSeries> episodes = scheduler.vod().seriesMap.get(scheduler.vod().playFlag);
            if (episodes == null || scheduler.vod().playIndex < 0 || scheduler.vod().playIndex + 1 >= episodes.size()) return null;
            VodInfo.VodSeries next = episodes.get(scheduler.vod().playIndex + 1);
            if (next == null || TextUtils.isEmpty(next.url)) return null;
            int nextIndex = scheduler.vod().playIndex + 1;
            String nextKey = scheduler.vod().sourceKey + scheduler.vod().id + scheduler.vod().playFlag + nextIndex + next.name;
            String nextSubtKey = scheduler.vod().sourceKey + "-" + scheduler.vod().id + "-" + scheduler.vod().playFlag + "-" + nextIndex + "-" + next.name + "-subt";
            long startSkipMs = scheduler.playerCfg() == null ? 0 : scheduler.playerCfg().optInt("st", 0) * 1000L;
            AbstractPlayer mediaPlayer = mVideoView == null ? null : mVideoView.getMediaPlayer();
            boolean exoKernel = mediaPlayer instanceof ExoPlayer;
            return new PreloadCoordinator.Snapshot(mContext, scheduler.sourceKey(), scheduler.vod().playFlag, scheduler.progressKey(), nextKey, next.url, nextSubtKey, startSkipMs, exoKernel);
        } catch (Throwable th) {
            LOG.i("echo-preload-skip: snapshot error " + th);
            return null;
        }
    }
    @Override
    public void setAutoSwitchLineEnabled(boolean enabled) {
        scheduler.setAutoSwitchLineEnabled(enabled);
    }

public void setPreviewMode(boolean previewMode) {
this.previewMode = previewMode;
if (mController != null) {
mController.setPreviewMode(previewMode);
mController.getLyricView().setTextSize(previewMode ? 16 : 24);
applySubtitleTextSize();
}
}

/** 字幕字号 = 设置值 × 当前形态(预览 0.6×/全屏 1×);统一走 setTextSize(float)=sp —— SimpleSubtitleView 只重写了 float 重载(描边层 backGroundText 随之同步),int 实参会被加宽到 float,同样落到该重载 */
private void applySubtitleTextSize() {
if (mController == null || mController.getSubtitleView() == null) return;
int size = SubtitleHelper.getTextSize(mActivity);
mController.getSubtitleView().setTextSize(previewMode ? size * 0.6f : (float) size);
}

public void toggleControllerControls() {
if (mController != null) {
mController.toggleControlBar();
}
}

    public void stopForSourceSwitch(String tip) {
        if (mVideoView == null) return;
        scheduler.cancelPlayTimeout();
        scheduler.stopParse();
        scheduler.markStoppedForSourceSwitch();
        scheduler.stopMusicSessionForFailedPlayback();
        
        long position = mVideoView.getCurrentPosition();
        scheduler.setPendingInherit(scheduler.progressKey(), position);
        mVideoView.pause();
        if (scheduler.isCrossContentReuseAllowed()) {
            // 总闸下换源也算换线:内核留给新源复用(释放与判定共用同一许可);进度改由此处显式落盘,原先靠 release 内部兜底
            mVideoView.saveCurrentProgress();
            LOG.i("echo-switchSource keep player kernel for reuse");
        } else {
            releasePlayerKernel();
        }
        if (mController != null) mController.stopOther();
        scheduler.setWebPlayUrl(null);
        scheduler.setWebHeaderMap(null);
        scheduler.initParseLoadFound();
        LOG.i("echo-switchSource stop at " + position + "ms, key=" + scheduler.progressKey());
        if (!TextUtils.isEmpty(tip)) setTip(tip, true, false);
    }

    public void clearSourceSwitchTip() {
        if (!scheduler.isSwitchStopPending()) return;
        hideTipOnUiThread();
    }

    /** 同页换片:停掉当前内容并立即落盘,免得新片加载期间旧片声画残留;不在播本页内容时不动(别误停音乐页/直播) */
    public void stopForContentSwitch() {
        if (mVideoView == null || !ownsEngineContent()) return;
        // 在途的解析/取流/超时属上一部:新片会话边界虽也会清,但新片详情回来之前它们足以把旧片再拉起来
        scheduler.cancelInFlight();
        mVideoView.pause();
        mVideoView.saveCurrentProgress();
        // pause 对取流中的起播无效(PAUSED 时本调用自会 return):不打断的话这一集会在新片加载期间自己响起来
        mVideoView.stopPlaybackKeepPlayer();
    }
                public MyVideoView getPlayer() {
        return mVideoView;
    }

    class MyWebView extends WebView {
        public MyWebView(@NonNull Context context) {
            super(context);
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (mContext instanceof Activity)
                AutoSize.autoConvertDensityOfCustomAdapt((Activity) mContext, PlayContainer.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }
}
