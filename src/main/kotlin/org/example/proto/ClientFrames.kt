package org.example.proto

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

// ---- d-пейлоады входящих (клиент -> сервер) кадров ----
// Лишние поля игнорируются (FAIL_ON_UNKNOWN_PROPERTIES = false, см. JacksonConfig).

data class HelloIn(
    val lastEventId: String? = null,
    val chats: List<UUID> = emptyList(),
)

data class ChatOpenIn(val chatId: UUID)
data class ChatCloseIn(val chatId: UUID)

data class MessageSendIn(
    val chatId: UUID,
    val body: String? = null,
    /** Устарело: одна картинка. */
    val mediaId: UUID? = null,
    /** До 4 картинок/гифок или одно видео — id из POST /api/media. */
    val mediaIds: List<UUID> = emptyList(),
    /** До 10 треков из своей музыки. */
    val trackIds: List<UUID> = emptyList(),
    val clientToken: UUID,
    /** Ответ на сообщение этого же чата. */
    val replyToId: UUID? = null,
    /** Гифка или внешний стикер — id из GET /api/gifs/search|trending|recent. */
    val gifId: UUID? = null,
    /** Стикер из набора — id из GET /api/stickers/… */
    val stickerId: UUID? = null,
)

/** message.forward: переслать выбранные сообщения чата chatId. */
data class MessageForwardIn(
    val chatId: UUID,
    val messageIds: List<UUID> = emptyList(),
    val toChatIds: List<UUID> = emptyList(),
    val toUserIds: List<UUID> = emptyList(),
    val comment: String? = null,
    val hideAuthor: Boolean = false,
)

/** reaction.add / reaction.remove — то же, что PUT/DELETE …/reactions/{emoji}. */
data class ReactionIn(val chatId: UUID, val messageId: UUID, val emoji: String)

data class MessageEditIn(val chatId: UUID, val messageId: UUID, val body: String)
data class MessageDeleteIn(val chatId: UUID, val messageId: UUID)

data class MessageReadIn(val chatId: UUID, val seq: Long)

/** room.open / room.close: ownerId из GET /api/rooms/{username}. */
data class RoomOpenIn(val ownerId: UUID)

data class TypingIn(val chatId: UUID)

data class PresenceSetIn(
    val status: String? = null,      // online / away / dnd / invisible
    val doing: String? = null,
    val trackId: String? = null,
    val positionSec: Int? = null,
)

/** Запрос текущих статусов, например участников открытой беседы. До 200 id за раз. */
data class PresenceQueryIn(val userIds: List<UUID> = emptyList())

// ---- звонки ----

data class CallInviteIn(val chatId: UUID, val callId: UUID, val kind: String)
data class CallAcceptIn(val chatId: UUID, val callId: UUID)
data class CallDeclineIn(val chatId: UUID, val callId: UUID, val reason: String? = null)
data class CallLeaveIn(val chatId: UUID, val callId: UUID)

data class CallSignalIn(
    val chatId: UUID,
    val callId: UUID,
    val toUserId: UUID? = null,   // null = всем остальным принявшим звонок
    val kind: String,             // offer / answer / candidate
    val payload: JsonNode,        // sdp или ice-candidate как есть, сервер не разбирает
)
