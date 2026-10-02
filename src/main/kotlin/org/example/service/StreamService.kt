package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.panache.common.Sort
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.bus.EventBus
import org.example.domain.AppUser
import org.example.domain.Chat
import org.example.domain.ChatMember
import org.example.domain.ChatMemberId
import org.example.domain.Community
import org.example.domain.Stream
import org.example.domain.StreamChannel
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.rest.ApiException
import org.example.rest.IngestOut
import org.example.rest.PlaybackOut
import org.example.rest.PostCommunityOut
import org.example.rest.StreamIn
import org.example.rest.StreamOut
import org.example.rest.StreamOwnerOut
import org.example.rest.StreamPageOut
import org.example.rest.StreamPatchIn
import org.example.rest.StreamStateOut
import org.example.rest.StreamViewersOut
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Простые стримы.
 *
 *  OBS ──RTMP──▶ MediaMTX ──HLS / WebRTC (без перекодирования)──▶ зрители
 *                   │ ▲
 *      auth-хук ────┘ └──── бэкенд раз в 5 с спрашивает, какие пути в эфире
 *
 * Канал (stream_channel) — «куда стримить»: личный у человека и общий у
 * сообщества. Ключ канала вбивается в OBS один раз: "<code>?pass=<secret>".
 * Эфир (stream) — одна трансляция: idle → live → ended. Его можно
 * подготовить заранее (название), а можно просто нажать в OBS «Начать
 * трансляцию» — эфир создастся сам с названием по умолчанию.
 *
 * Под каждым эфиром — свой чат (chat.room_type = 'stream'): зритель
 * заходит в него кнопкой, дальше сообщения по обычному сокету.
 */
@ApplicationScoped
class StreamService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bus: EventBus,
    private val communities: CommunityService,
    private val profiles: UserProfileService,
    private val friends: FriendService,
    private val notifications: NotificationService,
    private val mediaServer: MediaServerClient,
    private val tags: TagService,
    private val media: MediaService,
    @ConfigProperty(name = "straycatz.streams.rtmp-url", defaultValue = "rtmp://localhost:1935/live")
    private val rtmpUrl: String,
    @ConfigProperty(name = "straycatz.streams.hls-url", defaultValue = "http://localhost:8888")
    private val hlsUrl: String,
    @ConfigProperty(name = "straycatz.streams.webrtc-url", defaultValue = "http://localhost:8889")
    private val webrtcUrl: String,
    @ConfigProperty(name = "straycatz.streams.hook-secret")
    private val hookSecret: String,
) {
    companion object {
        const val APP = "live"
        const val MAX_TITLE = 120
        const val MAX_DESCRIPTION = 2000
        const val MAX_PAGE = 50
        /** Поток пропал дольше, чем на столько, — эфир закончился. */
        val GRACE: Duration = Duration.ofSeconds(30)
        /** OBS переподключился в течение этого окна — продолжаем тот же эфир. */
        val RECONNECT: Duration = Duration.ofMinutes(2)
        /** После «завершить» на сайте OBS не пускаем столько времени (иначе он сам переподключится). */
        val MANUAL_BLOCK: Duration = Duration.ofSeconds(90)
        /** Зритель «смотрит», если пинговал за последние 60 секунд. */
        const val VIEWER_TTL_SEC = 60
        val OBS_HINTS = listOf(
            "Сервис: Настраиваемый…; Сервер и Ключ потока — из этого ответа",
            "Кодировщик видео: x264 или аппаратный H.264 (не HEVC/AV1 — их браузеры по HLS не везде играют)",
            "Интервал ключевых кадров: 1–2 с (от него зависит задержка HLS)",
            "Аудио: AAC 48 кГц; битрейт видео — до 6000 Кбит/с: сервер раздаёт поток как есть, без перекодирования",
        )
        private val CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
    }

    private val random = SecureRandom()

    /** Последнее отправленное число зрителей — чтобы слать stream.state только при изменении. */
    private val lastViewers = ConcurrentHashMap<UUID, Long>()

    // ================================================================ настройки OBS

    /** Куда стримить: свой канал или канал сообщества (admin). Канал создаётся при первом обращении. */
    @Transactional
    fun ingest(me: UUID, communitySlug: String?): IngestOut {
        val ch = manageableChannel(me, communitySlug)
        return ingestOut(ch, me, streamKey = null)
    }

    /** Выпустить новый ключ (старый перестаёт работать для новых подключений). Ключ виден только в этом ответе. */
    @Transactional
    fun rotateKey(me: UUID, communitySlug: String?): IngestOut {
        val ch = manageableChannel(me, communitySlug)
        val secret = randomString(32)
        ch.secretHash = sha256(secret)
        ch.keyRotatedAt = Instant.now()
        ch.keyRotatedBy = me
        return ingestOut(ch, me, streamKey = "${ch.code}?pass=$secret")
    }

    // ================================================================ эфиры

    /**
     * Подготовить эфир: название и описание заранее. Если у канала уже есть
     * неоконченный эфир — он и обновляется (второго не бывает).
     */
    @Transactional
    fun create(me: UUID, req: StreamIn): StreamOut {
        val ch = manageableChannel(me, req.communitySlug)
        val title = title(req.title) ?: throw ApiException.badRequest("invalid_title", "нужно название: 1–$MAX_TITLE символов")
        val description = description(req.description)
        val active = activeOf(ch.id)
        val s = if (active != null) {
            active.title = title
            active.description = description
            Chat.findById(active.chatId)?.name = title
            active
        } else {
            newStream(ch, me, title, description)
        }
        req.posterMediaId?.let { s.posterMediaId = posterImage(me, it) }
        tags.sync(TagService.Owner.STREAM, s.id, req.tags, s.title, s.description)
        return render(listOf(s), me).first()
    }

    @Transactional
    fun update(me: UUID, id: UUID, req: StreamPatchIn): StreamOut {
        val s = find(id)
        requireManage(channel(s), me)
        req.title?.let { t ->
            s.title = title(t) ?: throw ApiException.badRequest("invalid_title", "нужно название: 1–$MAX_TITLE символов")
            Chat.findById(s.chatId)?.name = s.title
        }
        req.description?.let { s.description = description(it) }
        when {
            req.clearPoster -> s.posterMediaId = null
            req.posterMediaId != null -> s.posterMediaId = posterImage(me, req.posterMediaId)
        }
        if (req.title != null || req.description != null || req.tags != null) {
            tags.sync(TagService.Owner.STREAM, s.id, req.tags, s.title, s.description)
        }
        return render(listOf(s), me).first()
    }

    /** Завершить с сайта: эфир закрывается, издатель отключается, OBS 90 секунд не пускаем обратно. */
    @Transactional
    fun end(me: UUID, id: UUID): StreamOut {
        val s = em.find(Stream::class.java, id, LockModeType.PESSIMISTIC_WRITE) ?: throw ApiException.notFound("эфир не найден")
        requireManage(channel(s), me)
        if (s.status != "ended") {
            val kick = s.publisherType to s.publisherId
            finish(s, manually = true)
            mediaServer.kick(kick.first, kick.second)
        }
        return render(listOf(s), me).first()
    }

    @Transactional
    fun get(me: UUID, id: UUID): StreamOut = render(listOf(find(id)), me).first()

    /**
     * Списки. status: live (по умолчанию) | ended | all.
     * following=true — только друзья и сообщества, где я участник или читаю.
     * communitySlug / userId — эфиры одного канала (история).
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun list(
        me: UUID, statusRaw: String?, communitySlug: String?, userId: UUID?,
        following: Boolean, before: Instant?, limit: Int, tag: String? = null,
    ): StreamPageOut {
        val status = (statusRaw ?: "live").lowercase()
        val timeCol = when (status) {
            "live" -> "s.started_at"
            "ended" -> "s.ended_at"
            "all" -> "s.created_at"
            else -> throw ApiException.badRequest("invalid_status", "status: live, ended, all")
        }
        val size = limit.coerceIn(1, MAX_PAGE)
        // позиционные параметры по порядку появления: Hibernate не любит лишних и пропущенных номеров
        val params = mutableListOf<Any>()
        fun p(v: Any): String { params += v; return "?${params.size}" }
        val where = mutableListOf("$timeCol < ${p(before ?: Instant.now().plusSeconds(60))}")
        if (status != "all") where += "s.status = ${p(status)}"
        communitySlug?.takeIf { it.isNotBlank() }?.let { where += "ch.community_id = ${p(communities.bySlug(it).id)}" }
        userId?.let { where += "ch.user_id = ${p(it)}" }
        tag?.takeIf { it.isNotBlank() }?.let { raw ->
            val t = TagService.normalize(raw) ?: throw ApiException.badRequest("invalid_tag", "тег: 2–40 букв/цифр/_")
            where += "exists (select 1 from tag_link tl where tl.owner_type = 'stream' and tl.owner_id = s.id and tl.tag = ${p(t)})"
        }
        if (following) {
            val fr = friends.friendIdsOf(me)
            val m = p(me)
            val parts = mutableListOf(
                "ch.user_id = $m",
                """ch.community_id in (
                    select m.community_id from community_member m where m.user_id = $m and m.left_at is null
                    union select f.community_id from community_follow f where f.user_id = $m)""",
            )
            if (fr.isNotEmpty()) parts += "ch.user_id in (${p(fr)})"
            where += parts.joinToString(" or ", "(", ")")
        }
        val q = em.createNativeQuery(
            """
            select s.id from stream s join stream_channel ch on ch.id = s.channel_id
            where ${where.joinToString(" and ")}
            order by $timeCol desc nulls last
            limit ${p(size + 1)}
            """.trimIndent(),
            UUID::class.java,
        )
        params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
        val ids = q.resultList as List<UUID>
        val byId = if (ids.isEmpty()) emptyMap() else Stream.list("id in ?1", ids).associateBy { it.id }
        val rows = ids.mapNotNull { byId[it] }
        return StreamPageOut(render(rows.take(size), me), rows.size > size)
    }

    /** Пинг зрителя: раз в 30 секунд, пока плеер открыт. */
    @Transactional
    fun watch(me: UUID, id: UUID): StreamViewersOut {
        val s = find(id)
        if (s.status == "live") {
            em.createNativeQuery(
                "insert into stream_viewer (stream_id, user_id, seen_at) values (?1, ?2, now()) " +
                        "on conflict (stream_id, user_id) do update set seen_at = now()",
            ).setParameter(1, id).setParameter(2, me).executeUpdate()
        }
        val viewers = viewers(listOf(id))[id] ?: 0
        if (viewers > s.peakViewers) s.peakViewers = viewers.toInt()
        return StreamViewersOut(viewers, s.status)
    }

    /** Зайти в чат под видео. После этого — chat.open / message.send по сокету с chatId эфира. */
    @Transactional
    fun joinChat(me: UUID, id: UUID): StreamOut {
        val s = find(id)
        val m = ChatMember.findById(ChatMemberId(s.chatId, me))
        when {
            m == null -> ChatMember().also { it.id = ChatMemberId(s.chatId, me) }.persist()
            m.isDeleted -> { m.isDeleted = false; m.deletedAt = null }
        }
        return render(listOf(s), me).first()
    }

    @Transactional
    fun leaveChat(me: UUID, id: UUID) {
        val s = find(id)
        ChatMember.findById(ChatMemberId(s.chatId, me))?.let { it.isDeleted = true; it.deletedAt = Instant.now() }
    }

    // ================================================================ хук медиасервера

    /**
     * Внешняя авторизация MediaMTX (authMethod: http). Тело — JSON
     * {user, password, ip, action, path, protocol, id, query}. true → 200 (пустить).
     *  - publish: путь live/<code>, пароль = секрет канала (pass= в ключе OBS);
     *  - read / playback: любой существующий канал (эфиры публичные). В mediamtx.yml
     *    они исключены из хука (иначе он зовётся на каждый запрос HLS) — ветка на случай,
     *    если просмотр захочется закрыть.
     */
    @Transactional
    fun authorize(secret: String?, body: Map<String, Any?>): Boolean {
        if (secret == null || !MessageDigest.isEqual(secret.toByteArray(), hookSecret.toByteArray())) {
            Log.warn("stream hook: неверный secret — проверь authHTTPAddress в mediamtx.yml")
            return false
        }
        val action = body["action"]?.toString().orEmpty()
        val code = codeOf(body["path"]?.toString()) ?: return false
        val ch = StreamChannel.find("code = ?1", code).firstResult() ?: return false
        return when (action) {
            "read", "playback" -> true
            "publish" -> publish(ch, passwordOf(body))
            else -> false
        }
    }

    private fun publish(ch: StreamChannel, password: String?): Boolean {
        val hash = ch.secretHash ?: return false
        if (password == null || !MessageDigest.isEqual(sha256(password).toByteArray(), hash.toByteArray())) {
            Log.infof("stream: неверный ключ для канала %s", ch.code)
            return false
        }
        if (activeOf(ch.id) != null) return true // подготовленный заранее или переподключение в эфире

        val now = Instant.now()
        val last = Stream.find("channelId = ?1", Sort.descending("createdAt"), ch.id).firstResult()
        val ago = last?.endedAt?.let { Duration.between(it, now) }
        if (last != null && ago != null) {
            if (last.endedManually && ago < MANUAL_BLOCK) return false
            if (!last.endedManually && ago < RECONNECT) {
                // обрыв связи: продолжаем тот же эфир (тот же чат и зрители)
                last.status = "live"
                last.endedAt = null
                last.lastReadyAt = now
                pushState(last)
                return true
            }
        }
        val by = ch.keyRotatedBy ?: ch.userId ?: return false
        val name = ch.userId?.let { AppUser.findById(it)?.username }
            ?: ch.communityId?.let { Community.findById(it)?.name }
            ?: "эфир"
        newStream(ch, by, "эфир: $name", null)
        return true
    }

    // ================================================================ синхронизация с медиасервером

    @Transactional
    fun hasActive(): Boolean = Stream.count("status in ?1", listOf("idle", "live")) > 0

    /**
     * Снимок путей медиасервера → статусы эфиров. Возвращает, кого кикнуть
     * (публикует без эфира — например, после «завершить» на сайте).
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun applySnapshot(paths: Map<String, MediaServerClient.PathState>): List<Pair<String?, String?>> {
        val now = Instant.now()
        val active = em.createNativeQuery(
            "select s.id, ch.code from stream s join stream_channel ch on ch.id = s.channel_id where s.status in ('idle', 'live')",
        ).resultList as List<Array<Any?>>
        val activePaths = HashSet<String>()
        for (row in active) {
            val id = row[0] as UUID
            val path = "$APP/${row[1] as String}"
            activePaths += path
            // блокировка строки: несколько нод синхронизируются одновременно, уведомление уйдёт один раз
            val s = em.find(Stream::class.java, id, LockModeType.PESSIMISTIC_WRITE) ?: continue
            if (s.status == "ended") continue
            val ps = paths[path]
            if (ps != null && ps.ready) {
                s.lastReadyAt = now
                s.publisherType = ps.sourceType
                s.publisherId = ps.sourceId
                if (s.status == "idle") {
                    s.status = "live"
                    s.startedAt = now
                    announce(s)
                    pushState(s)
                }
            } else if (s.status == "live" && (s.lastReadyAt ?: s.startedAt ?: now).isBefore(now.minus(GRACE))) {
                finish(s, manually = false)
            }
        }

        // зрители у идущих эфиров: пик и stream.state при изменении
        val liveIds = Stream.list("status = ?1", "live").associateBy { it.id }
        val counts = viewers(liveIds.keys)
        liveIds.values.forEach { s ->
            val v = counts[s.id] ?: 0
            if (v > s.peakViewers) s.peakViewers = v.toInt()
            if (lastViewers.put(s.id, v) != v) pushState(s, v)
        }
        lastViewers.keys.retainAll(liveIds.keys)

        return paths.values
            .filter { it.ready && it.name.startsWith("$APP/") && it.name !in activePaths }
            .map { it.sourceType to it.sourceId }
    }

    @Transactional
    fun purgeViewers() {
        em.createNativeQuery("delete from stream_viewer where seen_at < now() - interval '1 day'").executeUpdate()
    }

    // ================================================================ внутреннее

    private fun newStream(ch: StreamChannel, by: UUID, title: String, description: String?): Stream {
        val chat = Chat().also {
            it.id = UUID.randomUUID()
            it.name = title
            it.roomType = ChatManagementService.ROOM_STREAM
            it.createdBy = by
        }
        chat.persist()
        ChatMember().also { it.id = ChatMemberId(chat.id, by) }.persist()
        val s = Stream().also {
            it.id = UUID.randomUUID()
            it.channelId = ch.id
            it.createdBy = by
            it.title = title
            it.description = description
            it.chatId = chat.id
            // обложка переезжает из прошлого эфира канала — чтобы карточка не была пустой
            it.posterMediaId = Stream.find("channelId = ?1 and posterMediaId is not null", Sort.descending("createdAt"), ch.id)
                .firstResult()?.posterMediaId
        }
        s.persist()
        tags.sync(TagService.Owner.STREAM, s.id, null, title, description)
        return s
    }

    private fun posterImage(me: UUID, id: UUID): UUID {
        val m = media.requireOwned(id, me)
        if (!m.contentType.startsWith("image/")) throw ApiException.badRequest("invalid_poster", "обложка — картинка")
        return m.id
    }

    // ================================================================ живые кадры (превью)

    /** Идущие эфиры, кадр которых старше [olderThanSec]: id → код канала. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun thumbTargets(olderThanSec: Long): List<Pair<UUID, String>> =
        (em.createNativeQuery(
            """
            select s.id, ch.code from stream s join stream_channel ch on ch.id = s.channel_id
            where s.status = 'live' and (s.thumb_at is null or s.thumb_at < now() - make_interval(secs => ?1))
            order by s.thumb_at nulls first limit 20
            """.trimIndent(),
        ).setParameter(1, olderThanSec.toDouble()).resultList as List<Array<Any?>>).map { it[0] as UUID to it[1] as String }

    @Transactional
    fun setThumb(id: UUID, key: String) {
        Stream.findById(id)?.let { it.thumbKey = key; it.thumbAt = Instant.now() }
    }

    /** Ключ кадра для GET /api/streams/{id}/thumbnail. */
    @Transactional
    fun thumbKey(id: UUID): String? = Stream.findById(id)?.thumbKey

    private fun finish(s: Stream, manually: Boolean) {
        s.status = "ended"
        s.endedAt = Instant.now()
        s.endedManually = manually
        s.publisherType = null
        s.publisherId = null
        lastViewers.remove(s.id)
        em.createNativeQuery("delete from stream_viewer where stream_id = ?1").setParameter(1, s.id).executeUpdate()
        pushState(s, 0)
    }

    /** Уведомление «в эфире»: друзьям (личный канал) или участникам и читателям (сообщество). */
    @Suppress("UNCHECKED_CAST")
    private fun announce(s: Stream) {
        val ch = channel(s)
        val community = ch.communityId?.let { Community.findById(it) }
        val to: Set<UUID> = when {
            ch.userId != null -> friends.friendIdsOf(ch.userId!!).toSet()
            community != null -> (communities.memberIds(community.id) +
                    (em.createNativeQuery("select user_id from community_follow where community_id = ?1", UUID::class.java)
                        .setParameter(1, community.id).resultList as List<UUID>)).toSet()
            else -> emptySet()
        }
        val payload = mapOf("streamId" to s.id.toString(), "title" to s.title, "communitySlug" to community?.slug)
        (to - s.createdBy).forEach { notifications.notify(it, NotificationService.STREAM_LIVE, s.createdBy, payload) }
    }

    private fun pushState(s: Stream, viewers: Long? = null) {
        val v = viewers ?: if (s.status == "live") (this.viewers(listOf(s.id))[s.id] ?: 0) else 0
        val frame = Envelope(
            t = FrameTypes.STREAM_STATE,
            d = mapper.valueToTree(StreamStateOut(s.id, s.status, v, s.startedAt, s.endedAt)),
        )
        bus.publishToChat(s.chatId, frame) // после COMMIT
    }

    private fun ingestOut(ch: StreamChannel, me: UUID, streamKey: String?): IngestOut {
        val current = activeOf(ch.id)
        return IngestOut(
            server = rtmpUrl,
            streamKey = streamKey,
            channel = ch.code,
            hasKey = ch.secretHash != null,
            keyRotatedAt = ch.keyRotatedAt,
            owner = owners(listOf(ch))[ch.id]!!,
            current = current?.let { render(listOf(it), me).first() },
            obsHints = OBS_HINTS,
        )
    }

    /** Для ленты: эфиры пачкой. */
    @Transactional
    fun byIds(me: UUID, ids: Collection<UUID>): Map<UUID, StreamOut> {
        if (ids.isEmpty()) return emptyMap()
        return render(Stream.list("id in ?1", ids.distinct()), me).associateBy { it.id }
    }

    private fun render(streams: List<Stream>, me: UUID): List<StreamOut> {
        if (streams.isEmpty()) return emptyList()
        val chans = StreamChannel.list("id in ?1", streams.map { it.channelId }.distinct()).associateBy { it.id }
        val owners = owners(chans.values.toList())
        val creators = profiles.shorts(streams.map { it.createdBy })
        val counts = viewers(streams.filter { it.status == "live" }.map { it.id })
        val inChat = ChatMember.list("id.chatId in ?1 and id.userId = ?2 and isDeleted = false", streams.map { it.chatId }, me)
            .map { it.id.chatId }.toSet()
        val streamTags = tags.tagsOf(TagService.Owner.STREAM, streams.map { it.id })
        return streams.map { s ->
            val poster = s.posterMediaId?.let { media.url(it) }
            val thumb = s.thumbAt?.takeIf { s.thumbKey != null }?.let { media.publicUrl("/api/streams/${s.id}/thumbnail?t=${it.epochSecond}") }
            // живой кадр считаем свежим минуту — дальше эфир, скорее всего, завис
            val freshThumb = thumb?.takeIf { s.thumbAt!!.isAfter(Instant.now().minusSeconds(60)) }
            val ch = chans[s.channelId]!!
            StreamOut(
                id = s.id,
                title = s.title,
                description = s.description,
                status = s.status,
                owner = owners[ch.id]!!,
                createdBy = creators[s.createdBy],
                chatId = s.chatId,
                createdAt = s.createdAt,
                startedAt = s.startedAt,
                endedAt = s.endedAt,
                viewers = counts[s.id] ?: 0,
                peakViewers = s.peakViewers,
                playback = playback(ch.code),
                canManage = canManage(ch, me),
                inChat = s.chatId in inChat,
                previewUrl = if (s.status == "live") freshThumb ?: poster ?: thumb else poster ?: thumb,
                posterUrl = poster,
                thumbnailUrl = thumb,
                thumbnailAt = s.thumbAt,
                tags = streamTags[s.id] ?: emptyList(),
            )
        }
    }

    private fun owners(chans: List<StreamChannel>): Map<UUID, StreamOwnerOut> {
        val users = profiles.shorts(chans.mapNotNull { it.userId })
        val commIds = chans.mapNotNull { it.communityId }
        val comms = if (commIds.isEmpty()) emptyMap() else Community.list("id in ?1", commIds).associateBy { it.id }
        return chans.associate { ch ->
            ch.id to if (ch.userId != null) {
                StreamOwnerOut("user", users[ch.userId!!], null)
            } else {
                StreamOwnerOut("community", null, ch.communityId?.let { comms[it] }?.let { PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) })
            }
        }
    }

    private fun playback(code: String): PlaybackOut {
        val hls = hlsUrl.trimEnd('/')
        return PlaybackOut(
            hls = "$hls/$APP/$code/index.m3u8",
            webrtc = "${webrtcUrl.trimEnd('/')}/$APP/$code/whep",
            page = "$hls/$APP/$code/",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun viewers(ids: Collection<UUID>): Map<UUID, Long> {
        if (ids.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery(
            "select stream_id, count(*) from stream_viewer where stream_id in (?1) " +
                    "and seen_at > now() - make_interval(secs => $VIEWER_TTL_SEC) group by stream_id",
        ).setParameter(1, ids.toList()).resultList as List<Array<Any?>>
        return rows.associate { it[0] as UUID to (it[1] as Number).toLong() }
    }

    private fun manageableChannel(me: UUID, communitySlug: String?): StreamChannel {
        val c = communitySlug?.takeIf { it.isNotBlank() }?.let { slug ->
            communities.bySlug(slug).also { communities.requireRole(it, me, "admin") }
        }
        val existing = if (c != null) StreamChannel.find("communityId = ?1", c.id).firstResult()
        else StreamChannel.find("userId = ?1", me).firstResult()
        if (existing != null) return existing
        return StreamChannel().also {
            it.id = UUID.randomUUID()
            it.code = randomString(12)
            it.userId = if (c == null) me else null
            it.communityId = c?.id
        }.also { it.persist() }
    }

    private fun canManage(ch: StreamChannel, me: UUID): Boolean = when {
        ch.userId != null -> ch.userId == me
        ch.communityId != null -> communities.roleOf(ch.communityId!!, me) in setOf("admin", "owner")
        else -> false
    }

    private fun requireManage(ch: StreamChannel, me: UUID) {
        if (!canManage(ch, me)) throw ApiException.forbidden("это не твой эфир")
    }

    private fun activeOf(channelId: UUID): Stream? =
        Stream.find("channelId = ?1 and status in ?2", channelId, listOf("idle", "live")).firstResult()

    private fun find(id: UUID): Stream = Stream.findById(id) ?: throw ApiException.notFound("эфир не найден")

    private fun channel(s: Stream): StreamChannel = StreamChannel.findById(s.channelId)!!

    private fun title(v: String?): String? {
        val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length > MAX_TITLE) throw ApiException.badRequest("invalid_title", "название длиннее $MAX_TITLE")
        return t
    }

    private fun description(v: String?): String? {
        val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length > MAX_DESCRIPTION) throw ApiException.badRequest("invalid_description", "описание длиннее $MAX_DESCRIPTION")
        return t
    }

    /** "live/<code>" → code. Всё остальное (чужие app, вложенные пути) — мимо. */
    private fun codeOf(path: String?): String? {
        val parts = path?.trim('/')?.split('/') ?: return null
        if (parts.size != 2 || parts[0] != APP) return null
        return parts[1].takeIf { it.matches(Regex("^[a-z0-9]{6,32}$")) }
    }

    /** Пароль из поля password или из query (pass= / key=) — MediaMTX для RTMP кладёт его и туда, и туда. */
    private fun passwordOf(body: Map<String, Any?>): String? {
        body["password"]?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
        val query = body["query"]?.toString() ?: return null
        return query.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2 && (kv[0] == "pass" || kv[0] == "key")) URLDecoder.decode(kv[1], StandardCharsets.UTF_8) else null
        }.firstOrNull()
    }

    private fun randomString(len: Int) = String(CharArray(len) { CODE_CHARS[random.nextInt(CODE_CHARS.size)] })

    private fun sha256(v: String): String =
        MessageDigest.getInstance("SHA-256").digest(v.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Раз в 5 секунд сверяет эфиры с медиасервером. Пока эфиров нет — медиасервер не дёргает. */
@ApplicationScoped
class StreamSyncJob(
    private val streams: StreamService,
    private val mediaServer: MediaServerClient,
    @ConfigProperty(name = "straycatz.streams.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun sync() {
        if (!enabled || !streams.hasActive()) return
        val paths = mediaServer.paths() ?: return // медиасервер лежит — статусы не трогаем
        streams.applySnapshot(paths).forEach { (type, id) -> mediaServer.kick(type, id) }
    }

    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun purge() = streams.purgeViewers()
}
