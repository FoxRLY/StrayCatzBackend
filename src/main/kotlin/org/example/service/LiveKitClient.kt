package org.example.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LiveKit без SDK: всё, что нужно бэкенду, — это
 *  1) токены доступа (JWT HS256 с грантом `video`) — клиент с ним подключается к комнате;
 *  2) несколько вызовов серверного API (Twirp поверх HTTP+JSON): создать/удалить комнату,
 *     выгнать участника, список участников;
 *  3) проверка подписи вебхуков (тот же JWT в заголовке Authorization + sha256 тела).
 *
 * Медиа через бэкенд не идёт вообще: браузеры шлют и получают его у LiveKit (SFU).
 */
@ApplicationScoped
class LiveKitClient(
    private val mapper: ObjectMapper,
    /** Адрес для клиентов (ws:// локально, wss:// в проде — через балансировщик). */
    @ConfigProperty(name = "straycatz.livekit.url", defaultValue = "ws://localhost:7880") val clientUrl: String,
    /** Адрес серверного API для бэкенда (обычно внутренний http://livekit:7880). */
    @ConfigProperty(name = "straycatz.livekit.api-url", defaultValue = "http://localhost:7880") private val apiUrl: String,
    @ConfigProperty(name = "straycatz.livekit.api-key", defaultValue = "devkey") private val apiKey: String,
    @ConfigProperty(name = "straycatz.livekit.api-secret", defaultValue = "") private val apiSecret: String,
    /**
     * Сколько живёт токен на ПОДКЛЮЧЕНИЕ. Уже подключённым LiveKit сам продлевает токен,
     * поэтому коротко: выгнанный не зайдёт обратно со старым токеном.
     */
    @ConfigProperty(name = "straycatz.livekit.token-ttl", defaultValue = "PT10M") private val tokenTtl: Duration,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun configured(): Boolean = apiSecret.length >= 32

    // ================================================================ токены

    /**
     * Токен участника. identity — id пользователя (по нему вебхуки и выгон),
     * name и metadata (JSON с аватаром/цветом) видят остальные участники.
     * sources — что можно публиковать: camera, microphone, screen_share, screen_share_audio.
     */
    fun participantToken(identity: String, name: String, metadata: String, room: String, sources: List<String>): String {
        val video = linkedMapOf<String, Any>(
            "room" to room,
            "roomJoin" to true,
            // пустой список источников — только смотреть/слушать (режим watch в голосовых каналах)
            "canPublish" to sources.isNotEmpty(),
            "canSubscribe" to true,
            "canPublishData" to true,
            "canPublishSources" to sources,
        )
        return sign(
            linkedMapOf(
                "iss" to apiKey,
                "sub" to identity,
                "name" to name,
                "metadata" to metadata,
                "jti" to UUID.randomUUID().toString(),
                "nbf" to Instant.now().epochSecond - 10,
                "exp" to Instant.now().plus(tokenTtl).epochSecond,
                "video" to video,
            ),
        )
    }

    /** Токен для серверного API (короткий). */
    private fun adminToken(room: String?): String {
        val video = linkedMapOf<String, Any>("roomAdmin" to true, "roomCreate" to true, "roomList" to true)
        if (room != null) video["room"] = room
        return sign(
            linkedMapOf(
                "iss" to apiKey,
                "sub" to "straycatz-backend",
                "nbf" to Instant.now().epochSecond - 10,
                "exp" to Instant.now().plusSeconds(60).epochSecond,
                "video" to video,
            ),
        )
    }

    // ================================================================ серверный API (Twirp)

    /** Создать комнату заранее: сколько ждать пустую, лимит участников. Не вышло — LiveKit создаст сам при входе. */
    fun createRoom(room: String, emptyTimeoutSec: Int, departureTimeoutSec: Int, maxParticipants: Int, metadata: String): Boolean =
        call(
            "CreateRoom", room,
            mapOf(
                "name" to room,
                "empty_timeout" to emptyTimeoutSec,
                "departure_timeout" to departureTimeoutSec,
                "max_participants" to maxParticipants,
                "metadata" to metadata,
            ),
        ) != null

    /** Закрыть комнату: всех отключит. */
    fun deleteRoom(room: String) {
        call("DeleteRoom", room, mapOf("room" to room))
    }

    /** Выгнать участника (его вкладки отключатся). */
    fun removeParticipant(room: String, identity: String) {
        call("RemoveParticipant", room, mapOf("room" to room, "identity" to identity))
    }

    /**
     * Что участнику можно публиковать прямо сейчас (без переподключения). Убрали microphone —
     * LiveKit сам снимет его микрофон («заглушить» модератором). sources — как в токене:
     * camera, microphone, screen_share, screen_share_audio.
     */
    fun updateSources(room: String, identity: String, sources: List<String>): Boolean =
        call(
            "UpdateParticipant", room,
            mapOf(
                "room" to room,
                "identity" to identity,
                "permission" to mapOf(
                    "can_subscribe" to true,
                    "can_publish" to sources.isNotEmpty(),
                    "can_publish_data" to true,
                    "can_publish_sources" to sources.map { it.uppercase() },
                ),
            ),
        ) != null

    /** Участники комнаты с их дорожками: identity → источники (MICROPHONE, CAMERA, SCREEN_SHARE…). null — LiveKit молчит. */
    fun listParticipantTracks(room: String): Map<String, Set<String>>? {
        val r = call("ListParticipants", room, mapOf("room" to room), notFoundIsEmpty = true) ?: return null
        return r.path("participants").filter { it.path("identity").asText().isNotEmpty() }.associate { p ->
            p.path("identity").asText() to p.path("tracks").map { it.path("source").asText() }.toSet()
        }
    }

    /**
     * Кто сейчас в комнате (identity). Пусто — комнаты нет или в ней никого;
     * null — LiveKit не ответил (ничего не решаем по такому ответу).
     */
    fun listParticipants(room: String): Set<String>? {
        val r = call("ListParticipants", room, mapOf("room" to room), notFoundIsEmpty = true) ?: return null
        return r.path("participants").mapNotNull { it.path("identity").asText().ifEmpty { null } }.toSet()
    }

    private fun call(method: String, room: String?, body: Map<String, Any?>, notFoundIsEmpty: Boolean = false): JsonNode? {
        if (!configured()) return null
        return try {
            val req = HttpRequest.newBuilder(URI.create("${apiUrl.trimEnd('/')}/twirp/livekit.RoomService/$method"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer ${adminToken(room)}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            when {
                resp.statusCode() in 200..299 -> mapper.readTree(resp.body().ifEmpty { "{}" })
                resp.statusCode() == 404 && notFoundIsEmpty -> mapper.createObjectNode()
                else -> {
                    Log.warnf("LiveKit %s: HTTP %d %s", method, resp.statusCode(), resp.body().take(300))
                    null
                }
            }
        } catch (e: Exception) {
            Log.warnf("LiveKit %s: %s", method, e.message)
            null
        }
    }

    // ================================================================ вебхуки

    /**
     * Проверить вебхук: в заголовке Authorization — JWT, подписанный нашим секретом,
     * iss = наш ключ, claim sha256 = base64(sha256(тело)). null — подделка.
     */
    fun verifyWebhook(authorization: String?, body: String): JsonNode? {
        if (!configured()) return null
        val jwt = authorization?.removePrefix("Bearer ")?.trim() ?: return null
        val claims = verify(jwt) ?: return null
        if (claims.path("iss").asText() != apiKey) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(StandardCharsets.UTF_8))
        val expected = setOf(Base64.getEncoder().encodeToString(digest), Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        val got = claims.path("sha256").asText()
        if (got !in expected && got.trimEnd('=') !in expected) return null
        return mapper.readTree(body)
    }

    // ================================================================ JWT HS256

    private fun sign(claims: Map<String, Any?>): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray(StandardCharsets.UTF_8))
        val payload = enc.encodeToString(mapper.writeValueAsBytes(claims))
        val sig = enc.encodeToString(hmac("$header.$payload"))
        return "$header.$payload.$sig"
    }

    private fun verify(jwt: String): JsonNode? {
        val parts = jwt.split('.')
        if (parts.size != 3) return null
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac("${parts[0]}.${parts[1]}"))
        if (!MessageDigest.isEqual(expected.toByteArray(), parts[2].trimEnd('=').toByteArray())) return null
        val claims = runCatching { mapper.readTree(Base64.getUrlDecoder().decode(parts[1])) }.getOrNull() ?: return null
        val now = Instant.now().epochSecond
        if (claims.has("exp") && claims.path("exp").asLong() < now - 60) return null
        if (claims.has("nbf") && claims.path("nbf").asLong() > now + 60) return null
        return claims
    }

    private fun hmac(data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }
}
