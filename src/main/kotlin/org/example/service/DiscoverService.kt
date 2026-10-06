package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Community
import org.example.domain.Post
import org.example.rest.ActivityItemOut
import org.example.rest.ActivityWidgetOut
import org.example.rest.ApiException
import org.example.rest.OnlineUserOut
import org.example.rest.OnlineWidgetOut
import org.example.rest.PostCommunityOut
import org.example.rest.StreamOut
import org.example.rest.TagPageOut
import org.example.rest.TickerItemOut
import org.example.rest.TickerOut
import org.example.rest.TrendingTagOut
import org.example.rest.TrendingWidgetOut
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Боковые виджеты (онлайн, «что делают люди», «о чём говорят») — у каждого
 * два вида: личный (моё окружение) и глобальный (вся сеть). Плюс страница тега.
 */
@ApplicationScoped
class DiscoverService(
    private val em: EntityManager,
    private val friends: FriendService,
    private val presence: PresenceService,
    private val profiles: UserProfileService,
    private val music: MusicService,
    private val lenta: LentaService,
    private val tags: TagService,
    private val posts: PostService,
    private val videos: VideoService,
    private val streams: StreamService,
    private val events: EventService,
) {
    companion object {
        private val STATUS_ORDER = mapOf("online" to 0, "away" to 1, "dnd" to 2, "offline" to 3)
        private val WINDOWS = listOf("1 hour" to "1h", "24 hours" to "24h", "7 days" to "7d")
        const val MAX_ITEMS = 100

        /** Сообщества, где я участник или читаю. ?1 = me. */
        private const val MY = """(select cm.community_id from community_member cm where cm.user_id = ?1 and cm.left_at is null
            union select cf.community_id from community_follow cf where cf.user_id = ?1)"""
    }

    // ================================================================ онлайн

    @Transactional
    fun online(me: UUID, scope: String, limit: Int): OnlineWidgetOut {
        val size = limit.coerceIn(1, MAX_ITEMS)
        val fr = friends.friendIdsOf(me)
        return when (scope.lowercase()) {
            "friends", "mine" -> {
                val snap = presence.friendsSnapshot(me)
                val playing = music.friendsListening(me).associateBy { it.userId }
                val users = profiles.shorts(fr)
                val items = snap.mapNotNull { p ->
                    val u = users[p.userId] ?: return@mapNotNull null
                    val np = playing[p.userId]?.takeIf { p.status != "offline" }
                    OnlineUserOut(u, p.status, p.doing, np?.track, np?.endsAt, friend = true)
                }.sortedWith(compareBy<OnlineUserOut> { STATUS_ORDER[it.status] ?: 9 }.thenBy { it.user.username })
                OnlineWidgetOut(
                    scope = "friends",
                    online = items.count { it.status != "offline" }.toLong(),
                    total = items.size.toLong(),
                    byStatus = items.groupingBy { it.status }.eachCount().mapValues { it.value.toLong() },
                    items = items.take(size),
                )
            }
            "global" -> {
                @Suppress("UNCHECKED_CAST")
                val byStatus = globalOnline.get("byStatus") { (em.createNativeQuery(
                    """
                    select up.status, count(*) from user_presence up join users u on u.id = up.user_id and not u.is_deleted
                    where up.status in ('online', 'away', 'dnd') and up.seen_at > now() - interval '3 minutes' group by up.status
                    """.trimIndent(),
                ).resultList as List<Array<Any?>>).associate { it[0] as String to (it[1] as Number).toLong() } }
                // сначала друзья, потом остальные — свежие смены статуса сверху
                @Suppress("UNCHECKED_CAST")
                val ids = em.createNativeQuery(
                    """
                    select up.user_id from user_presence up join users u on u.id = up.user_id and not u.is_deleted
                    where up.status in ('online', 'away', 'dnd') and up.seen_at > now() - interval '3 minutes' and up.user_id <> ?1
                    order by (${if (fr.isEmpty()) "up.user_id is null" else "up.user_id in (?3)"}) desc, up.updated_at desc limit ?2
                    """.trimIndent(),
                    UUID::class.java,
                ).setParameter(1, me).setParameter(2, size)
                    .also { if (fr.isNotEmpty()) it.setParameter(3, fr) }
                    .resultList as List<UUID>
                val statuses = presence.publicStatuses(ids)
                val users = profiles.shorts(ids)
                val friendSet = fr.toSet()
                val friendDoing = if (friendSet.isEmpty()) emptyMap() else
                    presence.friendsSnapshot(me).associateBy { it.userId }
                val playing = music.friendsListening(me).associateBy { it.userId }
                val items = ids.mapNotNull { id ->
                    val u = users[id] ?: return@mapNotNull null
                    val st = statuses[id] ?: "offline"
                    if (st == "offline") return@mapNotNull null
                    val isFriend = id in friendSet
                    val np = if (isFriend) playing[id] else null
                    OnlineUserOut(u, st, if (isFriend) friendDoing[id]?.doing else null, np?.track, np?.endsAt, isFriend)
                }
                val total = byStatus.values.sum()
                OnlineWidgetOut("global", total, total, byStatus, items)
            }
            else -> throw ApiException.badRequest("invalid_scope", "scope: friends или global")
        }
    }

    // ================================================================ что делают люди

    /** Компактная лента: mine — моё окружение (как /api/lenta), global — вся сеть. */
    @Transactional
    fun activity(me: UUID, scope: String, limit: Int): ActivityWidgetOut {
        val global = when (scope.lowercase()) {
            "mine", "friends" -> false
            "global" -> true
            else -> throw ApiException.badRequest("invalid_scope", "scope: mine или global")
        }
        val page = lenta.lenta(me, null, null, null, limit.coerceIn(1, 50), global = global, withCounts = false)
        val items = page.items.map {
            ActivityItemOut(
                id = it.id, kind = it.kind, who = it.actor,
                // от имени сообщества — «who» нет, фраза начинается с названия
                what = if (it.actor == null && it.community != null) "«${it.community.name}» ${it.summary}" else it.summary,
                at = it.at, hue = it.hue, community = it.community, link = it.link, target = it.target,
            )
        }
        return ActivityWidgetOut(if (global) "global" else "mine", items)
    }

    // ================================================================ о чём говорят

    /**
     * Горячие теги. Очки за окно: новая вещь с тегом 3, комментарий 2, апвоут 2,
     * репост 3, лайк 1, просмотр видео 1, идущий эфир 5. heat — доля от лидера.
     * Если в последний час тихо, окно расширяется до суток и недели.
     */
    /** Глобальное одинаково для всех — считаем раз в 30 с на ноду, а не на каждого смотрящего. */
    private val globalTrending = TtlCache<Int, TrendingWidgetOut>(30_000)
    private val globalTicker = TtlCache<Int, List<TickerItemOut>>(30_000)
    private val globalOnline = TtlCache<String, Map<String, Long>>(15_000)

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun trending(me: UUID, scope: String, limit: Int): TrendingWidgetOut {
        val mine = when (scope.lowercase()) {
            "mine", "friends" -> true
            "global" -> false
            else -> throw ApiException.badRequest("invalid_scope", "scope: global или mine")
        }
        val size = limit.coerceIn(1, 30)
        if (!mine) return globalTrending.get(size) { trendingUncached(me, false, size) }
        return trendingUncached(me, true, size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun trendingUncached(me: UUID, mine: Boolean, size: Int): TrendingWidgetOut {
        val fr = friends.friendIdsOf(me)
        var rows: List<Array<Any?>> = emptyList()
        var used = WINDOWS.first().second
        for ((interval, label) in WINDOWS) {
            rows = trendingRows(me, fr, mine, interval, size)
            used = label
            if (rows.size >= minOf(size, 3)) break
        }
        if (rows.isEmpty()) return TrendingWidgetOut(if (mine) "mine" else "global", used, emptyList())

        val top = (rows.first()[1] as Number).toDouble().coerceAtLeast(1.0)
        val tagsList = rows.map { it[0] as String }
        val where = whereOf(tagsList)
        return TrendingWidgetOut(
            scope = if (mine) "mine" else "global",
            window = used,
            items = rows.map { r ->
                val tag = r[0] as String
                val score = (r[1] as Number).toLong()
                val w = where[tag]
                TrendingTagOut(
                    tag = tag,
                    where = w?.first ?: "по всей сети",
                    whereSlug = w?.second,
                    heat = ((score / top) * 100).roundToLong() / 100.0,
                    score = score,
                    items = (r[2] as Number).toLong(),
                    link = "/tags/$tag",
                )
            },
        )
    }

    private fun trendingRows(me: UUID, fr: List<UUID>, mine: Boolean, interval: String, limit: Int): List<Array<Any?>> {
        val w = "now() - interval '$interval'"
        // кто «мой»: только для scope=mine; ?1 = me, ?2 = друзья (если есть)
        fun f(col: String) = if (fr.isEmpty()) "false" else "$col in (?2)"
        val scopeFilter = if (!mine) "" else """
            and (
              (t.owner_type = 'post' and exists (select 1 from post p where p.id = t.owner_id
                   and (p.community_id in $MY or p.creator_id = ?1 or ${f("p.creator_id")} or ${f("p.wall_user_id")})))
              or (t.owner_type = 'video' and exists (select 1 from media m where m.id = t.owner_id
                   and (m.owner_id = ?1 or ${f("m.owner_id")})))
              or (t.owner_type = 'stream' and exists (select 1 from stream s join stream_channel ch on ch.id = s.channel_id
                   where s.id = t.owner_id and (ch.community_id in $MY or ch.user_id = ?1 or ${f("ch.user_id")})))
              or (t.owner_type = 'track' and exists (select 1 from track tr where tr.id = t.owner_id
                   and (tr.uploader_id = ?1 or ${f("tr.uploader_id")})))
              or (t.owner_type = 'community' and t.owner_id in $MY)
              or (t.owner_type = 'event' and exists (select 1 from community_event e where e.id = t.owner_id and e.community_id in $MY))
            )"""
        val sql = """
            select t.tag, sum(t.w) as score, count(distinct t.owner_type || ':' || t.owner_id) as items
            from (
              select tl.tag, 3 as w, tl.owner_type, tl.owner_id from tag_link tl where tl.created_at > $w
              union all
              select tl.tag, 2, tl.owner_type, tl.owner_id from tag_link tl
                join post_comment c on tl.owner_type = 'post' and c.post_id = tl.owner_id
                where c.created_at > $w and c.deleted_at is null
              union all
              select tl.tag, 2, tl.owner_type, tl.owner_id from tag_link tl
                join post_upvote u on tl.owner_type = 'post' and u.post_id = tl.owner_id where u.created_at > $w
              union all
              select tl.tag, 3, tl.owner_type, tl.owner_id from tag_link tl
                join post_share s on tl.owner_type = 'post' and s.post_id = tl.owner_id where s.created_at > $w
              union all
              select tl.tag, 1, tl.owner_type, tl.owner_id from tag_link tl
                join post_like l on tl.owner_type = 'post' and l.post_id = tl.owner_id where l.created_at > $w
              union all
              select tl.tag, 1, tl.owner_type, tl.owner_id from tag_link tl
                join video_view vv on tl.owner_type = 'video' and vv.media_id = tl.owner_id where vv.viewed_at > $w
              union all
              select tl.tag, 5, tl.owner_type, tl.owner_id from tag_link tl
                join stream st on tl.owner_type = 'stream' and st.id = tl.owner_id where st.status = 'live'
            ) t
            where true $scopeFilter
            group by t.tag
            order by score desc, t.tag
            limit ${limit}
        """.trimIndent()
        val q = em.createNativeQuery(sql)
        if (mine) {
            q.setParameter(1, me)
            if (fr.isNotEmpty()) q.setParameter(2, fr)
        }
        @Suppress("UNCHECKED_CAST")
        return q.resultList as List<Array<Any?>>
    }

    /**
     * Где тег живёт: сообщество, где больше всего записей с ним; иначе — по виду
     * вещей («в пульсе», «в эфирах», «в видео», «в музыке»).
     */
    @Suppress("UNCHECKED_CAST")
    private fun whereOf(tagsList: List<String>): Map<String, Pair<String, String?>> {
        if (tagsList.isEmpty()) return emptyMap()
        val comm = (em.createNativeQuery(
            """
            select distinct on (tl.tag) tl.tag, c.name, c.slug, count(*) as n
            from tag_link tl join post p on tl.owner_type = 'post' and p.id = tl.owner_id and not p.is_deleted
            join community c on c.id = p.community_id and not c.is_deleted
            where tl.tag in (?1)
            group by tl.tag, c.name, c.slug
            order by tl.tag, n desc
            """.trimIndent(),
        ).setParameter(1, tagsList).resultList as List<Array<Any?>>)
            .associate { it[0] as String to ("в «${it[1]}»" to it[2] as String?) }
        val kinds = (em.createNativeQuery(
            """
            select distinct on (tl.tag) tl.tag,
                   case when tl.owner_type = 'post' then (select case when p.wall_user_id is not null then 'wall' when p.is_pulse then 'pulse' else 'community' end from post p where p.id = tl.owner_id)
                        else tl.owner_type end as k,
                   count(*) as n
            from tag_link tl where tl.tag in (?1)
            group by 1, 2 order by tl.tag, n desc
            """.trimIndent(),
        ).setParameter(1, tagsList).resultList as List<Array<Any?>>)
            .associate { r ->
                r[0] as String to when (r[1] as String?) {
                    "pulse" -> "в пульсе"
                    "wall" -> "на стенах"
                    "stream" -> "в эфирах"
                    "video" -> "в видео"
                    "track" -> "в музыке"
                    "event" -> "в событиях"
                    "community" -> "в сообществах"
                    else -> "по всей сети"
                }
            }
        return tagsList.associateWith { t -> comm[t] ?: ((kinds[t] ?: "по всей сети") to null) }
    }

    // ================================================================ бегущая строка

    /**
     * Бегущая строка: горячее по всей сети и из моего окружения примерно 50/50,
     * через одно (global, mine, global, …). Повторы (тот же тег/эфир) выкидываются.
     */
    @Transactional
    fun ticker(me: UUID, limit: Int): TickerOut {
        val size = limit.coerceIn(4, 40)
        val half = (size + 1) / 2
        val global = tickerGlobal(me, half + 2)
        val mine = tickerMine(me, half + 2)
        val out = mutableListOf<TickerItemOut>()
        val seen = HashSet<String>()
        val g = global.iterator()
        val m = mine.iterator()
        while (out.size < size && (g.hasNext() || m.hasNext())) {
            for (it in listOf(g, m)) {
                while (it.hasNext()) {
                    val x = it.next()
                    if (seen.add(x.id)) { out += x; break }
                }
                if (out.size >= size) break
            }
        }
        return TickerOut(out, Instant.now(), refreshInSec = 60)
    }

    private fun tickerGlobal(me: UUID, n: Int): List<TickerItemOut> = globalTicker.get(n) { tickerGlobalUncached(me, n) }

    private fun tickerGlobalUncached(me: UUID, n: Int): List<TickerItemOut> {
        val items = mutableListOf<TickerItemOut>()
        trending(me, "global", 4).items.forEach {
            items += TickerItemOut("tag:${it.tag}", "tag", "global", "#${it.tag} — обсуждают ${it.where}", it.link, null, it.heat)
        }
        streams.list(me, "live", null, null, following = false, before = null, limit = 3).items
            .sortedByDescending { it.viewers }.forEach { s ->
                val who = s.owner.community?.name ?: s.owner.user?.username ?: "кто-то"
                items += TickerItemOut(
                    "stream:${s.id}", "stream", "global", "в эфире: «${s.title}» · $who · смотрят ${s.viewers}",
                    "/video/live/${s.id}", s.owner.community?.hue, 0.9,
                )
            }
        hotPosts(me, null, 3).forEach { items += it.copy(scope = "global") }
        val online = online(me, "global", 1).online
        if (online > 0) items += TickerItemOut("online:global", "online", "global", "в сети ${people(online)}", "/rooms", null, 0.3)
        return interleaveByKind(items).take(n)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tickerMine(me: UUID, n: Int): List<TickerItemOut> {
        val items = mutableListOf<TickerItemOut>()
        trending(me, "mine", 4).items.forEach {
            items += TickerItemOut("tag:${it.tag}", "tag", "mine", "#${it.tag} — у твоих ${it.where}", it.link, null, it.heat)
        }
        streams.list(me, "live", null, null, following = true, before = null, limit = 3).items.forEach { s ->
            val who = s.owner.community?.name ?: s.owner.user?.username ?: "кто-то"
            items += TickerItemOut(
                "stream:${s.id}", "stream", "mine", "$who в эфире: «${s.title}»", "/video/live/${s.id}", s.owner.community?.hue, 1.0,
            )
        }
        music.friendsListening(me).take(3).forEach { np ->
            val t = np.track ?: return@forEach
            val u = profiles.shorts(listOf(np.userId))[np.userId] ?: return@forEach
            items += TickerItemOut(
                "listening:${u.id}", "listening", "mine", "${u.username} слушает ${t.artist} — ${t.title}", "/music?track=${t.id}", t.hue, 0.5,
            )
        }
        // события моих сообществ в ближайшие сутки
        val soon = em.createNativeQuery(
            """
            select e.id, e.title, e.starts_at, c.name, c.slug, c.hue from community_event e
            join community c on c.id = e.community_id and not c.is_deleted
            where e.cancelled_at is null and e.starts_at between now() and now() + interval '24 hours'
              and e.community_id in $MY
            order by e.starts_at limit 2
            """.trimIndent(),
        ).setParameter(1, me).resultList as List<Array<Any?>>
        soon.forEach { r ->
            val starts = when (val v = r[2]) {
                is Instant -> v
                is java.time.OffsetDateTime -> v.toInstant()
                is java.sql.Timestamp -> v.toInstant()
                else -> Instant.now()
            }
            val mins = Duration.between(Instant.now(), starts).toMinutes().coerceAtLeast(0)
            val inText = if (mins < 60) "через $mins мин" else "через ${mins / 60} ч"
            items += TickerItemOut(
                "event:${r[0]}", "event", "mine", "$inText: «${r[1]}» в «${r[3]}»",
                "/c/${r[4]}?tab=events&event=${r[0]}", (r[5] as Number).toInt(), 0.7,
            )
        }
        hotPosts(me, friends.friendIdsOf(me), 3).forEach { items += it.copy(scope = "mine") }
        return interleaveByKind(items).take(n)
    }

    /**
     * Самые живые записи за час (комментарий 2, апвоут 2, репост 3, лайк 1).
     * fr == null — по всей сети, иначе — мои сообщества, друзья и я.
     */
    @Suppress("UNCHECKED_CAST")
    private fun hotPosts(me: UUID, fr: List<UUID>?, n: Int): List<TickerItemOut> {
        val mineFilter = when {
            fr == null -> ""
            fr.isEmpty() -> " and (p.community_id in $MY or p.creator_id = ?1)"
            else -> " and (p.community_id in $MY or p.creator_id = ?1 or p.creator_id in (?2) or p.wall_user_id in (?2))"
        }
        val sql = """
            select x.id, x.score, x.comments from (
              select p.id,
                     2 * (select count(*) from post_comment c where c.post_id = p.id and c.deleted_at is null and c.created_at > now() - interval '1 hour')
                   + 2 * (select count(*) from post_upvote u where u.post_id = p.id and u.created_at > now() - interval '1 hour')
                   + 3 * (select count(*) from post_share s where s.post_id = p.id and s.created_at > now() - interval '1 hour')
                   +     (select count(*) from post_like l where l.post_id = p.id and l.created_at > now() - interval '1 hour') as score,
                     (select count(*) from post_comment c where c.post_id = p.id and c.deleted_at is null and c.created_at > now() - interval '1 hour') as comments
              from post p left join community cm on cm.id = p.community_id
              where not p.is_deleted and (p.community_id is null or not cm.is_deleted)
                and p.created_at > now() - interval '3 days' $mineFilter
            ) x where x.score > 0 order by x.score desc limit $n
        """.trimIndent()
        val q = em.createNativeQuery(sql)
        if (fr != null) {
            q.setParameter(1, me)
            if (fr.isNotEmpty()) q.setParameter(2, fr)
        }
        val rows = q.resultList as List<Array<Any?>>
        if (rows.isEmpty()) return emptyList()
        val top = (rows.first()[1] as Number).toDouble().coerceAtLeast(1.0)
        val byId = Post.list("id in ?1", rows.map { it[0] as UUID }).associateBy { it.id }
        val comms = byId.values.mapNotNull { it.communityId }.distinct().let { ids ->
            if (ids.isEmpty()) emptyMap() else Community.list("id in ?1", ids).associateBy { it.id }
        }
        return rows.mapNotNull { r ->
            val p = byId[r[0] as UUID] ?: return@mapNotNull null
            val c = p.communityId?.let { comms[it] }
            val snippet = (p.title ?: p.body.lineSequence().firstOrNull { it.isNotBlank() } ?: "запись").take(60)
            val comments = (r[2] as Number).toLong()
            val buzz = if (comments > 0) "$comments коммент. за час" else "разгоняется"
            val where = c?.let { " в «${it.name}»" } ?: if (p.isPulse) " в пульсе" else ""
            val link = when {
                p.wallUserId != null -> "/rooms/${profiles.shorts(listOf(p.wallUserId!!))[p.wallUserId!!]?.username}?post=${p.id}"
                p.isPulse -> "/pulse?post=${p.id}"
                else -> "/c/${c?.slug}?post=${p.id}"
            }
            TickerItemOut(
                "post:${p.id}", "post", "global", "«$snippet»$where — $buzz", link, c?.hue,
                ((r[1] as Number).toDouble() / top * 100).roundToLong() / 100.0,
            )
        }
    }

    /** Чтобы подряд не шли три тега — перемешиваем по видам, сохраняя порядок внутри вида. */
    private fun interleaveByKind(items: List<TickerItemOut>): List<TickerItemOut> {
        val groups = items.groupBy { it.kind }.values.map { it.toMutableList() }
        val out = mutableListOf<TickerItemOut>()
        while (groups.any { it.isNotEmpty() }) groups.forEach { g -> if (g.isNotEmpty()) out += g.removeAt(0) }
        return out
    }

    private fun people(n: Long): String {
        val m10 = n % 10
        val m100 = n % 100
        val word = if (m10 in 2..4 && m100 !in 12..14) "человека" else "человек"
        return "$n $word"
    }

    // ================================================================ страница тега

    @Transactional
    fun tagPage(me: UUID, raw: String): TagPageOut {
        val tag = TagService.normalize(raw) ?: throw ApiException.badRequest("invalid_tag", "тег: 2–40 букв/цифр/_")
        val n = 20
        val postIds = tags.ownersWith(TagService.Owner.POST, tag, n)
        val postEntities = if (postIds.isEmpty()) emptyMap() else
            Post.list("id in ?1 and isDeleted = false", postIds).associateBy { it.id }
        val postOut = posts.render(postIds.mapNotNull { postEntities[it] }, me)
        val videoOut = videos.byIds(me, tags.ownersWith(TagService.Owner.VIDEO, tag, n)).values
            .sortedByDescending { it.createdAt }
        val streamOut = streams.byIds(me, tags.ownersWith(TagService.Owner.STREAM, tag, n)).values
            .sortedWith(compareBy<StreamOut> { if (it.status == "live") 0 else 1 }.thenByDescending { it.startedAt ?: it.createdAt })
        val trackIds = tags.ownersWith(TagService.Owner.TRACK, tag, n)
        val trackOut = music.renderByIds(trackIds, me)
        val commIds = tags.ownersWith(TagService.Owner.COMMUNITY, tag, n)
        val comms = if (commIds.isEmpty()) emptyList() else Community.list("id in ?1 and isDeleted = false", commIds)
            .map { PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) }
        val eventOut = events.byIds(tags.ownersWith(TagService.Owner.EVENT, tag, n), me).values
            .filter { !it.cancelled }.sortedBy { it.startsAt }
        return TagPageOut(tag, tags.counts(tag), postOut, videoOut.toList(), streamOut, trackOut, comms, eventOut)
    }
}
