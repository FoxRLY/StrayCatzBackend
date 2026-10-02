package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.example.domain.Chat
import org.example.domain.ChatMember
import org.example.domain.ChatSeqEntity
import org.example.proto.ChatSummaryOut
import org.example.proto.MissedRangeOut
import java.util.UUID

/**
 * Членство с учётом мягкого удаления: и chat_member.is_deleted, и chat.is_deleted.
 */
@ApplicationScoped
class ChatService {

    private val activeMembership =
        "isDeleted = false and id.chatId in (select c.id from Chat c where c.isDeleted = false)"

    @Transactional
    fun isMember(chatId: UUID, userId: UUID): Boolean =
        ChatMember.count("id.chatId = ?1 and id.userId = ?2 and $activeMembership", chatId, userId) > 0

    @Transactional
    fun memberChatIds(userId: UUID): Set<UUID> =
        activeMembershipsOf(userId).map { it.id.chatId }.toSet()

    @Transactional
    fun activeMemberIds(chatId: UUID): List<UUID> =
        ChatMember.list("id.chatId = ?1 and isDeleted = false", chatId).map { it.id.userId }

    @Transactional
    fun summaries(userId: UUID): List<ChatSummaryOut> {
        val members = activeMembershipsOf(userId)
        if (members.isEmpty()) return emptyList()

        val chatIds = members.map { it.id.chatId }
        val chatsById = Chat.list("id in ?1", chatIds).associateBy { it.id }
        val seqByChat = ChatSeqEntity.list("chatId in ?1", chatIds).associateBy { it.chatId }

        return members.mapNotNull { m ->
            val chat = chatsById[m.id.chatId] ?: return@mapNotNull null
            // чат стрима — не беседа: без него в ready, иначе висел бы в непрочитанных
            if (chat.roomType == "stream") return@mapNotNull null
            val lastSeq = (seqByChat[m.id.chatId]?.nextSeq ?: 1L) - 1
            ChatSummaryOut(m.id.chatId, chat.roomType, chat.name, lastSeq, m.lastReadSeq)
        }
    }

    /**
     * Консервативно: "дыра" = всё новее last_read_seq. См. README про lastEventId.
     */
    @Transactional
    fun missedFrom(userId: UUID, requestedChats: Collection<UUID>): List<MissedRangeOut> {
        if (requestedChats.isEmpty()) return emptyList()
        val members = ChatMember.list(
            "id.userId = ?1 and id.chatId in ?2 and $activeMembership", userId, requestedChats.toList(),
        )
        if (members.isEmpty()) return emptyList()
        val seqByChat = ChatSeqEntity.list("chatId in ?1", members.map { it.id.chatId })
            .associateBy { it.chatId }

        return members.mapNotNull { m ->
            val lastSeq = (seqByChat[m.id.chatId]?.nextSeq ?: 1L) - 1
            if (lastSeq > m.lastReadSeq) MissedRangeOut(m.id.chatId, m.lastReadSeq + 1) else null
        }
    }

    private fun activeMembershipsOf(userId: UUID): List<ChatMember> =
        ChatMember.list("id.userId = ?1 and $activeMembership", userId)
}
