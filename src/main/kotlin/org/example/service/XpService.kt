package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.rest.LevelOut
import org.example.rest.XpEventOut
import org.example.rest.XpPageOut
import java.time.Instant
import java.util.UUID

/**
 * Опыт и уровни. Уровень = xp / 100 (0–99 — нулевой, 100–199 — первый…).
 *
 * За что дают опыт:
 *  - апвоут на мою запись на стене (или в пульсе без сообщества) — +5;
 *  - апвоут на мою запись в сообществе — +2 (записи «от имени сообщества»
 *    и перенесённые из Telegram не считаются: их писал не автор);
 *  - значок — сколько указано в каталоге.
 * Апвоут не отзывается, поэтому и опыт за него не отнимается.
 */
@ApplicationScoped
class XpService(
    private val em: EntityManager,
    private val notifications: NotificationService,
) {
    companion object {
        const val PER_LEVEL = 100
        const val UPVOTE_WALL = 5
        const val UPVOTE_COMMUNITY = 2
        const val MAX_PAGE = 100
        /** Уведомление «новый уровень». payload: {level, xp} */
        const val LEVEL_UP = "level_up"

        fun levelOf(xp: Int) = (xp / PER_LEVEL).coerceAtLeast(0)

        fun levelOut(xp: Int): LevelOut {
            val level = levelOf(xp)
            return LevelOut(
                level = level,
                xp = xp,
                xpInLevel = xp - level * PER_LEVEL,
                perLevel = PER_LEVEL,
                nextLevelAt = (level + 1) * PER_LEVEL,
            )
        }
    }

    /**
     * Начислить опыт. Возвращает новый xp. При переходе на новый уровень —
     * уведомление level_up.
     */
    @Transactional
    fun award(userId: UUID, amount: Int, reason: String, refId: UUID? = null, actorId: UUID? = null, badge: String? = null): Int {
        if (amount == 0) return current(userId)
        em.createNativeQuery(
            // null в нативном запросе Hibernate биндит как bytea — передаём строки и nullif
            "insert into xp_event (id, user_id, amount, reason, ref_id, actor_id, badge) " +
                "values (?1, ?2, ?3, ?4, cast(nullif(?5, '') as uuid), cast(nullif(?6, '') as uuid), nullif(?7, ''))",
        ).setParameter(1, UUID.randomUUID()).setParameter(2, userId).setParameter(3, amount).setParameter(4, reason)
            .setParameter(5, refId?.toString() ?: "").setParameter(6, actorId?.toString() ?: "").setParameter(7, badge ?: "").executeUpdate()
        val xp = (em.createNativeQuery(
            """
            with up as (
                insert into user_level (user_id, xp, level) values (?1, greatest(?2, 0), greatest(?2, 0) / $PER_LEVEL)
                on conflict (user_id) do update
                    set xp = greatest(user_level.xp + ?2, 0),
                        level = greatest(user_level.xp + ?2, 0) / $PER_LEVEL
                returning xp
            ) select xp from up
            """.trimIndent(),
        ).setParameter(1, userId).setParameter(2, amount).singleResult as Number).toInt()
        val before = levelOf(xp - amount)
        val after = levelOf(xp)
        if (after > before) notifications.notify(userId, LEVEL_UP, null, mapOf("level" to after, "xp" to xp))
        return xp
    }

    /**
     * Апвоут на запись [postId] автора [authorId] от [voter]. Стена/пульс без
     * сообщества — 5, сообщество — 2. Записи от имени сообщества и зеркала — 0.
     */
    @Transactional
    fun forUpvote(postId: UUID, authorId: UUID, voter: UUID, communityId: UUID?, asCommunity: Boolean, mirrored: Boolean) {
        if (authorId == voter || asCommunity || mirrored) return
        if (communityId == null) award(authorId, UPVOTE_WALL, "upvote_wall", postId, voter)
        else award(authorId, UPVOTE_COMMUNITY, "upvote_community", postId, voter)
    }

    @Transactional
    fun current(userId: UUID): Int =
        (em.createNativeQuery("select coalesce((select xp from user_level where user_id = ?1), 0)")
            .setParameter(1, userId).singleResult as Number).toInt()

    @Transactional
    fun level(userId: UUID): LevelOut = levelOut(current(userId))

    /** История начислений, свежие сверху. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun history(userId: UUID, before: Instant?, limit: Int): XpPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = em.createNativeQuery(
            """
            select e.id, e.amount, e.reason, e.ref_id, e.badge, e.created_at, u.username
            from xp_event e left join users u on u.id = e.actor_id
            where e.user_id = ?1 and e.created_at < ?2
            order by e.created_at desc limit ?3
            """.trimIndent(),
        ).setParameter(1, userId).setParameter(2, before ?: Instant.now().plusSeconds(60)).setParameter(3, size + 1)
            .resultList as List<Array<Any?>>
        val items = rows.take(size).map { r ->
            val reason = r[2] as String
            val badge = (r[4] as String?)?.let { BadgeCatalog.BY_CODE[it] }
            val actor = r[6] as String?
            XpEventOut(
                id = r[0] as UUID,
                amount = (r[1] as Number).toInt(),
                reason = reason,
                refId = r[3] as UUID?,
                badge = r[4] as String?,
                at = toInstant(r[5]),
                text = when (reason) {
                    "upvote_wall" -> "апвоут на запись на стене" + (actor?.let { " от @$it" } ?: "")
                    "upvote_community" -> "апвоут на запись в сообществе" + (actor?.let { " от @$it" } ?: "")
                    "badge" -> "значок «${badge?.title ?: r[4]}»"
                    else -> reason
                },
            )
        }
        return XpPageOut(level(userId), items, rows.size > size)
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}
