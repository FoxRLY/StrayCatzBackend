package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * POST /api/market и PATCH /api/market/{id}.
 * В PATCH: поле не передано (null) — не менять; city/contacts "" — очистить;
 * price — чтобы сделать «договорной», передай priceNegotiable: true.
 */
data class MarketItemIn(
    val title: String? = null,
    val description: String? = null,
    /** 0 — «отдам даром». Для «договорной» — не передавать (или priceNegotiable). */
    val price: BigDecimal? = null,
    val priceNegotiable: Boolean? = null,
    /** RUB по умолчанию; USD, EUR, KZT, BYN, UAH, GEL, AMD. */
    val currency: String? = null,
    /** Код из GET /api/market/categories. */
    val category: String? = null,
    /** new / used / broken */
    val condition: String? = null,
    val city: String? = null,
    /** Как связаться, свободным текстом: «тг @ник, после 18:00». Кнопка «написать» в личку есть всегда. */
    val contacts: String? = null,
    /** Фото: до 10 картинок из POST /api/media (порядок = порядок показа, первая — обложка). В PATCH — заменяет все. */
    val mediaIds: List<UUID>? = null,
)

/** active / reserved / sold */
data class MarketStatusIn(val status: String? = null)

/** «Написать продавцу»: текст первого сообщения (по умолчанию — «Привет! Ещё продаётся?»). */
data class MarketContactIn(val text: String? = null)

data class MarketContactOut(val chatId: UUID, val messageId: UUID)

data class MarketCategoryOut(val code: String, val title: String, val icon: String, val count: Long)

data class MyMarketOut(
    /** Моё объявление: можно править, менять статус, поднимать. */
    val mine: Boolean,
    val favorite: Boolean,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MarketItemOut(
    val id: UUID,
    val seller: UserShortOut?,
    val title: String,
    val description: String,
    /** null — договорная, 0 — даром. */
    val price: BigDecimal?,
    val currency: String,
    /** Готовая строка: «1 500 ₽», «даром», «договорная». */
    val priceText: String,
    val category: String,
    val categoryTitle: String,
    /** new / used / broken */
    val condition: String,
    val city: String?,
    val contacts: String?,
    /** active / reserved / sold */
    val status: String,
    val photos: List<AttachmentOut>,
    /** Первое фото или null. */
    val cover: String?,
    val views: Long,
    /** Сколько добавили в избранное. */
    val favorites: Long,
    val my: MyMarketOut,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** По нему сортируется лента и листается ?before=. */
    val bumpedAt: Instant,
    /** Когда можно снова «поднять» (для своих). */
    val canBumpAt: Instant?,
)

data class MarketPageOut(
    val items: List<MarketItemOut>,
    val hasMore: Boolean,
    /** Курсор следующей страницы: ?before=<next>. */
    val next: Instant?,
)
