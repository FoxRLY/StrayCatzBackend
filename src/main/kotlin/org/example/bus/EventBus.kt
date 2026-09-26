package org.example.bus

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.websockets.next.OpenConnections
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.pgclient.pubsub.PgSubscriber
import io.vertx.pgclient.PgConnectOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.proto.Envelope
import org.example.registry.ConnectionRegistry
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val CHANNEL = "straycatz_bus"

object PointerKinds {
    const val MESSAGE_NEW = "message.new"
    const val MESSAGE_UPDATED = "message.updated"
    const val CALL_SIGNAL = "call.signal"
}

/**
 * Рассылка между нодами через Postgres LISTEN/NOTIFY.
 *
 * Главное отличие от прошлой версии: NOTIFY шлётся через тот же JDBC
 * (Hibernate), что и запись данных, внутри той же транзакции. У Postgres
 * NOTIFY транзакционный — уходит слушателям только при COMMIT и
 * выбрасывается при ROLLBACK. Раньше NOTIFY уходил через отдельный
 * реактивный пул ДО коммита, и нода-получатель могла не найти только что
 * вставленное сообщение ("исчезло между записью и рассылкой").
 *
 * Если publish* вызван вне транзакции (typing), @Transactional(REQUIRED)
 * откроет короткую свою.
 *
 * По шине ходит либо маленький готовый кадр (frame), либо указатель
 * (pointer) на строку в БД — NOTIFY режет пейлоад на 8000 байт.
 */
@ApplicationScoped
class EventBus(
    private val vertx: Vertx,
    private val mapper: ObjectMapper,
    private val em: EntityManager,
    private val openConnections: OpenConnections,
    private val registry: ConnectionRegistry,
    private val resolver: BusResolver,
    @ConfigProperty(name = "quarkus.datasource.reactive.url") private val reactiveUrl: String,
    @ConfigProperty(name = "quarkus.datasource.username") private val dbUser: String,
    @ConfigProperty(name = "quarkus.datasource.password") private val dbPassword: String,
) {
    private lateinit var subscriber: PgSubscriber

    // Один поток: NOTIFY приходят в порядке коммитов, и так они же и
    // обрабатываются — message.new в чате не перемешиваются по seq.
    // Отправка в сокеты асинхронная, поэтому медленный клиент поток не держит.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "straycatz-bus").apply { isDaemon = true }
    }

    fun onStart(@Observes ev: StartupEvent) {
        val opts = PgConnectOptions.fromUri(reactiveUrl.removePrefix("vertx-reactive:"))
            .setUser(dbUser)
            .setPassword(dbPassword)

        subscriber = PgSubscriber.subscriber(vertx, opts)
        subscriber.reconnectPolicy { retries -> minOf(retries * 500L, 10_000L) }
        subscriber.channel(CHANNEL).handler { payload -> worker.execute { onNotify(payload) } }
        subscriber.connectAndAwait()
        Log.info("EventBus: слушаем канал $CHANNEL")
    }

    fun onStop(@Observes ev: ShutdownEvent) {
        if (::subscriber.isInitialized) subscriber.close()
        worker.shutdown()
        worker.awaitTermination(2, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- publish

    /** Всем активным участникам чата. liveOnly = только тем, у кого чат открыт (typing). */
    @Transactional
    fun publishToChat(chatId: UUID, frame: Envelope, liveOnly: Boolean = false) =
        notify(BusMessage(scope = Scope.CHAT, chatId = chatId, liveOnly = liveOnly, frame = mapper.valueToTree(frame)))

    @Transactional
    fun publishToUsers(userIds: Collection<UUID>, frame: Envelope) {
        if (userIds.isEmpty()) return
        notify(BusMessage(scope = Scope.USERS, userIds = userIds.toList(), frame = mapper.valueToTree(frame)))
    }

    @Transactional
    fun publishPointerToChat(chatId: UUID, kind: String, id: String) =
        notify(BusMessage(scope = Scope.CHAT, chatId = chatId, pointer = Pointer(kind, id)))

    @Transactional
    fun publishPointerToUsers(userIds: Collection<UUID>, kind: String, id: String) {
        if (userIds.isEmpty()) return
        notify(BusMessage(scope = Scope.USERS, userIds = userIds.toList(), pointer = Pointer(kind, id)))
    }

    private fun notify(msg: BusMessage) {
        val json = mapper.writeValueAsString(msg)
        require(json.toByteArray(Charsets.UTF_8).size < 7900) {
            "bus-сообщение слишком большое для NOTIFY (${json.length} симв.) — используйте pointer"
        }
        // pg_notify возвращает void, Hibernate такой тип не мапит — оборачиваем.
        em.createNativeQuery("select 1 from (select pg_notify(?1, ?2)) as n")
            .setParameter(1, CHANNEL)
            .setParameter(2, json)
            .singleResult
    }

    // ---------------------------------------------------------------- receive

    private fun onNotify(payload: String) {
        try {
            val msg = mapper.readValue(payload, BusMessage::class.java)
            // JsonNode-поле Jackson читает из "frame":null как NullNode, а не
            // как Kotlin-null — поэтому явно отбрасываем isNull, иначе в сокет
            // улетает строка "null", а указатель так и не разрешается.
            val frame: JsonNode = msg.frame?.takeUnless { it.isNull }
                ?: msg.pointer?.let { resolver.resolve(it) }
                ?: return
            deliverLocally(msg, mapper.writeValueAsString(frame))
        } catch (e: Exception) {
            Log.error("EventBus: не смогли обработать NOTIFY", e)
        }
    }

    private fun deliverLocally(msg: BusMessage, text: String) {
        val userIds = msg.userIds
        val chatId = msg.chatId
        val targetConnIds: Set<String> = when {
            userIds != null ->
                userIds.flatMapTo(HashSet()) { registry.connectionIdsOf(it) }

            chatId != null && msg.liveOnly -> {
                val open = registry.all().filter { chatId in it.openChats }
                if (open.isEmpty()) return
                // вышедшие из чата не должны получать typing, даже если вкладка открыта
                val members = resolver.activeMemberIds(chatId).toSet()
                open.filter { it.userId in members }.mapTo(HashSet()) { it.connectionId }
            }

            chatId != null -> {
                // Участников берём из БД, а не из кэша на соединении: так и
                // свежедобавленные в чат получат сообщение, и удалённые
                // сразу перестанут.
                val localUsers = registry.all().mapTo(HashSet()) { it.userId }
                if (localUsers.isEmpty()) return
                resolver.activeMemberIds(chatId)
                    .filter { it in localUsers }
                    .flatMapTo(HashSet()) { registry.connectionIdsOf(it) }
            }

            else -> emptySet()
        }
        if (targetConnIds.isEmpty()) return

        for (conn in openConnections.listAll()) {
            if (conn.id() !in targetConnIds) continue
            conn.sendText(text).subscribe().with(
                {},
                { e -> Log.debugf("EventBus: не смогли отправить в %s: %s", conn.id(), e.message) },
            )
        }
    }
}

enum class Scope { CHAT, USERS }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BusMessage(
    val scope: Scope,
    val chatId: UUID? = null,
    val userIds: List<UUID>? = null,
    val liveOnly: Boolean = false,
    val pointer: Pointer? = null,
    val frame: JsonNode? = null,
)

data class Pointer(val kind: String, val id: String)
