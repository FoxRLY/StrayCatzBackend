package org.example.ws.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.example.ws.bus.EventBus
import org.example.ws.domain.ChatMember
import org.example.ws.domain.ChatMemberId
import org.example.ws.domain.ChatSeqEntity
import org.example.ws.domain.Message
import org.example.ws.proto.ChatReadOut
import org.example.ws.proto.Envelope
import org.example.ws.proto.FrameTypes
import org.example.ws.proto.MessageAckOut
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import kotlinx.coroutines.runBlocking
import java.util.UUID

class MessageValidationException(message: String) : RuntimeException(message)

@ApplicationScoped
class MessageService(
    private val bus: EventBus,
    private val mapper: ObjectMapper,
) {
    companion object { const val MAX_BODY_LEN = 4000 }

    @Transactional
    fun send(chatId: UUID, authorId: UUID, body: String?, mediaId: UUID?, clientToken: UUID, rid: String): MessageAckOut {
        if (body.isNullOrBlank() && mediaId == null) {
            throw MessageValidationException("пустое сообщение: нужен body или mediaId")
        }
        if ((body?.length ?: 0) > MAX_BODY_LEN) {
            throw MessageValidationException("тело сообщения длиннее $MAX_BODY_LEN символов")
        }

        val seqRow = lockOrCreateChatSeq(chatId)
        val seq = seqRow.nextSeq
        seqRow.nextSeq = seq + 1

        val message = Message().also {
            it.id = UUID.randomUUID()
            it.chatId = chatId
            it.seq = seq
            it.authorId = authorId
            it.clientToken = clientToken
            it.body = body
            it.mediaId = mediaId
        }
        message.persist()

        runBlocking { bus.publishPointerToChat(chatId, "message", message.id.toString()) }

        return MessageAckOut(rid, clientToken, message.id, seq, message.createdAt)
    }

    private fun lockOrCreateChatSeq(chatId: UUID): ChatSeqEntity {
        ChatSeqEntity.findById(chatId, LockModeType.PESSIMISTIC_WRITE)?.let { return it }
        return try {
            ChatSeqEntity().also { it.chatId = chatId; it.nextSeq = 1 }.apply { persist() }
        } catch (e: Exception) {
            ChatSeqEntity.findById(chatId, LockModeType.PESSIMISTIC_WRITE)
                ?: throw IllegalStateException("chat_seq для $chatId пропал между вставкой и повторным чтением")
        }
    }

    @Transactional
    fun markRead(chatId: UUID, userId: UUID, seq: Long) {
        val member = ChatMember.findById(ChatMemberId(chatId, userId)) ?: return
        if (seq > member.lastReadSeq) member.lastReadSeq = seq

        val frame = Envelope(
            t = FrameTypes.CHAT_READ,
            id = UUID.randomUUID().toString(),
            d = mapper.valueToTree(ChatReadOut(chatId, userId, seq)),
        )
        runBlocking { bus.publishToChat(chatId, frame) }
    }
}