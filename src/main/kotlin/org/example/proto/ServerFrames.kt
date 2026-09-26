package org.example.proto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

// ---- d-пейлоады исходящих (сервер -> клиент) кадров ----

data class MeOut(val userId: UUID, val username: String)

data class ChatSummaryOut(
    val chatId: UUID,
    val roomType: String,
    val name: String?,
    val lastSeq: Long,
    val lastReadSeq: Long,
)

data class MissedRangeOut(val chatId: UUID, val fromSeq: Long)

data class ReadyOut(
    val me: MeOut,
    val chats: List<ChatSummaryOut>,
    val missedFrom: List<MissedRangeOut> = emptyList(),
    /** Текущие статусы друзей — чтобы не ждать первого presence.changed. */
    val presence: List<PresenceOut> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageOut(
    val id: UUID,
    val chatId: UUID,
    val seq: Long,
    val userId: UUID,
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

/** reason: created / member_added / member_left */
data class ChatUpdatedOut(val chatId: UUID, val reason: String, val userId: UUID? = null)

/** state с точки зрения получателя: incoming / friends / none */
data class FriendUpdatedOut(val userId: UUID, val state: String)

data class TypingOut(val chatId: UUID, val userId: UUID)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PresenceOut(
    val userId: UUID,
    val username: String,
    val status: String,
    val doing: String? = null,
    val listening: ListeningOut? = null,
)

data class PresenceSnapshotOut(val users: List<PresenceOut>)

data class ListeningOut(val trackId: String, val positionSec: Int)

data class FeedBumpOut(val count: Int)

data class ErrorOut(val rid: String? = null, val code: String, val message: String)

// ---- звонки ----

data class CallRingingOut(val chatId: UUID, val callId: UUID, val fromUserId: UUID, val kind: String)
data class CallAcceptedOut(val chatId: UUID, val callId: UUID, val userId: UUID)
data class CallDeclinedOut(val chatId: UUID, val callId: UUID, val userId: UUID, val reason: String?)
data class CallLeftOut(val chatId: UUID, val callId: UUID, val userId: UUID)
data class CallEndedOut(val chatId: UUID, val callId: UUID, val reason: String)

data class CallSignalOut(
    val chatId: UUID,
    val callId: UUID,
    val fromUserId: UUID,
    val kind: String,
    val payload: JsonNode,
)

object ErrorCodes {
    const val BAD_FRAME = "bad_frame"
    const val NOT_A_MEMBER = "not_a_member"
    const val RATE_LIMITED = "rate_limited"
    const val UNKNOWN_TYPE = "unknown_type"
    const val CALL_ERROR = "call_error"
    const val FORBIDDEN = "forbidden"
    const val INTERNAL = "internal"
}
