package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 通用 API 源(一个 jar 内置的标准爬虫核心)。
 *
 * 覆盖两类最主流的配置源:
 *  1) {@code api.php/provide/vod} 系(苹果CMS / 海洋CMS 标准接口)
 *  2) 通用 JSON 接口(ac=videolist / ac=detail 等)
 *
 * 对外契约与 com.github.catvod.crawler.Spider 完全一致,
 * 因此可被 app 的 JarLoader 直接 loadClass 加载,也可作为项目内源直接实例化。
 */
public class SpiderVod extends Spider {

    /** 源配置(init 时传入的 ext,JSON 串或纯 host) */
    private String ext = "";
    /** 站点根地址,如 https://example.com */
    private String host = "";
    /** 接口地址,如 https://example.com/api.php/provide/vod */
    private String api = "";
    /** 是否使用 ac=detail 详情接口 */
    private boolean useDetailApi = true;

    @Override
    public void init(Context context, String extend) {
        this.ext = extend == null ? "" : extend;
        parseExt(extend);
    }

    /** 解析源 ext:支持纯 URL 或 JSON 配置两种形态 */
    private void parseExt(String extend) {
        if (TextUtils.isEmpty(extend)) return;
        String e = extend.trim();
        if (e.startsWith("{")) {
            try {
                JSONObject jo = new JSONObject(e);
                this.api = jo.optString("api", jo.optString("url", ""));
                this.host = jo.optString("host", "");
                this.useDetailApi = jo.optBoolean("detail", true);
            } catch (Exception ignored) {
            }
        } else {
            this.api = e;
        }
        if (TextUtils.isEmpty(this.host) && !TextUtils.isEmpty(this.api)) {
            this.host = extractHost(this.api);
        }
        if (TextUtils.isEmpty(this.api) && !TextUtils.isEmpty(this.host)) {
            this.api = normalizeApi(this.host);
        } else if (!TextUtils.isEmpty(this.api)) {
            this.api = normalizeApi(this.api);
        }
    }

    /** 从 api 地址推导站点根 */
    private String extractHost(String url) {
        try {
            int idx = url.indexOf("://");
            if (idx < 0) return url;
            int slash = url.indexOf('/', idx + 3);
            return slash < 0 ? url : url.substring(0, slash);
        } catch (Exception e) {
            return url;
        }
    }

    /** 把各种写法的接口归一化到 standard 形式 */
    private String normalizeApi(String url) {
        String u = url.trim();
        if (u.contains("provide/vod")) return u;
        // 形如 xxx/api.php 补全为 xxx/api.php/provide/vod
        if (u.endsWith("/api.php") || u.endsWith("api.php")) {
            return u + "/provide/vod";
        }
        return u;
    }

    // ==================== Spider 生命周期实现 ====================

    /** 首页:分类 + 部分推荐。ac=videolist&pg=1 */
    @Override
    public String homeContent(boolean filter) {
        JSONObject result = new JSONObject();
        try {
            JSONArray classes = new JSONArray();
            result.put("class", classes);
            result.put("filters", new JSONObject());
            result.put("list", new JSONArray());
            String url = api + "?ac=videolist&wd=&pg=1";
            String html = OkHttp.string(url);
            if (TextUtils.isEmpty(html)) return result.toString();
            JSONObject rel = new JSONObject(html);
            // 分类列表(部分源 homeContent 才给)
            JSONArray clArr = rel.optJSONArray("class");
            if (clArr != null) {
                for (int i = 0; i < clArr.length(); i++) {
                    JSONObject c = clArr.optJSONObject(i);
                    if (c == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("type_id", String.valueOf(c.opt("type_id")));
                    item.put("type_name", c.opt("type_name"));
                    classes.put(item);
                }
            }
            // 推荐列表
            buildVodList(rel, result);
            result.put("class", classes);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    /** 分类翻页:ac=videolist&t=分类id&pg=页码 */
    @Override
    public String categoryContent(String cid, String pg, boolean filter, HashMap<String, String> extend) {
        JSONObject result = new JSONObject();
        try {
            String url = api + "?ac=videolist&t=" + cid + "&pg=" + pg;
            String html = OkHttp.string(url);
            if (TextUtils.isEmpty(html)) return result.toString();
            JSONArray list = new JSONObject(html).optJSONArray("list");
            result.put("page", pg);
            result.put("limit", 20);
            result.put("total", new JSONObject(html).optInt("total", 0));
            result.put("vodlist", list == null ? new JSONArray() : list);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    /** 详情:ac=detail&ids=vodId(标准化字段解析进 dict 结构) */
    @Override
    public String detailContent(List<String> ids) {
        JSONObject result = new JSONObject();
        try {
            if (ids == null || ids.isEmpty()) return result.toString();
            String url = api + "?ac=detail&ids=" + ids.get(0);
            String html = OkHttp.string(url);
            if (TextUtils.isEmpty(html)) return result.toString();
            JSONObject rel = new JSONObject(html);
            JSONArray list = rel.optJSONArray("list");
            if (list != null && list.length() > 0) {
                JSONObject item = list.optJSONObject(0);
                if (item != null) {
                    JSONObject detail = new JSONObject();
                    detail.put("vod_id", item.opt("vod_id"));
                    detail.put("vod_name", item.opt("vod_name"));
                    detail.put("vod_pic", item.opt("vod_pic"));
                    detail.put("type_name", item.opt("type_name"));
                    detail.put("vod_year", item.opt("vod_year"));
                    detail.put("vod_area", item.opt("vod_area"));
                    detail.put("vod_content", item.opt("vod_content"));
                    detail.put("vod_play_from", item.opt("vod_play_from"));
                    detail.put("vod_play_url", item.opt("vod_play_url"));
                    JSONArray l = new JSONArray();
                    l.put(detail);
                    result.put("list", l);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    /** 搜索:ac=videolist&wd=关键词 */
    @Override
    public String searchContent(String key, boolean quickSearch) {
        JSONObject result = new JSONObject();
        try {
            String url = api + "?ac=videolist&wd=" + URLEncoder.encode(key, "UTF-8");
            String html = OkHttp.string(url);
            if (TextUtils.isEmpty(html)) return result.toString();
            JSONObject rel = new JSONObject(html);
            JSONArray list = rel.optJSONArray("list");
            JSONArray v = new JSONArray();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.optJSONObject(i);
                    if (it == null) continue;
                    JSONObject vod = new JSONObject();
                    vod.put("vod_id", String.valueOf(it.opt("vod_id")));
                    vod.put("vod_name", it.opt("vod_name"));
                    vod.put("vod_pic", it.opt("vod_pic"));
                    vod.put("vod_remarks", it.opt("vod_remarks"));
                    v.put(vod);
                }
            }
            result.put("list", v);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    /** 通用:按接入方约定返回空,不影响主流程 */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject result = new JSONObject();
        try {
            result.put("parse", 1);
        } catch (Exception ignored) {
        }
        return result.toString();
    }

    /** 工具:把 provide/vod 首屏 list 装进通用 list 结构 */
    private JSONObject buildVodList(JSONObject rel, JSONObject bucket) {
        try {
            JSONArray list = rel.optJSONArray("list");
            bucket.put("list", list == null ? new JSONArray() : list);
        } catch (Exception ignored) {
        }
        return bucket;
    }

}
