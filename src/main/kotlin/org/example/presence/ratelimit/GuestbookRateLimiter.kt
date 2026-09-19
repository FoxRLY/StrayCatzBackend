package org.example.ratelimit

import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Простейший rate limiter в памяти процесса: не более [maxRequests] за [windowSeconds]
 * на ключ (например, handle автора). Для многоузлового деплоя это нужно будет
 * заменить на Redis/что-то shared — здесь только базовая защита для одного инстанса.
 */
@ApplicationScoped
class GuestbookRateLimiter(
    private val maxRequests: Int = 5,
    private val windowSeconds: Long = 60
) {

    private data class Bucket(var windowStart: Instant, var count: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** true — запрос разрешён и учтён, false — лимит исчерпан. */
    fun tryAcquire(key: String): Boolean {
        val now = Instant.now()
        val bucket = buckets.compute(key) { _, existing ->
            if (existing == null || existing.windowStart.plusSeconds(windowSeconds).isBefore(now)) {
                Bucket(windowStart = now, count = 1)
            } else {
                existing.count += 1
                existing
            }
        }!!
        return bucket.count <= maxRequests
    }
}
