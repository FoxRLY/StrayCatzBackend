package org.example.bus

import io.quarkus.logging.Log
import io.vertx.core.Vertx
import io.vertx.redis.client.Command
import io.vertx.redis.client.Redis
import io.vertx.redis.client.RedisConnection
import io.vertx.redis.client.Request
import io.vertx.redis.client.Response
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Шина на Redis pub/sub.
 *
 * - Одно выделенное соединение на подписки (SUBSCRIBE/UNSUBSCRIBE пачками до
 *   [CHUNK] каналов). Оборвалось — переподключаемся с паузой, переподписываемся
 *   на всё, что нужно, и зовём onResubscribed: за время обрыва сообщения могли
 *   потеряться, и клиентам уходит кадр resync.
 * - [PUBLISHERS] выделенных соединений на публикацию по кругу: PUBLISH пачками
 *   (пайплайн), без пула и его очереди ожидания.
 *
 * Redis не хранит сообщения: кто не подписан — тот не получил. Догонять
 * пропущенное клиент умеет сам (hello → missedFrom → история по HTTP).
 */
@ApplicationScoped
class RedisTransport(
    private val vertx: Vertx,
    @ConfigProperty(name = "straycatz.redis.url", defaultValue = "redis://localhost:6379") private val url: String,
) : BusTransport {
    companion object {
        const val CHUNK = 500
        const val PUBLISHERS = 4
        private const val MAX_BACKOFF_MS = 10_000L
    }

    override val name = "redis"
    override val transactional = false
    override val maxPayload = 8 * 1024 * 1024

    private lateinit var client: Redis
    @Volatile private var sub: RedisConnection? = null
    private val pubs = arrayOfNulls<RedisConnection>(PUBLISHERS)
    private val rr = AtomicInteger()
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false
    private lateinit var onMessage: (String, String) -> Unit
    private lateinit var onResubscribed: () -> Unit

    override fun start(onMessage: (channel: String, payload: String) -> Unit, onResubscribed: () -> Unit) {
        this.onMessage = onMessage
        this.onResubscribed = onResubscribed
        client = Redis.createClient(vertx, url)
        running = true
        connectSubscriber(0, first = true)
        for (i in 0 until PUBLISHERS) connectPublisher(i, 0)
        Log.infof("шина: Redis %s", url.substringAfter('@'))
    }

    override fun stop() {
        running = false
        sub?.close()
        pubs.forEach { it?.close() }
        if (::client.isInitialized) client.close()
    }

    override fun publish(messages: List<Pair<String, String>>) {
        if (messages.isEmpty()) return
        val conn = pubs[Math.floorMod(rr.getAndIncrement(), PUBLISHERS)]
            ?: pubs.firstOrNull { it != null }
        if (conn == null) {
            Log.warnf("шина: Redis недоступен, потеряно %d сообщений", messages.size)
            return
        }
        messages.chunked(CHUNK).forEach { chunk ->
            val reqs = chunk.map { (ch, payload) -> Request.cmd(Command.PUBLISH).arg(ch).arg(payload) }
            conn.batch(reqs).onFailure { e -> Log.warnf("шина: PUBLISH не прошёл: %s", e.message) }
        }
    }

    override fun subscribe(channels: Collection<String>) {
        val fresh = channels.filter { wanted.add(it) }
        if (fresh.isNotEmpty()) sub?.let { send(it, Command.SUBSCRIBE, fresh) }
    }

    override fun unsubscribe(channels: Collection<String>) {
        val gone = channels.filter { wanted.remove(it) }
        if (gone.isNotEmpty()) sub?.let { send(it, Command.UNSUBSCRIBE, gone) }
    }

    // ------------------------------------------------------------------

    private fun send(conn: RedisConnection, cmd: Command, channels: List<String>) {
        channels.chunked(CHUNK).forEach { chunk ->
            var req = Request.cmd(cmd)
            chunk.forEach { req = req.arg(it) }
            conn.send(req).onFailure { e -> Log.warnf("шина: %s не прошёл: %s", cmd, e.message) }
        }
    }

    private fun connectSubscriber(attempt: Int, first: Boolean) {
        if (!running) return
        client.connect()
            .onSuccess { conn ->
                conn.handler { msg -> handle(msg) }
                conn.exceptionHandler { e -> Log.warnf("шина: подписка Redis: %s", e.message) }
                conn.endHandler { _ ->
                    sub = null
                    Log.warn("шина: подписка Redis оборвалась, переподключаемся")
                    retry { connectSubscriber(1, first = false) }
                }
                sub = conn
                val all = wanted.toList()
                if (all.isNotEmpty()) send(conn, Command.SUBSCRIBE, all)
                if (!first) onResubscribed()
            }
            .onFailure { e ->
                Log.warnf("шина: не подключились к Redis (%s), попытка %d", e.message, attempt + 1)
                retry(attempt) { connectSubscriber(attempt + 1, first) }
            }
    }

    private fun connectPublisher(i: Int, attempt: Int) {
        if (!running) return
        client.connect()
            .onSuccess { conn ->
                conn.exceptionHandler { e -> Log.debugf("шина: публикация Redis: %s", e.message) }
                conn.endHandler { _ ->
                    pubs[i] = null
                    retry { connectPublisher(i, 1) }
                }
                pubs[i] = conn
            }
            .onFailure { retry(attempt) { connectPublisher(i, attempt + 1) } }
    }

    private fun retry(attempt: Int = 1, action: () -> Unit) {
        if (!running) return
        val delay = minOf(MAX_BACKOFF_MS, 250L shl minOf(attempt, 6))
        vertx.setTimer(delay) { _ -> action() }
    }

    /** ["message", channel, payload] — и в RESP2, и в RESP3 (push). Подтверждения подписки пропускаем. */
    private fun handle(msg: Response) {
        try {
            if (msg.size() < 3) return
            if (msg.get(0).toString() != "message") return
            onMessage(msg.get(1).toString(), msg.get(2).toString())
        } catch (e: Exception) {
            Log.warnf("шина: не разобрали сообщение Redis: %s", e.message)
        }
    }
}
