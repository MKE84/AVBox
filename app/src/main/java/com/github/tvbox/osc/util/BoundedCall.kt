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
 */
object BoundedCall {

    @JvmStatic
    fun <T> call(task: Callable<T>, timeoutMs: Long, tag: String): T? {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bounded-call") }
        val future = executor.submit(task)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            LOG.i("$tag-timeout(${timeoutMs}ms)")
            future.cancel(true)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.i("$tag-interrupted")
            null
        } catch (e: Exception) {
            LOG.e("BoundedCall", "$tag-error: ${e.cause ?: e}", e.cause ?: e)
            null
        } finally {
            executor.shutdown()
        }
    }
}