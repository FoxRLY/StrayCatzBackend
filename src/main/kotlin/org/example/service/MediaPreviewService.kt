package org.example.service

import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Событие: загружено видео, файл ещё лежит во временной папке запроса. */
data class VideoUploaded(val ownerId: UUID, val mediaId: UUID, val file: Path)

/**
 * ffmpeg / ffprobe как внешние программы. Нет на машине — превью просто не
 * делаются (один раз пишем в лог), всё остальное работает.
 */
@ApplicationScoped
class Ffmpeg(
    @ConfigProperty(name = "straycatz.ffmpeg.enabled", defaultValue = "true") private val enabled: Boolean,
    @ConfigProperty(name = "straycatz.ffmpeg.path", defaultValue = "ffmpeg") private val ffmpeg: String,
    @ConfigProperty(name = "straycatz.ffmpeg.ffprobe-path", defaultValue = "ffprobe") private val ffprobe: String,
) {
    private val ok: Boolean by lazy {
        if (!enabled) return@lazy false
        val found = run(listOf(ffmpeg, "-version"), 5) != null && run(listOf(ffprobe, "-version"), 5) != null
        if (!found) Log.warn("ffmpeg/ffprobe не найдены — превью видео и стримов выключены (straycatz.ffmpeg.path)")
        found
    }

    fun available(): Boolean = ok

    /**
     * Один кадр в JPEG шириной до 640. seekSec — откуда брать (для файла);
     * для эфира null. inputArgs — опции перед -i (таймауты сети).
     */
    fun frame(input: String, out: Path, seekSec: Double?, timeoutSec: Long, inputArgs: List<String> = emptyList()): Boolean {
        val cmd = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y")
        cmd += inputArgs
        seekSec?.let { cmd += listOf("-ss", it.toString()) }
        cmd += listOf("-i", input, "-frames:v", "1", "-vf", "scale='min(640,iw)':-2", "-q:v", "4", out.toString())
        return run(cmd, timeoutSec) != null && Files.exists(out) && Files.size(out) > 0
    }

    /** Длительность файла в секундах (округлённо), null — не вышло. */
    fun durationSec(file: Path): Int? =
        run(listOf(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.toString()), 10)
            ?.trim()?.toDoubleOrNull()?.let { Math.round(it).toInt() }?.takeIf { it >= 1 }

    /**
     * stdout или null (ошибка / таймаут). Вывод — во временный файл, а не в pipe:
     * чтение pipe блокировалось бы до конца процесса и таймаут бы не сработал.
     */
    private fun run(cmd: List<String>, timeoutSec: Long): String? {
        val outFile = Files.createTempFile("ffmpeg-", ".out")
        return try {
            val p = ProcessBuilder(cmd)
                .redirectOutput(outFile.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else if (p.exitValue() == 0) Files.readString(outFile) else null
        } catch (e: Exception) {
            null
        } finally {
            Files.deleteIfExists(outFile)
        }
    }
}

/**
 * Превью видео: при загрузке сервер вытаскивает кадр (с 1-й секунды) и
 * длительность и кладёт их в video_meta. Своя обложка автора (PATCH
 * /api/video/{id} posterMediaId) всегда главнее автоматической.
 * Для роликов, загруженных до этой версии, — фоновая догрузка по 3 штуки раз в 5 минут.
 */
@ApplicationScoped
class VideoPosterService(
    private val ffmpeg: Ffmpeg,
    private val storage: MediaStorage,
    private val media: MediaService,
    private val writer: VideoMetaWriter,
    private val lease: JobLease,
) {
    fun onVideoUploaded(@Observes e: VideoUploaded) {
        if (!ffmpeg.available()) return
        make(e.ownerId, e.mediaId, e.file)
    }

    /** true — превью сделано. */
    fun make(ownerId: UUID, mediaId: UUID, file: Path): Boolean {
        val jpg = Files.createTempFile("poster-", ".jpg")
        try {
            val got = ffmpeg.frame(file.toString(), jpg, 1.0, 20) || ffmpeg.frame(file.toString(), jpg, 0.0, 20)
            val duration = ffmpeg.durationSec(file)
            if (!got) {
                writer.apply(mediaId, null, duration, failed = true)
                return false
            }
            val id = UUID.randomUUID()
            val today = LocalDate.now()
            val key = "%d/%02d/%s.jpg".format(today.year, today.monthValue, id)
            storage.put(key, jpg, "image/jpeg")
            media.save(id, ownerId, "image/jpeg", Files.size(jpg), key)
            writer.apply(mediaId, id, duration, failed = false)
            return true
        } finally {
            Files.deleteIfExists(jpg)
        }
    }

    @Scheduled(every = "5m", delayed = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun backfill() {
        if (!ffmpeg.available()) return
        if (!lease.acquire("video-poster-backfill", java.time.Duration.ofMinutes(15))) return
        writer.pending(3).forEach { (mediaId, ownerId, key) ->
            val tmp = Files.createTempFile("video-", ".bin")
            try {
                val src = storage.open(key)
                if (src == null) { writer.apply(mediaId, null, null, failed = true); return@forEach }
                src.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
                make(ownerId, mediaId, tmp)
            } catch (e: Exception) {
                Log.warnf("превью видео %s: %s", mediaId, e.message)
                writer.apply(mediaId, null, null, failed = true)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}

/** Запись в video_meta отдельным бином — чтобы @Transactional работал через прокси. */
@ApplicationScoped
class VideoMetaWriter(private val em: EntityManager) {

    /** Ставит автообложку, только если своей нет; длительность — только если не указана. */
    @Transactional
    fun apply(mediaId: UUID, posterId: UUID?, durationSec: Int?, failed: Boolean) {
        em.createNativeQuery("insert into video_meta (media_id) values (?1) on conflict do nothing")
            .setParameter(1, mediaId).executeUpdate()
        posterId?.let {
            em.createNativeQuery(
                "update video_meta set poster_media_id = ?2, poster_auto = true, updated_at = now() " +
                    "where media_id = ?1 and poster_media_id is null",
            ).setParameter(1, mediaId).setParameter(2, it).executeUpdate()
        }
        durationSec?.let {
            em.createNativeQuery("update video_meta set duration_sec = ?2 where media_id = ?1 and duration_sec is null")
                .setParameter(1, mediaId).setParameter(2, it.coerceIn(1, 86400)).executeUpdate()
        }
        if (failed) {
            em.createNativeQuery("update video_meta set poster_failed_at = now() where media_id = ?1")
                .setParameter(1, mediaId).executeUpdate()
        }
    }

    /** Видео без обложки, которые ещё не пробовали: (mediaId, ownerId, storageKey). */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun pending(limit: Int): List<Triple<UUID, UUID, String>> =
        (em.createNativeQuery(
            """
            select m.id, m.owner_id, m.storage_key from media m
            left join video_meta vm on vm.media_id = m.id
            where m.content_type like 'video/%' and (vm.media_id is null or (vm.poster_media_id is null and vm.poster_failed_at is null))
            order by m.created_at desc limit ?1
            """.trimIndent(),
        ).setParameter(1, limit).resultList as List<Array<Any?>>).map { Triple(it[0] as UUID, it[1] as UUID, it[2] as String) }
}

/**
 * Живые кадры эфиров: раз в 20 секунд снимаем по кадру с каждого идущего эфира
 * (ffmpeg читает RTMP у MediaMTX, декодирует один ключевой кадр) и кладём в
 * хранилище по постоянному ключу streams/{id}/thumb.jpg. Это единственное место,
 * где сервер декодирует видео, — зрителям поток по-прежнему идёт без перекодирования.
 */
@ApplicationScoped
class StreamThumbnailJob(
    private val lease: JobLease,
    private val ffmpeg: Ffmpeg,
    private val storage: MediaStorage,
    private val streams: StreamService,
    @ConfigProperty(name = "straycatz.streams.thumb-source", defaultValue = "rtmp://localhost:1935/live")
    private val source: String,
    @ConfigProperty(name = "straycatz.streams.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    @Scheduled(every = "20s", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun snap() {
        if (!enabled || !ffmpeg.available()) return
        if (!lease.acquire("stream-thumbnails", java.time.Duration.ofSeconds(60))) return // ffmpeg — на одной ноде
        streams.thumbTargets(15).forEach { (id, code) ->
            val jpg = Files.createTempFile("thumb-", ".jpg")
            try {
                // -rw_timeout (мкс): не висеть, если эфир как раз оборвался
                if (ffmpeg.frame("${source.trimEnd('/')}/$code", jpg, null, 15, listOf("-rw_timeout", "8000000"))) {
                    val key = "streams/$id/thumb.jpg"
                    storage.put(key, jpg, "image/jpeg")
                    streams.setThumb(id, key)
                }
            } catch (e: Exception) {
                Log.debugf("кадр эфира %s: %s", id, e.message)
            } finally {
                Files.deleteIfExists(jpg)
            }
        }
    }
}
