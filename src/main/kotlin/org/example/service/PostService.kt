package org.example.service

import io.quarkus.panache.common.Sort
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.AppUser
import org.example.domain.Community
import org.example.domain.Post
import org.example.domain.PostComment
import org.example.rest.ApiException
import org.example.rest.PollOptionOut
import org.example.rest.PollOut
import org.example.rest.CommentIn
import org.example.rest.CommentOut
import org.example.rest.CommentsPageOut
import org.example.rest.LikeOut
import org.example.rest.MyPostOut
import org.example.rest.PostCommunityOut
import org.example.rest.PostIn
import org.example.rest.PostOut
import org.example.rest.PostPageOut
import org.example.rest.ReadOut
import org.example.rest.ShareIn
import org.example.rest.ShareOut
import org.example.rest.ShareSentOut
import org.example.rest.WallPostIn
import org.example.rest.UpvoteBudgetOut
import org.example.rest.UpvoteOut
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.exp
import kotlin.math.pow

/**
 * Записи и всё, что с ними делают. Универсально: одна и та же запись
 * показывается в ленте, в «свежем из сообществ» и на странице сообщества —
 * апвоут/лайк/комментарий/репост/прочтение работают одинаково откуда угодно.
 *
 *  - апвоут: 5 в сутки (UTC) на человека, не отзывается, свою запись нельзя;
 *    доступен и тем, кто «читает без вступления»;
 *  - лайк: «мне понравилось», попадает в /api/posts/liked, снимается; только участники;
 *  - комментарии с ответами: только участники;
 *  - репост: кто угодно — в чаты, где он состоит, или людям в личку;
 *  - стена: записи человека у себя (без сообщества), комментировать может любой;
 *  - прочтение живёт 5 минут: readers = прочтения за последние 5 минут.
 */
@ApplicationScoped
class PostService(
    private val em: EntityManager,
    private val communities: CommunityService,
    private val profiles: UserProfileService,
    private val media: MediaService,
    private val chats: ChatService,
    private val messages: MessageService,
    private val notifications: NotificationService,
    private val attachments: AttachmentService,
    private val chatAdmin: ChatManagementService,
    private val tags: TagService,
    private val mentionsSvc: MentionService,
) {
    companion object {
        const val UPVOTES_PER_DAY = 5
        const val MAX_SHARE_TARGETS = 20
        const val MAX_TITLE = 200
        const val MAX_BODY = 10_000
        const val MAX_GUIDE_BODY = 100_000
        const val MAX_META = 40
        const val MAX_COMMENT = 2000
        const val MAX_PAGE = 100
        const val HOT_PER_MINUTE = 5
        val READ_TTL: Duration = Duration.ofMinutes(5)
        val KINDS = setOf("text", "image", "video", "track", "guide")
    }

    // ================================================================ чтение

    /**
     * Записи сообщества. kind: null — все, кроме гайдов; "guide" — гайды;
     * "media" — картинки и видео (раздел «Медиа», пока из записей).
     */
    @Transactional
    fun ofCommunity(slug: String, me: UUID, kind: String?, before: Instant?, limit: Int, tag: String? = null): PostPageOut {
        val c = communities.bySlug(slug)
        val k = kind?.trim()?.lowercase().orEmpty()
        val kinds = when {
            k.isEmpty() -> (KINDS - "guide") + "poll" // + опросы из пульса («пульсары»)
            k == "media" -> setOf("image", "video")
            k in KINDS -> setOf(k)
            else -> throw ApiException.badRequest("invalid_kind", "kind: $KINDS или media")
        }
        val size = limit.coerceIn(1, MAX_PAGE)
        val tagged = tag?.takeIf { it.isNotBlank() }?.let { t ->
            val norm = TagService.normalize(t) ?: return PostPageOut(emptyList(), false)
            tags.ownersWith(TagService.Owner.POST, norm, 2000).ifEmpty { return PostPageOut(emptyList(), false) }
        }
        val rows = if (tagged == null) Post.find(
            "communityId = ?1 and isDeleted = false and kind in ?2 and createdAt < ?3",
            Sort.descending("createdAt"),
            c.id, kinds.toList(), before ?: far(),
        ).range(0, size).list() else Post.find(
            "communityId = ?1 and isDeleted = false and kind in ?2 and createdAt < ?3 and id in ?4",
            Sort.descending("createdAt"),
            c.id, kinds.toList(), before ?: far(), tagged,
        ).range(0, size).list()
        return PostPageOut(toOut(rows.take(size), me), rows.size > size)
    }

    /** Лента новенького: всё из сообществ, где я участник или «читаю без вступления». */
    @Transactional
    fun feed(me: UUID, before: Instant?, limit: Int): PostPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        @Suppress("UNCHECKED_CAST")
        val ids = em.createNativeQuery(
            """
            select p.id from post p
            join community c on c.id = p.community_id and not c.is_deleted
            where not p.is_deleted and p.created_at < ?2
              and (exists (select 1 from community_member m where m.community_id = p.community_id and m.user_id = ?1 and m.left_at is null)
                or exists (select 1 from community_follow f where f.community_id = p.community_id and f.user_id = ?1))
            order by p.created_at desc
            limit ?3
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, me).setParameter(2, before ?: far()).setParameter(3, size + 1).resultList as List<UUID>
        val byId = if (ids.isEmpty()) emptyMap() else Post.list("id in ?1", ids).associateBy { it.id }
        val rows = ids.mapNotNull { byId[it] }
        return PostPageOut(toOut(rows.take(size), me), rows.size > size)
    }

    @Transactional
    fun get(id: UUID, me: UUID): PostOut = toOut(listOf(activePost(id)), me).first()

    /** Что я лайкнул — «сохранённое». */
    @Transactional
    fun liked(me: UUID, before: Instant?, limit: Int): PostPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        @Suppress("UNCHECKED_CAST")
        val ids = em.createNativeQuery(
            """
            select l.post_id from post_like l join post p on p.id = l.post_id and not p.is_deleted
            where l.user_id = ?1 and l.created_at < ?2 order by l.created_at desc limit ?3
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, me).setParameter(2, before ?: far()).setParameter(3, size + 1).resultList as List<UUID>
        val byId = if (ids.isEmpty()) emptyMap() else Post.list("id in ?1", ids).associateBy { it.id }
        val rows = ids.mapNotNull { byId[it] }
        return PostPageOut(toOut(rows.take(size), me), rows.size > size)
    }

    // ================================================================ стена

    /** Стена человека: закреплённые сверху, дальше новые. */
    @Transactional
    fun wall(username: String, me: UUID, before: Instant?, limit: Int): PostPageOut {
        val owner = userByName(username)
        val size = limit.coerceIn(1, MAX_PAGE)
        val pinned = if (before == null) {
            Post.find("wallUserId = ?1 and isDeleted = false and pinned = true", Sort.descending("createdAt"), owner.id).list()
        } else emptyList()
        val rows = Post.find(
            "wallUserId = ?1 and isDeleted = false and pinned = false and createdAt < ?2",
            Sort.descending("createdAt"),
            owner.id, before ?: far(),
        ).range(0, size).list()
        return PostPageOut(toOut(pinned + rows.take(size), me), rows.size > size)
    }

    /** Запись на своей стене. kind выводится из вложений. */
    @Transactional
    fun createWall(me: UUID, req: WallPostIn): PostOut {
        val files = attachments.validate(me, req.mediaIds, allowVideo = true)
        val trackIds = attachments.validateTracks(me, req.trackIds)
        val body = req.body?.trim().orEmpty()
        if (body.isEmpty() && files.isEmpty() && trackIds.isEmpty()) {
            throw ApiException.badRequest("invalid_body", "нужен текст, картинка, видео или трек")
        }
        if (body.length > MAX_BODY) throw ApiException.badRequest("invalid_body", "текст длиннее $MAX_BODY символов")
        val p = Post().also {
            it.id = UUID.randomUUID()
            it.authorId = me
            it.wallUserId = me
            it.title = title(req.title)
            it.body = body
            it.kind = when {
                files.any { f -> f.contentType.startsWith("video/") } -> "video"
                files.isNotEmpty() -> "image"
                trackIds.isNotEmpty() -> "track"
                else -> "text"
            }
            it.mediaId = files.firstOrNull()?.id
        }
        p.persist()
        attachments.attach(AttachmentService.Owner.POST, p.id, files)
        attachments.attachTracks(AttachmentService.Owner.POST, p.id, trackIds)
        tags.sync(TagService.Owner.POST, p.id, req.tags, p.title, p.body)
        mentionPost(p)
        return toOut(listOf(p), me).first()
    }

    // ================================================================ запись

    @Transactional
    fun create(me: UUID, slug: String, req: PostIn): PostOut {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "member")
        val files = attachments.validate(me, req.mediaIds, req.mediaId, allowVideo = true)
        val trackIds = attachments.validateTracks(me, req.trackIds)
        // kind не передан — выводим из вложений
        val kind = (req.kind ?: when {
            files.any { it.contentType.startsWith("video/") } -> "video"
            files.isNotEmpty() -> "image"
            trackIds.isNotEmpty() -> "track"
            else -> "text"
        }).trim().lowercase()
        if (kind !in KINDS) throw ApiException.badRequest("invalid_kind", "kind: $KINDS")
        val body = req.body?.trim().orEmpty()
        val maxBody = if (kind == "guide") MAX_GUIDE_BODY else MAX_BODY
        if (body.isEmpty() && files.isEmpty() && trackIds.isEmpty()) {
            throw ApiException.badRequest("invalid_body", "нужен текст, картинка или трек")
        }
        if (body.length > maxBody) throw ApiException.badRequest("invalid_body", "текст длиннее $maxBody символов")

        val p = Post().also {
            it.id = UUID.randomUUID()
            it.authorId = me
            it.communityId = c.id
            it.title = title(req.title)
            it.body = body
            it.kind = kind
            it.meta = meta(req.meta)
            it.mediaId = files.firstOrNull()?.id // старое поле — для совместимости
        }
        p.persist()
        attachments.attach(AttachmentService.Owner.POST, p.id, files)
        attachments.attachTracks(AttachmentService.Owner.POST, p.id, trackIds)
        tags.sync(TagService.Owner.POST, p.id, req.tags, p.title, p.body)
        mentionPost(p)
        return toOut(listOf(p), me).first()
    }

    /** Править может только автор. */
    @Transactional
    fun update(me: UUID, id: UUID, req: PostIn): PostOut {
        val p = activePost(id)
        if (p.authorId != me) throw ApiException.forbidden("править можно только свою запись")
        req.title?.let { p.title = title(it) }
        req.body?.let {
            val max = if (p.kind == "guide") MAX_GUIDE_BODY else MAX_BODY
            val b = it.trim()
            if (b.length > max) throw ApiException.badRequest("invalid_body", "текст длиннее $max символов")
            p.body = b
        }
        req.meta?.let { p.meta = meta(it) }
        p.updatedAt = Instant.now()
        if (req.title != null || req.body != null || req.tags != null) {
            tags.sync(TagService.Owner.POST, p.id, req.tags, p.title, p.body)
        }
        if (req.title != null || req.body != null) mentionPost(p)
        return toOut(listOf(p), me).first()
    }

    /** Удалить: автор или admin сообщества. */
    @Transactional
    fun delete(me: UUID, id: UUID) {
        val p = activePost(id)
        if (p.authorId != me && p.wallUserId != me) requireAdminOf(p, me)
        p.isDeleted = true
        p.deletedAt = Instant.now()
        tags.clear(TagService.Owner.POST, p.id)
    }

    @Transactional
    fun pin(me: UUID, id: UUID, pinned: Boolean): PostOut {
        val p = activePost(id)
        // на стене закрепляет хозяин стены, в сообществе — admin
        if (p.wallUserId == null || p.wallUserId != me) requireAdminOf(p, me)
        p.pinned = pinned
        return toOut(listOf(p), me).first()
    }

    // ================================================================ апвоут / лайк

    @Transactional
    fun upvote(me: UUID, id: UUID): UpvoteOut {
        val p = activePost(id)
        if (p.authorId == me) throw ApiException.badRequest("own_post", "свою запись апвоутить нельзя")
        if (exists("select 1 from post_upvote where post_id = ?1 and user_id = ?2", id, me)) {
            throw ApiException.conflict("already_upvoted", "уже апвоутнуто")
        }
        val used = usedToday(me)
        if (used >= UPVOTES_PER_DAY) {
            throw ApiException(429, "upvote_limit", "апвоуты на сегодня кончились: $UPVOTES_PER_DAY в сутки")
        }
        em.createNativeQuery("insert into post_upvote (post_id, user_id) values (?1, ?2)")
            .setParameter(1, id).setParameter(2, me).executeUpdate()
        return UpvoteOut(count("select count(*) from post_upvote where post_id = ?1", id), true, UPVOTES_PER_DAY - used - 1)
    }

    @Transactional
    fun upvoteBudget(me: UUID): UpvoteBudgetOut {
        val tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        return UpvoteBudgetOut((UPVOTES_PER_DAY - usedToday(me)).coerceAtLeast(0), UPVOTES_PER_DAY, tomorrow)
    }

    @Transactional
    fun like(me: UUID, id: UUID): LikeOut {
        val p = activePost(id)
        requireMemberOf(p, me)
        em.createNativeQuery("insert into post_like (post_id, user_id) values (?1, ?2) on conflict do nothing")
            .setParameter(1, id).setParameter(2, me).executeUpdate()
        return LikeOut(count("select count(*) from post_like where post_id = ?1", id), true)
    }

    @Transactional
    fun unlike(me: UUID, id: UUID): LikeOut {
        activePost(id)
        em.createNativeQuery("delete from post_like where post_id = ?1 and user_id = ?2")
            .setParameter(1, id).setParameter(2, me).executeUpdate()
        return LikeOut(count("select count(*) from post_like where post_id = ?1", id), false)
    }

    // ================================================================ прочтения / репосты

    /**
     * «Я читаю эту запись». Фронт зовёт при открытии/показе записи и,
     * пока она на экране, раз в 2–3 минуты. Прочтение живёт 5 минут.
     */
    @Transactional
    fun read(me: UUID, id: UUID): ReadOut {
        val p = activePost(id)
        em.createNativeQuery(
            """
            insert into post_read (post_id, user_id, community_id, read_at) values (?1, ?2, ?3, now())
            on conflict (post_id, user_id) do update set read_at = now()
            """.trimIndent(),
        ).setParameter(1, id).setParameter(2, me).setParameter(3, p.communityId).executeUpdate()
        return ReadOut(count("select count(*) from post_read where post_id = ?1 and read_at > now() - interval '5 minutes'", id))
    }

    /**
     * Переслать запись: в чаты, где я состою, и/или людям в личку
     * (личка найдётся или создастся). Каждому адресату — отдельное сообщение
     * с sharedPostId, счётчик shares растёт на каждого адресата.
     */
    @Transactional
    fun share(me: UUID, id: UUID, req: ShareIn): ShareOut {
        val p = activePost(id)
        val text = comment(req.comment)
        val targets = shareTargets(me, req)

        val sent = targets.map { (chatId, userId) ->
            val ack = messages.send(chatId, me, text, null, UUID.randomUUID(), "share", sharedPostId = p.id)
            em.createNativeQuery("insert into post_share (id, post_id, user_id, chat_id) values (?1, ?2, ?3, ?4)")
                .setParameter(1, UUID.randomUUID()).setParameter(2, id).setParameter(3, me).setParameter(4, chatId)
                .executeUpdate()
            ShareSentOut(chatId, ack.id, ack.seq, userId)
        }
        val first = sent.first()
        return ShareOut(first.chatId, first.messageId, first.seq, count("select count(*) from post_share where post_id = ?1", id), sent)
    }

    /**
     * Куда пересылать: chatId -> userId (для лички) или null. Проверяет, что я
     * состою в чатах, и находит/создаёт лички. Общее для записей и видео.
     */
    @Transactional
    fun shareTargets(me: UUID, req: ShareIn): LinkedHashMap<UUID, UUID?> {
        val chatTargets = (listOfNotNull(req.chatId) + req.chatIds).distinct()
        val userTargets = (listOfNotNull(req.userId) + req.userIds).distinct()
        if (chatTargets.isEmpty() && userTargets.isEmpty()) {
            throw ApiException.badRequest("invalid_target", "нужен chatId(s) или userId(s)")
        }
        if (chatTargets.size + userTargets.size > MAX_SHARE_TARGETS) {
            throw ApiException.badRequest("too_many_targets", "не больше $MAX_SHARE_TARGETS адресатов за раз")
        }
        chatTargets.forEach { if (!chats.isMember(it, me)) throw ApiException.forbidden("ты не состоишь в чате $it") }

        // chatId -> кому (для лички), без дублей: человек и его личка в одном запросе — одно сообщение
        val targets = LinkedHashMap<UUID, UUID?>()
        chatTargets.forEach { targets[it] = null }
        userTargets.forEach { uid -> targets.putIfAbsent(chatAdmin.directChatId(me, uid), uid) }
        return targets
    }

    /** Подпись к пересылке: обрезанная, до MessageService.MAX_BODY_LEN. */
    fun comment(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length > MessageService.MAX_BODY_LEN) {
            throw ApiException.badRequest("invalid_comment", "комментарий длиннее ${MessageService.MAX_BODY_LEN}")
        }
        return text
    }

    // ================================================================ комментарии

    /** Все комментарии записи по времени (плоско, с parentId — дерево собирает фронт). */
    @Transactional
    fun comments(postId: UUID, me: UUID, after: Instant?, limit: Int): CommentsPageOut {
        val p = activePost(postId)
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = PostComment.find(
            "postId = ?1 and createdAt > ?2", Sort.ascending("createdAt"), postId, after ?: Instant.EPOCH,
        ).range(0, size).list()
        val page = rows.take(size)
        val authors = profiles.shorts(page.map { it.authorId })
        val alive = page.filter { it.deletedAt == null }.map { it.id }
        val files = attachments.load(AttachmentService.Owner.COMMENT, alive)
        val commentTracks = attachments.loadTracks(AttachmentService.Owner.COMMENT, alive, me)
        val isAdmin = p.communityId?.let { adminRank(it, me) } ?: false
        return CommentsPageOut(
            items = page.map { c ->
                val deleted = c.deletedAt != null
                CommentOut(
                    c.id, c.postId, c.parentId,
                    if (deleted) null else authors[c.authorId],
                    if (deleted) null else c.body,
                    deleted, c.createdAt,
                    canDelete = !deleted && (c.authorId == me || isAdmin),
                    attachments = if (deleted) emptyList() else files[c.id] ?: emptyList(),
                    tracks = if (deleted) emptyList() else commentTracks[c.id] ?: emptyList(),
                )
            },
            total = PostComment.count("postId = ?1 and deletedAt is null", postId),
            hasMore = rows.size > size,
        )
    }

    /** Для ленты («ответы мне»): комментарии пачкой по id. */
    @Transactional
    fun commentsByIds(ids: Collection<UUID>, me: UUID): Map<UUID, CommentOut> {
        if (ids.isEmpty()) return emptyMap()
        val rows = PostComment.list("id in ?1", ids.distinct())
        val authors = profiles.shorts(rows.map { it.authorId })
        val alive = rows.filter { it.deletedAt == null }.map { it.id }
        val files = attachments.load(AttachmentService.Owner.COMMENT, alive)
        val commentTracks = attachments.loadTracks(AttachmentService.Owner.COMMENT, alive, me)
        val postsById = Post.list("id in ?1", rows.map { it.postId }.distinct()).associateBy { it.id }
        return rows.associate { c ->
            val deleted = c.deletedAt != null
            val isAdmin = postsById[c.postId]?.communityId?.let { adminRank(it, me) } ?: false
            c.id to CommentOut(
                c.id, c.postId, c.parentId,
                if (deleted) null else authors[c.authorId],
                if (deleted) null else c.body,
                deleted, c.createdAt,
                canDelete = !deleted && (c.authorId == me || isAdmin),
                attachments = if (deleted) emptyList() else files[c.id] ?: emptyList(),
                tracks = if (deleted) emptyList() else commentTracks[c.id] ?: emptyList(),
            )
        }
    }

    @Transactional
    fun comment(me: UUID, postId: UUID, req: CommentIn): CommentOut {
        val p = activePost(postId)
        requireMemberOf(p, me)
        val files = attachments.validate(me, req.mediaIds, allowVideo = false)
        val trackIds = attachments.validateTracks(me, req.trackIds)
        val body = req.body?.trim().orEmpty()
        if (body.length > MAX_COMMENT) throw ApiException.badRequest("invalid_body", "комментарий длиннее $MAX_COMMENT символов")
        if (body.isEmpty() && files.isEmpty() && trackIds.isEmpty()) {
            throw ApiException.badRequest("invalid_body", "нужен текст, картинка или трек")
        }
        val parent = req.parentId?.let { pid ->
            PostComment.findById(pid)?.takeIf { it.postId == postId }
                ?: throw ApiException.notFound("комментарий, на который отвечаешь, не найден")
        }
        val c = PostComment().also {
            it.id = UUID.randomUUID()
            it.postId = postId
            it.authorId = me
            it.parentId = parent?.id
            it.body = body
        }
        c.persist()
        attachments.attach(AttachmentService.Owner.COMMENT, c.id, files)
        attachments.attachTracks(AttachmentService.Owner.COMMENT, c.id, trackIds)
        // @ник в комментарии: уведомление «mention» упомянутым
        mentionsSvc.sync(
            MentionService.Owner.COMMENT, c.id, me, body, null,
            mapOf("postId" to postId.toString(), "commentId" to c.id.toString(), "communitySlug" to p.communityId?.let { Community.findById(it)?.slug }),
        )

        val preview = mapOf("postId" to postId.toString(), "commentId" to c.id.toString(), "preview" to body.take(100))
        if (parent != null && parent.authorId != me) {
            notifications.notify(parent.authorId, NotificationService.COMMENT_REPLY, me, preview)
        } else if (parent == null && p.authorId != me) {
            notifications.notify(p.authorId, NotificationService.POST_COMMENT, me, preview)
        }
        val author = profiles.shorts(listOf(me))[me]
        return CommentOut(
            c.id, postId, c.parentId, author, c.body, false, c.createdAt,
            canDelete = true, attachments = attachments.render(files),
            tracks = attachments.renderTracks(trackIds, me),
        )
    }

    /** Удалить: автор или admin. Ответы под удалённым остаются (у него body = null). */
    @Transactional
    fun deleteComment(me: UUID, commentId: UUID) {
        val c = PostComment.findById(commentId)
        if (c == null || c.deletedAt != null) throw ApiException.notFound("комментарий не найден")
        if (c.authorId != me) requireAdminOf(activePost(c.postId), me)
        c.deletedAt = Instant.now()
    }

    // ================================================================ сборка ответа

    /** Публичная сборка ответа — ею же пользуется PulseService. */
    fun render(posts: List<Post>, me: UUID): List<PostOut> = toOut(posts, me)

    private fun toOut(posts: List<Post>, me: UUID): List<PostOut> {
        if (posts.isEmpty()) return emptyList()
        val ids = posts.map { it.id }
        val up = counts("select post_id, count(*) from post_upvote where post_id in (?1) group by post_id", ids)
        val likes = counts("select post_id, count(*) from post_like where post_id in (?1) group by post_id", ids)
        val shares = counts("select post_id, count(*) from post_share where post_id in (?1) group by post_id", ids)
        val comments = counts(
            "select post_id, count(*) from post_comment where post_id in (?1) and deleted_at is null group by post_id", ids,
        )
        val lastMinute = counts(
            "select post_id, count(*) from post_comment where post_id in (?1) and deleted_at is null " +
                    "and created_at > now() - interval '1 minute' group by post_id",
            ids,
        )
        val readers = counts(
            "select post_id, count(*) from post_read where post_id in (?1) and read_at > now() - interval '5 minutes' group by post_id",
            ids,
        )
        val myUp = mine("select post_id from post_upvote where user_id = ?2 and post_id in (?1)", ids, me)
        val myLikes = mine("select post_id from post_like where user_id = ?2 and post_id in (?1)", ids, me)

        val commIds = posts.mapNotNull { it.communityId }.distinct()
        val comms = if (commIds.isEmpty()) emptyMap() else Community.list("id in ?1", commIds).associateBy { it.id }
        val myMember = commIds.filter { communities.roleOf(it, me) != null }.toSet()
        val authors = profiles.shorts(posts.map { it.authorId } + posts.mapNotNull { it.wallUserId })
        val files = attachments.load(AttachmentService.Owner.POST, ids)
        val postTracks = attachments.loadTracks(AttachmentService.Owner.POST, ids, me)
        val polls = polls(ids, me)
        val postTags = tags.tagsOf(TagService.Owner.POST, ids)
        val now = Instant.now()

        return posts.map { p ->
            val c = p.communityId?.let { comms[it] }
            val u = up[p.id] ?: 0
            val l = likes[p.id] ?: 0
            val cm = comments[p.id] ?: 0
            val sh = shares[p.id] ?: 0
            PostOut(
                id = p.id,
                community = c?.slug,
                communityInfo = c?.let { PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) },
                hue = c?.hue ?: 200,
                author = authors[p.authorId],
                createdAt = p.createdAt,
                updatedAt = p.updatedAt,
                title = p.title,
                body = p.body,
                kind = p.kind,
                meta = p.meta,
                // первая картинка — для старых клиентов; весь список — в attachments
                mediaUrl = files[p.id]?.firstOrNull()?.url ?: p.mediaId?.let { media.url(it) },
                pinned = p.pinned,
                up = u, likes = l, comments = cm, shares = sh,
                readers = readers[p.id] ?: 0,
                temp = temperature(u, l, cm, sh, Duration.between(p.createdAt, now)),
                hot = (lastMinute[p.id] ?: 0) > HOT_PER_MINUTE,
                my = MyPostOut(
                    p.id in myUp, p.id in myLikes,
                    // пульс открыт всем; запись сообщества — только участникам
                    canComment = p.isPulse || p.communityId == null || p.communityId in myMember,
                ),
                pulse = p.isPulse,
                pulsar = p.isPulse && c != null,
                asCommunity = p.asCommunity && c != null,
                attachments = files[p.id] ?: emptyList(),
                tracks = postTracks[p.id] ?: emptyList(),
                poll = polls[p.id],
                source = when {
                    p.wallUserId != null -> "wall"
                    p.isPulse -> "pulse"
                    else -> "community"
                },
                wallOwner = p.wallUserId?.let { authors[it] },
                tags = postTags[p.id] ?: emptyList(),
                sourceUrl = p.sourceUrl,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun polls(ids: List<UUID>, me: UUID): Map<UUID, PollOut> {
        val polls = em.createNativeQuery("select post_id, multiple, closes_at from poll where post_id in (?1)")
            .setParameter(1, ids).resultList as List<Array<Any?>>
        if (polls.isEmpty()) return emptyMap()
        val pollIds = polls.map { it[0] as UUID }
        val options = em.createNativeQuery(
            """
            select o.post_id, o.id, o.text, (select count(*) from poll_vote v where v.option_id = o.id)
            from poll_option o where o.post_id in (?1) order by o.post_id, o.position
            """.trimIndent(),
        ).setParameter(1, pollIds).resultList as List<Array<Any?>>
        val voters = counts("select post_id, count(distinct user_id) from poll_vote where post_id in (?1) group by post_id", pollIds)
        val myVotes = (em.createNativeQuery("select post_id, option_id from poll_vote where post_id in (?1) and user_id = ?2")
            .setParameter(1, pollIds).setParameter(2, me).resultList as List<Array<Any?>>)
            .groupBy({ it[0] as UUID }) { it[1] as UUID }
        val optsByPost = options.groupBy({ it[0] as UUID }) {
            PollOptionOut(it[1] as UUID, it[2] as String, (it[3] as Number).toLong())
        }
        val now = Instant.now()
        return polls.associate { r ->
            val pid = r[0] as UUID
            val closes = toInstant(r[2])
            pid to PollOut(
                options = optsByPost[pid] ?: emptyList(),
                multiple = r[1] as Boolean,
                closesAt = closes,
                closed = !closes.isAfter(now),
                voters = voters[pid] ?: 0,
                myVotes = myVotes[pid] ?: emptyList(),
            )
        }
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }

    /**
     * «Температура» 0..1 — заглушка скоринга: взвешенная активность,
     * затухающая с возрастом записи.
     */
    private fun temperature(up: Long, likes: Long, comments: Long, shares: Long, age: Duration): Double {
        val score = up * 3.0 + likes * 2.0 + comments * 2.0 + shares * 4.0
        val hours = age.toMinutes().coerceAtLeast(0) / 60.0
        return (1 - exp(-score / (10.0 * (hours + 2).pow(0.8)))).coerceIn(0.0, 1.0)
    }

    // ================================================================ utils

    private fun activePost(id: UUID): Post {
        val p = Post.findById(id)
        if (p == null || p.isDeleted) throw ApiException.notFound("запись не найдена")
        p.communityId?.let { cid ->
            val c = Community.findById(cid)
            if (c == null || c.isDeleted) throw ApiException.notFound("запись не найдена")
        }
        return p
    }

    private fun requireMemberOf(p: Post, me: UUID) {
        if (p.isPulse) return // пульс — общая лента: лайкать и комментировать может любой
        val cid = p.communityId ?: return
        if (communities.roleOf(cid, me) == null) throw ApiException.forbidden("нужно вступить в сообщество")
    }

    private fun adminRank(communityId: UUID, me: UUID) = communities.roleOf(communityId, me) in setOf("admin", "owner")

    /** @ник в записи → уведомление «mention» (payload: postId, communitySlug). */
    fun mentionPost(p: Post) {
        mentionsSvc.sync(
            MentionService.Owner.POST, p.id, p.authorId, listOfNotNull(p.title, p.body).joinToString("\n"), null,
            mapOf("postId" to p.id.toString(), "communitySlug" to p.communityId?.let { Community.findById(it)?.slug }),
        )
    }

    private fun userByName(username: String): AppUser =
        AppUser.find("username = ?1 and isDeleted = false", username.trim().lowercase()).firstResult()
            ?: throw ApiException.notFound("пользователь не найден")

    private fun requireAdminOf(p: Post, me: UUID) {
        val cid = p.communityId ?: throw ApiException.forbidden("это не твоя запись")
        if (!adminRank(cid, me)) throw ApiException.forbidden("недостаточно прав в сообществе")
    }

    /** Сколько апвоутов уже потрачено сегодня (UTC). Int — как и лимит UPVOTES_PER_DAY. */
    private fun usedToday(me: UUID): Int = count(
        "select count(*) from post_upvote where user_id = ?1 and created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc'",
        me,
    ).toInt()

    private fun title(v: String?): String? {
        val t = v?.trim() ?: return null
        if (t.length > MAX_TITLE) throw ApiException.badRequest("invalid_title", "заголовок длиннее $MAX_TITLE")
        return t.ifEmpty { null }
    }

    private fun meta(v: String?): String? {
        val t = v?.trim() ?: return null
        if (t.length > MAX_META) throw ApiException.badRequest("invalid_meta", "meta длиннее $MAX_META")
        return t.ifEmpty { null }
    }

    private fun far(): Instant = Instant.now().plusSeconds(60)

    private fun count(sql: String, vararg params: Any): Long {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        return (q.singleResult as Number).toLong()
    }

    private fun exists(sql: String, vararg params: Any): Boolean {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        return q.resultList.isNotEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun counts(sql: String, ids: List<UUID>): Map<UUID, Long> {
        val rows = em.createNativeQuery(sql).setParameter(1, ids).resultList as List<Array<Any?>>
        return rows.associate { it[0] as UUID to (it[1] as Number).toLong() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mine(sql: String, ids: List<UUID>, me: UUID): Set<UUID> =
        (em.createNativeQuery(sql, UUID::class.java).setParameter(1, ids).setParameter(2, me).resultList as List<UUID>).toSet()
}

@ApplicationScoped
class PostReadCleanupJob(private val em: EntityManager) {
    /** Прочтения старше суток больше ни на что не влияют (онлайн считается за 30 минут). */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    fun purge() {
        em.createNativeQuery("delete from post_read where read_at < now() - interval '1 day'").executeUpdate()
    }
}
