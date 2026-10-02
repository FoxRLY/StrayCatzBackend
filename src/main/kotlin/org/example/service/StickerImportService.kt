package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.rest.ApiException
import org.example.rest.StickerPackOut
import java.nio.file.Files
import java.util.UUID

/**
 * Импорт набора стикеров Telegram по ссылке t.me/addstickers/ИМЯ.
 *
 * Bot API (getStickerSet) отдаёт список стикеров; сам набор создаётся сразу
 * (у системного пользователя «telegram», публичный, добавлен импортирующему),
 * а файлы докачиваются фоном по 10 штук раз в 5 секунд — пакет на 120
 * стикеров собирается примерно за минуту. Один и тот же набор Telegram
 * импортируется один раз: второй раз ссылка просто добавит его себе.
 */
@ApplicationScoped
class StickerImportService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bot: TelegramBot,
    private val stickers: StickerService,
    private val writer: StickerImportWriter,
) {
    companion object {
        private val NAME = Regex("^[A-Za-z0-9_]{1,64}$")

        /** «https://t.me/addstickers/Name», «tg://addstickers?set=Name», «Name» → Name. */
        fun setNameOf(link: String?): String? {
            val v = link?.trim() ?: return null
            val name = when {
                "addstickers/" in v -> v.substringAfter("addstickers/").substringBefore('?').substringBefore('/')
                "set=" in v -> v.substringAfter("set=").substringBefore('&')
                else -> v
            }
            return name.takeIf { NAME.matches(it) }
        }
    }

    fun importFromTelegram(me: UUID, link: String?): StickerPackOut {
        val name = setNameOf(link) ?: throw ApiException.badRequest("invalid_link", "нужна ссылка вида https://t.me/addstickers/ИМЯ")
        writer.existing(name)?.let { return stickers.install(me, it) }
        if (!bot.configured()) throw ApiException(503, "telegram_not_configured", "импорт стикеров не настроен: нужен straycatz.telegram.bot-token")
        val (set, error) = bot.stickerSet(name)
        if (set == null) throw ApiException(422, "sticker_set_not_found", error ?: "набор не найден")
        if (set.stickers.isEmpty()) throw ApiException(422, "sticker_set_empty", "в наборе нет стикеров")
        val packId = writer.create(me, set, mapper.writeValueAsString(set.stickers.take(StickerService.MAX_STICKERS)))
        return stickers.install(me, packId)
    }

    /** Фон: следующая пачка стикеров одного импорта. */
    @Scheduled(every = "5s", delayed = "20s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun tick() {
        if (!bot.configured()) return
        val job = writer.next() ?: return
        val (packId, items, done) = job
        val batch = items.drop(done).take(10)
        var ok = done
        for (s in batch) {
            val tmp = Files.createTempFile("tgs-", ".bin")
            try {
                if (bot.download(s.fileId, tmp)) writer.addSticker(packId, s, tmp)
                else Log.debugf("стикер %s не скачали: %s", s.uniqueId, bot.lastError)
            } catch (e: Exception) {
                Log.debugf("стикер %s: %s", s.uniqueId, e.message)
            } finally {
                Files.deleteIfExists(tmp)
            }
            ok++
        }
        writer.progress(packId, ok, ok >= items.size)
    }
}

@ApplicationScoped
class StickerImportWriter(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val media: MediaService,
) {
    @Transactional
    fun existing(name: String): UUID? =
        em.createNativeQuery(
            "select id from sticker_pack where source = 'telegram' and lower(source_ref) = lower(?1) and deleted_at is null",
            UUID::class.java,
        ).setParameter(1, name).resultList.firstOrNull() as UUID?

    @Transactional
    fun create(me: UUID, set: TgStickerSet, itemsJson: String): UUID {
        val id = UUID.randomUUID()
        em.createNativeQuery(
            "insert into sticker_pack (id, owner_id, title, is_public, source, source_ref) values (?1, ?2, ?3, true, 'telegram', ?4)",
        ).setParameter(1, id).setParameter(2, TelegramMirrorService.SYSTEM_USER).setParameter(3, set.title.take(StickerService.MAX_TITLE))
            .setParameter(4, set.name).executeUpdate()
        em.createNativeQuery(
            "insert into sticker_import (pack_id, set_name, total, items, requested_by) values (?1, ?2, ?3, cast(?4 as jsonb), ?5)",
        ).setParameter(1, id).setParameter(2, set.name).setParameter(3, mapper.readTree(itemsJson).size())
            .setParameter(4, itemsJson).setParameter(5, me).executeUpdate()
        return id
    }

    /** Самый старый незаконченный импорт: (packId, стикеры, сколько уже обработано). */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun next(): Triple<UUID, List<TgSticker>, Int>? {
        val r = em.createNativeQuery(
            "select pack_id, items::text, done from sticker_import where status in ('pending', 'running') order by created_at limit 1",
        ).resultList.firstOrNull() as Array<Any?>? ?: return null
        val packId = r[0] as UUID
        em.createNativeQuery("update sticker_import set status = 'running', updated_at = now() where pack_id = ?1").setParameter(1, packId).executeUpdate()
        val items = mapper.readTree(r[1] as String).map {
            TgSticker(it.path("fileId").asText(), it.path("uniqueId").asText(), it.path("emoji").asText().ifEmpty { null }, it.path("format").asText("static"))
        }
        return Triple(packId, items, (r[2] as Number).toInt())
    }

    /** Сохранить скачанный стикер. Проверяем, что внутри то, что обещано (webp / webm / gzip-Lottie). */
    @Transactional
    fun addSticker(packId: UUID, s: TgSticker, file: java.nio.file.Path) {
        val size = Files.size(file)
        if (size == 0L || size > StickerService.MAX_BYTES) return
        val head = Files.newInputStream(file).use { it.readNBytes(12) }
        fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { head[it] == b[it].toByte() }
        val (type, ext) = when (s.format) {
            "video" -> if (starts(0x1A, 0x45, 0xDF, 0xA3)) "video/webm" to "webm" else return
            "animated" -> if (starts(0x1F, 0x8B)) "application/x-tgsticker" to "tgs" else return
            else -> when {
                starts(0x52, 0x49, 0x46, 0x46) -> "image/webp" to "webp"
                starts(0x89, 0x50, 0x4E, 0x47) -> "image/png" to "png"
                else -> return
            }
        }
        val m = media.storeRaw(TelegramMirrorService.SYSTEM_USER, file, size, type, ext)
        em.createNativeQuery(
            """
            insert into sticker (id, pack_id, media_id, emoji, position, format, source_ref)
            values (?1, ?2, ?3, ?4, (select count(*) from sticker where pack_id = ?2), ?5, ?6)
            """.trimIndent(),
        ).setParameter(1, UUID.randomUUID()).setParameter(2, packId).setParameter(3, m.id)
            .setParameter(4, s.emoji ?: "").setParameter(5, s.format).setParameter(6, s.uniqueId).executeUpdate()
    }

    @Transactional
    fun progress(packId: UUID, done: Int, finished: Boolean) {
        em.createNativeQuery(
            "update sticker_import set done = ?2, status = case when ?3 then 'done' else 'running' end, updated_at = now() where pack_id = ?1",
        ).setParameter(1, packId).setParameter(2, done).setParameter(3, finished).executeUpdate()
        if (finished) {
            val n = (em.createNativeQuery("select count(*) from sticker where pack_id = ?1 and deleted_at is null")
                .setParameter(1, packId).singleResult as Number).toInt()
            if (n == 0) em.createNativeQuery("update sticker_import set status = 'failed', error = 'ни один стикер не скачался' where pack_id = ?1")
                .setParameter(1, packId).executeUpdate()
        }
    }
}
