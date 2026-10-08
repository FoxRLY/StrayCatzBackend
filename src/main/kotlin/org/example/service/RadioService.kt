package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.domain.Community
import org.example.proto.Envelope
import org.example.rest.ApiException
import org.example.rest.RadioIn
import org.example.rest.RadioMyOut
import org.example.rest.RadioNowOut
import org.example.rest.RadioOut
import org.example.rest.RadioPlayOut
import org.example.rest.RadioQueueItemOut
import org.example.rest.RadioReplayPageOut
import org.example.rest.RadioSessionDetailOut
import org.example.rest.RadioSessionOut
import org.example.rest.TrackOut
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Радио сообщества.
 *
 *  - Станция одна на сообщество, создаётся при первом открытии вкладки.
 *  - Звук сервер не микширует и не стримит: все слушатели играют один и тот же трек
 *    с одной и той же позиции (`startedAt` + часы сервера). Файл — обычный `audioUrl` трека
 *    (редирект на CDN/S3), поэтому 10 или 10 000 слушателей серверу стоят одинаково.
 *  - Что играет дальше: очередь (диджеи + заказы слушателей) → если пусто и включён автодиджей —
 *    случайный трек из музыки сообщества, не из последних 20 сыгранных → иначе тишина.
 *  - Смена треков — тикер раз в секунду на одной ноде (аренда `radio-tick`),
 *    следующий трек начинается ровно в `endsAt` предыдущего (без накопления сдвига).
 *  - Эфир (session) — от «включить» до «выключить». Что играло — `radio_play`: это запись эфира
 *    для вкладки «Реплеи» (треклист со временем, кто заказал, что пропустили).
 *  - Кадр `radio.state` (RadioOut без `my`) уходит тем, кто открыл станцию (`radio.open`)
 *    и страницу сообщества (`community.open`).
 *  - Эфир без слушателей дольше 30 минут выключается сам.
 */
@ApplicationScoped
class RadioService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bus: EventBus,
    private val communities: CommunityService,
    private val profiles: UserProfileService,
    private val music: MusicService,
    private val media: MediaService,
) {
    companion object {
        const val FRAME_STATE = "radio.state"
        const val MAX_QUEUE = 200
        const val MAX_MY_REQUESTS = 2
        const val SHOW_QUEUE = 50
        const val FRAME_QUEUE = 20
        const val MAX_PAGE = 50
        const val RECENT_NO_REPEAT = 20
        val LISTENER_TTL: Duration = Duration.ofMinutes(2)
        val IDLE_STOP: Duration = Duration.ofMinutes(30)
        /** Пауза между треками, чтобы у всех успел загрузиться следующий. */
        const val GAP_MS = 800L
    }

    private data class St(
        val id: UUID, val communityId: UUID, val name: String, val description: String?, val autoDj: Boolean,
        val requestsOpen: Boolean, val status: String, val sessionId: UUID?, val playId: UUID?, val endsAt: Instant?,
    )

    // ================================================================ чтение

    @Transactional
    fun forCommunity(me: UUID, slug: String): RadioOut {
        val c = communities.bySlug(slug)
        if (c.source != null) throw ApiException(404, "radio_unavailable", "у зеркала Telegram нет радио")
        val st = stationOf(c.id) ?: run {
            em.createNativeQuery("insert into radio_station (id, community_id, name) values (?1, ?2, ?3) on conflict (community_id) do nothing")
                .setParameter(1, UUID.randomUUID()).setParameter(2, c.id).setParameter(3, (c.name + " FM").take(80)).executeUpdate()
            stationOf(c.id)!!
        }
        return render(st, me, SHOW_QUEUE)
    }

    @Transactional
    fun get(me: UUID, id: UUID): RadioOut = render(station(id), me, SHOW_QUEUE)

    /** «Реплеи»: прошедшие эфиры, новые сверху. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun replays(slug: String, before: Instant?, limit: Int): RadioReplayPageOut {
        val c = communities.bySlug(slug)
        val size = limit.coerceIn(1, MAX_PAGE)
        val ids = em.createNativeQuery(
            """
            select s.id from radio_session s join radio_station r on r.id = s.station_id
            where r.community_id = ?1 and s.deleted_at is null and s.ended_at is not null and s.started_at < ?2
            order by s.started_at desc limit ?3
            """.trimIndent(), UUID::class.java,
        ).setParameter(1, c.id).setParameter(2, before ?: Instant.now().plusSeconds(60)).setParameter(3, size + 1)
            .resultList as List<UUID>
        val items = sessions(ids.take(size))
        return RadioReplayPageOut(items, ids.size > size, if (ids.size > size) items.lastOrNull()?.startedAt else null)
    }

    /** Эфир с треклистом. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun session(me: UUID, id: UUID): RadioSessionDetailOut {
        val s = sessions(listOf(id)).firstOrNull() ?: throw ApiException.notFound("эфир не найден")
        val rows = em.createNativeQuery(
            """
            select p.id, p.track_id, p.started_at, p.ended_at, p.requested_by, p.auto,
                   extract(epoch from p.started_at - s.started_at),
                   extract(epoch from coalesce(p.ended_at, now()) - p.started_at)
            from radio_play p join radio_session s on s.id = p.session_id
            where p.session_id = ?1 order by p.started_at
            """.trimIndent(),
        ).setParameter(1, id).resultList as List<Array<Any?>>
        val tracks = music.renderByIds(rows.map { it[1] as UUID }, me).associateBy { it.id }
        val users = profiles.shorts(rows.mapNotNull { it[4] as UUID? })
        val out = rows.map { r ->
            val t = tracks[r[1] as UUID]
            val played = (r[7] as Number).toLong()
            RadioPlayOut(
                id = r[0] as UUID, track = t, offsetSec = (r[6] as Number).toLong(), startedAt = toInstant(r[2]),
                playedSec = played, skipped = r[3] != null && t != null && played < t.durationSec - 5,
                requestedBy = (r[4] as UUID?)?.let { users[it] }, auto = r[5] == true,
            )
        }
        return RadioSessionDetailOut(s, out, isAdmin(s.communityId, me))
    }

    // ================================================================ настройки (admin)

    @Transactional
    fun update(me: UUID, slug: String, req: RadioIn): RadioOut {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "admin")
        val st = stationOf(c.id) ?: forCommunity(me, slug).let { stationOf(c.id)!! }
        req.name?.let {
            val n = it.trim()
            if (n.isEmpty() || n.length > 80) throw ApiException.badRequest("invalid_name", "название: 1–80 символов")
            exec("update radio_station set name = ?2 where id = ?1", st.id, n)
        }
        req.description?.let {
            val d = it.trim()
            if (d.length > 500) throw ApiException.badRequest("invalid_description", "описание длиннее 500")
            exec("update radio_station set description = nullif(?2, '') where id = ?1", st.id, d)
        }
        req.autoDj?.let { exec("update radio_station set auto_dj = ?2 where id = ?1", st.id, it) }
        req.requestsOpen?.let { exec("update radio_station set requests_open = ?2 where id = ?1", st.id, it) }
        val now = station(st.id)
        // включили автодиджея в тишине — сразу что-нибудь поставить
        if (now.status == "on" && now.playId == null && now.autoDj) return advanceLocked(now.id, me) ?: get(me, st.id)
        push(now)
        return render(now, me, SHOW_QUEUE)
    }

    @Transactional
    fun setDj(me: UUID, id: UUID, userId: UUID, on: Boolean): RadioOut {
        val st = station(id)
        requireAdmin(st, me)
        if (on) {
            if (communities.roleOf(st.communityId, userId) == null) throw ApiException.badRequest("not_member", "диджеем можно сделать только участника")
            em.createNativeQuery("insert into radio_dj (station_id, user_id) values (?1, ?2) on conflict do nothing")
                .setParameter(1, id).setParameter(2, userId).executeUpdate()
        } else {
            em.createNativeQuery("delete from radio_dj where station_id = ?1 and user_id = ?2")
                .setParameter(1, id).setParameter(2, userId).executeUpdate()
        }
        val now = station(id)
        push(now)
        return render(now, me, SHOW_QUEUE)
    }

    @Transactional
    fun renameSession(me: UUID, sessionId: UUID, title: String?): RadioSessionOut {
        val s = sessions(listOf(sessionId)).firstOrNull() ?: throw ApiException.notFound("эфир не найден")
        if (!isAdmin(s.communityId, me)) throw ApiException.forbidden("переименовать эфир может админ сообщества")
        val t = title?.trim().orEmpty()
        if (t.isEmpty() || t.length > 120) throw ApiException.badRequest("invalid_title", "название: 1–120 символов")
        exec("update radio_session set title = ?2 where id = ?1", sessionId, t)
        return sessions(listOf(sessionId)).first()
    }

    @Transactional
    fun deleteSession(me: UUID, sessionId: UUID) {
        val s = sessions(listOf(sessionId)).firstOrNull() ?: throw ApiException.notFound("эфир не найден")
        if (!isAdmin(s.communityId, me)) throw ApiException.forbidden("удалить эфир может админ сообщества")
        if (s.live) throw ApiException.conflict("live", "эфир ещё идёт — сначала выключи")
        exec("update radio_session set deleted_at = now() where id = ?1", sessionId)
    }

    // ================================================================ эфир (диджей)

    @Transactional
    fun start(me: UUID, id: UUID, title: String?): RadioOut {
        val st = lock(id)
        requireDj(st, me)
        if (st.status == "on") return render(st, me, SHOW_QUEUE)
        val t = title?.trim()?.takeIf { it.isNotEmpty() }?.take(120) ?: "Эфир «${st.name}»"
        val sid = UUID.randomUUID()
        em.createNativeQuery("insert into radio_session (id, station_id, title, started_by) values (?1, ?2, ?3, ?4)")
            .setParameter(1, sid).setParameter(2, id).setParameter(3, t).setParameter(4, me).executeUpdate()
        em.createNativeQuery("update radio_station set status = 'on', session_id = ?2, play_id = null, ends_at = null where id = ?1")
            .setParameter(1, id).setParameter(2, sid).executeUpdate()
        return advanceLocked(id, me) ?: get(me, id)
    }

    @Transactional
    fun stop(me: UUID, id: UUID): RadioOut {
        val st = lock(id)
        requireDj(st, me)
        stopLocked(st)
        return get(me, id)
    }

    @Transactional
    fun skip(me: UUID, id: UUID): RadioOut {
        val st = lock(id)
        requireDj(st, me)
        if (st.status != "on") throw ApiException.conflict("off_air", "эфир выключен")
        return advanceLocked(id, me) ?: get(me, id)
    }

    /** Диджей — в очередь; слушатель — заказ. */
    @Transactional
    fun enqueue(me: UUID, id: UUID, trackId: UUID?, next: Boolean): RadioOut {
        if (trackId == null) throw ApiException.badRequest("track_required", "нужен trackId")
        val st = lock(id)
        val dj = isDj(st, me)
        if (!dj) {
            if (!st.requestsOpen) throw ApiException.forbidden("заказы на этой станции закрыты")
            if (myRequests(id, me) >= MAX_MY_REQUESTS) throw ApiException(429, "too_many_requests", "не больше $MAX_MY_REQUESTS заказов в очереди")
        }
        val ok = (em.createNativeQuery("select count(*) from track where id = ?1 and deleted_at is null").setParameter(1, trackId).singleResult as Number).toLong()
        if (ok == 0L) throw ApiException.notFound("трек не найден")
        val size = (em.createNativeQuery("select count(*) from radio_queue where station_id = ?1").setParameter(1, id).singleResult as Number).toLong()
        if (size >= MAX_QUEUE) throw ApiException(429, "queue_full", "в очереди уже $MAX_QUEUE треков")
        val dup = (em.createNativeQuery("select count(*) from radio_queue where station_id = ?1 and track_id = ?2")
            .setParameter(1, id).setParameter(2, trackId).singleResult as Number).toLong()
        if (dup > 0) throw ApiException.conflict("already_queued", "этот трек уже в очереди")
        val agg = if (next && dj) "coalesce(min(position), 1) - 1" else "coalesce(max(position), 0) + 1"
        em.createNativeQuery(
            "insert into radio_queue (id, station_id, track_id, position, added_by, requested) " +
                "select ?1, ?2, ?3, $agg, ?4, ?5 from radio_queue where station_id = ?2",
        ).setParameter(1, UUID.randomUUID()).setParameter(2, id).setParameter(3, trackId).setParameter(4, me).setParameter(5, !dj)
            .executeUpdate()
        // тишина в эфире — запускаем сразу
        if (st.status == "on" && st.playId == null) return advanceLocked(id, me) ?: get(me, id)
        val now = station(id)
        push(now)
        return render(now, me, SHOW_QUEUE)
    }

    /** Убрать из очереди: диджей — любое, слушатель — свой заказ. */
    @Transactional
    fun dequeue(me: UUID, id: UUID, itemId: UUID): RadioOut {
        val st = lock(id)
        val by = em.createNativeQuery("select added_by from radio_queue where id = ?1 and station_id = ?2", UUID::class.java)
            .setParameter(1, itemId).setParameter(2, id).resultList.firstOrNull() as UUID?
            ?: throw ApiException.notFound("в очереди такого нет")
        if (by != me && !isDj(st, me)) throw ApiException.forbidden("убрать можно только свой заказ")
        exec("delete from radio_queue where id = ?1", itemId)
        push(st)
        return render(st, me, SHOW_QUEUE)
    }

    @Transactional
    fun reorder(me: UUID, id: UUID, ids: List<UUID>): RadioOut {
        val st = lock(id)
        requireDj(st, me)
        val order = ids.distinct()
        if (order.size > MAX_QUEUE) throw ApiException.badRequest("too_many", "не больше $MAX_QUEUE")
        // сначала перечисленные по порядку, остальные — следом в прежнем порядке
        exec("update radio_queue set position = position + 1000000 where station_id = ?1", id)
        order.forEachIndexed { i, itemId ->
            em.createNativeQuery("update radio_queue set position = ?3 where id = ?1 and station_id = ?2")
                .setParameter(1, itemId).setParameter(2, id).setParameter(3, (i + 1).toDouble()).executeUpdate()
        }
        exec(
            """
            update radio_queue q set position = o.rn from (
                select id, row_number() over (order by position, created_at) as rn from radio_queue where station_id = ?1
            ) o where q.id = o.id
            """.trimIndent(), id,
        )
        push(st)
        return render(st, me, SHOW_QUEUE)
    }

    /** Пинг слушателя раз в минуту, пока играет. Возвращает свежее состояние (синхронизация). */
    @Transactional
    fun listen(me: UUID, id: UUID): RadioOut {
        val st = station(id)
        em.createNativeQuery(
            "insert into radio_listener (station_id, user_id) values (?1, ?2) on conflict (station_id, user_id) do update set seen_at = now()",
        ).setParameter(1, id).setParameter(2, me).executeUpdate()
        st.sessionId?.let { sid ->
            em.createNativeQuery(
                """
                update radio_session set peak_listeners = greatest(peak_listeners,
                    (select count(*) from radio_listener where station_id = ?2 and seen_at > now() - interval '2 minutes'))
                where id = ?1
                """.trimIndent(),
            ).setParameter(1, sid).setParameter(2, id).executeUpdate()
        }
        return render(st, me, SHOW_QUEUE)
    }

    @Transactional
    fun unlisten(me: UUID, id: UUID) {
        em.createNativeQuery("delete from radio_listener where station_id = ?1 and user_id = ?2")
            .setParameter(1, id).setParameter(2, me).executeUpdate()
    }

    // ================================================================ тикер

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun due(): List<UUID> =
        em.createNativeQuery(
            "select id from radio_station where status = 'on' and ends_at <= now() + interval '300 milliseconds' order by ends_at limit 200",
            UUID::class.java,
        ).resultList as List<UUID>

    /** Тикер: трек доиграл — следующий. Повторная проверка под блокировкой (могли пропустить вручную). */
    @Transactional
    fun tick(id: UUID) {
        val st = lockOrNull(id) ?: return
        if (st.status != "on" || st.endsAt == null || st.endsAt.isAfter(Instant.now().plusMillis(300))) return
        advanceLocked(id, null, st.endsAt)
    }

    /** Выключить эфиры, которые никто не слушает дольше [IDLE_STOP]. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun stopIdle() {
        val ids = em.createNativeQuery(
            """
            select r.id from radio_station r join radio_session s on s.id = r.session_id
            where r.status = 'on' and s.started_at < now() - make_interval(secs => ?1)
              and not exists (select 1 from radio_listener l where l.station_id = r.id and l.seen_at > now() - make_interval(secs => ?1))
            limit 100
            """.trimIndent(), UUID::class.java,
        ).setParameter(1, IDLE_STOP.seconds.toDouble()).resultList as List<UUID>
        ids.forEach { id -> lockOrNull(id)?.let { stopLocked(it) } }
        em.createNativeQuery("delete from radio_listener where seen_at < now() - interval '1 hour'").executeUpdate()
    }

    // ------------------------------------------------------------------

    /**
     * Следующий трек. Станция уже заблокирована (select … for update).
     * [at] — когда кончился предыдущий (тикер): следующий начинается ровно тогда, без сдвига.
     */
    @Suppress("UNCHECKED_CAST")
    private fun advanceLocked(id: UUID, me: UUID?, at: Instant? = null): RadioOut? {
        val st = station(id)
        if (st.status != "on" || st.sessionId == null) return null
        val now = Instant.now()
        val start = (if (at != null && at.isAfter(now.minusSeconds(3))) at else now).plusMillis(GAP_MS)
        st.playId?.let {
            em.createNativeQuery("update radio_play set ended_at = least(?2, now()) where id = ?1 and ended_at is null")
                .setParameter(1, it).setParameter(2, start).executeUpdate()
        }
        // 1) очередь
        var next: Triple<UUID, UUID?, Boolean>? = null // track, requestedBy, auto
        repeat(5) {
            if (next != null) return@repeat
            val r = (em.createNativeQuery(
                """
                with d as (
                    delete from radio_queue where id = (select id from radio_queue where station_id = ?1 order by position, created_at limit 1)
                    returning track_id, added_by, requested
                ) select d.track_id, d.added_by, d.requested, exists(select 1 from track t where t.id = d.track_id and t.deleted_at is null) from d
                """.trimIndent(),
            ).setParameter(1, id).resultList as List<Array<Any?>>).firstOrNull() ?: return@repeat
            if (r[3] == true) next = Triple(r[0] as UUID, if (r[2] == true) r[1] as UUID else null, false)
        }
        // 2) автодиджей: музыка сообщества, не из последних 20
        if (next == null && st.autoDj) {
            val t = em.createNativeQuery(
                """
                select ct.track_id from community_track ct join track t on t.id = ct.track_id and t.deleted_at is null
                where ct.community_id = ?1
                order by (ct.track_id in (select p.track_id from radio_play p where p.station_id = ?2 order by p.started_at desc limit ?3)), random()
                limit 1
                """.trimIndent(), UUID::class.java,
            ).setParameter(1, st.communityId).setParameter(2, id).setParameter(3, RECENT_NO_REPEAT).resultList.firstOrNull() as UUID?
            if (t != null) next = Triple(t, null, true)
        }
        val n = next
        if (n == null) {
            em.createNativeQuery("update radio_station set play_id = null, ends_at = null where id = ?1").setParameter(1, id).executeUpdate()
        } else {
            val playId = UUID.randomUUID()
            val dur = (em.createNativeQuery("select duration_sec from track where id = ?1").setParameter(1, n.first).singleResult as Number).toLong()
            em.createNativeQuery(
                """
                insert into radio_play (id, session_id, station_id, track_id, started_at, requested_by, auto)
                values (?1, ?2, ?3, ?4, ?5, cast(nullif(?6, '') as uuid), ?7)
                """.trimIndent(),
            ).setParameter(1, playId).setParameter(2, st.sessionId).setParameter(3, id).setParameter(4, n.first)
                .setParameter(5, start).setParameter(6, n.second?.toString() ?: "").setParameter(7, n.third).executeUpdate()
            em.createNativeQuery("update radio_station set play_id = ?2, ends_at = ?3 where id = ?1")
                .setParameter(1, id).setParameter(2, playId).setParameter(3, start.plusSeconds(dur)).executeUpdate()
            em.createNativeQuery("update track set plays = plays + 1 where id = ?1").setParameter(1, n.first).executeUpdate()
        }
        val fresh = station(id)
        push(fresh)
        return if (me != null) render(fresh, me, SHOW_QUEUE) else null
    }

    private fun stopLocked(st: St) {
        if (st.status != "on") return
        st.playId?.let { exec("update radio_play set ended_at = now() where id = ?1 and ended_at is null", it) }
        st.sessionId?.let { sid ->
            exec("update radio_session set ended_at = now() where id = ?1", sid)
            // пустой эфир (ничего не сыграло) в «Реплеи» не попадает
            exec("delete from radio_session s where s.id = ?1 and not exists (select 1 from radio_play p where p.session_id = s.id)", sid)
        }
        exec("update radio_station set status = 'off', session_id = null, play_id = null, ends_at = null where id = ?1", st.id)
        push(station(st.id))
    }

    private fun push(st: St) {
        val out = render(st, null, FRAME_QUEUE)
        val f = Envelope(t = FRAME_STATE, d = mapper.valueToTree(out))
        bus.publishToRoomViewers(st.id, f)
        bus.publishToRoomViewers(st.communityId, f)
    }

    @Suppress("UNCHECKED_CAST")
    private fun render(st: St, me: UUID?, queueLimit: Int): RadioOut {
        val now = Instant.now()
        val queueRows = em.createNativeQuery(
            "select id, track_id, added_by, requested from radio_queue where station_id = ?1 order by position, created_at limit ?2",
        ).setParameter(1, st.id).setParameter(2, queueLimit).resultList as List<Array<Any?>>
        val queueSize = (em.createNativeQuery("select count(*) from radio_queue where station_id = ?1").setParameter(1, st.id).singleResult as Number).toInt()
        val play = st.playId?.let {
            (em.createNativeQuery("select track_id, started_at, requested_by, auto from radio_play where id = ?1")
                .setParameter(1, it).resultList as List<Array<Any?>>).firstOrNull()
        }
        val trackIds = queueRows.map { it[1] as UUID } + listOfNotNull(play?.get(0) as UUID?)
        val tracks: Map<UUID, TrackOut> = music.renderByIds(trackIds, me).associateBy { it.id }
        val listenerRows = em.createNativeQuery(
            "select user_id from radio_listener where station_id = ?1 and seen_at > ?2 order by seen_at desc",
            UUID::class.java,
        ).setParameter(1, st.id).setParameter(2, now.minus(LISTENER_TTL)).resultList as List<UUID>
        val djIds = em.createNativeQuery("select user_id from radio_dj where station_id = ?1 order by added_at", UUID::class.java)
            .setParameter(1, st.id).resultList as List<UUID>
        val users = profiles.shorts(
            queueRows.map { it[2] as UUID } + listenerRows.take(12) + djIds + listOfNotNull(play?.get(2) as UUID?),
        )
        val nowOut = play?.let { p ->
            val t = tracks[p[0] as UUID] ?: return@let null
            val started = toInstant(p[1])
            RadioNowOut(
                playId = st.playId!!, track = t, startedAt = started, endsAt = st.endsAt ?: started.plusSeconds(t.durationSec.toLong()),
                positionSec = ((now.toEpochMilli() - started.toEpochMilli()) / 1000.0).coerceAtLeast(0.0),
                requestedBy = (p[2] as UUID?)?.let { users[it] }, auto = p[3] == true,
            )
        }
        val my = me?.let {
            val dj = isDj(st, it)
            val mine = myRequests(st.id, it)
            RadioMyOut(isAdmin(st.communityId, it), dj, dj || (st.requestsOpen && mine < MAX_MY_REQUESTS), mine)
        }
        return RadioOut(
            id = st.id, communityId = st.communityId, name = st.name, description = st.description,
            autoDj = st.autoDj, requestsOpen = st.requestsOpen, status = st.status,
            session = st.sessionId?.let { sessions(listOf(it)).firstOrNull() },
            now = nowOut,
            queue = queueRows.mapNotNull { r ->
                tracks[r[1] as UUID]?.let { RadioQueueItemOut(r[0] as UUID, it, users[r[2] as UUID], r[3] == true) }
            },
            queueSize = queueSize,
            listeners = listenerRows.size,
            listenersSample = listenerRows.take(12).mapNotNull { users[it] },
            djs = djIds.mapNotNull { users[it] },
            serverTime = now,
            my = my,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun sessions(ids: List<UUID>): List<RadioSessionOut> {
        if (ids.isEmpty()) return emptyList()
        val rows = em.createNativeQuery(
            """
            select s.id, s.station_id, r.community_id, s.title, s.started_by, s.started_at, s.ended_at, s.peak_listeners,
                   extract(epoch from coalesce(s.ended_at, now()) - s.started_at),
                   (select count(*) from radio_play p where p.session_id = s.id),
                   (select string_agg(cast(x.cover_media_id as text), ',') from (
                        select distinct on (t.cover_media_id) t.cover_media_id, p.started_at from radio_play p
                        join track t on t.id = p.track_id where p.session_id = s.id and t.cover_media_id is not null
                        order by t.cover_media_id, p.started_at limit 4) x)
            from radio_session s join radio_station r on r.id = s.station_id
            where s.id in (?1) and s.deleted_at is null
            """.trimIndent(),
        ).setParameter(1, ids).resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.mapNotNull { it[4] as UUID? })
        val byId = rows.associateBy { it[0] as UUID }
        return ids.mapNotNull { id ->
            val r = byId[id] ?: return@mapNotNull null
            RadioSessionOut(
                id = id, stationId = r[1] as UUID, communityId = r[2] as UUID, title = r[3] as String,
                startedBy = (r[4] as UUID?)?.let { users[it] }, startedAt = toInstant(r[5]), endedAt = r[6]?.let { toInstant(it) },
                live = r[6] == null, durationSec = (r[8] as Number).toLong(), trackCount = (r[9] as Number).toInt(),
                peakListeners = (r[7] as Number).toInt(),
                covers = (r[10] as String?)?.split(',')?.filter { it.isNotBlank() }?.map { media.url(UUID.fromString(it)) }.orEmpty(),
            )
        }
    }

    private fun myRequests(id: UUID, me: UUID): Int =
        (em.createNativeQuery("select count(*) from radio_queue where station_id = ?1 and added_by = ?2 and requested")
            .setParameter(1, id).setParameter(2, me).singleResult as Number).toInt()

    private fun isAdmin(communityId: UUID, me: UUID) = communities.roleOf(communityId, me) in setOf("admin", "owner")

    private fun isDj(st: St, me: UUID): Boolean =
        isAdmin(st.communityId, me) ||
            (em.createNativeQuery("select count(*) from radio_dj where station_id = ?1 and user_id = ?2")
                .setParameter(1, st.id).setParameter(2, me).singleResult as Number).toLong() > 0

    private fun requireDj(st: St, me: UUID) {
        if (!isDj(st, me)) throw ApiException.forbidden("управлять эфиром может диджей или админ сообщества")
    }

    private fun requireAdmin(st: St, me: UUID) {
        val c = Community.findById(st.communityId) ?: throw ApiException.notFound("сообщество не найдено")
        communities.requireRole(c, me, "admin")
    }

    private val COLS = "id, community_id, name, description, auto_dj, requests_open, status, session_id, play_id, ends_at"

    @Suppress("UNCHECKED_CAST")
    private fun one(sql: String, v: UUID): St? =
        (em.createNativeQuery(sql).setParameter(1, v).resultList as List<Array<Any?>>).firstOrNull()?.let {
            St(
                it[0] as UUID, it[1] as UUID, it[2] as String, it[3] as String?, it[4] == true, it[5] == true, it[6] as String,
                it[7] as UUID?, it[8] as UUID?, it[9]?.let { v -> toInstant(v) },
            )
        }

    private fun stationOf(communityId: UUID): St? = one("select $COLS from radio_station where community_id = ?1", communityId)

    private fun station(id: UUID): St = one("select $COLS from radio_station where id = ?1", id)
        ?: throw ApiException.notFound("радиостанция не найдена")

    private fun lockOrNull(id: UUID): St? = one("select $COLS from radio_station where id = ?1 for update", id)

    private fun lock(id: UUID): St = lockOrNull(id) ?: throw ApiException.notFound("радиостанция не найдена")

    private fun exec(sql: String, id: UUID, v: Any? = null) {
        val q = em.createNativeQuery(sql).setParameter(1, id)
        if (v != null) q.setParameter(2, v)
        q.executeUpdate()
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}

/** Смена треков (раз в секунду) и выключение пустых эфиров — на одной ноде. */
@ApplicationScoped
class RadioJobs(private val radio: RadioService, private val lease: JobLease) {
    @Scheduled(every = "1s", delayed = "20s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun tick() {
        if (!lease.acquire("radio-tick", Duration.ofSeconds(5))) return
        for (id in radio.due()) {
            runCatching { radio.tick(id) }.onFailure { io.quarkus.logging.Log.warnf(it, "radio tick %s", id) }
        }
    }

    @Scheduled(every = "1m", delayed = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun idle() {
        if (lease.acquire("radio-idle", Duration.ofMinutes(2))) radio.stopIdle()
    }
}
