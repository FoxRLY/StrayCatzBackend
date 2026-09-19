package org.example.ws.service

import org.example.ws.domain.Chat
import org.example.ws.domain.ChatMember
import org.example.ws.domain.ChatMemberId
import org.example.ws.domain.ChatSeqEntity
import org.example.ws.proto.ChatSummaryOut
import org.example.ws.proto.MissedRangeOut
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID

@ApplicationScoped
class ChatService {

    @Transactional
    fun isMember(chatId: UUID, userId: UUID): Boolean =
        ChatMember.findById(ChatMemberId(chatId, userId)) != null

    @Transactional
    fun memberChatIds(userId: UUID): Set<UUID> =
        ChatMember.find("id.userId", userId).list().map { it.id.chatId }.toSet()

    @Transactional
    fun summaries(userId: UUID): List<ChatSummaryOut> {
        val members = ChatMember.find("id.userId", userId).list()
        if (members.isEmpty()) return emptyList()

        val chatIds = members.map { it.id.chatId }
        val chatsById = Chat.list("id in ?1", chatIds).associateBy { it.id }
        val seqByChat = ChatSeqEntity.list("chatId in ?1", chatIds).associateBy { it.chatId }

        return members.mapNotNull { m ->
            val chat = chatsById[m.id.chatId] ?: return@mapNotNull null
            val lastSeq = (seqByChat[m.id.chatId]?.nextSeq ?: 1L) - 1
            ChatSummaryOut(m.id.chatId, chat.isGroup, chat.title, lastSeq, m.lastReadSeq)
        }
    }

    @Transactional
    fun missedFrom(userId: UUID, requestedChats: List<UUID>): List<MissedRangeOut> {
        if (requestedChats.isEmpty()) return emptyList()
        val members = ChatMember.list("id.userId = ?1 and id.chatId in ?2", userId, requestedChats)
        val seqByChat = ChatSeqEntity.list("chatId in ?1", requestedChats).associateBy { it.chatId }

        return members.mapNotNull { m ->
            val lastSeq = (seqByChat[m.id.chatId]?.nextSeq ?: 1L) - 1
            if (lastSeq > m.lastReadSeq) MissedRangeOut(m.id.chatId, m.lastReadSeq + 1) else null
        }
    }
}