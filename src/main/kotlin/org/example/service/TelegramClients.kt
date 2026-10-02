package org.example.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional

// ================================================================ публичная веб-версия каналов (t.me/s/…)

data class TgChannelInfo(
    val username: String,
    val title: String,
    val description: String?,
    val avatarUrl: String?,
    val subscribers: Int?,
)

data class TgPost(
    /** Номер поста в канале (t.me/<канал>/<id>). */
    val id: Long,
    val date: Instant?,
    val text: String,
    val photos: List<String>,
    val videos: List<String>,
    /** Есть медиа, которое веб-версия не отдаёт (большое видео, документ, опрос…). */
    val unsupported: Boolean,
)

data class TgPage(val info: TgChannelInfo?, val posts: List<TgPost>, val error: String?)

/**
 * Читает публичную веб-версию канала https://t.me/s/<username> — ту же, что видна
 * в браузере без входа: шапка канала и последние ~20 постов, листается ?before=<id>.
 * Работает только для публичных каналов с включённым предпросмотром. Бот и
 * MTProto-аккаунт для этого не нужны.
 */
@ApplicationScoped
class TelegramWeb(private val http: OutboundHttp) {
    companion object {
        val USERNAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")
        private val BG_URL = Regex("""url\(['"]?(.*?)['"]?\)""")

        /** «https://t.me/name», «t.me/s/name», «@name», «name», «https://t.me/name/123» → name. */
        fun usernameOf(link: String?): String? {
            val v = link?.trim()?.removePrefix("@") ?: return null
            val path = v.replace(Regex("^(https?://)?(www\\.)?(t\\.me|telegram\\.me)/"), "")
                .removePrefix("s/").substringBefore('/').substringBefore('?')
            return path.takeIf { USERNAME.matches(it) }
        }

        /** «12.5K», «1,2M», «3 456» → число. */
        fun parseCount(raw: String?): Int? {
            val v = raw?.trim()?.replace(' ', ' ')?.replace(" ", "")?.replace(',', '.')?.uppercase() ?: return null
            val mult = when {
                v.endsWith("K") -> 1_000.0
                v.endsWith("M") -> 1_000_000.0
                else -> 1.0
            }
            return v.trimEnd('K', 'M').toDoubleOrNull()?.let { (it * mult).toInt() }
        }
    }

    fun channel(username: String, before: Long? = null): TgPage {
        val url = "https://t.me/s/$username" + (before?.let { "?before=$it" } ?: "")
        val r = http.getString(url, accept = "text/html")
        if (!r.ok) return TgPage(null, emptyList(), "t.me: ${r.error}")
        return try {
            parse(username, r.body!!)
        } catch (e: Exception) {
            Log.warnf("t.me/s/%s: не разобрали страницу: %s", username, e.message)
            TgPage(null, emptyList(), "не разобрали страницу t.me: ${e.message}")
        }
    }

    fun parse(username: String, html: String): TgPage {
        val doc = Jsoup.parse(html, "https://t.me/")
        val title = doc.selectFirst(".tgme_channel_info_header_title")?.text()?.trim()
        // нет шапки канала — это не публичный канал (бот, человек, группа, закрытый предпросмотр)
        val info = title?.takeIf { it.isNotEmpty() }?.let {
            TgChannelInfo(
                username = doc.selectFirst(".tgme_channel_info_header_username a")?.text()?.removePrefix("@")?.takeIf { u -> USERNAME.matches(u) }
                    ?: username,
                title = it,
                description = doc.selectFirst(".tgme_channel_info_description")?.let { d -> richText(d) }?.ifEmpty { null },
                avatarUrl = doc.selectFirst(".tgme_channel_info_header .tgme_page_photo_image img, .tgme_page_photo_image img")?.absUrl("src")
                    ?.ifEmpty { null },
                subscribers = doc.select(".tgme_channel_info_counter").firstOrNull { c ->
                    val type = c.selectFirst(".counter_type")?.text()?.lowercase().orEmpty()
                    "subscriber" in type || "подписчик" in type
                }?.selectFirst(".counter_value")?.text()?.let { v -> parseCount(v) },
            )
        }
        val posts = doc.select(".tgme_widget_message[data-post]").mapNotNull { el -> post(el) }
        return TgPage(info, posts.sortedBy { it.id }, if (info == null) "это не публичный канал или у него закрыт предпросмотр" else null)
    }

    private fun post(el: Element): TgPost? {
        if (el.hasClass("service_message")) return null
        val id = el.attr("data-post").substringAfterLast('/').toLongOrNull() ?: return null
        val textEl = el.select(".tgme_widget_message_text").firstOrNull { t ->
            t.parents().none { it.hasClass("tgme_widget_message_reply") || it.hasClass("tgme_widget_message_forwarded_from") }
        }
        val photos = el.select(".tgme_widget_message_photo_wrap").mapNotNull { bg(it) }
        val videos = el.select("video.tgme_widget_message_video, .tgme_widget_message_video_player video, video.tgme_widget_message_roundvideo")
            .mapNotNull { v -> v.absUrl("src").ifEmpty { null } }
        val date = el.selectFirst(".tgme_widget_message_date time")?.attr("datetime")?.let {
            runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
        }
        val unsupported = el.selectFirst(".message_media_not_supported, .tgme_widget_message_document, .tgme_widget_message_poll") != null
        return TgPost(id, date, textEl?.let { richText(it) }.orEmpty(), photos.distinct(), videos.distinct(), unsupported)
    }

    private fun bg(e: Element): String? = BG_URL.find(e.attr("style"))?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

    /**
     * HTML поста → обычный текст: <br> — перенос строки, ссылки со своим текстом
     * получают адрес в скобках (иначе он теряется), эмодзи-картинки — своим символом.
     */
    private fun richText(el: Element): String {
        val c = el.clone()
        c.select("br").forEach { it.replaceWith(TextNode("\n")) }
        c.select("a[href]").forEach { a ->
            val href = a.absUrl("href")
            val text = a.text()
            val shown = text.removePrefix("https://").removePrefix("http://").removeSuffix("…").removeSuffix("...")
            val isPlain = text.startsWith("#") || text.startsWith("@") || shown.isEmpty() || href.contains(shown.take(20))
            if (href.startsWith("http") && !isPlain) a.appendText(" ($href)")
        }
        return c.wholeText().replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

// ================================================================ Bot API (наборы стикеров)

data class TgSticker(val fileId: String, val uniqueId: String, val emoji: String?, val format: String)

data class TgStickerSet(val name: String, val title: String, val stickers: List<TgSticker>)

/**
 * Telegram Bot API — только для импорта наборов стикеров: getStickerSet + getFile.
 * Нужен токен любого бота (@BotFather → /newbot), в канал/чат его добавлять не надо.
 */
@ApplicationScoped
class TelegramBot(
    private val http: OutboundHttp,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "straycatz.telegram.bot-token") private val token: Optional<String>,
) {
    fun configured(): Boolean = token.filter { it.isNotBlank() }.isPresent

    /** null + причина, если не вышло. */
    fun stickerSet(name: String): Pair<TgStickerSet?, String?> {
        val root = call("getStickerSet?name=${URLEncoder.encode(name, StandardCharsets.UTF_8)}") ?: return null to lastError
        if (!root.path("ok").asBoolean(false)) return null to (root.path("description").asText("набор не найден"))
        val r = root.path("result")
        val stickers = r.path("stickers").map { s ->
            TgSticker(
                fileId = s.path("file_id").asText(),
                uniqueId = s.path("file_unique_id").asText(),
                emoji = s.path("emoji").asText().ifEmpty { null },
                format = when {
                    s.path("is_video").asBoolean(false) -> "video"
                    s.path("is_animated").asBoolean(false) -> "animated"
                    else -> "static"
                },
            )
        }.filter { it.fileId.isNotEmpty() }
        return TgStickerSet(r.path("name").asText(name), r.path("title").asText(name), stickers) to null
    }

    /** Скачать файл по file_id. true — получилось. */
    fun download(fileId: String, target: Path): Boolean {
        val root = call("getFile?file_id=${URLEncoder.encode(fileId, StandardCharsets.UTF_8)}") ?: return false
        val path = root.path("result").path("file_path").asText()
        if (path.isEmpty()) return false
        return http.getFile("https://api.telegram.org/file/bot${token.get()}/$path", target).ok
    }

    @Volatile
    var lastError: String? = null
        set

    private fun call(method: String): JsonNode? {
        val t = token.filter { it.isNotBlank() }.orElse(null) ?: run { lastError = "нет straycatz.telegram.bot-token"; return null }
        val r = http.getString("https://api.telegram.org/bot$t/$method")
        // 400 у Bot API тоже JSON с description — разбираем и его
        val body = r.body ?: run { lastError = "api.telegram.org: ${r.error}"; return null }
        return try {
            mapper.readTree(body).also { lastError = null }
        } catch (e: Exception) {
            lastError = "api.telegram.org: ${r.error ?: "не JSON"}"
            null
        }
    }
}
