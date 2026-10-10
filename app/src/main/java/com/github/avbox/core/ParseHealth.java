package com.github.avbox.core;

import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ParseHealth —— 解析站健康度 / 熔断 / 竞速排序。
 *
 * 背景:配置里的解析站常有三四十个,其中总有四五个是长期挂掉或要 5s+ 才吐页面的。
 * 原来的做法是"每次把所有站都平等地打一遍",慢站每次都要把整体等待时间拖到超时上限,
 * 表现就是"正在嗅探播放地址"转很久。
 *
 * 本类做三件事:
 *   1) 记分 —— 每个解析站累计成功/失败次数、失败连击、平均耗时;
 *   2) 熔断 —— 连击失败到阈值即冷落一段时间,期间不再被优先发起(冷落期结束自动复活,
 *      避免"一次网络抖动就把好站永久拉黑");
 *   3) 排序 —— sort() 把"快的、稳的"排前面,竞速时能更快撞到可用结果,
 *      同时让"上次在这里赢过的站"前置(命中率高的站大概率还会赢)。
 *
 * 全部状态在内存里,并顺带落一份到 KV(进程重启后仍能继承"哪个站快"的记忆)。
 * 任何一步失败都静默降级为"无记忆",绝不影响解析主流程。
 */
public final class ParseHealth {

    /** 触发熔断的连续失败次数 */
    private static final int FAIL_LIMIT = 3;
    /** 熔断冷落时长(到点自动复活) */
    private static final long COOLDOWN_MS = 3 * 60 * 1000L;
    /** 平均耗时只做滑动平均,老数据权重随时间衰减,避免"一次抽风背一辈子" */
    private static final float EWMA = 0.45f;
    /** 单个站的耗时上限:超过这个值记分时按此封顶,防止一次 20s 卡死把均分彻底带偏 */
    private static final long COST_CAP_MS = 12_000L;

    private static final String KV_KEY = "cache_parse_health_v1";
    private static final int KV_MAX_ENTRIES = 120;

    private static final ConcurrentHashMap<String, Stat> STATS = new ConcurrentHashMap<>();
    private static volatile boolean restored = false;
    private static final Object RESTORE_LOCK = new Object();

    /** 每个站一份状态 */
    public static final class Stat {
        public int ok;
        public int fail;
        public int streakFail;
        public long avgMs = -1L;
        public long cooldownUntil;
        public long lastUseAt;
        public String lastWinnerFlag = "";

        public boolean cooling(long now) {
            return now < cooldownUntil;
        }
    }

    private ParseHealth() {
    }

    /** 记一次结果。costMs 为本次实际耗时(毫秒) */
    public static void report(String name, boolean success, long costMs) {
        if (name == null || name.isEmpty()) return;
        try {
            long now = System.currentTimeMillis();
            Stat s = STATS.get(name);
            if (s == null) {
                Stat created = new Stat();
                Stat prev = STATS.putIfAbsent(name, created);
                s = prev != null ? prev : created;
            }
            synchronized (s) {
                s.lastUseAt = now;
                long cost = costMs <= 0 ? 0 : Math.min(costMs, COST_CAP_MS);
                if (success) {
                    s.ok++;
                    s.streakFail = 0;
                    s.cooldownUntil = 0L;
                    s.avgMs = s.avgMs < 0 ? cost : (long) (s.avgMs * (1 - EWMA) + cost * EWMA);
                } else {
                    s.fail++;
                    s.streakFail++;
                    if (s.streakFail >= FAIL_LIMIT) {
                        s.cooldownUntil = now + COOLDOWN_MS;
                        // 冷落生效时把连击清零:冷落期满若再失败,需要重新攒够连击才二次熔断
                        s.streakFail = 0;
                    }
                }
            }
            scheduleSave();
        } catch (Throwable th) {
            LOG.d("ParseHealth", "report failed: " + th);
        }
    }

    /** 记录"某个站点在本站点的解析里赢了",排序时优先复用 */
    public static void markWinner(String name, String flag) {
        if (name == null || name.isEmpty()) return;
        try {
            Stat s = STATS.get(name);
            if (s == null) {
                Stat created = new Stat();
                Stat prev = STATS.putIfAbsent(name, created);
                s = prev != null ? prev : created;
            }
            synchronized (s) {
                s.lastWinnerFlag = flag == null ? "" : flag;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 该站是否处于熔断冷落期 */
    public static boolean isCooling(String name) {
        if (name == null || name.isEmpty()) return false;
        try {
            Stat s = STATS.get(name);
            if (s == null) return false;
            synchronized (s) {
                return s.cooling(System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 评分,越小越优先。没记录的站给中间值(既不迷信也不歧视) */
    public static long score(String name, String preferFlag) {
        final long neutral = 600L;
        try {
            Stat s = name == null ? null : STATS.get(name);
            if (s == null) return neutral;
            synchronized (s) {
                long now = System.currentTimeMillis();
                if (s.cooling(now)) return 1_000_000L + (s.cooldownUntil - now);
                long base = s.avgMs < 0 ? neutral : Math.min(s.avgMs, COST_CAP_MS);
                // 败绩惩罚:失败多的站往后放,但不至于饿死(失败几次后还能被重新验证)
                base += (long) s.fail * 120L;
                if (s.streakFail > 0) base += (long) s.streakFail * 400L;
                // 在这个站点赢过的站大幅前置
                if (preferFlag != null && preferFlag.equals(s.lastWinnerFlag)) base -= 350L;
                return base;
            }
        } catch (Throwable ignored) {
            return neutral;
        }
    }

    /**
     * 按健康度排序(稳定排序:同分保持配置里的原顺序 —— 配置顺序本身是维护者的偏好)
     */
    public static List<String> sort(List<String> names) {
        return sort(names, null);
    }

    public static List<String> sort(List<String> names, final String preferFlag) {
        if (names == null || names.size() <= 1) return names;
        try {
            List<String> copy = new ArrayList<>(names);
            Collections.sort(copy, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    long sa = score(a, preferFlag);
                    long sb = score(b, preferFlag);
                    return sa == sb ? 0 : (sa < sb ? -1 : 1);
                }
            });
            return copy;
        } catch (Throwable ignored) {
            return names;
        }
    }

    /** 解析前的预筛:摘掉正在熔断的站(全被熔断时保持原样,宁可试也别空手) */
    public static List<String> filterCooling(List<String> names) {
        if (names == null || names.isEmpty()) return names;
        try {
            List<String> alive = new ArrayList<>(names.size());
            for (String n : names) {
                if (!isCooling(n)) alive.add(n);
            }
            return alive.isEmpty() ? names : alive;
        } catch (Throwable ignored) {
            return names;
        }
    }

    public static boolean hasCooling(String[] names) {
        if (names == null) return false;
        for (String n : names) {
            if (isCooling(n)) return true;
        }
        return false;
    }

    // ==================== 持久化(可选,失败静默) ====================

    private static volatile boolean saveScheduled = false;

    /**
     * 合并式落盘:单位时间内只写一次。
     * 解析是高频路径,每个站一次 KV 写入会把 IO 打满,所以改成"攒一下再写"。
     * 落盘放在独立线程,不占用解析线程。
     */
    private static void scheduleSave() {
        if (saveScheduled) return;
        saveScheduled = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    save();
                } catch (Throwable ignored) {
                } finally {
                    saveScheduled = false;
                }
            }
        }, "parse-health-save");
        t.setDaemon(true);
        t.start();
    }

    private static void save() {
        try {
            JSONObject root = new JSONObject();
            int written = 0;
            for (Map.Entry<String, Stat> e : STATS.entrySet()) {
                if (written >= KV_MAX_ENTRIES) break;
                Stat s = e.getValue();
                JSONObject o = new JSONObject();
                synchronized (s) {
                    o.put("ok", s.ok);
                    o.put("fail", s.fail);
                    o.put("sf", s.streakFail);
                    o.put("avg", s.avgMs);
                    o.put("cd", s.cooldownUntil);
                    o.put("w", s.lastWinnerFlag);
                }
                root.put(e.getKey(), o);
                written++;
            }
            KV.put(KV_KEY, root.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void restore() {
        if (restored) return;
        synchronized (RESTORE_LOCK) {
            if (restored) return;
            restored = true;
            try {
                String raw = KV.get(KV_KEY, "");
                if (raw == null || raw.isEmpty()) return;
                JSONObject root = new JSONObject(raw);
                java.util.Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    JSONObject o = root.optJSONObject(name);
                    if (o == null) continue;
                    Stat s = new Stat();
                    s.ok = o.optInt("ok", 0);
                    s.fail = o.optInt("fail", 0);
                    s.streakFail = o.optInt("sf", 0);
                    s.avgMs = o.optLong("avg", -1L);
                    s.cooldownUntil = o.optLong("cd", 0L);
                    s.lastWinnerFlag = o.optString("w", "");
                    STATS.put(name, s);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 幂等;首次真正用到健康度时才读盘,不给启动加负担 */
    public static void ensureRestored() {
        if (!restored) restore();
    }

    /** 一键重置(排障/换源后想抹掉旧记忆时用) */
    public static void reset() {
        STATS.clear();
        try {
            KV.put(KV_KEY, "");
        } catch (Throwable ignored) {
        }
    }
}
