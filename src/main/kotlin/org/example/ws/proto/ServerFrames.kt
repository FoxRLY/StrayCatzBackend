package org.example.ws.proto

import java.time.Instant
import java.util.UUID

// ---- d-пейлоады исходящих (сервер -> клиент) кадров ----

data class MeOut(val userId: UUID, val handle: String)

data class ChatSummaryOut(
    val chatId: UUID,
    val isGroup: Boolean,
    val title: String?,
    val lastSeq: Long,
    val lastReadSeq: Long,
)

data class MissedRangeOut(val chatId: UUID, val fromSeq: Long)

data class ReadyOut(
    val me: MeOut,
    val chats: List<ChatSummaryOut>,
    val missedFrom: List<MissedRangeOut> = emptyList(),
)

data class MessageOut(
    val id: UUID,
    val chatId: UUID,
    val seq: Long,
    val authorId: UUID,
    val body: String?,
    val mediaId: UUID?,
    val createdAt: Instant,
    val editedAt: Instant? = null,
    val deletedAt: Instant? = null,
)

data class MessageAckOut(
    val rid: String,
    val clientToken: UUID,
    val id: UUID,
    val seq: Long,
    val createdAt: Instant,
)

data class ChatReadOut(val chatId: UUID, val userId: UUID, val seq: Long)

data class TypingOut(val chatId: UUID, val userId: UUID)

data class PresenceChangedOut(
    val handle: String,
    val status: String,
    val doing: String? = null,
    val listening: ListeningOut? = null,
)

data class ListeningOut(val trackId: String, val positionSec: Int)

data class FeedBumpOut(val count: Int)

data class ErrorOut(val rid: String? = null, val code: String, val message: String)

// ---- звонки ----

data class CallRingingOut(val chatId: UUID, val callId: UUID, val fromUserId: UUID, val kind: String)
data class CallAcceptedOut(val chatId: UUID, val callId: UUID, val userId: UUID)
data class CallDeclinedOut(val chatId: UUID, val callId: UUID, val userId: UUID, val reason: String?)
data class CallEndedOut(val chatId: UUID, val callId: UUID, val reason: String)

data class CallSignalOut(
    val chatId: UUID,
    val callId: UUID,
    val fromUserId: UUID,
    val kind: String,
    val payload: Any,
)

object ErrorCodes {
    const val BAD_FRAME = "bad_frame"
    const val NOT_A_MEMBER = "not_a_member"
    const val RATE_LIMITED = "rate_limited"
    const val UNKNOWN_TYPE = "unknown_type"
    const val CALL_NOT_FOUND = "call_not_found"
}
