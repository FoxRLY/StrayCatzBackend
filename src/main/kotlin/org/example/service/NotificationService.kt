package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.domain.NotificationEntity
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.rest.ApiException
import org.example.rest.NotificationOut
import org.example.rest.NotificationsPageOut
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Уведомления: строка в notification + живой кадр `notification.new` в сокет,
 * если человек онлайн. Список/прочитанность — через REST.
 */
@ApplicationScoped
class NotificationService(
    private val bus: EventBus,
    private val mapper: ObjectMapper,
    private val profiles: UserProfileService,
) {
    companion object {
        const val ROOM_INVITE = "room_invite"
        const val GUESTBOOK_ENTRY = "guestbook_entry"
        const val POST_COMMENT = "post_comment"
        const val COMMENT_REPLY = "comment_reply"
        const val EVENT_NEW = "event_new"
        const val EVENT_REGISTERED = "event_registered"
        const val EVENT_REMINDER = "event_reminder"
        const val EVENT_CANCELLED = "event_cancelled"
        /** Друг или сообщество вышли в эфир. payload: {streamId, title, communitySlug?} */
        const val STREAM_LIVE = "stream_live"
        /** Упомянули @ником. payload: {where: message|post|comment, chatId?, chatName?, messageId?, postId?, commentId?, preview} */
        const val MENTION = "mention"
        /** Заявка в друзья. actor — кто хочет дружить. */
        const val FRIEND_REQUEST = "friend_request"
        /** Заявку приняли. actor — кто принял. */
        const val FRIEND_ACCEPTED = "friend_accepted"
        const val MAX_PAGE = 100
    }

    @Transactional
    fun notify(userId: UUID, kind: String, actorId: UUID?, payload: Map<String, Any?> = emptyMap()): NotificationEntity {
        val n = NotificationEntity().also {
            it.id = UUID.randomUUID()
            it.userId = userId
            it.kind = kind
            it.actorId = actorId
            it.payload = mapper.writeValueAsString(payload)
        }
        n.persist()
        val frame = Envelope(t = FrameTypes.NOTIFICATION_NEW, d = mapper.valueToTree(toOut(listOf(n)).first()))
        bus.publishToUsers(listOf(userId), frame) // после COMMIT
        return n
    }

    /** Было ли такое уведомление от actor к user за последние [window] — для антиспама. */
    @Transactional
    fun sentRecently(userId: UUID, actorId: UUID, kind: String, window: Duration): Boolean =
        NotificationEntity.count(
            "userId = ?1 and actorId = ?2 and kind = ?3 and createdAt > ?4",
            userId, actorId, kind, Instant.now().minus(window),
        ) > 0

    /** status: all / unread / read. */
    @Transactional
    fun list(me: UUID, status: String, before: Instant?, limit: Int): NotificationsPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val q = buildString {
            append("userId = ?1 and createdAt < ?2")
            when (status.lowercase()) {
                "all", "" -> {}
                "unread" -> append(" and readAt is null")
                "read" -> append(" and readAt is not null")
                else -> throw ApiException.badRequest("invalid_status", "status: all, unread или read")
            }
        }
        val rows = NotificationEntity.find(q, Sort.descending("createdAt"), me, before ?: Instant.now().plusSeconds(1))
            .range(0, size).list()
        return NotificationsPageOut(toOut(rows.take(size)), unreadCount(me), rows.size > size)
    }

    @Transactional
    fun unreadCount(me: UUID): Long = NotificationEntity.count("userId = ?1 and readAt is null", me)

    @Transactional
    fun markRead(me: UUID, id: UUID): Long {
        val n = own(me, id)
        if (n.readAt == null) n.readAt = Instant.now()
        return pushState(me)
    }

    /** Вернуть в непрочитанные. */
    @Transactional
    fun markUnread(me: UUID, id: UUID): Long {
        own(me, id).readAt = null
        return pushState(me)
    }

    /** Прочитать пачкой (например, всё, что видно на экране). */
    @Transactional
    fun markReadMany(me: UUID, ids: List<UUID>): Long {
        if (ids.isNotEmpty()) {
            NotificationEntity.update(
                "readAt = ?1 where userId = ?2 and readAt is null and id in ?3", Instant.now(), me, ids.distinct(),
            )
        }
        return pushState(me)
    }

    @Transactional
    fun markAllRead(me: UUID): Long {
        NotificationEntity.update("readAt = ?1 where userId = ?2 and readAt is null", Instant.now(), me)
        return pushState(me)
    }

    @Transactional
    fun delete(me: UUID, id: UUID): Long {
        own(me, id).delete()
        return pushState(me)
    }

    private fun own(me: UUID, id: UUID): NotificationEntity {
        val n = NotificationEntity.findById(id)
        if (n == null || n.userId != me) throw ApiException.notFound("уведомление не найдено")
        return n
    }

    /**
     * Счётчик непрочитанных во все вкладки/устройства: кадр notification.state {unread}.
     * Так бейдж гаснет везде, когда прочитал в одном месте.
     */
    private fun pushState(me: UUID): Long {
        val unread = unreadCount(me)
        val frame = Envelope(t = FrameTypes.NOTIFICATION_STATE, d = mapper.valueToTree(mapOf("unread" to unread)))
        bus.publishToUsers(listOf(me), frame)
        return unread
    }

    private fun toOut(rows: List<NotificationEntity>): List<NotificationOut> {
        val actors = profiles.shorts(rows.mapNotNull { it.actorId })
        return rows.map {
            NotificationOut(it.id, it.kind, it.actorId?.let { a -> actors[a] }, mapper.readTree(it.payload), it.createdAt, it.readAt)
        }
    }
}
