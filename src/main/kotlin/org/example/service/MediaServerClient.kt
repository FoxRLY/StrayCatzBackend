package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Клиент API медиасервера MediaMTX (v3). Он принимает RTMP от OBS и сам
 * раздаёт поток зрителям (HLS / WebRTC) без перекодирования — бэкенд видео
 * не трогает, только спрашивает «какие пути сейчас в эфире» и кикает издателя.
 *
 * API MediaMTX нельзя открывать наружу: в docker-compose он слушает только localhost.
 */
@ApplicationScoped
class MediaServerClient(
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "straycatz.streams.api-url", defaultValue = "http://localhost:9997")
    private val apiUrl: String,
) {
    /** Путь медиасервера в эфире. sourceType/sourceId — кто публикует (для kick). */
    data class PathState(val name: String, val ready: Boolean, val sourceType: String?, val sourceId: String?)

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @Volatile
    private var lastWarn: Instant = Instant.EPOCH

    /** Все пути. null — медиасервер недоступен (тогда стримы не трогаем). */
    fun paths(): Map<String, PathState>? = try {
        val res = http.send(
            HttpRequest.newBuilder(URI.create("${apiUrl.trimEnd('/')}/v3/paths/list?itemsPerPage=1000"))
                .timeout(Duration.ofSeconds(3)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() != 200) {
            warn("MediaMTX /v3/paths/list ответил ${res.statusCode()}")
            null
        } else {
            mapper.readTree(res.body()).path("items").associate { item ->
                val src = item.path("source")
                val name = item.path("name").asText()
                name to PathState(
                    name = name,
                    ready = item.path("ready").asBoolean(false),
                    sourceType = src.path("type").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    sourceId = src.path("id").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                )
            }
        }
    } catch (e: Exception) {
        warn("MediaMTX недоступен ($apiUrl): ${e.message}")
        null
    }

    /** Отключить издателя (завершили эфир с сайта). Ошибки не критичны. */
    fun kick(sourceType: String?, sourceId: String?) {
        if (sourceType == null || sourceId == null) return
        val kind = when (sourceType.lowercase()) {
            "rtmpconn", "rtmpsconn" -> "rtmpconns"
            "rtspsession", "rtspssession" -> "rtspsessions"
            "srtconn" -> "srtconns"
            "webrtcsession" -> "webrtcsessions"
            else -> return
        }
        try {
            http.send(
                HttpRequest.newBuilder(URI.create("${apiUrl.trimEnd('/')}/v3/$kind/kick/$sourceId"))
                    .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        } catch (e: Exception) {
            Log.warnf("MediaMTX: не смогли кикнуть %s/%s: %s", sourceType, sourceId, e.message)
        }
    }

    /** Не спамим лог раз в 5 секунд, если медиасервер не запущен. */
    private fun warn(msg: String) {
        val now = Instant.now()
        if (Duration.between(lastWarn, now) > Duration.ofMinutes(5)) {
            lastWarn = now
            Log.warn("$msg — стримы не синхронизируются (проверь docker compose up mediamtx)")
        }
    }
}
