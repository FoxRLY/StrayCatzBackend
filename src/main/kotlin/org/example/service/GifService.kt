package org.example.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.rest.ApiException
import org.example.rest.GifOut
import org.example.rest.GifPageOut
import org.example.rest.GifStatusOut
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Одна гифка/стикер в ответе провайдера. */
data class ProviderGif(
    val externalId: String,
    val title: String?,
    /** Что качаем к себе (webp/gif среднего размера). */
    val sourceUrl: String,
    /** Лёгкое превью для пикера. */
    val previewUrl: String?,
    val width: Int?,
    val height: Int?,
)

data class ProviderPage(val items: List<ProviderGif>, val hasNext: Boolean)

/** Внешний источник гифок. Возвращает null, если провайдер недоступен — тогда ищем у себя. */
interface GifProvider {
    val name: String
    fun search(kind: String, q: String, page: Int, perPage: Int, customer: String): ProviderPage?
    fun trending(kind: String, page: Int, perPage: Int, customer: String): ProviderPage?
}

/**
 * KLIPY (бывшая команда Tenor, бесплатно). Ключ — в пути:
 * GET https://api.klipy.com/api/v1/{key}/{gifs|stickers}/{search|trending}?q&page&per_page&customer_id&locale&content_filter
 * Ответ: {result, data: {data: [{id, slug, title, type, file: {hd|md|sm|xs: {gif|webp|mp4: {url, width, height}}}}], has_next}}
 */
class KlipyProvider(
    private val http: OutboundHttp,
    private val mapper: ObjectMapper,
    private val key: String,
    private val locale: String,
    private val contentFilter: String,
) : GifProvider {
    override val name = "klipy"

    override fun search(kind: String, q: String, page: Int, perPage: Int, customer: String) =
        call("${section(kind)}/search", "q=${enc(q)}&page=$page&per_page=${perPage.coerceIn(8, 50)}&customer_id=$customer&locale=$locale&content_filter=$contentFilter")

    override fun trending(kind: String, page: Int, perPage: Int, customer: String) =
        call("${section(kind)}/trending", "page=$page&per_page=${perPage.coerceIn(8, 50)}&customer_id=$customer&locale=$locale&content_filter=$contentFilter")

    private fun section(kind: String) = if (kind == "sticker") "stickers" else "gifs"

    private fun call(path: String, query: String): ProviderPage? {
        val root = getJson(http, mapper, "https://api.klipy.com/api/v1/$key/$path?$query") ?: return null
        if (root.has("result") && !root.path("result").asBoolean(true)) {
            Log.warnf("klipy: result=false — %s", root.path("message").asText(root.toString().take(200)))
            return null
        }
        // бывает {data: {data: [...], has_next}} и просто {data: [...]}
        val data = root.path("data")
        val list = if (data.isArray) data else data.path("data")
        val items = list.mapNotNull { item ->
            if (item.path("type").asText() == "ad") return@mapNotNull null // рекламные карточки не показываем
            val file = item.path("file")
            fun pick(vararg sizes: String, formats: List<String>): JsonNode? {
                for (s in sizes) for (f in formats) {
                    val n = file.path(s).path(f)
                    if (n.path("url").isTextual) return n
                }
                // запасной вариант: file.gif.url без размеров
                for (f in formats) if (file.path(f).path("url").isTextual) return file.path(f)
                return null
            }
            val source = pick("md", "sm", "hd", formats = listOf("webp", "gif")) ?: return@mapNotNull null
            val preview = pick("sm", "xs", "md", formats = listOf("webp", "gif"))
            ProviderGif(
                externalId = item.path("id").asText().ifEmpty { item.path("slug").asText() },
                title = item.path("title").asText().ifEmpty { null },
                sourceUrl = source.path("url").asText(),
                previewUrl = preview?.path("url")?.asText(),
                width = source.path("width").asInt(0).takeIf { it > 0 },
                height = source.path("height").asInt(0).takeIf { it > 0 },
            )
        }.filter { it.externalId.isNotEmpty() }
        return ProviderPage(items, data.path("has_next").asBoolean(false))
    }
}

/**
 * GIPHY. Бесплатный beta-ключ ~100 запросов в час — для разработки; для прода нужен
 * production-ключ (заявка в GIPHY). GET https://api.giphy.com/v1/{gifs|stickers}/{search|trending}
 */
class GiphyProvider(
    private val http: OutboundHttp,
    private val mapper: ObjectMapper,
    private val key: String,
    private val locale: String,
    private val rating: String,
) : GifProvider {
    override val name = "giphy"

    override fun search(kind: String, q: String, page: Int, perPage: Int, customer: String) =
        call("${section(kind)}/search", "q=${enc(q)}&limit=$perPage&offset=${(page - 1) * perPage}&rating=$rating&lang=$locale&random_id=$customer")

    override fun trending(kind: String, page: Int, perPage: Int, customer: String) =
        call("${section(kind)}/trending", "limit=$perPage&offset=${(page - 1) * perPage}&rating=$rating&random_id=$customer")

    private fun section(kind: String) = if (kind == "sticker") "stickers" else "gifs"

    private fun call(path: String, query: String): ProviderPage? {
        val root = getJson(http, mapper, "https://api.giphy.com/v1/$path?api_key=$key&$query") ?: return null
        val items = root.path("data").mapNotNull { item ->
            val images = item.path("images")
            val fh = images.path("fixed_height")
            val source = fh.path("webp").asText().ifEmpty { fh.path("url").asText() }.ifEmpty { return@mapNotNull null }
            val small = images.path("fixed_height_small")
            ProviderGif(
                externalId = item.path("id").asText(),
                title = item.path("title").asText().ifEmpty { null },
                sourceUrl = source,
                previewUrl = small.path("webp").asText().ifEmpty { small.path("url").asText() }.ifEmpty { null },
                width = fh.path("width").asText().toIntOrNull(),
                height = fh.path("height").asText().toIntOrNull(),
            )
        }.filter { it.externalId.isNotEmpty() }
        val p = root.path("pagination")
        val hasNext = p.path("offset").asInt(0) + p.path("count").asInt(0) < p.path("total_count").asInt(0)
        return ProviderPage(items, hasNext)
    }
}

private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8)

/** Последняя ошибка провайдера — для GET /api/gifs/status. */
object GifDiag {
    @Volatile var lastError: String? = null
    @Volatile var lastMillis: Long = 0
}

private fun getJson(http: OutboundHttp, mapper: ObjectMapper, url: String): JsonNode? {
    val masked = url.substringBefore("?").replace(Regex("/v1/[^/]+/"), "/v1/***/")
    val r = http.getString(url)
    GifDiag.lastMillis = r.millis
    if (!r.ok) {
        val why = "${r.error}${r.body?.let { " — " + it.take(200) } ?: ""}"
        GifDiag.lastError = why
        Log.warnf("gif provider %s: %s за %d мс", masked, why, r.millis)
        return null
    }
    return try {
        mapper.readTree(r.body).also { GifDiag.lastError = null }
    } catch (e: Exception) {
        GifDiag.lastError = "не JSON: ${r.body?.take(200)}"
        Log.warnf("gif provider %s: не JSON (%s): %s", masked, e.message, r.body?.take(200))
        null
    }
}

/**
 * Гифки и внешние стикеры для чата.
 *
 *  - поиск идёт к провайдеру (KLIPY или GIPHY, straycatz.gifs.provider), ответ кэшируется на час;
 *  - всё, что пришло из поиска, оседает в таблице gif (метаданные) — со временем это
 *    своя библиотека: без провайдера (или если он лёг) ищем по ней;
 *  - файл скачивается к себе (media) при первой отправке в чат и фоновой догрузкой
 *    популярного — старые сообщения не ломаются, даже если провайдер закроется (как Tenor).
 */
@ApplicationScoped
class GifService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val media: MediaService,
    private val http: OutboundHttp,
    @ConfigProperty(name = "straycatz.gifs.provider", defaultValue = "none") private val providerName: String,
    @ConfigProperty(name = "straycatz.gifs.klipy-key") private val klipyKey: Optional<String>,
    @ConfigProperty(name = "straycatz.gifs.giphy-key") private val giphyKey: Optional<String>,
    @ConfigProperty(name = "straycatz.gifs.locale", defaultValue = "ru_RU") private val locale: String,
    @ConfigProperty(name = "straycatz.gifs.content-filter", defaultValue = "medium") private val contentFilter: String,
    @ConfigProperty(name = "straycatz.gifs.max-bytes", defaultValue = "8388608") private val maxBytes: Long,
) {
    companion object {
        val KINDS = setOf("gif", "sticker")
        const val MAX_PAGE = 50
        val SEARCH_TTL: Duration = Duration.ofHours(1)
    }

    private val provider: GifProvider? by lazy {
        when (providerName.lowercase()) {
            "klipy" -> klipyKey.filter { it.isNotBlank() }.map { KlipyProvider(http, mapper, it, locale, contentFilter) }.orElse(null)
            "giphy" -> giphyKey.filter { it.isNotBlank() }.map { GiphyProvider(http, mapper, it, locale, "pg-13") }.orElse(null)
            else -> null
        }.also { if (it == null && providerName != "none") Log.warn("straycatz.gifs.provider=$providerName, но ключа нет — ищем только в своей библиотеке") }
    }

    // ================================================================ поиск

    @Transactional
    fun search(me: UUID, kindRaw: String?, q: String?, page: Int, limit: Int): GifPageOut {
        val kind = kind(kindRaw)
        val query = q?.trim()?.lowercase().orEmpty()
        val p = page.coerceAtLeast(1)
        val n = limit.coerceIn(1, MAX_PAGE)
        if (query.isEmpty()) return trending(me, kind, p, n)
        if (query.length > 100) throw ApiException.badRequest("invalid_query", "запрос длиннее 100")
        val fromProvider = provider?.let { prov -> cachedOrFetch(prov.name, kind, query, p) { prov.search(kind, query, p, n, customer(me)) } }
        if (fromProvider != null) return page(fromProvider.first, fromProvider.second, p, provider!!.name)
        // своя библиотека: то, что уже приходило из поиска и отправлялось
        val ids = ids(
            "select id from gif where kind = ?1 and lower(coalesce(title, '')) like ?2 order by uses desc, created_at desc limit ?3 offset ?4",
            kind, "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", n + 1, (p - 1) * n,
        )
        return page(ids.take(n), ids.size > n, p, "local")
    }

    @Transactional
    fun trending(me: UUID, kind: String, page: Int, limit: Int): GifPageOut {
        val fromProvider = provider?.let { prov -> cachedOrFetch(prov.name, kind, "", page) { prov.trending(kind, page, limit, customer(me)) } }
        if (fromProvider != null) return page(fromProvider.first, fromProvider.second, page, provider!!.name)
        val ids = ids("select id from gif where kind = ?1 order by uses desc, created_at desc limit ?2 offset ?3", kind, limit + 1, (page - 1) * limit)
        return page(ids.take(limit), ids.size > limit, page, "local")
    }

    /** Мои недавние. */
    @Transactional
    fun recent(me: UUID, kindRaw: String?, limit: Int): GifPageOut {
        val kind = kind(kindRaw)
        val ids = ids(
            "select r.gif_id from recent_gif r join gif g on g.id = r.gif_id where r.user_id = ?1 and g.kind = ?2 order by r.used_at desc limit ?3",
            me, kind, limit.coerceIn(1, MAX_PAGE),
        )
        return page(ids, false, 1, "recent")
    }

    // ================================================================ отправка

    /**
     * Гифка уходит в сообщение: качаем файл к себе (если ещё нет), считаем использование.
     * Если скачать не вышло — сообщение всё равно отправится, с внешней ссылкой.
     */
    @Transactional
    fun use(me: UUID, gifId: UUID): UUID {
        @Suppress("UNCHECKED_CAST")
        val row = em.createNativeQuery("select id, source_url, media_id, failed_at from gif where id = ?1")
            .setParameter(1, gifId).resultList.firstOrNull() as Array<Any?>?
            ?: throw ApiException.notFound("гифка не найдена")
        if (row[2] == null) download(me, gifId, row[1] as String)
        em.createNativeQuery("update gif set uses = uses + 1, last_used_at = now() where id = ?1").setParameter(1, gifId).executeUpdate()
        em.createNativeQuery(
            "insert into recent_gif (user_id, gif_id) values (?1, ?2) on conflict (user_id, gif_id) do update set used_at = now()",
        ).setParameter(1, me).setParameter(2, gifId).executeUpdate()
        return gifId
    }

    /** Для сообщений: гифки пачкой. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun byIds(ids: Collection<UUID>): Map<UUID, GifOut> {
        if (ids.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery(
            "select id, provider, kind, title, source_url, preview_url, width, height, media_id from gif where id in (?1)",
        ).setParameter(1, ids.distinct()).resultList as List<Array<Any?>>
        return rows.associate { r ->
            val mediaId = r[8] as UUID?
            val id = r[0] as UUID
            id to GifOut(
                id = id,
                kind = r[2] as String,
                title = (r[3] as String?)?.ifEmpty { null },
                url = mediaId?.let { media.url(it) } ?: r[4] as String,
                previewUrl = mediaId?.let { media.url(it) } ?: (r[5] as String?)?.ifEmpty { null } ?: r[4] as String,
                width = (r[6] as Number?)?.toInt()?.takeIf { it > 0 },
                height = (r[7] as Number?)?.toInt()?.takeIf { it > 0 },
                cached = mediaId != null,
                provider = r[1] as String,
            )
        }
    }

    // ================================================================ фон: постепенное кэширование

    fun hasProvider(): Boolean = provider != null

    /** Проверка провайдера прямым запросом (мимо кэша): работает ли, за сколько, что не так. */
    fun status(me: UUID): GifStatusOut {
        val prov = provider ?: return GifStatusOut(
            providerName, configured = false, ok = false, millis = 0, items = 0,
            error = if (providerName == "none") "провайдер не выбран (straycatz.gifs.provider)" else "нет ключа для $providerName",
        )
        val page = prov.trending("gif", 1, 8, customer(me))
        return GifStatusOut(prov.name, true, page != null, GifDiag.lastMillis, page?.items?.size ?: 0, if (page == null) GifDiag.lastError else null)
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun pending(n: Int): List<Triple<UUID, String, UUID>> =
        (em.createNativeQuery(
            """
            select g.id, g.source_url, (select u.id from users u where not u.is_deleted order by u.created_at limit 1)
            from gif g where g.media_id is null and (g.failed_at is null or g.failed_at < now() - interval '1 day')
            order by g.uses desc, g.created_at desc limit ?1
            """.trimIndent(),
        ).setParameter(1, n).resultList as List<Array<Any?>>)
            .filter { it[2] != null }.map { Triple(it[0] as UUID, it[1] as String, it[2] as UUID) }

    @Transactional
    fun downloadTx(owner: UUID, gifId: UUID, url: String) = download(owner, gifId, url)

    // ================================================================ внутреннее

    private fun download(owner: UUID, gifId: UUID, url: String) {
        val tmp = Files.createTempFile("gif-", ".bin")
        try {
            val res = http.getFile(url, tmp)
            val size = if (Files.exists(tmp)) Files.size(tmp) else 0L
            if (!res.ok || size == 0L || size > maxBytes) throw IllegalStateException("${res.error ?: "ok"}, $size байт")
            // сигнатуру (gif/webp/png) проверит MediaService; видео сюда не пускаем
            val m = media.upload(owner, tmp, size, onlyImages = true, maxOverride = maxBytes)
            em.createNativeQuery("update gif set media_id = ?2, failed_at = null where id = ?1")
                .setParameter(1, gifId).setParameter(2, m.id).executeUpdate()
        } catch (e: Exception) {
            Log.debugf("не скачали гифку %s: %s", gifId, e.message)
            em.createNativeQuery("update gif set failed_at = now() where id = ?1").setParameter(1, gifId).executeUpdate()
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Кэш ответа провайдера на час; заодно складываем метаданные в gif. (ids, hasNext) или null. */
    @Suppress("UNCHECKED_CAST")
    private fun cachedOrFetch(prov: String, kind: String, query: String, page: Int, fetch: () -> ProviderPage?): Pair<List<UUID>, Boolean>? {
        val cached = em.createNativeQuery(
            "select gif_ids::text, has_next, fetched_at from gif_search_cache where provider = ?1 and kind = ?2 and query = ?3 and page = ?4",
        ).setParameter(1, prov).setParameter(2, kind).setParameter(3, query).setParameter(4, page)
            .resultList.firstOrNull() as Array<Any?>?
        if (cached != null && toInstant(cached[2]).isAfter(Instant.now().minus(SEARCH_TTL))) {
            val ids = mapper.readTree(cached[0] as String).map { UUID.fromString(it.asText()) }
            return ids to (cached[1] as Boolean)
        }
        val fetched = fetch() ?: return cached?.let { c ->
            // провайдер лёг — отдаём устаревший кэш, лучше, чем ничего
            mapper.readTree(c[0] as String).map { UUID.fromString(it.asText()) } to (c[1] as Boolean)
        }
        val ids = fetched.items.map { upsert(prov, kind, it) }
        em.createNativeQuery(
            """
            insert into gif_search_cache (provider, kind, query, page, gif_ids, has_next, fetched_at)
            values (?1, ?2, ?3, ?4, cast(?5 as jsonb), ?6, now())
            on conflict (provider, kind, query, page) do update set gif_ids = excluded.gif_ids, has_next = excluded.has_next, fetched_at = now()
            """.trimIndent(),
        ).setParameter(1, prov).setParameter(2, kind).setParameter(3, query).setParameter(4, page)
            .setParameter(5, mapper.writeValueAsString(ids.map { it.toString() })).setParameter(6, fetched.hasNext)
            .executeUpdate()
        return ids to fetched.hasNext
    }

    private fun upsert(prov: String, kind: String, g: ProviderGif): UUID =
        (em.createNativeQuery(
            """
            insert into gif (id, provider, external_id, kind, title, source_url, preview_url, width, height)
            values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
            on conflict (provider, external_id, kind) do update
              set title = coalesce(excluded.title, gif.title), source_url = excluded.source_url, preview_url = excluded.preview_url
            returning id
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, UUID.randomUUID()).setParameter(2, prov).setParameter(3, g.externalId).setParameter(4, kind)
            .setParameter(5, g.title ?: "").setParameter(6, g.sourceUrl).setParameter(7, g.previewUrl ?: "")
            .setParameter(8, g.width ?: 0).setParameter(9, g.height ?: 0)
            .singleResult as UUID)

    private fun page(ids: List<UUID>, hasNext: Boolean, page: Int, source: String): GifPageOut {
        val byId = byIds(ids)
        return GifPageOut(ids.mapNotNull { byId[it] }, hasNext, page, source)
    }

    private fun kind(raw: String?): String {
        val k = (raw ?: "gif").lowercase()
        if (k !in KINDS) throw ApiException.badRequest("invalid_kind", "kind: gif или sticker")
        return k
    }

    /** Провайдерам — не наш id, а его хэш (им нужен стабильный «customer», не больше). */
    private fun customer(me: UUID): String =
        MessageDigest.getInstance("SHA-256").digest(me.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    private fun ids(sql: String, vararg params: Any): List<UUID> {
        val q = em.createNativeQuery(sql, UUID::class.java)
        params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
        return q.resultList as List<UUID>
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}

/**
 * Постепенное кэширование: раз в 10 минут докачиваем к себе самое популярное
 * из того, что попадалось в поиске, но ещё не скачано. Отдельный бин — чтобы
 * транзакции на каждую гифку шли через прокси GifService.
 */
@ApplicationScoped
class GifWarmUpJob(private val gifs: GifService, private val lease: JobLease) {
    @Scheduled(every = "10m", delayed = "2m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun warmUp() {
        if (!gifs.hasProvider()) return
        if (!lease.acquire("gif-warmup", java.time.Duration.ofMinutes(30))) return
        gifs.pending(20).forEach { (id, url, owner) ->
            try {
                gifs.downloadTx(owner, id, url)
            } catch (e: Exception) {
                Log.debugf("gif %s: %s", id, e.message)
            }
        }
    }
}
