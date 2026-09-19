package org.example.ws.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.example.ws.bus.EventBus
import org.example.ws.domain.CallEntity
import org.example.ws.domain.CallParticipant
import org.example.ws.domain.CallParticipantId
import org.example.ws.domain.CallSignalEntity
import org.example.ws.domain.ChatMember
import org.example.ws.proto.CallAcceptedOut
import org.example.ws.proto.CallDeclinedOut
import org.example.ws.proto.CallEndedOut
import org.example.ws.proto.CallRingingOut
import org.example.ws.proto.Envelope
import org.example.ws.proto.FrameTypes
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

class CallException(message: String) : RuntimeException(message)

@ApplicationScoped
class CallService(
    private val bus: EventBus,
    private val mapper: ObjectMapper,
) {

    @Transactional
    fun invite(chatId: UUID, callId: UUID, kind: String, fromUserId: UUID) {
        require(kind == "audio" || kind == "video") { "kind должен быть audio или video" }

        val memberIds = chatMembers(chatId)
        if (fromUserId !in memberIds) throw CallException("не участник чата")

        CallEntity().also {
            it.id = callId; it.chatId = chatId; it.kind = kind; it.startedBy = fromUserId
        }.persist()

        for (userId in memberIds) {
            CallParticipant().also {
                it.id = CallParticipantId(callId, userId)
                it.state = if (userId == fromUserId) "accepted" else "invited"
                if (userId == fromUserId) it.joinedAt = Instant.now()
            }.persist()
        }

        publish(chatId, FrameTypes.CALL_RINGING, CallRingingOut(chatId, callId, fromUserId, kind))
    }

    @Transactional
    fun accept(chatId: UUID, callId: UUID, userId: UUID) {
        setParticipantState(callId, userId, "accepted")
        CallEntity.findById(callId)?.let { if (it.status == "ringing") it.status = "active" }
        publish(chatId, FrameTypes.CALL_ACCEPTED, CallAcceptedOut(chatId, callId, userId))
    }

    @Transactional
    fun decline(chatId: UUID, callId: UUID, userId: UUID, reason: String?) {
        setParticipantState(callId, userId, "declined")
        publish(chatId, FrameTypes.CALL_DECLINED, CallDeclinedOut(chatId, callId, userId, reason))
        maybeEndIfEveryoneLeft(chatId, callId, "declined")
    }

    @Transactional
    fun leave(chatId: UUID, callId: UUID, userId: UUID) {
        CallParticipant.findById(CallParticipantId(callId, userId))?.let {
            it.state = "left"; it.leftAt = Instant.now()
        }
        maybeEndIfEveryoneLeft(chatId, callId, "left")
    }

    @Transactional
    fun signal(chatId: UUID, callId: UUID, fromUserId: UUID, toUserId: UUID?, kind: String, payload: Any) {
        val row = CallSignalEntity().also {
            it.callId = callId; it.chatId = chatId; it.fromUserId = fromUserId
            it.toUserId = toUserId; it.kind = kind; it.payload = mapper.writeValueAsString(payload)
        }
        row.persist()

        if (toUserId != null) {
            runBlocking { bus.publishPointerToUsers(listOf(toUserId), "call_signal", row.id.toString()) }
        } else {
            val others = chatMembers(chatId).filter { it != fromUserId }
            runBlocking { bus.publishPointerToUsers(others, "call_signal", row.id.toString()) }
        }
    }

    private fun setParticipantState(callId: UUID, userId: UUID, state: String) {
        CallParticipant.findById(CallParticipantId(callId, userId))?.let { it.state = state }
    }

    private fun maybeEndIfEveryoneLeft(chatId: UUID, callId: UUID, reason: String) {
        val active = CallParticipant.count("id.callId = ?1 and state in ('invited', 'accepted')", callId)
        if (active == 0L) {
            CallEntity.findById(callId)?.also { it.status = "ended"; it.endedAt = Instant.now(); it.endReason = reason }
            publish(chatId, FrameTypes.CALL_ENDED, CallEndedOut(chatId, callId, reason))
        }
    }

    private fun chatMembers(chatId: UUID): List<UUID> =
        ChatMember.find("id.chatId", chatId).list().map { it.id.userId }

    private fun publish(chatId: UUID, type: String, payload: Any) {
        val frame = Envelope(type, UUID.randomUUID().toString(), d = mapper.valueToTree(payload))
        runBlocking { bus.publishToChat(chatId, frame) }
    }
}
