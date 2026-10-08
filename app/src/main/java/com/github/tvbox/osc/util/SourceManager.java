package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * 源管理：用户手动「隐藏」的展开源(订阅里解析出的 py/js/jar…)与「最近删除」记录。
 * <p>核心诉求：源失效后用户删掉它，哪怕重新拉订阅，也不该再「复活」。
 * 所以把所有被删除(隐藏)的源 key 持久到 {@link HawkConfig#HIDDEN_SOURCES}，
 * 每次解析订阅源列表时按它过滤。恢复 = 移出该集合(下次刷新自然回来)，同时进「最近删除」。
 * 全局 single-use 单例，线程安全(方法内同步)。
 */
public final class SourceManager {

    private static final SourceManager INSTANCE = new SourceManager();

    public static SourceManager get() {
        return INSTANCE;
    }

    /** 读取当前所有被隐藏(删除)的源 key */
    public synchronized ArrayList<String> hiddenKeys() {
        try {
            return new ArrayList<>(KV.get(HawkConfig.HIDDEN_SOURCES, new ArrayList<String>()));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** 判断某个源 key 是否已被隐藏 */
    public synchronized boolean isHidden(String key) {
        if (key == null || key.isEmpty()) return false;
        for (String k : hiddenKeys()) if (k.equals(key)) return true;
        return false;
    }

    /** 隐藏一个源(删除)，并追加到「最近删除」。纯函数式写回，异常吞掉不影响主流程。 */
    public synchronized void hide(SourceBean sb) {
        if (sb == null || sb.getKey() == null || sb.getKey().isEmpty()) return;
        try {
            String name = sb.getName() == null || sb.getName().isEmpty() ? sb.getKey() : sb.getName();
            // 写隐藏集合(去重)
            ArrayList<String> hidden = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>(hiddenKeys());
            seen.add(sb.getKey());
            hidden.addAll(seen);
            KV.put(HawkConfig.HIDDEN_SOURCES, hidden);
            // 写最近删除(最前,去重,最多保留 60 条)
            ArrayList<String> recent = new ArrayList<>();
            ArrayList<String> old = KV.get(HawkConfig.RECENT_DELETED_SOURCES, new ArrayList<String>());
            recent.add(name + "\t" + sb.getKey());
            for (String item : old) {
                if (recent.size() >= 60) break;
                if (!item.endsWith("\t" + sb.getKey())) recent.add(item);
            }
            KV.put(HawkConfig.RECENT_DELETED_SOURCES, recent);
        } catch (Throwable ignored) {
        }
    }

    /** 恢复一个被隐藏(删除)的源：移出隐藏集合，即下次刷新订阅会重新出现 */
    public synchronized void restore(String key) {
        if (key == null || key.isEmpty()) return;
        try {
            ArrayList<String> hidden = new ArrayList<>();
            for (String k : hiddenKeys()) if (!k.equals(key)) hidden.add(k);
            KV.put(HawkConfig.HIDDEN_SOURCES, hidden);
            // 顺带从最近删除里移除该记录
            ArrayList<String> recent = new ArrayList<>();
            for (String item : KV.get(HawkConfig.RECENT_DELETED_SOURCES, new ArrayList<String>())) {
                if (!item.endsWith("\t" + key)) recent.add(item);
            }
            KV.put(HawkConfig.RECENT_DELETED_SOURCES, recent);
        } catch (Throwable ignored) {
        }
    }

    /** 「最近删除」列表条目(每条为 "名字\tkey") */
    public synchronized ArrayList<String> recentDeleted() {
        try {
            return new ArrayList<>(KV.get(HawkConfig.RECENT_DELETED_SOURCES, new ArrayList<String>()));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** 从「最近删除」里移除一条记录(仅清列表，不改变隐藏状态；恢复请用 {@link #restore(String)}) */
    public synchronized void clearRecent(String key) {
        if (key == null || key.isEmpty()) return;
        try {
            ArrayList<String> recent = new ArrayList<>();
            for (String item : KV.get(HawkConfig.RECENT_DELETED_SOURCES, new ArrayList<String>())) {
                if (!item.endsWith("\t" + key)) recent.add(item);
            }
            KV.put(HawkConfig.RECENT_DELETED_SOURCES, recent);
        } catch (Throwable ignored) {
        }
    }

    /** 清空「最近删除」列表(仅清记录，不影响隐藏黑名单；隐藏源不会因此复活) */
    public synchronized void clearRecentAll() {
        try {
            KV.put(HawkConfig.RECENT_DELETED_SOURCES, new ArrayList<String>());
        } catch (Throwable ignored) {
        }
    }

    /** 清空全部隐藏与最近删除记录(整仓重置时使用) */
    public synchronized void clearAll() {
        try {
            KV.put(HawkConfig.HIDDEN_SOURCES, new ArrayList<String>());
            KV.put(HawkConfig.RECENT_DELETED_SOURCES, new ArrayList<String>());
        } catch (Throwable ignored) {
        }
    }

    private SourceManager() {
    }
}