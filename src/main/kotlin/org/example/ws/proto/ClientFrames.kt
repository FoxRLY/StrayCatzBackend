package org.example.ws.proto

import java.util.UUID

// ---- d-пейлоады входящих (клиент -> сервер) кадров ----
// Jackson мапит по полям конструктора, лишнего в d не бывает — но если
// придёт, оно просто игнорируется (FAIL_ON_UNKNOWN_PROPERTIES = false,
// см. JacksonConfig).

data class HelloIn(
    val lastEventId: String? = null,
    val chats: List<UUID> = emptyList(),
)

data class ChatOpenIn(val chatId: UUID)
data class ChatCloseIn(val chatId: UUID)

data class MessageSendIn(
    val chatId: UUID,
    val body: String? = null,
    val mediaId: UUID? = null,
    val clientToken: UUID,
)

data class MessageReadIn(val chatId: UUID, val seq: Long)

data class TypingIn(val chatId: UUID)

data class PresenceSetIn(
    val status: String? = null,
    val doing: String? = null,
    val trackId: String? = null,
    val positionSec: Int? = null,
)

// ---- звонки ----

data class CallInviteIn(val chatId: UUID, val callId: UUID, val kind: String)
data class CallAcceptIn(val chatId: UUID, val callId: UUID)
data class CallDeclineIn(val chatId: UUID, val callId: UUID, val reason: String? = null)
data class CallLeaveIn(val chatId: UUID, val callId: UUID)

data class CallSignalIn(
    val chatId: UUID,
    val callId: UUID,
    val toUserId: UUID? = null,   // null = всем остальным участникам звонка
    val kind: String,             // offer / answer / candidate
    val payload: Any,             // sdp-объект или ice-candidate как есть, сервер его не разбирает
)
