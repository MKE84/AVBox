package com.github.avbox.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlayCore —— 播放解析的"可真正超时"调度核心。
 *
 * 背景:现有 PlayUrlResolver 里 jsonExtMix / SuperParse.parse 是反射调用 jar 内的
 * 阻塞爬虫(com.github.catvod.parser.Mix*),一旦挂起会长期占用线程;stopParse() 的
 * shutdown() 并不会中断已运行任务,导致线程堆积 → "正在嗅探播放地址"长时间卡死、
 * 偶发假死。
 *
 * 本类的职责:给任何阻塞解析任务套一个真正的墙钟超时,超时即返回 null 并让上层
 * 走自动切换。线程用受限的共享池(不每次 new),杜绝泄漏。
 */
public final class PlayCore {

    /** 共享解析池:固定 3 条,队列有界;不每次 new,避免线程堆积 */
    private static final int POOL_SIZE = 3;
    private static final int QUEUE_SIZE = 16;

    private static volatile ExecutorService pool;

    private static ExecutorService pool() {
        ExecutorService p = pool;
        if (p == null) {
            synchronized (PlayCore.class) {
                p = pool;
                if (p == null) {
                    final AtomicInteger seq = new AtomicInteger(1);
                    ThreadFactory tf = r -> {
                        Thread t = new Thread(r, "play-core-" + seq.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    };
                    p = new ThreadPoolExecutor(
                            POOL_SIZE, POOL_SIZE,
                            30L, TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(QUEUE_SIZE),
                            tf,
                            new ThreadPoolExecutor.DiscardOldestPolicy());
                    pool = p;
                }
            }
        }
        return p;
    }

    /**
     * 在共享池上执行阻塞任务,最多等待 timeoutMs;超时返回 null(并让上层切换解析器)。
     *
     * 注意:Java 无法强杀阻塞线程,故这里只保证"调用方不再等待",线程随底层
     * 网络读写超时自行退出(BoundedHttp 已设 readTimeout)。相比原实现,超时后
     * 调用方立即返回,不再无限转圈。
     */
    public static <T> T callWithTimeout(Callable<T> task, long timeoutMs, String tag) {
        Future<T> future = null;
        try {
            future = pool().submit(task);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            if (future != null) future.cancel(true);
            return null;
        } catch (Throwable e) {
            if (future != null) future.cancel(true);
            return null;
        }
    }

    /** 取消所有排队中(尚未开始)的任务;正在跑的任务靠自身网络超时退出 */
    public static void purge() {
        try {
            ExecutorService p = pool;
            if (p instanceof ThreadPoolExecutor) {
                ((ThreadPoolExecutor) p).getQueue().clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 在共享池上执行任务(fire-and-forget)。
     * 用于替代 PlayUrlResolver 里每次 new 的 newSingleThreadExecutor,避免线程泄漏堆积。
     */
    public static void execute(Runnable task) {
        try {
            pool().execute(task);
        } catch (Throwable ignored) {
        }
    }
}