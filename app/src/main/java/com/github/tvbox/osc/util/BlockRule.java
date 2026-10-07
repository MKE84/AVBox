package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 关键词屏蔽规则:一条规则 = 名称 + 一组关键词(源名/标题命中任一关键词即屏蔽)。
 *
 * <p>存储为 JSON 数组字符串(KV key = {@link HawkConfig#SEARCH_BLOCK_KEYWORDS}),
 * 兼容旧版逗号分隔字符串(解析成单条"默认"规则,见 {@link #parseRules})。
 */
public class BlockRule {
    public String name;
    public List<String> keywords;

    public BlockRule() {
        this.keywords = new ArrayList<>();
    }

    public BlockRule(String name, List<String> keywords) {
        this.name = name;
        this.keywords = keywords != null ? keywords : new ArrayList<>();
    }

    private static final Gson GSON = new Gson();
    private static final Type RULE_LIST_TYPE = new TypeToken<List<BlockRule>>() {
    }.getType();

    /** 读取全部规则;旧格式(逗号分隔字符串)自动转成单条规则,保证平滑升级 */
    public static List<BlockRule> load() {
        String raw = KV.get(HawkConfig.SEARCH_BLOCK_KEYWORDS, "");
        return parseRules(raw);
    }

    /** 解析存储串:先试 JSON 数组,失败再按旧逗号分隔字符串兜底 */
    public static List<BlockRule> parseRules(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        try {
            List<BlockRule> rules = GSON.fromJson(raw, RULE_LIST_TYPE);
            if (rules != null) {
                // 清洗:去掉空名称/空关键词的规则,关键词去空
                List<BlockRule> cleaned = new ArrayList<>();
                for (BlockRule r : rules) {
                    if (r == null || TextUtils.isEmpty(r.name)) continue;
                    if (r.keywords == null || r.keywords.isEmpty()) continue;
                    List<String> words = new ArrayList<>();
                    for (String k : r.keywords) {
                        if (!TextUtils.isEmpty(k) && !k.trim().isEmpty()) words.add(k.trim());
                    }
                    if (!words.isEmpty()) {
                        r.keywords = words;
                        cleaned.add(r);
                    }
                }
                return cleaned;
            }
        } catch (JsonSyntaxException ignored) {
            // 不是 JSON,落到旧格式
        }
        // 旧格式:逗号分隔的关键词,名称取"关键词屏蔽"(旧设置页里存的就是这种)
        List<String> words = new ArrayList<>();
        for (String k : raw.split("[,\uFF0C\n\u3001;； ]")) {
            String t = k.trim();
            if (!t.isEmpty()) words.add(t);
        }
        if (words.isEmpty()) return new ArrayList<>();
        BlockRule legacy = new BlockRule("关键词屏蔽", words);
        return Collections.singletonList(legacy);
    }

    /** 落盘:保存为 JSON 数组字符串 */
    public static void save(List<BlockRule> rules) {
        if (rules == null || rules.isEmpty()) {
            KV.put(HawkConfig.SEARCH_BLOCK_KEYWORDS, "");
            return;
        }
        KV.put(HawkConfig.SEARCH_BLOCK_KEYWORDS, GSON.toJson(rules));
    }

    /** 全部规则的平铺关键词 */
    public static List<String> allKeywords(List<BlockRule> rules) {
        List<String> out = new ArrayList<>();
        for (BlockRule r : rules) {
            if (r.keywords != null) out.addAll(r.keywords);
        }
        return out;
    }

    /** 文本是否命中任一规则的关键词 */
    public static boolean isBlocked(String text, List<BlockRule> rules) {
        if (TextUtils.isEmpty(text) || rules == null || rules.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (BlockRule r : rules) {
            if (r.keywords == null) continue;
            for (String k : r.keywords) {
                if (!TextUtils.isEmpty(k) && lower.contains(k.toLowerCase())) return true;
            }
        }
        return false;
    }

    /** 便捷入口:读配置后判断文本是否命中 */
    public static boolean isBlocked(String text) {
        return isBlocked(text, load());
    }
}
