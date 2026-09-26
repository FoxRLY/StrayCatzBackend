package org.example.registry

import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Состояние одного соединения (одной вкладки) на ЭТОЙ ноде.
 * Ключ — connection.id().
 */
class ConnState(
    val connectionId: String,
    val userId: UUID,
    val username: String,
) {
    /** Беседы, открытые на экране (chat.open) — сюда летит typing. */
    val openChats: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Кэш членства, чтобы не ходить в БД на каждый кадр. Сбрасывается на hello. */
    val memberChats: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 10 message.send в секунду на соединение. */
    val sendBucket = RateBucket(capacity = 10, refillPerSecond = 10)
}

@ApplicationScoped
class ConnectionRegistry {
    private val byConnectionId = ConcurrentHashMap<String, ConnState>()
    private val byUser = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun put(state: ConnState) {
        byConnectionId[state.connectionId] = state
        byUser.computeIfAbsent(state.userId) { ConcurrentHashMap.newKeySet() }.add(state.connectionId)
    }

    fun get(connectionId: String): ConnState? = byConnectionId[connectionId]

    /** @return сколько соединений этого пользователя осталось на этой ноде. */
    fun remove(connectionId: String): Int {
        val st = byConnectionId.remove(connectionId) ?: return 0
        var left = 0
        byUser.computeIfPresent(st.userId) { _, set ->
            set.remove(connectionId)
            left = set.size
            if (set.isEmpty()) null else set
        }
        return left
    }

    fun connectionCount(userId: UUID): Int = byUser[userId]?.size ?: 0

    fun connectionIdsOf(userId: UUID): Set<String> = byUser[userId]?.toSet() ?: emptySet()

    fun all(): Collection<ConnState> = byConnectionId.values
}

/** Простой token bucket без внешних зависимостей. */
class RateBucket(private val capacity: Int, private val refillPerSecond: Int) {
    private val tokens = AtomicLong(capacity.toLong())
    private val lastRefill = AtomicLong(System.nanoTime())

    fun tryTake(): Boolean {
        refill()
        while (true) {
            val cur = tokens.get()
            if (cur <= 0) return false
            if (tokens.compareAndSet(cur, cur - 1)) return true
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val prev = lastRefill.get()
        val add = ((now - prev) / 1_000_000_000.0 * refillPerSecond).toLong()
        if (add > 0 && lastRefill.compareAndSet(prev, now)) {
            tokens.updateAndGet { minOf(capacity.toLong(), it + add) }
        }
    }
}
