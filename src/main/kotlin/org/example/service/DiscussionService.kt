package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Chat
import org.example.domain.ChatMember
import org.example.domain.ChatMemberId
import org.example.domain.Community
import org.example.rest.ApiException
import org.example.rest.DiscussionOut
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Обсуждения сообщества = большие чаты (chat.room_type = 'community').
 * Зайти в обсуждение = стать участником этого чата; дальше всё как в обычном
 * чате: история — GET /api/chats/{id}/messages, сообщения — по сокету.
 * Заходить могут только участники сообщества (не «читающие без вступления»).
 */
@ApplicationScoped
class DiscussionService(
    private val em: EntityManager,
    private val communities: CommunityService,
    private val bus: org.example.bus.EventBus,
) {
    companion object {
        const val ROOM_COMMUNITY = "community"
        const val MAX_TITLE = 120
        const val HOT_PER_MINUTE = 5
    }

    /** Закреплённые сверху, дальше — где свежее сообщение. */
    @Transactional
    fun list(slug: String, me: UUID): List<DiscussionOut> {
        val c = communities.bySlug(slug)
        val chats = Chat.list("communityId = ?1 and isDeleted = false", c.id)
        return toOut(chats, me).sortedWith(
            compareByDescending<DiscussionOut> { it.pinned }.thenByDescending { it.lastAt ?: Instant.EPOCH },
        )
    }

    @Transactional
    fun create(me: UUID, slug: String, rawTitle: String?): DiscussionOut {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "member")
        val title = rawTitle?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE }
            ?: throw ApiException.badRequest("invalid_title", "тема: 1–$MAX_TITLE символов")
        val chat = Chat().also {
            it.id = UUID.randomUUID()
            it.name = title
            it.roomType = ROOM_COMMUNITY
            it.communityId = c.id
            it.createdBy = me
        }
        chat.persist()
        ChatMember().also { it.id = ChatMemberId(chat.id, me) }.persist()
        bus.membershipChanged(listOf(me))
        return toOut(listOf(chat), me).first()
    }

    /** «Зайти» — нажатием. После этого чат доступен по сокету и в GET /api/chats. */
    @Transactional
    fun enter(me: UUID, slug: String, chatId: UUID): DiscussionOut {
        val (c, chat) = find(slug, chatId)
        communities.requireRole(c, me, "member")
        val m = ChatMember.findById(ChatMemberId(chat.id, me))
        when {
            m == null -> ChatMember().also { it.id = ChatMemberId(chat.id, me) }.persist()
            m.isDeleted -> { m.isDeleted = false; m.deletedAt = null }
        }
        bus.membershipChanged(listOf(me))
        return toOut(listOf(chat), me).first()
    }

    @Transactional
    fun leave(me: UUID, slug: String, chatId: UUID) {
        val (_, chat) = find(slug, chatId)
        ChatMember.findById(ChatMemberId(chat.id, me))?.let { it.isDeleted = true; it.deletedAt = Instant.now() }
        bus.membershipChanged(listOf(me))
    }

    @Transactional
    fun pin(me: UUID, slug: String, chatId: UUID, pinned: Boolean): DiscussionOut {
        val (c, chat) = find(slug, chatId)
        communities.requireRole(c, me, "admin")
        chat.pinned = pinned
        return toOut(listOf(chat), me).first()
    }

    @Transactional
    fun delete(me: UUID, slug: String, chatId: UUID) {
        val (c, chat) = find(slug, chatId)
        if (chat.createdBy != me) communities.requireRole(c, me, "admin")
        chat.isDeleted = true
        chat.deletedAt = Instant.now()
        // все участники: их ноды отпишутся от чата
        bus.membershipChanged(ChatMember.list("id.chatId = ?1 and isDeleted = false", chat.id).map { it.id.userId })
    }

    // ------------------------------------------------------------------

    private fun find(slug: String, chatId: UUID): Pair<Community, Chat> {
        val c = communities.bySlug(slug)
        val chat = Chat.findById(chatId)
        if (chat == null || chat.isDeleted || chat.communityId != c.id) throw ApiException.notFound("обсуждение не найдено")
        return c to chat
    }

    /** Для ленты: обсуждения пачкой по chatId (удалённые пропускаются). */
    @Transactional
    fun byIds(chatIds: Collection<UUID>, me: UUID): Map<UUID, DiscussionOut> {
        if (chatIds.isEmpty()) return emptyMap()
        return toOut(Chat.list("id in ?1 and isDeleted = false", chatIds.distinct()), me).associateBy { it.chatId }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toOut(chats: List<Chat>, me: UUID): List<DiscussionOut> {
        if (chats.isEmpty()) return emptyList()
        val ids = chats.map { it.id }
        val rows = em.createNativeQuery(
            """
            select c.id,
                   coalesce((select s.next_seq - 1 from chat_seq s where s.chat_id = c.id), 0),
                   (select max(m.created_at) from message m where m.chat_id = c.id),
                   (select count(*) from message m where m.chat_id = c.id and m.created_at > now() - interval '1 minute'),
                   (select count(*) from chat_member cm where cm.chat_id = c.id and not cm.is_deleted),
                   exists (select 1 from chat_member cm where cm.chat_id = c.id and cm.user_id = ?2 and not cm.is_deleted)
            from chat c where c.id in (?1)
            """.trimIndent(),
        ).setParameter(1, ids).setParameter(2, me).resultList as List<Array<Any?>>
        val stats = rows.associateBy { it[0] as UUID }
        return chats.map { chat ->
            val r = stats[chat.id]
            DiscussionOut(
                chatId = chat.id,
                title = chat.name ?: "обсуждение",
                replies = (r?.get(1) as Number?)?.toLong() ?: 0,
                lastAt = toInstant(r?.get(2)),
                pinned = chat.pinned,
                hot = ((r?.get(3) as Number?)?.toLong() ?: 0) > HOT_PER_MINUTE,
                participants = (r?.get(4) as Number?)?.toLong() ?: 0,
                joined = r?.get(5) as Boolean? ?: false,
            )
        }
    }

    private fun toInstant(v: Any?): Instant? = when (v) {
        null -> null
        is Instant -> v
        is OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> null
    }
}
