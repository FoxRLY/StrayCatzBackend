package org.example.proto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import org.example.rest.AttachmentOut
import org.example.rest.ForwardOriginOut
import org.example.rest.GifOut
import org.example.rest.PostCommunityOut
import org.example.rest.ReactionOut
import org.example.rest.ReplyPreviewOut
import org.example.rest.StickerOut
import org.example.rest.TrackOut
import org.example.rest.UserShortOut
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
    /** Репост записи («поделиться»): GET /api/posts/{id} за карточкой. */
    val sharedPostId: UUID? = null,
    /** Картинки/гифки/видео к сообщению (у удалённого — пусто). */
    val attachments: List<AttachmentOut> = emptyList(),
    /** Прикреплённые треки (inLibrary здесь всегда false). */
    val tracks: List<TrackOut> = emptyList(),
    /** Карточка пересланной записи — чтобы нарисовать превью без лишнего запроса. */
    val sharedPost: SharedPostOut? = null,
    /** Ответ на сообщение — превью того, на что отвечают. */
    val replyTo: ReplyPreviewOut? = null,
    /** Переслано от … (автор и время оригинала). */
    val forwardedFrom: ForwardOriginOut? = null,
    /** Гифка или внешний стикер (kind = sticker — рисовать без пузыря). */
    val gif: GifOut? = null,
    /** Стикер из набора — рисовать без пузыря. */
    val sticker: StickerOut? = null,
    /** Реакции: [{emoji, count, userIds}] — «моя» = userIds содержит меня. */
    val reactions: List<ReactionOut> = emptyList(),
    /** Кого упомянули @ником (участники чата) — для подсветки. */
    val mentions: List<UserShortOut> = emptyList(),
)

/**
 * Превью пересланной записи внутри сообщения. Полная запись — GET /api/posts/{id}.
 * deleted = true — запись удалена (остальные поля пустые).
 */
data class SharedPostOut(
    val id: UUID,
    val deleted: Boolean = false,
    /** community / pulse / wall */
    val source: String? = null,
    val kind: String? = null,
    val title: String? = null,
    /** Начало текста, до 200 символов. */
    val text: String? = null,
    val author: UserShortOut? = null,
    val community: PostCommunityOut? = null,
    val asCommunity: Boolean = false,
    val wallOwner: UserShortOut? = null,
    /** Первое вложение (картинка или видео) — обложка превью. */
    val cover: AttachmentOut? = null,
    val attachmentsCount: Int = 0,
    val createdAt: Instant? = null,
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

/** what: look / links / guestbook */
data class RoomUpdatedOut(val ownerId: UUID, val what: String)

data class TypingOut(val chatId: UUID, val userId: UUID)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PresenceOut(
    val userId: UUID,
    val username: String,
    val status: String,
    val doing: String? = null,
    val listening: ListeningOut? = null,
    /** Последнее действие: «был(а) 5 минут назад». У invisible — null. */
    val lastSeenAt: java.time.Instant? = null,
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
