package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.example.domain.Community
import org.example.domain.Post
import org.example.rest.ApiException
import org.example.rest.CommentOut
import org.example.rest.DiscussionOut
import org.example.rest.EventOut
import org.example.rest.GuestbookEntryOut
import org.example.rest.LentaItemOut
import org.example.rest.LentaListeningOut
import org.example.rest.LentaPageOut
import org.example.rest.LentaSourceOut
import org.example.rest.LentaTargetOut
import org.example.rest.PlaylistOut
import org.example.rest.PostCommunityOut
import org.example.rest.PostOut
import org.example.rest.StreamOut
import org.example.rest.TrackOut
import org.example.rest.UserShortOut
import org.example.rest.VideoOut

/**
 * Общая лента: всё, что происходит вокруг человека, одним хронологическим
 * списком. Ничего не хранится отдельно — строки собираются одним UNION из
 * уже существующих таблиц, потом каждая «оживает» полной карточкой своей
 * сущности, поэтому кнопки (апвоут, лайк, ответ, поделиться, «+ себе»,
 * «записаться», «зайти в обсуждение», «смотреть эфир») работают как везде.
 *
 * Что попадает (я = смотрящий, друзья = подтверждённые, мои сообщества =
 * участник или «читаю без вступления»):
 *  - communities: записи моих сообществ (и пульсары в них), события, новые обсуждения;
 *  - video:       записи с видео (откуда угодно из видимого), ролики друзей без записи, эфиры;
 *  - rooms:       перестановки в комнатах друзей, записи в гостевых (моей и друзей);
 *  - music:       что друзья слушают сейчас, их новые треки и плейлисты, музыка моих сообществ;
 *  - friends:     записи друзей в пульсе и на стенах, вступления в сообщества, новые друзья;
 *  - replies:     ответы на мои записи и комментарии.
 * Порядок строго по времени, без весов.
 */
@ApplicationScoped
class LentaService(
    private val em: EntityManager,
    private val friends: FriendService,
    private val profiles: UserProfileService,
    private val posts: PostService,
    private val videos: VideoService,
    private val streams: StreamService,
    private val music: MusicService,
    private val events: EventService,
    private val discussions: DiscussionService,
    private val rooms: RoomService,
) {
    companion object {
        const val MAX_PAGE = 50
        /** Ключи источников и подписи тумблеров (как на странице /lenta). */
        val SOURCES = linkedMapOf(
            "communities" to "сообщества",
            "video" to "видео",
            "rooms" to "комнаты",
            "music" to "музыка",
            "friends" to "друзья",
            "replies" to "ответы",
        )
        private val WHEN_FMT = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
    }

    /** Строка UNION до «оживления». */
    private data class Row(
        val kind: String, val source: String, val ref: UUID, val actor: UUID?,
        val ctx: UUID?, val at: Instant, val extra: String?,
    )

    /** Собиратель SQL с позиционными параметрами по порядку появления. */
    private class Sql {
        val params = mutableListOf<Any>()
        fun p(v: Any): String { params += v; return "?${params.size}" }
    }

    // ================================================================ API

    /**
     * @param sourcesRaw "communities,video" — только эти источники (по умолчанию все).
     * @param before     курсор: строки старше этого момента.
     * @param since      нижняя граница («только новое с момента, как я заходил»); по умолчанию нет.
     */
    @Transactional
    fun lenta(me: UUID, sourcesRaw: String?, before: Instant?, since: Instant?, limit: Int, global: Boolean = false, withCounts: Boolean = true): LentaPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val only = sourcesRaw?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
        only?.firstOrNull { it !in SOURCES }?.let {
            throw ApiException.badRequest("invalid_source", "источники: ${SOURCES.keys.joinToString()}")
        }
        val fr = friends.friendIdsOf(me)

        val q = Sql()
        val sql = union(q, me, fr, before ?: Instant.now().plusSeconds(60), since, only, perBranch = size + 1, global = global) +
                " order by at desc, ref limit ${q.p(size + 1)}"
        val rows = rows(sql, q.params)
        val page = rows.take(size)

        val today = if (withCounts) today(me, fr, global) else emptyMap()
        return LentaPageOut(
            items = hydrate(page, me),
            hasMore = rows.size > size,
            nextBefore = if (rows.size > size) page.lastOrNull()?.at else null,
            sources = SOURCES.map { (k, label) -> LentaSourceOut(k, label, today[k] ?: 0) },
            today = today.values.sum(),
        )
    }

    // ================================================================ сборка UNION

    /**
     * Ветки UNION. Каждая: kind, source, ref, actor, ctx, at, extra.
     * Условия по времени и источнику — внутри каждой ветки (с лимитом), так
     * Postgres не тянет всю историю, чтобы взять верхние N.
     */
    private fun union(
        q: Sql, me: UUID, fr: List<UUID>, before: Instant, since: Instant?,
        only: Set<String>?, perBranch: Int?, global: Boolean = false,
    ): String {
        val m = q.p(me)
        // в global друзья не нужны — и параметр не заводим (Hibernate не любит неиспользованных)
        val f = if (global || fr.isEmpty()) null else q.p(fr)
        val b = q.p(before)
        val s = since?.let { q.p(it) }
        val lim = perBranch?.let { q.p(it) }
        // global — вся сеть: «мои сообщества» = все живые, «друзья» = все люди
        val my = if (global) "(select gc.id from community gc where not gc.is_deleted)" else
            """(select cm.community_id from community_member cm where cm.user_id = $m and cm.left_at is null
                     union select cf.community_id from community_follow cf where cf.user_id = $m)"""
        fun inF(col: String) = when {
            global -> "true"
            f == null -> "false"
            else -> "$col in ($f)"
        }

        val branches = mutableListOf<String>()

        // записи: сообщества, пульс, стены
        branches += """
            select 'post' as kind,
                   case when exists (select 1 from media_attachment a join media md on md.id = a.media_id
                                     where a.owner_type = 'post' and a.owner_id = p.id and md.content_type like 'video/%') then 'video'
                        when p.community_id in $my then 'communities'
                        else 'friends' end as source,
                   p.id as ref, case when p.as_community then null else p.creator_id end as actor,
                   p.community_id as ctx, p.created_at as at, null::text as extra
            from post p left join community c on c.id = p.community_id
            where not p.is_deleted and (p.community_id is null or not c.is_deleted)
              and (p.community_id in $my or p.creator_id = $m
                   or (${inF("p.creator_id")} and not p.as_community) or ${inF("p.wall_user_id")})"""

        // ролики без записи (загружены прямо во вкладку «видео»)
        branches += """
            select 'video', 'video', v.media_id, v.author_id, null::uuid, v.created_at, null
            from video_item v
            where v.source = 'upload' and (v.author_id = $m or ${inF("v.author_id")})"""

        // эфиры: момент начала; extra = статус
        branches += """
            select 'stream', 'video', st.id, st.created_by, sc.community_id, st.started_at, st.status
            from stream st join stream_channel sc on sc.id = st.channel_id
            where st.started_at is not null
              and (sc.community_id in $my or sc.user_id = $m or ${inF("sc.user_id")})"""

        // гостевые: моя и друзей (свои записи не показываем); в общей ленте — нет (это личное)
        if (!global) branches += """
            select 'guestbook', 'rooms', g.id, g.author_id, g.owner_id, g.created_at, null
            from room_guestbook_entry g
            where g.deleted_at is null and g.author_id <> $m
              and (g.owner_id = $m or ${inF("g.owner_id")})"""

        // музыка сообществ
        branches += """
            select 'community_track', 'music', ct.track_id, ct.added_by, ct.community_id, ct.added_at, null
            from community_track ct join track t on t.id = ct.track_id and t.deleted_at is null
            where ct.community_id in $my and ct.added_by <> $m"""

        // плейлисты: сообществ и публичные друзей
        branches += """
            select 'playlist', 'music', pl.id, pl.owner_id, pl.community_id, pl.created_at, null
            from playlist pl
            where pl.deleted_at is null and pl.is_public
              and (pl.community_id in $my or (pl.community_id is null and ${inF("pl.owner_id")}))"""

        // события и обсуждения моих сообществ
        branches += """
            select 'event', 'communities', e.id, e.created_by, e.community_id, e.created_at, null
            from community_event e join community c on c.id = e.community_id and not c.is_deleted
            where e.cancelled_at is null and e.community_id in $my"""
        branches += """
            select 'discussion', 'communities', ch.id, ch.created_by, ch.community_id, ch.created_at, null
            from chat ch join community c on c.id = ch.community_id and not c.is_deleted
            where ch.room_type = 'community' and not ch.is_deleted and ch.community_id in $my"""

        // ответы мне: на мои записи и на мои комментарии
        if (!global) branches += """
            select 'reply', 'replies', pc.id, pc.author_id, pc.post_id, pc.created_at,
                   case when pc.parent_id is null then 'post' else 'comment' end
            from post_comment pc
            join post p on p.id = pc.post_id and not p.is_deleted
            left join post_comment par on par.id = pc.parent_id
            where pc.deleted_at is null and pc.author_id <> $m
              and ((pc.parent_id is null and p.creator_id = $m) or par.author_id = $m)"""

        // новые друзья
        if (!global) branches += """
            select 'friend', 'friends',
                   case when fs.initiator_id = $m then fs.acceptor_id else fs.initiator_id end,
                   case when fs.initiator_id = $m then fs.acceptor_id else fs.initiator_id end,
                   null::uuid, fs.accepted_at, null
            from friendship fs
            where fs.is_accepted and fs.accepted_at is not null and (fs.initiator_id = $m or fs.acceptor_id = $m)"""

        if (global || f != null) {
            branches += """
                select 'room', 'rooms', ra.id, ra.owner_id, ra.owner_id, ra.created_at, ra.detail
                from room_activity ra where ${inF("ra.owner_id")}"""
            // «слушает сейчас» — только пока трек играет
            branches += """
                select 'listening', 'music', np.track_id, np.user_id, null::uuid, np.started_at, null
                from now_playing np where np.ends_at > now() and ${inF("np.user_id")}"""
            branches += """
                select 'track', 'music', t.id, t.uploader_id, null::uuid, t.created_at, null
                from track t where t.deleted_at is null and ${inF("t.uploader_id")}"""
            branches += """
                select 'join', 'friends', mb.community_id, mb.user_id, mb.community_id, mb.created_at, null
                from community_member mb join community c on c.id = mb.community_id and not c.is_deleted
                where mb.left_at is null and ${inF("mb.user_id")}"""
        }

        val sourceCond = only?.let { set -> " and x.source in (${q.p(set.toList())})" } ?: ""
        val timeCond = "x.at < $b" + (s?.let { " and x.at > $it" } ?: "")
        val limitCond = lim?.let { " order by x.at desc limit $it" } ?: ""
        return "select * from (" + branches.joinToString(" union all ") { br ->
            // алиасы колонок на каждой ветке: у веток после первой свои имена колонок
            "(select * from ($br) x(kind, source, ref, actor, ctx, at, extra) where $timeCond$sourceCond$limitCond)"
        } + ") u"
    }

    /** Сколько нового за 24 часа по источникам. */
    private fun today(me: UUID, fr: List<UUID>, global: Boolean): Map<String, Long> {
        val q = Sql()
        val inner = union(q, me, fr, Instant.now().plusSeconds(60), Instant.now().minusSeconds(24 * 3600), null, null, global)
        val query = em.createNativeQuery("select source, count(*) from ($inner) t group by source")
        q.params.forEachIndexed { i, v -> query.setParameter(i + 1, v) }
        @Suppress("UNCHECKED_CAST")
        return (query.resultList as List<Array<Any?>>).associate { it[0] as String to (it[1] as Number).toLong() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(sql: String, params: List<Any>): List<Row> {
        val query = em.createNativeQuery("select kind, source, ref, actor, ctx, at, extra from ($sql) r")
        params.forEachIndexed { i, v -> query.setParameter(i + 1, v) }
        return (query.resultList as List<Array<Any?>>).map {
            Row(it[0] as String, it[1] as String, it[2] as UUID, it[3] as UUID?, it[4] as UUID?, toInstant(it[5]), it[6] as String?)
        }
    }

    // ================================================================ «оживление»

    private fun hydrate(rows: List<Row>, me: UUID): List<LentaItemOut> {
        if (rows.isEmpty()) return emptyList()
        fun ids(vararg kinds: String) = rows.filter { it.kind in kinds }.map { it.ref }.distinct()

        // записи (и ответы — к их записям)
        val replyComments = posts.commentsByIds(ids("reply"), me)
        val postIds = (ids("post") + replyComments.values.map { it.postId }).distinct()
        val postEntities = if (postIds.isEmpty()) emptyMap() else Post.list("id in ?1", postIds).associateBy { it.id }
        val postOut = posts.render(postIds.mapNotNull { postEntities[it] }.filter { !it.isDeleted }, me).associateBy { it.id }

        // видео: ролики записей с видео + загруженные без записи
        @Suppress("UNCHECKED_CAST")
        val videoOfPost: Map<UUID, UUID> = if (postIds.isEmpty()) emptyMap() else
            (em.createNativeQuery(
                """
                select distinct on (a.owner_id) a.owner_id, a.media_id from media_attachment a
                join media md on md.id = a.media_id and md.content_type like 'video/%'
                where a.owner_type = 'post' and a.owner_id in (?1) order by a.owner_id, a.position
                """.trimIndent(),
            ).setParameter(1, postIds).resultList as List<Array<Any?>>).associate { it[0] as UUID to it[1] as UUID }
        val videoOut = videos.byIds(me, (videoOfPost.values + ids("video")).distinct())

        val streamOut = streams.byIds(me, ids("stream"))
        val trackOut = music.renderByIds(ids("listening", "track", "community_track"), me).associateBy { it.id }
        val playlistOut: Map<UUID, PlaylistOut> = music.playlistsByIds(ids("playlist"), me)
        val eventOut = events.byIds(ids("event"), me)
        val discussionOut = discussions.byIds(ids("discussion"), me)
        val guestbookOut = rooms.guestbookByIds(me, ids("guestbook"))

        @Suppress("UNCHECKED_CAST")
        val listening: Map<UUID, LentaListeningOut> = rows.filter { it.kind == "listening" }.mapNotNull { it.actor }.let { users ->
            if (users.isEmpty()) emptyMap() else
                (em.createNativeQuery("select user_id, started_at, ends_at from now_playing where user_id in (?1)")
                    .setParameter(1, users).resultList as List<Array<Any?>>)
                    .associate { it[0] as UUID to LentaListeningOut(toInstant(it[1]), toInstant(it[2])) }
        }

        // люди и сообщества
        val roomOwnerIds = rows.filter { it.kind == "room" || it.kind == "guestbook" }.mapNotNull { it.ctx }
        val users = profiles.shorts((rows.mapNotNull { it.actor } + roomOwnerIds).distinct())
        val commIds = (rows.filter { it.kind != "reply" && it.kind != "room" && it.kind != "guestbook" }.mapNotNull { it.ctx } +
                postOut.values.mapNotNull { it.communityInfo?.id }).distinct()
        val comms = if (commIds.isEmpty()) emptyMap() else
            Community.list("id in ?1 and isDeleted = false", commIds).associate { it.id to PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) }

        return rows.mapNotNull { r ->
            val actor = r.actor?.let { users[it] }
            val community = if (r.kind == "reply" || r.kind == "room" || r.kind == "guestbook") null else r.ctx?.let { comms[it] }
            val hue = community?.hue ?: r.actor?.let { Math.floorMod(it.hashCode(), 360) } ?: 200
            val key = "${r.kind}:${r.ref}" + (r.actor?.let { ":$it" } ?: "")
            fun item(
                summary: String, link: String, target: LentaTargetOut, detail: String? = null,
                post: PostOut? = null, video: VideoOut? = null,
                stream: StreamOut? = null, track: TrackOut? = null,
                listen: LentaListeningOut? = null, playlist: PlaylistOut? = null,
                event: EventOut? = null, discussion: DiscussionOut? = null,
                guestbook: GuestbookEntryOut? = null, roomOwner: UserShortOut? = null,
                comment: CommentOut? = null, comm: PostCommunityOut? = community,
            ) = LentaItemOut(
                key, r.kind, r.source, r.at, actor, comm, summary, detail, link, target, comm?.hue ?: hue,
                post, video, stream, track, listen, playlist, event, discussion, guestbook, roomOwner, comment,
            )

            when (r.kind) {
                "post" -> {
                    val p = postOut[r.ref] ?: return@mapNotNull null
                    val v = videoOfPost[p.id]?.let { videoOut[it] }
                    val comm = p.communityInfo
                    val where = when (p.source) {
                        "wall" -> "у себя на стене"
                        "pulse" -> if (comm != null) "в пульсе для «${comm.name}»" else "в пульсе"
                        else -> "в «${comm?.name ?: "сообществе"}»"
                    }
                    val summary = when {
                        p.asCommunity && comm != null -> if (v != null) "выложило видео" else "опубликовало запись"
                        v != null -> "выложил(а) видео $where"
                        p.poll != null -> "устроил(а) опрос $where"
                        else -> "написал(а) $where"
                    }
                    item(summary, postLink(p), LentaTargetOut("post", p.id, comm?.slug, p.author?.username), post = p, video = v, comm = comm)
                }
                "video" -> {
                    val v = videoOut[r.ref] ?: return@mapNotNull null
                    item("выложил(а) видео", "/video?v=${v.id}", LentaTargetOut("video", v.id, username = actor?.username),
                        detail = v.title, video = v)
                }
                "stream" -> {
                    val s = streamOut[r.ref] ?: return@mapNotNull null
                    val comm = s.owner.community
                    val summary = if (s.status == "live") "в эфире: «${s.title}»" else "провёл(а) эфир «${s.title}»"
                    item(summary, "/video/live/${s.id}", LentaTargetOut("stream", s.id, comm?.slug, actor?.username),
                        detail = if (s.status == "live") "смотрят ${s.viewers}" else null, stream = s, comm = comm)
                }
                "room" -> {
                    val owner = r.ctx?.let { users[it] } ?: return@mapNotNull null
                    item(r.extra ?: "обновил(а) комнату", "/rooms/${owner.username}",
                        LentaTargetOut("room", owner.id, username = owner.username), roomOwner = owner)
                }
                "guestbook" -> {
                    val g = guestbookOut[r.ref] ?: return@mapNotNull null
                    val owner = r.ctx?.let { users[it] } ?: return@mapNotNull null
                    val summary = if (owner.id == me) "написал(а) у тебя в гостевой" else "написал(а) в гостевой у ${owner.username}"
                    item(summary, "/rooms/${owner.username}", LentaTargetOut("room", owner.id, username = owner.username),
                        detail = g.body.take(140).ifEmpty { null }, guestbook = g, roomOwner = owner)
                }
                "listening" -> {
                    val t = trackOut[r.ref] ?: return@mapNotNull null
                    item("слушает", "/music", LentaTargetOut("track", t.id, username = actor?.username),
                        detail = "${t.artist} — ${t.title}", track = t, listen = r.actor?.let { listening[it] })
                }
                "track" -> {
                    val t = trackOut[r.ref] ?: return@mapNotNull null
                    item("выложил(а) трек", "/music?track=${t.id}", LentaTargetOut("track", t.id, username = actor?.username),
                        detail = "${t.artist} — ${t.title}", track = t)
                }
                "community_track" -> {
                    val t = trackOut[r.ref] ?: return@mapNotNull null
                    item("добавил(а) трек в музыку «${community?.name ?: "сообщества"}»", "/c/${community?.slug}?tab=music",
                        LentaTargetOut("track", t.id, community?.slug), detail = "${t.artist} — ${t.title}", track = t)
                }
                "playlist" -> {
                    val pl = playlistOut[r.ref] ?: return@mapNotNull null
                    val summary = if (community != null) "собрал(а) плейлист для «${community.name}»" else "собрал(а) плейлист"
                    item(summary, "/music/playlists/${pl.id}", LentaTargetOut("playlist", pl.id, community?.slug),
                        detail = "«${pl.title}» · ${pl.trackCount} трек.", playlist = pl)
                }
                "event" -> {
                    val e = eventOut[r.ref] ?: return@mapNotNull null
                    item("новое событие в «${community?.name ?: "сообществе"}»: ${e.title}", "/c/${community?.slug}?tab=events&event=${e.id}",
                        LentaTargetOut("event", e.id, community?.slug),
                        detail = WHEN_FMT.format(e.startsAt.atZone(ZoneId.of("UTC"))) + (e.location?.let { " · $it" } ?: ""),
                        event = e)
                }
                "discussion" -> {
                    val d = discussionOut[r.ref] ?: return@mapNotNull null
                    item("начал(а) обсуждение в «${community?.name ?: "сообществе"}»: ${d.title}",
                        "/c/${community?.slug}?tab=discussions&chat=${d.chatId}",
                        LentaTargetOut("discussion", d.chatId, community?.slug),
                        detail = "${d.replies} сообщ. · ${d.participants} внутри", discussion = d)
                }
                "join" -> {
                    val c = community ?: return@mapNotNull null
                    item("вступил(а) в «${c.name}»", "/c/${c.slug}", LentaTargetOut("community", c.id, c.slug))
                }
                "reply" -> {
                    val c = replyComments[r.ref] ?: return@mapNotNull null
                    val p = postOut[c.postId] ?: return@mapNotNull null
                    val summary = if (r.extra == "comment") "ответил(а) на твой комментарий" else "ответил(а) на твою запись"
                    item(summary, postLink(p) + "&comment=${c.id}",
                        LentaTargetOut("post", p.id, p.communityInfo?.slug, p.author?.username, postId = p.id),
                        detail = c.body?.take(140), post = p, comment = c, comm = p.communityInfo)
                }
                "friend" -> {
                    val u = actor ?: return@mapNotNull null
                    item("теперь у тебя в друзьях", "/rooms/${u.username}", LentaTargetOut("user", u.id, username = u.username))
                }
                else -> null
            }
        }
    }

    private fun postLink(p: PostOut): String = when (p.source) {
        "wall" -> "/rooms/${p.wallOwner?.username ?: p.author?.username}?post=${p.id}"
        "pulse" -> "/pulse?post=${p.id}"
        else -> "/c/${p.community}?post=${p.id}"
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}
