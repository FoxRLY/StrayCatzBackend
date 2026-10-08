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
import org.example.service.VideoService
import java.util.UUID

/** Вкладка «видео»: все ролики сети одним списком. {id} — id файла (media). */
@Path("/api/video")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class VideoResource(
    private val currentUser: CurrentUser,
    private val videos: VideoService,
    private val recommend: org.example.service.RecommendService,
) {
    /**
     * ?scope=feed|all|mine|friends|community|user (feed по умолчанию)
     * &slug= (для community) &userId= (для user) &q= (поиск по подписи/тексту записи)
     * &sort=new|popular &limit=24; дальше ?before=<nextBefore> или ?offset=<nextOffset>.
     * &hideSlop=true — без роликов из записей с плашкой «ИИ слоп».
     * &algo=true (только scope=feed без q/tag) — умная лента, дальше ?algo=true&cursor=<nextCursor>.
     */
    @GET
    fun list(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("scope") scope: String?,
        @QueryParam("slug") slug: String?,
        @QueryParam("userId") userId: UUID?,
        @QueryParam("q") q: String?,
        @QueryParam("sort") sort: String?,
        @QueryParam("before") before: String?,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
        @QueryParam("limit") @DefaultValue("24") limit: Int,
        @QueryParam("tag") tag: String?,
        @QueryParam("algo") @DefaultValue("false") algo: Boolean,
        @QueryParam("cursor") cursor: String?,
        @QueryParam("hideSlop") @DefaultValue("false") hideSlop: Boolean,
    ): VideoPageOut {
        val me = me(auth)
        // умная лента — только для основной вкладки (scope=feed, без поиска и тега)
        if (algo && (scope == null || scope.equals("feed", true)) && q.isNullOrBlank() && tag.isNullOrBlank()) {
            return recommend.video(me, cursor, limit, hideSlop)
        }
        return videos.list(me, scope, slug, userId, q, sort, parseInstantParam(before, "before"), offset, limit, tag, hideSlop)
    }

    /** Загрузить в «мои видео» без записи: {mediaId, title?, durationSec?, posterMediaId?} → 201. */
    @POST
    fun upload(@HeaderParam("Authorization") auth: String?, req: VideoUploadIn?): Response =
        Response.status(201).entity(videos.upload(me(auth), req ?: VideoUploadIn())).build()

    @GET
    @Path("/{id}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): VideoOut = videos.get(me(auth), id)

    /** Своё видео: {title?, durationSec?, posterMediaId?, clearPoster?}. */
    @PATCH
    @Path("/{id}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: VideoPatchIn?): VideoOut =
        videos.update(me(auth), id, req ?: VideoPatchIn())

    /** «Добавить себе». */
    @PUT
    @Path("/{id}/mine")
    fun save(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): VideoOut = videos.save(me(auth), id)

    /** Убрать из «моих видео». */
    @DELETE
    @Path("/{id}/mine")
    fun unsave(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        videos.unsave(me(auth), id)
        return Response.noContent().build()
    }

    /** Ролик начал играть → {views}. */
    @POST
    @Path("/{id}/view")
    fun view(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): VideoViewOut = videos.view(me(auth), id)

    /** Переслать: {chatId?, chatIds?, userId?, userIds?, comment?} — как /api/posts/{id}/share. */
    @POST
    @Path("/{id}/share")
    fun share(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: ShareIn?): ShareOut =
        videos.share(me(auth), id, req ?: ShareIn())

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
