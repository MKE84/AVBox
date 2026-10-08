package com.github.avbox.core;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.js.JsSpider;
import com.undcover.freedom.pyramid.PythonSpider;
import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.util.ApiLog;

import java.util.HashMap;
import java.util.Map;

/**
 * SpiderCore —— 统一源工厂。
 * 根据源 URL 的类型(py / js / api.php?ac=detail / provide/vod)分发,
 * 返回一个可直接被 app 调用的 {@link Spider} 实例。
 * 自包含,不重写现有 JsSpider / PythonSpider,只做统一调度与兜底。
 */
public class SpiderCore {

    private static final String TAG = "SpiderCore";

    /** 源类型枚举: 覆盖三种核心源 */
    public enum SourceKind { API, PY, JS, UNKNOWN }

    /** 内置解析器实例(懒加载) */
    private static volatile ParserCore parser;

    private final Context context;
    private final String key;
    private final String api;

    private SpiderCore(Context ctx, String key, String api) {
        this.context = ctx;
        this.key = key;
        this.api = api;
    }

    // ==================== 统一入口 ====================

    /**
     * 根据源 URL 创建一个可用的 Spider(api/py/js)。
     * @return 永不返回 null;解析失败时返回 null(由调用方兜底)
     */
    public static Spider create(Context ctx, String key, String api) {
        if (ctx == null || TextUtils.isEmpty(api)) return null;
        SourceKind kind = detect(api);
        ApiLog.registerSource(key, api);
        switch (kind) {
            case PY:
                return py(ctx, key, api);
            case JS:
                return js(ctx, key, api);
            default:
                return api(ctx, key, api);
        }
    }

    /** 探测源类型 */
    public static SourceKind detect(String api) {
        if (TextUtils.isEmpty(api)) return SourceKind.UNKNOWN;
        String u = api.trim().toLowerCase();
        if (u.endsWith(".py")) return SourceKind.PY;
        if (u.endsWith(".js")) return SourceKind.JS;
        if (u.contains("api.php") || u.contains("provide/vod")) return SourceKind.API;
        return SourceKind.API; // 默认当作 api
    }

    // ==================== api.php / provide/vod 源 ====================

    private static Spider api(Context ctx, String key, String api) {
        try {
            Class<?> cls = Class.forName("com.github.catvod.spider.SpiderVod");
            Spider s = (Spider) cls.getConstructor().newInstance();
            s.init(ctx, normalize(api), "");
            return s;
        } catch (Exception e) {
            ApiLog.fail(ApiLog.KIND_API, key, "core.api", e.getMessage());
            return null;
        }
    }

    // ==================== py 源 ====================

    private static Spider py(Context ctx, String key, String api) {
        try {
            Spider s = new PythonSpider(key);
            s.init(ctx, api, "");
            return s;
        } catch (Exception e) {
            ApiLog.fail(ApiLog.KIND_PY, key, "core.py", e.getMessage());
            return null;
        }
    }

    // ==================== js 源 ====================

    private static Spider js(Context ctx, String key, String api) {
        try {
            Spider s = new JsSpider(key, api, null);
            s.init(ctx, api, "");
            return s;
        } catch (Exception e) {
            ApiLog.fail(ApiLog.KIND_JS, key, "core.js", e.getMessage());
            return null;
        }
    }

    // ==================== 工具 ====================

    /** api.php 绝对值补全为提供 provide/vod 的形式(如已含则不变) */
    public static String normalize(String api) {
        String u = api == null ? "" : api.trim();
        if (u.isEmpty()) return u;
        if (u.contains("provide/vod")) return u;
        if (u.endsWith("/") ) return u + "api.php/provide/vod";
        if (u.endsWith("api.php")) return u + "/provide/vod";
        return u;
    }

    /** 便捷: 判断一个源是否可被本核心覆盖 */
    public static boolean canHandle(String api) {
        return !TextUtils.isEmpty(api) && detect(api) != SourceKind.UNKNOWN;
    }
}
