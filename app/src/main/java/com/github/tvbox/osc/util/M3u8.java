package com.github.tvbox.osc.util;

/**
 * M3U8 工具（净化功能已于 2026-10-09 按用户要求彻底移除，仅保留配置解析所需的广告正则判定）。
 *
 * @author asdfgh, FongMi
 * Based on FongMi/TV.
 * https://github.com/FongMi/TV
 */
public class M3u8 {
    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String TAG_MEDIA_DURATION = "#EXTINF";
    private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
    private static final String TAG_KEY = "#EXT-X-KEY";
    private static final String TAG_CUE_OUT = "#EXT-X-CUE-OUT";
    private static final String TAG_CUE_IN = "#EXT-X-CUE-IN";
    private static final String TAG_DATERANGE = "#EXT-X-DATERANGE";

    /** 供 ConfigApplier 在解析配置时区分「广告过滤正则」与「普通过滤规则」。 */
    public static boolean isAd(String regex) {
        return regex.contains(TAG_DISCONTINUITY) || regex.contains(TAG_MEDIA_DURATION) || regex.contains(TAG_ENDLIST) || regex.contains(TAG_KEY) || regex.contains(TAG_CUE_OUT) || regex.contains(TAG_CUE_IN) || regex.contains(TAG_DATERANGE) || isDouble(regex);
    }

    private static boolean isDouble(String ad) {
        try {
            return Double.parseDouble(ad) != 0;
        } catch (Exception e) {
            return false;
        }
    }
}
