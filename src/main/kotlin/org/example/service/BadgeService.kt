package org.example.service

import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.AppUser
import org.example.rest.ApiException
import org.example.rest.BadgeOut
import org.example.rest.BadgeProgressOut
import org.example.rest.BadgesOut
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Значки: выдаются сами, когда метрика доходит до цели.
 *
 * Когда проверяем:
 *  - сразу после действий, которые двигают метрику (апвоут — автору, гостевая —
 *    хозяину, музыка, барахолка, ночной онлайн): [mark] кладёт человека в
 *    очередь, BadgeJob раз в 20 секунд проверяет до 300 человек;
 *  - раз в 10 минут — всех, кто был активен за эти 10 минут (ловит то, что
 *    не отмечено явно: записи, комментарии, друзья, вступления);
 *  - при открытии вкладки «значки» — того, чьи значки смотрят.
 * Выдача идемпотентна (insert … on conflict do nothing), опыт за значок — один раз.
 */
@ApplicationScoped
class BadgeService(
    private val em: EntityManager,
    private val xp: XpService,
    private val notifications: NotificationService,
) {
    companion object {
        /** Уведомление «новый значок». payload: {code, title, icon, xp} */
        const val BADGE_EARNED = "badge_earned"
        const val BATCH = 300

        /** Метрика → запрос, ?1 = user_id, результат — одно число. */
        val METRICS: Map<String, String> = mapOf(
            "night_hours" to "select coalesce((select night_seconds from user_stat where user_id = ?1), 0) / 3600",
            "together" to "select coalesce((select together_count from user_stat where user_id = ?1), 0)",
            "guestbook_from_others" to
                    "select count(*) from room_guestbook_entry where owner_id = ?1 and author_id <> ?1 and deleted_at is null",
            "room_handmade" to """
                select count(*) from room r where r.owner_id = ?1
                  and (r.wall_media_id is not null or coalesce(r.wall_image_url, '') <> '')
                  and coalesce(r.about, '') <> '' and coalesce(r.mood, '') <> ''
            """.trimIndent(),
            "community_members" to """
                select coalesce(max(n), 0) from (
                  select count(m.user_id) n from community c
                  join community_member m on m.community_id = c.id and m.left_at is null
                  where c.owner_id = ?1 group by c.id) x
            """.trimIndent(),
            "tracks_listened" to "select count(*) from track_listen where user_id = ?1",
            "posts" to "select count(*) from post where creator_id = ?1 and not is_deleted and not as_community and source_url is null",
            "comments" to "select count(*) from post_comment where author_id = ?1 and deleted_at is null",
            "upvotes_received" to """
                select count(*) from post_upvote u join post p on p.id = u.post_id
                where p.creator_id = ?1 and not p.as_community and p.source_url is null
            """.trimIndent(),
            "friends" to "select count(*) from friendship where is_accepted and (initiator_id = ?1 or acceptor_id = ?1)",
            "tracks_uploaded" to "select count(*) from track where uploader_id = ?1 and deleted_at is null",
            "market_sold" to "select count(*) from market_item where seller_id = ?1 and status = 'sold'",
        )
    }

    private val queue = ConcurrentHashMap.newKeySet<UUID>()

    /** Поставить в очередь на проверку (дёшево, без БД). */
    fun mark(userId: UUID) { queue.add(userId) }

    fun mark(userIds: Collection<UUID>) { queue.addAll(userIds) }

    /** Забрать очередь (для BadgeJob). */
    fun drain(max: Int): List<UUID> {
        val batch = queue.take(max)
        queue.removeAll(batch.toSet())
        return batch
    }

    /**
     * Проверить человека и выдать всё, до чего он дорос. Возвращает выданные сейчас коды.
     * [only] — проверить только эти метрики.
     */
    @Transactional
    fun check(userId: UUID, only: Set<String>? = null): List<String> {
        val have = earned(userId).keys
        val todo = BadgeCatalog.ALL.filter { it.code !in have && (only == null || it.metric in only) }
        if (todo.isEmpty()) return emptyList()
        val values = todo.map { it.metric }.distinct().associateWith { metric(userId, it) }
        return todo.filter { (values[it.metric] ?: 0) >= it.goal }.filter { award(userId, it) }.map { it.code }
    }

    /** Вкладка «значки»: все значки с прогрессом. Заодно выдаёт дозревшие. */
    @Transactional
    fun of(username: String): BadgesOut {
        val user = AppUser.find("username = ?1 and isDeleted = false", username.trim().lowercase()).firstResult()
            ?: throw ApiException.notFound("пользователь не найден")
        check(user.id)
        val got = earned(user.id)
        val values = BadgeCatalog.ALL.map { it.metric }.distinct().associateWith { metric(user.id, it) }
        val items = BadgeCatalog.ALL.map { b ->
            val v = values[b.metric] ?: 0
            BadgeOut(
                code = b.code, title = b.title, description = b.description, icon = b.icon, xp = b.xp,
                earned = b.code in got, earnedAt = got[b.code],
                progress = BadgeProgressOut(v.coerceAtMost(b.goal), b.goal, b.unit.ifEmpty { null }),
            )
        }.sortedWith(compareByDescending<BadgeOut> { it.earned }.thenByDescending { it.earnedAt }
            .thenByDescending { it.progress.value.toDouble() / it.progress.goal })
        return BadgesOut(
            earned = items.count { it.earned },
            total = items.size,
            level = xp.level(user.id),
            items = items,
        )
    }

    /** Значки человека: код → когда получен. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun earned(userId: UUID): Map<String, Instant> =
        (em.createNativeQuery("select code, earned_at from user_badge where user_id = ?1")
            .setParameter(1, userId).resultList as List<Array<Any?>>)
            .associate { (it[0] as String) to toInstant(it[1]) }

    /**
     * Кого проверить по расписанию: только тех, у кого за последние [minutes]
     * минут сдвинулась метрика, которую не отмечают явно (записи, комментарии,
     * друзья, свои треки, новые участники в их сообществах). Не «всех активных»:
     * на 100k онлайн это были бы десятки тысяч проверок каждые 10 минут.
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun recentlyActive(minutes: Int, limit: Int): List<UUID> =
        em.createNativeQuery(
            """
            select x.u from (
                select creator_id as u from post where created_at > now() - make_interval(mins => ?1)
                union select author_id from post_comment where created_at > now() - make_interval(mins => ?1)
                union select initiator_id from friendship where accepted_at > now() - make_interval(mins => ?1)
                union select acceptor_id from friendship where accepted_at > now() - make_interval(mins => ?1)
                union select uploader_id from track where created_at > now() - make_interval(mins => ?1)
                union select c.owner_id from community_member m join community c on c.id = m.community_id
                      where m.created_at > now() - make_interval(mins => ?1) and c.owner_id is not null
            ) x limit ?2
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, minutes).setParameter(2, limit).resultList as List<UUID>

    // ------------------------------------------------------------------

    private fun metric(userId: UUID, metric: String): Long {
        val sql = METRICS[metric] ?: return 0
        return (em.createNativeQuery(sql).setParameter(1, userId).singleResult as Number).toLong()
    }

    /** true — выдали сейчас (раньше не было). */
    private fun award(userId: UUID, b: BadgeDef): Boolean {
        val inserted = em.createNativeQuery("insert into user_badge (user_id, code) values (?1, ?2) on conflict do nothing")
            .setParameter(1, userId).setParameter(2, b.code).executeUpdate()
        if (inserted == 0) return false
        notifications.notify(userId, BADGE_EARNED, null, mapOf("code" to b.code, "title" to b.title, "icon" to b.icon, "xp" to b.xp))
        xp.award(userId, b.xp, "badge", badge = b.code)
        return true
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}

/**
 * Проверка значков в фоне. Каждый человек — своей транзакцией (через прокси
 * BadgeService), чтобы ошибка у одного не откатила остальных.
 */
@ApplicationScoped
class BadgeJob(private val badges: BadgeService, private val lease: JobLease) {
    @Scheduled(every = "20s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun queued() {
        badges.drain(BadgeService.BATCH).forEach { id ->
            runCatching { badges.check(id) }.onFailure { Log.warnf("значки %s: %s", id, it.message) }
        }
    }

    @Scheduled(every = "10m", delayed = "2m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun active() {
        if (!lease.acquire("badges-active", java.time.Duration.ofMinutes(9))) return
        badges.recentlyActive(11, 20_000).forEach { badges.mark(it) }
    }
}
