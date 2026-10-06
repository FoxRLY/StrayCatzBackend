package org.example.bus

import io.quarkus.logging.Log
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.pgclient.pubsub.PgSubscriber
import io.vertx.pgclient.PgConnectOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.ConcurrentHashMap

/**
 * Шина на Postgres LISTEN/NOTIFY — для разработки и одной-двух нод.
 *
 * Один канал Postgres на всё: в NOTIFY уходит «<канал шины>\n<сообщение>»,
 * каждая нода получает всё и отбрасывает то, на что не подписана. NOTIFY
 * транзакционный (уходит при COMMIT), поэтому publish зовётся внутри
 * транзакции. Предел — ~8 КБ на сообщение и сериализация коммитов с NOTIFY
 * в Postgres: на 100k соединений нужен [RedisTransport].
 */
@ApplicationScoped
class PgNotifyTransport(
    private val vertx: Vertx,
    private val em: EntityManager,
    @ConfigProperty(name = "quarkus.datasource.reactive.url") private val reactiveUrl: String,
    @ConfigProperty(name = "quarkus.datasource.username") private val dbUser: String,
    @ConfigProperty(name = "quarkus.datasource.password") private val dbPassword: String,
) : BusTransport {
    companion object {
        const val PG_CHANNEL = "straycatz_bus"
    }

    override val name = "pg"
    override val transactional = true
    override val maxPayload = 7800 // 8000 у NOTIFY минус имя канала шины

    private var subscriber: PgSubscriber? = null
    private val wanted = ConcurrentHashMap.newKeySet<String>()

    override fun start(onMessage: (channel: String, payload: String) -> Unit, onResubscribed: () -> Unit) {
        val opts = PgConnectOptions.fromUri(reactiveUrl.removePrefix("vertx-reactive:"))
            .setUser(dbUser)
            .setPassword(dbPassword)
        val s = PgSubscriber.subscriber(vertx, opts)
        s.reconnectPolicy { retries -> minOf(retries * 500L, 10_000L) }
        s.channel(PG_CHANNEL).handler { raw ->
            val nl = raw.indexOf('\n')
            if (nl <= 0) return@handler
            val channel = raw.substring(0, nl)
            if (channel in wanted) onMessage(channel, raw.substring(nl + 1))
        }
        s.connectAndAwait()
        subscriber = s
        Log.info("шина: Postgres LISTEN $PG_CHANNEL")
    }

    override fun stop() {
        subscriber?.close()
    }

    /** Внутри транзакции вызывающего: уйдёт при COMMIT. */
    override fun publish(messages: List<Pair<String, String>>) {
        messages.forEach { (channel, payload) ->
            // pg_notify возвращает void, Hibernate такой тип не мапит — оборачиваем
            em.createNativeQuery("select 1 from (select pg_notify(?1, ?2)) as n")
                .setParameter(1, PG_CHANNEL)
                .setParameter(2, channel + "\n" + payload)
                .singleResult
        }
    }

    override fun subscribe(channels: Collection<String>) { wanted.addAll(channels) }

    override fun unsubscribe(channels: Collection<String>) { wanted.removeAll(channels.toSet()) }
}
