package org.example.registry

import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    /** Комнаты, открытые на экране (room.open) — сюда летит room.updated. Храним ownerId. */
    val openRooms: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Беседы, где человек участник. Держит в актуальном виде шина: загружается
     * при подключении, обновляется событием «состав чатов поменялся» и
     * сверкой раз в 10 минут. Меняется только через ConnectionRegistry.
     */
    val memberChats: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 10 message.send в секунду на соединение. */
    val sendBucket = RateBucket(capacity = 10, refillPerSecond = 10)

    /** Чат эфира: сообщение не чаще раза в 2 секунды с вкладки (медленный режим). */
    val lastStreamSendAt = AtomicLong(0)

    /** typing — не чаще раза в 1,5 секунды (остальное молча отбрасываем). */
    val lastTypingAt = AtomicLong(0)

    /** Сколько кадров ушло в сокет и ещё не дописано — защита от «медленного читателя». */
    val pending = AtomicInteger(0)
}

/**
 * Кто подключён к ЭТОЙ ноде и куда кому доставлять.
 *
 * Кроме «соединения человека» держит два индекса для шины:
 *  - чат → соединения его участников (по memberChats);
 *  - комната → соединения, у которых она открыта.
 * Шина подписывается на канал чата/комнаты/человека, пока здесь есть хоть одно
 * соединение, которому он нужен. Методы, меняющие состав, возвращают [Delta] —
 * на какие каналы подписаться и от каких отписаться.
 */
@ApplicationScoped
class ConnectionRegistry {
    private val byConnectionId = ConcurrentHashMap<String, ConnState>()
    private val byUser = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val byChat = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val byRoom = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val lock = Any()

    /** Изменение подписок: ключи — что появилось / что пропало на этой ноде. */
    data class Delta(
        val users: Set<UUID> = emptySet(), val usersGone: Set<UUID> = emptySet(),
        val chats: Set<UUID> = emptySet(), val chatsGone: Set<UUID> = emptySet(),
        val rooms: Set<UUID> = emptySet(), val roomsGone: Set<UUID> = emptySet(),
    ) {
        fun isEmpty() = users.isEmpty() && usersGone.isEmpty() && chats.isEmpty() && chatsGone.isEmpty() &&
                rooms.isEmpty() && roomsGone.isEmpty()
    }

    fun put(state: ConnState): Delta = synchronized(lock) {
        byConnectionId[state.connectionId] = state
        val set = byUser.computeIfAbsent(state.userId) { ConcurrentHashMap.newKeySet() }
        val first = set.isEmpty()
        set.add(state.connectionId)
        if (first) Delta(users = setOf(state.userId)) else Delta()
    }

    fun get(connectionId: String): ConnState? = byConnectionId[connectionId]

    /** Убрать соединение. left — сколько соединений человека осталось на этой ноде. */
    fun remove(connectionId: String): Pair<Int, Delta> = synchronized(lock) {
        val st = byConnectionId.remove(connectionId) ?: return 0 to Delta()
        var left = 0
        byUser.computeIfPresent(st.userId) { _, set ->
            set.remove(connectionId)
            left = set.size
            if (set.isEmpty()) null else set
        }
        val chatsGone = st.memberChats.filterTo(HashSet()) { detach(byChat, it, connectionId) }
        val roomsGone = st.openRooms.filterTo(HashSet()) { detach(byRoom, it, connectionId) }
        left to Delta(usersGone = if (left == 0) setOf(st.userId) else emptySet(), chatsGone = chatsGone, roomsGone = roomsGone)
    }

    /** Заменить состав чатов соединения (после загрузки из БД). */
    fun setChats(connectionId: String, chats: Set<UUID>): Delta = synchronized(lock) {
        val st = byConnectionId[connectionId] ?: return Delta()
        val added = chats - st.memberChats
        val removed = st.memberChats - chats
        st.memberChats.removeAll(removed)
        st.memberChats.addAll(added)
        st.openChats.retainAll(chats)
        Delta(
            chats = added.filterTo(HashSet()) { attach(byChat, it, connectionId) },
            chatsGone = removed.filterTo(HashSet()) { detach(byChat, it, connectionId) },
        )
    }

    /** Добавить один чат (человек только что вошёл, а событие ещё не дошло). */
    fun addChat(connectionId: String, chatId: UUID): Delta = synchronized(lock) {
        val st = byConnectionId[connectionId] ?: return Delta()
        if (!st.memberChats.add(chatId)) return Delta()
        if (attach(byChat, chatId, connectionId)) Delta(chats = setOf(chatId)) else Delta()
    }

    fun removeChat(connectionId: String, chatId: UUID): Delta = synchronized(lock) {
        val st = byConnectionId[connectionId] ?: return Delta()
        st.openChats.remove(chatId)
        if (!st.memberChats.remove(chatId)) return Delta()
        if (detach(byChat, chatId, connectionId)) Delta(chatsGone = setOf(chatId)) else Delta()
    }

    fun openRoom(connectionId: String, ownerId: UUID): Delta = synchronized(lock) {
        val st = byConnectionId[connectionId] ?: return Delta()
        if (!st.openRooms.add(ownerId)) return Delta()
        if (attach(byRoom, ownerId, connectionId)) Delta(rooms = setOf(ownerId)) else Delta()
    }

    fun closeRoom(connectionId: String, ownerId: UUID): Delta = synchronized(lock) {
        val st = byConnectionId[connectionId] ?: return Delta()
        if (!st.openRooms.remove(ownerId)) return Delta()
        if (detach(byRoom, ownerId, connectionId)) Delta(roomsGone = setOf(ownerId)) else Delta()
    }

    fun connectionCount(userId: UUID): Int = byUser[userId]?.size ?: 0

    fun connectionIdsOf(userId: UUID): Set<String> = byUser[userId]?.toSet() ?: emptySet()

    /** Соединения участников чата на этой ноде. */
    fun chatConnections(chatId: UUID): Set<String> = byChat[chatId]?.toSet() ?: emptySet()

    /** Соединения, у которых открыта комната. */
    fun roomConnections(ownerId: UUID): Set<String> = byRoom[ownerId]?.toSet() ?: emptySet()

    fun all(): Collection<ConnState> = byConnectionId.values

    fun localUsers(): Set<UUID> = byUser.keys.toSet()

    /** Все каналы, которые сейчас нужны этой ноде (переподписка после обрыва шины). */
    fun snapshot(): Delta = Delta(users = byUser.keys.toSet(), chats = byChat.keys.toSet(), rooms = byRoom.keys.toSet())

    fun size(): Int = byConnectionId.size

    /** true — это первое соединение для ключа (нужна подписка). */
    private fun attach(index: ConcurrentHashMap<UUID, MutableSet<String>>, key: UUID, connectionId: String): Boolean {
        val set = index.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }
        val first = set.isEmpty()
        set.add(connectionId)
        return first
    }

    /** true — соединений по ключу не осталось (можно отписаться). */
    private fun detach(index: ConcurrentHashMap<UUID, MutableSet<String>>, key: UUID, connectionId: String): Boolean {
        var gone = false
        index.computeIfPresent(key) { _, set ->
            set.remove(connectionId)
            if (set.isEmpty()) { gone = true; null } else set
        }
        return gone
    }
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
