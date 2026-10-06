package com.github.tvbox.osc.player;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.player.effect.PictureEffects;
import com.github.tvbox.osc.player.effect.RedrawPolicy;
import com.github.tvbox.osc.player.effect.ReplayableCacheVideoRenderer;
import com.github.tvbox.osc.util.TrackMemory;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LanguageManager;
import com.github.tvbox.osc.util.PlayerHelper;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Size;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;

import java.util.ArrayList;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;

public class ExoPlayer extends ExoMediaPlayer {

    /** 资源文案:Application 的 base 只在进程启动时挂一次,切语言后直接用 app.getString 会停在旧语言 */
    private static String str(int resId, Object... args) {
        App app = App.getInstance();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId, args);
    }

    private volatile long internalSubtitleDelayUs;
    private OnCuesListener onCuesListener;
    private boolean defaultSubtitleTrackSelected;
    private boolean defaultSubtitleTrackSelectionClosed;
    /** 渲染器工厂创建的视频渲染器(用于关闭帧率匹配,见 disableFrameRateMatching) */
    private final ArrayList<Renderer> capturedVideoRenderers = new ArrayList<>();
    /** 效果管线是否已开通:开通后视频帧走 VideoSink,内核不再上报视频尺寸,须自行补报(见 reportVideoSizeFromTracks) */
    private volatile boolean videoEffectsOpen;
    /** 本实例是否下发隧道模式(与效果管线互斥,见 prepareAsync) */
    private boolean tunnelingEnabled;
    /** 本集选中的视频轨是 HDR:效果链退化为纯拷贝(见 VideoAdjustShaderProgram),面板据此给原因 */
    private volatile boolean pictureHdrSource;
    /** media3 的 Player 有线程校验,效果与重绘信令一律落主线程 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 上一次推给内核的输出几何:只有变了才需要补重绘 */
    private int lastOutputWidth;
    private int lastOutputHeight;
    /** 重绘已排队:同一帧内的连发合并成一次 */
    private boolean redrawScheduled;

    /** 本片记忆键(见 TrackMemory);内核重建即新实例,故由 MyVideoView 在起播前推入 */
    private String contentKey = "";

    public void setContentKey(String key) {
        this.contentKey = key == null ? "" : key;
    }

    private static volatile boolean preferSoftwareDecode = false;

    /** 下发 EXO 解码方式:true = 软解(系统软件解码器优先) */
    public static void setPreferSoftwareDecode(boolean prefer) {
        preferSoftwareDecode = prefer;
    }

    /** 当前已下发的 EXO 解码方式(供 PlayerHelper 判断"这次起的解码方式变了没") */
    public static boolean isPreferSoftwareDecode() {
        return preferSoftwareDecode;
    }

    private static final MediaCodecSelector EXO_VIDEO_CODEC_SELECTOR =
            (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
                List<MediaCodecInfo> infos =
                        (preferSoftwareDecode ? MediaCodecSelector.PREFER_SOFTWARE : MediaCodecSelector.DEFAULT)
                                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                LOG.i("echo-exo-selector: mime=" + mimeType + " preferSoft=" + preferSoftwareDecode
                        + " count=" + infos.size() + " first=" + (infos.isEmpty() ? "none" : infos.get(0).name));
                return infos;
            };

    public static final int ERROR_KIND_UNKNOWN = 0;
    public static final int ERROR_KIND_NETWORK = 1;
    public static final int ERROR_KIND_DECODE = 2;

    private volatile int lastErrorKind = ERROR_KIND_UNKNOWN;

    public int lastErrorKind() {
        return lastErrorKind;
    }

    private volatile long droppedFramesTotal;
    private volatile int rebufferCountTotal;
    private volatile boolean playbackStarted;

    public long droppedFrames() {
        return droppedFramesTotal;
    }

    public int rebufferCount() {
        return rebufferCountTotal;
    }

    public boolean isTunnelingEnabled() {
        return tunnelingEnabled;
    }

    public String videoDecoderName() {
        return PlayerCodecStats.videoDecoderName;
    }

    private final java.util.concurrent.atomic.AtomicLong renderedFrameCount = new java.util.concurrent.atomic.AtomicLong();
    private volatile long frameRateWindowStartMs;
    private volatile float measuredFrameRate;
    private volatile boolean frameRateTracking;
    private final androidx.media3.exoplayer.video.VideoFrameMetadataListener videoFrameListener =
            (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> renderedFrameCount.incrementAndGet();

    public float measuredFrameRate() {
        return measuredFrameRate;
    }

    public void sampleFrameRate() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (frameRateWindowStartMs == 0) {
            frameRateWindowStartMs = now;
            renderedFrameCount.set(0);
            return;
        }
        long elapsed = now - frameRateWindowStartMs;
        if (elapsed < 1000) return;
        measuredFrameRate = renderedFrameCount.getAndSet(0) * 1000f / elapsed;
        frameRateWindowStartMs = now;
    }

    public void setFrameRateTracking(boolean enabled) {
        frameRateTracking = enabled;
        frameRateWindowStartMs = 0;
        renderedFrameCount.set(0);
        if (enabled) measuredFrameRate = 0f;
        applyFrameRateTracking();
    }

    private void applyFrameRateTracking() {
        if (mInternalPlayer == null) return;
        if (frameRateTracking) {
            mInternalPlayer.setVideoFrameMetadataListener(videoFrameListener);
        } else {
            mInternalPlayer.clearVideoFrameMetadataListener(videoFrameListener);
        }
    }

    public Format getSelectedVideoFormat() {
        return selectedFormat(C.TRACK_TYPE_VIDEO);
    }

    public Format getSelectedAudioFormat() {
        return selectedFormat(C.TRACK_TYPE_AUDIO);
    }

    private Format selectedFormat(int trackType) {
        if (mInternalPlayer == null) return null;
        Tracks tracks = mInternalPlayer.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != trackType || !group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i);
            }
        }
        return null;
    }

    private static int classifyError(String codeName) {
        if (codeName == null) return ERROR_KIND_UNKNOWN;
        if (codeName.startsWith("ERROR_CODE_IO") || codeName.startsWith("ERROR_CODE_PARSING")) return ERROR_KIND_NETWORK;
        if (codeName.startsWith("ERROR_CODE_DECOD")) return ERROR_KIND_DECODE;
        return ERROR_KIND_UNKNOWN;
    }

    public ExoPlayer(Context context) {
        super(context);
        int bufferTimes = KV.get(HawkConfig.BUFFER_TIMES, HawkConfig.BUFFER_TIMES_DEFAULT);
        bufferTimes = Math.max(1, Math.min(10, bufferTimes));
        setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * bufferTimes,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * bufferTimes,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .build());
        setRenderersFactory(buildRenderersFactory(context));
        LOG.i("echo-exo-low-memory-load-control");
    }

    @Override
    public void initPlayer() {
        if (PreloadManagerHolder.enabled()) {
            setPlaybackLooper(PreloadManagerHolder.preloadLooper());
        }
        super.initPlayer();
        applyPlaybackParameters();
        disableFrameRateMatching();
        mInternalPlayer.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                loadDefaultSubtitleTrackBeforeReady();
                reportVideoSizeFromTracks(tracks);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    defaultSubtitleTrackSelectionClosed = true;
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (videoEffectsOpen && videoSize.width > 0 && videoSize.height > 0) {
                    videoEffectsOpen = false;
                    LOG.i("echo-picture-effects inactive: kernel reported video size");
                }
            }

            @Override
            public void onCues(CueGroup cueGroup) {
                OnCuesListener listener = onCuesListener;
                if (listener != null) {
                    listener.onCues(cueGroup.cues);
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                String codeName = error.getErrorCodeName();
                lastErrorKind = classifyError(codeName);
                // 播放错误详情(错误码 + cause 链,排查播放失败用)
                StringBuilder sb = new StringBuilder("echo-exo-player-error: code=")
                        .append(codeName)
                        .append(", msg=").append(error.getMessage());
                Throwable cause = error.getCause();
                for (int i = 0; cause != null && i < 5; i++) {
                    sb.append(" | cause[").append(i).append("]=")
                            .append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
                    cause = cause.getCause();
                }
                LOG.i(sb.toString());
            }
        });
        mInternalPlayer.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
                droppedFramesTotal += droppedFrames;
            }

            @Override
            public void onPlaybackStateChanged(EventTime eventTime, int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    playbackStarted = true;
                } else if (playbackState == Player.STATE_BUFFERING && playbackStarted) {
                    rebufferCountTotal++;
                }
            }
        });
        applyFrameRateTracking();
        LOG.i("echo-exo-cues-listener-ready");
    }

    /** 起播前开通画质效果管线:media3 只在渲染器首次 enable(≈ 本次 prepare)时按当时的效果列表建 VideoSink,晚于 prepare 下发则本集不生效 */
    @Override
    public void prepareAsync() {
        PictureEffects.INSTANCE.onPrepare(this, tunnelingEnabled);
        super.prepareAsync();
    }

    private void applyPlaybackParameters() {
        if (mInternalPlayer == null || trackSelector == null) return;
        boolean tunnel = KV.get(HawkConfig.PLAY_TUNNEL, false);
        boolean preferAac = KV.get(HawkConfig.PLAY_PREFER_AAC, false);
        boolean surfaceRender = KV.get(HawkConfig.PLAY_RENDER, 1) == 1;
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        tunnelingEnabled = tunnel && surfaceRender;
        builder.setTunnelingEnabled(tunnelingEnabled);
        if (preferAac) {
            builder.setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC);
        }
        trackSelector.setParameters(builder.build());
        LOG.i("echo-exo-tunnel-prefs: tunnel=" + tunnel + ", surfaceRender=" + surfaceRender + ", preferAac=" + preferAac);
    }

    /**
     * 清掉上一段内容留下的选轨覆盖(内核复用换内容时调用,见 {@code VideoView.replay})。
     *
     * <p>选轨器随播放器常驻、reset 不清参数,而 {@link #applyTrack} 的覆盖表以轨道组为键、该键按内容比相等:
     * 不清则"在 A 片选过的轨"会串到轨道结构相同的 B 片(最刺眼:B 片记忆是关字幕却仍有字幕)。
     * 只影响默认选哪条,记忆还原({@link #restoreTracks})在 STATE_PREPARED 会按新片重放。
     */
    @Override
    public void resetTrackSelection() {
        if (trackSelector == null) return;
        trackSelector.setParameters(trackSelector.buildUponParameters().clearSelectionOverrides().build());
        LOG.i("echo-setTrack: clear stale overrides on content switch");
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        defaultSubtitleTrackSelected = false;
        defaultSubtitleTrackSelectionClosed = false;
        droppedFramesTotal = 0;
        rebufferCountTotal = 0;
        playbackStarted = false;
        PlayerCodecStats.videoDecoderName = "";
        renderedFrameCount.set(0);
        frameRateWindowStartMs = 0;
        measuredFrameRate = 0f;
        // librtmp 要求直播流地址末尾带 " live=1"(media3 的 RtmpDataSource 原样透传 URL,不会补),
        // 缺了会被当作点播流,读到流尾即结束(直播必现)
        boolean isRtmp = path != null && path.startsWith("rtmp://");
        if (isRtmp && KV.get(HawkConfig.PLAYER_IS_LIVE, false) && !path.contains("live=1")) {
            path = path + " live=1";
            LOG.i("echo-rtmp-live-flag: " + path);
        }
        super.setDataSource(path, headers);
        boolean preloadTarget = PreloadManagerHolder.isPreloadTargetUrl(path, headers);

        if (PlayerHelper.isLocalProxyUrl(path) || isRtmp) {
            if (preloadTarget) {
                LOG.i((isRtmp ? "echo-preload-skip-rtmp: " : "echo-preload-skip-local-proxy: ") + path);
            }
            preloadTarget = false;
        }
        if (preloadTarget) {
            // 预载目标必须用与预缓存写盘一致的 key(media3 默认 key=uri)
            MediaSource cached = mMediaSourceHelper.getPreloadTargetMediaSource(path, headers);
            if (cached != null) {
                mMediaSource = cached;
                LOG.i("echo-preload-disk-source: " + path);
            }
        }
    }

    /** 挂/换显示面。裸 Surface(本项目直接 setVideoSurface)不走自动路径,**必须自己补发** MSG_SET_VIDEO_OUTPUT_RESOLUTION,否则效果管线每帧被丢弃(黑屏) */
    @Override
    public void setDisplay(SurfaceHolder holder) {
        super.setDisplay(holder);
        if (holder == null) return;
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;
        Rect frame = holder.getSurfaceFrame();
        if (frame != null) notifyVideoOutputResolution(frame.width(), frame.height());
    }

    /**
     * 输出分辨率信令(纹理渲染路径没有 SurfaceHolder,由 {@link MyVideoView} 按渲染视图尺寸推同一份)。
     * 本地不去重:media3 按"同一显示面 + 同一尺寸"自己短路,而换面必须重发(换面会清掉 VideoSink 输出面信息)。
     */
    public void notifyVideoOutputResolution(int width, int height) {
        if (mInternalPlayer == null || width <= 0 || height <= 0) return;
        // 送屏画布尺寸:Anime4K 链末要按它出画(否则链内 2x 会被管线缩放器再抹一遍,放大成果到不了屏幕)
        PictureEffects.INSTANCE.setOutputCanvas(width, height);
        boolean sizeChanged = width != lastOutputWidth || height != lastOutputHeight;
        lastOutputWidth = width;
        lastOutputHeight = height;
        for (Renderer renderer : capturedVideoRenderers) {
            try {
                mInternalPlayer.createMessage(renderer)
                        .setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION)
                        .setPayload(new Size(width, height))
                        .send();
            } catch (Throwable th) {
                LOG.e("ExoPlayer", "echo-picture-output-resolution failed", th);
            }
        }
        // 暂停态换几何(退出全屏回小窗/转屏):信令更新了输出尺寸,但合成仍停在旧几何那一帧上
        if (RedrawPolicy.shouldRedrawOnGeometry(sizeChanged, isPlaying(), redrawReady())) {
            redrawVideoFrame();
        }
    }

    /** 能重绘的前提:效果链已挂 && 视频渲染器带可重放缓存 */
    private boolean redrawReady() {
        if (!videoEffectsOpen) return false;
        for (Renderer renderer : capturedVideoRenderers) {
            if (renderer instanceof ReplayableCacheVideoRenderer) return true;
        }
        return false;
    }

    /** 让内核按当前参数/几何重绘最后一帧(暂停态唯一能更新画面的手段);同一帧内多次请求合并成一次 */
    public void redrawVideoFrame() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::redrawVideoFrame);
            return;
        }
        if (redrawScheduled || !redrawReady()) return;
        redrawScheduled = true;
        mainHandler.post(() -> {
            redrawScheduled = false;
            if (mInternalPlayer == null || !videoEffectsOpen) return;
            try {
                mInternalPlayer.setVideoEffects(VideoFrameProcessor.REDRAW);
            } catch (Throwable th) {
                LOG.e("ExoPlayer", "echo-picture-redraw failed", th);
            }
        });
    }

    /** 下发画质效果(调色)列表,由 {@link PictureEffects} 调用;失败只留痕,不让调色把播放带崩 */
    public void applyVideoEffects(List<Effect> effects) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> applyVideoEffects(effects));
            return;
        }
        if (mInternalPlayer == null) return;
        try {
            mInternalPlayer.setVideoEffects(effects);
            videoEffectsOpen = true;
        } catch (Throwable th) {
            // 缺 media3-effect、非默认渲染器、DRM 等都会在这里抛:画面照常播,只是没有调色
            videoEffectsOpen = false;
            LOG.e("ExoPlayer", "echo-picture-effects apply failed", th);
        }
    }

    /** 效果链是否真的挂着(apply 失败或非默认渲染器吞掉信令时翻 false),供面板提示"当前不可调色" */
    public boolean isPictureEffectsActive() {
        return videoEffectsOpen;
    }

    /** 本集视频轨是否 HDR(效果退化为纯拷贝,面板据此提示) */
    public boolean isPictureHdrSource() {
        return pictureHdrSource;
    }

    private void reportVideoSizeFromTracks(Tracks tracks) {
        if (!videoEffectsOpen || mPlayerEventListener == null || tracks == null) return;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO || !group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) continue;
                Format format = group.getTrackFormat(i);
                if (format.width <= 0 || format.height <= 0) return;
                int width = format.width;
                int height = format.height;
                if (format.rotationDegrees == 90 || format.rotationDegrees == 270) {
                    int rotated = width;
                    width = height;
                    height = rotated;
                }
                pictureHdrSource = ColorInfo.isTransferHdr(format.colorInfo);
                LOG.i("echo-picture-size: " + width + "x" + height + " rotation=" + format.rotationDegrees
                        + " hdr=" + pictureHdrSource);
                mPlayerEventListener.onVideoSizeChanged(width, height);
                return;
            }
        }
    }

    @Override
    public void release() {
        PictureEffects.INSTANCE.onPlayerReleased(this);
        super.release();
    }

    private void disableFrameRateMatching() {
        if (mInternalPlayer == null || capturedVideoRenderers.isEmpty()) return;
        for (Renderer renderer : capturedVideoRenderers) {
            try {
                mInternalPlayer.createMessage(renderer)
                        .setType(Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY)
                        .setPayload(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                        .send();
                LOG.i("echo-frameRate matching OFF -> " + renderer.getClass().getSimpleName());
            } catch (Throwable th) {
                LOG.i("echo-frameRate matching OFF failed: " + th);
            }
        }
    }

    private RenderersFactory buildRenderersFactory(Context context) {
        boolean dynamicScheduling = KV.get(HawkConfig.EXO_VIDEO_DYNAMIC_SCHEDULING,
                HawkConfig.EXO_VIDEO_DYNAMIC_SCHEDULING_DEFAULT);
        DefaultRenderersFactory factory = new SubtitleOffsetRenderersFactory(context, new SubtitleDelayProvider() {
            @Override
            public long getDelayUs() {
                return internalSubtitleDelayUs;
            }
        }, capturedVideoRenderers, dynamicScheduling)
                .setEnableDecoderFallback(true)
                // 音频硬解优先:MediaCodec 不支持的格式(AC3/DTS 类)才落到 ffmpeg 软解兜底
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        // 动态调度开关由自建渲染器带回,见 replaceWithReplayableRenderer
        factory.forceDisableMediaCodecAsynchronousQueueing();
        LOG.i("echo-exo-disable-async-codec-queue");
        LOG.i("echo-exo-video-dynamic-scheduling: " + dynamicScheduling);
        return factory;
    }

    public TrackInfo getTrackInfo() {
        TrackInfo data = new TrackInfo();
        MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedInfo == null) return data;
        logRendererListOnce(mappedInfo);

        for (int rendererIndex = 0; rendererIndex < mappedInfo.getRendererCount(); rendererIndex++) {
            int type = mappedInfo.getRendererType(rendererIndex);
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_TEXT) continue;

            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup group = groups.get(groupIndex);
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format fmt = group.getFormat(trackIndex);
                    if (type == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(fmt)) continue;
                    String language = getLanguage(fmt);
                    String detail = type == C.TRACK_TYPE_VIDEO ? getVideoName(fmt) : getName(fmt);
                    TrackInfoBean bean = new TrackInfoBean();
                    bean.language = language;
                    bean.name = buildDisplayName(type == C.TRACK_TYPE_AUDIO ? str(R.string.player_menu_audio_track) : type == C.TRACK_TYPE_VIDEO ? str(R.string.player_menu_video_track) : str(R.string.player_menu_subtitle),
                            type == C.TRACK_TYPE_AUDIO ? data.getAudio().size() + 1 : type == C.TRACK_TYPE_VIDEO ? data.getVideo().size() + 1 : data.getSubtitle().size() + 1,
                            language, detail);
                    bean.renderId = rendererIndex;
                    bean.trackGroupId = groupIndex;
                    bean.trackId = trackIndex;
                    bean.groupIndex = groupIndex;
                    bean.index = trackIndex;
                    bean.selected = isCurrentTrackSelected(fmt, type);
                    bean.bitmapSubtitle = type == C.TRACK_TYPE_TEXT && isBitmapSubtitle(fmt);
                    bean.type = type;
                    bean.formatKey = formatKey(fmt, type);

                    if (type == C.TRACK_TYPE_AUDIO) {
                        data.addAudio(bean);
                    } else if (type == C.TRACK_TYPE_VIDEO) {
                        data.addVideo(bean);
                    } else {
                        data.addSubtitle(bean);
                    }
                }
            }
        }
        return data;
    }

    /** 渲染器清单只落一次盘:getTrackInfo 在播放状态回调里被高频调用 */
    private boolean rendererListLogged;

    private void logRendererListOnce(MappingTrackSelector.MappedTrackInfo mappedInfo) {
        if (rendererListLogged) return;
        rendererListLogged = true;
        StringBuilder sb = new StringBuilder("echo-setTrack renderers:");
        for (int i = 0; i < mappedInfo.getRendererCount(); i++) {
            sb.append(" [").append(i).append("]type=").append(mappedInfo.getRendererType(i))
                    .append('/').append(mappedInfo.getRendererName(i));
        }
        LOG.i(sb.toString());
    }

    /** 用户显式选轨:改当前选择并记住**指纹**(下标换集即失效,存了必然选错轨) */
    public void setTrack(TrackInfoBean track) {
        if (track == null) return;
        if (!applyTrack(track.renderId, track.trackGroupId, track.trackId)) return;
        if (track.type == C.TRACK_TYPE_TEXT) {
            // 选内置字幕即重新决定字幕来源,覆盖 #off / #local / #online
            TrackMemory.saveSubtitle(contentKey, track.formatKey);
        } else {
            TrackMemory.saveTrack(contentKey, track.type, track.formatKey);
        }
    }

    /** 程序性选轨(默认字幕等自动逻辑),不写记忆 */
    public void selectTrack(TrackInfoBean track) {
        if (track == null) return;
        applyTrack(track.renderId, track.trackGroupId, track.trackId);
    }

    /** 下发选择(无记忆写入);返回是否真的下发 */
    private boolean applyTrack(int rendererIndex, int groupIndex, int trackIndex) {
        try {
            MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
            if (mappedInfo == null) {
                LOG.i("echo-setTrack: MappedTrackInfo is null");
                return false;
            }
            if (rendererIndex == C.INDEX_UNSET || rendererIndex < 0 || rendererIndex >= mappedInfo.getRendererCount()) {
                LOG.i("echo-setTrack: No renderer found");
                return false;
            }

            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            if (!isTrackIndexValid(groups, groupIndex, trackIndex)) {
                LOG.i("echo-setTrack: Invalid track index - group:" + groupIndex + ", track:" + trackIndex);
                return false;
            }
            DefaultTrackSelector.SelectionOverride override =
                    new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex);
            DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
            builder.setRendererDisabled(rendererIndex, false);
            builder.clearSelectionOverrides(rendererIndex);
            // 同一 track type 只允许一路渲染器持有选择:media3 只取第一个同类 definition、不清其余,
            // 两路音频渲染器同时 enable 即抛 "Multiple renderer media clocks enabled."(清掉即自动 disable)。
            int targetType = mappedInfo.getRendererType(rendererIndex);
            for (int i = 0; i < mappedInfo.getRendererCount(); i++) {
                if (i != rendererIndex && mappedInfo.getRendererType(i) == targetType) {
                    builder.clearSelectionOverrides(i);
                }
            }
            builder.setSelectionOverride(rendererIndex, groups, override);
            trackSelector.setParameters(builder.build());
            // 诊断:记录真正下发的选择(渲染器/组/轨/格式);本机 ROM 吞 logcat,只信 App 文件日志
            Format applied = groups.get(groupIndex).getFormat(trackIndex);
            LOG.i("echo-setTrack applied: renderer=" + rendererIndex + " group=" + groupIndex + " track=" + trackIndex
                    + " type=" + targetType
                    + " mime=" + (applied == null ? "null" : applied.sampleMimeType)
                    + " channels=" + (applied == null ? -1 : applied.channelCount)
                    + " codecs=" + (applied == null ? "null" : applied.codecs)
                    + " trackKey=" + contentKey);
            return true;
        } catch (Exception e) {
            LOG.i("echo-setTrack error: " + e.getMessage());
            return false;
        }
    }

    /** 按记忆还原音轨/视轨/内置字幕;无记忆/定位不到的类型保持播放器默认(内置字幕则退"国语→第一条") */
    public void restoreTracks() {
        restoreByMemory(C.TRACK_TYPE_AUDIO);
        restoreByMemory(C.TRACK_TYPE_VIDEO);
        restoreSubtitleByMemory();
    }

    private void restoreByMemory(int trackType) {
        String remembered = TrackMemory.loadTrack(contentKey, trackType);
        if (remembered == null) return;
        int[] position = locate(trackType, remembered);
        if (position == null) {
            LOG.i("echo-track-memory miss type=" + trackType + " key=" + contentKey + " fp=" + remembered);
            return;
        }
        if (applyTrack(position[0], position[1], position[2])) {
            LOG.i("echo-track-memory restore type=" + trackType + " fp=" + remembered);
        }
    }

    /** 内置字幕按指纹还原;#off / #local / #online 三种来源决定由页面层落地,这里不动 */
    private void restoreSubtitleByMemory() {
        String record = TrackMemory.loadSubtitle(contentKey);
        if (record == null) return;
        if (!TrackMemory.isSubtitleTrack(record)) return;
        int[] position = locate(C.TRACK_TYPE_TEXT, record);
        if (position == null) {
            // 有决定但这一集定位不到(编码变了/有歧义):退回默认选轨,别变成"什么都没有"
            LOG.i("echo-track-memory text miss, use default: " + record);
            selectDefaultSubtitlePick();
            return;
        }
        if (applyTrack(position[0], position[1], position[2])) {
            LOG.i("echo-track-memory restore text fp=" + record);
        }
    }

    /** 在指定类型的全部渲染器/组/轨里按指纹定位;返回 {渲染器,组,轨},定位不到返回 null */
    private int[] locate(int trackType, String fingerprint) {
        MappingTrackSelector.MappedTrackInfo mappedInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedInfo == null) return null;
        List<String> keys = new ArrayList<>();
        List<int[]> positions = new ArrayList<>();
        for (int rendererIndex = 0; rendererIndex < mappedInfo.getRendererCount(); rendererIndex++) {
            if (mappedInfo.getRendererType(rendererIndex) != trackType) continue;
            TrackGroupArray groups = mappedInfo.getTrackGroups(rendererIndex);
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup group = groups.get(groupIndex);
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format format = group.getFormat(trackIndex);
                    // 与菜单口径一致:未声明语言的 CEA608/708 不进列表(菜单里看不到,就不会是"用户选过")
                    if (trackType == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(format)) continue;
                    keys.add(formatKey(format, trackType));
                    positions.add(new int[]{rendererIndex, groupIndex, trackIndex});
                }
            }
        }
        int index = TrackMemory.pick(keys, fingerprint);
        return index < 0 ? null : positions.get(index);
    }

    /** 轨道指纹:语言取菜单同款的归一化值(跨内核可比),编码优先 codecs、缺失退 mime 子类型 */
    private String formatKey(Format fmt, int trackType) {
        if (fmt == null) return "";
        String codec = firstNonEmpty(fmt.codecs, mimeSubtype(fmt));
        if (trackType == C.TRACK_TYPE_AUDIO) {
            return TrackMemory.audioFingerprint(getLanguage(fmt), codec, fmt.channelCount);
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
            return TrackMemory.videoFingerprint(codec, fmt.width, fmt.height);
        }
        return TrackMemory.textFingerprint(getLanguage(fmt), codec);
    }

    private String mimeSubtype(Format fmt) {
        if (fmt == null || fmt.sampleMimeType == null || !fmt.sampleMimeType.contains("/")) return "";
        return fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1);
    }

    private String firstNonEmpty(String first, String second) {
        return (first != null && !first.isEmpty()) ? first : (second == null ? "" : second);
    }

    public void loadDefaultSubtitleTrack() {
        if (defaultSubtitleTrackSelected) return;
        // 该片已有字幕决定(内置/外挂/关闭):默认选轨让位,由页面层按记忆落地;
        // 内置指纹定位不到时,restoreSubtitleByMemory 会自己退回默认选轨
        if (TrackMemory.loadSubtitle(contentKey) != null) {
            LOG.i("echo-track-memory subtitle decision exists, skip default");
            defaultSubtitleTrackSelected = true;
            return;
        }
        selectDefaultSubtitlePick();
    }

    /** 当前没有选中任何内置字幕轨时补一次默认选轨(外挂字幕落地失败回落、或媒体未标 DEFAULT 轨时全靠它) */
    public void ensureSubtitleTrackSelected() {
        List<TrackInfoBean> subtitles = getTrackInfo().getSubtitle();
        if (subtitles.isEmpty()) return;
        for (TrackInfoBean subtitle : subtitles) {
            if (subtitle.selected) return;
        }
        selectDefaultSubtitlePick();
    }

    /** 默认内置字幕:国语优先,否则第一条 */
    private void selectDefaultSubtitlePick() {
        List<TrackInfoBean> subtitles = getTrackInfo().getSubtitle();
        // 轨道还没映射出来时不封口:onTracksChanged 会再来一次(封了就再也选不上)
        if (subtitles.isEmpty()) return;
        defaultSubtitleTrackSelected = true;
        TrackInfoBean target = subtitles.get(0);
        for (TrackInfoBean subtitle : subtitles) {
            if ("国语".equals(subtitle.language)) { // i18n: keep(字幕语言匹配值)
                target = subtitle;
                break;
            }
        }
        selectTrack(target);
    }

    private void loadDefaultSubtitleTrackBeforeReady() {
        if (defaultSubtitleTrackSelectionClosed) return;
        loadDefaultSubtitleTrack();
    }

    public void setOnCuesListener(OnCuesListener listener) {
        onCuesListener = listener;
    }

    public void setInternalSubtitleDelay(int milliseconds) {
        internalSubtitleDelayUs = milliseconds * 1000L;
    }

    private boolean isTrackIndexValid(TrackGroupArray groups, int groupIndex, int trackIndex) {
        if (groupIndex < 0 || groupIndex >= groups.length) return false;
        TrackGroup group = groups.get(groupIndex);
        return trackIndex >= 0 && trackIndex < group.length;
    }

    private boolean isBitmapSubtitle(Format format) {
        if (format == null || format.sampleMimeType == null) return false;
        String mimeType = format.sampleMimeType.toLowerCase();
        return mimeType.contains("pgs") || mimeType.contains("dvb") || mimeType.contains("vobsub");
    }

    private boolean isUndeclaredClosedCaptionTrack(Format format) {
        if (format == null || format.accessibilityChannel != Format.NO_VALUE) return false;
        return MimeTypes.APPLICATION_CEA608.equals(format.sampleMimeType)
                || MimeTypes.APPLICATION_CEA708.equals(format.sampleMimeType);
    }

    private boolean isCurrentTrackSelected(Format format, int trackType) {
        if (mInternalPlayer == null) return false;
        Tracks tracks = mInternalPlayer.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != trackType || !group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i) && isSameFormat(format, group.getTrackFormat(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSameFormat(Format a, Format b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.id != null && b.id != null && a.id.equals(b.id)) return true;
        return a.equals(b);
    }

    private static final Map<String, String> LANG_MAP = new HashMap<>();

    static {
        LANG_MAP.put("zh", "\u56fd\u8bed");
        LANG_MAP.put("zh-cn", "\u56fd\u8bed");
        LANG_MAP.put("cmn", "\u56fd\u8bed");
        LANG_MAP.put("chi", "\u56fd\u8bed");
        LANG_MAP.put("zho", "\u56fd\u8bed");
        LANG_MAP.put("chs", "\u56fd\u8bed");
        LANG_MAP.put("yue", "\u7ca4\u8bed");
        LANG_MAP.put("zh-hk", "\u7ca4\u8bed");
        LANG_MAP.put("zh-yue", "\u7ca4\u8bed");
        LANG_MAP.put("en", "\u82f1\u8bed");
        LANG_MAP.put("en-us", "\u82f1\u8bed");
        LANG_MAP.put("eng", "\u82f1\u8bed");
        LANG_MAP.put("ja", "\u65e5\u8bed");
        LANG_MAP.put("jpn", "\u65e5\u8bed");
        LANG_MAP.put("ko", "\u97e9\u8bed");
        LANG_MAP.put("kor", "\u97e9\u8bed");
        LANG_MAP.put("th", "\u6cf0\u8bed");
        LANG_MAP.put("tha", "\u6cf0\u8bed");
    }

    private String getLanguage(Format fmt) {
        String language = matchLanguage(fmt.language);
        if (!language.isEmpty()) {
            return language;
        }
        return matchLanguage((fmt.label == null ? "" : fmt.label) + " "
                + (fmt.id == null ? "" : fmt.id) + " "
                + (fmt.codecs == null ? "" : fmt.codecs));
    }

    private String matchLanguage(String text) {
        if (text == null) return "";
        String value = text.toLowerCase();
        String mapped = LANG_MAP.get(value);
        if (mapped != null) return mapped;
        if (value.contains("yue") || value.contains("cantonese") || value.contains("\u7ca4") || value.contains("\u5e7f\u4e1c")) {
            return "\u7ca4\u8bed";
        }
        if (value.contains("zh") || value.contains("chi") || value.contains("zho") || value.contains("chs")
                || value.contains("cht") || value.contains("cmn") || value.contains("\u4e2d")
                || value.contains("\u56fd\u8bed") || value.contains("\u666e\u901a\u8bdd")) {
            return "\u56fd\u8bed";
        }
        if (value.contains("en") || value.contains("eng") || value.contains("english") || value.contains("\u82f1")) {
            return "\u82f1\u8bed";
        }
        if (value.contains("ja") || value.contains("jpn") || value.contains("japanese") || value.contains("\u65e5")) {
            return "\u65e5\u8bed";
        }
        if (value.contains("ko") || value.contains("kor") || value.contains("korean") || value.contains("\u97e9")) {
            return "\u97e9\u8bed";
        }
        if (value.contains("tha") || value.contains("thai") || value.contains("th")) {
            return "\u6cf0\u8bed";
        }
        return "";
    }

    private String getName(Format fmt) {
        String channelLabel;
        if (fmt.channelCount <= 0) {
            channelLabel = "";
        } else if (fmt.channelCount == 1) {
            channelLabel = str(R.string.player_channel_mono);
        } else if (fmt.channelCount == 2) {
            channelLabel = str(R.string.player_channel_stereo);
        } else {
            channelLabel = str(R.string.player_channel_count, fmt.channelCount);
        }

        String codec = "";
        if (fmt.codecs != null && !fmt.codecs.isEmpty()) {
            codec = fmt.codecs.toUpperCase();
        }
        if (fmt.sampleMimeType != null && fmt.sampleMimeType.contains("/")) {
            String mime = fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1);
            if (codec.isEmpty()) {
                codec = mime.toUpperCase();
            }
        }
        StringBuilder builder = new StringBuilder();
        appendPart(builder, fmt.label);
        appendPart(builder, codec);
        appendPart(builder, channelLabel);
        return builder.toString();
    }

    private String getVideoName(Format fmt) {
        StringBuilder builder = new StringBuilder();
        appendPart(builder, fmt.label);
        if (fmt.width > 0 && fmt.height > 0) {
            appendPart(builder, fmt.width + "x" + fmt.height);
        }
        if (fmt.codecs != null && !fmt.codecs.isEmpty()) {
            appendPart(builder, fmt.codecs.toUpperCase());
        } else if (fmt.sampleMimeType != null && fmt.sampleMimeType.contains("/")) {
            appendPart(builder, fmt.sampleMimeType.substring(fmt.sampleMimeType.indexOf('/') + 1).toUpperCase());
        }
        return builder.toString();
    }

    private String buildDisplayName(String prefix, int number, String language, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(number);
        if (language != null && !language.isEmpty()) {
            builder.append(" - ").append(language);
        }
        if (detail != null && !detail.isEmpty()) {
            builder.append(" ").append(detail);
        }
        return builder.toString();
    }

    private void appendPart(StringBuilder builder, String value) {
        if (value == null) return;
        String part = value.trim();
        if (part.isEmpty() || "und".equalsIgnoreCase(part) || "\u672a\u77e5".equals(part)) return;
        if (builder.length() > 0) {
            builder.append(" / ");
        }
        builder.append(part);
    }

    public interface OnCuesListener {
        void onCues(List<Cue> cues);
    }

    private interface SubtitleDelayProvider {
        long getDelayUs();
    }

    private static final class SubtitleOffsetRenderersFactory extends DefaultRenderersFactory {
        private final SubtitleDelayProvider subtitleDelayProvider;
        /** 收集本工厂创建的视频渲染器(帧率匹配策略需要按渲染器实例下发消息) */
        private final List<Renderer> videoRendererSink;
        private final boolean enableDurationToProgressUs;

        SubtitleOffsetRenderersFactory(Context context, SubtitleDelayProvider subtitleDelayProvider,
                                       List<Renderer> videoRendererSink, boolean enableDurationToProgressUs) {
            super(context);
            this.subtitleDelayProvider = subtitleDelayProvider;
            this.videoRendererSink = videoRendererSink;
            this.enableDurationToProgressUs = enableDurationToProgressUs;
        }

        @Override
        protected void buildVideoRenderers(Context context, int extensionRendererMode,
                                           MediaCodecSelector mediaCodecSelector,
                                           boolean enableDecoderFallback, android.os.Handler eventHandler,
                                           androidx.media3.exoplayer.video.VideoRendererEventListener eventListener,
                                           long allowedJoiningTimeMs, ArrayList<Renderer> out) {
            int firstRendererIndex = out.size();
            
            super.buildVideoRenderers(context, extensionRendererMode, EXO_VIDEO_CODEC_SELECTOR, enableDecoderFallback,
                    eventHandler, eventListener, allowedJoiningTimeMs, out);
            replaceWithReplayableRenderer(context, EXO_VIDEO_CODEC_SELECTOR, enableDecoderFallback, eventHandler,
                    eventListener, allowedJoiningTimeMs, firstRendererIndex, out);
            if (videoRendererSink != null) {
                for (int i = firstRendererIndex; i < out.size(); i++) {
                    videoRendererSink.add(out.get(i));
                }
            }
        }

        /**
         * 把 super 建的默认 MediaCodecVideoRenderer 换成带可重放帧缓存的子类。构建设置须与上游 1.11.1 的
         * {@code DefaultRenderersFactory.createMediaCodecVideoRenderer} 逐项对齐(升级 media3 时回来对账);
         * 唯一例外:selector 必须下发 {@link ExoPlayer#EXO_VIDEO_CODEC_SELECTOR}(软解偏好靠它,上游形参恒为 DEFAULT)。
         * 被换下的实例未 init/enable、不持编解码器与显示面,丢弃安全。
         */
        private void replaceWithReplayableRenderer(Context context, MediaCodecSelector mediaCodecSelector,
                                                  boolean enableDecoderFallback, android.os.Handler eventHandler,
                                                  androidx.media3.exoplayer.video.VideoRendererEventListener eventListener,
                                                  long allowedJoiningTimeMs, int firstRendererIndex,
                                                  ArrayList<Renderer> out) {
            for (int i = firstRendererIndex; i < out.size(); i++) {
                Renderer renderer = out.get(i);
                if (!(renderer instanceof androidx.media3.exoplayer.video.MediaCodecVideoRenderer)
                        || renderer instanceof ReplayableCacheVideoRenderer) {
                    continue;
                }
                long lateThresholdToDropDecoderInputUs =
                        androidx.media3.exoplayer.video.MediaCodecVideoRenderer.DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US;
                androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder builder =
                        new androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(context)
                                .setCodecAdapterFactory(getCodecAdapterFactory())
                                .setMediaCodecSelector(mediaCodecSelector)
                                .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
                                .setEnableDecoderFallback(enableDecoderFallback)
                                .setEventHandler(eventHandler)
                                .setEventListener(eventListener)
                                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                                .experimentalSetParseAv1SampleDependencies(true)
                                .experimentalSetLateThresholdToDropDecoderInputUs(lateThresholdToDropDecoderInputUs)
                                .setEarlySchedulingThresholdUs(
                                        androidx.media3.exoplayer.video.MediaCodecVideoRenderer.DEFAULT_EARLY_SCHEDULING_THRESHOLD_US)
                                .setEnableDurationToProgressUs(enableDurationToProgressUs);
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    builder = builder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(false);
                }
                out.set(i, new ReplayableCacheVideoRenderer(builder, lateThresholdToDropDecoderInputUs));
                LOG.i("echo-exo-video-renderer: replayable cache on, usesExoSelector="
                        + (mediaCodecSelector == EXO_VIDEO_CODEC_SELECTOR)
                        + " preferSoft=" + preferSoftwareDecode);
                return;
            }
            // 未换成功:保持上游默认渲染器,暂停态重绘随之失效(redrawReady 会挡掉),不影响播放
            LOG.i("echo-exo-video-renderer: media codec renderer not found, redraw disabled");
        }

        @Override
        protected void buildTextRenderers(Context context, TextOutput output, Looper outputLooper,
                                          int extensionRendererMode, ArrayList<Renderer> out) {
            int firstRendererIndex = out.size();
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out);
            for (int i = firstRendererIndex; i < out.size(); i++) {
                Renderer renderer = out.get(i);
                out.set(i, (Renderer) Proxy.newProxyInstance(Renderer.class.getClassLoader(),
                        new Class<?>[]{Renderer.class},
                        new SubtitleOffsetRendererHandler(renderer, subtitleDelayProvider)));
            }
        }
    }

    private static final class SubtitleOffsetRendererHandler implements InvocationHandler {
        private final Renderer renderer;
        private final SubtitleDelayProvider subtitleDelayProvider;

        SubtitleOffsetRendererHandler(Renderer renderer, SubtitleDelayProvider subtitleDelayProvider) {
            this.renderer = renderer;
            this.subtitleDelayProvider = subtitleDelayProvider;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object[] invokeArgs = args;
            if ("render".equals(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof Long) {
                invokeArgs = args.clone();
                long positionUs = (Long) invokeArgs[0];
                invokeArgs[0] = Math.max(0, positionUs - subtitleDelayProvider.getDelayUs());
            }
            try {
                return method.invoke(renderer, invokeArgs);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
