package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Community
import org.example.domain.Post
import org.example.domain.Room
import org.example.rest.ApiException
import org.example.rest.EventOut
import org.example.rest.PostOut
import org.example.rest.SearchCommunityOut
import org.example.rest.SearchHitOut
import org.example.rest.SearchOut
import org.example.rest.SearchRoomOut
import org.example.rest.SearchUserOut
import org.example.rest.StreamOut
import org.example.rest.TagCountOut
import org.example.rest.TrackOut
import org.example.rest.VideoOut
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Глобальный поиск из шапки: люди, комнаты, сообщества, треки, теги, записи,
 * видео, эфиры, события — одним запросом.
 *
 *  - обычный текст — ищем везде (по началу слова выше, чем по середине);
 *  - #тег — только по тегам: сами теги + всё, что ими помечено;
 *  - @имя — только люди.
 * Без pg_trgm: lower(...) like, поэтому опечатки не прощаются (см. README).
 */
@ApplicationScoped
class SearchService(
    private val em: EntityManager,
    private val friends: FriendService,
    private val presence: PresenceService,
    private val profiles: UserProfileService,
    private val music: MusicService,
    private val tags: TagService,
    private val posts: PostService,
    private val videos: VideoService,
    private val streams: StreamService,
    private val events: EventService,
) {
    companion object {
        val TYPES = setOf("all", "users", "rooms", "communities", "tracks", "tags", "posts", "videos", "streams", "events")
        const val PER_GROUP = 5
        const val MAX_LIMIT = 50
        const val TOP = 8
        const val MAX_Q = 100
        private val WHEN_FMT = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
    }

    private class Sql {
        val params = mutableListOf<Any>()
        fun p(v: Any): String { params += v; return "?${params.size}" }
    }

    @Transactional
    fun search(me: UUID, raw: String?, typeRaw: String?, limit: Int, offset: Int): SearchOut {
        val input = raw?.trim().orEmpty()
        if (input.length > MAX_Q) throw ApiException.badRequest("invalid_query", "запрос длиннее $MAX_Q")
        val type = (typeRaw ?: "all").lowercase()
        if (type !in TYPES) throw ApiException.badRequest("invalid_type", "type: $TYPES")

        val tagMode = input.startsWith("#")
        val userMode = input.startsWith("@")
        val q = input.removePrefix("#").removePrefix("@").trim().lowercase()
        if (q.isEmpty()) return empty(input, type)

        val single = type != "all"
        val n = if (single) limit.coerceIn(1, MAX_LIMIT) else PER_GROUP
        val off = if (single) offset.coerceAtLeast(0) else 0
        fun want(t: String) = (type == "all" || type == t) && (!userMode || t == "users")
        val fr = friends.friendIdsOf(me)
        val contains = "%" + escape(q) + "%"
        val prefix = escape(q) + "%"
        val tagNorm = TagService.normalize(q)

        // ---------------------------------------------------------------- люди и комнаты
        val users = if (want("users") && !tagMode) users(me, fr, contains, prefix, n + 1, off) else emptyList()
        val rooms = if (want("rooms") && !tagMode) rooms(contains, prefix, n + 1, off) else emptyList()

        // ---------------------------------------------------------------- сообщества
        val communityIds = when {
            !want("communities") -> emptyList()
            tagMode -> tagNorm?.let { tags.ownersWith(TagService.Owner.COMMUNITY, it, n + 1 + off).drop(off) } ?: emptyList()
            else -> ids(
                """
                select c.id from community c
                where not c.is_deleted and (lower(c.name) like ?1 or c.slug like ?1 or lower(coalesce(c.description, '')) like ?1)
                order by (lower(c.name) like ?2 or c.slug like ?2) desc,
                         (select count(*) from community_member m where m.community_id = c.id and m.left_at is null) desc
                limit ?3 offset ?4
                """.trimIndent(),
                contains, prefix, n + 1, off,
            )
        }
        val communities = communityCards(communityIds, me)

        // ---------------------------------------------------------------- треки и теги
        val tracks: List<TrackOut> = when {
            !want("tracks") -> emptyList()
            tagMode -> tagNorm?.let { music.renderByIds(tags.ownersWith(TagService.Owner.TRACK, it, n + 1 + off).drop(off), me) } ?: emptyList()
            else -> music.search(me, q, n + 1, off).items
        }
        val tagHits: List<TagCountOut> = if (want("tags")) tags.suggest(q, n + 1 + off).drop(off) else emptyList()

        // ---------------------------------------------------------------- записи, видео, эфиры, события
        val postIds = when {
            !want("posts") -> emptyList()
            tagMode -> tagNorm?.let { tags.ownersWith(TagService.Owner.POST, it, n + 1 + off).drop(off) } ?: emptyList()
            q.length < 2 -> emptyList()
            else -> ids(
                """
                select p.id from post p left join community c on c.id = p.community_id
                where not p.is_deleted and (p.community_id is null or not c.is_deleted)
                  and (lower(coalesce(p.title, '')) like ?1 or lower(p.body) like ?1)
                order by (lower(coalesce(p.title, '')) like ?2) desc, p.created_at desc
                limit ?3 offset ?4
                """.trimIndent(),
                contains, prefix, n + 1, off,
            )
        }
        val postEntities = if (postIds.isEmpty()) emptyMap() else Post.list("id in ?1", postIds).associateBy { it.id }
        val postOut: List<PostOut> = posts.render(postIds.mapNotNull { postEntities[it] }, me)

        val videoOut: List<VideoOut> = when {
            !want("videos") -> emptyList()
            tagMode -> if (tagNorm == null) emptyList() else videos.list(me, "all", null, null, null, "new", null, 0, n + 1 + off, tagNorm).items.drop(off)
            q.length < 2 -> emptyList()
            else -> videos.list(me, "all", null, null, q, "new", null, 0, n + 1 + off).items.drop(off)
        }

        val streamIds = when {
            !want("streams") -> emptyList()
            tagMode -> tagNorm?.let { tags.ownersWith(TagService.Owner.STREAM, it, n + 1 + off).drop(off) } ?: emptyList()
            else -> ids(
                """
                select s.id from stream s
                where lower(s.title) like ?1 or lower(coalesce(s.description, '')) like ?1
                order by (s.status = 'live') desc, coalesce(s.started_at, s.created_at) desc
                limit ?2 offset ?3
                """.trimIndent(),
                contains, n + 1, off,
            )
        }
        val streamMap = streams.byIds(me, streamIds)
        val streamOut: List<StreamOut> = streamIds.mapNotNull { streamMap[it] }

        val eventIds = when {
            !want("events") -> emptyList()
            tagMode -> tagNorm?.let { tags.ownersWith(TagService.Owner.EVENT, it, n + 1 + off).drop(off) } ?: emptyList()
            q.length < 2 -> emptyList()
            else -> ids(
                """
                select e.id from community_event e join community c on c.id = e.community_id and not c.is_deleted
                where e.cancelled_at is null and (lower(e.title) like ?1 or lower(coalesce(e.description, '')) like ?1)
                order by (e.starts_at > now()) desc, abs(extract(epoch from e.starts_at - now()))
                limit ?2 offset ?3
                """.trimIndent(),
                contains, n + 1, off,
            )
        }
        val eventMap = events.byIds(eventIds, me)
        val eventOut: List<EventOut> = eventIds.mapNotNull { eventMap[it] }
        val eventComm = eventCommunities(eventIds)

        val hasMore = single && when (type) {
            "users" -> users.size > n
            "rooms" -> rooms.size > n
            "communities" -> communities.size > n
            "tracks" -> tracks.size > n
            "tags" -> tagHits.size > n
            "posts" -> postOut.size > n
            "videos" -> videoOut.size > n
            "streams" -> streamOut.size > n
            "events" -> eventOut.size > n
            else -> false
        }

        val result = SearchOut(
            q = input, type = type, top = emptyList(),
            users = users.take(n), rooms = rooms.take(n), communities = communities.take(n),
            tracks = tracks.take(n), tags = tagHits.take(n), posts = postOut.take(n),
            videos = videoOut.take(n), streams = streamOut.take(n), events = eventOut.take(n),
            hasMore = hasMore,
        )
        return result.copy(top = top(result, q, tagMode, eventComm))
    }

    // ================================================================ группы

    private fun users(me: UUID, fr: List<UUID>, contains: String, prefix: String, limit: Int, offset: Int): List<SearchUserOut> {
        val q = Sql()
        val c = q.p(contains)
        val pr = q.p(prefix)
        val friendOrder = if (fr.isEmpty()) "" else "(u.id in (${q.p(fr)})) desc,"
        val sql = """
            select u.id from users u left join room r on r.owner_id = u.id
            where not u.is_deleted and u.id <> ${q.p(me)} and (u.username like $c or lower(coalesce(r.title, '')) like $c)
            order by (u.username like $pr) desc, $friendOrder length(u.username), u.username
            limit ${q.p(limit)} offset ${q.p(offset)}
        """.trimIndent()
        val ids = idsQ(sql, q.params)
        if (ids.isEmpty()) return emptyList()
        val shorts = profiles.shorts(ids)
        val statuses = presence.publicStatuses(ids)
        val roomsBy = Room.list("ownerId in ?1", ids).associateBy { it.ownerId }
        val friendSet = fr.toSet()
        return ids.mapNotNull { id ->
            shorts[id]?.let { SearchUserOut(it, statuses[id] ?: "offline", id in friendSet, roomsBy[id]?.title, roomsBy[id]?.mood) }
        }
    }

    private fun rooms(contains: String, prefix: String, limit: Int, offset: Int): List<SearchRoomOut> {
        val ids = ids(
            """
            select r.owner_id from room r join users u on u.id = r.owner_id and not u.is_deleted
            where lower(r.title) like ?1 or lower(coalesce(r.mood, '')) like ?1 or lower(coalesce(r.about, '')) like ?1
            order by (lower(r.title) like ?2) desc, r.updated_at desc
            limit ?3 offset ?4
            """.trimIndent(),
            contains, prefix, limit, offset,
        )
        if (ids.isEmpty()) return emptyList()
        val shorts = profiles.shorts(ids)
        val roomsBy = Room.list("ownerId in ?1", ids).associateBy { it.ownerId }
        return ids.mapNotNull { id ->
            val r = roomsBy[id] ?: return@mapNotNull null
            shorts[id]?.let { SearchRoomOut(it, r.title, r.mood, r.theme) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun communityCards(ids: List<UUID>, me: UUID): List<SearchCommunityOut> {
        if (ids.isEmpty()) return emptyList()
        val byId = Community.list("id in ?1 and isDeleted = false", ids).associateBy { it.id }
        val members = (em.createNativeQuery(
            "select community_id, count(*) from community_member where left_at is null and community_id in (?1) group by community_id",
        ).setParameter(1, ids).resultList as List<Array<Any?>>).associate { it[0] as UUID to (it[1] as Number).toLong() }
        val mine = (em.createNativeQuery(
            "select community_id from community_member where left_at is null and user_id = ?1 and community_id in (?2)",
            UUID::class.java,
        ).setParameter(1, me).setParameter(2, ids).resultList as List<UUID>).toSet()
        return ids.mapNotNull { id ->
            byId[id]?.let { SearchCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar, it.description, members[id] ?: 0, id in mine) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun eventCommunities(eventIds: List<UUID>): Map<UUID, Community> {
        if (eventIds.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery("select id, community_id from community_event where id in (?1)")
            .setParameter(1, eventIds).resultList as List<Array<Any?>>
        val comms = Community.list("id in ?1", rows.map { it[1] as UUID }.distinct()).associateBy { it.id }
        return rows.mapNotNull { r -> comms[r[1] as UUID]?.let { r[0] as UUID to it } }.toMap()
    }

    // ================================================================ смешанный топ

    private fun top(r: SearchOut, q: String, tagMode: Boolean, eventComm: Map<UUID, Community>): List<SearchHitOut> {
        val scored = mutableListOf<Pair<Int, SearchHitOut>>()
        fun match(text: String?): Int {
            val t = text?.lowercase() ?: return 0
            return when {
                t == q -> 100
                t.startsWith(q) -> 50
                t.split(' ', '_', '-').any { it.startsWith(q) } -> 25
                else -> 0
            }
        }
        fun hue(id: Any) = Math.floorMod(id.hashCode(), 360)

        r.users.forEach { u ->
            scored += (30 + match(u.user.username) + (if (u.friend) 20 else 0) + (if (u.status != "offline") 5 else 0)) to SearchHitOut(
                "user", u.user.id.toString(), u.user.username,
                u.roomTitle?.let { "комната: $it" } ?: u.mood, u.user.avatar, hue(u.user.id), "/rooms/${u.user.username}",
                if (u.friend) "друг" else if (u.status == "online") "в сети" else null,
            )
        }
        r.communities.forEach { c ->
            scored += (25 + match(c.name) + match(c.slug) / 2 + (if (c.joined) 10 else 0)) to SearchHitOut(
                "community", c.id.toString(), c.name, "${c.members} участн.", c.avatar, c.hue, "/c/${c.slug}",
                if (c.joined) "ты внутри" else null,
            )
        }
        r.tags.forEach { t ->
            scored += ((if (tagMode) 70 else 20) + match(t.tag)) to SearchHitOut(
                "tag", t.tag, "#${t.tag}", "${t.count} упомин.", null, hue(t.tag), "/tags/${t.tag}", null,
            )
        }
        r.rooms.forEach { rm ->
            scored += (15 + match(rm.title)) to SearchHitOut(
                "room", rm.owner.id.toString(), rm.title, "комната ${rm.owner.username}" + (rm.mood?.let { " · $it" } ?: ""),
                rm.owner.avatar, hue(rm.owner.id), "/rooms/${rm.owner.username}", null,
            )
        }
        r.tracks.forEach { t ->
            scored += (15 + maxOf(match(t.title), match(t.artist))) to SearchHitOut(
                "track", t.id.toString(), "${t.artist} — ${t.title}", t.album ?: "%d:%02d".format(t.durationSec / 60, t.durationSec % 60),
                t.coverUrl, t.hue, "/music?track=${t.id}", if (t.inLibrary) "в моей музыке" else null,
            )
        }
        r.streams.forEach { s ->
            val live = s.status == "live"
            val who = s.owner.community?.name ?: s.owner.user?.username
            scored += (15 + match(s.title) + (if (live) 40 else 0)) to SearchHitOut(
                "stream", s.id.toString(), s.title, who, s.previewUrl, s.owner.community?.hue ?: hue(s.id),
                "/video/live/${s.id}", if (live) "в эфире" else null,
            )
        }
        r.videos.forEach { v ->
            scored += (10 + match(v.title)) to SearchHitOut(
                "video", v.id.toString(), v.title ?: "видео", v.community?.name ?: v.author?.username, v.posterUrl,
                v.community?.hue ?: hue(v.id), "/video?v=${v.id}", null,
            )
        }
        r.events.forEach { e ->
            val c = eventComm[e.id]
            scored += (10 + match(e.title)) to SearchHitOut(
                "event", e.id.toString(), e.title,
                WHEN_FMT.format(e.startsAt.atZone(ZoneId.of("UTC"))) + (c?.let { " · ${it.name}" } ?: ""),
                null, c?.hue ?: hue(e.id), "/c/${c?.slug}?tab=events&event=${e.id}", null,
            )
        }
        r.posts.forEach { p ->
            val title = p.title ?: p.body.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "запись"
            val where = when (p.source) {
                "wall" -> "на стене ${p.wallOwner?.username ?: p.author?.username}"
                "pulse" -> "в пульсе"
                else -> p.communityInfo?.name
            }
            val link = when (p.source) {
                "wall" -> "/rooms/${p.wallOwner?.username ?: p.author?.username}?post=${p.id}"
                "pulse" -> "/pulse?post=${p.id}"
                else -> "/c/${p.community}?post=${p.id}"
            }
            scored += (5 + match(p.title)) to SearchHitOut(
                "post", p.id.toString(), title, where, p.attachments.firstOrNull()?.url, p.hue, link, null,
            )
        }
        return scored.sortedByDescending { it.first }.take(TOP).map { it.second }
    }

    // ================================================================ utils

    private fun empty(q: String, type: String) = SearchOut(
        q, type, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), false,
    )

    private fun escape(q: String) = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun ids(sql: String, vararg params: Any): List<UUID> = idsQ(sql, params.toList())

    @Suppress("UNCHECKED_CAST")
    private fun idsQ(sql: String, params: List<Any>): List<UUID> {
        val q = em.createNativeQuery(sql, UUID::class.java)
        params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
        return q.resultList as List<UUID>
    }
}
