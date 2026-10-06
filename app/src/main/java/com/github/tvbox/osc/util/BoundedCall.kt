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
 */
object BoundedCall {

    @JvmStatic
    fun <T> call(task: Callable<T>, timeoutMs: Long, tag: String): T? {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bounded-call") }
        val future = executor.submit(task)
        val startMs = System.currentTimeMillis()
        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val cost = System.currentTimeMillis() - startMs
            if (ApiLog.enabled()) {
                val action = actionOf(tag)
                if (result == null) {
                    ApiLog.fail(kindOf(tag), sourceOf(tag), action, "返回空", cost)
                } else {
                    ApiLog.ok(kindOf(tag), sourceOf(tag), action, cost)
                }
            }
            result
        } catch (e: TimeoutException) {
            LOG.i("$tag-timeout(${timeoutMs}ms)")
            ApiLog.fail(kindOf(tag), sourceOf(tag), actionOf(tag), "超时(${timeoutMs}ms)", System.currentTimeMillis() - startMs)
            future.cancel(true)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.i("$tag-interrupted")
            ApiLog.fail(kindOf(tag), sourceOf(tag), actionOf(tag), "被中断", System.currentTimeMillis() - startMs)
            null
        } catch (e: Exception) {
            LOG.e("BoundedCall", "$tag-error: ${e.cause ?: e}", e.cause ?: e)
            ApiLog.fail(kindOf(tag), sourceOf(tag), actionOf(tag), "${e.cause ?: e}", System.currentTimeMillis() - startMs)
            null
        } finally {
            executor.shutdown()
        }
    }

    /** tag 形如 `echo--getDetail--<源key>` 或 `echo-getDetail-key`:取出中段作为动作名 */
    private fun actionOf(tag: String?): String {
        val t = tag ?: return "call"
        // 去掉 echo 前缀与尾部的源 key,保留动作名(如 getDetail / getList / getSort / parse)
        val parts = t.split("--", "-").filter { it.isNotBlank() && it != "echo" }
        return parts.firstOrNull() ?: "call"
    }

    /** tag 尾段通常是源 key */
    private fun sourceOf(tag: String?): String {
        val t = tag ?: return "-"
        val parts = t.split("--", "-").filter { it.isNotBlank() && it != "echo" }
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