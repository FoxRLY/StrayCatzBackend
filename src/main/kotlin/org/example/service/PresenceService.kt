package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.bus.EventBus
import org.example.domain.AppUser
import org.example.domain.UserPresenceEntity
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.proto.ListeningOut
import org.example.proto.PresenceOut
import org.example.registry.ConnectionRegistry
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Presence: кто в сети, кто отошёл, кто нет.
 *
 * Что видят другие (status) сервер считает сам:
 *  - нет ни одного открытого сокета (или нода, державшая сокет, не отмечалась
 *    дольше [STALE_AFTER]) — offline;
 *  - человек ничего не делал [OFFLINE_AFTER] (30 минут) — offline, даже если вкладка открыта;
 *  - ничего не делал [AWAY_AFTER] (10 минут) — away;
 *  - иначе — что он выставил сам (away / dnd / invisible) или online.
 *
 * «Действие» — любой кадр сокета, кроме служебных (ping, presence.query,
 * chat.close, room.close), отдельный кадр activity (фронт шлёт его по
 * движению мыши / клавишам / скроллу не чаще раза в минуту) и любой
 * изменяющий REST-запрос (POST/PUT/PATCH/DELETE).
 *
 * Как устроено: каждая нода держит в памяти тех, у кого открыт сокет на ней,
 * и раз в [TICK] пишет им в user_presence seen_at (пульс) и last_active_at,
 * пересчитывает статусы и рассылает presence.changed друзьям тех, у кого он
 * поменялся. Строки с seen_at старше [STALE_AFTER] (нода упала, не успев
 * записать offline) любая нода переводит в offline.
 */
@ApplicationScoped
class PresenceService(
    private val bus: EventBus,
    private val mapper: ObjectMapper,
    private val em: EntityManager,
    private val registry: ConnectionRegistry,
    @ConfigProperty(name = "straycatz.presence.away-after", defaultValue = "PT10M") private val awayAfter: Duration,
    @ConfigProperty(name = "straycatz.presence.offline-after", defaultValue = "PT30M") private val offlineAfter: Duration,
    @ConfigProperty(name = "straycatz.badges.night-zone", defaultValue = "Europe/Moscow") private val nightZone: String,
) {
    companion object {
        /** Что можно выставить руками. offline = invisible. */
        val ALLOWED_STATUSES = setOf("online", "away", "dnd", "invisible", "offline")
        val MANUAL = setOf("away", "dnd", "invisible")
        const val MAX_QUERY = 200
        const val TICK_SECONDS = 30L
        /** Нода не отмечалась столько — считаем, что её сокеты умерли. */
        val STALE_AFTER: Duration = Duration.ofMinutes(3)
        /** Пульс seen_at — раз в 60 с (каждый второй тик): на 100k онлайн это ~1,7k строк/с, а не 3,3k. */
        private const val PULSE_EVERY_TICKS = 2
        /** Список друзей для рассылки presence.changed кэшируем на 2 минуты. */
        private const val FRIENDS_TTL_MS = 120_000L
        private const val CHUNK = 1000
        /** «Ночной сторож»: с 00:00 до 06:00. */
        private const val NIGHT_UNTIL_HOUR = 6
    }

    private data class Snapshot(
        /** То, что видят другие. */
        var status: String = "online",
        /** Выставлено руками: away / dnd / invisible или null. */
        var manual: String? = null,
        var doing: String? = null,
        var trackId: String? = null,
        var positionSec: Int? = null,
        var lastActive: Instant = Instant.now(),
    )

    /** Только те, у кого открыт сокет на ЭТОЙ ноде. */
    private val inMemory = ConcurrentHashMap<UUID, Snapshot>()
    /** doing/track поменялись — записать пачкой. */
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()
    /** Что-то делали с прошлого тика — обновить last_active_at. */
    private val active = ConcurrentHashMap.newKeySet<UUID>()

    private val tickNo = java.util.concurrent.atomic.AtomicLong()
    private val friendsCache = ConcurrentHashMap<UUID, Pair<Long, List<UUID>>>()
    private val usernames = ConcurrentHashMap<UUID, String>()

    private val zone: ZoneId = runCatching { ZoneId.of(nightZone) }.getOrDefault(ZoneId.of("Europe/Moscow"))

    // ================================================================ события

    /**
     * Первое соединение пользователя на этой ноде. Ручной статус
     * (away/dnd/invisible) переживает переподключение.
     */
    @Transactional
    fun connected(userId: UUID) {
        val row = UserPresenceEntity.findById(userId)
        val now = Instant.now()
        apply(userId, persistNow = true) {
            it.manual = row?.manualStatus
            it.lastActive = now
            it.status = effective(it.manual, now, now)
        }
    }

    /** Последнее соединение пользователя на ЭТОЙ ноде закрыто. */
    @Transactional
    fun disconnected(userId: UUID) {
        // если у человека открыта вкладка на другой ноде, она вернёт online своим тиком
        apply(userId, persistNow = true) {
            it.status = "offline"; it.doing = null; it.trackId = null; it.positionSec = null
        }
        inMemory.remove(userId)
        dirty.remove(userId)
        active.remove(userId)
        friendsCache.remove(userId)
        usernames.remove(userId)
    }

    /**
     * Человек что-то сделал. Дёшево: без БД, если статус не меняется
     * (last_active_at уйдёт в базу ближайшим тиком). Если он был «отошёл»
     * или «не в сети» по бездействию — сразу online и presence.changed.
     */
    @Transactional
    fun touch(userId: UUID) {
        val snap = inMemory[userId] ?: return // сокета на этой ноде нет — учтёт нода с сокетом
        val now = Instant.now()
        val changed = synchronized(snap) {
            snap.lastActive = now
            val st = effective(snap.manual, now, now)
            if (st != snap.status) { snap.status = st; true } else false
        }
        active.add(userId)
        if (changed) {
            val copy = synchronized(snap) { snap.copy() }
            persist(userId, copy)
            broadcast(userId, copy)
        }
    }

    /** presence.set: ручной статус и «чем занят». Тоже считается действием. */
    @Transactional
    fun update(userId: UUID, status: String?, doing: String?, trackId: String?, positionSec: Int?) {
        if (status != null && status !in ALLOWED_STATUSES) {
            throw MessageValidationException("status должен быть одним из $ALLOWED_STATUSES")
        }
        val now = Instant.now()
        val before = inMemory[userId]?.let { synchronized(it) { it.status to it.manual } }
        apply(userId, persistNow = false) {
            if (status != null) it.manual = when (status) {
                "online" -> null
                "offline" -> "invisible"
                else -> status
            }
            it.doing = doing?.take(128)
            it.trackId = trackId
            it.positionSec = positionSec
            it.lastActive = now
            it.status = effective(it.manual, now, now)
        }
        active.add(userId)
        val after = inMemory[userId]?.let { synchronized(it) { it.copy() } } ?: return
        // смена статуса — в базу сразу (её читают другие ноды и списки онлайна)
        if (before == null || before.first != after.status || before.second != after.manual) {
            persist(userId, after)
            dirty.remove(userId)
        }
    }

    // ================================================================ чтение

    /** Текущие статусы друзей — кладём в ready. */
    @Transactional
    fun friendsSnapshot(userId: UUID): List<PresenceOut> = snapshot(friendIds(userId))

    /** presence.query: отдаём только тех, кого спрашивающему можно видеть. */
    @Transactional
    fun query(requester: UUID, userIds: List<UUID>): List<PresenceOut> {
        if (userIds.isEmpty()) return emptyList()
        val wanted = userIds.distinct().take(MAX_QUERY)
        val visible = visibleTo(requester, wanted)
        return snapshot(wanted.filter { it in visible })
    }

    /**
     * Только статус (без «чем занят» и музыки) — для публичных списков вроде
     * участников сообщества. invisible показывается как offline.
     */
    @Transactional
    fun publicStatuses(userIds: Collection<UUID>): Map<UUID, String> {
        if (userIds.isEmpty()) return emptyMap()
        val ids = userIds.distinct()
        val now = Instant.now()
        val fromDb = ids.chunked(CHUNK).flatMap { UserPresenceEntity.list("userId in ?1", it) }.associateBy { it.userId }
        return ids.associateWith { id ->
            val st = inMemory[id]?.let { synchronized(it) { it.status } } ?: fromDb[id]?.let { statusOf(it, now) } ?: "offline"
            if (st == "invisible") "offline" else st
        }
    }

    // ================================================================ тик

    /**
     * Раз в 30 секунд на каждой ноде:
     *  1) пульс seen_at и last_active_at для своих подключённых;
     *  2) пересчёт статусов (10 минут — away, 30 — offline) + presence.changed;
     *  3) «Ночной сторож»: +30 секунд ночного онлайна тем, кто не отошёл;
     *  4) чужие зависшие строки (нода упала) — в offline.
     * Возвращает тех, кому начислили ночное время (для проверки значков).
     */
    @Transactional
    fun tick(): List<UUID> {
        val now = Instant.now()
        // сокет закрылся мимо onClose (убитая вкладка, падение обработчика) — забываем
        inMemory.keys.filter { registry.connectionCount(it) == 0 }.forEach { id ->
            runCatching { disconnected(id) }.onFailure { Log.warn("presence: не смогли погасить $id", it) }
        }
        val nowMs = System.currentTimeMillis()
        friendsCache.entries.removeIf { nowMs - it.value.first > FRIENDS_TTL_MS }
        usernames.keys.removeIf { !inMemory.containsKey(it) }
        val local = inMemory.keys.toList()
        val wasActive = active.toList().also { active.removeAll(it.toSet()) }.filter { inMemory.containsKey(it) }

        if (tickNo.incrementAndGet() % PULSE_EVERY_TICKS == 0L) {
            local.chunked(CHUNK).forEach { chunk ->
                exec("update user_presence set seen_at = now() where user_id in (?1)", chunk)
            }
        }
        wasActive.chunked(CHUNK).forEach { chunk ->
            exec("update user_presence set last_active_at = greatest(last_active_at, now()) where user_id in (?1)", chunk)
        }

        // активность могла быть на другой ноде (вторая вкладка) — берём максимум из базы
        val dbActive = local.chunked(CHUNK).flatMap { chunk ->
            @Suppress("UNCHECKED_CAST")
            (em.createNativeQuery("select user_id, last_active_at from user_presence where user_id in (?1)")
                .setParameter(1, chunk).resultList as List<Array<Any?>>)
                .map { (it[0] as UUID) to toInstant(it[1]) }
        }.toMap()

        val nightly = mutableListOf<UUID>()
        val night = now.atZone(zone).hour < NIGHT_UNTIL_HOUR
        for (id in local) {
            val snap = inMemory[id] ?: continue
            val (changed, copy) = synchronized(snap) {
                dbActive[id]?.let { if (it.isAfter(snap.lastActive)) snap.lastActive = it }
                val st = effective(snap.manual, snap.lastActive, now)
                val ch = st != snap.status
                snap.status = st
                ch to snap.copy()
            }
            if (changed) {
                persist(id, copy)
                broadcast(id, copy)
            }
            if (night && copy.status in setOf("online", "dnd", "invisible")) nightly += id
        }

        // ночное время — одним апдейтом на пачку
        nightly.chunked(CHUNK).forEach { chunk ->
            exec("insert into user_stat (user_id) select id from users where id in (?1) on conflict do nothing", chunk)
            em.createNativeQuery(
                "update user_stat set night_seconds = night_seconds + ?2, updated_at = now() where user_id in (?1)",
            ).setParameter(1, chunk).setParameter(2, TICK_SECONDS).executeUpdate()
        }

        // строки, которые никто не пульсирует (нода умерла)
        @Suppress("UNCHECKED_CAST")
        val stale = em.createNativeQuery(
            """
            with s as (
                update user_presence set status = 'offline', doing = null, track_id = null, position_sec = null, updated_at = now()
                where status <> 'offline' and seen_at < now() - make_interval(secs => ?1)
                returning user_id
            ) select user_id from s
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, STALE_AFTER.seconds.toDouble()).resultList as List<UUID>
        stale.filter { !inMemory.containsKey(it) }.forEach { id -> broadcast(id, Snapshot(status = "offline")) }

        flushDirty()
        return nightly
    }

    @Transactional
    fun flushDirty() {
        val batch = dirty.toList()
        if (batch.isEmpty()) return
        dirty.removeAll(batch.toSet())
        batch.forEach { userId -> inMemory[userId]?.let { s -> persist(userId, synchronized(s) { s.copy() }) } }
    }

    // ------------------------------------------------------------------

    /** Что видят другие при таком ручном статусе и последнем действии. */
    private fun effective(manual: String?, lastActive: Instant, now: Instant): String {
        val idle = Duration.between(lastActive, now)
        return when {
            idle >= offlineAfter -> "offline"
            manual == "invisible" -> "invisible"
            manual == "dnd" -> "dnd"
            idle >= awayAfter -> "away"
            manual == "away" -> "away"
            else -> "online"
        }
    }

    /** Статус из строки базы (человек подключён к другой ноде или не подключён вовсе). */
    private fun statusOf(row: UserPresenceEntity, now: Instant): String {
        if (row.status == "offline" || row.seenAt.isBefore(now.minus(STALE_AFTER))) return "offline"
        return effective(row.manualStatus, row.lastActiveAt, now)
    }

    private fun apply(userId: UUID, persistNow: Boolean, mutate: (Snapshot) -> Unit) {
        val snap = inMemory.computeIfAbsent(userId) { Snapshot() }
        val copy = synchronized(snap) { mutate(snap); snap.copy() }
        if (persistNow) persist(userId, copy) else dirty.add(userId)
        broadcast(userId, copy)
    }

    private fun persist(userId: UUID, s: Snapshot) {
        val row = UserPresenceEntity.findById(userId)
            ?: UserPresenceEntity().also { it.userId = userId; it.persist() }
        row.status = s.status
        row.manualStatus = s.manual
        row.doing = s.doing
        row.trackId = s.trackId
        row.positionSec = s.positionSec
        if (s.status != "offline" || inMemory.containsKey(userId)) row.seenAt = Instant.now()
        if (s.lastActive.isAfter(row.lastActiveAt)) row.lastActiveAt = s.lastActive
        row.updatedAt = Instant.now()
    }

    private fun broadcast(userId: UUID, snap: Snapshot) {
        val now = System.currentTimeMillis()
        val recipients = friendsCache[userId]?.takeIf { now - it.first < FRIENDS_TTL_MS }?.second
            ?: friendIds(userId).also { friendsCache[userId] = now to it }
        if (recipients.isEmpty()) return
        val username = usernames[userId]
            ?: (AppUser.findById(userId)?.username ?: return).also { usernames[userId] = it }
        val frame = Envelope(t = FrameTypes.PRESENCE_CHANGED, d = mapper.valueToTree(toOut(userId, username, snap)))
        bus.publishToUsers(recipients, frame)
    }

    private fun snapshot(userIds: Collection<UUID>): List<PresenceOut> {
        if (userIds.isEmpty()) return emptyList()
        val now = Instant.now()
        val users = AppUser.list("id in ?1 and isDeleted = false", userIds.toList()).associateBy { it.id }
        val fromDb = UserPresenceEntity.list("userId in ?1", userIds.toList()).associateBy { it.userId }
        return userIds.mapNotNull { id ->
            val user = users[id] ?: return@mapNotNull null
            // локальная память свежее БД (doing/track пишутся пачкой)
            val snap = inMemory[id]?.let { synchronized(it) { it.copy() } }
                ?: fromDb[id]?.let { Snapshot(statusOf(it, now), it.manualStatus, it.doing, it.trackId, it.positionSec, it.lastActiveAt) }
                ?: Snapshot(status = "offline", lastActive = Instant.EPOCH)
            toOut(id, user.username, snap, fromDb[id]?.lastActiveAt)
        }
    }

    private fun toOut(userId: UUID, username: String, s: Snapshot, dbLastActive: Instant? = null): PresenceOut {
        // invisible для других выглядит как offline, без подробностей
        val lastSeen = listOfNotNull(s.lastActive.takeIf { it != Instant.EPOCH }, dbLastActive).maxOrNull()
        if (s.status == "invisible") return PresenceOut(userId, username, "offline")
        if (s.status == "offline") return PresenceOut(userId, username, "offline", lastSeenAt = lastSeen)
        val listening = s.trackId?.let { ListeningOut(it, s.positionSec ?: 0) }
        return PresenceOut(userId, username, s.status, s.doing, listening, lastSeenAt = lastSeen)
    }

    private fun exec(sql: String, ids: List<UUID>) {
        if (ids.isEmpty()) return
        em.createNativeQuery(sql).setParameter(1, ids).executeUpdate()
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }

    @Suppress("UNCHECKED_CAST")
    private fun friendIds(userId: UUID): List<UUID> =
        em.createNativeQuery(
            """
            select case when f.initiator_id = ?1 then f.acceptor_id else f.initiator_id end
            from friendship f
            where f.is_accepted and (f.initiator_id = ?1 or f.acceptor_id = ?1)
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, userId).resultList.distinct() as List<UUID>

    /** Кого из [candidates] пользователь может видеть: друзья + общие чаты. */
    @Suppress("UNCHECKED_CAST")
    private fun visibleTo(requester: UUID, candidates: List<UUID>): Set<UUID> {
        val sharedChat = em.createNativeQuery(
            """
            select distinct other.user_id
            from chat_member me
            join chat_member other on other.chat_id = me.chat_id and not other.is_deleted
            join chat c on c.id = me.chat_id and not c.is_deleted
            where me.user_id = ?1 and not me.is_deleted and other.user_id in (?2)
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, requester).setParameter(2, candidates).resultList as List<UUID>
        return (sharedChat + friendIds(requester)).toSet() + requester
    }
}

/** Тик presence отдельным бином — чтобы @Transactional сработал через прокси. */
@ApplicationScoped
class PresenceFlushJob(private val presence: PresenceService, private val badges: BadgeService) {
    @Scheduled(every = "30s", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun tick() {
        val nightly = presence.tick()
        // «Ночной сторож» проверяем только у тех, кому сейчас капнуло время
        if (nightly.isNotEmpty()) badges.mark(nightly)
    }
}
