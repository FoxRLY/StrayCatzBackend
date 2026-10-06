package org.example.service

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Просмотры записей копятся в памяти ноды и раз в 10 секунд уходят в базу
 * одним update на запись. Без этого вирусная запись с тысячами просмотров
 * в минуту превращала свою строку post в горячую точку блокировок.
 */
@ApplicationScoped
class ViewCounter(private val em: EntityManager) {
    private val pending = ConcurrentHashMap<UUID, AtomicLong>()

    fun add(postId: UUID) {
        pending.computeIfAbsent(postId) { AtomicLong() }.incrementAndGet()
    }

    /** Ещё не записанные просмотры этой ноды (чтобы порог «ИИ слоп» считался честно). */
    fun pendingFor(postId: UUID): Long = pending[postId]?.get() ?: 0

    @Transactional
    fun flush() {
        val batch = pending.keys.toList()
        batch.forEach { id ->
            val n = pending.remove(id)?.get() ?: return@forEach
            if (n > 0) {
                em.createNativeQuery("update post set views = views + ?2 where id = ?1")
                    .setParameter(1, id).setParameter(2, n).executeUpdate()
            }
        }
    }
}

@ApplicationScoped
class ViewCounterJob(private val views: ViewCounter) {
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun flush() = views.flush()
}
