package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Post
import org.example.rest.ApiException
import org.example.rest.PulsePageOut
import org.example.rest.RecommendReasonOut
import org.example.rest.UserShortOut
import org.example.rest.VideoPageOut
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Умная лента «как в старом ВК» — для ленты, пульса и видео (?algo=true).
 * Старые режимы (по времени / hot / popular) не меняются.
 *
 * Страница собирается из четырёх корзин по квотам:
 *  - подписки ≈ 40% — друзья и мои сообщества, по времени (в первую очередь: если остальных нет —
 *    подписки заполняют всё);
 *  - похожее ≈ 30% — новое, НЕ из подписок: совпадает по тегам, авторам и сообществам с тем,
 *    что я апвоутил (вес 3), лайкал (2), репостил (2), комментировал (1,5), смотрел (0,5);
 *    сигналы затухают с полураспадом ~3 недели (e-fold 30 дней), смотрим последние 90 дней;
 *  - друзья ≈ 18% — что лайкают, апвоутят, комментируют и смотрят мои друзья;
 *  - популярное ≈ 12% — топ по активности во всей сети (общий для всех, кэш на минуту).
 * Корзины чередуются «по наибольшему недобору»: на каждой позиции берётся корзина, которая сильнее
 * всех отстаёт от своей доли. Пустая корзина пропускается — её доля достаётся остальным.
 *
 * Уже прочитанное, лайкнутое/апвоутнутое мной и моё собственное в рекомендации не попадает.
 * hideSlop = true — без записей с плашкой «ИИ слоп» (во всех корзинах).
 *
 * Курсор: "<asOf мс>.<offset>". Всё считается «на момент asOf» (сигналы, окна, прочтения — до asOf),
 * поэтому следующая страница на любой ноде получается той же лентой без повторов.
 */
@ApplicationScoped
class RecommendService(
    private val em: EntityManager,
    private val friends: FriendService,
    private val profiles: UserProfileService,
    private val posts: PostService,
    private val videos: VideoService,
) {
    companion object {
        const val SHARE_SUBS = 0.40
        const val SHARE_SIMILAR = 0.30
        const val SHARE_FRIENDS = 0.18
        const val SHARE_GLOBAL = 0.12
        /** Длина одной «сессии» умной ленты; дальше — hasMore = false. */
        const val MAX_ITEMS = 500
        const val MAX_PAGE = 50
        private const val DECAY_SEC = 30.0 * 86400
    }

    enum class Surface(
        val key: String,
        /** Окно кандидатов. */
        val window: Duration,
        /** Окно активности для «популярного». */
        val hotWindow: Duration,
        /** Свежесть: e-fold, часов. */
        val freshHours: Double,
    ) {
        LENTA("lenta", Duration.ofDays(7), Duration.ofHours(48), 48.0),
        PULSE("pulse", Duration.ofHours(48), Duration.ofHours(24), 12.0),
        VIDEO("video", Duration.ofDays(30), Duration.ofDays(7), 168.0),
    }

    /** Кандидат рекомендации. */
    data class Cand(val id: UUID, val score: Double, val reason: RecommendReasonOut)

    /** Ячейка готовой ленты: [key] — ключ элемента подписок или "rec:<id>"; reason — почему. */
    data class Slot(val key: String, val recId: UUID?, val reason: RecommendReasonOut)

    data class Cursor(val asOf: Instant, val offset: Int) {
        override fun toString() = "${asOf.toEpochMilli()}.$offset"
    }

    private data class Profile(val authors: Map<UUID, Double>, val communities: Map<UUID, Double>, val tags: Map<String, Double>) {
        fun isEmpty() = authors.isEmpty() && communities.isEmpty() && tags.isEmpty()
    }

    private val profileCache = TtlCache<String, Profile>(10 * 60_000L, 20_000)
    private val globalCache = TtlCache<String, List<Pair<UUID, Double>>>(120_000L, 50)
    private val slotCache = TtlCache<String, List<Slot>>(10 * 60_000L, 2_000)

    // ================================================================ курсор

    fun parseCursor(raw: String?): Cursor {
        if (raw.isNullOrBlank()) return Cursor(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), 0)
        val parts = raw.split('.')
        val ms = parts.getOrNull(0)?.toLongOrNull()
        val off = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 2 || ms == null || off == null || off < 0 || off > MAX_ITEMS) {
            throw ApiException.badRequest("invalid_cursor", "cursor из nextCursor предыдущей страницы")
        }
        val asOf = Instant.ofEpochMilli(ms)
        // старше суток — начать заново (лента устарела)
        if (asOf.isBefore(Instant.now().minus(Duration.ofDays(1))) || asOf.isAfter(Instant.now().plusSeconds(60))) {
            throw ApiException(410, "cursor_expired", "лента устарела — запроси первую страницу без cursor")
        }
        return Cursor(asOf, off)
    }

    // ================================================================ пульс и видео

    @Transactional
    fun pulse(me: UUID, cursorRaw: String?, limit: Int, hideSlop: Boolean): PulsePageOut {
        val c = parseCursor(cursorRaw)
        val size = limit.coerceIn(1, MAX_PAGE)
        val slots = slotCache.get("pulse:$me:$hideSlop:${c.asOf.toEpochMilli()}") {
            val subs = subsPosts(me, Surface.PULSE, c.asOf, hideSlop)
            mix(subs.map { "post:$it" }, recommend(me, Surface.PULSE, c.asOf, hideSlop))
        }
        val page = slots.drop(c.offset).take(size)
        val ids = page.map { it.recId ?: UUID.fromString(it.key.substringAfter(':')) }
        val byId = if (ids.isEmpty()) emptyMap() else Post.list("id in ?1 and isDeleted = false", ids).associateBy { it.id }
        val outs = posts.render(ids.mapNotNull { byId[it] }, me).associateBy { it.id }
        val items = page.mapNotNull { s -> outs[s.recId ?: UUID.fromString(s.key.substringAfter(':'))]?.copy(reason = s.reason) }
        val end = c.offset + page.size
        val more = end < slots.size
        return PulsePageOut(items, more, "smart", null, null, if (more) Cursor(c.asOf, end).toString() else null)
    }

    @Transactional
    fun video(me: UUID, cursorRaw: String?, limit: Int, hideSlop: Boolean): VideoPageOut {
        val c = parseCursor(cursorRaw)
        val size = limit.coerceIn(1, MAX_PAGE)
        val slots = slotCache.get("video:$me:$hideSlop:${c.asOf.toEpochMilli()}") {
            val subs = subsVideos(me, c.asOf, hideSlop)
            mix(subs.map { "video:$it" }, recommend(me, Surface.VIDEO, c.asOf, hideSlop), "video")
        }
        val page = slots.drop(c.offset).take(size)
        val ids = page.map { it.recId ?: UUID.fromString(it.key.substringAfter(':')) }
        val outs = videos.byIds(me, ids)
        val items = page.mapNotNull { s -> outs[s.recId ?: UUID.fromString(s.key.substringAfter(':'))]?.copy(reason = s.reason) }
        val end = c.offset + page.size
        val more = end < slots.size
        return VideoPageOut(items, more, "feed", "smart", null, null, if (more) Cursor(c.asOf, end).toString() else null)
    }

    // ================================================================ корзины

    /** Три корзины рекомендаций (кроме подписок) для [surface]. */
    data class Buckets(val similar: List<Cand>, val friends: List<Cand>, val global: List<Cand>)

    fun recommend(me: UUID, surface: Surface, asOf: Instant, hideSlop: Boolean): Buckets {
        val fr = friends.friendIdsOf(me)
        val profile = profileCache.get("$me:${asOf.toEpochMilli()}") { profile(me, asOf) }
        val similar = if (surface == Surface.VIDEO) similarVideos(me, fr, profile, asOf, hideSlop)
        else similarPosts(me, fr, profile, surface, asOf, hideSlop)
        val byFriends = if (fr.isEmpty()) emptyList() else
            if (surface == Surface.VIDEO) friendsVideos(me, fr, asOf, hideSlop) else friendsPosts(me, fr, surface, asOf, hideSlop)
        val global = global(me, surface, asOf, hideSlop)
        return Buckets(similar, byFriends, global)
    }

    /**
     * Чередование корзин по квотам. [subs] — ключи элементов подписок в порядке показа.
     * Рекомендации дедуплицируются друг с другом и с подписками (ключ "post:<id>" / "video:<id>").
     */
    fun mix(subs: List<String>, b: Buckets, recPrefix: String = "post"): List<Slot> {
        val subReason = RecommendReasonOut("subscription", "из подписок")
        val queues = listOf(
            SHARE_SUBS to ArrayDeque(subs.map { Slot(it, null, subReason) }),
            SHARE_SIMILAR to ArrayDeque(b.similar.map { Slot("rec:${it.id}", it.id, it.reason) }),
            SHARE_FRIENDS to ArrayDeque(b.friends.map { Slot("rec:${it.id}", it.id, it.reason) }),
            SHARE_GLOBAL to ArrayDeque(b.global.map { Slot("rec:${it.id}", it.id, it.reason) }),
        )
        val taken = IntArray(queues.size)
        val used = HashSet<String>()
        val out = ArrayList<Slot>()
        // элемент из подписок «post:<id>» и рекомендация того же поста — один и тот же
        fun identity(s: Slot) = if (s.recId != null) "$recPrefix:${s.recId}" else s.key
        while (out.size < MAX_ITEMS) {
            var best = -1
            var bestDeficit = Double.NEGATIVE_INFINITY
            val n = out.size + 1
            for (i in queues.indices) {
                val q = queues[i].second
                while (q.isNotEmpty() && identity(q.first()) in used) q.removeFirst()
                if (q.isEmpty()) continue
                val deficit = queues[i].first * n - taken[i]
                if (deficit > bestDeficit + 1e-9) { bestDeficit = deficit; best = i }
            }
            if (best < 0) break
            val s = queues[best].second.removeFirst()
            used += identity(s)
            taken[best]++
            out += s
        }
        return out
    }

    // ---------------------------------------------------------------- профиль интересов

    @Suppress("UNCHECKED_CAST")
    private fun profile(me: UUID, asOf: Instant): Profile {
        val decay = "exp(-extract(epoch from (cast(?2 as timestamptz) - x.at)) / $DECAY_SEC)"
        val win = "> cast(?2 as timestamptz) - interval '90 days' and %s < cast(?2 as timestamptz)"
        fun w(col: String) = "$col " + win.format(col)
        val rows = em.createNativeQuery(
            """
            with sig as (
                select u.post_id, 3.0 as w, u.created_at as at from post_upvote u where u.user_id = ?1 and ${w("u.created_at")}
                union all select l.post_id, 2.0, l.created_at from post_like l where l.user_id = ?1 and ${w("l.created_at")}
                union all select c.post_id, 1.5, c.created_at from post_comment c where c.author_id = ?1 and c.deleted_at is null and ${w("c.created_at")}
                union all select s.post_id, 2.0, s.created_at from post_share s where s.user_id = ?1 and ${w("s.created_at")}
                union all select a.owner_id, 0.5, vv.viewed_at from video_view vv
                    join media_attachment a on a.media_id = vv.media_id and a.owner_type = 'post'
                    where vv.user_id = ?1 and ${w("vv.viewed_at")}
            ),
            ws as (select x.post_id, sum(x.w * $decay) as s from sig x group by x.post_id)
            (select 'a', cast(p.creator_id as text), sum(ws.s) from ws join post p on p.id = ws.post_id
                where not p.as_community and p.creator_id <> ?1 group by p.creator_id order by 3 desc limit 50)
            union all
            (select 'c', cast(p.community_id as text), sum(ws.s) from ws join post p on p.id = ws.post_id
                where p.community_id is not null group by p.community_id order by 3 desc limit 30)
            union all
            (select 't', tl.tag, sum(ws.s) from ws join tag_link tl on tl.owner_type = 'post' and tl.owner_id = ws.post_id
                group by tl.tag order by 3 desc limit 40)
            union all
            (select 'a', cast(m.owner_id as text), sum(0.5 * $decay) from (
                    select vv.media_id, vv.viewed_at as at from video_view vv where vv.user_id = ?1 and ${w("vv.viewed_at")}
                ) x join media m on m.id = x.media_id
                where m.owner_id <> ?1 and not exists (select 1 from media_attachment a where a.media_id = x.media_id and a.owner_type = 'post')
                group by m.owner_id order by 3 desc limit 20)
            """.trimIndent(),
        ).setParameter(1, me).setParameter(2, asOf).resultList as List<Array<Any?>>
        val a = HashMap<UUID, Double>()
        val c = HashMap<UUID, Double>()
        val t = HashMap<String, Double>()
        for (r in rows) {
            val v = (r[2] as Number).toDouble()
            when (r[0]) {
                "a" -> a.merge(UUID.fromString(r[1] as String), v, Double::plus)
                "c" -> c.merge(UUID.fromString(r[1] as String), v, Double::plus)
                "t" -> t.merge(r[1] as String, v, Double::plus)
            }
        }
        fun <K> norm(m: Map<K, Double>): Map<K, Double> {
            val max = m.values.maxOrNull() ?: return emptyMap()
            return if (max <= 0) emptyMap() else m.mapValues { it.value / max }
        }
        return Profile(norm(a), norm(c), norm(t))
    }

    // ---------------------------------------------------------------- подписки

    /** Пульс: записи друзей и моих сообществ за окно, по времени. */
    @Suppress("UNCHECKED_CAST")
    fun subsPosts(me: UUID, surface: Surface, asOf: Instant, hideSlop: Boolean): List<UUID> {
        val q = Q(me, asOf)
        val fr = friends.friendIdsOf(me)
        val sql = """
            select p.id from post p left join community c on c.id = p.community_id
            where ${postBase(q, surface, hideSlop)} and p.creator_id <> ?1
              and (${subsCond(q, fr)})
            order by p.created_at desc limit 300
        """
        return q.ids(sql)
    }

    @Suppress("UNCHECKED_CAST")
    private fun subsVideos(me: UUID, asOf: Instant, hideSlop: Boolean): List<UUID> {
        val q = Q(me, asOf)
        val fr = friends.friendIdsOf(me)
        val sql = """
            select v.media_id from video_item v left join post p on p.id = v.post_id
            where ${videoBase(q, hideSlop)} and v.author_id <> ?1 and (${videoSubsCond(q, fr)})
            order by v.created_at desc limit 300
        """
        return q.ids(sql)
    }

    // ---------------------------------------------------------------- похожее

    @Suppress("UNCHECKED_CAST")
    private fun similarPosts(me: UUID, fr: List<UUID>, pr: Profile, surface: Surface, asOf: Instant, hideSlop: Boolean): List<Cand> {
        if (pr.isEmpty()) return emptyList()
        val q = Q(me, asOf)
        val match = mutableListOf<String>()
        val tagP = if (pr.tags.isNotEmpty()) q.p(pr.tags.keys.toList()) else null
        if (pr.authors.isNotEmpty()) match += "(p.creator_id in (${q.p(pr.authors.keys.toList())}) and not p.as_community)"
        if (pr.communities.isNotEmpty()) match += "p.community_id in (${q.p(pr.communities.keys.toList())})"
        if (tagP != null) match += "exists (select 1 from tag_link tl where tl.owner_type = 'post' and tl.owner_id = p.id and tl.tag in ($tagP))"
        val rows = q.rows(
            """
            select p.id, p.creator_id, p.as_community, p.community_id, p.created_at,
                   ${if (tagP != null) "(select string_agg(tl.tag, ',') from tag_link tl where tl.owner_type = 'post' and tl.owner_id = p.id and tl.tag in ($tagP))" else "null"},
                   (select count(*) from post_upvote u where u.post_id = p.id and u.created_at < ?2) * 3
                   + (select count(*) from post_like l where l.post_id = p.id and l.created_at < ?2)
            from post p left join community c on c.id = p.community_id
            where ${postBase(q, surface, hideSlop)} and p.creator_id <> ?1
              and not (${subsCond(q, fr)})
              and ${notSeenPost()}
              and (${match.joinToString(" or ")})
            order by p.created_at desc limit 400
            """,
        )
        return rows.map { r ->
            val tags = (r[5] as String?)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
            score(r[0] as UUID, if (r[2] == true) null else r[1] as UUID, r[3] as UUID?, tags, toInstant(r[4]), (r[6] as Number).toDouble(), pr, surface, asOf)
        }.sortedByDescending { it.score }.take(200)
    }

    @Suppress("UNCHECKED_CAST")
    private fun similarVideos(me: UUID, fr: List<UUID>, pr: Profile, asOf: Instant, hideSlop: Boolean): List<Cand> {
        if (pr.isEmpty()) return emptyList()
        val q = Q(me, asOf)
        val match = mutableListOf<String>()
        val tagP = if (pr.tags.isNotEmpty()) q.p(pr.tags.keys.toList()) else null
        if (pr.authors.isNotEmpty()) match += "(v.author_id in (${q.p(pr.authors.keys.toList())}) and not v.as_community)"
        if (pr.communities.isNotEmpty()) match += "v.community_id in (${q.p(pr.communities.keys.toList())})"
        val tagOwner = "((tl.owner_type = 'video' and tl.owner_id = v.media_id) or (tl.owner_type = 'post' and tl.owner_id = v.post_id))"
        if (tagP != null) match += "exists (select 1 from tag_link tl where $tagOwner and tl.tag in ($tagP))"
        val rows = q.rows(
            """
            select v.media_id, v.author_id, v.as_community, v.community_id, v.created_at,
                   ${if (tagP != null) "(select string_agg(distinct tl.tag, ',') from tag_link tl where $tagOwner and tl.tag in ($tagP))" else "null"},
                   (select count(*) from video_view vv where vv.media_id = v.media_id and vv.viewed_at < ?2)
            from video_item v left join post p on p.id = v.post_id
            where ${videoBase(q, hideSlop)} and v.author_id <> ?1
              and not (${videoSubsCond(q, fr)})
              and not exists (select 1 from video_view mv where mv.media_id = v.media_id and mv.user_id = ?1 and mv.viewed_at < ?2)
              and (${match.joinToString(" or ")})
            order by v.created_at desc limit 400
            """,
        )
        return rows.map { r ->
            val tags = (r[5] as String?)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
            score(r[0] as UUID, if (r[2] == true) null else r[1] as UUID, r[3] as UUID?, tags, toInstant(r[4]), (r[6] as Number).toDouble() / 3, pr, Surface.VIDEO, asOf)
        }.sortedByDescending { it.score }.take(200)
    }

    /** Сходство (теги сильнее всего, потом автор, потом сообщество) × свежесть + немного популярности. */
    private fun score(
        id: UUID, author: UUID?, community: UUID?, tags: List<String>, at: Instant, engagement: Double,
        pr: Profile, surface: Surface, asOf: Instant,
    ): Cand {
        val tagScore = min(2.0, tags.sumOf { pr.tags[it] ?: 0.0 })
        val authorScore = author?.let { pr.authors[it] } ?: 0.0
        val commScore = community?.let { pr.communities[it] } ?: 0.0
        val sim = tagScore + 0.8 * authorScore + 0.6 * commScore
        val ageH = Duration.between(at, asOf).toMinutes() / 60.0
        val fresh = exp(-ageH / surface.freshHours)
        val s = sim * (0.4 + fresh) + 0.15 * ln(1 + engagement)
        val topTags = tags.sortedByDescending { pr.tags[it] ?: 0.0 }.take(2)
        val text = when {
            topTags.isNotEmpty() -> "похоже на " + topTags.joinToString(" и ") { "#$it" }
            authorScore > 0 -> "ты часто лайкаешь этого автора"
            else -> "похоже на то, что тебе нравится"
        }
        return Cand(id, s, RecommendReasonOut("similar", text, tags = topTags))
    }

    // ---------------------------------------------------------------- друзья

    @Suppress("UNCHECKED_CAST")
    private fun friendsPosts(me: UUID, fr: List<UUID>, surface: Surface, asOf: Instant, hideSlop: Boolean): List<Cand> {
        val q = Q(me, asOf)
        val f = q.p(fr)
        val win = q.p(surface.window.seconds.toDouble())
        val t = { col: String -> "$col > cast(?2 as timestamptz) - make_interval(secs => $win) and $col < ?2" }
        val rows = q.rows(
            """
            with fs as (
                select u.post_id, u.user_id, 3.0 as w from post_upvote u where u.user_id in ($f) and ${t("u.created_at")}
                union all select l.post_id, l.user_id, 2.0 from post_like l where l.user_id in ($f) and ${t("l.created_at")}
                union all select c.post_id, c.author_id, 1.5 from post_comment c where c.author_id in ($f) and c.deleted_at is null and ${t("c.created_at")}
                union all select s.post_id, s.user_id, 2.0 from post_share s where s.user_id in ($f) and ${t("s.created_at")}
            )
            select p.id, sum(fs.w), count(distinct fs.user_id), string_agg(distinct cast(fs.user_id as text), ',')
            from fs join post p on p.id = fs.post_id left join community c on c.id = p.community_id
            where ${postBase(q, surface, hideSlop, window = false)} and p.creator_id <> ?1 and ${notSeenPost()}
            group by p.id order by 2 desc, 3 desc limit 200
            """,
        )
        return friendCands(rows)
    }

    @Suppress("UNCHECKED_CAST")
    private fun friendsVideos(me: UUID, fr: List<UUID>, asOf: Instant, hideSlop: Boolean): List<Cand> {
        val q = Q(me, asOf)
        val f = q.p(fr)
        val win = q.p(Surface.VIDEO.window.seconds.toDouble())
        val t = { col: String -> "$col > cast(?2 as timestamptz) - make_interval(secs => $win) and $col < ?2" }
        val rows = q.rows(
            """
            with fs as (
                select vv.media_id, vv.user_id, 1.0 as w from video_view vv where vv.user_id in ($f) and ${t("vv.viewed_at")}
                union all select a.media_id, u.user_id, 3.0 from post_upvote u
                    join media_attachment a on a.owner_type = 'post' and a.owner_id = u.post_id
                    where u.user_id in ($f) and ${t("u.created_at")}
                union all select a.media_id, l.user_id, 2.0 from post_like l
                    join media_attachment a on a.owner_type = 'post' and a.owner_id = l.post_id
                    where l.user_id in ($f) and ${t("l.created_at")}
            )
            select v.media_id, sum(fs.w), count(distinct fs.user_id), string_agg(distinct cast(fs.user_id as text), ',')
            from fs join video_item v on v.media_id = fs.media_id left join post p on p.id = v.post_id
            where v.author_id <> ?1 ${if (hideSlop) "and (p.id is null or p.ai_slop_at is null)" else ""}
              and not exists (select 1 from video_view mv where mv.media_id = v.media_id and mv.user_id = ?1 and mv.viewed_at < ?2)
            group by v.media_id order by 2 desc, 3 desc limit 200
            """,
        )
        return friendCands(rows)
    }

    private fun friendCands(rows: List<Array<Any?>>): List<Cand> {
        val firstIds = rows.associate { r ->
            (r[0] as UUID) to (r[3] as String?)?.split(',')?.filter { it.isNotEmpty() }?.map(UUID::fromString).orEmpty()
        }
        val users: Map<UUID, UserShortOut> = profiles.shorts(firstIds.values.flatMap { it.take(3) }.distinct())
        return rows.map { r ->
            val id = r[0] as UUID
            val n = (r[2] as Number).toInt()
            val who = firstIds[id].orEmpty().take(3).mapNotNull { users[it] }
            val text = when {
                who.isEmpty() -> "нравится друзьям"
                n <= 1 -> "нравится ${who[0].username}"
                else -> "нравится ${who[0].username} и ещё ${n - 1}"
            }
            Cand(id, (r[1] as Number).toDouble(), RecommendReasonOut("friends", text, friends = who))
        }
    }

    // ---------------------------------------------------------------- популярное

    @Suppress("UNCHECKED_CAST")
    private fun global(me: UUID, surface: Surface, asOf: Instant, hideSlop: Boolean): List<Cand> {
        // общий для всех, кто начал ленту в эту минуту: сигналы «до начала минуты»
        val minute = asOf.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val top = globalCache.get("${surface.key}:$hideSlop:${minute.toEpochMilli()}") { globalTop(surface, minute, hideSlop) }
        if (top.isEmpty()) return emptyList()
        // моё, прочитанное и уже лайкнутое — убрать
        val ids = top.map { it.first }
        val seen: Set<UUID> = if (surface == Surface.VIDEO) {
            (em.createNativeQuery(
                """
                select vv.media_id from video_view vv where vv.user_id = ?1 and vv.media_id in (?2) and vv.viewed_at < ?3
                union select m.id from media m where m.id in (?2) and m.owner_id = ?1
                """.trimIndent(), UUID::class.java,
            ).setParameter(1, me).setParameter(2, ids).setParameter(3, asOf).resultList as List<UUID>).toSet()
        } else {
            (em.createNativeQuery(
                """
                select r.post_id from post_read r where r.user_id = ?1 and r.post_id in (?2) and r.read_at < ?3
                union select l.post_id from post_like l where l.user_id = ?1 and l.post_id in (?2)
                union select u.post_id from post_upvote u where u.user_id = ?1 and u.post_id in (?2)
                union select p.id from post p where p.id in (?2) and p.creator_id = ?1
                """.trimIndent(), UUID::class.java,
            ).setParameter(1, me).setParameter(2, ids).setParameter(3, asOf).resultList as List<UUID>).toSet()
        }
        val reason = RecommendReasonOut("popular", "популярно сейчас")
        return top.filter { it.first !in seen }.map { Cand(it.first, it.second, reason) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun globalTop(surface: Surface, asOf: Instant, hideSlop: Boolean): List<Pair<UUID, Double>> {
        val hot = surface.hotWindow.seconds.toDouble()
        val t = { col: String -> "$col > cast(?1 as timestamptz) - make_interval(secs => ?2) and $col < ?1" }
        val rows = if (surface == Surface.VIDEO) {
            em.createNativeQuery(
                """
                with s as (
                    select vv.media_id, 1.0 as w from video_view vv where ${t("vv.viewed_at")}
                    union all select a.media_id, 3.0 from post_upvote u join media_attachment a on a.owner_type = 'post' and a.owner_id = u.post_id
                        where ${t("u.created_at")}
                    union all select a.media_id, 1.0 from post_like l join media_attachment a on a.owner_type = 'post' and a.owner_id = l.post_id
                        where ${t("l.created_at")}
                )
                select v.media_id, sum(s.w) from s join video_item v on v.media_id = s.media_id left join post p on p.id = v.post_id
                where v.created_at > cast(?1 as timestamptz) - make_interval(secs => ?3)
                  ${if (hideSlop) "and (p.id is null or p.ai_slop_at is null)" else ""}
                group by v.media_id order by 2 desc limit 300
                """.trimIndent(),
            )
        } else {
            em.createNativeQuery(
                """
                with s as (
                    select u.post_id, 3.0 as w from post_upvote u where ${t("u.created_at")}
                    union all select l.post_id, 1.0 from post_like l where ${t("l.created_at")}
                    union all select c.post_id, 2.0 from post_comment c where c.deleted_at is null and ${t("c.created_at")}
                    union all select sh.post_id, 4.0 from post_share sh where ${t("sh.created_at")}
                )
                select p.id, sum(s.w) from s join post p on p.id = s.post_id left join community c on c.id = p.community_id
                where not p.is_deleted and (p.community_id is null or not c.is_deleted)
                  and p.created_at > cast(?1 as timestamptz) - make_interval(secs => ?3)
                  ${if (surface == Surface.PULSE) "and p.is_pulse" else ""}
                  ${if (hideSlop) "and p.ai_slop_at is null" else ""}
                group by p.id order by 2 desc limit 300
                """.trimIndent(),
            )
        }
        return (rows.setParameter(1, asOf).setParameter(2, hot).setParameter(3, surface.window.seconds.toDouble())
            .resultList as List<Array<Any?>>).map { (it[0] as UUID) to (it[1] as Number).toDouble() }
    }

    // ---------------------------------------------------------------- SQL-кусочки

    /** Сборщик запроса: ?1 = me, ?2 = asOf всегда. */
    private inner class Q(me: UUID, asOf: Instant) {
        val params = mutableListOf<Any>(me, asOf)
        fun p(v: Any): String { params += v; return "?${params.size}" }

        @Suppress("UNCHECKED_CAST")
        fun rows(sql: String): List<Array<Any?>> {
            val q = em.createNativeQuery(sql.trimIndent())
            params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
            return q.resultList as List<Array<Any?>>
        }

        @Suppress("UNCHECKED_CAST")
        fun ids(sql: String): List<UUID> {
            val q = em.createNativeQuery(sql.trimIndent(), UUID::class.java)
            params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
            return q.resultList as List<UUID>
        }
    }

    /** Живая запись в окне (до asOf); пульс — только пульс; без слопа по желанию. Нужен join community c. */
    private fun postBase(q: Q, surface: Surface, hideSlop: Boolean, window: Boolean = true): String {
        val parts = mutableListOf("not p.is_deleted", "(p.community_id is null or not c.is_deleted)", "p.created_at < ?2")
        if (window) parts += "p.created_at > cast(?2 as timestamptz) - make_interval(secs => ${q.p(surface.window.seconds.toDouble())})"
        else parts += "p.created_at > cast(?2 as timestamptz) - interval '30 days'"
        if (surface == Surface.PULSE) parts += "p.is_pulse"
        if (hideSlop) parts += "p.ai_slop_at is null"
        return parts.joinToString(" and ")
    }

    private fun videoBase(q: Q, hideSlop: Boolean): String {
        val parts = mutableListOf(
            "v.created_at < ?2",
            "v.created_at > cast(?2 as timestamptz) - make_interval(secs => ${q.p(Surface.VIDEO.window.seconds.toDouble())})",
        )
        if (hideSlop) parts += "(p.id is null or p.ai_slop_at is null)"
        return parts.joinToString(" and ")
    }

    private fun myCommunities() = """(select cm.community_id from community_member cm where cm.user_id = ?1 and cm.left_at is null
                     union select cf.community_id from community_follow cf where cf.user_id = ?1)"""

    /** Подписки для записей: мои сообщества, записи друзей, стены друзей. */
    private fun subsCond(q: Q, fr: List<UUID>): String {
        val parts = mutableListOf("p.community_id in ${myCommunities()}")
        if (fr.isNotEmpty()) {
            val f = q.p(fr)
            parts += "(p.creator_id in ($f) and not p.as_community)"
            parts += "p.wall_user_id in ($f)"
        }
        return parts.joinToString(" or ")
    }

    private fun videoSubsCond(q: Q, fr: List<UUID>): String {
        val parts = mutableListOf("v.community_id in ${myCommunities()}")
        if (fr.isNotEmpty()) parts += "(v.author_id in (${q.p(fr)}) and not v.as_community)"
        return parts.joinToString(" or ")
    }

    /** Не прочитано (до asOf), не лайкнуто и не апвоутнуто мной. */
    private fun notSeenPost() = """not exists (select 1 from post_read r where r.post_id = p.id and r.user_id = ?1 and r.read_at < ?2)
              and not exists (select 1 from post_like ml where ml.post_id = p.id and ml.user_id = ?1)
              and not exists (select 1 from post_upvote mu where mu.post_id = p.id and mu.user_id = ?1)"""

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}
