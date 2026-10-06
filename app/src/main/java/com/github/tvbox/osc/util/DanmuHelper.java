package com.github.tvbox.osc.util;

import android.graphics.Color;

import com.github.tvbox.osc.util.KV;

public class DanmuHelper {
    private static final String[] PALETTE = new String[]{
            "#ffffff", "#70f3ff", "#44cef6", "#3eede7", "#00e079", "#2edfa3",
            "#bce672", "#fff143", "#ffa631", "#ff7500", "#ff4e20", "#ff2d51",
            "#ef7a82", "#ff0097", "#b0a4e3", "#4b5cc4"
    };

    public static boolean isOpen() {
        return KV.get(HawkConfig.DANMU_OPEN, false);
    }

    public static void setOpen(boolean open) {
        KV.put(HawkConfig.DANMU_OPEN, open);
    }

    public static int getMaxLine() {
        return KV.get(HawkConfig.DANMU_MAX_LINE, 3);
    }

    public static void setMaxLine(int line) {
        KV.put(HawkConfig.DANMU_MAX_LINE, clamp(line, 1, 15));
    }

    public static float getSpeed() {
        return KV.get(HawkConfig.DANMU_SPEED, 1.5f);
    }

    public static void setSpeed(float speed) {
        KV.put(HawkConfig.DANMU_SPEED, speed);
    }

    public static float getAlpha() {
        return KV.get(HawkConfig.DANMU_ALPHA, 0.9f);
    }

    public static void setAlpha(float alpha) {
        KV.put(HawkConfig.DANMU_ALPHA, Math.max(0.1f, Math.min(alpha, 1.0f)));
    }

    public static float getSizeScale() {
        return KV.get(HawkConfig.DANMU_SIZE_SCALE, 0.8f);
    }

    public static void setSizeScale(float scale) {
        KV.put(HawkConfig.DANMU_SIZE_SCALE, Math.max(0.6f, Math.min(scale, 2.0f)));
    }

    public static boolean useRandomColor() {
        return KV.get(HawkConfig.DANMU_RANDOM_COLOR, false);
    }

    public static void setRandomColor(boolean randomColor) {
        KV.put(HawkConfig.DANMU_RANDOM_COLOR, randomColor);
    }

    public static int randomColor() {
        int index = (int) (Math.random() * PALETTE.length);
        return Color.parseColor(PALETTE[index]);
    }

    public static void reset() {
        KV.delete(HawkConfig.DANMU_RANDOM_COLOR);
        KV.delete(HawkConfig.DANMU_SPEED);
        KV.delete(HawkConfig.DANMU_SIZE_SCALE);
        KV.delete(HawkConfig.DANMU_MAX_LINE);
        KV.delete(HawkConfig.DANMU_ALPHA);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
