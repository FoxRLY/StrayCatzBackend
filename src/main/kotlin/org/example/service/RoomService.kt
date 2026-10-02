package org.example.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.domain.AppUser
import org.example.domain.GuestbookEntry
import org.example.domain.Room
import org.example.domain.RoomLink
import org.example.domain.UserCosmetics
import org.example.domain.UserLevel
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.proto.RoomUpdatedOut
import org.example.rest.ApiException
import org.example.rest.BlocksIn
import org.example.rest.FriendCardOut
import org.example.rest.FriendsPageOut
import org.example.rest.GuestOut
import org.example.rest.GuestbookEntryOut
import org.example.rest.GuestbookPageOut
import org.example.rest.InviteOut
import org.example.rest.LinkIn
import org.example.rest.RoomLinkOut
import org.example.rest.RoomLookOut
import org.example.rest.RoomOut
import org.example.rest.RoomOwnerOut
import org.example.rest.RoomPatchIn
import org.example.rest.VisitOut
import org.example.rest.WallImageIn
import org.example.rest.WallImageOut
import org.example.rest.WordsIn
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Комната = страница пользователя (/rooms/{username}). Одна на человека,
 * строка в `room` создаётся лениво — при первой правке; до этого гости видят
 * значения по умолчанию.
 *
 * Всё, что меняет вид комнаты, рассылает `room.updated` тем, у кого комната
 * открыта (кадр `room.open` в сокете) — «меняется сразу и для гостей тоже».
 */
@ApplicationScoped
class RoomService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bus: EventBus,
    private val profiles: UserProfileService,
    private val friends: FriendService,
    private val presence: PresenceService,
    private val communities: CommunityService,
    private val media: MediaService,
    private val notifications: NotificationService,
    private val attachments: AttachmentService,
) {
    companion object {
        val THEMES = setOf("dvor", "fonar", "led", "malina")
        val WALLPAPERS = setOf("grid", "asphalt", "stars", "stripes", "none")
        val FITS = setOf("cover", "contain", "tile")
        val DIALECTS = setOf("normal", "yard", "forum", "cat", "dry")

        /** Все блоки стены в порядке по умолчанию. */
        val ALL_BLOCKS = listOf("about", "music", "friends", "guestbook", "activity", "badges", "video", "communities", "links")

        /**
         * Известные ключи "своих слов" (фронт может добавлять новые — любой
         * ключ вида snake_case принимается, но эти стоит поддержать первыми).
         */
        val KNOWN_WORDS = listOf(
            "join_community", "send_post", "upvote_post", "replies_counter", "guestbook_title", "guestbook_sign",
        )
        private val WORD_KEY_RE = Regex("^[a-z][a-z0-9_]{1,39}$")
        private val URL_RE = Regex("^(https?://\\S+|/\\S*)$")

        const val MAX_TITLE = 60
        const val MAX_MOOD = 140
        const val MAX_ABOUT = 2000
        const val MAX_STICKER = 40
        const val MAX_WORD = 22
        const val MAX_WORDS = 40
        const val MAX_LINKS = 20
        const val MAX_LINK_TITLE = 80
        const val MAX_URL = 1024
        const val MAX_GUESTBOOK_BODY = 500
        val GUESTBOOK_COOLDOWN: Duration = Duration.ofSeconds(20)
        val INVITE_COOLDOWN: Duration = Duration.ofHours(24)
        const val PREVIEW_FRIENDS = 12
        const val PREVIEW_COMMUNITIES = 12
        const val PREVIEW_GUESTBOOK = 5
        const val MAX_PAGE = 100

        private val STRING_LIST = object : TypeReference<List<String>>() {}
        private val STRING_MAP = object : TypeReference<Map<String, String>>() {}
    }

    // ================================================================ просмотр

    @Transactional
    fun view(viewer: UUID, username: String): RoomOut {
        val owner = ownerByUsername(username)
        val room = Room.findById(owner.id) ?: defaultRoom(owner)
        val isOwner = viewer == owner.id
        val blocks = parseBlocks(room.blocks)
        fun shown(block: String) = isOwner || block in blocks

        val cosmetics = UserCosmetics.findById(owner.id)
        val level = UserLevel.findById(owner.id)

        return RoomOut(
            owner = RoomOwnerOut(
                owner.id, owner.username, cosmetics?.avatar, cosmetics?.color, cosmetics?.tagline,
                level?.level ?: 0, level?.xp ?: 0, owner.createdAt.atZone(java.time.ZoneOffset.UTC).year.toString(),
            ),
            isOwner = isOwner,
            friendship = friends.stateBetween(viewer, owner.id),
            presence = presence.query(viewer, listOf(owner.id)).firstOrNull(),
            guestsToday = guestsToday(owner.id),
            look = look(room),
            links = if (shown("links")) links(owner.id) else null,
            friends = if (shown("friends")) friendsPage(viewer, owner.id, PREVIEW_FRIENDS, 0) else null,
            communities = if (shown("communities")) communities.ofUser(owner.id, PREVIEW_COMMUNITIES).items else null,
            guestbook = if (shown("guestbook")) guestbookPage(viewer, owner.id, null, PREVIEW_GUESTBOOK) else null,
        )
    }

    /**
     * Отметить заход. Гость — залогиненный не-хозяин; считается раз в сутки
     * (UTC-дата). Отдельным POST, а не в GET: SvelteKit префетчит страницы по
     * наведению, и GET с побочным эффектом накручивал бы гостей.
     */
    @Transactional
    fun visit(viewer: UUID, username: String): VisitOut {
        val owner = ownerByUsername(username)
        if (viewer == owner.id) return VisitOut(guestsToday(owner.id), counted = false)
        em.createNativeQuery(
            """
            insert into room_visit (owner_id, visitor_id, day)
            values (?1, ?2, (now() at time zone 'utc')::date)
            on conflict (owner_id, visitor_id, day) do update set last_at = now()
            """.trimIndent(),
        ).setParameter(1, owner.id).setParameter(2, viewer).executeUpdate()
        return VisitOut(guestsToday(owner.id), counted = true)
    }

    /** Кто заходил за последние сутки — видит только хозяин. */
    @Transactional
    fun guests(me: UUID, limit: Int): List<GuestOut> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select visitor_id, max(last_at) from room_visit
            where owner_id = ?1 and last_at >= now() - interval '24 hours'
            group by visitor_id order by max(last_at) desc limit ?2
            """.trimIndent(),
        ).setParameter(1, me).setParameter(2, limit.coerceIn(1, MAX_PAGE)).resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.map { it[0] as UUID })
        return rows.mapNotNull { r ->
            val at = when (val v = r[1]) {
                is Instant -> v
                is java.time.OffsetDateTime -> v.toInstant()
                is java.sql.Timestamp -> v.toInstant()
                else -> Instant.now()
            }
            users[r[0] as UUID]?.let { GuestOut(it, at) }
        }
    }

    @Transactional
    fun friendsOf(viewer: UUID, username: String, limit: Int, offset: Int): FriendsPageOut {
        val owner = ownerByUsername(username)
        return friendsPage(viewer, owner.id, limit.coerceIn(1, MAX_PAGE), offset.coerceAtLeast(0))
    }

    // ================================================================ вид комнаты

    @Transactional
    fun update(me: UUID, patch: RoomPatchIn): RoomOut {
        val room = ensureRoom(me)
        patch.title?.let {
            val t = it.trim()
            if (t.isEmpty() || t.length > MAX_TITLE) throw bad("invalid_title", "название: 1–$MAX_TITLE символов")
            room.title = t
        }
        patch.mood?.let { room.mood = optionalText(it, MAX_MOOD, "mood") }
        patch.about?.let { room.about = optionalText(it, MAX_ABOUT, "about") }
        patch.sticker?.let { room.sticker = optionalText(it, MAX_STICKER, "sticker") }
        patch.theme?.let { room.theme = oneOf(it, THEMES, "theme") }
        patch.wallpaper?.let { room.wallpaper = oneOf(it, WALLPAPERS, "wallpaper") }
        patch.wallFit?.let { room.wallFit = oneOf(it, FITS, "wallFit") }
        patch.dialect?.let { room.dialect = oneOf(it, DIALECTS, "dialect") }
        patch.wallVeil?.let {
            if (it < 0.35 || it > 0.85) throw bad("invalid_wallVeil", "wallVeil: от 0.35 до 0.85 — текст должен читаться")
            room.wallVeil = it
        }
        patch.wallBlur?.let {
            if (it !in 0..12) throw bad("invalid_wallBlur", "wallBlur: от 0 до 12")
            room.wallBlur = it
        }
        patch.tilt?.let {
            if (it < 0.0 || it > 1.0) throw bad("invalid_tilt", "tilt: от 0 до 1")
            room.tilt = it
        }
        val detail = when {
            patch.theme != null -> "сменил(а) тему комнаты"
            patch.wallpaper != null || patch.wallFit != null || patch.wallVeil != null || patch.wallBlur != null -> "переклеил(а) обои в комнате"
            patch.mood != null -> "сменил(а) настроение: ${room.mood ?: "без слов"}"
            patch.about != null -> "переписал(а) «обо мне»"
            patch.title != null -> "переименовал(а) комнату: ${room.title}"
            patch.sticker != null -> "повесил(а) новую наклейку"
            else -> "обновил(а) комнату"
        }
        return touched(room, "look", detail)
    }

    /** Картинка на стену: либо загруженная (mediaId из POST /api/media), либо внешняя ссылка. */
    @Transactional
    fun setWallImage(me: UUID, req: WallImageIn): RoomOut {
        val room = ensureRoom(me)
        when {
            req.mediaId != null && req.url == null -> {
                media.requireOwned(req.mediaId, me)
                room.wallMediaId = req.mediaId
                room.wallImageUrl = null
            }
            req.url != null && req.mediaId == null -> {
                val u = req.url.trim()
                if (!u.startsWith("https://") && !u.startsWith("http://") || u.length > MAX_URL) {
                    throw bad("invalid_url", "ссылка на картинку должна начинаться с http(s)://")
                }
                room.wallImageUrl = u
                room.wallMediaId = null
            }
            else -> throw bad("invalid_wall_image", "нужно ровно одно: mediaId или url")
        }
        return touched(room, "look", "повесил(а) новую картинку на стену")
    }

    @Transactional
    fun clearWallImage(me: UUID): RoomOut {
        val room = ensureRoom(me)
        room.wallMediaId = null
        room.wallImageUrl = null
        return touched(room, "look", "снял(а) картинку со стены")
    }

    /** Видимые блоки и их порядок. Пустой список — стена пустая, это допустимо. */
    @Transactional
    fun setBlocks(me: UUID, req: BlocksIn): RoomOut {
        val list = req.blocks ?: throw bad("invalid_blocks", "нужен массив blocks")
        val unknown = list.filter { it !in ALL_BLOCKS }
        if (unknown.isNotEmpty()) throw bad("invalid_blocks", "неизвестные блоки: $unknown, допустимы: $ALL_BLOCKS")
        if (list.size != list.distinct().size) throw bad("invalid_blocks", "блоки повторяются")
        val room = ensureRoom(me)
        room.blocks = mapper.writeValueAsString(list)
        return touched(room, "look", "переставил(а) блоки в комнате")
    }

    /** Свои названия кнопок: полностью заменяет словарь. Пустое значение — ключ убирается. */
    @Transactional
    fun setWords(me: UUID, req: WordsIn): RoomOut {
        val words = req.words ?: throw bad("invalid_words", "нужен объект words")
        val clean = words.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        if (clean.size > MAX_WORDS) throw bad("invalid_words", "не больше $MAX_WORDS слов")
        clean.forEach { (k, v) ->
            if (!WORD_KEY_RE.matches(k)) throw bad("invalid_words", "ключ '$k': латиница в snake_case")
            if (v.length > MAX_WORD) throw bad("invalid_words", "'$k' длиннее $MAX_WORD символов")
        }
        val room = ensureRoom(me)
        room.words = mapper.writeValueAsString(clean)
        return touched(room, "look")
    }

    // ================================================================ ссылки наружу

    @Transactional
    fun addLink(me: UUID, req: LinkIn): RoomLinkOut {
        ensureRoom(me)
        if (RoomLink.count("ownerId", me) >= MAX_LINKS) throw bad("too_many_links", "не больше $MAX_LINKS ссылок")
        val max = RoomLink.find("ownerId = ?1", Sort.descending("position"), me).firstResult()?.position ?: -1
        val link = RoomLink().also {
            it.id = UUID.randomUUID()
            it.ownerId = me
            it.title = linkTitle(req.title)
            it.url = linkUrl(req.url)
            it.position = max + 1
        }
        link.persist()
        linksTouched(me)
        return linkOut(link)
    }

    @Transactional
    fun updateLink(me: UUID, id: UUID, req: LinkIn): RoomLinkOut {
        val link = ownLink(me, id)
        req.title?.let { link.title = linkTitle(it) }
        req.url?.let { link.url = linkUrl(it) }
        linksTouched(me)
        return linkOut(link)
    }

    @Transactional
    fun deleteLink(me: UUID, id: UUID) {
        ownLink(me, id).delete()
        linksTouched(me)
    }

    /** Новый порядок: ids — все мои ссылки, каждая ровно один раз. */
    @Transactional
    fun reorderLinks(me: UUID, ids: List<UUID>?): List<RoomLinkOut> {
        val order = ids ?: throw bad("invalid_order", "нужен массив ids")
        val mine = RoomLink.list("ownerId", me).associateBy { it.id }
        if (order.toSet() != mine.keys || order.size != mine.size) {
            throw bad("invalid_order", "ids должны содержать все ваши ссылки ровно по разу")
        }
        order.forEachIndexed { i, id -> mine.getValue(id).position = i }
        return linksTouched(me)
    }

    // ================================================================ гостевая

    @Transactional
    fun guestbook(viewer: UUID, username: String, before: Instant?, limit: Int): GuestbookPageOut {
        val owner = ownerByUsername(username)
        val room = Room.findById(owner.id)
        if (viewer != owner.id && room != null && "guestbook" !in parseBlocks(room.blocks)) {
            throw ApiException.notFound("гостевая скрыта хозяином")
        }
        return guestbookPage(viewer, owner.id, before, limit.coerceIn(1, MAX_PAGE))
    }

    @Transactional
    fun sign(
        me: UUID,
        username: String,
        body: String?,
        mediaIds: List<UUID> = emptyList(),
        trackIds: List<UUID> = emptyList(),
    ): GuestbookEntryOut {
        val owner = ownerByUsername(username)
        // в гостевую — только картинки и гифки, без видео
        val files = attachments.validate(me, mediaIds, allowVideo = false)
        val tracks = attachments.validateTracks(me, trackIds)
        val text = body?.trim().orEmpty()
        if (text.length > MAX_GUESTBOOK_BODY) throw bad("invalid_body", "запись длиннее $MAX_GUESTBOOK_BODY символов")
        if (text.isEmpty() && files.isEmpty() && tracks.isEmpty()) throw bad("invalid_body", "нужен текст, картинка или трек")
        val room = Room.findById(owner.id)
        if (room != null && "guestbook" !in parseBlocks(room.blocks)) {
            throw ApiException.forbidden("хозяин закрыл гостевую")
        }
        val recent = GuestbookEntry.count(
            "ownerId = ?1 and authorId = ?2 and createdAt > ?3", owner.id, me, Instant.now().minus(GUESTBOOK_COOLDOWN),
        )
        if (recent > 0) throw ApiException(429, "too_fast", "не чаще одной записи в ${GUESTBOOK_COOLDOWN.seconds} секунд")

        val entry = GuestbookEntry().also {
            it.id = UUID.randomUUID()
            it.ownerId = owner.id
            it.authorId = me
            it.body = text
        }
        entry.persist()
        attachments.attach(AttachmentService.Owner.GUESTBOOK, entry.id, files)
        attachments.attachTracks(AttachmentService.Owner.GUESTBOOK, entry.id, tracks)

        if (me != owner.id) {
            notifications.notify(
                owner.id, NotificationService.GUESTBOOK_ENTRY, me,
                mapOf("entryId" to entry.id.toString(), "preview" to text.ifEmpty { "[картинка]" }.take(100)),
            )
        }
        pushRoomUpdated(owner.id, "guestbook")
        val author = profiles.shorts(listOf(me))[me] ?: throw ApiException.notFound("пользователь не найден")
        return GuestbookEntryOut(
            entry.id, author, entry.body, entry.createdAt, canDelete = true,
            attachments = attachments.render(files), tracks = attachments.renderTracks(tracks, me),
        )
    }

    /** Удалить запись может автор или хозяин комнаты. */
    @Transactional
    fun deleteEntry(me: UUID, entryId: UUID) {
        val e = GuestbookEntry.findById(entryId)
        if (e == null || e.deletedAt != null) throw ApiException.notFound("запись не найдена")
        if (e.authorId != me && e.ownerId != me) throw ApiException.forbidden("это не ваша запись и не ваша комната")
        e.deletedAt = Instant.now()
        pushRoomUpdated(e.ownerId, "guestbook")
    }

    // ================================================================ позвать в гости

    /**
     * Смотрю комнату [username] -> жму «Позвать в гости» -> её хозяину приходит
     * приглашение в МОЮ комнату. Не чаще раза в сутки одному человеку.
     */
    @Transactional
    fun invite(me: UUID, username: String): InviteOut {
        val target = ownerByUsername(username)
        if (target.id == me) throw bad("invalid_invite", "себя в гости не зовут")
        if (notifications.sentRecently(target.id, me, NotificationService.ROOM_INVITE, INVITE_COOLDOWN)) {
            return InviteOut(sent = false, reason = "already_sent")
        }
        val myName = AppUser.findById(me)?.username ?: throw ApiException.notFound("пользователь не найден")
        notifications.notify(target.id, NotificationService.ROOM_INVITE, me, mapOf("roomUsername" to myName))
        return InviteOut(sent = true)
    }

    // ================================================================ utils

    private fun ownerByUsername(username: String): AppUser =
        AppUser.find("username = ?1 and isDeleted = false", username.trim().lowercase()).firstResult()
            ?: throw ApiException.notFound("комната не найдена")

    private fun defaultRoom(owner: AppUser) = Room().also {
        it.ownerId = owner.id
        it.title = owner.username
        it.blocks = mapper.writeValueAsString(ALL_BLOCKS)
    }

    private fun ensureRoom(ownerId: UUID): Room {
        Room.findById(ownerId)?.let { return it }
        val owner = AppUser.findById(ownerId)
        if (owner == null || owner.isDeleted) throw ApiException.notFound("пользователь не найден")
        return defaultRoom(owner).also { it.persist() }
    }

    /** После правки отдаём комнату целиком (как GET) — фронт ждёт RoomView. */
    private fun touched(room: Room, what: String, detail: String? = null): RoomOut {
        room.updatedAt = Instant.now()
        detail?.let { logActivity(room.ownerId, it) }
        pushRoomUpdated(room.ownerId, what)
        val me = AppUser.findById(room.ownerId) ?: throw ApiException.notFound("пользователь не найден")
        return view(me.id, me.username)
    }

    private fun linksTouched(me: UUID): List<RoomLinkOut> {
        Room.findById(me)?.updatedAt = Instant.now()
        logActivity(me, "обновил(а) ссылки в комнате")
        pushRoomUpdated(me, "links")
        return links(me)
    }

    /**
     * «Перестановка в комнате» для ленты. Серия правок подряд (ползунки, несколько
     * полей) склеивается: если такая же запись была меньше 10 минут назад — только
     * сдвигаем её время, иначе лента друзей зарастёт одинаковыми строчками.
     */
    private fun logActivity(ownerId: UUID, detail: String) {
        val updated = em.createNativeQuery(
            "update room_activity set created_at = now() where id = (select id from room_activity " +
                    "where owner_id = ?1 and detail = ?2 and created_at > now() - interval '10 minutes' order by created_at desc limit 1)",
        ).setParameter(1, ownerId).setParameter(2, detail).executeUpdate()
        if (updated == 0) {
            em.createNativeQuery("insert into room_activity (id, owner_id, detail) values (?1, ?2, ?3)")
                .setParameter(1, UUID.randomUUID()).setParameter(2, ownerId).setParameter(3, detail).executeUpdate()
        }
    }

    private fun pushRoomUpdated(ownerId: UUID, what: String) {
        val frame = Envelope(t = FrameTypes.ROOM_UPDATED, d = mapper.valueToTree(RoomUpdatedOut(ownerId, what)))
        bus.publishToRoomViewers(ownerId, frame)
    }

    private fun look(room: Room): RoomLookOut {
        val wall = when {
            room.wallMediaId != null ->
                WallImageOut(media.url(room.wallMediaId!!), room.wallMediaId, room.wallFit, room.wallVeil, room.wallBlur)
            room.wallImageUrl != null ->
                WallImageOut(room.wallImageUrl!!, null, room.wallFit, room.wallVeil, room.wallBlur)
            else -> null
        }
        return RoomLookOut(
            title = room.title,
            mood = room.mood,
            about = room.about,
            sticker = room.sticker,
            theme = room.theme,
            wallpaper = room.wallpaper,
            wallImage = wall,
            wallImageUrl = wall?.url,
            wallFit = room.wallFit,
            wallVeil = room.wallVeil,
            wallBlur = room.wallBlur,
            tilt = room.tilt,
            dialect = room.dialect,
            words = runCatching { mapper.readValue(room.words, STRING_MAP) }.getOrDefault(emptyMap()),
            blocks = parseBlocks(room.blocks),
        )
    }

    private fun parseBlocks(json: String): List<String> =
        runCatching { mapper.readValue(json, STRING_LIST) }.getOrDefault(ALL_BLOCKS)

    private fun links(ownerId: UUID): List<RoomLinkOut> =
        RoomLink.list("ownerId = ?1 order by position, createdAt", ownerId)
            .map { linkOut(it) }

    private fun linkOut(l: RoomLink) = RoomLinkOut(l.id, l.title, l.url, l.position)

    private fun ownLink(me: UUID, id: UUID): RoomLink {
        val l = RoomLink.findById(id)
        if (l == null || l.ownerId != me) throw ApiException.notFound("ссылка не найдена")
        return l
    }

    private fun linkTitle(v: String?): String =
        v?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LINK_TITLE }
            ?: throw bad("invalid_link", "подпись ссылки: 1–$MAX_LINK_TITLE символов")

    private fun linkUrl(v: String?): String =
        v?.trim()?.takeIf { it.length <= MAX_URL && URL_RE.matches(it) }
            ?: throw bad("invalid_link", "ссылка: http(s)://… или путь внутри сайта, начинающийся с /")

    private fun guestsToday(ownerId: UUID): Long =
        (em.createNativeQuery(
            "select count(distinct visitor_id) from room_visit where owner_id = ?1 and last_at >= now() - interval '24 hours'",
        ).setParameter(1, ownerId).singleResult as Number).toLong()

    private fun friendsPage(viewer: UUID, ownerId: UUID, limit: Int, offset: Int): FriendsPageOut {
        val ids = friends.friendIdsOf(ownerId)
        val cards = profiles.shorts(ids).values.sortedBy { it.username }
        val page = cards.drop(offset).take(limit)
        // статус видно только тех, кого смотрящему можно видеть (друзья / общий чат)
        val statuses = presence.query(viewer, page.map { it.id }).associateBy { it.userId }
        return FriendsPageOut(
            items = page.map { FriendCardOut(it.id, it.username, it.avatar, it.color, statuses[it.id]) },
            total = cards.size.toLong(),
        )
    }

    /** Для ленты: записи гостевой пачкой по id (удалённые пропускаются). */
    @Transactional
    fun guestbookByIds(viewer: UUID, ids: Collection<UUID>): Map<UUID, GuestbookEntryOut> {
        if (ids.isEmpty()) return emptyMap()
        val rows = GuestbookEntry.list("id in ?1 and deletedAt is null", ids.distinct())
        val authors = profiles.shorts(rows.map { it.authorId })
        val files = attachments.load(AttachmentService.Owner.GUESTBOOK, rows.map { it.id })
        val entryTracks = attachments.loadTracks(AttachmentService.Owner.GUESTBOOK, rows.map { it.id }, viewer)
        return rows.mapNotNull { e ->
            authors[e.authorId]?.let {
                e.id to GuestbookEntryOut(
                    e.id, it, e.body, e.createdAt,
                    canDelete = viewer == e.authorId || viewer == e.ownerId,
                    attachments = files[e.id] ?: emptyList(),
                    tracks = entryTracks[e.id] ?: emptyList(),
                )
            }
        }.toMap()
    }

    private fun guestbookPage(viewer: UUID, ownerId: UUID, before: Instant?, limit: Int): GuestbookPageOut {
        val total = GuestbookEntry.count("ownerId = ?1 and deletedAt is null", ownerId)
        val rows = GuestbookEntry.find(
            "ownerId = ?1 and deletedAt is null and createdAt < ?2",
            Sort.descending("createdAt"),
            ownerId, before ?: Instant.now().plusSeconds(1),
        ).range(0, limit).list()
        val page = rows.take(limit)
        val authors = profiles.shorts(page.map { it.authorId })
        val files = attachments.load(AttachmentService.Owner.GUESTBOOK, page.map { it.id })
        val entryTracks = attachments.loadTracks(AttachmentService.Owner.GUESTBOOK, page.map { it.id }, viewer)
        return GuestbookPageOut(
            items = page.mapNotNull { e ->
                authors[e.authorId]?.let {
                    GuestbookEntryOut(
                        e.id, it, e.body, e.createdAt,
                        canDelete = viewer == e.authorId || viewer == ownerId,
                        attachments = files[e.id] ?: emptyList(),
                        tracks = entryTracks[e.id] ?: emptyList(),
                    )
                }
            },
            total = total,
            hasMore = rows.size > limit,
        )
    }

    private fun optionalText(v: String, max: Int, field: String): String? {
        val t = v.trim()
        if (t.length > max) throw bad("invalid_$field", "$field длиннее $max символов")
        return t.ifEmpty { null }
    }

    private fun oneOf(v: String, allowed: Set<String>, field: String): String {
        val x = v.trim().lowercase()
        if (x !in allowed) throw bad("invalid_$field", "$field: одно из $allowed")
        return x
    }

    private fun bad(code: String, msg: String) = ApiException.badRequest(code, msg)
}
