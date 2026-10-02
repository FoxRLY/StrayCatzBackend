package org.example.rest

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.example.service.DiscoverService
import org.example.service.SearchService
import org.example.service.TagService
import java.util.UUID

/** Боковые виджеты: у каждого личный и глобальный вариант (?scope=). */
@Path("/api/widgets")
@Produces(MediaType.APPLICATION_JSON)
class WidgetsResource(
    private val currentUser: CurrentUser,
    private val discover: DiscoverService,
) {
    /** «Друзья / в сети»: ?scope=friends (по умолчанию) | global, &limit=30. */
    @GET
    @Path("/online")
    fun online(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("scope") @DefaultValue("friends") scope: String,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
    ): OnlineWidgetOut = discover.online(me(auth), scope, limit)

    /** «Что делают люди»: ?scope=mine (по умолчанию) | global, &limit=20. */
    @GET
    @Path("/activity")
    fun activity(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("scope") @DefaultValue("mine") scope: String,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): ActivityWidgetOut = discover.activity(me(auth), scope, limit)

    /** «О чём говорят»: ?scope=global (по умолчанию) | mine, &limit=8. */
    @GET
    @Path("/trending")
    fun trending(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("scope") @DefaultValue("global") scope: String,
        @QueryParam("limit") @DefaultValue("8") limit: Int,
    ): TrendingWidgetOut = discover.trending(me(auth), scope, limit)

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}

/** Теги: автодополнение и страница тега. */
@Path("/api/tags")
@Produces(MediaType.APPLICATION_JSON)
class TagResource(
    private val currentUser: CurrentUser,
    private val tags: TagService,
    private val discover: DiscoverService,
) {
    /** ?q=low — самые используемые теги с таким началом (пустой q — просто самые используемые). */
    @GET
    fun suggest(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("10") limit: Int,
    ): List<TagCountOut> {
        currentUser.require(auth)
        return tags.suggest(q, limit)
    }

    /** Всё с тегом: записи, видео, эфиры, треки, сообщества, события. Можно с # или без. */
    @GET
    @Path("/{tag}")
    fun page(@HeaderParam("Authorization") auth: String?, @PathParam("tag") tag: String): TagPageOut =
        discover.tagPage(currentUser.require(auth).userId, tag)
}

/** Глобальный поиск из шапки. */
@Path("/api/search")
@Produces(MediaType.APPLICATION_JSON)
class SearchResource(
    private val currentUser: CurrentUser,
    private val search: SearchService,
) {
    /**
     * ?q=ночь — везде по чуть-чуть (по 5 каждого вида + смешанный top на 8);
     * ?q=#lowpoly — только теги и то, что ими помечено; ?q=@ali — только люди;
     * &type=users|rooms|communities|tracks|tags|posts|videos|streams|events — один вид
     * целиком, страницами: &limit=20&offset=0 (есть ли ещё — hasMore).
     */
    @GET
    fun search(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("q") q: String?,
        @QueryParam("type") type: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): SearchOut = search.search(currentUser.require(auth).userId, q, type, limit, offset)
}

/** Бегущая строка в шапке: горячее по сети и из моего окружения ~50/50. */
@Path("/api/ticker")
@Produces(MediaType.APPLICATION_JSON)
class TickerResource(
    private val currentUser: CurrentUser,
    private val discover: DiscoverService,
) {
    @GET
    fun ticker(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("limit") @DefaultValue("16") limit: Int,
    ): TickerOut = discover.ticker(currentUser.require(auth).userId, limit)
}
