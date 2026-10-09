package com.github.tvbox.osc.util;

/**
 * ExoPlayer 内核内置字幕的显示设置（时间延迟 / 缩放着 / 垂直位置）。
 *
 * <p>外挂字幕（SimpleSubtitleView / 歌词视图 / 在线字幕搜索）已随字幕功能整体移除。
 */
public class SubtitleHelper {

    /** 内置字幕时间延迟（毫秒，正数=字幕提前） */
    public static int getTimeDelay() {
        return KV.get(HawkConfig.SUBTITLE_TIME_DELAY, 0);
    }

    public static void setTimeDelay(int delay) {
        KV.put(HawkConfig.SUBTITLE_TIME_DELAY, delay);
    }

    /** 内置字幕缩放（百分比，100=原始大小） */
    public static int getExoSubtitleScale() {
        return KV.get(HawkConfig.SUBTITLE_EXO_SCALE, 100);
    }

    public static void setExoSubtitleScale(int scale) {
        KV.put(HawkConfig.SUBTITLE_EXO_SCALE, scale);
    }

    /** 内置字幕垂直位置（百分比偏移） */
    public static float getExoSubtitlePosition() {
        return KV.get(HawkConfig.SUBTITLE_EXO_POSITION, 0.0f);
    }

    public static void setExoSubtitlePosition(float position) {
        KV.put(HawkConfig.SUBTITLE_EXO_POSITION, position);
    }

    /** 恢复内置字幕显示设置的默认值 */
    public static void reset() {
        KV.delete(HawkConfig.SUBTITLE_TIME_DELAY);
        KV.delete(HawkConfig.SUBTITLE_EXO_SCALE);
        KV.delete(HawkConfig.SUBTITLE_EXO_POSITION);
    }

}
