package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Post
import org.example.rest.ApiException
import org.example.rest.PollIn
import org.example.rest.PostOut
import org.example.rest.PulseIn
import org.example.rest.PulsePageOut
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Пульс — общая лента коротких записей (как твиттер/реддит) за последние сутки.
 *
 * Это те же записи (таблица post, is_pulse = true), поэтому апвоут, лайк,
 * комментарии с ответами, репост в чат, прочтения и плашка «активно
 * обсуждают» работают через уже существующие /api/posts/{id}/…
 *
 * Сортировки (?sort=):
 *  - hot     — активность за последний час: апвоут 3, комментарий 2, лайк 1, репост 4;
 *  - new     — просто по времени;
 *  - friends — только от друзей, по времени;
 *  - week    — лучшее за неделю по тем же весам (единственная сортировка шире суток).
 */
@ApplicationScoped
class PulseService(
    private val em: EntityManager,
    private val posts: PostService,
    private val communities: CommunityService,
    private val friends: FriendService,
    private val attachments: AttachmentService,
    private val tags: TagService,
) {
    companion object {
        const val MAX_BODY = 600
        const val MAX_PAGE = 50
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 6
        const val MAX_OPTION_LEN = 80
        val SORTS = setOf("hot", "new", "friends", "week")

        /** Очки активности. Окно подставляется в интервал. */
        private fun scoreSql(window: String) = """
            3 * (select count(*) from post_upvote u where u.post_id = p.id and u.created_at > now() - interval '$window')
          + 2 * (select count(*) from post_comment c where c.post_id = p.id and c.deleted_at is null and c.created_at > now() - interval '$window')
          + 1 * (select count(*) from post_like l where l.post_id = p.id and l.created_at > now() - interval '$window')
          + 4 * (select count(*) from post_share s where s.post_id = p.id and s.created_at > now() - interval '$window')
        """.trimIndent()

        private const val BASE_WHERE = """
            p.is_pulse and not p.is_deleted
            and (p.community_id is null or exists (select 1 from community c where c.id = p.community_id and not c.is_deleted))
        """
    }

    @Transactional
    fun list(me: UUID, sortRaw: String?, before: Instant?, offset: Int, limit: Int, tag: String? = null): PulsePageOut {
        // тег уже нормализован (только буквы/цифры/_), поэтому его можно подставить литералом
        val baseWhere = BASE_WHERE + (tag?.takeIf { it.isNotBlank() }?.let { raw ->
            val t = TagService.normalize(raw) ?: return PulsePageOut(emptyList(), false, (sortRaw ?: "hot").lowercase(), null, null)
            " and exists (select 1 from tag_link tl where tl.owner_type = 'post' and tl.owner_id = p.id and tl.tag = '$t')"
        } ?: "")
        val sort = (sortRaw ?: "hot").lowercase()
        if (sort !in SORTS) throw ApiException.badRequest("invalid_sort", "sort: $SORTS")
        val size = limit.coerceIn(1, MAX_PAGE)
        val from = offset.coerceAtLeast(0)

        val ids: List<UUID> = when (sort) {
            "new" -> ids(
                "select p.id from post p where $baseWhere and p.created_at > now() - interval '24 hours' " +
                        "and p.created_at < ?1 order by p.created_at desc limit ?2",
                before ?: far(), size + 1,
            )
            "friends" -> {
                val fr = friends.friendIdsOf(me)
                if (fr.isEmpty()) emptyList() else ids(
                    "select p.id from post p where $baseWhere and p.created_at > now() - interval '24 hours' " +
                            "and p.created_at < ?1 and p.creator_id in (?3) order by p.created_at desc limit ?2",
                    before ?: far(), size + 1, fr,
                )
            }
            "hot" -> ids(
                "select p.id from post p where $baseWhere and p.created_at > now() - interval '24 hours' " +
                        "order by (${scoreSql("1 hour")}) desc, p.created_at desc limit ?1 offset ?2",
                size + 1, from,
            )
            else -> ids( // week
                "select p.id from post p where $baseWhere and p.created_at > now() - interval '7 days' " +
                        "order by (${scoreSql("7 days")}) desc, p.created_at desc limit ?1 offset ?2",
                size + 1, from,
            )
        }

        val byId = if (ids.isEmpty()) emptyMap() else Post.list("id in ?1", ids).associateBy { it.id }
        val rows = ids.mapNotNull { byId[it] }
        val page = rows.take(size)
        val hasMore = rows.size > size
        val byTime = sort == "new" || sort == "friends"
        return PulsePageOut(
            items = posts.render(page, me),
            hasMore = hasMore,
            sort = sort,
            nextBefore = if (hasMore && byTime) page.lastOrNull()?.createdAt else null,
            nextOffset = if (hasMore && !byTime) from + size else null,
        )
    }

    @Transactional
    fun create(me: UUID, req: PulseIn): PostOut {
        val body = req.body?.trim().orEmpty()
        if (body.length > MAX_BODY) throw ApiException.badRequest("invalid_body", "не длиннее $MAX_BODY символов")
        val medias = attachments.validate(me, req.mediaIds, allowVideo = true)
        val trackIds = attachments.validateTracks(me, req.trackIds)
        if (body.isEmpty() && medias.isEmpty() && trackIds.isEmpty() && req.poll == null) {
            throw ApiException.badRequest("empty_pulse", "нужен текст, картинка/видео, трек или опрос")
        }
        val videos = medias.count { it.contentType.startsWith("video/") }
        if (req.poll != null && medias.isNotEmpty()) {
            throw ApiException.badRequest("invalid_media", "опрос и вложения в одной записи не совмещаются")
        }

        // привязка к сообществу: участник — «пульсар» от себя, admin может от имени сообщества
        val community = req.communitySlug?.takeIf { it.isNotBlank() }?.let { slug ->
            communities.bySlug(slug).also { c ->
                communities.requireRole(c, me, if (req.asCommunity) "admin" else "member")
            }
        }
        if (req.asCommunity && community == null) {
            throw ApiException.badRequest("invalid_community", "asCommunity требует communitySlug")
        }

        val kind = when {
            req.poll != null -> "poll"
            videos == 1 -> "video"
            medias.isNotEmpty() -> "image"
            trackIds.isNotEmpty() -> "track"
            else -> "text"
        }
        val p = Post().also {
            it.id = UUID.randomUUID()
            it.authorId = me
            it.communityId = community?.id
            it.body = body
            it.kind = kind
            it.isPulse = true
            it.asCommunity = req.asCommunity
            it.mediaId = medias.firstOrNull()?.id
        }
        p.persist()
        em.flush()

        attachments.attach(AttachmentService.Owner.POST, p.id, medias)
        attachments.attachTracks(AttachmentService.Owner.POST, p.id, trackIds)
        tags.sync(TagService.Owner.POST, p.id, req.tags, p.body)
        posts.mentionPost(p)
        req.poll?.let { createPoll(p.id, it) }
        return posts.render(listOf(p), me).first()
    }

    /**
     * Голос в опросе. optionIds заменяют прошлый выбор (переголосовать можно,
     * пока опрос открыт). Для single — ровно один вариант; пустой список — отозвать голос.
     */
    @Transactional
    fun vote(me: UUID, postId: UUID, optionIds: List<UUID>): PostOut {
        val p = Post.findById(postId)
        if (p == null || p.isDeleted || !p.isPulse) throw ApiException.notFound("запись не найдена")

        @Suppress("UNCHECKED_CAST")
        val poll = em.createNativeQuery("select multiple, closes_at from poll where post_id = ?1")
            .setParameter(1, postId).resultList.firstOrNull() as Array<Any?>?
            ?: throw ApiException.notFound("в записи нет опроса")
        val multiple = poll[0] as Boolean
        val closes = when (val v = poll[1]) {
            is Instant -> v
            is java.time.OffsetDateTime -> v.toInstant()
            is java.sql.Timestamp -> v.toInstant()
            else -> Instant.EPOCH
        }
        if (!closes.isAfter(Instant.now())) throw ApiException.badRequest("poll_closed", "опрос уже закрыт")

        val chosen = optionIds.distinct()
        if (!multiple && chosen.size > 1) throw ApiException.badRequest("single_choice", "здесь можно выбрать только один вариант")
        @Suppress("UNCHECKED_CAST")
        val valid = (em.createNativeQuery("select id from poll_option where post_id = ?1", UUID::class.java)
            .setParameter(1, postId).resultList as List<UUID>).toSet()
        if (!valid.containsAll(chosen)) throw ApiException.badRequest("invalid_option", "такого варианта нет в опросе")

        em.createNativeQuery("delete from poll_vote where post_id = ?1 and user_id = ?2")
            .setParameter(1, postId).setParameter(2, me).executeUpdate()
        chosen.forEach {
            em.createNativeQuery("insert into poll_vote (post_id, option_id, user_id) values (?1, ?2, ?3)")
                .setParameter(1, postId).setParameter(2, it).setParameter(3, me).executeUpdate()
        }
        return posts.render(listOf(p), me).first()
    }

    // ------------------------------------------------------------------

    private fun createPoll(postId: UUID, req: PollIn) {
        val options = req.options.map { it.trim() }.filter { it.isNotEmpty() }
        if (options.size !in MIN_OPTIONS..MAX_OPTIONS) {
            throw ApiException.badRequest("invalid_poll", "в опросе от $MIN_OPTIONS до $MAX_OPTIONS вариантов")
        }
        if (options.any { it.length > MAX_OPTION_LEN }) {
            throw ApiException.badRequest("invalid_poll", "вариант длиннее $MAX_OPTION_LEN символов")
        }
        if (options.map { it.lowercase() }.distinct().size != options.size) {
            throw ApiException.badRequest("invalid_poll", "варианты повторяются")
        }
        val hours = (req.closesInHours ?: 24)
        if (hours !in 1..168) throw ApiException.badRequest("invalid_poll", "closesInHours: от 1 до 168")

        em.createNativeQuery("insert into poll (post_id, multiple, closes_at) values (?1, ?2, ?3)")
            .setParameter(1, postId).setParameter(2, req.multiple)
            .setParameter(3, Instant.now().plus(Duration.ofHours(hours.toLong()))).executeUpdate()
        options.forEachIndexed { i, text ->
            em.createNativeQuery("insert into poll_option (id, post_id, text, position) values (?1, ?2, ?3, ?4)")
                .setParameter(1, UUID.randomUUID()).setParameter(2, postId).setParameter(3, text).setParameter(4, i)
                .executeUpdate()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ids(sql: String, vararg params: Any): List<UUID> {
        val q = em.createNativeQuery(sql, UUID::class.java)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        return q.resultList as List<UUID>
    }

    private fun far(): Instant = Instant.now().plusSeconds(60)
}
