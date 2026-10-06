package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.MarketService
import java.math.BigDecimal
import java.util.UUID

/** Барахолка. Фото — сначала POST /api/media, потом mediaIds. */
@Path("/api/market")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MarketResource(
    private val currentUser: CurrentUser,
    private val market: MarketService,
) {
    /**
     * Лента и поиск, свежие и «поднятые» сверху. Дальше — ?before=<next>.
     * status: active (по умолчанию: активные + забронированные) / sold / all;
     * seller: username или me.
     */
    @GET
    fun search(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("q") q: String?,
        @QueryParam("category") category: String?,
        @QueryParam("city") city: String?,
        @QueryParam("minPrice") minPrice: BigDecimal?,
        @QueryParam("maxPrice") maxPrice: BigDecimal?,
        @QueryParam("free") free: Boolean?,
        @QueryParam("condition") condition: String?,
        @QueryParam("seller") seller: String?,
        @QueryParam("status") status: String?,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
    ): MarketPageOut = market.search(
        me(auth), q, category, city, minPrice, maxPrice, free, condition, seller, status,
        parseInstantParam(before, "before"), limit,
    )

    /** Категории с числом активных объявлений. */
    @GET
    @Path("/categories")
    fun categories(@HeaderParam("Authorization") auth: String?): List<MarketCategoryOut> {
        me(auth)
        return market.categories()
    }

    /** Моё избранное. */
    @GET
    @Path("/favorites")
    fun favorites(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
    ): MarketPageOut = market.favorites(me(auth), parseInstantParam(before, "before"), limit)

    @GET
    @Path("/{id}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): MarketItemOut = market.get(me(auth), id)

    /** → 201 */
    @POST
    fun create(@HeaderParam("Authorization") auth: String?, req: MarketItemIn?): Response =
        Response.status(201).entity(market.create(me(auth), req ?: MarketItemIn())).build()

    @PATCH
    @Path("/{id}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: MarketItemIn?): MarketItemOut =
        market.update(me(auth), id, req ?: MarketItemIn())

    /** {status: active | reserved | sold} */
    @PUT
    @Path("/{id}/status")
    fun status(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: MarketStatusIn?): MarketItemOut =
        market.setStatus(me(auth), id, req?.status)

    /** Поднять наверх (раз в сутки). */
    @POST
    @Path("/{id}/bump")
    fun bump(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): MarketItemOut = market.bump(me(auth), id)

    @DELETE
    @Path("/{id}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        market.delete(me(auth), id)
        return Response.noContent().build()
    }

    @PUT
    @Path("/{id}/favorite")
    fun favorite(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): MarketItemOut =
        market.favorite(me(auth), id, true)

    @DELETE
    @Path("/{id}/favorite")
    fun unfavorite(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): MarketItemOut =
        market.favorite(me(auth), id, false)

    /** «Написать продавцу» → {chatId, messageId}: открыть личку. */
    @POST
    @Path("/{id}/contact")
    fun contact(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: MarketContactIn?): MarketContactOut =
        market.contact(me(auth), id, req?.text)

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
