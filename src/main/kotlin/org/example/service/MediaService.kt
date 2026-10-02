package org.example.service

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.domain.Media
import org.example.rest.ApiException
import org.example.rest.MediaOut
import java.io.InputStream
import java.util.Optional
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class MediaService(
    private val storage: MediaStorage,
    /** «Загрузили видео» — VideoPosterService вытаскивает кадр и длительность, пока файл ещё лежит локально. */
    private val videoUploaded: Event<VideoUploaded>,
    @ConfigProperty(name = "straycatz.media.max-bytes", defaultValue = "10485760")
    private val maxBytes: Long,
    /**
     * Полный адрес API, если фронт и API на разных доменах. Optional, а не
     * String с defaultValue="": пустое значение в properties SmallRye Config
     * считает отсутствующим и падает на старте (SRCFG00040).
     */
    @ConfigProperty(name = "straycatz.media.public-base-url")
    private val publicBaseUrl: Optional<String>,
    @ConfigProperty(name = "straycatz.media.max-video-bytes", defaultValue = "52428800")
    private val maxVideoBytes: Long,
    @ConfigProperty(name = "straycatz.media.max-audio-bytes", defaultValue = "31457280")
    private val maxAudioBytes: Long,
) {
    private class Signature(val contentType: String, val ext: String, val matches: (ByteArray) -> Boolean)

    companion object {
        private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
            size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }

        /** Тип определяем по первым байтам файла, а не по тому, что прислал браузер. */
        private val SIGNATURES = listOf(
            Signature("image/png", "png") { b -> b.startsWith(0x89, 0x50, 0x4E, 0x47) },
            Signature("image/jpeg", "jpg") { b -> b.startsWith(0xFF, 0xD8, 0xFF) },
            Signature("image/gif", "gif") { b -> b.startsWith(0x47, 0x49, 0x46, 0x38) },
            Signature("image/webp", "webp") { b ->
                b.startsWith(0x52, 0x49, 0x46, 0x46) && b.size >= 12 &&
                        b[8] == 0x57.toByte() && b[9] == 0x45.toByte() && b[10] == 0x42.toByte() && b[11] == 0x50.toByte()
            },
            // аудио для музыки. m4a — тот же контейнер, что mp4, поэтому проверяем раньше видео
            Signature("audio/mp4", "m4a") { b ->
                b.size >= 12 && b[4] == 0x66.toByte() && b[5] == 0x74.toByte() && b[6] == 0x79.toByte() && b[7] == 0x70.toByte() &&
                        b[8] == 0x4D.toByte() && b[9] == 0x34.toByte() && b[10] == 0x41.toByte() // "M4A"
            },
            Signature("audio/mpeg", "mp3") { b ->
                b.startsWith(0x49, 0x44, 0x33) || // "ID3"
                        (b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) // MPEG frame sync
            },
            Signature("audio/ogg", "ogg") { b -> b.startsWith(0x4F, 0x67, 0x67, 0x53) }, // "OggS"
            Signature("audio/flac", "flac") { b -> b.startsWith(0x66, 0x4C, 0x61, 0x43) }, // "fLaC"
            Signature("audio/wav", "wav") { b ->
                b.startsWith(0x52, 0x49, 0x46, 0x46) && b.size >= 12 &&
                        b[8] == 0x57.toByte() && b[9] == 0x41.toByte() && b[10] == 0x56.toByte() && b[11] == 0x45.toByte()
            },
            // видео для пульса: mp4/mov (контейнер ISO BMFF, "ftyp" с 4-го байта) и webm
            Signature("video/mp4", "mp4") { b ->
                b.size >= 8 && b[4] == 0x66.toByte() && b[5] == 0x74.toByte() && b[6] == 0x79.toByte() && b[7] == 0x70.toByte()
            },
            Signature("video/webm", "webm") { b -> b.startsWith(0x1A, 0x45, 0xDF, 0xA3) },
        )
    }

    /**
     * @param onlyImages только картинки (аватар): видео/аудио отбиваются ДО записи в хранилище.
     * @param maxOverride свой лимит размера (например, для аватара), иначе — по типу файла.
     */
    fun upload(ownerId: UUID, file: Path, size: Long, onlyImages: Boolean = false, maxOverride: Long? = null): MediaOut {
        if (size <= 0) throw ApiException.badRequest("empty_file", "пустой файл")

        val head = Files.newInputStream(file).use { it.readNBytes(16) }
        val sig = SIGNATURES.firstOrNull { it.matches(head) }
            ?: throw ApiException(415, "unsupported_media", "поддерживаются png, jpeg, gif, webp, mp4, webm, mp3, m4a, ogg, flac, wav")
        if (onlyImages && !sig.contentType.startsWith("image/")) {
            throw ApiException(415, "not_image", "нужна картинка: png, jpeg, gif или webp")
        }
        // у видео и аудио свои лимиты — они заметно тяжелее картинок
        val limit = when {
            maxOverride != null -> maxOverride
            sig.contentType.startsWith("video/") -> maxVideoBytes
            sig.contentType.startsWith("audio/") -> maxAudioBytes
            else -> maxBytes
        }
        if (size > limit) throw ApiException(413, "file_too_large", "файл больше ${limit / 1024 / 1024} МБ")

        val id = UUID.randomUUID()
        val today = LocalDate.now()
        val key = "%d/%02d/%s.%s".format(today.year, today.monthValue, id, sig.ext)
        storage.put(key, file, sig.contentType)
        val out = try {
            save(id, ownerId, sig.contentType, size, key)
        } catch (e: Exception) {
            storage.delete(key)
            throw e
        }
        if (sig.contentType.startsWith("video/")) {
            // превью — не повод ронять загрузку
            try { videoUploaded.fire(VideoUploaded(ownerId, id, file)) } catch (e: Exception) {
                Log.warnf("превью видео %s не сделано: %s", id, e.message)
            }
        }
        return out
    }

    @Transactional
    fun find(id: UUID): Media? = Media.findById(id)

    fun open(m: Media): InputStream? = storage.open(m.storageKey)

    /** Адрес любой ручки API с учётом public-base-url (для ссылок, которые уходят на фронт). */
    fun publicUrl(path: String): String = "${publicBaseUrl.orElse("").trimEnd('/')}$path"

    /** Стабильный адрес картинки — он и хранится/отдаётся везде, от хранилища не зависит. */
    fun url(id: UUID): String = "${publicBaseUrl.orElse("").trimEnd('/')}/api/media/$id"

    /** Прямая ссылка в хранилище (S3: presigned или публичная), null — отдавать через API. */
    fun directUrl(m: Media): String? = storage.directUrl(m.storageKey)

    /**
     * Сохранить файл без проверки сигнатуры (импорт: стикеры Telegram .tgs и т.п.).
     * Тип и расширение задаёт вызывающий — он отвечает за то, что внутри.
     */
    fun storeRaw(ownerId: UUID, file: Path, size: Long, contentType: String, ext: String): MediaOut {
        val id = UUID.randomUUID()
        val today = LocalDate.now()
        val key = "%d/%02d/%s.%s".format(today.year, today.monthValue, id, ext)
        storage.put(key, file, contentType)
        return try {
            save(id, ownerId, contentType, size, key)
        } catch (e: Exception) {
            storage.delete(key)
            throw e
        }
    }

    /** Картинка существует и загружена этим пользователем (для обоев/аватаров). */
    @Transactional
    fun requireOwned(id: UUID, ownerId: UUID): Media {
        val m = Media.findById(id) ?: throw ApiException.notFound("картинка не найдена")
        if (m.ownerId != ownerId) throw ApiException.forbidden("можно использовать только свои картинки")
        return m
    }

    @Transactional
    fun save(id: UUID, ownerId: UUID, contentType: String, size: Long, key: String): MediaOut {
        val m = Media().also {
            it.id = id; it.ownerId = ownerId; it.contentType = contentType; it.sizeBytes = size; it.storageKey = key
        }
        m.persist()
        return MediaOut(m.id, url(m.id), m.contentType, m.sizeBytes, m.createdAt)
    }
}
