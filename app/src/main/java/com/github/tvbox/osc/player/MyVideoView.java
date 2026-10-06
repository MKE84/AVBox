package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.ImgUtil;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.ui.widget.DanmakuView;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.TextureRenderView;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class MyVideoView extends VideoView implements DrawHandler.Callback {
    private DanmakuView danmuView;
    private ImageView artworkView;
    /** 封面的在途图片请求句柄:换图/隐藏前必须先取消,否则过期海报可能盖到画面上(见 clearArtwork) */
    private coil3.request.Disposable artworkDisposable;
    private View frameCover;

    /** "本次起播必须重建内核"标记(EXO 解码方式变更,见 PlayerHelper.updateCfg) */
    private boolean mKernelRebuildRequired;
    /** 本片记忆键(见 TrackMemory);存于 VideoView 是因为内核重建后要把键推给新实例 */
    private String mTrackMemoryKey = "";

    public void setTrackMemoryKey(String key) {
        mTrackMemoryKey = key == null ? "" : key;
        applyTrackMemoryKey();
    }

    @Override
    protected void initPlayer() {
        super.initPlayer();
        applyTrackMemoryKey();
    }

    private void applyTrackMemoryKey() {
        if (mMediaPlayer instanceof ExoPlayer) {
            ((ExoPlayer) mMediaPlayer).setContentKey(mTrackMemoryKey);
        }
    }

    public MyVideoView(@NonNull Context context) {
        super(context, null);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AbstractPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    /** 内核存在且停在错误态:复用判定用它兜底 —— 复用一个坏内核没有意义,必须强制重建(无内核时为 false) */
    public boolean isKernelErrored() {
        return mMediaPlayer != null && getCurrentPlayState() == STATE_ERROR;
    }

    public void requireKernelRebuild() {
        mKernelRebuildRequired = true;
    }

    /** 取出并复位"必须重建内核"标记(起播处消费;true 时走非复用路径,先释放再新建) */
    public boolean consumeKernelRebuildRequired() {
        boolean required = mKernelRebuildRequired;
        mKernelRebuildRequired = false;
        return required;
    }

    /** 当前渲染视图是否为 SurfaceView(见 [switchRenderToTexture] 的纯音频兜底) */
    public boolean isSurfaceRenderActive() {
        return mRenderView != null && mRenderView.getView() instanceof SurfaceView;
    }

    /** 当前渲染工厂对应的渲染方式(1=Surface,0=Texture):"本次起播实际会用哪种视图"的唯一取值口 */
    public int factoryRenderType() {
        return (mRenderViewFactory instanceof TextureRenderViewFactory) ? 0 : 1;
    }

    /**
     * 渲染视图是否与目标渲染方式不一致(不一致 = 必须重建内核才会生效);未挂载时算已就绪 —— 下次 start() 本就按工厂新建。
     * 目标是"工厂"还是"配置值"由调用方给(起播链路传 {@link #factoryRenderType()},接管链路传配置值),两处口径都经本方法。
     */
    public boolean needsRenderRebuild(int targetRenderType) {
        if (mRenderView == null) return false;
        return (targetRenderType == 1) != isSurfaceRenderActive();
    }

    public void switchRenderToTexture() {
        setRenderViewFactory(TextureRenderViewFactory.create());
        addDisplay();
    }

    public void ensureRenderViewMatchesConfig() {
        // mRenderView 空 = 尚未挂载(下次 start() 会按工厂创建);
        // mMediaPlayer 空 = 无播放器可挂载 —— addDisplay 内 attachToPlayer(null) 属未定义调用,
        // 直接返回更稳(与 clearVideoFrame 的判空风格一致)
        if (mRenderView == null || mMediaPlayer == null) return;
        boolean expectedSurface = !(mRenderViewFactory instanceof TextureRenderViewFactory);
        if (expectedSurface == isSurfaceRenderActive()) return;
        addDisplay();
    }

    /** 纹理渲染路径没有 SurfaceHolder:补发输出分辨率信令的时机靠这里挂钩(交面之后),漏挂 = 效果链拿不到输出面 */
    @Override
    protected void addDisplay() {
        super.addDisplay();
        if (mRenderView instanceof TextureRenderView) {
            ((TextureRenderView) mRenderView).setOnSurfaceReadyListener(this::pushRenderOutputResolution);
        }
    }

    /** 纹理渲染路径没有 SurfaceHolder:交面后与每次布局都补发(漏发 = 效果管线出画异常) */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        pushRenderOutputResolution();
    }

    /** 视频尺寸就绪必须当帧补发(不能等下一次布局),输出面尺寸就取它 */
    @Override
    public void onVideoSizeChanged(int videoWidth, int videoHeight) {
        super.onVideoSizeChanged(videoWidth, videoHeight);
        pushRenderOutputResolution();
    }

    /** 纹理路径只推视频原生尺寸(推视图尺寸会被管线等比适应进画布 = 丢「铺满/裁剪」;取流前不下发) */
    private void pushRenderOutputResolution() {
        if (!(mMediaPlayer instanceof ExoPlayer) || mRenderView == null || isSurfaceRenderActive()) return;
        int width = mVideoSize[0];
        int height = mVideoSize[1];
        if (width <= 0 || height <= 0) return;
        if (mRenderView instanceof TextureRenderView) {
            ((TextureRenderView) mRenderView).setOutputSize(width, height);
        }
        ((ExoPlayer) mMediaPlayer).notifyVideoOutputResolution(width, height);
    }

    public void setArtwork(String url) {
        if (TextUtils.isEmpty(url)) {
            clearArtwork();
            return;
        }
        if (artworkView == null) {
            artworkView = new ImageView(getContext());
            artworkView.setBackgroundColor(android.graphics.Color.BLACK);
            artworkView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            artworkView.setClickable(false);
            artworkView.setFocusable(false);
            int index = mRenderView == null ? 0 : Math.min(1, mPlayerContainer.getChildCount());
            mPlayerContainer.addView(artworkView, index, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        artworkView.setVisibility(VISIBLE);
        // 先撤旧请求再发新请求:封面每换一集/一线都要换图,上一个请求的迟到回调会把过期海报盖上来
        cancelArtworkRequest();
        artworkDisposable = ImgUtil.loadPlayerArtwork(url, artworkView);
    }

    public void clearArtwork() {
        // 取消在途请求,再隐藏:Coil 的 onSuccess 不检查视图可见性,只置 GONE 挡不住晚到的位图
        cancelArtworkRequest();
        if (artworkView != null) {
            artworkView.setVisibility(GONE);
            artworkView.setImageDrawable(null);
        }
    }

    /** 取消播放器封面的在途图片请求(Coil dispose 会同步置 isDisposed 并取消 job,晚到回调不再落地) */
    private void cancelArtworkRequest() {
        if (artworkDisposable != null) {
            artworkDisposable.dispose();
            artworkDisposable = null;
        }
    }

    public int[] getVideoSize() {
        return mVideoSize;
    }

    public boolean isPortraitVideo() {
        return VideoOrientation.isPortrait(mVideoSize[0], mVideoSize[1]);
    }

    public void clearVideoFrame() {
        if (mMediaPlayer != null) mMediaPlayer.stop();
        showFrameCover();
    }

    public void coverVideoFrame() {
        showFrameCover();
    }

    private void showFrameCover() {
        if (frameCover == null) {
            frameCover = new View(getContext());
            frameCover.setBackgroundColor(android.graphics.Color.BLACK);
            mPlayerContainer.addView(frameCover, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        frameCover.setVisibility(VISIBLE);
        // 遮罩是追加的,会把控制器(顶栏/手势层/字幕/直播控制层)一起盖住;控制器属 UI 层必须压在最上。
        // 用 bringToFront 而不是按 index 插:addDisplay() 永远把渲染视图插到 index 0,index 方案在
        // "渲染视图尚未创建"时会算错位(此时容器里可能只有控制器)
        if (mVideoController != null) mVideoController.bringToFront();
    }

    public void showVideoFrame() {
        hideVideoFrameCover();
        // 画面已出 → 顺手撤掉封面:artworkView 与渲染 Surface 同层且盖在其上,
        // 任何「画面已就绪却仍显示封面」的时序都会把视频压成一张海报(有声无画)。
        // 这里做终极兜底,保证「有画面」与「显示封面」互斥。
        clearArtwork();
    }

    public void hideVideoFrameCover() {
        if (frameCover != null) frameCover.setVisibility(GONE);
    }

    public boolean isVideoFrameCleared() {
        return frameCover != null && frameCover.getVisibility() == VISIBLE;
    }

    @Override
    public void seekTo(long pos) {
        super.seekTo(pos);
        if (haveDanmu()) danmuView.seekTo(pos);
    }

    @Override
    public void resume() {
        super.resume();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void start() {
        super.start();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void pause() {
        super.pause();
        if (haveDanmu()) danmuView.pause();
    }

    @Override
    public void release() {
        super.release();
        // 键随内核一起作废:直播页/音乐页直接 setUrl 起播从不推键,留着上一部片的键会吃掉它们的默认字幕
        mTrackMemoryKey = "";
        if (haveDanmu()) danmuView.release();
    }

    private boolean haveDanmu() {
        return danmuView != null && danmuView.isPrepared();
    }

    public void setDanmuView(DanmakuView view) {
        danmuView = view;
        if (danmuView != null) danmuView.setCallback(this);
    }

    public DanmakuView getDanmuView() {
        return danmuView;
    }

    @Override
    public void prepared() {
        post(() -> {
            if (danmuView == null) return;
            if (isPlaying() && danmuView.isPrepared()) {
                danmuView.start(getCurrentPosition());
            }
        });
    }

    @Override
    public void updateTimer(DanmakuTimer timer) {
    }

    @Override
    public void danmakuShown(BaseDanmaku danmaku) {
    }

    @Override
    public void drawingFinished() {
    }
}
