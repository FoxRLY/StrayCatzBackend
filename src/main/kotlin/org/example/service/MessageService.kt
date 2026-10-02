package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.bus.PointerKinds
import org.example.domain.Chat
import org.example.domain.ChatMember
import org.example.domain.ChatMemberId
import org.example.domain.Media
import org.example.domain.Message
import org.example.proto.ChatReadOut
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.proto.MessageAckOut
import java.time.Instant
import java.util.UUID

class MessageValidationException(message: String) : RuntimeException(message)

/** Пересланное: откуда оригинал. */
data class ForwardMeta(val userId: UUID?, val messageId: UUID?, val chatId: UUID?, val at: java.time.Instant?)

/** Необязательное в сообщении сверх текста и вложений. */
data class MessageExtras(
    /** Ответ на сообщение этого же чата. */
    val replyToId: UUID? = null,
    /** Гифка/внешний стикер из GET /api/gifs/… (наш id). */
    val gifId: UUID? = null,
    /** Стикер из набора. */
    val stickerId: UUID? = null,
    /** Пересылка: без проверок «моё ли» — гифка/стикер/треки копируются как были. */
    val forward: ForwardMeta? = null,
    val forwardTrackIds: List<UUID> = emptyList(),
)
class ForbiddenException(message: String) : RuntimeException(message)

@ApplicationScoped
class MessageService(
    private val bus: EventBus,
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val attachments: AttachmentService,
    private val gifs: GifService,
    private val stickers: StickerService,
    private val mentions: MentionService,
) {
    companion object { const val MAX_BODY_LEN = 4000 }

    /**
     * Идемпотентно по (chat_id, user_id, client_token): если клиент
     * переотправил кадр после обрыва — вернём ack на уже созданное сообщение,
     * а не создадим дубль.
     */
    @Transactional
    fun send(
        chatId: UUID,
        userId: UUID,
        body: String?,
        mediaId: UUID?,
        clientToken: UUID,
        rid: String,
        sharedPostId: UUID? = null,
        mediaIds: List<UUID> = emptyList(),
        trackIds: List<UUID> = emptyList(),
        /** Уже проверенные чужие файлы (пересылка видео из «видео»): владение не проверяем. */
        forwardedMedia: List<Media> = emptyList(),
        extras: MessageExtras = MessageExtras(),
    ): MessageAckOut {
        // повтор кадра после обрыва — сразу ack, ничего не проверяя заново
        Message.find("chatId = ?1 and userId = ?2 and clientToken = ?3", chatId, userId, clientToken)
            .firstResult()
            ?.let { return MessageAckOut(rid, clientToken, it.id, it.seq, it.createdAt) }

        // до 4 картинок/гифок или одно видео; mediaId — старое одиночное поле
        val files = attachments.validate(userId, mediaIds, mediaId, allowVideo = true)
            .let { own -> if (forwardedMedia.isEmpty()) own else (own + forwardedMedia).distinctBy { it.id } }
        val tracks = attachments.validateTracks(userId, trackIds) + extras.forwardTrackIds
        val fwd = extras.forward
        // гифка и стикер: при пересылке берём как есть, иначе — проверка и «недавние» + кэш файла
        val gifId = extras.gifId?.let { if (fwd != null) it else gifs.use(userId, it) }
        val stickerId = extras.stickerId?.let { if (fwd != null) it else stickers.use(userId, it) }
        if (gifId != null && stickerId != null) throw MessageValidationException("в одном сообщении — гифка или стикер, не оба")
        val replyTo = extras.replyToId?.let { rid2 ->
            Message.findById(rid2)?.takeIf { it.chatId == chatId }?.id
                ?: throw MessageValidationException("сообщение, на которое отвечаешь, не из этого чата")
        }
        // сообщение может быть без текста: только картинка, трек, гифка, стикер или репост
        validateBody(
            body,
            allowBlank = files.isNotEmpty() || tracks.isNotEmpty() || sharedPostId != null || gifId != null || stickerId != null,
        )

        val message = Message().also {
            it.id = UUID.randomUUID()
            it.chatId = chatId
            it.seq = allocateSeq(chatId)
            it.userId = userId
            it.clientToken = clientToken
            it.body = body
            it.mediaId = files.firstOrNull()?.id // старое поле — для совместимости
            it.sharedPostId = sharedPostId
            it.replyToId = replyTo
            it.gifId = gifId
            it.stickerId = stickerId
            it.fwdUserId = fwd?.userId
            it.fwdMessageId = fwd?.messageId
            it.fwdChatId = fwd?.chatId
            it.fwdAt = fwd?.at
        }
        message.persist()
        attachments.attach(AttachmentService.Owner.MESSAGE, message.id, files)
        attachments.attachTracks(AttachmentService.Owner.MESSAGE, message.id, tracks)

        // @ник — только участникам этого чата; пересланное чужое никого не упоминает заново
        if (fwd == null) syncMentions(message)

        // своё сообщение считаем прочитанным — иначе оно висит в unread у автора
        ChatMember.findById(ChatMemberId(chatId, userId))?.let { if (it.lastReadSeq < message.seq) it.lastReadSeq = message.seq }

        // NOTIFY уйдёт только после COMMIT этой транзакции — см. EventBus
        bus.publishPointerToChat(chatId, PointerKinds.MESSAGE_NEW, message.id.toString())
        return MessageAckOut(rid, clientToken, message.id, message.seq, message.createdAt)
    }

    @Transactional
    fun edit(chatId: UUID, messageId: UUID, userId: UUID, body: String) {
        validateBody(body, allowBlank = false)
        val m = ownMessage(chatId, messageId, userId)
        m.body = body
        m.editedAt = Instant.now()
        if (m.fwdMessageId == null) syncMentions(m)
        bus.publishPointerToChat(chatId, PointerKinds.MESSAGE_UPDATED, m.id.toString())
    }

    @Transactional
    fun delete(chatId: UUID, messageId: UUID, userId: UUID) {
        val m = ownMessage(chatId, messageId, userId)
        m.deletedAt = Instant.now()
        bus.publishPointerToChat(chatId, PointerKinds.MESSAGE_UPDATED, m.id.toString())
    }

    @Transactional
    fun markRead(chatId: UUID, userId: UUID, seq: Long) {
        val member = ChatMember.findById(ChatMemberId(chatId, userId)) ?: return
        if (member.isDeleted || seq <= member.lastReadSeq) return
        member.lastReadSeq = seq

        val frame = Envelope(t = FrameTypes.CHAT_READ, d = mapper.valueToTree(ChatReadOut(chatId, userId, seq)))
        bus.publishToChat(chatId, frame)
    }

    /**
     * Атомарная выдача следующего seq одним запросом: upsert берёт
     * row-lock на строку chat_seq до конца транзакции, так что конкурентные
     * send в один чат выстраиваются в очередь, а дырок/дублей не бывает.
     * (Прошлая версия ловила исключение от persist() и продолжала в той же
     * транзакции — в Postgres после ошибки транзакция уже aborted.)
     */
    private fun allocateSeq(chatId: UUID): Long =
        (em.createNativeQuery(
            "insert into chat_seq (chat_id, next_seq) values (?1, 2) " +
                    "on conflict (chat_id) do update set next_seq = chat_seq.next_seq + 1 " +
                    "returning next_seq - 1",
        ).setParameter(1, chatId).singleResult as Number).toLong()

    private fun syncMentions(m: Message) {
        val body = m.body
        if (body.isNullOrBlank() || !body.contains('@')) {
            if (m.editedAt != null) mentions.sync(MentionService.Owner.MESSAGE, m.id, m.userId, null, emptySet(), emptyMap())
            return
        }
        val chat = Chat.findById(m.chatId)
        val members = ChatMember.list("id.chatId = ?1 and isDeleted = false", m.chatId).map { it.id.userId }.toSet()
        mentions.sync(
            MentionService.Owner.MESSAGE, m.id, m.userId, body, members,
            mapOf("chatId" to m.chatId.toString(), "messageId" to m.id.toString(), "seq" to m.seq, "chatName" to chat?.name),
        )
    }

    private fun ownMessage(chatId: UUID, messageId: UUID, userId: UUID): Message {
        val m = Message.findById(messageId)
        if (m == null || m.chatId != chatId || m.deletedAt != null) {
            throw MessageValidationException("сообщение не найдено")
        }
        if (m.userId != userId) throw ForbiddenException("можно менять только свои сообщения")
        return m
    }

    private fun validateBody(body: String?, allowBlank: Boolean) {
        if (body.isNullOrBlank() && !allowBlank) {
            throw MessageValidationException("пустое сообщение: нужен body или mediaId")
        }
        if ((body?.length ?: 0) > MAX_BODY_LEN) {
            throw MessageValidationException("тело сообщения длиннее $MAX_BODY_LEN символов")
        }
    }
}
