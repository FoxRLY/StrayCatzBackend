package org.example.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.bus.EventBus
import org.example.domain.Chat
import org.example.domain.Community
import org.example.domain.CommunityEvent
import org.example.domain.UserCosmetics
import org.example.proto.Envelope
import org.example.rest.ApiException
import org.example.rest.LiveKitConnectOut
import org.example.rest.VoiceChannelIn
import org.example.rest.VoiceChannelOut
import org.example.rest.VoiceCommunityOut
import org.example.rest.VoiceJoinOut
import org.example.rest.VoicePeerOut
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Голосовые каналы сообществ — «мини-дискорд» на LiveKit.
 *
 *  - Канал = постоянная комната LiveKit `voice-<id>`: зашёл — говоришь, вышел — ушёл,
 *    без звонков и «звенит». Человек — максимум в одном голосовом канале сразу:
 *    вход в другой выкидывает из предыдущего.
 *  - Режимы: talk — микрофон, камера, демонстрация экрана («мини-стрим»);
 *    watch — только смотреть и слушать (до [MAX_WATCHERS]), без микрофона.
 *  - Кто в канале, видят все, кто открыл сообщество (сокет `community.open`), —
 *    кадр `voice.updated`. Голос обсуждения дополнительно уходит в чат обсуждения.
 *  - Модерация (admin/owner сообщества): заглушить (LiveKit сам снимает микрофон,
 *    при повторном входе микрофона в токене нет) и выгнать.
 *  - Кто подключён, стримит, с камерой — по вебхукам LiveKit + сверка раз в 20 с.
 */
@ApplicationScoped
class VoiceService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bus: EventBus,
    private val lk: LiveKitClient,
    private val communities: CommunityService,
    private val profiles: UserProfileService,
    @ConfigProperty(name = "straycatz.voice.max-watchers", defaultValue = "500") private val maxWatchers: Int,
) {
    companion object {
        const val ROOM_PREFIX = "voice-"
        const val MAX_CHANNELS = 30
        val DROP_GRACE: Duration = Duration.ofSeconds(30)
        const val FRAME_UPDATED = "voice.updated"
        const val FRAME_REMOVED = "voice.removed"
        private val TALK_SOURCES = listOf("microphone", "camera", "screen_share", "screen_share_audio")
        private val MUTED_SOURCES = listOf("camera", "screen_share", "screen_share_audio")
    }

    private data class Ch(
        val id: UUID, val communityId: UUID, val kind: String, val refId: UUID?, val name: String,
        val position: Int, val maxTalkers: Int,
    )

    // ================================================================ чтение

    /** Вкладка «Голос»: каналы + где сейчас кто-то говорит в событиях и обсуждениях. */
    @Transactional
    fun list(me: UUID, slug: String): VoiceCommunityOut {
        val c = communities.bySlug(slug)
        var channels = channelsOf(c.id, "channel")
        // у сообщества ещё нет каналов (создано после V18) — заводим «общий»
        if (channels.isEmpty() && c.source == null) {
            insertChannel(c.id, "channel", null, "общий", 0, null)
            channels = channelsOf(c.id, "channel")
        }
        val active = channelsOf(c.id, null).filter { it.kind != "channel" }
        val all = channels + active
        val peers = peersOf(all.map { it.id })
        return VoiceCommunityOut(
            communityId = c.id,
            channels = channels.map { render(it, peers[it.id].orEmpty()) },
            active = active.filter { peers[it.id].orEmpty().isNotEmpty() }.map { render(it, peers[it.id].orEmpty()) },
            canManage = isAdmin(c, me),
            myChannelId = myChannel(me),
        )
    }

    @Transactional
    fun get(id: UUID): VoiceChannelOut {
        val ch = channel(id)
        return render(ch, peersOf(listOf(id))[id].orEmpty())
    }

    /** Голос события: создаётся при первом обращении. */
    @Transactional
    fun forEvent(me: UUID, eventId: UUID): VoiceChannelOut {
        val e = CommunityEvent.findById(eventId) ?: throw ApiException.notFound("событие не найдено")
        if (e.cancelledAt != null) throw ApiException(410, "event_cancelled", "событие отменено")
        val ch = byRef("event", eventId) ?: run {
            insertChannel(e.communityId, "event", eventId, e.title.take(60), 0, me)
            byRef("event", eventId)!!
        }
        return render(ch, peersOf(listOf(ch.id))[ch.id].orEmpty())
    }

    /** Голос обсуждения (чат с community_id): создаётся при первом обращении. */
    @Transactional
    fun forDiscussion(me: UUID, chatId: UUID): VoiceChannelOut {
        val chat = Chat.findById(chatId)
        if (chat == null || chat.isDeleted || chat.communityId == null) throw ApiException.notFound("обсуждение не найдено")
        val ch = byRef("discussion", chatId) ?: run {
            insertChannel(chat.communityId!!, "discussion", chatId, (chat.name ?: "обсуждение").take(60), 0, me)
            byRef("discussion", chatId)!!
        }
        return render(ch, peersOf(listOf(ch.id))[ch.id].orEmpty())
    }

    // ================================================================ управление (admin)

    @Transactional
    fun create(me: UUID, slug: String, req: VoiceChannelIn): VoiceChannelOut {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "admin")
        val n = channelsOf(c.id, "channel").size
        if (n >= MAX_CHANNELS) throw ApiException.badRequest("too_many_channels", "не больше $MAX_CHANNELS голосовых каналов")
        val id = insertChannel(c.id, "channel", null, name(req.name), n, me, req.maxTalkers)
        return get(id).also { pushUpdated(channel(id)) }
    }

    @Transactional
    fun update(me: UUID, id: UUID, req: VoiceChannelIn): VoiceChannelOut {
        val ch = channel(id)
        communities.requireRole(community(ch), me, "admin")
        req.name?.let { exec("update voice_channel set name = ?2 where id = ?1", id, name(it)) }
        req.maxTalkers?.let { exec("update voice_channel set max_talkers = ?2 where id = ?1", id, it.coerceIn(2, 100)) }
        return get(id).also { pushUpdated(channel(id)) }
    }

    /** Удалить канал: всех из него отключит. Голос событий/обсуждений тоже можно — пересоздастся при входе. */
    @Transactional
    fun delete(me: UUID, id: UUID) {
        val ch = channel(id)
        communities.requireRole(community(ch), me, "admin")
        exec("update voice_channel set deleted_at = now() where id = ?1", id)
        exec("delete from voice_presence where channel_id = ?1", id)
        lk.deleteRoom(room(id))
        bus.publishToRoomViewers(ch.communityId, frame(FRAME_REMOVED, mapOf("communityId" to ch.communityId, "channelId" to id)))
    }

    @Transactional
    fun reorder(me: UUID, slug: String, ids: List<UUID>): VoiceCommunityOut {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "admin")
        val mine = channelsOf(c.id, "channel").map { it.id }
        if (ids.toSet() != mine.toSet() || ids.size != mine.size) {
            throw ApiException.badRequest("invalid_order", "channelIds — все каналы сообщества, каждый по разу")
        }
        ids.forEachIndexed { i, id -> exec("update voice_channel set position = ?2 where id = ?1", id, i) }
        return list(me, slug)
    }

    // ================================================================ войти / выйти

    @Transactional
    fun join(me: UUID, id: UUID, modeRaw: String?): VoiceJoinOut {
        if (!lk.configured()) throw ApiException(503, "calls_disabled", "голос не настроен: нужен straycatz.livekit.api-secret")
        val ch = channel(id)
        val c = community(ch)
        if (c.source != null) throw ApiException(403, "mirror_readonly", "в зеркале Telegram голоса нет")
        if (communities.roleOf(c.id, me) == null) throw ApiException.forbidden("нужно вступить в сообщество")
        val mode = (modeRaw ?: "talk").lowercase()
        if (mode != "talk" && mode != "watch") throw ApiException.badRequest("invalid_mode", "mode: talk или watch")

        // человек — в одном голосовом канале: выходим из остальных
        @Suppress("UNCHECKED_CAST")
        val elsewhere = em.createNativeQuery("select channel_id from voice_presence where user_id = ?1 and channel_id <> ?2", UUID::class.java)
            .setParameter(1, me).setParameter(2, id).resultList as List<UUID>
        elsewhere.forEach { other -> dropPeer(other, me, kick = true) }

        val counts = counts(id, me)
        if (mode == "talk" && counts.first >= ch.maxTalkers) {
            throw ApiException(409, "voice_full", "в канале уже ${ch.maxTalkers} говорящих — можно зайти послушать (mode=watch)")
        }
        if (mode == "watch" && counts.second >= maxWatchers) throw ApiException(409, "voice_full", "слишком много зрителей")

        val muted = em.createNativeQuery("select 1 from voice_mute where channel_id = ?1 and user_id = ?2")
            .setParameter(1, id).setParameter(2, me).resultList.isNotEmpty()
        em.createNativeQuery(
            """
            insert into voice_presence (channel_id, user_id, mode, joined_at) values (?1, ?2, ?3, now())
            on conflict (channel_id, user_id) do update set mode = excluded.mode, disconnected_at = null
            """.trimIndent(),
        ).setParameter(1, id).setParameter(2, me).setParameter(3, mode).executeUpdate()

        // комната: пустая живёт 30 с, места — говорящим и зрителям (повторный CreateRoom безопасен)
        if (counts.first + counts.second == 0) lk.createRoom(room(id), 30, 15, ch.maxTalkers + maxWatchers, "{\"channelId\":\"$id\"}")

        val user = profiles.shorts(listOf(me))[me] ?: throw ApiException.notFound("пользователь не найден")
        val cos = UserCosmetics.findById(me)
        val role = communities.roleOf(c.id, me)
        val metadata = mapper.writeValueAsString(
            mapOf("username" to user.username, "avatar" to cos?.avatar, "color" to cos?.color, "role" to role, "mode" to mode),
        )
        val sources = when {
            mode == "watch" -> emptyList()
            muted -> MUTED_SOURCES
            else -> TALK_SOURCES
        }
        val token = lk.participantToken(me.toString(), user.username, metadata, room(id), sources)
        pushUpdated(ch)
        return VoiceJoinOut(get(id), LiveKitConnectOut(lk.clientUrl, token, room(id), me.toString()))
    }

    @Transactional
    fun leave(me: UUID, id: UUID) {
        dropPeer(id, me, kick = true)
    }

    /** Выгнать из канала (admin). Вернуться может — для «навсегда» есть исключение из сообщества. */
    @Transactional
    fun kick(me: UUID, id: UUID, userId: UUID) {
        val ch = channel(id)
        communities.requireRole(community(ch), me, "admin")
        dropPeer(id, userId, kick = true)
    }

    /** Заглушить / вернуть микрофон (admin). Заглушённый и при повторном входе будет без микрофона. */
    @Transactional
    fun mute(me: UUID, id: UUID, userId: UUID, muted: Boolean): VoiceChannelOut {
        val ch = channel(id)
        communities.requireRole(community(ch), me, "admin")
        if (muted) {
            em.createNativeQuery("insert into voice_mute (channel_id, user_id, muted_by) values (?1, ?2, ?3) on conflict do nothing")
                .setParameter(1, id).setParameter(2, userId).setParameter(3, me).executeUpdate()
        } else {
            exec2("delete from voice_mute where channel_id = ?1 and user_id = ?2", id, userId)
        }
        lk.updateSources(room(id), userId.toString(), if (muted) MUTED_SOURCES else TALK_SOURCES)
        pushUpdated(ch)
        return get(id)
    }

    // ================================================================ вебхуки / сверка

    /** participant_joined / participant_left / track_published / track_unpublished. */
    @Transactional
    fun onWebhook(event: JsonNode) {
        val roomName = event.path("room").path("name").asText()
        val id = runCatching { UUID.fromString(roomName.removePrefix(ROOM_PREFIX)) }.getOrNull() ?: return
        val identity = event.path("participant").path("identity").asText()
        val userId = runCatching { UUID.fromString(identity) }.getOrNull() ?: return
        val ch = channelOrNull(id)
        val exists = em.createNativeQuery("select 1 from voice_presence where channel_id = ?1 and user_id = ?2")
            .setParameter(1, id).setParameter(2, userId).resultList.isNotEmpty()
        when (event.path("event").asText()) {
            "participant_joined" -> {
                if (ch == null || !exists) { lk.removeParticipant(roomName, identity); return } // вошёл со старым токеном
                exec2("update voice_presence set connected = true, disconnected_at = null where channel_id = ?1 and user_id = ?2", id, userId)
            }
            "participant_left" -> {
                if (!exists) return
                exec2(
                    "update voice_presence set connected = false, streaming = false, camera = false, disconnected_at = now() " +
                        "where channel_id = ?1 and user_id = ?2", id, userId,
                )
            }
            "track_published", "track_unpublished" -> {
                if (!exists) return
                val on = event.path("event").asText() == "track_published"
                when (event.path("track").path("source").asText()) {
                    "SCREEN_SHARE" -> exec3("update voice_presence set streaming = ?3 where channel_id = ?1 and user_id = ?2", id, userId, on)
                    "CAMERA" -> exec3("update voice_presence set camera = ?3 where channel_id = ?1 and user_id = ?2", id, userId, on)
                    else -> return
                }
            }
            else -> return
        }
        ch?.let { pushUpdated(it) }
    }

    /** Раз в 20 с: кто реально в комнатах каналов, где кто-то числится. */
    @Transactional
    fun reconcile() {
        if (!lk.configured()) return
        @Suppress("UNCHECKED_CAST")
        val busy = em.createNativeQuery("select distinct channel_id from voice_presence", UUID::class.java).resultList as List<UUID>
        val dropBefore = Instant.now().minus(DROP_GRACE)
        busy.forEach { id ->
            val inRoom = lk.listParticipantTracks(room(id)) ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            val rows = em.createNativeQuery(
                "select user_id, connected, streaming, camera, coalesce(disconnected_at, joined_at) from voice_presence where channel_id = ?1",
            ).setParameter(1, id).resultList as List<Array<Any?>>
            var changed = false
            rows.forEach { r ->
                val uid = r[0] as UUID
                val tracks = inRoom[uid.toString()]
                if (tracks == null) {
                    if (toInstant(r[4]).isBefore(dropBefore)) { exec2("delete from voice_presence where channel_id = ?1 and user_id = ?2", id, uid); changed = true }
                    else if (r[1] == true) {
                        exec2("update voice_presence set connected = false, disconnected_at = now() where channel_id = ?1 and user_id = ?2", id, uid)
                        changed = true
                    }
                } else {
                    val streaming = "SCREEN_SHARE" in tracks
                    val camera = "CAMERA" in tracks
                    if (r[1] != true || r[2] != streaming || r[3] != camera) {
                        em.createNativeQuery(
                            "update voice_presence set connected = true, disconnected_at = null, streaming = ?3, camera = ?4 where channel_id = ?1 and user_id = ?2",
                        ).setParameter(1, id).setParameter(2, uid).setParameter(3, streaming).setParameter(4, camera).executeUpdate()
                        changed = true
                    }
                }
            }
            if (changed) channelOrNull(id)?.let { pushUpdated(it) }
        }
    }

    // ------------------------------------------------------------------

    private fun dropPeer(id: UUID, userId: UUID, kick: Boolean) {
        val n = exec2("delete from voice_presence where channel_id = ?1 and user_id = ?2", id, userId)
        if (n == 0) return
        if (kick) lk.removeParticipant(room(id), userId.toString())
        channelOrNull(id)?.let { pushUpdated(it) }
    }

    private fun pushUpdated(ch: Ch) {
        val out = render(ch, peersOf(listOf(ch.id))[ch.id].orEmpty())
        val f = frame(FRAME_UPDATED, mapOf("communityId" to ch.communityId, "channel" to out))
        bus.publishToRoomViewers(ch.communityId, f)
        if (ch.kind == "discussion" && ch.refId != null) bus.publishToChat(ch.refId, f)
    }

    private fun frame(t: String, d: Any) = Envelope(t = t, d = mapper.valueToTree(d))

    private fun render(ch: Ch, peers: List<VoicePeerOut>) = VoiceChannelOut(
        id = ch.id, communityId = ch.communityId, kind = ch.kind, refId = ch.refId, name = ch.name,
        position = ch.position, maxTalkers = ch.maxTalkers,
        peers = peers,
        talkers = peers.count { it.mode == "talk" },
        watchers = peers.count { it.mode == "watch" },
        live = peers.any { it.streaming },
    )

    @Suppress("UNCHECKED_CAST")
    private fun peersOf(ids: List<UUID>): Map<UUID, List<VoicePeerOut>> {
        if (ids.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery(
            """
            select p.channel_id, p.user_id, p.mode, p.connected, p.streaming, p.camera,
                   exists(select 1 from voice_mute m where m.channel_id = p.channel_id and m.user_id = p.user_id), p.joined_at
            from voice_presence p where p.channel_id in (?1)
            order by (mode = 'talk') desc, streaming desc, joined_at
            """.trimIndent(),
        ).setParameter(1, ids).resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.map { it[1] as UUID })
        return rows.groupBy({ it[0] as UUID }) { r ->
            VoicePeerOut(users[r[1] as UUID], r[2] as String, r[3] == true, r[4] == true, r[5] == true, r[6] == true, toInstant(r[7]))
        }
    }

    /** (говорящих, зрителей) без меня. */
    private fun counts(id: UUID, me: UUID): Pair<Int, Int> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            "select mode, count(*) from voice_presence where channel_id = ?1 and user_id <> ?2 group by mode",
        ).setParameter(1, id).setParameter(2, me).resultList as List<Array<Any?>>
        val m = rows.associate { (it[0] as String) to (it[1] as Number).toInt() }
        return (m["talk"] ?: 0) to (m["watch"] ?: 0)
    }

    private fun myChannel(me: UUID): UUID? =
        em.createNativeQuery("select channel_id from voice_presence where user_id = ?1", UUID::class.java)
            .setParameter(1, me).resultList.firstOrNull() as UUID?

    @Suppress("UNCHECKED_CAST")
    private fun channelsOf(communityId: UUID, kind: String?): List<Ch> {
        val q = em.createNativeQuery(
            "select id, community_id, kind, ref_id, name, position, max_talkers from voice_channel " +
                "where community_id = ?1 and deleted_at is null" + (if (kind != null) " and kind = ?2" else "") +
                " order by position, created_at",
        ).setParameter(1, communityId)
        if (kind != null) q.setParameter(2, kind)
        return (q.resultList as List<Array<Any?>>).map(::ch)
    }

    @Suppress("UNCHECKED_CAST")
    private fun byRef(kind: String, refId: UUID): Ch? =
        (em.createNativeQuery(
            "select id, community_id, kind, ref_id, name, position, max_talkers from voice_channel where kind = ?1 and ref_id = ?2 and deleted_at is null",
        ).setParameter(1, kind).setParameter(2, refId).resultList as List<Array<Any?>>).firstOrNull()?.let(::ch)

    @Suppress("UNCHECKED_CAST")
    private fun channelOrNull(id: UUID): Ch? =
        (em.createNativeQuery(
            "select id, community_id, kind, ref_id, name, position, max_talkers from voice_channel where id = ?1 and deleted_at is null",
        ).setParameter(1, id).resultList as List<Array<Any?>>).firstOrNull()?.let(::ch)

    private fun channel(id: UUID): Ch = channelOrNull(id) ?: throw ApiException.notFound("голосовой канал не найден")

    private fun ch(r: Array<Any?>) = Ch(
        r[0] as UUID, r[1] as UUID, r[2] as String, r[3] as UUID?, r[4] as String, (r[5] as Number).toInt(), (r[6] as Number).toInt(),
    )

    private fun insertChannel(communityId: UUID, kind: String, refId: UUID?, name: String, position: Int, by: UUID?, maxTalkers: Int? = null): UUID {
        val id = UUID.randomUUID()
        em.createNativeQuery(
            """
            insert into voice_channel (id, community_id, kind, ref_id, name, position, max_talkers, created_by)
            values (?1, ?2, ?3, cast(nullif(?4, '') as uuid), ?5, ?6, ?7, cast(nullif(?8, '') as uuid))
            on conflict do nothing
            """.trimIndent(),
        ).setParameter(1, id).setParameter(2, communityId).setParameter(3, kind).setParameter(4, refId?.toString() ?: "")
            .setParameter(5, name).setParameter(6, position).setParameter(7, (maxTalkers ?: 25).coerceIn(2, 100))
            .setParameter(8, by?.toString() ?: "").executeUpdate()
        return id
    }

    private fun community(ch: Ch): Community = Community.findById(ch.communityId) ?: throw ApiException.notFound("сообщество не найдено")

    private fun isAdmin(c: Community, me: UUID) = communities.roleOf(c.id, me) in setOf("admin", "owner")

    private fun name(v: String?): String {
        val t = v?.trim().orEmpty()
        if (t.isEmpty() || t.length > 60) throw ApiException.badRequest("invalid_name", "название: 1–60 символов")
        return t
    }

    private fun room(id: UUID) = "$ROOM_PREFIX$id"

    private fun exec(sql: String, id: UUID, v: Any) {
        em.createNativeQuery(sql).setParameter(1, id).setParameter(2, v).executeUpdate()
    }

    private fun exec(sql: String, id: UUID) {
        em.createNativeQuery(sql).setParameter(1, id).executeUpdate()
    }

    private fun exec2(sql: String, a: UUID, b: UUID): Int =
        em.createNativeQuery(sql).setParameter(1, a).setParameter(2, b).executeUpdate()

    private fun exec3(sql: String, a: UUID, b: UUID, c: Any) {
        em.createNativeQuery(sql).setParameter(1, a).setParameter(2, b).setParameter(3, c).executeUpdate()
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}

@ApplicationScoped
class VoiceJobs(private val voice: VoiceService, private val lease: JobLease) {
    @Scheduled(every = "20s", delayed = "40s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun reconcile() {
        if (lease.acquire("voice-reconcile", java.time.Duration.ofMinutes(1))) voice.reconcile()
    }
}
