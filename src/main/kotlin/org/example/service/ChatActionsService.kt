package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.BusResolver
import org.example.bus.EventBus
import org.example.domain.Chat
import org.example.domain.ChatMember
import org.example.domain.Media
import org.example.domain.Message
import org.example.proto.ChatUpdatedOut
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.rest.ApiException
import org.example.rest.ChatPatchIn
import org.example.rest.ForwardIn
import org.example.rest.ForwardResultOut
import org.example.rest.ForwardSentOut
import org.example.rest.MessageReactionsOut
import java.util.UUID

/**
 * Чат «как в телеге» поверх обычных сообщений: пересылка, реакции, название и
 * аватар беседы. Ответ (replyToId), гифки и стикеры — прямо в message.send.
 */
@ApplicationScoped
class ChatActionsService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bus: EventBus,
    private val chats: ChatService,
    private val chatAdmin: ChatManagementService,
    private val messages: MessageService,
    private val resolver: BusResolver,
    private val media: MediaService,
) {
    companion object {
        const val MAX_FORWARD = 50
        const val MAX_TARGETS = 10
        const val MAX_COPIES = 100
        const val MAX_MY_REACTIONS = 3
        const val MAX_EMOJIS = 20
        const val MAX_NAME = 100
        /** Реакция — эмодзи (или несколько кодпоинтов одного эмодзи), а не слово. */
        private val PLAIN_WORD = Regex("^[A-Za-z0-9_\\p{IsCyrillic}]+$")
    }

    // ================================================================ пересылка

    /**
     * Переслать сообщения чата fromChatId в другие чаты и/или людям в личку.
     * Копии сохраняют «переслано от» (автор и время оригинала; пересланное
     * дальше — указывает на самый первый оригинал). Порядок — как в исходном чате.
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun forward(me: UUID, fromChatId: UUID, req: ForwardIn): ForwardResultOut {
        requireMember(fromChatId, me)
        val ids = req.messageIds.distinct()
        if (ids.isEmpty() || ids.size > MAX_FORWARD) throw ApiException.badRequest("invalid_messages", "messageIds: от 1 до $MAX_FORWARD")
        val msgs = Message.list("id in ?1 and chatId = ?2 and deletedAt is null", ids, fromChatId).sortedBy { it.seq }
        if (msgs.isEmpty()) throw ApiException.notFound("сообщения не найдены")

        val chatTargets = req.toChatIds.distinct()
        val userTargets = req.toUserIds.distinct()
        if (chatTargets.isEmpty() && userTargets.isEmpty()) throw ApiException.badRequest("invalid_target", "нужен toChatIds или toUserIds")
        if (chatTargets.size + userTargets.size > MAX_TARGETS) throw ApiException.badRequest("too_many_targets", "не больше $MAX_TARGETS адресатов")
        if (msgs.size * (chatTargets.size + userTargets.size) > MAX_COPIES) {
            throw ApiException.badRequest("too_many_copies", "за раз — не больше $MAX_COPIES пересланных сообщений на всех адресатов")
        }
        chatTargets.forEach { if (!chats.isMember(it, me)) throw ApiException.forbidden("ты не состоишь в чате $it") }
        val targets = LinkedHashMap<UUID, UUID?>()
        chatTargets.forEach { targets[it] = null }
        userTargets.forEach { uid -> targets.putIfAbsent(chatAdmin.directChatId(me, uid), uid) }

        val comment = req.comment?.trim()?.takeIf { it.isNotEmpty() }
        if (comment != null && comment.length > MessageService.MAX_BODY_LEN) {
            throw ApiException.badRequest("invalid_comment", "подпись длиннее ${MessageService.MAX_BODY_LEN}")
        }

        // вложения и треки оригиналов — пачкой
        val msgIds = msgs.map { it.id }
        val fileRows = em.createNativeQuery(
            "select owner_id, media_id from media_attachment where owner_type = 'message' and owner_id in (?1) order by owner_id, position",
        ).setParameter(1, msgIds).resultList as List<Array<Any?>>
        val mediaById = if (fileRows.isEmpty()) emptyMap() else Media.list("id in ?1", fileRows.map { it[1] as UUID }).associateBy { it.id }
        val files = fileRows.groupBy({ it[0] as UUID }) { mediaById[it[1] as UUID] }.mapValues { it.value.filterNotNull() }
        val trackRows = em.createNativeQuery(
            "select owner_id, track_id from track_attachment where owner_type = 'message' and owner_id in (?1) order by owner_id, position",
        ).setParameter(1, msgIds).resultList as List<Array<Any?>>
        val tracks = trackRows.groupBy({ it[0] as UUID }) { it[1] as UUID }

        val sent = targets.map { (chatId, userId) ->
            val created = mutableListOf<UUID>()
            var lastSeq = 0L
            comment?.let {
                val ack = messages.send(chatId, me, it, null, UUID.randomUUID(), "forward")
                created += ack.id; lastSeq = ack.seq
            }
            msgs.forEach { m ->
                val ack = messages.send(
                    chatId, me, m.body, null, UUID.randomUUID(), "forward",
                    sharedPostId = m.sharedPostId,
                    forwardedMedia = files[m.id] ?: emptyList(),
                    extras = MessageExtras(
                        gifId = m.gifId,
                        stickerId = m.stickerId,
                        forward = ForwardMeta(
                            userId = m.fwdUserId ?: m.userId,
                            messageId = m.fwdMessageId ?: m.id,
                            chatId = m.fwdChatId ?: m.chatId,
                            at = m.fwdAt ?: m.createdAt,
                        ),
                        forwardTrackIds = tracks[m.id] ?: emptyList(),
                    ),
                )
                created += ack.id; lastSeq = ack.seq
            }
            ForwardSentOut(chatId, userId, created, lastSeq)
        }
        return ForwardResultOut(sent)
    }

    // ================================================================ реакции

    @Transactional
    fun react(me: UUID, chatId: UUID, messageId: UUID, rawEmoji: String, add: Boolean): MessageReactionsOut {
        requireMember(chatId, me)
        val m = Message.findById(messageId)
        if (m == null || m.chatId != chatId || m.deletedAt != null) throw ApiException.notFound("сообщение не найдено")
        val emoji = rawEmoji.trim()
        if (emoji.isEmpty() || emoji.length > 32 || emoji.any { it.isWhitespace() } || PLAIN_WORD.matches(emoji)) {
            throw ApiException.badRequest("invalid_emoji", "реакция — эмодзи")
        }
        if (add) {
            val mine = count("select count(*) from message_reaction where message_id = ?1 and user_id = ?2 and emoji <> ?3", messageId, me, emoji)
            if (mine >= MAX_MY_REACTIONS) throw ApiException.badRequest("too_many_reactions", "не больше $MAX_MY_REACTIONS реакций на сообщение")
            val distinct = count("select count(distinct emoji) from message_reaction where message_id = ?1 and emoji <> ?2", messageId, emoji)
            if (distinct >= MAX_EMOJIS) throw ApiException.badRequest("too_many_emojis", "на сообщении уже $MAX_EMOJIS разных реакций")
            em.createNativeQuery("insert into message_reaction (message_id, user_id, emoji) values (?1, ?2, ?3) on conflict do nothing")
                .setParameter(1, messageId).setParameter(2, me).setParameter(3, emoji).executeUpdate()
        } else {
            em.createNativeQuery("delete from message_reaction where message_id = ?1 and user_id = ?2 and emoji = ?3")
                .setParameter(1, messageId).setParameter(2, me).setParameter(3, emoji).executeUpdate()
        }
        val out = MessageReactionsOut(chatId, messageId, resolver.reactionsOf(listOf(messageId))[messageId] ?: emptyList())
        bus.publishToChat(chatId, Envelope(t = FrameTypes.MESSAGE_REACTIONS, d = mapper.valueToTree(out))) // после COMMIT
        return out
    }

    // ================================================================ беседа: название и аватар

    @Transactional
    fun rename(me: UUID, chatId: UUID, req: ChatPatchIn) {
        val chat = groupChat(chatId, me)
        req.name?.let {
            val n = it.trim()
            if (n.isEmpty() || n.length > MAX_NAME) throw ApiException.badRequest("invalid_name", "название: 1–$MAX_NAME символов")
            chat.name = n
        }
        pushUpdated(chatId, me)
    }

    @Transactional
    fun setAvatar(me: UUID, chatId: UUID, mediaId: UUID) {
        val chat = groupChat(chatId, me)
        val m = media.requireOwned(mediaId, me)
        if (!m.contentType.startsWith("image/")) throw ApiException.badRequest("not_image", "аватар — картинка: png, jpeg, gif или webp")
        chat.avatar = media.url(m.id)
        pushUpdated(chatId, me)
    }

    @Transactional
    fun clearAvatar(me: UUID, chatId: UUID) {
        groupChat(chatId, me).avatar = null
        pushUpdated(chatId, me)
    }

    // ================================================================ внутреннее

    /** Название и аватар меняются у групп (у лички — собеседник, у обсуждений — admin сообщества). */
    private fun groupChat(chatId: UUID, me: UUID): Chat {
        requireMember(chatId, me)
        val chat = Chat.findById(chatId) ?: throw ApiException.notFound("чат не найден")
        if (chat.roomType != ChatManagementService.ROOM_GROUP) {
            throw ApiException.badRequest("not_group", "название и аватар меняются только у групповых бесед")
        }
        return chat
    }

    private fun requireMember(chatId: UUID, me: UUID) {
        if (!chats.isMember(chatId, me)) throw ApiException.forbidden("ты не состоишь в этом чате")
    }

    private fun pushUpdated(chatId: UUID, me: UUID) {
        val members = ChatMember.list("id.chatId = ?1 and isDeleted = false", chatId).map { it.id.userId }
        bus.publishToUsers(members, Envelope(t = FrameTypes.CHAT_UPDATED, d = mapper.valueToTree(ChatUpdatedOut(chatId, "info", me))))
    }

    private fun count(sql: String, vararg params: Any): Long {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        return (q.singleResult as Number).toLong()
    }
}
