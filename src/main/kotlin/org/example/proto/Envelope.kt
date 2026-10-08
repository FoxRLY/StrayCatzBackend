package org.example.proto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Конверт кадра: { t, id, ts, d[, rid] }.
 * `d` держим как JsonNode и разбираем в конкретный payload-класс по `t`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Envelope(
    val t: String,
    val id: String = UUID.randomUUID().toString(),
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
    const val MESSAGE_EDIT = "message.edit"
    const val MESSAGE_DELETE = "message.delete"
    const val MESSAGE_READ = "message.read"
    const val TYPING = "typing"
    const val PRESENCE_SET = "presence.set"
    const val PRESENCE_QUERY = "presence.query"
    const val PING = "ping"
    /** «Я здесь»: мышь/клавиатура/скролл, не чаще раза в минуту. Без ответа. */
    const val ACTIVITY = "activity"
    /** Переслать выбранные сообщения (как POST …/messages/forward). */
    const val MESSAGE_FORWARD = "message.forward"
    /** Смотрю комнату — присылай room.updated, пока открыта. d = {ownerId} */
    const val ROOM_OPEN = "room.open"
    const val ROOM_CLOSE = "room.close"
    /** Открыл/закрыл страницу сообщества: { communityId } — сюда летит voice.updated / voice.removed. */
    const val COMMUNITY_OPEN = "community.open"
    const val COMMUNITY_CLOSE = "community.close"
    /** Слушаю/открыл радио: { stationId } — сюда летит radio.state (смена трека, очередь, слушатели). */
    const val RADIO_OPEN = "radio.open"
    const val RADIO_CLOSE = "radio.close"
    /** Поставить / снять реакцию. d = {chatId, messageId, emoji} */
    const val REACTION_ADD = "reaction.add"
    const val REACTION_REMOVE = "reaction.remove"

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
    /** Чат создан / изменился состав / вас удалили — клиент перечитывает GET /api/chats(/{id}). */
    const val CHAT_UPDATED = "chat.updated"
    /** Заявка в друзья / принятие / удаление. */
    const val FRIEND_UPDATED = "friend.updated"
    /** Новое уведомление (приглашение в гости, запись в гостевой, ...). d = NotificationOut. */
    const val NOTIFICATION_NEW = "notification.new"
    /** Изменился счётчик непрочитанных (прочитал в другой вкладке и т.п.). d = {unread} */
    const val NOTIFICATION_STATE = "notification.state"
    /** Друг включил/выключил трек. d = NowPlayingOut */
    const val NOW_PLAYING = "music.now_playing"
    /** Хозяин поменял комнату (what: look / links / guestbook) — перечитай GET /api/rooms/{username}. */
    const val ROOM_UPDATED = "room.updated"
    /** Реакции на сообщении поменялись. Участникам чата. d = {chatId, messageId, reactions} */
    const val MESSAGE_REACTIONS = "message.reactions"
    /** Стрим начался/закончился/сменилось число зрителей. Участникам чата стрима. d = StreamStateOut */
    const val STREAM_STATE = "stream.state"
    const val PRESENCE_CHANGED = "presence.changed"
    const val PRESENCE_SNAPSHOT = "presence.snapshot"
    const val FEED_BUMP = "feed.bump"
    const val ERROR = "error"
    const val PONG = "pong"
    /** Ответ на message.forward (тот же rid): ForwardResultOut. */
    const val MESSAGE_FORWARDED = "message.forwarded"
    /** Мои папки чатов поменялись (на другом устройстве): перечитать GET /api/chats/folders. */
    const val CHAT_FOLDERS = "chat.folders"

    // звонки — от сервера
    const val CALL_RINGING = "call.ringing"
    const val CALL_ACCEPTED = "call.accepted"
    const val CALL_DECLINED = "call.declined"
    const val CALL_LEFT = "call.left"
    const val CALL_ENDED = "call.ended"
    /** Ответ на call.invite / call.accept этому соединению: { call, livekit: { url, token, room, identity } }. */
    const val CALL_JOINED = "call.joined"
    /** Состояние звонка поменялось (кто в звонке, кто подключён): CallOut. */
    const val CALL_UPDATED = "call.updated"
}

/** Коды закрытия сокета. */
object CloseCodes {
    const val UNAUTHORIZED = 4401      // нет/битый/просроченный токен
    const val FORBIDDEN = 4403        // полез в чужой чат
    const val TOO_MANY_CONNECTIONS = 4409
    /** Аккаунт заблокирован модератором — не переподключаться, показать экран бана (GET любого REST → details). */
    const val BANNED = 4410
}

class EnvelopeCodec(private val mapper: ObjectMapper) {

    /**
     * Конверт разбираем руками, а не через data class: так он не зависит от
     * Kotlin-модуля Jackson, а от клиента обязателен только `t`
     * (`id`/`ts` клиент может не слать).
     */
    fun parse(raw: String): Envelope {
        val node = mapper.readTree(raw)
        require(node != null && node.isObject) { "кадр должен быть JSON-объектом" }
        val t = node.get("t")?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("нет строкового поля t")
        return Envelope(
            t = t,
            id = node.get("id")?.takeIf { it.isTextual }?.asText() ?: UUID.randomUUID().toString(),
            d = node.get("d")?.takeUnless { it.isNull },
            rid = node.get("rid")?.takeIf { it.isTextual }?.asText(),
        )
    }

    fun <T> payloadAs(env: Envelope, clazz: Class<T>): T {
        val node = env.d ?: mapper.createObjectNode()
        return mapper.treeToValue(node, clazz)
    }

    fun server(t: String, payload: Any?, rid: String? = null): String {
        val node: JsonNode = payload?.let { mapper.valueToTree(it) } ?: mapper.createObjectNode()
        return mapper.writeValueAsString(Envelope(t = t, d = node, rid = rid))
    }
}
