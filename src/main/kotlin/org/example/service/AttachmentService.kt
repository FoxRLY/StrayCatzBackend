package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Media
import org.example.rest.ApiException
import org.example.rest.AttachmentOut
import org.example.rest.TrackOut
import java.util.UUID

/**
 * Вложения к чему угодно: записи/пульс, комментарии, сообщения, гостевая.
 * Общие правила везде одинаковые:
 *  - файл сначала грузится в POST /api/media, сюда приходят его id;
 *  - прикреплять можно только свои загрузки;
 *  - до 4 картинок/гифок ИЛИ одно видео (видео — где разрешено).
 */
@ApplicationScoped
class AttachmentService(
    private val em: EntityManager,
    private val media: MediaService,
    private val music: MusicService,
) {
    enum class Owner(val db: String) { POST("post"), COMMENT("comment"), MESSAGE("message"), GUESTBOOK("guestbook"), MARKET("market") }

    companion object {
        const val MAX_ATTACHMENTS = 4

        fun kindOf(contentType: String) = when {
            contentType.startsWith("video/") -> "video"
            contentType == "image/gif" -> "gif"
            else -> "image"
        }
    }

    /**
     * Проверяет список id и возвращает файлы в том же порядке.
     * legacyId — старое одиночное поле mediaId, склеивается спереди.
     */
    @Transactional
    fun validate(owner: UUID, mediaIds: List<UUID>?, legacyId: UUID? = null, allowVideo: Boolean): List<Media> {
        val ids = (listOfNotNull(legacyId) + mediaIds.orEmpty()).distinct()
        if (ids.isEmpty()) return emptyList()
        if (ids.size > MAX_ATTACHMENTS) throw ApiException.badRequest("too_many_media", "не больше $MAX_ATTACHMENTS вложений")
        val files = ids.map { media.requireOwned(it, owner) }
        if (files.any { it.contentType.startsWith("audio/") }) {
            throw ApiException.badRequest("audio_as_media", "музыка прикрепляется треком: trackIds, а не mediaIds")
        }
        val videos = files.count { it.contentType.startsWith("video/") }
        if (videos > 0 && !allowVideo) throw ApiException.badRequest("video_not_allowed", "сюда можно только картинки и гифки")
        if (videos > 1 || (videos == 1 && files.size > 1)) {
            throw ApiException.badRequest("invalid_media", "видео — только одно и без картинок рядом")
        }
        return files
    }

    @Transactional
    fun attach(type: Owner, ownerId: UUID, files: List<Media>) {
        files.forEachIndexed { i, m ->
            em.createNativeQuery(
                "insert into media_attachment (owner_type, owner_id, media_id, position) values (?1, ?2, ?3, ?4) on conflict do nothing",
            ).setParameter(1, type.db).setParameter(2, ownerId).setParameter(3, m.id).setParameter(4, i).executeUpdate()
        }
    }

    /** Вложения пачкой: ownerId -> список по порядку. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun load(type: Owner, ownerIds: Collection<UUID>): Map<UUID, List<AttachmentOut>> {
        if (ownerIds.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery(
            """
            select a.owner_id, m.id, m.content_type from media_attachment a join media m on m.id = a.media_id
            where a.owner_type = ?1 and a.owner_id in (?2) order by a.owner_id, a.position
            """.trimIndent(),
        ).setParameter(1, type.db).setParameter(2, ownerIds.distinct()).resultList as List<Array<Any?>>
        return rows.groupBy({ it[0] as UUID }) {
            val id = it[1] as UUID
            val ct = it[2] as String
            AttachmentOut(id, media.url(id), ct, kindOf(ct))
        }
    }

    // ---------------------------------------------------------------- треки

    /** Треки из своей музыки (загруженные или добавленные себе), до 10. */
    fun validateTracks(owner: UUID, trackIds: List<UUID>?): List<UUID> = music.validateAttachable(owner, trackIds)

    @Transactional
    fun attachTracks(type: Owner, ownerId: UUID, trackIds: List<UUID>) {
        trackIds.forEachIndexed { i, t ->
            em.createNativeQuery(
                "insert into track_attachment (owner_type, owner_id, track_id, position) values (?1, ?2, ?3, ?4) on conflict do nothing",
            ).setParameter(1, type.db).setParameter(2, ownerId).setParameter(3, t).setParameter(4, i).executeUpdate()
        }
    }

    /** Прикреплённые треки пачкой. viewer = null — без inLibrary (например, для кадров сокета). */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun loadTracks(type: Owner, ownerIds: Collection<UUID>, viewer: UUID?): Map<UUID, List<TrackOut>> {
        if (ownerIds.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery(
            "select owner_id, track_id from track_attachment where owner_type = ?1 and owner_id in (?2) order by owner_id, position",
        ).setParameter(1, type.db).setParameter(2, ownerIds.distinct()).resultList as List<Array<Any?>>
        if (rows.isEmpty()) return emptyMap()
        val tracks = music.renderByIds(rows.map { it[1] as UUID }.distinct(), viewer).associateBy { it.id }
        return rows.groupBy({ it[0] as UUID }) { it[1] as UUID }
            .mapValues { (_, ids) -> ids.mapNotNull { tracks[it] } }
    }

    fun renderTracks(ids: List<UUID>, viewer: UUID?): List<TrackOut> = music.renderByIds(ids, viewer)

    fun render(files: List<Media>): List<AttachmentOut> =
        files.map { AttachmentOut(it.id, media.url(it.id), it.contentType, kindOf(it.contentType)) }
}
