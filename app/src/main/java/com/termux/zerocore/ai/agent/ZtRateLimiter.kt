package com.termux.zerocore.ai.agent

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 客户端节流：令牌桶限速 + 并发闸门 + 熔断。
 *
 * 限速放在客户端而不是等 429，是因为稳态限流（比如 RPM 10）下收到 429 时
 * 那一分钟已经废了，退避只是把损失摊平；本地排队能让人几乎看不到 429。
 *
 * 熔断是给"越限流越重试、越重试越限流"兜底的：连续失败到阈值就整段跳过，
 * 而不是每个请求都去撞一次墙。
 */
class ZtRateLimiter(private val profile: ZtEndpointProfile) {

    private val lock = Any()

    private val capacity = profile.burst.toDouble().coerceAtLeast(1.0)
    private val ratePerSec = profile.rpm / 60.0

    @Volatile
    private var tokens = capacity

    @Volatile
    private var lastRefillNanos = System.nanoTime()

    private val slots = Semaphore(profile.maxConcurrency.coerceAtLeast(1), true)

    @Volatile
    private var breakerUntilNanos = 0L

    private val consecutiveFailures = AtomicInteger(0)

    private val breakerThreshold = 5
    private val breakerOpenMs = 30_000L

    /** 档案变了就要换实例，否则旧的令牌桶速率还在沿用。 */
    fun matches(other: ZtEndpointProfile): Boolean {
        return other.rpm == profile.rpm && other.maxConcurrency == profile.maxConcurrency
    }

    /** 熔断是否已打开。 */
    fun isBreakerOpen(): Boolean {
        val until = breakerUntilNanos
        return until > 0 && System.nanoTime() < until
    }

    /** 熔断剩余等待毫秒，0 表示没熔断。 */
    fun breakerRemainingMs(): Long {
        val until = breakerUntilNanos
        if (until <= 0) return 0
        val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
        return left.coerceAtLeast(0)
    }

    fun resetBreaker() {
        breakerUntilNanos = 0
        consecutiveFailures.set(0)
    }

    /**
     * 取一枚令牌，必要时阻塞等待。
     * @return false 表示超时或熔断，调用方应放弃而不是硬发。
     */
    fun acquire(timeoutMs: Long = ACQUIRE_TIMEOUT_MS): Boolean {
        if (isBreakerOpen()) return false
        if (!acquireSlot(timeoutMs)) return false
        val got = acquireToken(timeoutMs)
        if (!got) slots.release()
        return got
    }

    /** 请求结束必须调用，归还并发位。 */
    fun release() {
        slots.release()
    }

    fun onSuccess() {
        consecutiveFailures.set(0)
        breakerUntilNanos = 0
    }

    /**
     * 记录一次失败。429 / 5xx 计入熔断；401 / 404 是配置问题，
     * 重试没意义，也不该把整个端点熔断掉。
     */
    fun onFailure(httpCode: Int) {
        when (httpCode) {
            401, 403, 404, 400, 422 -> return
        }
        val n = consecutiveFailures.incrementAndGet()
        if (n >= breakerThreshold) {
            breakerUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(breakerOpenMs)
            android.util.Log.w(TAG, "breaker open for ${breakerOpenMs}ms after $n failures")
        }
    }

    /** 退避时长：指数增长 + 全抖动，避免一批请求整齐地一起重试。 */
    fun backoffMs(attempt: Int): Long {
        val upper = minOf(CAP_MS, BASE_MS shl attempt.coerceAtMost(6))
        return (Math.random() * upper).toLong().coerceAtLeast(MIN_MS)
    }

    private fun acquireSlot(timeoutMs: Long): Boolean {
        return try {
            slots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun acquireToken(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val waitMs = synchronized(lock) {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return true
                }
                val deficit = 1.0 - tokens
                val ms = (deficit / ratePerSec * 1000.0).toLong()
                ms.coerceAtLeast(MIN_WAIT_MS).coerceAtMost(MAX_WAIT_MS)
            }
            if (System.nanoTime() >= deadline) return false
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            try {
                Thread.sleep(waitMs.coerceAtMost(remaining.coerceAtLeast(1)))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0
        if (elapsedSec <= 0) return
        lastRefillNanos = now
        tokens = (tokens + elapsedSec * ratePerSec).coerceAtMost(capacity)
    }

    companion object {
        private const val TAG = "ZtRateLimiter"

        private const val BASE_MS = 1_000L
        private const val CAP_MS = 60_000L
        private const val MIN_MS = 200L

        private const val MIN_WAIT_MS = 20L
        private const val MAX_WAIT_MS = 5_000L
        private const val ACQUIRE_TIMEOUT_MS = 120_000L
    }
}
