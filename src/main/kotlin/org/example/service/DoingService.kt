package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.domain.AppUser
import org.example.rest.ApiException
import org.example.rest.DoingCommunityOut
import org.example.rest.DoingItemOut
import org.example.rest.DoingOut
import org.example.rest.DoingSummaryOut
import org.example.rest.DoingTargetOut
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Вкладка «чем занят» в комнате: короткое саммари за период и список
 * последних действий человека с датами. Ничего не хранится отдельно —
 * собирается одним UNION из записей, комментариев, вступлений, треков,
 * гостевых, комнаты, дружб, значков и барахолки.
 */
@ApplicationScoped
class DoingService(
    private val em: EntityManager,
    private val xp: XpService,
    @ConfigProperty(name = "straycatz.badges.night-zone", defaultValue = "Europe/Moscow") private val zone: String,
) {
    companion object {
        const val MAX_PAGE = 50
        const val MAX_DAYS = 365

        private val ITEMS_SQL = """
            select x.kind, x.ref, x.a, x.b, x.c, x.at, x.d from (
                select 'post' as kind, p.id as ref, coalesce(c.name, '') as a, coalesce(c.slug, '') as b,
                       coalesce(nullif(p.title, ''), left(p.body, 120)) as c, p.created_at as at,
                       case when p.wall_user_id is not null and p.wall_user_id <> p.creator_id
                                 then (select w.username from users w where w.id = p.wall_user_id)
                            when p.wall_user_id is not null then '#wall'
                            when p.is_pulse then '#pulse' else '#community' end as d
                from post p left join community c on c.id = p.community_id
                where p.creator_id = ?1 and not p.is_deleted and not p.as_community and p.source_url is null and p.created_at < ?2
              union all
                select 'comment', pc.post_id, coalesce(c.name, ''), coalesce(c.slug, ''), left(pc.body, 120), pc.created_at, null
                from post_comment pc join post p on p.id = pc.post_id left join community c on c.id = p.community_id
                where pc.author_id = ?1 and pc.deleted_at is null and pc.created_at < ?2
              union all
                select 'join', c.id, c.name, coalesce(c.slug, ''), null, m.created_at, null
                from community_member m join community c on c.id = m.community_id
                where m.user_id = ?1 and m.left_at is null and m.created_at < ?2
              union all
                select 'track', t.id, t.artist, '', t.title, t.created_at, null
                from track t where t.uploader_id = ?1 and t.deleted_at is null and t.created_at < ?2
              union all
                select 'guestbook', g.id, o.username, '', left(g.body, 120), g.created_at, null
                from room_guestbook_entry g join users o on o.id = g.owner_id
                where g.author_id = ?1 and g.owner_id <> ?1 and g.deleted_at is null and g.created_at < ?2
              union all
                select 'room', ra.id, '', '', ra.detail, ra.created_at, null
                from room_activity ra where ra.owner_id = ?1 and ra.created_at < ?2
              union all
                select 'friend', o.id, o.username, '', null, f.accepted_at, null
                from friendship f join users o on o.id = case when f.initiator_id = ?1 then f.acceptor_id else f.initiator_id end
                where f.is_accepted and f.accepted_at is not null and (f.initiator_id = ?1 or f.acceptor_id = ?1)
                  and f.accepted_at < ?2 and not o.is_deleted
              union all
                select 'badge', ub.user_id, ub.code, '', null, ub.earned_at, null
                from user_badge ub where ub.user_id = ?1 and ub.earned_at < ?2
              union all
                select 'market', mi.id, coalesce(mi.price::text, ''), mi.currency, mi.title, mi.created_at, mi.status
                from market_item mi where mi.seller_id = ?1 and mi.status <> 'deleted' and mi.created_at < ?2
            ) x
            order by x.at desc
            limit ?3
        """.trimIndent()
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun of(username: String, days: Int, before: Instant?, limit: Int): DoingOut {
        val user = AppUser.find("username = ?1 and isDeleted = false", username.trim().lowercase()).firstResult()
            ?: throw ApiException.notFound("пользователь не найден")
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = em.createNativeQuery(ITEMS_SQL)
            .setParameter(1, user.id).setParameter(2, before ?: Instant.now().plusSeconds(60)).setParameter(3, size + 1)
            .resultList as List<Array<Any?>>
        val items = rows.take(size).map { item(it) }
        return DoingOut(
            summary = if (before == null) summary(user.id, days.coerceIn(1, MAX_DAYS)) else null,
            items = items,
            hasMore = rows.size > size,
        )
    }

    // ------------------------------------------------------------------

    private fun item(r: Array<Any?>): DoingItemOut {
        val kind = r[0] as String
        val ref = r[1] as UUID
        val a = (r[2] as String?).orEmpty()
        val b = (r[3] as String?).orEmpty()
        val c = r[4] as String?
        val at = toInstant(r[5])
        val d = r[6] as String?
        return when (kind) {
            "post" -> DoingItemOut(
                kind, at,
                when (d) {
                    "#wall" -> "написал(а) на своей стене"
                    "#pulse" -> if (a.isNotEmpty()) "написал(а) в пульс из «$a»" else "написал(а) в пульс"
                    "#community" -> "написал(а) в сообществе «$a»"
                    else -> "написал(а) на стене у @$d"
                },
                "✏️", c, DoingTargetOut("post", ref, slug = b.ifEmpty { null }, username = d?.takeIf { !it.startsWith("#") }),
            )
            "comment" -> DoingItemOut(
                kind, at, if (a.isNotEmpty()) "прокомментировал(а) запись в «$a»" else "прокомментировал(а) запись",
                "💬", c, DoingTargetOut("post", ref, slug = b.ifEmpty { null }),
            )
            "join" -> DoingItemOut(kind, at, "вступил(а) в «$a»", "🏘️", null, DoingTargetOut("community", ref, slug = b, title = a))
            "track" -> DoingItemOut(kind, at, "загрузил(а) трек $a — $c", "🎵", null, DoingTargetOut("track", ref, title = c))
            "guestbook" -> DoingItemOut(kind, at, "оставил(а) запись в гостевой у @$a", "📖", c, DoingTargetOut("room", ref, username = a))
            "room" -> DoingItemOut(kind, at, c.orEmpty(), "🛋️", null, null)
            "friend" -> DoingItemOut(kind, at, "подружился(-ась) с @$a", "🤝", null, DoingTargetOut("user", ref, username = a))
            "badge" -> {
                val def = BadgeCatalog.BY_CODE[a]
                DoingItemOut(kind, at, "получил(а) значок «${def?.title ?: a}»", def?.icon ?: "🏅", def?.description, DoingTargetOut("badge", null, title = a))
            }
            "market" -> DoingItemOut(
                kind, at,
                (if (d == "sold") "продал(а) на барахолке «$c»" else "выставил(а) на барахолку «$c»"),
                "🏷️", if (a.isEmpty()) "договорная" else "$a $b", DoingTargetOut("market", ref, title = c),
            )
            else -> DoingItemOut(kind, at, kind, "•", c, null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun summary(userId: UUID, days: Int): DoingSummaryOut {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val n = em.createNativeQuery(
            """
            select
              (select count(*) from post where creator_id = ?1 and not is_deleted and not as_community and source_url is null and created_at > ?2),
              (select count(*) from post_comment where author_id = ?1 and deleted_at is null and created_at > ?2),
              (select count(*) from post_upvote u join post p on p.id = u.post_id where p.creator_id = ?1 and u.created_at > ?2),
              (select coalesce(sum(amount), 0) from xp_event where user_id = ?1 and created_at > ?2),
              (select count(*) from track_listen where user_id = ?1 and last_at > ?2),
              (select count(*) from community_member where user_id = ?1 and left_at is null and created_at > ?2),
              (select count(*) from room_guestbook_entry where owner_id = ?1 and author_id <> ?1 and deleted_at is null and created_at > ?2),
              (select count(*) from friendship where is_accepted and (initiator_id = ?1 or acceptor_id = ?1) and accepted_at > ?2),
              (select count(*) from user_badge where user_id = ?1 and earned_at > ?2)
            """.trimIndent(),
        ).setParameter(1, userId).setParameter(2, since).singleResult as Array<Any?>
        fun num(i: Int) = (n[i] as Number).toLong()

        // по каким дням и часам человек что-то делал (часы — в часовом поясе сервиса)
        val moments = em.createNativeQuery(
            """
            select (t at time zone ?3)::date, extract(hour from t at time zone ?3)::int from (
                select created_at t from post where creator_id = ?1 and not is_deleted and created_at > ?2
                union all select created_at from post_comment where author_id = ?1 and deleted_at is null and created_at > ?2
                union all select created_at from room_guestbook_entry where author_id = ?1 and deleted_at is null and created_at > ?2
                union all select created_at from room_activity where owner_id = ?1 and created_at > ?2
                union all select last_at from track_listen where user_id = ?1 and last_at > ?2
            ) x
            """.trimIndent(),
        ).setParameter(1, userId).setParameter(2, since).setParameter(3, zone).resultList as List<Array<Any?>>
        val activeDays = moments.map { it[0].toString() }.distinct().size
        val partOfDay = moments.map { (it[1] as Number).toInt() }
            .groupingBy { h -> when (h) { in 0..5 -> "night"; in 6..11 -> "morning"; in 12..17 -> "day"; else -> "evening" } }
            .eachCount().maxByOrNull { it.value }?.key

        val top = em.createNativeQuery(
            """
            select c.id, c.slug, c.name, c.avatar, count(*) as n from (
                select community_id as cid from post
                where creator_id = ?1 and not is_deleted and community_id is not null and created_at > ?2
                union all
                select p.community_id from post_comment pc join post p on p.id = pc.post_id
                where pc.author_id = ?1 and pc.deleted_at is null and p.community_id is not null and pc.created_at > ?2
            ) x join community c on c.id = x.cid
            group by c.id, c.slug, c.name, c.avatar order by n desc limit 1
            """.trimIndent(),
        ).setParameter(1, userId).setParameter(2, since).resultList as List<Array<Any?>>
        val topCommunity = top.firstOrNull()?.let {
            DoingCommunityOut(it[0] as UUID, it[1] as String?, it[2] as String, it[3] as String?, (it[4] as Number).toLong())
        }

        val onRepeat = (em.createNativeQuery(
            """
            select t.artist || ' — ' || t.title from track_listen tl join track t on t.id = tl.track_id
            where tl.user_id = ?1 and tl.last_at > ?2 and t.deleted_at is null
            order by tl.plays desc, tl.last_at desc limit 1
            """.trimIndent(),
        ).setParameter(1, userId).setParameter(2, since).resultList as List<Any?>).firstOrNull() as String?

        val posts = num(0); val comments = num(1); val upvotes = num(2); val xpGot = num(3)
        val tracks = num(4); val joined = num(5); val guestbook = num(6); val friends = num(7); val badges = num(8)

        val parts = listOfNotNull(
            posts.takeIf { it > 0 }?.let { plural(it, "запись", "записи", "записей") },
            comments.takeIf { it > 0 }?.let { plural(it, "комментарий", "комментария", "комментариев") },
            tracks.takeIf { it > 0 }?.let { plural(it, "трек", "трека", "треков") },
            friends.takeIf { it > 0 }?.let { plural(it, "новый друг", "новых друга", "новых друзей") },
            badges.takeIf { it > 0 }?.let { plural(it, "значок", "значка", "значков") },
            xpGot.takeIf { it > 0 }?.let { "+$it опыта" },
        )
        val whenText = when (partOfDay) {
            "night" -> "чаще всего появляется ночью"
            "morning" -> "чаще всего появляется утром"
            "day" -> "чаще всего появляется днём"
            "evening" -> "чаще всего появляется вечером"
            else -> null
        }
        val text = if (parts.isEmpty()) "за $days ${dayWord(days)} тихо" else
            "за $days ${dayWord(days)}: " + parts.joinToString(", ") + (whenText?.let { "; $it" } ?: "")

        return DoingSummaryOut(
            days = days,
            text = text,
            posts = posts,
            comments = comments,
            upvotesReceived = upvotes,
            xpEarned = xpGot,
            tracksListened = tracks,
            communitiesJoined = joined,
            guestbookReceived = guestbook,
            newFriends = friends,
            badgesEarned = badges,
            activeDays = activeDays,
            partOfDay = partOfDay,
            topCommunity = topCommunity,
            onRepeat = onRepeat,
            level = xp.level(userId),
        )
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val w = when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $w"
    }

    private fun dayWord(n: Int): String = plural(n.toLong(), "день", "дня", "дней").substringAfter(' ')

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}
