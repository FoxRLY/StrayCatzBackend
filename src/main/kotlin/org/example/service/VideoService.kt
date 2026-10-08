package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Community
import org.example.domain.Media
import org.example.domain.Post
import org.example.rest.ApiException
import org.example.rest.PostCommunityOut
import org.example.rest.ShareIn
import org.example.rest.ShareOut
import org.example.rest.ShareSentOut
import org.example.rest.VideoOut
import org.example.rest.VideoPageOut
import org.example.rest.VideoPatchIn
import org.example.rest.VideoUploadIn
import org.example.rest.VideoViewOut
import java.time.Instant
import java.util.UUID

/**
 * Все видео сети «одной папкой» — поверх SQL-вида video_item (V10):
 * ролики из записей сообществ, пульса и стен + загруженные прямо во вкладку.
 *
 * Ролик не копируется: это тот же файл (media), что и во вложении записи.
 * Лайк/апвоут/комментарии ролика = записи, в которой он опубликован
 * (/api/posts/{postId}/…). У загруженного без записи их нет.
 *
 * scope:
 *  - feed      — моё + друзья + сообщества, где я участник или читаю (по умолчанию);
 *  - all       — вся сеть;
 *  - mine      — «мои видео»: опубликованные мной, загруженные во вкладку и добавленные себе;
 *  - friends   — от друзей;
 *  - community — сообщества (?slug=);
 *  - user      — человека (?userId=).
 * sort: new (по умолчанию) | popular (по просмотрам).
 */
@ApplicationScoped
class VideoService(
    private val em: EntityManager,
    private val media: MediaService,
    private val profiles: UserProfileService,
    private val friends: FriendService,
    private val communities: CommunityService,
    private val posts: PostService,
    private val messages: MessageService,
    private val tags: TagService,
) {
    companion object {
        const val MAX_PAGE = 60
        const val MAX_TITLE = 120
        const val MAX_QUERY = 100
        val SCOPES = setOf("feed", "all", "mine", "friends", "community", "user")
        val SORTS = setOf("new", "popular")

        private const val VIEWS = "(select count(*) from video_view vv where vv.media_id = v.media_id)"
    }

    /** Строка выборки до рендера. */
    private data class Row(
        val mediaId: UUID,
        val uploaderId: UUID,
        val authorId: UUID,
        val communityId: UUID?,
        val postId: UUID?,
        val source: String,
        val asCommunity: Boolean,
        val createdAt: Instant,
        val addedAt: Instant?,
    )

    // ================================================================ списки

    @Transactional
    fun list(
        me: UUID, scopeRaw: String?, slug: String?, userId: UUID?, query: String?,
        sortRaw: String?, before: Instant?, offset: Int, limit: Int, tag: String? = null, hideSlop: Boolean = false,
    ): VideoPageOut {
        val scope = (scopeRaw ?: "feed").lowercase()
        if (scope !in SCOPES) throw ApiException.badRequest("invalid_scope", "scope: $SCOPES")
        val sort = (sortRaw ?: "new").lowercase()
        if (sort !in SORTS) throw ApiException.badRequest("invalid_sort", "sort: $SORTS")
        val size = limit.coerceIn(1, MAX_PAGE)
        val from = offset.coerceAtLeast(0)

        val params = mutableListOf<Any>(me) // ?1 = me всегда
        fun p(v: Any): String { params += v; return "?${params.size}" }

        val where = mutableListOf<String>()
        when (scope) {
            "feed" -> {
                val fr = friends.friendIdsOf(me)
                val parts = mutableListOf(
                    "v.author_id = ?1",
                    "mv.user_id is not null",
                    """v.community_id in (
                        select m.community_id from community_member m where m.user_id = ?1 and m.left_at is null
                        union select f.community_id from community_follow f where f.user_id = ?1)""",
                )
                if (fr.isNotEmpty()) parts += "(v.author_id in (${p(fr)}) and not v.as_community)"
                where += parts.joinToString(" or ", "(", ")")
            }
            "all" -> {}
            "mine" -> where += "((v.author_id = ?1 and not v.as_community) or mv.user_id is not null)"
            "friends" -> {
                val fr = friends.friendIdsOf(me)
                if (fr.isEmpty()) return VideoPageOut(emptyList(), false, scope, sort, null, null)
                where += "v.author_id in (${p(fr)}) and not v.as_community"
            }
            "community" -> {
                val c = communities.bySlug(slug?.takeIf { it.isNotBlank() }
                    ?: throw ApiException.badRequest("invalid_slug", "scope=community требует slug"))
                where += "v.community_id = ${p(c.id)}"
            }
            "user" -> {
                val uid = userId ?: throw ApiException.badRequest("invalid_user", "scope=user требует userId")
                where += "v.author_id = ${p(uid)} and not v.as_community"
            }
        }
        if (hideSlop) where += "(p.id is null or p.ai_slop_at is null)"
        tag?.takeIf { it.isNotBlank() }?.let { raw ->
            val t = p(TagService.normalize(raw) ?: throw ApiException.badRequest("invalid_tag", "тег: 2–40 букв/цифр/_"))
            // тег самого ролика или записи, в которой он опубликован
            where += """exists (select 1 from tag_link tl where tl.tag = $t and (
                (tl.owner_type = 'video' and tl.owner_id = v.media_id) or (tl.owner_type = 'post' and tl.owner_id = v.post_id)))"""
        }
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            if (q.length > MAX_QUERY) throw ApiException.badRequest("invalid_query", "запрос длиннее $MAX_QUERY")
            val like = p("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            where += "(vm.title ilike $like or p.title ilike $like or p.body ilike $like)"
        }

        // в «моих» порядок — когда добавил себе; в остальных — когда опубликовано
        val sortAt = if (scope == "mine") "coalesce(mv.added_at, v.created_at)" else "v.created_at"
        val order = if (sort == "popular") {
            "order by $VIEWS desc, $sortAt desc limit ${p(size + 1)} offset ${p(from)}"
        } else {
            where += "$sortAt < ${p(before ?: Instant.now().plusSeconds(60))}"
            "order by $sortAt desc limit ${p(size + 1)}"
        }

        val sql = """
            select v.media_id, v.uploader_id, v.author_id, v.community_id, v.post_id, v.source, v.as_community,
                   v.created_at, mv.added_at
            from video_item v
            left join user_video mv on mv.media_id = v.media_id and mv.user_id = ?1
            left join video_meta vm on vm.media_id = v.media_id
            left join post p on p.id = v.post_id
            ${if (where.isEmpty()) "" else "where " + where.joinToString(" and ")}
            $order
        """.trimIndent()

        val rows = rows(sql, params)
        val page = rows.take(size)
        val hasMore = rows.size > size
        return VideoPageOut(
            items = render(page, me),
            hasMore = hasMore,
            scope = scope,
            sort = sort,
            nextBefore = if (hasMore && sort == "new") page.lastOrNull()?.let { if (scope == "mine") it.addedAt ?: it.createdAt else it.createdAt } else null,
            nextOffset = if (hasMore && sort == "popular") from + size else null,
        )
    }

    @Transactional
    fun get(me: UUID, id: UUID): VideoOut = render(listOf(row(me, id)), me).first()

    // ================================================================ «мои видео»

    /** Загрузить ролик прямо во вкладку: файл уже в POST /api/media, здесь — подпись и обложка. */
    @Transactional
    fun upload(me: UUID, req: VideoUploadIn): VideoOut {
        val id = req.mediaId ?: throw ApiException.badRequest("invalid_media", "нужен mediaId")
        requireOwnVideo(me, id)
        em.createNativeQuery("insert into user_video (user_id, media_id) values (?1, ?2) on conflict do nothing")
            .setParameter(1, me).setParameter(2, id).executeUpdate()
        saveMeta(me, id, req.title, req.durationSec, req.posterMediaId, clearPoster = false)
        tags.sync(TagService.Owner.VIDEO, id, req.tags, req.title)
        return get(me, id)
    }

    /** Подпись/длительность/обложка — только тот, кто загрузил файл. */
    @Transactional
    fun update(me: UUID, id: UUID, req: VideoPatchIn): VideoOut {
        requireOwnVideo(me, id)
        saveMeta(me, id, req.title, req.durationSec, req.posterMediaId, req.clearPoster)
        if (req.title != null || req.tags != null) {
            val title = em.createNativeQuery("select title from video_meta where media_id = ?1").setParameter(1, id)
                .resultList.firstOrNull() as String?
            tags.sync(TagService.Owner.VIDEO, id, req.tags, title)
        }
        return get(me, id)
    }

    /** «Добавить себе» чужой (или свой) ролик. */
    @Transactional
    fun save(me: UUID, id: UUID): VideoOut {
        row(me, id) // ролик должен быть виден в сети
        em.createNativeQuery("insert into user_video (user_id, media_id) values (?1, ?2) on conflict do nothing")
            .setParameter(1, me).setParameter(2, id).executeUpdate()
        return get(me, id)
    }

    /**
     * Убрать из «моих видео». Загруженный без записи ролик после этого пропадает
     * из вкладки совсем; опубликованный в записи — остаётся в ней (удаляется вместе с записью).
     */
    @Transactional
    fun unsave(me: UUID, id: UUID) {
        em.createNativeQuery("delete from user_video where user_id = ?1 and media_id = ?2")
            .setParameter(1, me).setParameter(2, id).executeUpdate()
    }

    /** Просмотр: фронт шлёт, когда ролик реально начал играть. Уникально по человеку. */
    @Transactional
    fun view(me: UUID, id: UUID): VideoViewOut {
        row(me, id)
        em.createNativeQuery(
            "insert into video_view (media_id, user_id) values (?1, ?2) on conflict (media_id, user_id) do update set viewed_at = now()",
        ).setParameter(1, id).setParameter(2, me).executeUpdate()
        return VideoViewOut(count("select count(*) from video_view where media_id = ?1", id))
    }

    /**
     * Переслать ролик. Опубликованный в записи — пересылается запись целиком
     * (как «поделиться»). Загруженный без записи — сообщением с этим видео.
     */
    @Transactional
    fun share(me: UUID, id: UUID, req: ShareIn): ShareOut {
        val r = row(me, id)
        r.postId?.let { return posts.share(me, it, req) }

        val file = Media.findById(id) ?: throw ApiException.notFound("видео не найдено")
        val text = posts.comment(req.comment)
        val sent = posts.shareTargets(me, req).map { (chatId, userId) ->
            val ack = messages.send(chatId, me, text, null, UUID.randomUUID(), "share", forwardedMedia = listOf(file))
            ShareSentOut(chatId, ack.id, ack.seq, userId)
        }
        val first = sent.first()
        return ShareOut(first.chatId, first.messageId, first.seq, sent.size.toLong(), sent)
    }

    /** Для ленты: ролики пачкой по id файла (что не в video_item — пропускается). */
    @Transactional
    fun byIds(me: UUID, ids: Collection<UUID>): Map<UUID, VideoOut> {
        if (ids.isEmpty()) return emptyMap()
        val found = rows(
            """
            select v.media_id, v.uploader_id, v.author_id, v.community_id, v.post_id, v.source, v.as_community,
                   v.created_at, mv.added_at
            from video_item v left join user_video mv on mv.media_id = v.media_id and mv.user_id = ?1
            where v.media_id in (?2)
            """.trimIndent(),
            listOf(me, ids.distinct()),
        )
        return render(found, me).associateBy { it.id }
    }

    // ================================================================ внутреннее

    private fun row(me: UUID, id: UUID): Row = rows(
        """
        select v.media_id, v.uploader_id, v.author_id, v.community_id, v.post_id, v.source, v.as_community,
               v.created_at, mv.added_at
        from video_item v left join user_video mv on mv.media_id = v.media_id and mv.user_id = ?1
        where v.media_id = ?2
        """.trimIndent(),
        listOf(me, id),
    ).firstOrNull() ?: throw ApiException.notFound("видео не найдено")

    private fun requireOwnVideo(me: UUID, id: UUID): Media {
        val m = Media.findById(id) ?: throw ApiException.notFound("видео не найдено")
        if (m.ownerId != me) throw ApiException.forbidden("это не твоё видео")
        if (!m.contentType.startsWith("video/")) throw ApiException.badRequest("not_video", "это не видео")
        return m
    }

    private fun saveMeta(me: UUID, id: UUID, title: String?, durationSec: Int?, posterId: UUID?, clearPoster: Boolean) {
        val t = title?.trim()
        if (t != null && t.length > MAX_TITLE) throw ApiException.badRequest("invalid_title", "подпись длиннее $MAX_TITLE")
        if (durationSec != null && durationSec !in 1..86400) throw ApiException.badRequest("invalid_duration", "durationSec: 1..86400")
        posterId?.let {
            val img = media.requireOwned(it, me)
            if (!img.contentType.startsWith("image/")) throw ApiException.badRequest("invalid_poster", "обложка — картинка")
        }
        em.createNativeQuery("insert into video_meta (media_id) values (?1) on conflict do nothing")
            .setParameter(1, id).executeUpdate()
        if (t != null) {
            // "" — убрать подпись (null в параметре нативного запроса не типизируется, поэтому nullif)
            em.createNativeQuery("update video_meta set title = nullif(?2, ''), updated_at = now() where media_id = ?1")
                .setParameter(1, id).setParameter(2, t).executeUpdate()
        }
        durationSec?.let {
            em.createNativeQuery("update video_meta set duration_sec = ?2, updated_at = now() where media_id = ?1")
                .setParameter(1, id).setParameter(2, it).executeUpdate()
        }
        when {
            clearPoster -> em.createNativeQuery("update video_meta set poster_media_id = null, poster_auto = false, updated_at = now() where media_id = ?1")
                .setParameter(1, id).executeUpdate()
            posterId != null -> em.createNativeQuery("update video_meta set poster_media_id = ?2, poster_auto = false, updated_at = now() where media_id = ?1")
                .setParameter(1, id).setParameter(2, posterId).executeUpdate()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(sql: String, params: List<Any>): List<Row> {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
        return (q.resultList as List<Array<Any?>>).map {
            Row(
                mediaId = it[0] as UUID,
                uploaderId = it[1] as UUID,
                authorId = it[2] as UUID,
                communityId = it[3] as UUID?,
                postId = it[4] as UUID?,
                source = it[5] as String,
                asCommunity = it[6] as Boolean,
                createdAt = toInstant(it[7])!!,
                addedAt = toInstant(it[8]),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun render(rows: List<Row>, me: UUID): List<VideoOut> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.mediaId }
        val files = Media.list("id in ?1", ids).associateBy { it.id }
        val meta = (em.createNativeQuery("select media_id, title, duration_sec, poster_media_id, poster_auto from video_meta where media_id in (?1)")
            .setParameter(1, ids).resultList as List<Array<Any?>>).associateBy { it[0] as UUID }
        val postIds = rows.mapNotNull { it.postId }
        val postsById = if (postIds.isEmpty()) emptyMap() else Post.list("id in ?1", postIds).associateBy { it.id }
        val videoTags = tags.tagsOf(TagService.Owner.VIDEO, ids)
        val postTags = tags.tagsOf(TagService.Owner.POST, postIds)
        val commIds = rows.mapNotNull { it.communityId }.distinct()
        val comms = if (commIds.isEmpty()) emptyMap() else Community.list("id in ?1", commIds).associateBy { it.id }
        val users = profiles.shorts(rows.map { it.authorId })
        val views = counts("select media_id, count(*) from video_view where media_id in (?1) group by media_id", ids)
        val likes = if (postIds.isEmpty()) emptyMap() else
            counts("select post_id, count(*) from post_like where post_id in (?1) group by post_id", postIds)
        val ups = if (postIds.isEmpty()) emptyMap() else
            counts("select post_id, count(*) from post_upvote where post_id in (?1) group by post_id", postIds)
        val comments = if (postIds.isEmpty()) emptyMap() else
            counts("select post_id, count(*) from post_comment where post_id in (?1) and deleted_at is null group by post_id", postIds)

        return rows.mapNotNull { r ->
            val f = files[r.mediaId] ?: return@mapNotNull null
            val m = meta[r.mediaId]
            val p = r.postId?.let { postsById[it] }
            val c = r.communityId?.let { comms[it] }
            VideoOut(
                id = r.mediaId,
                url = media.url(r.mediaId),
                contentType = f.contentType,
                sizeBytes = f.sizeBytes,
                title = (m?.get(1) as String?) ?: p?.title ?: p?.body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(100),
                description = p?.body?.ifEmpty { null },
                durationSec = (m?.get(2) as Number?)?.toInt(),
                posterUrl = (m?.get(3) as UUID?)?.let { media.url(it) },
                source = r.source,
                postId = r.postId,
                author = users[r.authorId],
                community = c?.let { PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) },
                asCommunity = r.asCommunity && c != null,
                createdAt = r.createdAt,
                views = views[r.mediaId] ?: 0,
                likes = r.postId?.let { likes[it] } ?: 0,
                comments = r.postId?.let { comments[it] } ?: 0,
                up = r.postId?.let { ups[it] } ?: 0,
                inMine = r.addedAt != null || (r.authorId == me && !r.asCommunity),
                mine = r.uploaderId == me,
                addedAt = r.addedAt,
                tags = ((videoTags[r.mediaId] ?: emptyList()) + (r.postId?.let { postTags[it] } ?: emptyList())).distinct(),
                posterAuto = m?.get(4) as Boolean? ?: false,
            )
        }
    }

    private fun count(sql: String, vararg params: Any): Long {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        return (q.singleResult as Number).toLong()
    }

    @Suppress("UNCHECKED_CAST")
    private fun counts(sql: String, ids: List<UUID>): Map<UUID, Long> {
        val rows = em.createNativeQuery(sql).setParameter(1, ids).resultList as List<Array<Any?>>
        return rows.associate { it[0] as UUID to (it[1] as Number).toLong() }
    }

    private fun toInstant(v: Any?): Instant? = when (v) {
        null -> null
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> null
    }
}
