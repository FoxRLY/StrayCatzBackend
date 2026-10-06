package org.example.bus

/**
 * Транспорт шины между нодами. Сообщение = (канал, текст).
 *
 * Каналы: `<prefix>:c:<chatId>` — чат, `<prefix>:u:<userId>` — человек,
 * `<prefix>:r:<ownerId>` — комната. Нода подписана только на каналы тех,
 * кто подключён к ней, поэтому сообщение получают только ноды с адресатами.
 *
 * Реализации:
 *  - [RedisTransport] — Redis pub/sub, для прода (100k+ соединений, любое число нод);
 *  - [PgNotifyTransport] — Postgres LISTEN/NOTIFY, для разработки и одной-двух нод:
 *    все сообщения приходят всем нодам, лишнее отбрасывается на месте.
 */
interface BusTransport {
    /** Имя для логов и настройки straycatz.bus.transport. */
    val name: String

    /**
     * true — publish зовётся внутри транзакции и уходит при COMMIT (NOTIFY);
     * false — шина сама зовёт publish после COMMIT.
     */
    val transactional: Boolean

    /** Максимальный размер одного сообщения в байтах (UTF-8). */
    val maxPayload: Int

    /** [onMessage] зовётся на потоке транспорта — тяжёлую работу туда не класть. */
    fun start(onMessage: (channel: String, payload: String) -> Unit, onResubscribed: () -> Unit)

    fun stop()

    fun publish(messages: List<Pair<String, String>>)

    fun subscribe(channels: Collection<String>)

    fun unsubscribe(channels: Collection<String>)
}
