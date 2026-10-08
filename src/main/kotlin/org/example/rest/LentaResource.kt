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
     * &hideSlop=true — без записей с плашкой «ИИ слоп» (в обоих режимах)
     * &algo=true — умная лента (подписки ~40%, похожее ~30%, друзья ~18%, популярное ~12%);
     *   дальше — ?algo=true&cursor=<nextCursor>, before/since не используются. С scope=global игнорируется.
     */
    @GET
    fun get(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("sources") sources: String?,
        @QueryParam("before") before: String?,
        @QueryParam("since") since: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
        @QueryParam("scope") @DefaultValue("mine") scope: String,
        @QueryParam("algo") @DefaultValue("false") algo: Boolean,
        @QueryParam("cursor") cursor: String?,
        @QueryParam("hideSlop") @DefaultValue("false") hideSlop: Boolean,
    ): LentaPageOut {
        val me = currentUser.require(auth).userId
        val global = when (scope.lowercase()) {
            "mine" -> false
            "global" -> true
            else -> throw ApiException.badRequest("invalid_scope", "scope: mine или global")
        }
        // умная лента: подписки + похожее + друзья + популярное; листается ?cursor=<nextCursor>
        if (algo && !global) return lenta.smart(me, sources, cursor, limit, hideSlop)
        return lenta.lenta(
            me, sources, parseInstantParam(before, "before"), parseInstantParam(since, "since"), limit,
            global = global, hideSlop = hideSlop,
        )
    }
}
