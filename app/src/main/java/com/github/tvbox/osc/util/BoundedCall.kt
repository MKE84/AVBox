package com.github.tvbox.osc.util

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 爬虫调用有界化:任务放到一次性线程上跑,超时就返回 null。
 *
 * 爬虫普遍不响应 interrupt,所以超时只作废本次结果,任务线程跑完自然回收;
 * 调用方必须把 null 当"无结果"处理,不能假定任务已停止。
 *
 * 同时这里也是**全局接口日志的统一埋点**:所有 py/js/jar 爬虫调用都经过本方法,
 * 在一处即可记录动作、所属源、耗时与成功/失败(tag 形如 `echo--getDetail--<源key>`)。
 *
 * 性能约定:日志关闭时热路径只多一次 volatile 布尔读,不做任何字符串解析。
 */
object BoundedCall {

    @JvmStatic
    fun <T> call(task: Callable<T>, timeoutMs: Long, tag: String): T? {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bounded-call") }
        val future = executor.submit(task)
        // 只在日志开启时才取时间戳与解析 tag,关闭时这条路径零额外开销
        val logOn = ApiLog.enabled()
        val startMs = if (logOn) System.currentTimeMillis() else 0L
        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (logOn) {
                val cost = System.currentTimeMillis() - startMs
                if (result == null) {
                    ApiLog.fail(kindOf(tag), sourceOf(tag), actionOf(tag), "返回空", cost)
                } else {
                    ApiLog.ok(kindOf(tag), sourceOf(tag), actionOf(tag), cost)
                }
            }
            result
        } catch (e: TimeoutException) {
            LOG.i("$tag-timeout(${timeoutMs}ms)")
            if (logOn) {
                ApiLog.fail(kindOf(tag), sourceOf(tag), actionOf(tag), "超时(${timeoutMs}ms)", System.currentTimeMillis() - startMs)
            }
            future.cancel(true)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.i("$tag-interrupted")
            if (logOn) {
                ApiLog.fail(kindOf(tag), sourceOf(tag), actionOf(tag), "被中断", System.currentTimeMillis() - startMs)
            }
            null
        } catch (e: Exception) {
            LOG.e("BoundedCall", "$tag-error: ${e.cause ?: e}", e.cause ?: e)
            if (logOn) {
                ApiLog.fail(kindOf(tag), sourceOf(tag), actionOf(tag), "${e.cause ?: e}", System.currentTimeMillis() - startMs)
            }
            null
        } finally {
            executor.shutdown()
        }
    }

    /** tag 形如 `echo--getDetail--<源key>`:取出中段作为动作名 */
    private fun partsOf(tag: String?): List<String> {
        val t = tag ?: return emptyList()
        return t.split("--", "-").filter { it.isNotBlank() && it != "echo" }
    }

    private fun actionOf(tag: String?): String = partsOf(tag).firstOrNull() ?: "call"

    /** tag 尾段通常是源 key */
    private fun sourceOf(tag: String?): String {
        val parts = partsOf(tag)
        return if (parts.size >= 2) parts[parts.size - 1] else "-"
    }

    /** 由 SpiderLoader 装载时登记的源类型决定 py/js/jar;未登记按通用接口记 */
    private fun kindOf(tag: String?): String {
        return try {
            ApiLog.kindOfKey(sourceOf(tag))
        } catch (e: Throwable) {
            ApiLog.KIND_API
        }
    }
}
