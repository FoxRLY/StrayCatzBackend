package org.example.service

import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.domain.Community
import org.example.domain.CommunityMember
import org.example.domain.CommunityMemberId
import org.example.domain.Media
import org.example.domain.Post
import org.example.rest.ApiException
import org.example.rest.CommunityPageOut
import org.example.rest.MirrorOut
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Зеркала публичных Telegram-каналов («автоследование»).
 *
 * Кто угодно добавляет канал по ссылке → создаётся сообщество с названием,
 * описанием и аватаркой канала, дальше раз в несколько минут новые посты
 * канала переносятся туда записями (текст, фото, небольшие видео, хэштеги →
 * теги), с ссылкой на оригинал. Писать в зеркало нельзя никому; добавивший
 * может только поставить на паузу, обновить сейчас и скрыть запись.
 *
 * Источник — публичная веб-версия t.me/s/<канал> (TelegramWeb), без бота и аккаунта.
 * Правки и удаления постов в канале пока не отслеживаются.
 */
@ApplicationScoped
class TelegramMirrorService(
    private val em: EntityManager,
    private val web: TelegramWeb,
    private val communities: CommunityService,
    private val writer: TelegramMirrorWriter,
    @ConfigProperty(name = "straycatz.telegram.mirror.enabled", defaultValue = "true") private val enabled: Boolean,
    @ConfigProperty(name = "straycatz.telegram.mirror.per-user-per-day", defaultValue = "5") private val perUserPerDay: Int,
) {
    companion object {
        val SYSTEM_USER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000007e")
        const val FIRST_IMPORT = 20
        const val MAX_BACKFILL_PAGES = 3
        val SYNC_EVERY: Duration = Duration.ofMinutes(10)
        val META_EVERY: Duration = Duration.ofDays(1)
    }

    // ================================================================ добавить / список

    /** Добавить канал по ссылке. Уже есть зеркало — вернём его. */
    fun add(me: UUID, link: String?): CommunityPageOut {
        if (!enabled) throw ApiException(503, "mirror_disabled", "зеркала Telegram выключены")
        val username = TelegramWeb.usernameOf(link)
            ?: throw ApiException.badRequest("invalid_link", "нужна ссылка на публичный канал: https://t.me/имя или @имя")
        writer.existingSlug(username)?.let { return communities.page(it, me) }
        writer.checkQuota(me, perUserPerDay)

        val page = web.channel(username)
        val info = page.info ?: throw ApiException(422, "not_public_channel", page.error ?: "это не публичный канал")
        val avatar = info.avatarUrl?.let { writer.downloadImage(it) }
        val slug = writer.create(me, info, avatar)
        return communities.page(slug, me)
    }

    @Transactional
    fun list(me: UUID, limit: Int, offset: Int): List<MirrorOut> = writer.mirrors(me, null, limit, offset)

    // ================================================================ «базовое администрирование» (добавивший)

    @Transactional
    fun setPaused(me: UUID, slug: String, paused: Boolean): MirrorOut {
        val ch = writer.channelOf(slug)
        writer.requireCurator(ch, me)
        em.createNativeQuery("update telegram_channel set paused = ?2 where id = ?1").setParameter(1, ch.id).setParameter(2, paused).executeUpdate()
        return writer.mirrors(me, ch.communityId, 1, 0).first()
    }

    /** Обновить сейчас (не чаще раза в минуту). */
    fun syncNow(me: UUID, slug: String): MirrorOut {
        val ch = writer.channelOf(slug)
        writer.requireCurator(ch, me)
        if (ch.lastSyncedAt != null && ch.lastSyncedAt.isAfter(Instant.now().minusSeconds(60))) {
            throw ApiException(429, "too_often", "обновлять можно раз в минуту")
        }
        sync(ch.id)
        return writer.mirrors(me, ch.communityId, 1, 0).first()
    }

    /** Скрыть перенесённую запись (добавивший зеркало). */
    @Transactional
    fun hidePost(me: UUID, slug: String, postId: UUID) {
        val ch = writer.channelOf(slug)
        writer.requireCurator(ch, me)
        val p = Post.findById(postId)
        if (p == null || p.communityId != ch.communityId) throw ApiException.notFound("запись не найдена")
        p.isDeleted = true
        p.deletedAt = Instant.now()
    }

    // ================================================================ синхронизация

    /**
     * Перенести новые посты канала. Первый раз — последние 20; дальше всё, что новее
     * last_post_id (если накопилось больше страницы — догоняем до 3 страниц назад).
     */
    fun sync(channelId: UUID) {
        val ch = writer.channel(channelId) ?: return
        val first = web.channel(ch.username)
        if (first.info == null) {
            writer.failed(ch.id, first.error ?: "канал недоступен")
            return
        }
        val fresh = first.posts.filter { it.id > ch.lastPostId }.toMutableList()
        if (ch.lastPostId > 0) {
            var pages = 1
            var oldest = fresh.minOfOrNull { it.id }
            while (oldest != null && oldest > ch.lastPostId + 1 && pages < MAX_BACKFILL_PAGES) {
                val more = web.channel(ch.username, before = oldest).posts.filter { it.id > ch.lastPostId && it.id < oldest!! }
                if (more.isEmpty()) break
                fresh += more
                oldest = more.minOf { it.id }
                pages++
            }
        }
        val toImport = fresh.distinctBy { it.id }.sortedBy { it.id }
            .let { if (ch.lastPostId == 0L) it.takeLast(FIRST_IMPORT) else it }
        var last = ch.lastPostId
        for (p in toImport) {
            try {
                writer.importPost(ch.id, ch.communityId, ch.username, p)
                last = p.id
            } catch (e: Exception) {
                Log.warnf("зеркало %s, пост %d: %s", ch.username, p.id, e.message)
            }
        }
        val metaDue = ch.lastMetaAt == null || ch.lastMetaAt.isBefore(Instant.now().minus(META_EVERY))
        writer.synced(ch.id, last, if (metaDue) first.info else null)
    }

    @Scheduled(every = "2m", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun tick() {
        if (!enabled) return
        writer.due(SYNC_EVERY, 5).forEach { id ->
            try {
                sync(id)
            } catch (e: Exception) {
                Log.warnf("зеркало %s: %s", id, e.message)
                writer.failed(id, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

/** Всё, что пишет в БД, — отдельным бином, чтобы каждая запись шла своей транзакцией через прокси. */
@ApplicationScoped
class TelegramMirrorWriter(
    private val em: EntityManager,
    private val media: MediaService,
    private val http: OutboundHttp,
    private val tags: TagService,
    private val attachments: AttachmentService,
    private val profiles: UserProfileService,
) {
    data class Channel(
        val id: UUID, val username: String, val communityId: UUID, val lastPostId: Long,
        val lastSyncedAt: Instant?, val lastMetaAt: Instant?, val addedBy: UUID, val paused: Boolean,
    )

    @Transactional
    fun existingSlug(username: String): String? =
        (em.createNativeQuery(
            "select c.slug from telegram_channel t join community c on c.id = t.community_id where lower(t.username) = lower(?1)",
        ).setParameter(1, username).resultList.firstOrNull() as String?)

    @Transactional
    fun checkQuota(me: UUID, perDay: Int) {
        val n = (em.createNativeQuery("select count(*) from telegram_channel where added_by = ?1 and created_at > now() - interval '1 day'")
            .setParameter(1, me).singleResult as Number).toInt()
        if (n >= perDay) throw ApiException(429, "mirror_quota", "не больше $perDay каналов в сутки")
    }

    /** Создать сообщество-зеркало и запись о канале. Возвращает slug. */
    @Transactional
    fun create(me: UUID, info: TgChannelInfo, avatarMediaId: UUID?): String {
        val slug = freeSlug(info.username)
        val c = Community().also {
            it.id = UUID.randomUUID()
            it.slug = slug
            it.name = info.title.take(80)
            it.description = info.description?.take(2000)
            it.hue = Math.floorMod(info.username.lowercase().hashCode(), 360)
            it.avatar = avatarMediaId?.let { m -> media.url(m) }
            it.ownerId = TelegramMirrorService.SYSTEM_USER
            it.sections = """["posts", "media", "members"]"""
            it.source = "telegram"
            it.sourceRef = info.username
        }
        c.persist()
        CommunityMember().also { it.id = CommunityMemberId(c.id, TelegramMirrorService.SYSTEM_USER); it.role = "owner" }.persist()
        em.createNativeQuery(
            """
            insert into telegram_channel (id, username, community_id, title, description, subscribers, added_by, last_meta_at)
            values (?1, ?2, ?3, ?4, ?5, ?6, ?7, now())
            """.trimIndent(),
        ).setParameter(1, UUID.randomUUID()).setParameter(2, info.username).setParameter(3, c.id)
            .setParameter(4, info.title).setParameter(5, info.description ?: "").setParameter(6, info.subscribers ?: 0)
            .setParameter(7, me).executeUpdate()
        tags.sync(TagService.Owner.COMMUNITY, c.id, listOf("telegram"), c.description)
        // добавивший сразу следит за зеркалом (читает без вступления)
        em.createNativeQuery("insert into community_follow (community_id, user_id) values (?1, ?2) on conflict do nothing")
            .setParameter(1, c.id).setParameter(2, me).executeUpdate()
        return slug
    }

    /** Один пост канала → запись сообщества. Повтор (уже перенесён) — ничего не делает. */
    @Transactional
    fun importPost(channelId: UUID, communityId: UUID, username: String, p: TgPost) {
        val exists = em.createNativeQuery("select 1 from telegram_post where channel_id = ?1 and tg_post_id = ?2")
            .setParameter(1, channelId).setParameter(2, p.id).resultList.isNotEmpty()
        if (exists) return
        val files = mutableListOf<Media>()
        p.photos.take(10).forEach { url -> downloadImage(url)?.let { id -> Media.findById(id)?.let { files += it } } }
        p.videos.take(2).forEach { url -> downloadVideo(url)?.let { id -> Media.findById(id)?.let { files += it } } }
        var body = p.text
        if (p.unsupported && files.isEmpty()) body = (body + "\n\n[вложение — открой оригинал в Telegram]").trim()
        if (body.isBlank() && files.isEmpty()) return // пустой (стикер, опрос без текста…) — пропускаем
        val at = p.date ?: Instant.now()
        val post = Post().also {
            it.id = UUID.randomUUID()
            it.authorId = TelegramMirrorService.SYSTEM_USER
            it.communityId = communityId
            it.body = body.take(PostService.MAX_BODY)
            it.kind = when {
                files.any { f -> f.contentType.startsWith("video/") } -> "video"
                files.isNotEmpty() -> "image"
                else -> "text"
            }
            it.asCommunity = true
            it.mediaId = files.firstOrNull()?.id
            it.sourceUrl = "https://t.me/$username/${p.id}"
            it.createdAt = at
            it.updatedAt = at
        }
        post.persist()
        attachments.attach(AttachmentService.Owner.POST, post.id, files)
        tags.sync(TagService.Owner.POST, post.id, null, post.body)
        em.createNativeQuery("insert into telegram_post (channel_id, tg_post_id, post_id) values (?1, ?2, ?3)")
            .setParameter(1, channelId).setParameter(2, p.id).setParameter(3, post.id).executeUpdate()
    }

    /** Отметить успешную синхронизацию; info — заодно обновить шапку (раз в сутки). */
    @Transactional
    fun synced(channelId: UUID, lastPostId: Long, info: TgChannelInfo?) {
        em.createNativeQuery(
            "update telegram_channel set last_post_id = greatest(last_post_id, ?2), last_synced_at = now(), last_error = null, errors_in_row = 0 where id = ?1",
        ).setParameter(1, channelId).setParameter(2, lastPostId).executeUpdate()
        if (info == null) return
        val communityId = em.createNativeQuery("select community_id from telegram_channel where id = ?1", UUID::class.java)
            .setParameter(1, channelId).singleResult as UUID
        val c = Community.findById(communityId) ?: return
        if (c.source != "telegram") return // зеркало уже передали владельцу — шапку не трогаем
        c.name = info.title.take(80)
        c.description = info.description?.take(2000)
        info.avatarUrl?.let { url -> downloadImage(url)?.let { c.avatar = media.url(it) } }
        em.createNativeQuery(
            "update telegram_channel set title = ?2, description = ?3, subscribers = ?4, last_meta_at = now() where id = ?1",
        ).setParameter(1, channelId).setParameter(2, info.title).setParameter(3, info.description ?: "")
            .setParameter(4, info.subscribers ?: 0).executeUpdate()
    }

    @Transactional
    fun failed(channelId: UUID, error: String) {
        em.createNativeQuery(
            "update telegram_channel set last_synced_at = now(), last_error = ?2, errors_in_row = errors_in_row + 1 where id = ?1",
        ).setParameter(1, channelId).setParameter(2, error.take(500)).executeUpdate()
    }

    /** Кого пора синхронизировать: никогда не синхронизированные первыми; после 10 ошибок подряд — раз в 6 часов. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun due(every: Duration, n: Int): List<UUID> =
        em.createNativeQuery(
            """
            select t.id from telegram_channel t join community c on c.id = t.community_id and not c.is_deleted
            where not t.paused and c.source = 'telegram'
              and (t.last_synced_at is null
                   or (t.errors_in_row < 10 and t.last_synced_at < now() - make_interval(secs => ?1))
                   or t.last_synced_at < now() - interval '6 hours')
            order by t.last_synced_at nulls first limit ?2
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, every.seconds.toDouble()).setParameter(2, n).resultList as List<UUID>

    @Transactional
    fun channel(id: UUID): Channel? = channels("t.id = ?1", id).firstOrNull()

    @Transactional
    fun channelOf(slug: String): Channel =
        channels("c.slug = ?1", slug.lowercase()).firstOrNull() ?: throw ApiException.notFound("это не зеркало Telegram")

    fun requireCurator(ch: Channel, me: UUID) {
        if (ch.addedBy != me) throw ApiException.forbidden("управлять зеркалом может тот, кто его добавил")
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun mirrors(me: UUID, communityId: UUID?, limit: Int, offset: Int): List<org.example.rest.MirrorOut> {
        val rows = em.createNativeQuery(
            """
            select c.slug, c.name, c.avatar, c.hue, t.username, t.subscribers, t.paused, t.last_synced_at, t.last_error,
                   t.added_by, t.claimed_by, (select count(*) from telegram_post tp where tp.channel_id = t.id)
            from telegram_channel t join community c on c.id = t.community_id and not c.is_deleted
            ${if (communityId != null) "where t.community_id = ?3" else ""}
            order by t.created_at desc limit ?1 offset ?2
            """.trimIndent(),
        ).setParameter(1, limit.coerceIn(1, 100)).setParameter(2, offset.coerceAtLeast(0))
            .also { if (communityId != null) it.setParameter(3, communityId) }
            .resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.map { it[9] as UUID })
        return rows.map { r ->
            org.example.rest.MirrorOut(
                slug = r[0] as String,
                name = r[1] as String,
                avatar = r[2] as String?,
                hue = (r[3] as Number).toInt(),
                source = "telegram",
                username = r[4] as String,
                url = "https://t.me/${r[4]}",
                subscribers = (r[5] as Number?)?.toInt()?.takeIf { it > 0 },
                paused = r[6] as Boolean,
                lastSyncedAt = toInstant(r[7]),
                lastError = (r[8] as String?)?.ifEmpty { null },
                addedBy = users[r[9] as UUID],
                claimed = r[10] != null,
                posts = (r[11] as Number).toLong(),
                canManage = r[9] == me,
            )
        }
    }

    // ---------------------------------------------------------------- файлы

    /** Скачать картинку (аватар, фото поста) к себе. null — не вышло. */
    fun downloadImage(url: String): UUID? = download(url, onlyImages = true)

    fun downloadVideo(url: String): UUID? = download(url, onlyImages = false)

    private fun download(url: String, onlyImages: Boolean): UUID? {
        val tmp = Files.createTempFile("tg-", ".bin")
        return try {
            val r = http.getFile(url, tmp)
            val size = if (Files.exists(tmp)) Files.size(tmp) else 0L
            if (!r.ok || size == 0L) null
            else media.upload(TelegramMirrorService.SYSTEM_USER, tmp, size, onlyImages = onlyImages).id
        } catch (e: Exception) {
            Log.debugf("не скачали %s: %s", url, e.message)
            null
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    // ---------------------------------------------------------------- внутреннее

    @Suppress("UNCHECKED_CAST")
    private fun channels(where: String, param: Any): List<Channel> =
        (em.createNativeQuery(
            """
            select t.id, t.username, t.community_id, t.last_post_id, t.last_synced_at, t.last_meta_at, t.added_by, t.paused
            from telegram_channel t join community c on c.id = t.community_id where $where
            """.trimIndent(),
        ).setParameter(1, param).resultList as List<Array<Any?>>).map {
            Channel(
                it[0] as UUID, it[1] as String, it[2] as UUID, (it[3] as Number).toLong(),
                toInstant(it[4]), toInstant(it[5]), it[6] as UUID, it[7] as Boolean,
            )
        }

    /** tg username → свободный slug сообщества (a-z0-9-, до 32). */
    private fun freeSlug(username: String): String {
        val base = username.lowercase().replace('_', '-').trim('-').take(29).ifEmpty { "tg" }
        val candidates = listOf(base, "tg-$base".take(32)) + (2..50).map { "${base.take(28)}-$it" }
        return candidates.firstOrNull { s ->
            Community.count("lower(slug) = ?1", s) == 0L
        } ?: "tg-${UUID.randomUUID().toString().take(8)}"
    }

    private fun toInstant(v: Any?): Instant? = when (v) {
        null -> null
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> null
    }
}
