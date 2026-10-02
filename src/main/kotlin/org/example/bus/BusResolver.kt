package org.example.bus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.CallSignalEntity
import org.example.domain.ChatMember
import org.example.domain.Community
import org.example.domain.Message
import org.example.domain.Post
import org.example.proto.CallSignalOut
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.proto.MessageOut
import org.example.proto.SharedPostOut
import org.example.rest.AttachmentOut
import org.example.rest.ForwardOriginOut
import org.example.rest.PostCommunityOut
import org.example.rest.ReactionOut
import org.example.rest.ReplyPreviewOut
import org.example.rest.TrackOut
import org.example.rest.UserShortOut
import org.example.service.AttachmentService
import org.example.service.GifService
import org.example.service.StickerService
import org.example.service.UserProfileService
import java.util.UUID

/**
 * Нода-получатель превращает указатель из NOTIFY в готовый кадр,
 * дочитав строку из БД. Отдельный бин — чтобы @Transactional работал
 * через CDI-прокси при вызове из EventBus.
 */
@ApplicationScoped
class BusResolver(
    private val mapper: ObjectMapper,
    private val attachments: AttachmentService,
    private val profiles: UserProfileService,
    private val em: EntityManager,
    private val gifs: GifService,
    private val stickers: StickerService,
) {

    @Transactional
    fun resolve(p: Pointer): JsonNode? = when (p.kind) {
        PointerKinds.MESSAGE_NEW, PointerKinds.MESSAGE_UPDATED -> {
            val m = Message.findById(UUID.fromString(p.id))
            m?.let {
                val t = if (p.kind == PointerKinds.MESSAGE_NEW) FrameTypes.MESSAGE_NEW else FrameTypes.MESSAGE_UPDATED
                frame(t, render(listOf(it)).first())
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

    /** Сообщения с вложениями — одним запросом на пачку (история, список чатов, сокет). */
    @Transactional
    fun render(messages: List<Message>): List<MessageOut> {
        val alive = messages.filter { it.deletedAt == null }.map { it.id }
        val files = attachments.load(AttachmentService.Owner.MESSAGE, alive)
        val tracks = attachments.loadTracks(AttachmentService.Owner.MESSAGE, alive, null)
        val live = messages.filter { it.deletedAt == null }
        val shared = sharedPosts(live.mapNotNull { it.sharedPostId })
        val gifOut = gifs.byIds(live.mapNotNull { it.gifId })
        val stickerOut = stickers.byIds(live.mapNotNull { it.stickerId })
        val replies = replyPreviews(live.mapNotNull { it.replyToId })
        val reactions = reactionsOf(alive)
        val mentions = mentionsOf(alive)
        val fwdUsers = profiles.shorts(live.mapNotNull { it.fwdUserId })
        return messages.map {
            val out = toOut(it, files[it.id] ?: emptyList(), tracks[it.id] ?: emptyList())
            if (it.deletedAt != null) out else out.copy(
                sharedPost = it.sharedPostId?.let { id -> shared[id] },
                replyTo = it.replyToId?.let { id -> replies[id] },
                forwardedFrom = if (it.fwdMessageId != null || it.fwdUserId != null) {
                    ForwardOriginOut(it.fwdUserId?.let { u -> fwdUsers[u] }, it.fwdMessageId, it.fwdChatId, it.fwdAt)
                } else null,
                gif = it.gifId?.let { id -> gifOut[id] },
                sticker = it.stickerId?.let { id -> stickerOut[id] },
                reactions = reactions[it.id] ?: emptyList(),
                mentions = mentions[it.id] ?: emptyList(),
            )
        }
    }

    /** Реакции пачкой: emoji → сколько и кто (до 50). Порядок — по первому появлению. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun reactionsOf(messageIds: Collection<UUID>): Map<UUID, List<ReactionOut>> {
        if (messageIds.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery(
            "select message_id, emoji, user_id from message_reaction where message_id in (?1) order by created_at",
        ).setParameter(1, messageIds.distinct()).resultList as List<Array<Any?>>
        return rows.groupBy { it[0] as UUID }.mapValues { (_, list) ->
            list.groupBy { it[1] as String }.map { (emoji, us) ->
                ReactionOut(emoji, us.size.toLong(), us.map { it[2] as UUID }.take(50))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mentionsOf(messageIds: List<UUID>): Map<UUID, List<UserShortOut>> {
        if (messageIds.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery("select owner_id, user_id from mention where owner_type = 'message' and owner_id in (?1)")
            .setParameter(1, messageIds).resultList as List<Array<Any?>>
        if (rows.isEmpty()) return emptyMap()
        val users = profiles.shorts(rows.map { it[1] as UUID })
        return rows.groupBy({ it[0] as UUID }) { it[1] as UUID }.mapValues { (_, us) -> us.mapNotNull { users[it] } }
    }

    /** Превью сообщений, на которые отвечают. */
    private fun replyPreviews(ids: List<UUID>): Map<UUID, ReplyPreviewOut> {
        if (ids.isEmpty()) return emptyMap()
        val msgs = Message.list("id in ?1", ids.distinct())
        if (msgs.isEmpty()) return emptyMap()
        val users = profiles.shorts(msgs.map { it.userId })
        val files = attachments.load(AttachmentService.Owner.MESSAGE, msgs.filter { it.deletedAt == null }.map { it.id })
        val withTracks = attachments.loadTracks(AttachmentService.Owner.MESSAGE, msgs.map { it.id }, null)
        return msgs.associate { m ->
            val deleted = m.deletedAt != null
            val kind = when {
                deleted -> null
                m.stickerId != null -> "sticker"
                m.gifId != null -> "gif"
                files[m.id]?.any { it.kind == "video" } == true -> "video"
                !files[m.id].isNullOrEmpty() -> "photo"
                !withTracks[m.id].isNullOrEmpty() -> "track"
                m.sharedPostId != null -> "post"
                else -> null
            }
            m.id to ReplyPreviewOut(
                m.id, m.seq, users[m.userId],
                if (deleted) null else m.body?.replace('\n', ' ')?.take(120)?.ifEmpty { null },
                kind, deleted,
            )
        }
    }

    /** Превью пересланных записей пачкой. Удалённая запись — {id, deleted: true}. */
    private fun sharedPosts(ids: List<UUID>): Map<UUID, SharedPostOut> {
        if (ids.isEmpty()) return emptyMap()
        val unique = ids.distinct()
        val posts = Post.list("id in ?1", unique).associateBy { it.id }
        val commIds = posts.values.mapNotNull { it.communityId }.distinct()
        val comms = if (commIds.isEmpty()) emptyMap() else Community.list("id in ?1", commIds).associateBy { it.id }
        val users = profiles.shorts(posts.values.flatMap { listOfNotNull(it.authorId, it.wallUserId) })
        val files = attachments.load(AttachmentService.Owner.POST, posts.keys)
        return unique.associateWith { id ->
            val p = posts[id]
            val c = p?.communityId?.let { comms[it] }
            if (p == null || p.isDeleted || (p.communityId != null && (c == null || c.isDeleted))) {
                SharedPostOut(id, deleted = true)
            } else {
                val att = files[id] ?: emptyList()
                SharedPostOut(
                    id = id,
                    source = when {
                        p.wallUserId != null -> "wall"
                        p.isPulse -> "pulse"
                        else -> "community"
                    },
                    kind = p.kind,
                    title = p.title,
                    text = p.body.take(200).ifEmpty { null },
                    author = users[p.authorId],
                    community = c?.let { PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) },
                    asCommunity = p.asCommunity && c != null,
                    wallOwner = p.wallUserId?.let { users[it] },
                    cover = att.firstOrNull(),
                    attachmentsCount = att.size,
                    createdAt = p.createdAt,
                )
            }
        }
    }

    private fun frame(t: String, payload: Any): JsonNode =
        mapper.valueToTree(Envelope(t = t, d = mapper.valueToTree(payload)))

    companion object {
        fun toOut(m: Message, files: List<AttachmentOut> = emptyList(), tracks: List<TrackOut> = emptyList()) = MessageOut(
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
            sharedPostId = if (m.deletedAt != null) null else m.sharedPostId,
            attachments = if (m.deletedAt != null) emptyList() else files,
            tracks = if (m.deletedAt != null) emptyList() else tracks,
        )
    }
}
