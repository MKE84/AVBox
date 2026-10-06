package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.tvbox.osc.BuildConfig;

import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局接口日志:记录 py / js / jar 三类爬虫与普通接口的调用状况(成功 / 失败 / 耗时)。
 *
 * <p>设计要点:
 * <ul>
 *   <li>release 也编译,是否记录由 {@link HawkConfig#API_LOG_ENABLED} 开关控制(默认关)。</li>
 *   <li>内存环形缓冲(最近 {@link #MEMORY_CAPACITY} 条)供日志页秒开;落盘文件按大小滚动。</li>
 *   <li>任何异常都在内部吞掉:日志组件自身绝不能影响主流程。</li>
 * </ul>
 */
public final class ApiLog {

    /** 接口种类 */
    public static final String KIND_PY = "PY";
    public static final String KIND_JS = "JS";
    public static final String KIND_JAR = "JAR";
    public static final String KIND_API = "API";

    /** 结果 */
    public static final String OK = "OK";
    public static final String FAIL = "FAIL";

    private static final int MEMORY_CAPACITY = 500;
    private static final long FILE_MAX_BYTES = 2L * 1024 * 1024; // 2MB 滚动
    private static final String FILE_NAME = "api_log.txt";
    private static final String SEP = " | ";

    private static final ArrayDeque<String> memoryLines = new ArrayDeque<>();
    private static ExecutorService fileExecutor;
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private ApiLog() {
    }

    /** 开关是否打开(读 KV;任何异常按关闭处理) */
    public static boolean enabled() {
        try {
            return KV.get(HawkConfig.API_LOG_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(boolean on) {
        try {
            KV.put(HawkConfig.API_LOG_ENABLED, on);
            record(KIND_API, OK, "logger", on ? "接口日志已开启" : "接口日志已关闭", 0);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 记录入口 ====================

    /** 记录一次成功的接口动作 */
    public static void ok(String kind, String source, String action, long costMs) {
        if (!enabled()) return;
        record(kind, OK, source, action, costMs);
    }

    /** 记录一次失败的接口动作 */
    public static void fail(String kind, String source, String action, String error, long costMs) {
        if (!enabled()) return;
        String detail = TextUtils.isEmpty(error) ? action : action + " :: " + error;
        record(kind, FAIL, source, detail, costMs);
    }

    /** 记录失败(无耗时) */
    public static void fail(String kind, String source, String action, String error) {
        fail(kind, source, action, error, 0);
    }

    private static void record(String kind, String result, String source, String action, long costMs) {
        try {
            String line = TIME_FMT.format(new Date()) + SEP
                    + kind + SEP
                    + result + SEP
                    + (TextUtils.isEmpty(source) ? "-" : source) + SEP
                    + (TextUtils.isEmpty(action) ? "-" : action)
                    + (costMs > 0 ? SEP + costMs + "ms" : "");
            synchronized (memoryLines) {
                if (memoryLines.size() >= MEMORY_CAPACITY) memoryLines.pollFirst();
                memoryLines.addLast(line);
            }
            appendFile(line, result);
        } catch (Throwable ignored) {
            // 日志组件自身不得抛出:静默丢弃
        }
    }

    /** 依据 api 地址判断种类 */
    public static String kindOf(String apiOrJar) {
        String s = apiOrJar == null ? "" : apiOrJar.toLowerCase(Locale.US);
        if (s.contains(".py")) return KIND_PY;
        if (s.contains(".js")) return KIND_JS;
        if (s.contains(".jar")) return KIND_JAR;
        return KIND_API;
    }

    // ==================== 读取 / 清理 ====================

    /** 内存中的最近日志(倒序:最新在前) */
    public static List<String> recent() {
        synchronized (memoryLines) {
            List<String> out = new ArrayList<>(memoryLines);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    /** 磁盘日志文件(供导出 / 分享) */
    public static File file() {
        try {
            return new File(AppContextHolder.context().getFilesDir(), FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 清空内存与磁盘日志 */
    public static void clear() {
        synchronized (memoryLines) {
            memoryLines.clear();
        }
        try {
            File f = file();
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 落盘(滚动) ====================

    private static void appendFile(final String line, final String result) {
        // 只落盘 ERROR 级别或总开关打开时的全部?——需求是"记正常和错误",故全部落盘(受总开关约束)
        synchronized (ApiLog.class) {
            if (fileExecutor == null) {
                fileExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "api-log");
                    t.setDaemon(true);
                    return t;
                });
            }
        }
        try {
            fileExecutor.execute(() -> writeLine(line));
        } catch (Throwable ignored) {
        }
    }

    private static void writeLine(String line) {
        RandomAccessFile raf = null;
        try {
            File f = file();
            if (f == null) return;
            if (f.exists() && f.length() > FILE_MAX_BYTES) {
                // 简单滚动:超过上限就清空重来(避免无限增长;接口日志用于近端排查)
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            raf = new RandomAccessFile(f, "rw");
            raf.seek(raf.length());
            raf.write((line + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // 落盘失败即放弃
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}