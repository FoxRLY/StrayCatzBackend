package org.example.ws.service


import com.fasterxml.jackson.databind.ObjectMapper
import org.example.ws.bus.EventBus
import org.example.ws.domain.AppUser
import org.example.ws.domain.ChatMember
import org.example.ws.domain.UserPresenceEntity
import org.example.ws.proto.Envelope
import org.example.ws.proto.FrameTypes
import org.example.ws.proto.ListeningOut
import org.example.ws.proto.PresenceChangedOut
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class PresenceService(
    private val bus: EventBus,
    private val mapper: ObjectMapper,
) {
    private data class Snapshot(
        var status: String = "online",
        var doing: String? = null,
        var trackId: String? = null,
        var positionSec: Int? = null,
    )

    private val inMemory = ConcurrentHashMap<UUID, Snapshot>()
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()

    @Transactional
    fun setOnline(userId: UUID) = apply(userId) { it.status = "online" }

    @Transactional
    fun setOffline(userId: UUID) = apply(userId) { it.status = "offline" }

    @Transactional
    fun update(userId: UUID, status: String?, doing: String?, trackId: String?, positionSec: Int?) =
        apply(userId) {
            if (status != null) it.status = status
            it.doing = doing
            it.trackId = trackId
            it.positionSec = positionSec
        }

    private fun apply(userId: UUID, mutate: (Snapshot) -> Unit) {
        val snap = inMemory.computeIfAbsent(userId) { Snapshot() }
        synchronized(snap) { mutate(snap) }
        dirty.add(userId)
        broadcast(userId, snap)
    }

    private fun broadcast(userId: UUID, snap: Snapshot) {
        val handle = AppUser.findById(userId)?.handle ?: return
        val recipients = contactsOf(userId)
        if (recipients.isEmpty()) return

        val listening = snap.trackId?.let { ListeningOut(it, snap.positionSec ?: 0) }
        val payload = PresenceChangedOut(handle = handle, status = snap.status, doing = snap.doing, listening = listening)
        val frame = Envelope(t = FrameTypes.PRESENCE_CHANGED, id = UUID.randomUUID().toString(), d = mapper.valueToTree(payload))
        runBlocking { bus.publishToUsers(recipients, frame) }
    }

    private fun contactsOf(userId: UUID): List<UUID> {
        val myChatIds = ChatMember.find("id.userId", userId).list().map { it.id.chatId }
        if (myChatIds.isEmpty()) return emptyList()
        return ChatMember.list("id.chatId in ?1 and id.userId <> ?2", myChatIds, userId)
            .map { it.id.userId }.distinct()
    }

    @Transactional
    fun flushDirty() {
        val batch = dirty.toList()
        if (batch.isEmpty()) return
        dirty.removeAll(batch.toSet())

        for (userId in batch) {
            val snap = inMemory[userId] ?: continue
            val row = UserPresenceEntity.findById(userId) ?: UserPresenceEntity().also { it.userId = userId; it.persist() }
            row.status = snap.status
            row.doing = snap.doing
            row.trackId = snap.trackId
            row.positionSec = snap.positionSec
            row.updatedAt = Instant.now()
        }
    }
}

@jakarta.enterprise.context.ApplicationScoped
class PresenceFlushJob(private val presence: PresenceService) {
    @io.quarkus.scheduler.Scheduled(every = "20s")
    fun flush() = presence.flushDirty()
}