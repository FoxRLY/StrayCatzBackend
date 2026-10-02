package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.rest.ApiException
import org.example.rest.StickerOut
import org.example.rest.StickerPackIn
import org.example.rest.StickerPackOut
import java.time.Instant
import java.util.UUID

/**
 * Наборы стикеров как в телеге: человек собирает набор из своих картинок
 * (png/webp/gif, до 1 МБ, лучше квадрат 512×512 с прозрачностью), другие
 * добавляют набор себе. Внешние стикеры (поиск у провайдера) — в GifService, kind=sticker.
 */
@ApplicationScoped
class StickerService(
    private val em: EntityManager,
    private val media: MediaService,
    private val profiles: UserProfileService,
) {
    companion object {
        const val MAX_TITLE = 64
        const val MAX_STICKERS = 120
        const val MAX_PACKS = 50
        const val MAX_EMOJI = 16
        const val MAX_BYTES = 1L * 1024 * 1024
    }

    private data class PackRow(
        val id: UUID, val ownerId: UUID, val title: String, val isPublic: Boolean, val installs: Long,
        val source: String? = null, val sourceRef: String? = null,
    )

    // ================================================================ чтение

    /** Мои наборы со стикерами — для панели стикеров. */
    @Transactional
    fun mine(me: UUID): List<StickerPackOut> {
        val ids = ids(
            """
            select p.id from user_sticker_pack u join sticker_pack p on p.id = u.pack_id and p.deleted_at is null
            where u.user_id = ?1 and (p.is_public or p.owner_id = ?1) order by u.position, u.added_at
            """.trimIndent(),
            me,
        )
        return render(ids, me, withStickers = true)
    }

    /** Каталог публичных наборов: ?q= по названию, популярные сверху. */
    @Transactional
    fun catalog(me: UUID, q: String?, limit: Int, offset: Int): List<StickerPackOut> {
        val query = q?.trim()?.lowercase().orEmpty()
        val ids = if (query.isEmpty()) ids(
            "select id from sticker_pack where deleted_at is null and is_public order by installs desc, created_at desc limit ?1 offset ?2",
            limit.coerceIn(1, 50), offset.coerceAtLeast(0),
        ) else ids(
            "select id from sticker_pack where deleted_at is null and is_public and lower(title) like ?1 order by installs desc limit ?2 offset ?3",
            "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", limit.coerceIn(1, 50), offset.coerceAtLeast(0),
        )
        return render(ids, me, withStickers = false)
    }

    @Transactional
    fun get(me: UUID, packId: UUID): StickerPackOut {
        val p = pack(packId)
        if (!p.isPublic && p.ownerId != me) throw ApiException.notFound("набор не найден")
        return render(listOf(packId), me, withStickers = true).first()
    }

    /** Недавние стикеры. */
    @Transactional
    fun recent(me: UUID, limit: Int): List<StickerOut> {
        val ids = ids(
            """
            select r.sticker_id from recent_sticker r join sticker s on s.id = r.sticker_id and s.deleted_at is null
            join sticker_pack p on p.id = s.pack_id and p.deleted_at is null
            where r.user_id = ?1 order by r.used_at desc limit ?2
            """.trimIndent(),
            me, limit.coerceIn(1, 50),
        )
        val byId = byIds(ids)
        return ids.mapNotNull { byId[it] }
    }

    // ================================================================ мои наборы

    @Transactional
    fun create(me: UUID, req: StickerPackIn): StickerPackOut {
        val title = title(req.title) ?: throw ApiException.badRequest("invalid_title", "название: 1–$MAX_TITLE символов")
        val count = (em.createNativeQuery("select count(*) from sticker_pack where owner_id = ?1 and deleted_at is null")
            .setParameter(1, me).singleResult as Number).toLong()
        if (count >= MAX_PACKS) throw ApiException.badRequest("too_many_packs", "не больше $MAX_PACKS своих наборов")
        val id = UUID.randomUUID()
        em.createNativeQuery("insert into sticker_pack (id, owner_id, title, is_public) values (?1, ?2, ?3, ?4)")
            .setParameter(1, id).setParameter(2, me).setParameter(3, title).setParameter(4, req.isPublic ?: true).executeUpdate()
        install(me, id)
        return get(me, id)
    }

    @Transactional
    fun update(me: UUID, packId: UUID, req: StickerPackIn): StickerPackOut {
        own(me, packId)
        req.title?.let {
            val t = title(it) ?: throw ApiException.badRequest("invalid_title", "название: 1–$MAX_TITLE символов")
            em.createNativeQuery("update sticker_pack set title = ?2, updated_at = now() where id = ?1").setParameter(1, packId).setParameter(2, t).executeUpdate()
        }
        req.isPublic?.let {
            em.createNativeQuery("update sticker_pack set is_public = ?2, updated_at = now() where id = ?1").setParameter(1, packId).setParameter(2, it).executeUpdate()
        }
        return get(me, packId)
    }

    /** Удалить набор: пропадает у всех; уже отправленные стикеры в чатах остаются картинками. */
    @Transactional
    fun delete(me: UUID, packId: UUID) {
        own(me, packId)
        em.createNativeQuery("update sticker_pack set deleted_at = now() where id = ?1").setParameter(1, packId).executeUpdate()
    }

    /** Добавить стикер в свой набор (картинка уже загружена: POST /api/media или multipart-ручка). */
    @Transactional
    fun addSticker(me: UUID, packId: UUID, mediaId: UUID, emoji: String?): StickerPackOut {
        own(me, packId)
        val m = media.requireOwned(mediaId, me)
        if (!m.contentType.startsWith("image/")) throw ApiException.badRequest("not_image", "стикер — картинка: png, webp или gif")
        if (m.sizeBytes > MAX_BYTES) throw ApiException(413, "file_too_large", "стикер больше 1 МБ")
        val e = emoji?.trim()?.takeIf { it.isNotEmpty() }
        if (e != null && e.length > MAX_EMOJI) throw ApiException.badRequest("invalid_emoji", "emoji длиннее $MAX_EMOJI")
        val count = (em.createNativeQuery("select count(*) from sticker where pack_id = ?1 and deleted_at is null")
            .setParameter(1, packId).singleResult as Number).toInt()
        if (count >= MAX_STICKERS) throw ApiException.badRequest("pack_full", "в наборе не больше $MAX_STICKERS стикеров")
        em.createNativeQuery("insert into sticker (id, pack_id, media_id, emoji, position) values (?1, ?2, ?3, ?4, ?5)")
            .setParameter(1, UUID.randomUUID()).setParameter(2, packId).setParameter(3, mediaId)
            .setParameter(4, e ?: "").setParameter(5, count).executeUpdate()
        em.createNativeQuery("update sticker_pack set updated_at = now() where id = ?1").setParameter(1, packId).executeUpdate()
        return get(me, packId)
    }

    @Transactional
    fun deleteSticker(me: UUID, stickerId: UUID): StickerPackOut {
        val packId = ids("select pack_id from sticker where id = ?1 and deleted_at is null", stickerId).firstOrNull()
            ?: throw ApiException.notFound("стикер не найден")
        own(me, packId)
        em.createNativeQuery("update sticker set deleted_at = now() where id = ?1").setParameter(1, stickerId).executeUpdate()
        return get(me, packId)
    }

    @Transactional
    fun reorder(me: UUID, packId: UUID, stickerIds: List<UUID>): StickerPackOut {
        own(me, packId)
        stickerIds.forEachIndexed { i, id ->
            em.createNativeQuery("update sticker set position = ?3 where id = ?1 and pack_id = ?2")
                .setParameter(1, id).setParameter(2, packId).setParameter(3, i).executeUpdate()
        }
        return get(me, packId)
    }

    // ================================================================ добавить себе

    @Transactional
    fun install(me: UUID, packId: UUID): StickerPackOut {
        val p = pack(packId)
        if (!p.isPublic && p.ownerId != me) throw ApiException.notFound("набор не найден")
        val added = em.createNativeQuery(
            """
            insert into user_sticker_pack (user_id, pack_id, position)
            values (?1, ?2, coalesce((select max(position) + 1 from user_sticker_pack where user_id = ?1), 0))
            on conflict do nothing
            """.trimIndent(),
        ).setParameter(1, me).setParameter(2, packId).executeUpdate()
        if (added > 0) em.createNativeQuery("update sticker_pack set installs = installs + 1 where id = ?1").setParameter(1, packId).executeUpdate()
        return render(listOf(packId), me, withStickers = true).first()
    }

    @Transactional
    fun uninstall(me: UUID, packId: UUID) {
        val removed = em.createNativeQuery("delete from user_sticker_pack where user_id = ?1 and pack_id = ?2")
            .setParameter(1, me).setParameter(2, packId).executeUpdate()
        if (removed > 0) em.createNativeQuery("update sticker_pack set installs = greatest(installs - 1, 0) where id = ?1").setParameter(1, packId).executeUpdate()
    }

    // ================================================================ для сообщений

    /** Стикер можно отправить, если набор публичный или мой. Заодно — в «недавние». */
    @Transactional
    fun use(me: UUID, stickerId: UUID): UUID {
        @Suppress("UNCHECKED_CAST")
        val row = em.createNativeQuery(
            """
            select p.is_public, p.owner_id from sticker s join sticker_pack p on p.id = s.pack_id and p.deleted_at is null
            where s.id = ?1 and s.deleted_at is null
            """.trimIndent(),
        ).setParameter(1, stickerId).resultList.firstOrNull() as Array<Any?>? ?: throw ApiException.notFound("стикер не найден")
        if (!(row[0] as Boolean) && row[1] != me) throw ApiException.notFound("стикер не найден")
        em.createNativeQuery(
            "insert into recent_sticker (user_id, sticker_id) values (?1, ?2) on conflict (user_id, sticker_id) do update set used_at = now()",
        ).setParameter(1, me).setParameter(2, stickerId).executeUpdate()
        return stickerId
    }

    /** Стикеры пачкой (в т.ч. удалённые — в старых сообщениях они остаются картинкой). */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun byIds(ids: Collection<UUID>): Map<UUID, StickerOut> {
        if (ids.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery("select id, pack_id, media_id, emoji, format from sticker where id in (?1)")
            .setParameter(1, ids.distinct()).resultList as List<Array<Any?>>
        return rows.associate { r ->
            r[0] as UUID to StickerOut(r[0] as UUID, r[1] as UUID, media.url(r[2] as UUID), (r[3] as String?)?.ifEmpty { null }, r[4] as String)
        }
    }

    // ================================================================ внутреннее

    @Suppress("UNCHECKED_CAST")
    private fun render(ids: List<UUID>, me: UUID, withStickers: Boolean): List<StickerPackOut> {
        if (ids.isEmpty()) return emptyList()
        val packs = (em.createNativeQuery(
            "select id, owner_id, title, is_public, installs, source, source_ref from sticker_pack where id in (?1) and deleted_at is null",
        ).setParameter(1, ids).resultList as List<Array<Any?>>)
            .map { PackRow(it[0] as UUID, it[1] as UUID, it[2] as String, it[3] as Boolean, (it[4] as Number).toLong(), it[5] as String?, it[6] as String?) }
            .associateBy { it.id }
        val stickers = (em.createNativeQuery(
            "select id, pack_id, media_id, emoji, format from sticker where pack_id in (?1) and deleted_at is null order by pack_id, position, created_at",
        ).setParameter(1, ids).resultList as List<Array<Any?>>)
            .groupBy({ it[1] as UUID }) { StickerOut(it[0] as UUID, it[1] as UUID, media.url(it[2] as UUID), (it[3] as String?)?.ifEmpty { null }, it[4] as String) }
        // импорт идёт или закончился меньше суток назад — показываем ход
        val imports = (em.createNativeQuery(
            "select pack_id, status, done, total, error from sticker_import where pack_id in (?1) and (status <> 'done' or updated_at > now() - interval '1 day')",
        ).setParameter(1, ids).resultList as List<Array<Any?>>)
            .associate { it[0] as UUID to org.example.rest.StickerImportOut(it[1] as String, (it[2] as Number).toInt(), (it[3] as Number).toInt(), it[4] as String?) }
        val installed = ids("select pack_id from user_sticker_pack where user_id = ?1 and pack_id in (?2)", me, ids).toSet()
        val owners = profiles.shorts(packs.values.map { it.ownerId })
        return ids.mapNotNull { id ->
            val p = packs[id] ?: return@mapNotNull null
            val list = stickers[id] ?: emptyList()
            StickerPackOut(
                p.id, p.title, owners[p.ownerId], p.isPublic, p.installs, list.size, list.firstOrNull(),
                installed = id in installed, mine = p.ownerId == me, stickers = if (withStickers) list else null,
                source = p.source,
                sourceUrl = if (p.source == "telegram") "https://t.me/addstickers/${p.sourceRef}" else null,
                importing = imports[id],
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pack(id: UUID): PackRow {
        val r = em.createNativeQuery("select id, owner_id, title, is_public, installs from sticker_pack where id = ?1 and deleted_at is null")
            .setParameter(1, id).resultList.firstOrNull() as Array<Any?>? ?: throw ApiException.notFound("набор не найден")
        return PackRow(r[0] as UUID, r[1] as UUID, r[2] as String, r[3] as Boolean, (r[4] as Number).toLong())
    }

    private fun own(me: UUID, packId: UUID) {
        if (pack(packId).ownerId != me) throw ApiException.forbidden("это не твой набор")
    }

    private fun title(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE }

    @Suppress("UNCHECKED_CAST")
    private fun ids(sql: String, vararg params: Any): List<UUID> {
        val q = em.createNativeQuery(sql, UUID::class.java)
        params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
        return q.resultList as List<UUID>
    }

    @Suppress("unused")
    private fun now() = Instant.now()
}
