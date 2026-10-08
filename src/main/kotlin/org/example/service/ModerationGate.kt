package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Быстрая проверка «забанен / ограничен» для сокета (REST проверяет при разборе токена).
 * Кэш на ноде 10 с: после бана другие ноды узнают максимум через 10 с, а эта — сразу
 * ([forget]); живые соединения забаненного шина ещё и закрывает (EventBus.kickUser).
 */
@ApplicationScoped
class ModerationGate(private val em: EntityManager) {
    data class Status(val bannedUntil: Instant?, val restrictedUntil: Instant?, val restrictReason: String?) {
        val banned get() = bannedUntil?.isAfter(Instant.now()) == true
        val restricted get() = restrictedUntil?.isAfter(Instant.now()) == true
    }

    private class Entry(val at: Long, val status: Status)

    private val cache = ConcurrentHashMap<UUID, Entry>()

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun status(userId: UUID): Status {
        val now = System.currentTimeMillis()
        cache[userId]?.let { if (now - it.at < TTL_MS) return it.status }
        val row = (em.createNativeQuery("select banned_until, restricted_until, restrict_reason from users where id = ?1")
            .setParameter(1, userId).resultList as List<Array<Any?>>).firstOrNull()
        val st = Status(row?.get(0)?.let(::toInstant), row?.get(1)?.let(::toInstant), row?.get(2) as String?)
        if (cache.size > 200_000) cache.clear()
        cache[userId] = Entry(now, st)
        return st
    }

    fun forget(userId: UUID) {
        cache.remove(userId)
    }

    private fun toInstant(v: Any?): Instant? = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> null
    }

    companion object {
        const val TTL_MS = 10_000L
    }
}
