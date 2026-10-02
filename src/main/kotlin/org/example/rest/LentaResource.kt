package org.example.rest

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.example.service.LentaService

/**
 * Общая лента (страница /lenta): всё, что происходит вокруг, по времени.
 * Не путать с /api/feed — там только записи сообществ.
 */
@Path("/api/lenta")
@Produces(MediaType.APPLICATION_JSON)
class LentaResource(
    private val currentUser: CurrentUser,
    private val lenta: LentaService,
) {
    /**
     * ?sources=communities,video,rooms,music,friends,replies (по умолчанию все)
     * &before=<nextBefore> (следующая страница) &since=<ISO> (только новее) &limit=30
     * &scope=mine (по умолчанию: моё окружение) | global (вся сеть, без личного: гостевых, ответов, новых друзей)
     */
    @GET
    fun get(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("sources") sources: String?,
        @QueryParam("before") before: String?,
        @QueryParam("since") since: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
        @QueryParam("scope") @DefaultValue("mine") scope: String,
    ): LentaPageOut = lenta.lenta(
        currentUser.require(auth).userId, sources,
        parseInstantParam(before, "before"), parseInstantParam(since, "since"), limit,
        global = when (scope.lowercase()) {
            "mine" -> false
            "global" -> true
            else -> throw ApiException.badRequest("invalid_scope", "scope: mine или global")
        },
    )
}
