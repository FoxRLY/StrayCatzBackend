package org.example.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.bus.EventBus
import org.example.domain.CallEntity
import org.example.domain.CallParticipant
import org.example.domain.CallParticipantId
import org.example.domain.CallSignalEntity
import org.example.domain.Chat
import org.example.domain.UserCosmetics
import org.example.proto.CallAcceptedOut
import org.example.proto.CallDeclinedOut
import org.example.proto.CallEndedOut
import org.example.proto.CallLeftOut
import org.example.proto.CallRingingOut
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.rest.ApiException
import org.example.rest.CallJoinOut
import org.example.rest.CallOut
import org.example.rest.CallParticipantOut
import org.example.rest.LiveKitConnectOut
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CallException(message: String) : RuntimeException(message)

/**
 * Звонки — личные и групповые — через LiveKit (SFU).
 *
 * Бэкенд отвечает за «кто кому звонит» и права, LiveKit — за медиа:
 *  - звонок = строка call + комната LiveKit `call-<id>`;
 *  - «начать» / «войти» → токен LiveKit (короткий, только на подключение);
 *  - звонок «звенит» у участников лички и маленьких групп (до ring-max человек);
 *    в больших группах и обсуждениях — «открытый»: плашка «идёт звонок», заходит кто хочет;
 *  - кто реально в комнате — по вебхукам LiveKit (participant_joined/left, room_finished)
 *    и сверке раз в 20 секунд (на случай потерянного вебхука или если вебхуки не настроены);
 *  - конец: в личке — когда один из двоих вышел; в группе — когда в звонке никого не осталось.
 *
 * Сокет только оповещает (call.ringing / accepted / declined / left / ended / updated),
 * SDP и ICE через него больше не ходят.
 */
@ApplicationScoped
class CallService(
    private val bus: EventBus,
    private val mapper: ObjectMapper,
    private val chats: ChatService,
    private val profiles: UserProfileService,
    private val lk: LiveKitClient,
    private val em: jakarta.persistence.EntityManager,
    private val friends: FriendService,
    private val chatAdmin: ChatManagementService,
    @ConfigProperty(name = "straycatz.calls.ring-max", defaultValue = "16") private val ringMax: Int,
    @ConfigProperty(name = "straycatz.calls.max-participants", defaultValue = "100") private val maxParticipants: Int,
) {
    companion object {
        val RING_TIMEOUT: Duration = Duration.ofSeconds(45)
        val SIGNAL_TTL: Duration = Duration.ofMinutes(10)
        /** Сколько комната может постоять пустой, прежде чем сверка закроет звонок. */
        val EMPTY_GRACE: Duration = Duration.ofSeconds(60)
        /** Принявший, которого нет в комнате дольше этого (закрыл вкладку, не положив трубку), — вышел. */
        val DROP_GRACE: Duration = Duration.ofSeconds(30)
        private val LIVE_STATES = listOf("invited", "accepted")
        const val CALL_UPDATED = "call.updated"
    }

    // ================================================================ начать / войти

    /**
     * Начать звонок в чате. Если в чате уже идёт звонок — просто войти в него.
     * callId можно передать свой (старый сокет-кадр call.invite), иначе сгенерируем.
     */
    @Transactional
    fun start(me: UUID, chatId: UUID, kindRaw: String?, callId: UUID? = null): CallJoinOut {
        requireConfigured()
        val kind = (kindRaw ?: "audio").lowercase()
        if (kind != "audio" && kind != "video") throw ApiException.badRequest("invalid_kind", "kind: audio или video")
        if (!chats.isMember(chatId, me)) throw ApiException.forbidden("ты не в этом чате")
        if (chats.roomType(chatId) == "stream") throw ApiException.badRequest("no_calls_here", "в чате эфира звонков нет")

        // двое нажали «позвонить» одновременно — второй дождётся первого и просто войдёт в его звонок
        em.createNativeQuery("select 1 from (select pg_advisory_xact_lock(hashtext(?1))) as l")
            .setParameter(1, "call:$chatId").singleResult
        CallEntity.find("chatId = ?1 and status <> 'ended'", chatId).firstResult()?.let { return join(me, it.id) }

        val memberIds = chats.activeMemberIds(chatId)
        if (memberIds.size < 2) throw ApiException.badRequest("nobody_to_call", "в чате некому звонить")
        val id = callId ?: UUID.randomUUID()
        if (CallEntity.findById(id) != null) throw ApiException.conflict("call_exists", "callId уже использован")

        val ring = memberIds.size <= ringMax
        val call = CallEntity().also {
            it.id = id; it.chatId = chatId; it.kind = kind; it.startedBy = me
            it.roomName = "call-$id"; it.ring = ring
        }
        call.persist()
        val now = Instant.now()
        CallParticipant().also { it.id = CallParticipantId(id, me); it.state = "accepted"; it.joinedAt = now }.persist()
        if (ring) {
            memberIds.filter { it != me }.forEach { uid ->
                CallParticipant().also { it.id = CallParticipantId(id, uid); it.state = "invited" }.persist()
            }
        }
        // комнату создаём заранее (пустая живёт минуту, лимит участников); не вышло — LiveKit создаст при входе
        lk.createRoom(call.roomName!!, 60, 20, maxParticipants, mapper.writeValueAsString(mapOf("chatId" to chatId, "callId" to id)))

        publish(chatId, FrameTypes.CALL_RINGING, CallRingingOut(chatId, id, me, kind, ring))
        pushUpdated(call)
        return joinOut(call, me)
    }

    /**
     * Позвонить человеку (из его комнаты или профиля): найдёт или заведёт личку и начнёт в ней звонок.
     * Можно другу или тому, с кем уже есть личка, — чтобы незнакомцы не звонили.
     */
    @Transactional
    fun callUser(me: UUID, other: UUID, kind: String?): CallJoinOut {
        if (other == me) throw ApiException.badRequest("invalid_user", "себе не звонят")
        if (!canCall(me, other)) {
            throw ApiException(403, "not_friends", "позвонить можно другу или тому, с кем уже есть переписка")
        }
        return start(me, chatAdmin.directChatId(me, other), kind)
    }

    @Transactional
    fun callUsername(me: UUID, username: String, kind: String?): CallJoinOut {
        val user = org.example.domain.AppUser.find("username = ?1 and isDeleted = false", username.trim().lowercase()).firstResult()
            ?: throw ApiException.notFound("пользователь не найден")
        return callUser(me, user.id, kind)
    }

    /** Можно ли [me] позвонить [other]: друзья или уже есть личка. */
    @Transactional
    fun canCall(me: UUID, other: UUID): Boolean {
        if (me == other) return false
        if (friends.stateBetween(me, other) == "friends") return true
        val key = listOf(me.toString(), other.toString()).sorted().joinToString(":")
        return Chat.count("directKey = ?1 and isDeleted = false", key) > 0
    }

    /** Принять входящий / войти в идущий звонок (в том числе вернуться после выхода). */
    @Transactional
    fun join(me: UUID, callId: UUID): CallJoinOut {
        requireConfigured()
        val call = liveCall(callId)
        if (!chats.isMember(call.chatId, me)) throw ApiException.forbidden("ты не в этом чате")
        val p = CallParticipant.findById(CallParticipantId(callId, me))
        if (p?.state == "kicked") throw ApiException.forbidden("тебя выгнали из этого звонка")
        val inCall = CallParticipant.count("id.callId = ?1 and state = 'accepted' and id.userId <> ?2", callId, me)
        if (p?.state != "accepted" && inCall >= maxParticipants) {
            throw ApiException(409, "call_full", "в звонке уже $maxParticipants человек")
        }
        val wasInvited = p?.state == "invited"
        val row = p ?: CallParticipant().also { it.id = CallParticipantId(callId, me) }.also { it.persist() }
        if (row.state != "accepted") {
            row.state = "accepted"
            row.joinedAt = Instant.now()
            row.leftAt = null
        }
        if (call.status == "ringing" && me != call.startedBy) call.status = "active"
        if (wasInvited || p == null) publish(call.chatId, FrameTypes.CALL_ACCEPTED, CallAcceptedOut(call.chatId, callId, me))
        pushUpdated(call)
        return joinOut(call, me)
    }

    @Transactional
    fun decline(me: UUID, callId: UUID, reason: String?) {
        val call = liveCall(callId)
        val p = CallParticipant.findById(CallParticipantId(callId, me)) ?: return
        if (p.state != "invited") throw ApiException.badRequest("not_ringing", "отклонить можно только входящий звонок")
        p.state = "declined"
        publish(call.chatId, FrameTypes.CALL_DECLINED, CallDeclinedOut(call.chatId, callId, me, reason?.take(200)))
        if (!maybeEnd(call, "declined")) pushUpdated(call)
    }

    /** Положить трубку. Медиа LiveKit отключит сам клиент; на всякий случай выгоняем и с сервера. */
    @Transactional
    fun leave(me: UUID, callId: UUID) {
        val call = CallEntity.findById(callId) ?: return
        if (call.status == "ended") return
        val p = CallParticipant.findById(CallParticipantId(callId, me)) ?: return
        if (p.state !in LIVE_STATES) return
        markLeft(call, p, "left")
        call.roomName?.let { lk.removeParticipant(it, me.toString()) }
        if (!maybeEnd(call, "left")) pushUpdated(call)
    }

    /** Выгнать из звонка: начавший звонок или создатель беседы. */
    @Transactional
    fun kick(me: UUID, callId: UUID, userId: UUID) {
        val call = liveCall(callId)
        if (!canKick(call, me)) throw ApiException.forbidden("выгонять может начавший звонок или создатель беседы")
        if (userId == me) return leave(me, callId)
        val p = CallParticipant.findById(CallParticipantId(callId, userId)) ?: throw ApiException.notFound("его нет в звонке")
        markLeft(call, p, "kicked")
        call.roomName?.let { lk.removeParticipant(it, userId.toString()) }
        if (!maybeEnd(call, "left")) pushUpdated(call)
    }

    @Transactional
    fun get(me: UUID, callId: UUID): CallOut {
        val call = CallEntity.findById(callId) ?: throw ApiException.notFound("звонок не найден")
        if (!chats.isMember(call.chatId, me)) throw ApiException.notFound("звонок не найден")
        return render(call, me)
    }

    /** Идущий звонок в чате или null — для плашки «идёт звонок» при открытии беседы. */
    @Transactional
    fun activeInChat(me: UUID, chatId: UUID): CallOut? {
        if (!chats.isMember(chatId, me)) throw ApiException.notFound("чат не найден")
        return CallEntity.find("chatId = ?1 and status <> 'ended'", chatId).firstResult()?.let { render(it, me) }
    }

    // ================================================================ старые кадры сокета

    /** call.invite { chatId, callId, kind } — как start(). */
    @Transactional
    fun invite(chatId: UUID, callId: UUID, kind: String, fromUserId: UUID): CallJoinOut = start(fromUserId, chatId, kind, callId)

    /** call.accept { chatId, callId } — как join(). */
    @Transactional
    fun accept(chatId: UUID, callId: UUID, userId: UUID): CallJoinOut {
        val call = CallEntity.findById(callId)
        if (call == null || call.chatId != chatId) throw CallException("звонок не найден")
        return join(userId, callId)
    }

    @Transactional
    fun decline(chatId: UUID, callId: UUID, userId: UUID, reason: String?) {
        checkChat(chatId, callId)
        decline(userId, callId, reason)
    }

    @Transactional
    fun leave(chatId: UUID, callId: UUID, userId: UUID) {
        checkChat(chatId, callId)
        leave(userId, callId)
    }

    /** P2P-сигналинг больше не нужен: медиа идёт через LiveKit. */
    @Suppress("UNUSED_PARAMETER")
    fun signal(chatId: UUID, callId: UUID, fromUserId: UUID, toUserId: UUID?, kind: String, payload: JsonNode) {
        throw CallException("call.signal больше не поддерживается: звонки идут через LiveKit, токен — в ответе на call.invite/call.accept")
    }

    // ================================================================ вебхуки LiveKit

    /** participant_joined / participant_left / room_finished. Повторы безопасны. */
    @Transactional
    fun onWebhook(event: JsonNode) {
        val type = event.path("event").asText()
        val room = event.path("room").path("name").asText()
        if (room.isEmpty()) return
        val call = CallEntity.find("roomName = ?1", room).firstResult() ?: return
        val identity = event.path("participant").path("identity").asText()
        val userId = runCatching { UUID.fromString(identity) }.getOrNull()
        when (type) {
            "participant_joined" -> {
                userId ?: return
                if (call.status == "ended") {
                    // зашёл в уже закрытый звонок со старым токеном — выгоняем
                    lk.removeParticipant(room, identity)
                    return
                }
                val p = CallParticipant.findById(CallParticipantId(call.id, userId))
                if (p == null || p.state == "kicked") {
                    lk.removeParticipant(room, identity)
                    return
                }
                p.connected = true
                p.connectedAt = Instant.now()
                p.leftAt = null
                if (p.state != "accepted") { p.state = "accepted"; p.joinedAt = Instant.now() }
                if (call.status == "ringing" && userId != call.startedBy) call.status = "active"
                pushUpdated(call)
            }
            "participant_left" -> {
                // Не «вышел», а «отключился»: так же выглядит обновление страницы или смена сети.
                // Вышел — это POST /leave (положил трубку) или сверка: не вернулся за DROP_GRACE.
                userId ?: return
                val p = CallParticipant.findById(CallParticipantId(call.id, userId)) ?: return
                if (!p.connected) return
                p.connected = false
                if (p.state == "accepted") p.leftAt = Instant.now() // «отключён с»
                if (call.status != "ended") pushUpdated(call)
            }
            "room_finished" -> if (call.status != "ended") end(call, "empty")
        }
    }

    // ================================================================ фоновые задачи

    /** Кто не ответил за RING_TIMEOUT — missed; если в звонке никого не осталось — конец. */
    @Transactional
    fun expireRinging() {
        val deadline = Instant.now().minus(RING_TIMEOUT)
        val stale = CallEntity.list("status <> 'ended' and startedAt < ?1", deadline)
        for (call in stale) {
            val invited = CallParticipant.list("id.callId = ?1 and state = 'invited'", call.id)
            if (invited.isEmpty()) continue
            invited.forEach { it.state = "missed" }
            if (!maybeEnd(call, "missed")) pushUpdated(call)
        }
    }

    /**
     * Сверка с LiveKit раз в 20 секунд: кто реально в комнате. Чинит потерянные вебхуки
     * и работает, даже если вебхуки не настроены: пустая дольше минуты комната — конец звонка.
     */
    @Transactional
    fun reconcile() {
        if (!lk.configured()) return
        val older = Instant.now().minus(EMPTY_GRACE)
        val dropped = Instant.now().minus(DROP_GRACE)
        CallEntity.list("status <> 'ended' and startedAt < ?1", Instant.now().minus(DROP_GRACE)).forEach { call ->
            val inRoom = lk.listParticipants(call.roomName ?: return@forEach) ?: return@forEach // LiveKit молчит — не решаем
            val rows = CallParticipant.list("id.callId = ?1", call.id)
            var changed = false
            rows.forEach { p ->
                val here = p.id.userId.toString() in inRoom
                if (p.connected != here) {
                    p.connected = here
                    if (p.state == "accepted") p.leftAt = if (here) null else (p.leftAt ?: Instant.now())
                    changed = true
                }
                // принял, но так и не появился в комнате или пропал и не вернулся — вышел
                if (!here && p.state == "accepted") {
                    val since = p.leftAt ?: p.joinedAt ?: call.startedAt
                    if (since.isBefore(dropped)) { markLeft(call, p, "left"); changed = true }
                }
            }
            if (inRoom.isEmpty() && rows.none { it.state == "invited" } && call.startedAt.isBefore(older)) {
                end(call, "empty")
            } else if (changed && !maybeEnd(call, "left")) {
                pushUpdated(call)
            }
        }
    }

    @Transactional
    fun purgeOldSignals() {
        val n = CallSignalEntity.delete("createdAt < ?1", Instant.now().minus(SIGNAL_TTL))
        if (n > 0) Log.debugf("удалили %d старых call_signal", n)
    }

    // ------------------------------------------------------------------

    private fun joinOut(call: CallEntity, me: UUID): CallJoinOut {
        val user = profiles.shorts(listOf(me))[me] ?: throw ApiException.notFound("пользователь не найден")
        val c = UserCosmetics.findById(me)
        val metadata = mapper.writeValueAsString(mapOf("username" to user.username, "avatar" to c?.avatar, "color" to c?.color))
        val sources = if (call.kind == "video") listOf("camera", "microphone", "screen_share", "screen_share_audio")
        else listOf("microphone", "screen_share", "screen_share_audio")
        val token = lk.participantToken(me.toString(), user.username, metadata, call.roomName!!, sources)
        return CallJoinOut(render(call, me), LiveKitConnectOut(lk.clientUrl, token, call.roomName!!, me.toString()))
    }

    private fun render(call: CallEntity, me: UUID): CallOut {
        val rows = CallParticipant.list("id.callId = ?1", call.id)
            .filter { it.state in setOf("invited", "accepted", "left") }
        val users = profiles.shorts(rows.map { it.id.userId } + call.startedBy)
        return CallOut(
            id = call.id,
            chatId = call.chatId,
            kind = call.kind,
            status = call.status,
            ring = call.ring,
            startedBy = users[call.startedBy],
            startedAt = call.startedAt,
            endedAt = call.endedAt,
            endReason = call.endReason,
            participants = rows.sortedWith(compareBy({ it.state != "accepted" }, { it.joinedAt ?: Instant.MAX }))
                .map { CallParticipantOut(users[it.id.userId], it.state, it.connected, it.joinedAt) },
            connectedCount = rows.count { it.connected },
            maxParticipants = maxParticipants,
            canKick = canKick(call, me),
        )
    }

    private fun canKick(call: CallEntity, me: UUID): Boolean =
        call.startedBy == me || Chat.findById(call.chatId)?.createdBy == me

    private fun requireConfigured() {
        if (!lk.configured()) throw ApiException(503, "calls_disabled", "звонки не настроены: нужен straycatz.livekit.api-secret (≥ 32 символов)")
    }

    private fun liveCall(callId: UUID): CallEntity {
        val call = CallEntity.findById(callId) ?: throw ApiException.notFound("звонок не найден")
        if (call.status == "ended") throw ApiException(410, "call_ended", "звонок уже завершён")
        return call
    }

    private fun checkChat(chatId: UUID, callId: UUID) {
        val call = CallEntity.findById(callId)
        if (call == null || call.chatId != chatId) throw CallException("звонок не найден")
    }

    private fun markLeft(call: CallEntity, p: CallParticipant, state: String) {
        val wasAccepted = p.state == "accepted"
        p.state = state
        p.leftAt = Instant.now()
        p.connected = false
        if (wasAccepted) publish(call.chatId, FrameTypes.CALL_LEFT, CallLeftOut(call.chatId, call.id, p.id.userId))
    }

    /**
     * Личка: конец, когда «живых» (звонят + в звонке) осталось ≤ 1.
     * Группа: конец, когда в звонке не осталось ни одного принявшего.
     * true — звонок завершён.
     */
    private fun maybeEnd(call: CallEntity, reason: String): Boolean {
        val direct = chats.roomType(call.chatId) == "direct"
        val accepted = CallParticipant.count("id.callId = ?1 and state = 'accepted'", call.id)
        val live = CallParticipant.count("id.callId = ?1 and state in ?2", call.id, LIVE_STATES)
        val over = if (direct) live <= 1 else accepted == 0L
        if (over) end(call, reason)
        return over
    }

    private fun end(call: CallEntity, reason: String) {
        call.status = "ended"
        call.endedAt = Instant.now()
        call.endReason = reason
        CallParticipant.list("id.callId = ?1 and state in ?2", call.id, LIVE_STATES).forEach {
            it.state = if (it.state == "invited") "missed" else "left"
            it.leftAt = Instant.now()
            it.connected = false
        }
        // всех отключить из комнаты (оставшийся в личке не должен висеть один)
        call.roomName?.let { lk.deleteRoom(it) }
        publish(call.chatId, FrameTypes.CALL_ENDED, CallEndedOut(call.chatId, call.id, reason))
    }

    private fun pushUpdated(call: CallEntity) {
        // кадр одинаковый для всех: canKick считаем для начавшего, фронт сверяет сам
        publish(call.chatId, CALL_UPDATED, render(call, call.startedBy))
    }

    private fun publish(chatId: UUID, type: String, payload: Any) {
        bus.publishToChat(chatId, Envelope(t = type, d = mapper.valueToTree(payload)))
    }
}

@ApplicationScoped
class CallJobs(private val calls: CallService, private val lease: JobLease) {
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun expire() {
        if (lease.acquire("call-expire", java.time.Duration.ofSeconds(30))) calls.expireRinging()
    }

    @Scheduled(every = "20s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun reconcile() {
        if (lease.acquire("call-reconcile", java.time.Duration.ofMinutes(1))) calls.reconcile()
    }

    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun purge() {
        if (lease.acquire("call-purge", java.time.Duration.ofMinutes(4))) calls.purgeOldSignals()
    }
}
