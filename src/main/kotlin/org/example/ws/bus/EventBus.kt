package org.example.ws.bus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.example.ws.proto.Envelope
import org.example.ws.registry.ConnectionRegistry
import io.quarkus.logging.Log
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.websockets.next.OpenConnections
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.pgclient.pubsub.PgSubscriber
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import io.vertx.pgclient.PgConnectOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.*

private const val CHANNEL = "straycatz_bus"

/**
 * Кросс-нодовая рассылка через LISTEN/NOTIFY, как описано в доке
 * ("Рассылка между узлами — через LISTEN/NOTIFY Postgres, пока узлов
 * немного; когда станет мало [узлов свободных], меняется на Redis
 * pub/sub, и протокол от этого не меняется").
 *
 * NOTIFY-пейлоад у постгри ограничен 8000 байт, а тело сообщения — до
 * 4000 символов, что в юникоде легко перевешивает лимит. Поэтому по шине
 * гоняем НЕ сами данные, а маленький BusMessage: либо готовый маленький
 * кадр (frame), либо "указатель" (pointer) на строку в БД, которую сама
 * достаёт нода-получатель. Пишущая нода тоже проходит через NOTIFY, а не
 * доставляет локально напрямую — так что если сервер F сам держит одну
 * из целевых вкладок, сообщение и до него долетит тем же путём.
 */
@ApplicationScoped
class EventBus(
    private val vertx: Vertx,
    private val pool: Pool,
    private val mapper: ObjectMapper,
    private val openConnections: OpenConnections,
    private val registry: ConnectionRegistry,
    @ConfigProperty(name = "quarkus.datasource.reactive.url") private val reactiveUrl: String,
    @ConfigProperty(name = "quarkus.datasource.username") private val dbUser: String,
    @ConfigProperty(name = "quarkus.datasource.password") private val dbPassword: String,
) {
    private lateinit var subscriber: PgSubscriber

    fun onStart(@Observes ev: StartupEvent) {
        // PgSubscriber хочет отдельное постоянное соединение — LISTEN не
        // может жить на соединении из пула, которое гуляет туда-сюда.
        val opts = PgConnectOptions.fromUri(reactiveUrl.removePrefix("vertx-reactive:"))
            .setUser(dbUser)
            .setPassword(dbPassword)

        subscriber = PgSubscriber.subscriber(vertx, opts)
        subscriber.reconnectPolicy { retries -> minOf(retries * 500L, 10_000L) }
        subscriber.connectAndAwait()
        subscriber.channel(CHANNEL).handler { payload -> onNotify(payload) }
        Log.info("EventBus: подписались на канал $CHANNEL")
    }

    fun onStop(@Observes ev: ShutdownEvent) {
        if (::subscriber.isInitialized) subscriber.close()
    }

    /** Кадр адресован всем участникам чата (message.new и всё, что влияет на список чатов). */
    suspend fun publishToChat(chatId: UUID, frame: Envelope, liveOnly: Boolean = false) {
        notify(BusMessage(scope = Scope.CHAT, chatId = chatId, liveOnly = liveOnly, frame = mapper.valueToTree(frame)))
    }

    /** Кадр адресован конкретным пользователям (presence, точечный call.signal). */
    suspend fun publishToUsers(userIds: List<UUID>, frame: Envelope) {
        if (userIds.isEmpty()) return
        notify(BusMessage(scope = Scope.USERS, userIds = userIds, frame = mapper.valueToTree(frame)))
    }

    /** Указатель на строку в БД — приёмник сам достаёт полезную нагрузку, см. [resolvePointer]. */
    suspend fun publishPointerToChat(chatId: UUID, pointerKind: String, pointerId: String) {
        notify(BusMessage(scope = Scope.CHAT, chatId = chatId, pointer = Pointer(pointerKind, pointerId)))
    }

    suspend fun publishPointerToUsers(userIds: List<UUID>, pointerKind: String, pointerId: String) {
        if (userIds.isEmpty()) return
        notify(BusMessage(scope = Scope.USERS, userIds = userIds, pointer = Pointer(pointerKind, pointerId)))
    }

    private suspend fun notify(msg: BusMessage) {
        val json = mapper.writeValueAsString(msg)
        require(json.toByteArray(Charsets.UTF_8).size < 7900) {
            "bus-сообщение слишком большое для NOTIFY (${json.length} байт); " +
                    "для крупных пейлоадов используйте publishPointer, а не publishFrame"
        }
        pool.preparedQuery("select pg_notify($1, $2)")
            .execute(Tuple.of(CHANNEL, json))
            .awaitSuspending()
    }

    private fun onNotify(payload: String) {
        // Хендлер PgSubscriber вызывается на event loop. Уходить в блокирующий
        // JDBC тут нельзя, а вот awaitAndBlock-методы Mutiny (fetchOne/sendTextAndAwait)
        // синхронно ждут неблокирующий Uni — сам event loop они не занимают
        // надолго, но чтобы не подвесить его на время похода в БД, сбрасываем
        // работу в воркер-пул Vert.x. Для первой версии этого достаточно;
        // при желании легко заменить на полностью suspend-цепочку.
        vertx.executeBlocking({
            try {
                val msg = mapper.readValue(payload, BusMessage::class.java)
                val frame: JsonNode = msg.frame ?: resolvePointer(msg.pointer ?: return@executeBlocking)
                deliverLocally(msg, frame)
            } catch (e: Exception) {
                Log.error("EventBus: не смогли обработать NOTIFY", e)
            }
        }, false).subscribe().with({}, { e -> Log.error("EventBus: executeBlocking упал", e) })
    }

    private fun resolvePointer(p: Pointer): JsonNode = when (p.kind) {
        "message" -> fetchOne(
            "select id, chat_id, seq, author_id, body, media_id, created_at, edited_at, deleted_at " +
                    "from messages where id = $1",
            Tuple.of(UUID.fromString(p.id)),
        ) { row -> messageRowToEnvelope(row) }
            ?: throw IllegalStateException("message ${p.id} исчезло между записью и рассылкой")

        "call_signal" -> fetchOne(
            "select id, chat_id, call_id, from_user_id, kind, payload " +
                    "from call_signals where id = $1",
            Tuple.of(p.id.toLong()),
        ) { row -> callSignalRowToEnvelope(row) }
            ?: throw IllegalStateException("call_signal ${p.id} не найден")

        else -> throw IllegalArgumentException("неизвестный тип указателя: ${p.kind}")
    }

    private fun <T> fetchOne(sql: String, args: Tuple, map: (Row) -> T): T? {
        val rs = pool.preparedQuery(sql).execute(args).await().indefinitely()
        val it = rs.iterator()
        return if (it.hasNext()) map(it.next()) else null
    }

    private fun messageRowToEnvelope(row: Row): JsonNode {
        val d = mapper.createObjectNode()
        d.put("id", row.getUUID("id").toString())
        d.put("chatId", row.getUUID("chat_id").toString())
        d.put("seq", row.getLong("seq"))
        d.put("authorId", row.getUUID("author_id").toString())
        d.put("body", row.getString("body"))
        row.getUUID("media_id")?.let { d.put("mediaId", it.toString()) }
        d.put("createdAt", row.getOffsetDateTime("created_at").toInstant().toString())
        row.getOffsetDateTime("edited_at")?.let { d.put("editedAt", it.toInstant().toString()) }
        row.getOffsetDateTime("deleted_at")?.let { d.put("deletedAt", it.toInstant().toString()) }
        val env = mapper.createObjectNode()
        val isUpdate = row.getOffsetDateTime("edited_at") != null || row.getOffsetDateTime("deleted_at") != null
        env.put("t", if (isUpdate) "message.updated" else "message.new")
        env.put("id", UUID.randomUUID().toString())
        env.set<JsonNode>("d", d)
        return env
    }

    private fun callSignalRowToEnvelope(row: Row): JsonNode {
        val d = mapper.createObjectNode()
        d.put("chatId", row.getUUID("chat_id").toString())
        d.put("callId", row.getUUID("call_id").toString())
        d.put("fromUserId", row.getUUID("from_user_id").toString())
        d.put("kind", row.getString("kind"))
        d.set<JsonNode>("payload", mapper.readTree(row.getJsonObject("payload").encode()))
        val env = mapper.createObjectNode()
        env.put("t", "call.signal")
        env.put("id", UUID.randomUUID().toString())
        env.set<JsonNode>("d", d)
        return env
    }

    private fun deliverLocally(msg: BusMessage, frame: JsonNode) {
        val text = mapper.writeValueAsString(frame)
        val chatId = msg.chatId
        val targetUsers = msg.userIds?.toSet()

        for (conn in openConnections.listAll()) {
            val state = registry.get(conn.id()) ?: continue
            val matches = when {
                targetUsers != null -> state.userId in targetUsers
                chatId != null && msg.liveOnly -> chatId in state.openChats
                chatId != null -> chatId in state.memberChats
                else -> false
            }
            if (!matches) continue
            try {
                conn.sendTextAndAwait(text)
            } catch (e: Exception) {
                Log.debug("EventBus: не смогли отправить в соединение ${conn.id()}: ${e.message}")
            }
        }
    }
}

enum class Scope { CHAT, USERS }

data class BusMessage(
    val scope: Scope,
    val chatId: UUID? = null,
    val userIds: List<UUID>? = null,
    val liveOnly: Boolean = false,
    val pointer: Pointer? = null,
    val frame: JsonNode? = null,
)

data class Pointer(val kind: String, val id: String)
