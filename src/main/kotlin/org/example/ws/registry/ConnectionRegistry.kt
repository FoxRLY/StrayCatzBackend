package org.example.ws.registry

import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Состояние одного соединения: userId/handle + какие чаты человек знает
 * (memberChats) и какие реально держит открытыми на экране (openChats).
 *
 * websockets-next даёт свой UserData/TypedKey механизм на самом
 * WebSocketConnection, но его API под generic-объекты не задокументирован
 * достаточно однозначно, чтобы на него полагаться не глядя в исходники
 * установленной версии. Поэтому состояние храним сами — в CDI-синглтоне
 * ConnectionRegistry, ключ — connection.id() (эта строка есть в API
 * стабильно и используется, например, в логах ошибок отправки).
 */
class ConnState(
    val userId: UUID,
    val handle: String,
) {
    val memberChats: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val openChats: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // token bucket на message.send, см. протокол: 10/сек на соединение
    val sendBucket = RateBucket(capacity = 10, refillPerSecond = 10)
}

@ApplicationScoped
class ConnectionRegistry {
    private val byConnectionId = ConcurrentHashMap<String, ConnState>()

    fun put(connectionId: String, state: ConnState) {
        byConnectionId[connectionId] = state
    }

    fun get(connectionId: String): ConnState? = byConnectionId[connectionId]

    fun remove(connectionId: String) {
        byConnectionId.remove(connectionId)
    }
}

/** Грубый token bucket, без внешних зависимостей — на большее протокол не просит. */
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
        val elapsedSec = (now - prev) / 1_000_000_000.0
        if (elapsedSec <= 0) return
        val add = (elapsedSec * refillPerSecond).toLong()
        if (add > 0 && lastRefill.compareAndSet(prev, now)) {
            tokens.updateAndGet { minOf(capacity.toLong(), it + add) }
        }
    }
}
