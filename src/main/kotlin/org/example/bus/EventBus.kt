package org.example.bus

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.websockets.next.CloseReason
import io.quarkus.websockets.next.OpenConnections
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.proto.Envelope
import org.example.registry.ConnState
import org.example.registry.ConnectionRegistry
import org.example.service.ChatService
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Шина событий между нодами: «доставить кадр всем участникам чата / этим
 * людям / тем, у кого открыта комната», где бы они ни были подключены.
 *
 * Как устроено (рассчитано на 100k+ одновременных соединений):
 *  1. Кадр собирается ОДИН раз — у того, кто публикует, — и уходит в шину
 *     готовым текстом. Ноды-получатели в БД не ходят вообще.
 *  2. Каналы адресные: чат, человек, комната. Нода подписана только на каналы
 *     тех, кто к ней подключён (индексы в [ConnectionRegistry]), поэтому
 *     сообщение получают только ноды с адресатами. Транспорт — Redis pub/sub
 *     ([RedisTransport]); для разработки — Postgres NOTIFY ([PgNotifyTransport]).
 *  3. Публикация — после COMMIT (через TransactionSynchronizationRegistry):
 *     откатилась транзакция — никто ничего не получил.
 *  4. Приём — по [lanes] однопоточным очередям, канал всегда в одной и той же
 *     очереди: порядок сообщений внутри чата сохраняется, разные чаты идут
 *     параллельно.
 *  5. Доставка в сокет — по id соединения, без перебора всех соединений.
 *     Клиент, который не успевает читать (больше [MAX_PENDING] кадров в
 *     очереди), отключается с кодом 4508 и переподключается.
 *
 * Состав чатов у подключённых держится в памяти: грузится при подключении,
 * обновляется событием [membershipChanged] и сверкой раз в 10 минут.
 */
@ApplicationScoped
class EventBus(
    private val mapper: ObjectMapper,
    private val registry: ConnectionRegistry,
    private val resolver: BusResolver,
    private val openConnections: OpenConnections,
    private val redis: RedisTransport,
    private val pg: PgNotifyTransport,
    private val tsr: TransactionSynchronizationRegistry,
    private val chats: ChatService,
    @ConfigProperty(name = "straycatz.bus.transport", defaultValue = "redis") private val transportName: String,
    @ConfigProperty(name = "straycatz.bus.prefix", defaultValue = "sc") private val prefix: String,
    @ConfigProperty(name = "straycatz.bus.lanes", defaultValue = "8") private val laneCount: Int,
) {
    companion object {
        /** Кадров в очереди одного сокета, после которых считаем клиента зависшим. */
        const val MAX_PENDING = 1000
        const val SLOW_CONSUMER = 4508
        private const val BUFFER_KEY = "straycatz.bus.buffer"
        private const val T_FRAME = 'F'
        private const val T_POINTER = 'P'
        private const val T_CONTROL = 'C'
        private const val CTL_CHATS = "chats"
    }

    private val transport: BusTransport = if (transportName.equals("pg", ignoreCase = true)) pg else redis
    private val subLock = Any()
    private val lanes: Array<ThreadPoolExecutor> = Array(laneCount.coerceIn(1, 64)) { i ->
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "straycatz-bus-$i").apply { isDaemon = true }
        }
    }

    fun onStart(@Observes ev: StartupEvent) {
        transport.start(::onMessage, ::onResubscribed)
    }

    /** Нода останавливается: закрытия сокетов — не «человек ушёл» (presence не трогаем). */
    @Volatile
    var shuttingDown = false
        set

    fun onStop(@Observes ev: ShutdownEvent) {
        shuttingDown = true
        // «сервер перезапускается»: клиенты переподключатся (с разбросом) к другим нодам
        runCatching {
            openConnections.listAll().forEach { c ->
                c.close(CloseReason(1012, "restart")).subscribe().with({}, {})
            }
        }
        transport.stop()
        lanes.forEach { it.shutdown() }
        lanes.forEach { it.awaitTermination(2, TimeUnit.SECONDS) }
    }

    // ================================================================ публикация

    /** Всем участникам чата. liveOnly — только тем, у кого чат открыт на экране (typing). */
    @Transactional
    fun publishToChat(chatId: UUID, frame: Envelope, liveOnly: Boolean = false) =
        enqueue(listOf(chatCh(chatId) to wire(T_FRAME, if (liveOnly) "l" else "", text(frame))))

    /** Всем, у кого сейчас открыта комната ownerId (room.open). */
    @Transactional
    fun publishToRoomViewers(ownerId: UUID, frame: Envelope) =
        enqueue(listOf(roomCh(ownerId) to wire(T_FRAME, "", text(frame))))

    @Transactional
    fun publishToUsers(userIds: Collection<UUID>, frame: Envelope) {
        if (userIds.isEmpty()) return
        val payload = wire(T_FRAME, "", text(frame))
        enqueue(userIds.distinct().map { userCh(it) to payload })
    }

    /**
     * Сообщение чата по id строки: кадр собирается здесь, один раз (в той же
     * транзакции, поэтому видит только что вставленное). Если кадр не влезает
     * в транспорт (Postgres NOTIFY, 8 КБ) — уходит указатель, и получатель
     * дочитает строку сам.
     */
    @Transactional
    fun publishPointerToChat(chatId: UUID, kind: String, id: String) {
        val payload = resolved(kind, id) ?: return
        enqueue(listOf(chatCh(chatId) to payload))
    }

    @Transactional
    fun publishPointerToUsers(userIds: Collection<UUID>, kind: String, id: String) {
        if (userIds.isEmpty()) return
        val payload = resolved(kind, id) ?: return
        enqueue(userIds.distinct().map { userCh(it) to payload })
    }

    /**
     * Состав чатов этих людей поменялся (вошли/вышли/удалили чат): их ноды
     * перечитают членство и переподпишутся. Звать после любых правок chat_member.
     */
    @Transactional
    fun membershipChanged(userIds: Collection<UUID>) {
        if (userIds.isEmpty()) return
        val payload = wire(T_CONTROL, "", CTL_CHATS)
        enqueue(userIds.distinct().map { userCh(it) to payload })
    }

    // ================================================================ подключения этой ноды

    /** Новое соединение: подписаться на человека и его чаты. */
    fun attach(st: ConnState) {
        val memberOf = chats.memberChatIds(st.userId)
        synchronized(subLock) {
            apply(registry.put(st))
            apply(registry.setChats(st.connectionId, memberOf))
        }
    }

    /** Соединение закрылось. @return сколько соединений человека осталось на этой ноде. */
    fun detach(connectionId: String): Int = synchronized(subLock) {
        val (left, delta) = registry.remove(connectionId)
        apply(delta)
        left
    }

    /** Человек только что стал участником (проверили по БД) — не ждать события. */
    fun addChat(connectionId: String, chatId: UUID) = synchronized(subLock) { apply(registry.addChat(connectionId, chatId)) }

    fun removeChat(connectionId: String, chatId: UUID) = synchronized(subLock) { apply(registry.removeChat(connectionId, chatId)) }

    fun openRoom(connectionId: String, ownerId: UUID) = synchronized(subLock) { apply(registry.openRoom(connectionId, ownerId)) }

    fun closeRoom(connectionId: String, ownerId: UUID) = synchronized(subLock) { apply(registry.closeRoom(connectionId, ownerId)) }

    /** Перечитать состав чатов у всех подключённых к этой ноде (сверка раз в 10 минут). */
    fun reconcile() {
        registry.localUsers().chunked(1000).forEach { refreshChats(it) }
        val backlog = lanes.sumOf { it.queue.size }
        Log.infof("шина: %d соединений, %d людей, очередь %d", registry.size(), registry.localUsers().size, backlog)
    }

    // ================================================================ приём

    private fun onMessage(channel: String, payload: String) {
        lanes[Math.floorMod(channel.hashCode(), lanes.size)].execute { process(channel, payload) }
    }

    private fun process(channel: String, payload: String) {
        try {
            val nl = payload.indexOf('\n')
            if (nl < 1) return
            val type = payload[0]
            val flags = payload.substring(2, nl)
            val body = payload.substring(nl + 1)
            val key = channel.substring(prefix.length + 1) // "c:<uuid>" / "u:<uuid>" / "r:<uuid>"
            val scope = key[0]
            val id = UUID.fromString(key.substring(2))
            when (type) {
                T_CONTROL -> if (scope == 'u' && body == CTL_CHATS) refreshChats(listOf(id))
                T_FRAME -> deliver(targets(scope, id, flags), body)
                T_POINTER -> {
                    val targets = targets(scope, id, flags)
                    if (targets.isEmpty()) return
                    val frame = resolver.resolve(Pointer(body.substringBefore('|'), body.substringAfter('|'))) ?: return
                    deliver(targets, mapper.writeValueAsString(frame))
                }
            }
        } catch (e: Exception) {
            Log.error("шина: не смогли обработать сообщение канала $channel", e)
        }
    }

    private fun targets(scope: Char, id: UUID, flags: String): Set<String> = when (scope) {
        'c' -> {
            val all = registry.chatConnections(id)
            if ('l' in flags) all.filterTo(HashSet()) { registry.get(it)?.openChats?.contains(id) == true } else all
        }
        'u' -> registry.connectionIdsOf(id)
        'r' -> registry.roomConnections(id)
        else -> emptySet()
    }

    private fun deliver(connectionIds: Set<String>, text: String) {
        for (cid in connectionIds) {
            val st = registry.get(cid) ?: continue
            val conn = openConnections.findByConnectionId(cid).orElse(null) ?: continue
            if (st.pending.get() >= MAX_PENDING) {
                Log.infof("шина: %s не успевает читать — отключаем", cid)
                conn.close(CloseReason(SLOW_CONSUMER, "slow consumer")).subscribe().with({}, {})
                continue
            }
            st.pending.incrementAndGet()
            conn.sendText(text).subscribe().with(
                { st.pending.decrementAndGet() },
                { e -> st.pending.decrementAndGet(); Log.debugf("шина: не смогли отправить в %s: %s", cid, e.message) },
            )
        }
    }

    /** Транспорт переподключился — за время обрыва что-то могло потеряться: клиенты пусть догонят. */
    private fun onResubscribed() {
        val text = mapper.writeValueAsString(Envelope(t = "resync", d = mapper.createObjectNode()))
        lanes[0].execute { deliver(registry.all().mapTo(HashSet()) { it.connectionId }, text) }
    }

    fun refreshChats(userIds: Collection<UUID>) {
        val local = userIds.filter { registry.connectionCount(it) > 0 }
        if (local.isEmpty()) return
        val byUser = chats.memberChatIdsOf(local)
        synchronized(subLock) {
            local.forEach { u ->
                val set = byUser[u] ?: emptySet()
                registry.connectionIdsOf(u).forEach { cid -> apply(registry.setChats(cid, set)) }
            }
        }
    }

    // ------------------------------------------------------------------

    /** Вызывать под subLock: порядок SUBSCRIBE/UNSUBSCRIBE должен совпадать с порядком изменений. */
    private fun apply(d: ConnectionRegistry.Delta) {
        if (d.isEmpty()) return
        val sub = d.users.map(::userCh) + d.chats.map(::chatCh) + d.rooms.map(::roomCh)
        val unsub = d.usersGone.map(::userCh) + d.chatsGone.map(::chatCh) + d.roomsGone.map(::roomCh)
        if (unsub.isNotEmpty()) transport.unsubscribe(unsub)
        if (sub.isNotEmpty()) transport.subscribe(sub)
    }

    private fun enqueue(messages: List<Pair<String, String>>) {
        if (messages.isEmpty()) return
        if (transport.transactional) {
            transport.publish(messages)
            return
        }
        when (tsr.transactionStatus) {
            Status.STATUS_NO_TRANSACTION -> transport.publish(messages)
            Status.STATUS_ACTIVE -> buffer().addAll(messages)
            else -> Unit // транзакция уже откатывается — никому ничего
        }
    }

    /** Сообщения этой транзакции — уйдут одной пачкой после COMMIT. */
    @Suppress("UNCHECKED_CAST")
    private fun buffer(): MutableList<Pair<String, String>> {
        (tsr.getResource(BUFFER_KEY) as MutableList<Pair<String, String>>?)?.let { return it }
        val buf = ArrayList<Pair<String, String>>()
        tsr.putResource(BUFFER_KEY, buf)
        tsr.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() {}
            override fun afterCompletion(status: Int) {
                if (status == Status.STATUS_COMMITTED) transport.publish(buf)
            }
        })
        return buf
    }

    private fun resolved(kind: String, id: String): String? {
        val frame = resolver.resolve(Pointer(kind, id)) ?: return null
        val payload = wire(T_FRAME, "", mapper.writeValueAsString(frame))
        if (payload.toByteArray(Charsets.UTF_8).size <= transport.maxPayload) return payload
        return wire(T_POINTER, "", "$kind|$id")
    }

    private fun text(frame: Envelope): String = mapper.writeValueAsString(frame)

    /** «<тип>|<флаги>\n<тело>»: заголовок разбирается без JSON, тело уходит в сокет как есть. */
    private fun wire(type: Char, flags: String, body: String): String = "$type|$flags\n$body"

    private fun chatCh(id: UUID) = "$prefix:c:$id"
    private fun userCh(id: UUID) = "$prefix:u:$id"
    private fun roomCh(id: UUID) = "$prefix:r:$id"
}

/** Сверка состава чатов раз в 10 минут — на случай пропущенного события. */
@ApplicationScoped
class BusReconcileJob(private val bus: EventBus) {
    @Scheduled(every = "10m", delayed = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun run() = bus.reconcile()
}
