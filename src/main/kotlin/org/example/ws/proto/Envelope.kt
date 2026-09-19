package org.example.ws.proto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * Конверт кадра ровно как в протоколе: { t, id, ts, d[, rid] }.
 * `d` держим как JsonNode и разбираем предметно в конкретный payload-класс
 * по значению `t` — так проще эволюционировать протокол, не переписывая
 * (де)сериализацию каждый раз.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Envelope(
    val t: String,
    val id: String,
    val ts: String = Instant.now().toString(),
    val d: JsonNode?,
    val rid: String? = null,
)

object FrameTypes {
    // от клиента
    const val HELLO = "hello"
    const val CHAT_OPEN = "chat.open"
    const val CHAT_CLOSE = "chat.close"
    const val MESSAGE_SEND = "message.send"
    const val MESSAGE_READ = "message.read"
    const val TYPING = "typing"
    const val PRESENCE_SET = "presence.set"
    const val PING = "ping"

    // звонки — от клиента
    const val CALL_INVITE = "call.invite"
    const val CALL_ACCEPT = "call.accept"
    const val CALL_DECLINE = "call.decline"
    const val CALL_LEAVE = "call.leave"
    const val CALL_SIGNAL = "call.signal"

    // от сервера
    const val READY = "ready"
    const val MESSAGE_NEW = "message.new"
    const val MESSAGE_ACK = "message.ack"
    const val MESSAGE_UPDATED = "message.updated"
    const val CHAT_READ = "chat.read"
    const val PRESENCE_CHANGED = "presence.changed"
    const val FEED_BUMP = "feed.bump"
    const val ERROR = "error"
    const val PONG = "pong"

    // звонки — от сервера
    const val CALL_RINGING = "call.ringing"
    const val CALL_ACCEPTED = "call.accepted"
    const val CALL_DECLINED = "call.declined"
    const val CALL_ENDED = "call.ended"
}

/** Небольшой хелпер, чтобы не таскать ObjectMapper через все сервисы вручную. */
class EnvelopeCodec(private val mapper: ObjectMapper) {

    fun parse(raw: String): Envelope = mapper.readValue(raw, Envelope::class.java)

    fun <T> payloadAs(env: Envelope, clazz: Class<T>): T {
        val node = env.d ?: mapper.createObjectNode()
        return mapper.treeToValue(node, clazz)
    }

    fun server(t: String, id: String, payload: Any?, rid: String? = null): String {
        val node: JsonNode = payload?.let { mapper.valueToTree(it) } ?: mapper.createObjectNode()
        val env = Envelope(t = t, id = id, d = node, rid = rid)
        return mapper.writeValueAsString(env)
    }

    fun objectNode(): ObjectNode = mapper.createObjectNode()
}
