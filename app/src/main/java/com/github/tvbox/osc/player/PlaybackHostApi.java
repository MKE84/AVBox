package com.github.tvbox.osc.player;

import android.net.Uri;

import androidx.annotation.NonNull;

/**
 * 播放指令面(播放服务化 Spec §2.1–2.3,`skill/avbox-playback-service-spec.md`)。
 *
 * <p>页面只通过这些方法驱动播放;实现方在 P0/P1 是页面内的 {@code PlayContainer},
 * P2 起改为前台服务持有的播放宿主 `MyVideoView` + `PlaybackController`。页面侧(Compose 覆盖层、
 * 详情页、媒体会话/通知)只依赖本接口,从而与"播放器由谁持有"解耦。
 */
public interface PlaybackHostApi {

    /** 开始一次播放(页面组装的会话数据) */
    void setData(@NonNull PlaybackSession session);

    /** 重播当前集(reset=true 时清除进度从头发起) */
    void play(boolean reset);

    /** 下一集(rmProgress=true 时清掉上一集进度) */
    void playNext(boolean rmProgress);

    /** 上一集 */
    void playPrevious();

    /** 切换清晰度 {@code position}(多清晰度源的 url 数组下标) */
    boolean selectQuality(int position);

    /** 全屏形态下是否允许自动换线(预览态启用,全屏态禁用 —— 见 DetailActivity.applyFullscreen) */
    void setAutoSwitchLineEnabled(boolean enabled);

    /** 竖屏预览态样式(底栏菜单行/暂停钮/边距) */
    void setPreviewMode(boolean previewMode);

    /** 唤出/收起控制器控件(返回键 YouTube 式两步退出用) */
    void toggleControllerControls();

    /** 返回键交给播放层消费:true=已处理(不退出页面) */
    boolean onBackPressed();

    /** 标记"正在退出预览态"(退出页面时不自动暂停 —— 避免退后台暂停语义误伤) */
    void setExitingPreview(boolean exitingPreview);

    /** 顶部标题显隐 */
    void setPlayTitle(boolean show);

    /** 换源点击即停:停播并记住进度,抑制在途取流结果 */
    void stopForSourceSwitch(String tip);

    /** 清除"正在切换片源"提示 */
    void clearSourceSwitchTip();

    /** 投屏面板(详情页标题行入口复用播放器底栏同一条链路) */
    void showCast();

    void hostResume();

    void hostPause();

    void hostDestroy();

    void resumeFromMediaSession();

    void pauseFromMediaSession();

    void stopFromMediaSession();

    void seekFromMediaSession(long position);
}
