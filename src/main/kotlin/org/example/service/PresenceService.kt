package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.domain.AppUser
import org.example.domain.UserPresenceEntity
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.proto.ListeningOut
import org.example.proto.PresenceOut
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Presence:
 *  - кому рассылаем presence.changed — друзьям (friendship.is_accepted);
 *  - статусы собеседников в окне беседы клиент запрашивает сам через
 *    presence.query (можно спросить только про друзей и тех, с кем есть
 *    общий чат);
 *  - смена status пишется в user_presence сразу (редкое событие, и так
 *    другие ноды видят актуальный статус), doing/track — пачкой раз в 20с.
 */
@ApplicationScoped
class PresenceService(
    private val bus: EventBus,
    private val mapper: ObjectMapper,
    private val em: EntityManager,
) {
    companion object {
        val ALLOWED_STATUSES = setOf("online", "away", "dnd", "invisible", "offline")
        const val MAX_QUERY = 200
    }

    private data class Snapshot(
        var status: String = "online",
        var doing: String? = null,
        var trackId: String? = null,
        var positionSec: Int? = null,
    )

    private val inMemory = ConcurrentHashMap<UUID, Snapshot>()
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * Первое соединение пользователя. Если он раньше выставил away/dnd/invisible
     * — сохраняем это, а не сбрасываем в online.
     */
    @Transactional
    fun connected(userId: UUID) {
        val prev = UserPresenceEntity.findById(userId)?.status
        val status = if (prev == null || prev == "offline") "online" else prev
        apply(userId, persistNow = true) { it.status = status }
    }

    /** Последнее соединение пользователя на ЭТОЙ ноде закрыто. */
    @Transactional
    fun disconnected(userId: UUID) {
        apply(userId, persistNow = true) {
            // предпочтение (away/dnd/invisible) в памяти не держим — в БД
            // пишем offline, при следующем входе будет online
            it.status = "offline"; it.doing = null; it.trackId = null; it.positionSec = null
        }
        inMemory.remove(userId)
    }

    @Transactional
    fun update(userId: UUID, status: String?, doing: String?, trackId: String?, positionSec: Int?) {
        if (status != null && status !in ALLOWED_STATUSES) {
            throw MessageValidationException("status должен быть одним из $ALLOWED_STATUSES")
        }
        val statusChanged = status != null && status != inMemory[userId]?.status
        apply(userId, persistNow = statusChanged) {
            if (status != null) it.status = status
            it.doing = doing?.take(128)
            it.trackId = trackId
            it.positionSec = positionSec
        }
    }

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
        val fromDb = UserPresenceEntity.list("userId in ?1", ids).associate { it.userId to it.status }
        return ids.associateWith { id ->
            val st = inMemory[id]?.let { synchronized(it) { it.status } } ?: fromDb[id] ?: "offline"
            if (st == "invisible") "offline" else st
        }
    }

    @Transactional
    fun flushDirty() {
        val batch = dirty.toList()
        if (batch.isEmpty()) return
        dirty.removeAll(batch.toSet())
        batch.forEach { userId -> inMemory[userId]?.let { persist(userId, it) } }
    }

    // ------------------------------------------------------------------

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
        row.doing = s.doing
        row.trackId = s.trackId
        row.positionSec = s.positionSec
        row.updatedAt = Instant.now()
    }

    private fun broadcast(userId: UUID, snap: Snapshot) {
        val recipients = friendIds(userId)
        if (recipients.isEmpty()) return
        val username = AppUser.findById(userId)?.username ?: return
        val frame = Envelope(t = FrameTypes.PRESENCE_CHANGED, d = mapper.valueToTree(toOut(userId, username, snap)))
        bus.publishToUsers(recipients, frame)
    }

    private fun snapshot(userIds: Collection<UUID>): List<PresenceOut> {
        if (userIds.isEmpty()) return emptyList()
        val users = AppUser.list("id in ?1 and isDeleted = false", userIds.toList()).associateBy { it.id }
        val fromDb = UserPresenceEntity.list("userId in ?1", userIds.toList()).associateBy { it.userId }
        return userIds.mapNotNull { id ->
            val user = users[id] ?: return@mapNotNull null
            // локальная память свежее БД (doing/track пишутся пачкой)
            val snap = inMemory[id]?.let { synchronized(it) { it.copy() } }
                ?: fromDb[id]?.let { Snapshot(it.status, it.doing, it.trackId, it.positionSec) }
                ?: Snapshot(status = "offline")
            toOut(id, user.username, snap)
        }
    }

    private fun toOut(userId: UUID, username: String, s: Snapshot): PresenceOut {
        // invisible для других выглядит как offline, без подробностей
        if (s.status == "invisible" || s.status == "offline") return PresenceOut(userId, username, "offline")
        val listening = s.trackId?.let { ListeningOut(it, s.positionSec ?: 0) }
        return PresenceOut(userId, username, s.status, s.doing, listening)
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

@ApplicationScoped
class PresenceFlushJob(private val presence: PresenceService) {
    @Scheduled(every = "20s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun flush() = presence.flushDirty()
}
