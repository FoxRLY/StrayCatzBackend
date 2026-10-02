package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.BusResolver
import org.example.bus.EventBus
import org.example.domain.AppUser
import org.example.domain.Chat
import org.example.domain.ChatMember
import org.example.domain.ChatMemberId
import org.example.domain.ChatSeqEntity
import org.example.domain.Message
import org.example.proto.ChatUpdatedOut
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.rest.ApiException
import org.example.rest.ChatDetailsOut
import org.example.rest.ChatListItemOut
import org.example.rest.ChatMemberOut
import org.example.rest.CreateChatIn
import org.example.rest.MessagePageOut
import java.time.Instant
import java.util.UUID

/**
 * REST-часть чатов: список, карточка, история, создание, участники.
 * Отправка/чтение сообщений — по сокету (MessageService).
 */
@ApplicationScoped
class ChatManagementService(
    private val em: EntityManager,
    private val bus: EventBus,
    private val mapper: ObjectMapper,
    private val profiles: UserProfileService,
    private val resolver: BusResolver,
) {
    companion object {
        const val ROOM_DIRECT = "direct"
        const val ROOM_GROUP = "group"
        const val ROOM_STREAM = "stream"
        const val MAX_PAGE = 100
        const val MAX_GROUP_MEMBERS = 200
        const val MAX_NAME = 100
    }

    // ---------------------------------------------------------------- чтение

    /** Мои чаты, свежие сверху: последнее сообщение, непрочитанные, собеседник для лички. */
    @Transactional
    fun listMine(me: UUID): List<ChatListItemOut> {
        val memberships = ChatMember.list(
            // чаты стримов — не беседы: в список не попадают (у них своя страница)
            "id.userId = ?1 and isDeleted = false and id.chatId in " +
                    "(select c.id from Chat c where c.isDeleted = false and c.roomType <> '$ROOM_STREAM')",
            me,
        )
        if (memberships.isEmpty()) return emptyList()
        val chatIds = memberships.map { it.id.chatId }

        val chats = Chat.list("id in ?1", chatIds).associateBy { it.id }
        val lastSeq = ChatSeqEntity.list("chatId in ?1", chatIds).associate { it.chatId to it.nextSeq - 1 }
        val lastMessages = lastMessages(chatIds)
        val lastRendered = resolver.render(lastMessages.values.toList()).associateBy { it.chatId }

        // собеседники в личках
        val directIds = chats.values.filter { it.roomType == ROOM_DIRECT }.map { it.id }
        val peerByChat: Map<UUID, UUID> = if (directIds.isEmpty()) emptyMap() else
            ChatMember.list("id.chatId in ?1 and id.userId <> ?2", directIds, me)
                .associate { it.id.chatId to it.id.userId }
        val peers = profiles.shorts(peerByChat.values)

        return memberships.mapNotNull { m ->
            val chat = chats[m.id.chatId] ?: return@mapNotNull null
            val last = lastSeq[chat.id] ?: 0L
            ChatListItemOut(
                chatId = chat.id,
                roomType = chat.roomType,
                name = chat.name,
                peer = peerByChat[chat.id]?.let { peers[it] },
                lastSeq = last,
                lastReadSeq = m.lastReadSeq,
                unread = (last - m.lastReadSeq).coerceAtLeast(0),
                lastMessage = lastRendered[chat.id],
                avatar = chat.avatar ?: peerByChat[chat.id]?.let { peers[it]?.avatar },
            )
        }.sortedByDescending { it.lastMessage?.createdAt ?: chats[it.chatId]?.createdAt ?: Instant.EPOCH }
    }

    @Transactional
    fun details(chatId: UUID, me: UUID): ChatDetailsOut {
        val chat = requireMember(chatId, me)
        val members = ChatMember.list("id.chatId = ?1 and isDeleted = false order by createdAt", chatId)
        val users = profiles.shorts(members.map { it.id.userId })
        val lastSeq = (ChatSeqEntity.findById(chatId)?.nextSeq ?: 1L) - 1
        return ChatDetailsOut(
            chatId = chat.id,
            roomType = chat.roomType,
            name = chat.name,
            createdAt = chat.createdAt,
            lastSeq = lastSeq,
            members = members.mapNotNull { m ->
                users[m.id.userId]?.let { ChatMemberOut(it, m.lastReadSeq, m.createdAt) }
            },
            avatar = chat.avatar ?: if (chat.roomType == ROOM_DIRECT) {
                members.firstOrNull { it.id.userId != me }?.let { users[it.id.userId]?.avatar }
            } else null,
        )
    }

    /**
     * История. Всегда отдаём по возрастанию seq.
     *  - без параметров     — последние `limit` сообщений (открыли чат);
     *  - before=<seq>       — более старые, чем seq (скролл вверх);
     *  - after=<seq>        — более новые, чем seq (догрузить дыру после
     *                          реконнекта, см. ready.missedFrom: after = fromSeq - 1).
     */
    @Transactional
    fun history(chatId: UUID, me: UUID, before: Long?, after: Long?, limit: Int): MessagePageOut {
        requireMember(chatId, me)
        val size = limit.coerceIn(1, MAX_PAGE)

        val rows = if (after != null) {
            Message.find("chatId = ?1 and seq > ?2", Sort.ascending("seq"), chatId, after)
                .range(0, size).list()
        } else {
            Message.find("chatId = ?1 and seq < ?2", Sort.descending("seq"), chatId, before ?: Long.MAX_VALUE)
                .range(0, size).list()
        }
        val hasMore = rows.size > size
        val page = rows.take(size).let { if (after != null) it else it.reversed() }
        return MessagePageOut(resolver.render(page), hasMore)
    }

    // ---------------------------------------------------------------- запись

    /** @return details + флаг "создан сейчас" (для 201 vs 200). */
    @Transactional
    fun create(me: UUID, req: CreateChatIn): Pair<ChatDetailsOut, Boolean> = when (req.type?.lowercase()) {
        ROOM_DIRECT -> createDirect(me, req.userId ?: throw ApiException.badRequest("invalid_chat", "нужен userId"))
        ROOM_GROUP -> createGroup(me, req.name, req.memberIds)
        else -> throw ApiException.badRequest("invalid_chat", "type: direct или group")
    }

    /** Личка с человеком: найти или создать. Для «переслать в личку» и т.п. */
    @Transactional
    fun directChatId(me: UUID, other: UUID): UUID = createDirect(me, other).first.chatId

    private fun createDirect(me: UUID, other: UUID): Pair<ChatDetailsOut, Boolean> {
        if (other == me) throw ApiException.badRequest("invalid_chat", "нельзя создать личку с собой")
        requireActiveUser(other)

        val key = listOf(me.toString(), other.toString()).sorted().joinToString(":")
        Chat.find("directKey = ?1 and isDeleted = false", key).firstResult()?.let { existing ->
            // уже есть — вернём её (и вернём в неё того, кто когда-то вышел)
            val reactivated = listOf(me, other).count { activateMember(existing.id, it) }
            if (reactivated > 0) notify(listOf(me, other), existing.id, "member_added", null)
            return details(existing.id, me) to false
        }

        val chat = Chat().also {
            it.id = UUID.randomUUID()
            it.roomType = ROOM_DIRECT
            it.directKey = key
        }
        chat.persist()
        activateMember(chat.id, me)
        activateMember(chat.id, other)
        notify(listOf(me, other), chat.id, "created", me)
        return details(chat.id, me) to true
    }

    private fun createGroup(me: UUID, rawName: String?, memberIds: List<UUID>): Pair<ChatDetailsOut, Boolean> {
        val name = rawName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
            ?: throw ApiException.badRequest("invalid_chat", "name: 1–$MAX_NAME символов")
        val ids = (memberIds + me).distinct()
        if (ids.size > MAX_GROUP_MEMBERS) {
            throw ApiException.badRequest("invalid_chat", "не больше $MAX_GROUP_MEMBERS участников")
        }
        val existing = AppUser.list("id in ?1 and isDeleted = false", ids).map { it.id }.toSet()
        val missing = ids - existing
        if (missing.isNotEmpty()) throw ApiException.notFound("пользователи не найдены: $missing")

        val chat = Chat().also {
            it.id = UUID.randomUUID()
            it.roomType = ROOM_GROUP
            it.name = name
        }
        chat.persist()
        ids.forEach { activateMember(chat.id, it) }
        notify(ids, chat.id, "created", me)
        return details(chat.id, me) to true
    }

    /** Добавить в группу может любой её участник. */
    @Transactional
    fun addMember(chatId: UUID, me: UUID, userId: UUID): ChatDetailsOut {
        val chat = requireMember(chatId, me)
        if (chat.roomType != ROOM_GROUP) throw ApiException.badRequest("invalid_chat", "добавлять можно только в группу")
        requireActiveUser(userId)
        if (activateMember(chatId, userId)) {
            notify(activeMemberIds(chatId), chatId, "member_added", userId)
        }
        return details(chatId, me)
    }

    /** Выйти из группы. Из лички не выходим — её можно просто не показывать на фронте. */
    @Transactional
    fun leave(chatId: UUID, me: UUID) {
        val chat = requireMember(chatId, me)
        if (chat.roomType != ROOM_GROUP) throw ApiException.badRequest("invalid_chat", "выйти можно только из группы")
        val m = ChatMember.findById(ChatMemberId(chatId, me)) ?: return
        m.isDeleted = true
        m.deletedAt = Instant.now()
        // уведомляем оставшихся и самого вышедшего (его вкладки уберут чат из списка)
        notify(activeMemberIds(chatId).filter { it != me } + me, chatId, "member_left", me)
    }

    // ---------------------------------------------------------------- utils

    private fun requireMember(chatId: UUID, me: UUID): Chat {
        val chat = Chat.findById(chatId)
        val m = ChatMember.findById(ChatMemberId(chatId, me))
        // одинаковый 404 и для "нет такого", и для "не твой" — не палим существование чата
        if (chat == null || chat.isDeleted || m == null || m.isDeleted) throw ApiException.notFound("чат не найден")
        return chat
    }

    private fun requireActiveUser(id: UUID) {
        val u = AppUser.findById(id)
        if (u == null || u.isDeleted) throw ApiException.notFound("пользователь не найден")
    }

    /** @return true, если участник добавлен или возвращён (был удалён). */
    private fun activateMember(chatId: UUID, userId: UUID): Boolean {
        val m = ChatMember.findById(ChatMemberId(chatId, userId))
        if (m == null) {
            ChatMember().also { it.id = ChatMemberId(chatId, userId) }.persist()
            return true
        }
        if (m.isDeleted) {
            m.isDeleted = false
            m.deletedAt = null
            return true
        }
        return false
    }

    private fun activeMemberIds(chatId: UUID): List<UUID> =
        ChatMember.list("id.chatId = ?1 and isDeleted = false", chatId).map { it.id.userId }

    private fun notify(userIds: Collection<UUID>, chatId: UUID, reason: String, userId: UUID?) {
        val frame = Envelope(t = FrameTypes.CHAT_UPDATED, d = mapper.valueToTree(ChatUpdatedOut(chatId, reason, userId)))
        bus.publishToUsers(userIds.distinct(), frame) // уйдёт после COMMIT
    }

    @Suppress("UNCHECKED_CAST")
    private fun lastMessages(chatIds: List<UUID>): Map<UUID, Message> {
        val rows = em.createNativeQuery(
            """
            select m.* from message m
            join chat_seq s on s.chat_id = m.chat_id and m.seq = s.next_seq - 1
            where m.chat_id in (?1)
            """.trimIndent(),
            Message::class.java,
        ).setParameter(1, chatIds).resultList as List<Message>
        return rows.associateBy { it.chatId }
    }
}
