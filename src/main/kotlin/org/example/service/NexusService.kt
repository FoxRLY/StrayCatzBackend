package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Community
import org.example.rest.NexusActivityOut
import org.example.rest.NexusCountsOut
import org.example.rest.NexusFriendOut
import org.example.rest.NexusOut
import org.example.rest.NexusTargetOut
import org.example.rest.PostCommunityOut
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Нексус — экран входа одним запросом: числа на плитках, «что произошло,
 * пока тебя не было» (последние 6 часов), друзья онлайн, эфиры, горячее в пульсе.
 */
@ApplicationScoped
class NexusService(
    private val em: EntityManager,
    private val profiles: UserProfileService,
    private val friends: FriendService,
    private val presence: PresenceService,
    private val music: MusicService,
    private val notifications: NotificationService,
    private val streams: StreamService,
    private val pulse: PulseService,
) {
    companion object {
        val WINDOW: Duration = Duration.ofHours(6)
        const val MAX_ACTIVITY = 30
        const val MAX_FRIENDS = 12
        private val STATUS_ORDER = mapOf("online" to 0, "away" to 1, "dnd" to 2)

        /** Сообщества, где я участник или читаю. ?1 = me. */
        private const val MY_COMMUNITIES = """
            select m.community_id from community_member m where m.user_id = ?1 and m.left_at is null
            union select f.community_id from community_follow f where f.user_id = ?1"""
    }

    @Transactional
    fun nexus(me: UUID): NexusOut {
        val since = Instant.now().minus(WINDOW)
        val fr = friends.friendIdsOf(me)
        val statuses = presence.publicStatuses(fr).filterValues { it != "offline" }
        val users = profiles.shorts(fr + me)
        val live = streams.list(me, "live", null, null, following = true, before = null, limit = 6)

        val freshPosts = count(
            "select count(*) from post p join community c on c.id = p.community_id and not c.is_deleted " +
                    "where not p.is_deleted and p.created_at > ?2 and p.creator_id <> ?1 and p.community_id in ($MY_COMMUNITIES)",
            me, since,
        )
        val friendPosts = if (fr.isEmpty()) 0 else count(
            "select count(*) from post p where not p.is_deleted and p.created_at > ?2 and p.community_id is null " +
                    "and p.creator_id in (?3) and p.creator_id <> ?1",
            me, since, fr,
        )

        val counts = NexusCountsOut(
            friends = fr.size,
            friendsOnline = statuses.size,
            communities = count("select count(*) from community_member m join community c on c.id = m.community_id and not c.is_deleted " +
                    "where m.user_id = ?1 and m.left_at is null", me),
            freshPosts = freshPosts,
            videos = videos(me, fr),
            listening = music.friendsListening(me).size,
            unreadMessages = count(
                """
                select coalesce(sum(greatest(coalesce(s.next_seq, 1) - 1 - m.last_read_seq, 0)), 0)
                from chat_member m
                join chat c on c.id = m.chat_id and not c.is_deleted and c.room_type <> 'stream'
                left join chat_seq s on s.chat_id = m.chat_id
                where m.user_id = ?1 and not m.is_deleted
                """.trimIndent(),
                me,
            ),
            unreadNotifications = notifications.unreadCount(me),
            pulse = count(
                "select count(*) from post p where p.is_pulse and not p.is_deleted and p.created_at > now() - interval '24 hours'",
            ),
            feed = freshPosts + friendPosts,
            live = live.items.size.toLong() + if (live.hasMore) 1 else 0,
        )

        return NexusOut(
            me = users[me],
            since = since,
            counts = counts,
            activity = activity(me, fr, since),
            friendsOnline = statuses.entries
                .sortedBy { STATUS_ORDER[it.value] ?: 9 }
                .take(MAX_FRIENDS)
                .mapNotNull { (id, st) -> users[id]?.let { NexusFriendOut(it, st) } },
            live = live.items,
            hot = pulse.list(me, "hot", null, 0, 5).items,
        )
    }

    private fun videos(me: UUID, fr: List<UUID>): Long {
        val friendsPart = if (fr.isEmpty()) "" else " or (v.author_id in (?2) and not v.as_community)"
        val sql = "select count(*) from video_item v where v.created_at > now() - interval '7 days' " +
                "and (v.author_id = ?1 or v.community_id in ($MY_COMMUNITIES)$friendsPart)"
        return if (fr.isEmpty()) count(sql, me) else count(sql, me, fr)
    }

    /** Лента событий друзей + эфиры моих сообществ за окно. */
    @Suppress("UNCHECKED_CAST")
    private fun activity(me: UUID, fr: List<UUID>, since: Instant): List<NexusActivityOut> {
        val friendParts = if (fr.isEmpty()) "" else """
            select case when p.wall_user_id is not null then 'wall' when p.is_pulse then 'pulse' else 'post' end,
                   p.creator_id, p.created_at, p.id, p.community_id, coalesce(p.title, left(p.body, 80))
            from post p left join community c on c.id = p.community_id
            where not p.is_deleted and not p.as_community and p.created_at > ?2 and p.creator_id in (?3)
              and (p.community_id is null or not c.is_deleted)
            union all
            select 'track', t.uploader_id, t.created_at, t.id, null, t.artist || ' — ' || t.title
            from track t where t.deleted_at is null and t.created_at > ?2 and t.uploader_id in (?3)
            union all
            select 'join', m.user_id, m.created_at, m.community_id, m.community_id, null
            from community_member m join community c on c.id = m.community_id and not c.is_deleted
            where m.left_at is null and m.created_at > ?2 and m.user_id in (?3)
            union all
        """
        val sql = """
            select * from (
            $friendParts
            select 'stream', s.created_by, s.started_at, s.id, ch.community_id, s.title
            from stream s join stream_channel ch on ch.id = s.channel_id
            where s.status = 'live' and s.created_by <> ?1
              and (ch.community_id in ($MY_COMMUNITIES)${if (fr.isEmpty()) "" else " or ch.user_id in (?3)"})
            ) x order by 3 desc limit $MAX_ACTIVITY
        """.trimIndent()
        val q = em.createNativeQuery(sql).setParameter(1, me)
        if (fr.isNotEmpty()) q.setParameter(2, since).setParameter(3, fr)
        val rows = q.resultList as List<Array<Any?>>
        if (rows.isEmpty()) return emptyList()

        val who = profiles.shorts(rows.map { it[1] as UUID })
        val commIds = rows.mapNotNull { it[4] as UUID? }.distinct()
        val comms = if (commIds.isEmpty()) emptyMap() else Community.list("id in ?1", commIds).associateBy { it.id }

        return rows.map { r ->
            val kind = r[0] as String
            val userId = r[1] as UUID
            val id = r[3] as UUID
            val c = (r[4] as UUID?)?.let { comms[it] }
            val text = (r[5] as String?)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
            val quoted = text?.let { " «${if (it.length > 60) it.take(60) + "…" else it}»" } ?: ""
            val what = when (kind) {
                "pulse" -> if (c != null) "написал(а) в пульс для «${c.name}»$quoted" else "написал(а) в пульс$quoted"
                "wall" -> "написал(а) у себя на стене$quoted"
                "post" -> "написал(а) в «${c?.name ?: "сообщество"}»$quoted"
                "track" -> "выложил(а) трек$quoted"
                "join" -> "вступил(а) в «${c?.name ?: "сообщество"}»"
                "stream" -> if (c != null) "ведёт эфир «${c.name}»$quoted" else "в эфире$quoted"
                else -> kind
            }
            NexusActivityOut(
                kind = kind,
                who = who[userId],
                what = what,
                target = when (kind) {
                    "track" -> NexusTargetOut("track", id)
                    "join" -> NexusTargetOut("community", id, c?.slug)
                    "stream" -> NexusTargetOut("stream", id, c?.slug)
                    else -> NexusTargetOut("post", id, c?.slug)
                },
                community = c?.let { PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) },
                hue = c?.hue ?: Math.floorMod(userId.hashCode(), 360),
                at = toInstant(r[2]),
            )
        }
    }

    private fun count(sql: String, vararg params: Any): Long {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        return (q.singleResult as Number).toLong()
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}
