package com.github.tvbox.osc.util.parser;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ParseResultCache —— 解析结果短缓存。
 *
 * 只缓存"直出快速路"命中的结果,TTL 故意做得很短(90s):
 * 解析地址常带时效签名,缓存太久会出现"地址还在、放不了"的玄学问题,
 * 而 90s 足够覆盖用户最需要秒开的两个场景 —— 同一集里拖动/重试、切集再切回来。
 *
 * LRU + 容量上限,内存占用有界;所有操作容忍并发,不会抛。
 */
public final class ParseResultCache {

    /** 存活时间:90 秒 */
    private static final long TTL_MS = 90 * 1000L;
    /** 容量上限 */
    private static final int MAX_ENTRIES = 40;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Entry> CACHE =
            new LinkedHashMap<String, Entry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, ParseResultCache.Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private static final class Entry {
        final String json;
        final long expireAt;

        Entry(String json, long expireAt) {
            this.json = json;
            this.expireAt = expireAt;
        }
    }

    private ParseResultCache() {
    }

    private static String key(String flag, String url) {
        return (flag == null ? "" : flag) + "|" + (url == null ? "" : url);
    }

    /** 命中且未过期则返回一份新对象(调用方可能会往里塞字段,不能给共享实例) */
    public static JSONObject get(String flag, String url) {
        try {
            synchronized (LOCK) {
                Entry e = CACHE.get(key(flag, url));
                if (e == null) return null;
                if (System.currentTimeMillis() > e.expireAt) {
                    CACHE.remove(key(flag, url));
                    return null;
                }
                return new JSONObject(e.json);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void put(String flag, String url, JSONObject result) {
        if (result == null) return;
        try {
            synchronized (LOCK) {
                CACHE.put(key(flag, url), new Entry(result.toString(), System.currentTimeMillis() + TTL_MS));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 主动作废(例如用户点"重试"想换个解析站时) */
    public static void invalidate(String flag, String url) {
        try {
            synchronized (LOCK) {
                CACHE.remove(key(flag, url));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clear() {
        try {
            synchronized (LOCK) {
                CACHE.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清理过期项(切集时顺手调用,避免靠容量被动淘汰) */
    public static void purgeExpired() {
        try {
            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
                while (it.hasNext()) {
                    if (now > it.next().getValue().expireAt) it.remove();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
