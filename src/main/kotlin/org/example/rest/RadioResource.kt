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
import org.example.service.RadioService
import java.util.UUID

/** Радио сообщества. (Отдельные классы на /radio и /replays — чтобы не перехватывать остальные /api/communities/{slug}/….) */
@Path("/api/communities/{slug}/radio")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class CommunityRadioResource(
    private val currentUser: CurrentUser,
    private val radio: RadioService,
) {
    /** Станция сообщества (создаётся при первом открытии): что играет, очередь, слушатели. */
    @GET
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): RadioOut =
        radio.forCommunity(me(auth), slug)

    /** admin: {name, description, autoDj, requestsOpen} */
    @PATCH
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: RadioIn?): RadioOut =
        radio.update(me(auth), slug, req ?: RadioIn())

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}

/** Вкладка «Реплеи»: записи эфиров радио. */
@Path("/api/communities/{slug}/replays")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class CommunityReplaysResource(
    private val currentUser: CurrentUser,
    private val radio: RadioService,
) {
    /** Прошедшие эфиры, новые сверху. Дальше — ?before=<next>. */
    @GET
    fun replays(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): RadioReplayPageOut {
        me(auth)
        return radio.replays(slug, parseInstantParam(before, "before"), limit)
    }

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}

@Path("/api/radio")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class RadioResource(
    private val currentUser: CurrentUser,
    private val radio: RadioService,
) {
    @GET
    @Path("/{id}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): RadioOut = radio.get(me(auth), id)

    /** Диджей: включить эфир {title?}. */
    @POST
    @Path("/{id}/start")
    fun start(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: RadioStartIn?): RadioOut =
        radio.start(me(auth), id, req?.title)

    @POST
    @Path("/{id}/stop")
    fun stop(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): RadioOut = radio.stop(me(auth), id)

    @POST
    @Path("/{id}/skip")
    fun skip(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): RadioOut = radio.skip(me(auth), id)

    /** {trackId, next?}: диджей ставит в очередь, слушатель заказывает. */
    @POST
    @Path("/{id}/queue")
    fun enqueue(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: RadioQueueIn?): RadioOut =
        radio.enqueue(me(auth), id, req?.trackId, req?.next == true)

    @DELETE
    @Path("/{id}/queue/{item}")
    fun dequeue(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @PathParam("item") item: UUID): RadioOut =
        radio.dequeue(me(auth), id, item)

    /** Диджей: {ids: [...]} — новый порядок. */
    @PUT
    @Path("/{id}/queue/order")
    fun reorder(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: RadioOrderIn?): RadioOut =
        radio.reorder(me(auth), id, req?.ids ?: emptyList())

    /** Пинг слушателя раз в минуту, пока играет. */
    @POST
    @Path("/{id}/listen")
    fun listen(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): RadioOut = radio.listen(me(auth), id)

    /** Перестал слушать (пауза, закрыл плеер). */
    @DELETE
    @Path("/{id}/listen")
    fun unlisten(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        radio.unlisten(me(auth), id)
        return Response.noContent().build()
    }

    /** admin: сделать диджеем участника сообщества. */
    @PUT
    @Path("/{id}/djs/{userId}")
    fun addDj(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @PathParam("userId") userId: UUID): RadioOut =
        radio.setDj(me(auth), id, userId, true)

    @DELETE
    @Path("/{id}/djs/{userId}")
    fun removeDj(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @PathParam("userId") userId: UUID): RadioOut =
        radio.setDj(me(auth), id, userId, false)

    /** Реплей: эфир с треклистом. */
    @GET
    @Path("/sessions/{sid}")
    fun session(@HeaderParam("Authorization") auth: String?, @PathParam("sid") sid: UUID): RadioSessionDetailOut =
        radio.session(me(auth), sid)

    /** admin: {title} */
    @PATCH
    @Path("/sessions/{sid}")
    fun renameSession(@HeaderParam("Authorization") auth: String?, @PathParam("sid") sid: UUID, req: RadioSessionPatchIn?): RadioSessionOut =
        radio.renameSession(me(auth), sid, req?.title)

    /** admin */
    @DELETE
    @Path("/sessions/{sid}")
    fun deleteSession(@HeaderParam("Authorization") auth: String?, @PathParam("sid") sid: UUID): Response {
        radio.deleteSession(me(auth), sid)
        return Response.noContent().build()
    }

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
