package com.github.tvbox.osc.api;

import androidx.media3.common.util.UriUtil;

import com.github.tvbox.osc.bean.Depot;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HeaderGuard;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 配置解析里的纯函数:地址改写、文本/JSON 形状判断、站点与线路列表构造。
 * 需要写实例状态或 KV 的部分留在 ApiConfig。
 */
final class ConfigParser {

    private ConfigParser() {
    }

    private static final Gson gson = new Gson();

    /** 与 TextUtils.isEmpty 等价;不得改用 android.text.TextUtils —— 单测 returnDefaultValues 会让它静默返 false */
    private static boolean isEmpty(String text) {
        return text == null || text.length() == 0;
    }

    /** 截出第一个 { 到最后一个 } 之间的内容:接口正文前面常带版权说明、后面带尾巴 */
    static String trimJsonObject(String content) {
        if (content == null) {
            return "";
        }
        String trimContent = content.trim();
        int start = trimContent.indexOf("{");
        int end = trimContent.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return trimContent.substring(start, end + 1);
        }
        return trimContent;
    }

    /** 判断直播配置正文是 JSON 还是 m3u/txt 文本(带 BOM 的也算 JSON) */
    static boolean isLiveJsonContent(String content) {
        if (content == null) return false;
        String text = content.trim();
        if (text.startsWith("\ufeff")) text = text.substring(1).trim();
        return text.startsWith("{");
    }

    /** 从 m3u 头部 #EXTM3U 行取 EPG 地址,依次尝试 x-tvg-url / tvg-url / url-tvg */
    static String extractLiveTextEpg(String content) {
        if (content == null) return "";
        String text = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("\ufeff")) line = line.substring(1).trim();
            if (!line.startsWith("#EXTM3U")) continue;
            String epg = extractQuotedAttr(line, "x-tvg-url");
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "tvg-url");
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "url-tvg");
            return epg;
        }
        return "";
    }

    /** 取 key="value" 里的 value;没有该 key 或引号不闭合都返回空串 */
    static String extractQuotedAttr(String line, String key) {
        String token = key + "=\"";
        int start = line.indexOf(token);
        if (start < 0) return "";
        start += token.length();
        int end = line.indexOf("\"", start);
        if (end < 0) return "";
        return line.substring(start, end).trim();
    }

    /**
     * 解析点播站点列表。缺 key/type/api 的条目跳过(不完整的站点进内存只会在首页变成一个点不开的图标)。
     * 顺序即配置顺序,调用方按第一个站点兜底首页源。
     */
    static List<SourceBean> parseSites(JsonObject infoJson) {
        List<SourceBean> sites = new ArrayList<>();
        for (JsonElement opt : infoJson.get("sites").getAsJsonArray()) {
            JsonObject obj = (JsonObject) opt;
            if (!obj.has("key") || !obj.has("type") || !obj.has("api")) {
                LOG.i("echo-skip incomplete site config: " + obj);
                continue;
            }
            SourceBean sb = new SourceBean();
            String siteKey = obj.get("key").getAsString().trim();
            sb.setKey(siteKey);
            sb.setName(obj.has("name") ? obj.get("name").getAsString().trim() : siteKey);
            sb.setType(obj.get("type").getAsInt());
            sb.setApi(obj.get("api").getAsString().trim());
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
            sb.setChangeable(DefaultConfig.safeJsonInt(obj, "changeable", 1));
            if (siteKey.startsWith("py_")) {
                sb.setFilterable(1);
            } else {
                sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
            }
            sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
            sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
            sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
            sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
            sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
            sb.setTimeout(DefaultConfig.safeJsonInt(obj, "timeout", 0));
            sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
            sb.setStyle(DefaultConfig.safeJsonString(obj, "style", ""));
            sb.setIcon(DefaultConfig.safeJsonString(obj, "icon", ""));
            sb.setHide(DefaultConfig.safeJsonInt(obj, "hide", 0));
            sb.setIndexs(DefaultConfig.safeJsonInt(obj, "indexs", 0));
            sb.setDanmaku(DefaultConfig.safeJsonInt(obj, "danmaku", 1));
            sb.setHeader(parseHeaderObject(obj, "header"));
            String extPreview = sb.getExt();
            LOG.i("echo-site:" + sb.getName() + " icon:" + sb.getIcon()
                    + " ext:" + (extPreview.length() > 160 ? extPreview.substring(0, 160) : extPreview));
            sites.add(sb);
        }
        return sites;
    }

    /**
     * 解析"线路合集"(多仓)配置:只有带 urls 且不带 sites 的才算合集,返回 {@link HistoryHelper#buildApiLine} 拼好的行。
     * 任何异常都按"不是合集"处理(返回已解析到的行),不能让它把正常配置加载带崩。
     */
    static ArrayList<String> parseApiCollection(String jsonStr) {
        ArrayList<String> apiLines = new ArrayList<>();
        try {
            String json = trimJsonObject(jsonStr);
            if (isEmpty(json)) {
                return apiLines;
            }
            JsonObject infoJson = gson.fromJson(json, JsonObject.class);
            if (!isDepotJson(infoJson)) {
                return apiLines;
            }
            for (Depot item : Depot.arrayFrom(infoJson.get("urls").getAsJsonArray())) {
                apiLines.add(HistoryHelper.buildApiLine(item.getName(), item.getUrl()));
            }
        } catch (Throwable ignored) {
            LOG.d("ApiConfig", "api lines parse failed, keep lines so far");
        }
        return apiLines;
    }

    /**
     * 这段 JSON 是不是"多仓"(仓库)配置:有非空 {@code urls} 数组且**没有 {@code sites}**。
     * 点播与直播共用这条判定(2026-09-21);sites 优先,否则会把正常配置整段换成仓里第一条。
     */
    static boolean isDepotJson(JsonObject infoJson) {
        if (infoJson == null || infoJson.has("sites")) return false;
        if (!infoJson.has("urls")) return false;
        JsonElement urls = infoJson.get("urls");
        return urls != null && urls.isJsonArray() && urls.getAsJsonArray().size() > 0;
    }

    /** 直播设置「配置切换」组的候选项:没写 name 的用"线路N"占位 */
    static ArrayList<LiveSettingItem> parseLiveSettingItems(JsonArray livesGroups) {
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        for (int i = 0; i < livesGroups.size(); i++) {
            JsonObject jsonObject = livesGroups.get(i).getAsJsonObject();
            String name = jsonObject.has("name") ? jsonObject.get("name").getAsString() : "线路" + (i + 1); // i18n: keep(数据默认名,进 bean 且被 ConfigParserTest 锁定)
            LiveSettingItem liveSettingItem = new LiveSettingItem();
            liveSettingItem.setItemIndex(i);
            liveSettingItem.setItemName(name);
            liveSettingItemList.add(liveSettingItem);
        }
        return liveSettingItemList;
    }

    /** 配置顶层 hosts 数组,形如 "a.com=1.2.3.4";value 里再出现 = 也保留(只按第一个 = 拆) */
    static Map<String, String> parseHosts(JsonArray hostsArray) {
        Map<String, String> hosts = new HashMap<>();
        for (int i = 0; i < hostsArray.size(); i++) {
            String entry = hostsArray.get(i).getAsString();
            String[] parts = entry.split("=", 2);
            if (parts.length == 2) {
                hosts.put(parts[0], parts[1]);
            }
        }
        return hosts;
    }

    /** 站点级 header 对象(fongmi 的 sites[].header):只取标量值,缺失/非对象/值全非法时返回 null(表示没配) */
    static Map<String, String> parseHeaderObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            return null;
        }
        Map<String, String> header = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject(key).entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) continue;
            String name = entry.getKey();
            String value = entry.getValue().getAsString();
            // OkHttp 对 header 名/值有字符集限制,越界会在构造请求时抛异常把整个源带崩:这里直接跳过
            if (!HeaderGuard.isSendable(name, value)) {
                LOG.i("echo-site-header-skip:" + name);
                continue;
            }
            header.put(name, value);
        }
        return header.isEmpty() ? null : header;
    }

    /** 频道显示名:优先 name,没写时用首地址兜底(与 Depot/parseApiCollection 同口径);两者都空返回空串=该条不该进列表 */
    static String parseLiveChannelName(JsonObject obj, ArrayList<String> sourceUrls) {
        if (obj != null && obj.has("name")) {
            JsonElement name = obj.get("name");
            if (name != null && name.isJsonPrimitive()) {
                String text = name.getAsString().trim();
                if (!text.isEmpty()) return text;
            }
        }
        for (String url : sourceUrls) {
            if (url != null && !url.isEmpty()) return url;
        }
        return "";
    }

    /**
     * 直播 catchup:对象原样用;标量转 type 并按需带上 catchup-source/replace;null 与非标量非对象算"未配"。
     * 这些脏值以前各抛一种异常,而加载链路(ApiConfig.loadLives)在主线程且没有 try。
     */
    static JsonObject parseLiveCatchup(JsonObject obj) {
        if (obj == null || !obj.has("catchup")) return null;
        JsonElement catchup = obj.get("catchup");
        if (catchup == null || catchup.isJsonNull()) return null;
        if (catchup.isJsonObject()) return catchup.getAsJsonObject();
        if (!catchup.isJsonPrimitive()) return null;
        JsonObject catchupObj = new JsonObject();
        catchupObj.addProperty("type", catchup.getAsString());
        String source = DefaultConfig.safeJsonString(obj, "catchup-source", "");
        if (!source.isEmpty()) catchupObj.addProperty("source", source);
        String replace = DefaultConfig.safeJsonString(obj, "catchup-replace", "");
        if (!replace.isEmpty()) catchupObj.addProperty("replace", replace);
        return catchupObj;
    }


    /**
     * clan:// 地址转真实地址。
     *
     * @param localFileBase 本机服务基址;用 Supplier 保持"只在 clan://localhost/ 时才求值"
     */
    static String clanToAddress(String lanLink, Supplier<String> localFileBase) {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", localFileBase.get() + "file/");
        } else {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
    }

    /** 把配置正文里的 clan://localhost/ 与 file:// 前缀统一改写成真实地址前缀 */
    static String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://localhost/", fix).replace("file://", fix);
    }

    /** 配置里用了 "./" / "../" 相对路径时,按配置地址把它们展开成绝对地址 */
    static String fixContentPath(String url, String content, Supplier<String> localFileBase) {
        if (content.contains("\"./") || content.contains("\"../")) {
            url = url.replace("file://", "clan://localhost/");
            if (!url.startsWith("http") && !url.startsWith("clan://")) {
                url = "http://" + url;
            }
            if (url.startsWith("clan://")) url = clanToAddress(url, localFileBase);
            content = content.replace("../", UriUtil.resolve(url, "../"));
            content = content.replace("./", UriUtil.resolve(url, "./"));
        }
        return content;
    }

    /** 拆配置地址:支持 `地址;pk;密钥` 形式,返回真实地址与密钥 */
    static ConfigUrl configUrl(String apiUrl, Supplier<String> localFileBase) {
        String key = null;
        String configUrl = "";
        String pk = ";pk;";
        apiUrl = apiUrl.replace("file://", "clan://localhost/");
        if (apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            key = a[1];
            if (apiUrl.startsWith("clan")) {
                configUrl = clanToAddress(a[0], localFileBase);
            } else if (apiUrl.startsWith("http")) {
                configUrl = a[0];
            } else {
                configUrl = "http://" + a[0];
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl, localFileBase);
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + apiUrl;
        } else {
            configUrl = apiUrl;
        }
        return new ConfigUrl(configUrl, key);
    }

    static final class ConfigUrl {
        final String url;
        final String key;

        ConfigUrl(String url, String key) {
            this.url = url;
            this.key = key;
        }
    }

    /**
     * 源列表格式归一化:把"裸源数组"订阅包装成标准配置对象。
     *
     * <p>现象:第三方源列表(如 uzVideo 的 video_sources_default.json)直接给
     * {@code [{"name":"…","api":"…"},…]},而标准配置需要顶层 {@code {"sites":[…]}} 且每个源
     * 必须有 {@code key}/{@code type}。这种裸数组让 {@code parseJson} 的
     * {@code gson.fromJson(jsonStr, JsonObject.class)} 直接抛异常。
     *
     * <p>仅当正文能被解析为 JSON 数组、且元素都是对象并含 {@code api} 时才算源列表;
     * 否则原样返回(null 或任何异常也返回原串)。
     *
     * <p>源补齐:{@code key} 用 api 的 host(去协议/路径,剩余非法字符换下划线),重复时在 key 后追加序号;
     * {@code type} 默认 1(苹果CMS JSON)——若 api 以 {@code .xml} 结尾或含 {@code at/xml} 则 0。
     */
    static String normalizeSourceListConfig(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("[")) {
            return content;
        }
        try {
            JsonArray raw;
            try {
                raw = JsonParser.parseString(trimmed).getAsJsonArray();
            } catch (Exception e) {
                raw = JsonParser.parseString(ConfigParser.trimJsonObject(trimmed)).getAsJsonArray();
            }
            JsonObject wrapper = new JsonObject();
            JsonArray sites = new JsonArray();
            java.util.HashSet<String> usedKeys = new java.util.HashSet<>();
            for (JsonElement element : raw) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                if (!item.has("api")) {
                    continue;
                }
                String api = item.get("api").getAsString().trim();
                if (api.isEmpty()) {
                    continue;
                }
                String name = item.has("name") && !item.get("name").isJsonNull()
                        ? item.get("name").getAsString().trim() : api;
                String key = name.isEmpty()
                        ? hostOf(api) : api.equals(name) ? hostOf(api) : keyOfName(name, api);
                // key 去重(同名 host 多源):追加 -2/-3/… 序号,不能让后源覆盖前源
                if (!usedKeys.add(key)) {
                    int n = 2;
                    while (!usedKeys.add(key + "_" + n)) {
                        n++;
                    }
                    key = key + "_" + n;
                }

                JsonObject site = new JsonObject();
                site.addProperty("key", key);
                site.addProperty("name", name.isEmpty() ? key : name);
                site.addProperty("api", api);
                site.addProperty("type", api.endsWith(".xml") || api.contains("/at/xml") ? 0 : 1);
                if (item.has("ext") && item.get("ext").isJsonObject()) {
                    site.add("ext", item.get("ext"));
                }
                if (item.has("jar")) {
                    site.addProperty("jar", item.get("jar").getAsString());
                }
                sites.add(site);
            }
            if (sites.size() == 0) {
                return content;
            }
            wrapper.add("sites", sites);
            return wrapper.toString();
        } catch (Throwable th) {
            return content;
        }
    }

    /** api 的主机名(去 scheme/端口/路径),非法字符统一换下划线 */
    private static String hostOf(String api) {
        String s = api.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s.replaceAll("[^A-Za-z0-9]", "_");
    }

    /** 源 key:站点名重名用 api 主机兜底;都不可靠则退回全小写下划线名 */
    private static String keyOfName(String name, String api) {
        String host = hostOf(api);
        if (host != null && !host.isEmpty()) {
            return host;
        }
        return name.replaceAll("[^A-Za-z0-9]", "_").toLowerCase();
    }
}
