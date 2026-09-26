package org.example.bus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.example.domain.CallSignalEntity
import org.example.domain.ChatMember
import org.example.domain.Message
import org.example.proto.CallSignalOut
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.proto.MessageOut
import java.util.UUID

/**
 * Нода-получатель превращает указатель из NOTIFY в готовый кадр,
 * дочитав строку из БД. Отдельный бин — чтобы @Transactional работал
 * через CDI-прокси при вызове из EventBus.
 */
@ApplicationScoped
class BusResolver(private val mapper: ObjectMapper) {

    @Transactional
    fun resolve(p: Pointer): JsonNode? = when (p.kind) {
        PointerKinds.MESSAGE_NEW, PointerKinds.MESSAGE_UPDATED -> {
            val m = Message.findById(UUID.fromString(p.id))
            m?.let {
                val t = if (p.kind == PointerKinds.MESSAGE_NEW) FrameTypes.MESSAGE_NEW else FrameTypes.MESSAGE_UPDATED
                frame(t, toOut(it))
            }
        }

        PointerKinds.CALL_SIGNAL -> CallSignalEntity.findById(p.id.toLong())?.let {
            frame(
                FrameTypes.CALL_SIGNAL,
                CallSignalOut(it.chatId, it.callId, it.fromUserId, it.kind, mapper.readTree(it.payload)),
            )
        }

        else -> throw IllegalArgumentException("неизвестный тип указателя: ${p.kind}")
    }

    @Transactional
    fun activeMemberIds(chatId: UUID): List<UUID> =
        ChatMember.list("id.chatId = ?1 and isDeleted = false", chatId).map { it.id.userId }

    private fun frame(t: String, payload: Any): JsonNode =
        mapper.valueToTree(Envelope(t = t, d = mapper.valueToTree(payload)))

    companion object {
        fun toOut(m: Message) = MessageOut(
            id = m.id,
            chatId = m.chatId,
            seq = m.seq,
            userId = m.userId,
            // удалённое сообщение отдаём без тела
            body = if (m.deletedAt != null) null else m.body,
            mediaId = if (m.deletedAt != null) null else m.mediaId,
            createdAt = m.createdAt,
            editedAt = m.editedAt,
            deletedAt = m.deletedAt,
        )
    }
}
