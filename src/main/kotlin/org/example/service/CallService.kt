package org.example.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.bus.PointerKinds
import org.example.domain.CallEntity
import org.example.domain.CallParticipant
import org.example.domain.CallParticipantId
import org.example.domain.CallSignalEntity
import org.example.proto.CallAcceptedOut
import org.example.proto.CallDeclinedOut
import org.example.proto.CallEndedOut
import org.example.proto.CallLeftOut
import org.example.proto.CallRingingOut
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CallException(message: String) : RuntimeException(message)

/**
 * Сигналинг звонков. Состояние — в call / call_participant (V2).
 *
 * Жизненный цикл:
 *   invite -> ringing (инициатор accepted, остальные invited)
 *   accept -> active
 *   decline / leave / таймаут дозвона (missed) / обрыв последнего сокета (left)
 *   звонок завершается, когда "живых" (invited+accepted) остаётся <= 1.
 */
@ApplicationScoped
class CallService(
    private val bus: EventBus,
    private val mapper: ObjectMapper,
    private val chats: ChatService,
) {
    companion object {
        val RING_TIMEOUT: Duration = Duration.ofSeconds(45)
        val SIGNAL_TTL: Duration = Duration.ofMinutes(10)
        private val LIVE_STATES = listOf("invited", "accepted")
    }

    @Transactional
    fun invite(chatId: UUID, callId: UUID, kind: String, fromUserId: UUID) {
        if (kind != "audio" && kind != "video") throw CallException("kind должен быть audio или video")

        val memberIds = chats.activeMemberIds(chatId)
        if (fromUserId !in memberIds) throw CallException("не участник чата")
        if (memberIds.size < 2) throw CallException("в чате некому звонить")
        if (CallEntity.count("chatId = ?1 and status <> 'ended'", chatId) > 0) {
            throw CallException("в этом чате уже идёт звонок")
        }
        if (CallEntity.findById(callId) != null) throw CallException("callId уже использован")

        CallEntity().also {
            it.id = callId; it.chatId = chatId; it.kind = kind; it.startedBy = fromUserId
        }.persist()

        val now = Instant.now()
        for (userId in memberIds) {
            CallParticipant().also {
                it.id = CallParticipantId(callId, userId)
                it.state = if (userId == fromUserId) "accepted" else "invited"
                if (userId == fromUserId) it.joinedAt = now
            }.persist()
        }

        publish(chatId, FrameTypes.CALL_RINGING, CallRingingOut(chatId, callId, fromUserId, kind))
    }

    @Transactional
    fun accept(chatId: UUID, callId: UUID, userId: UUID) {
        val call = liveCall(chatId, callId)
        val p = participant(callId, userId)
        if (p.state != "invited") throw CallException("звонок уже ${p.state}")
        p.state = "accepted"
        p.joinedAt = Instant.now()
        if (call.status == "ringing") call.status = "active"
        publish(chatId, FrameTypes.CALL_ACCEPTED, CallAcceptedOut(chatId, callId, userId))
    }

    @Transactional
    fun decline(chatId: UUID, callId: UUID, userId: UUID, reason: String?) {
        val call = liveCall(chatId, callId)
        val p = participant(callId, userId)
        if (p.state != "invited") throw CallException("отклонить можно только входящий звонок")
        p.state = "declined"
        publish(chatId, FrameTypes.CALL_DECLINED, CallDeclinedOut(chatId, callId, userId, reason?.take(200)))
        maybeEnd(call, "declined")
    }

    @Transactional
    fun leave(chatId: UUID, callId: UUID, userId: UUID) {
        val call = liveCall(chatId, callId)
        val p = participant(callId, userId)
        if (p.state !in LIVE_STATES) return
        markLeft(call, p)
        maybeEnd(call, "left")
    }

    /** Закрылось последнее соединение пользователя — выходим из всех его живых звонков. */
    @Transactional
    fun leaveAll(userId: UUID) {
        val mine = CallParticipant.list("id.userId = ?1 and state in ?2", userId, LIVE_STATES)
        for (p in mine) {
            val call = CallEntity.findById(p.id.callId) ?: continue
            if (call.status == "ended") continue
            markLeft(call, p)
            maybeEnd(call, "disconnected")
        }
    }

    @Transactional
    fun signal(chatId: UUID, callId: UUID, fromUserId: UUID, toUserId: UUID?, kind: String, payload: JsonNode) {
        liveCall(chatId, callId)
        val from = participant(callId, fromUserId)
        if (from.state != "accepted") throw CallException("сигналить может только принявший звонок")

        val recipients: List<UUID> = if (toUserId != null) {
            val to = CallParticipant.findById(CallParticipantId(callId, toUserId))
            if (to == null || to.state != "accepted") throw CallException("адресат не в звонке")
            listOf(toUserId)
        } else {
            CallParticipant.list("id.callId = ?1 and state = 'accepted' and id.userId <> ?2", callId, fromUserId)
                .map { it.id.userId }
        }
        if (recipients.isEmpty()) return

        val row = CallSignalEntity().also {
            it.callId = callId; it.chatId = chatId; it.fromUserId = fromUserId
            it.toUserId = toUserId; it.kind = kind.take(32); it.payload = mapper.writeValueAsString(payload)
        }
        row.persist() // IDENTITY: INSERT выполняется сразу, id уже есть

        bus.publishPointerToUsers(recipients, PointerKinds.CALL_SIGNAL, row.id.toString())
    }

    // --------------------------------------------------------- фоновые задачи

    /** Кто не ответил за RING_TIMEOUT — missed; звонок, где никто не ответил, — ended/missed. */
    @Transactional
    fun expireRinging() {
        val deadline = Instant.now().minus(RING_TIMEOUT)
        val stale = CallEntity.list("status <> 'ended' and startedAt < ?1", deadline)
        for (call in stale) {
            val invited = CallParticipant.list("id.callId = ?1 and state = 'invited'", call.id)
            if (invited.isEmpty()) continue
            invited.forEach { it.state = "missed" }
            maybeEnd(call, "missed")
        }
    }

    @Transactional
    fun purgeOldSignals() {
        val n = CallSignalEntity.delete("createdAt < ?1", Instant.now().minus(SIGNAL_TTL))
        if (n > 0) Log.debugf("удалили %d старых call_signal", n)
    }

    // ------------------------------------------------------------------ utils

    private fun liveCall(chatId: UUID, callId: UUID): CallEntity {
        val call = CallEntity.findById(callId)
        if (call == null || call.chatId != chatId) throw CallException("звонок не найден")
        if (call.status == "ended") throw CallException("звонок уже завершён")
        return call
    }

    private fun participant(callId: UUID, userId: UUID): CallParticipant =
        CallParticipant.findById(CallParticipantId(callId, userId))
            ?: throw CallException("вы не участник этого звонка")

    private fun markLeft(call: CallEntity, p: CallParticipant) {
        val wasAccepted = p.state == "accepted"
        p.state = "left"
        p.leftAt = Instant.now()
        if (wasAccepted) publish(call.chatId, FrameTypes.CALL_LEFT, CallLeftOut(call.chatId, call.id, p.id.userId))
    }

    private fun maybeEnd(call: CallEntity, reason: String) {
        val live = CallParticipant.count("id.callId = ?1 and state in ?2", call.id, LIVE_STATES)
        if (live <= 1) {
            call.status = "ended"
            call.endedAt = Instant.now()
            call.endReason = reason
            // оставшегося одного (например, звонящего) тоже переводим в left
            CallParticipant.list("id.callId = ?1 and state in ?2", call.id, LIVE_STATES).forEach {
                it.state = if (it.state == "invited") "missed" else "left"
                it.leftAt = Instant.now()
            }
            publish(call.chatId, FrameTypes.CALL_ENDED, CallEndedOut(call.chatId, call.id, reason))
        }
    }

    private fun publish(chatId: UUID, type: String, payload: Any) {
        bus.publishToChat(chatId, Envelope(t = type, d = mapper.valueToTree(payload)))
    }
}

@ApplicationScoped
class CallJobs(private val calls: CallService) {
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun expire() = calls.expireRinging()

    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun purge() = calls.purgeOldSignals()
}
