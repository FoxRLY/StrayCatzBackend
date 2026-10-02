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
import org.example.service.MediaService
import org.example.service.MediaStorage
import org.example.service.StreamService
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.net.URI
import java.util.UUID

/** Стримы: OBS → RTMP → MediaMTX → зрители (HLS/WebRTC), чат — на наших сокетах. */
@Path("/api/streams")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StreamResource(
    private val currentUser: CurrentUser,
    private val streams: StreamService,
    private val media: MediaService,
    private val storage: MediaStorage,
) {
    /**
     * ?status=live|ended|all (live по умолчанию) &following=true (друзья и мои сообщества)
     * &communitySlug= / &userId= (эфиры одного канала) &limit=20; дальше ?before=<startedAt|endedAt|createdAt последнего>.
     */
    @GET
    fun list(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("status") status: String?,
        @QueryParam("communitySlug") communitySlug: String?,
        @QueryParam("userId") userId: UUID?,
        @QueryParam("following") @DefaultValue("false") following: Boolean,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("tag") tag: String?,
    ): StreamPageOut = streams.list(me(auth), status, communitySlug, userId, following, parseInstantParam(before, "before"), limit, tag)

    /** Подготовить эфир: {title, description?, communitySlug?} → 201 (или обновит уже подготовленный). */
    @POST
    fun create(@HeaderParam("Authorization") auth: String?, req: StreamIn?): Response =
        Response.status(201).entity(streams.create(me(auth), req ?: StreamIn())).build()

    /** Настройки OBS своего канала (?communitySlug= — канала сообщества, нужен admin). Ключ здесь не показывается. */
    @GET
    @Path("/ingest")
    fun ingest(@HeaderParam("Authorization") auth: String?, @QueryParam("communitySlug") slug: String?): IngestOut =
        streams.ingest(me(auth), slug)

    /** Выпустить новый ключ для OBS — он вернётся в streamKey один раз. */
    @POST
    @Path("/ingest/key")
    fun rotateKey(@HeaderParam("Authorization") auth: String?, @QueryParam("communitySlug") slug: String?): IngestOut =
        streams.rotateKey(me(auth), slug)

    @GET
    @Path("/{id}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): StreamOut = streams.get(me(auth), id)

    /** {title?, description?} */
    @PATCH
    @Path("/{id}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: StreamPatchIn?): StreamOut =
        streams.update(me(auth), id, req ?: StreamPatchIn())

    /** Обложка эфира файлом: multipart, поле file (картинка до 10 МБ). JSON-вариант — PATCH {posterMediaId}. */
    @PUT
    @Path("/{id}/poster")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadPoster(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @RestForm("file") file: FileUpload?): StreamOut {
        val me = me(auth)
        val f = file ?: throw ApiException.badRequest("no_file", "нужно поле file (multipart/form-data)")
        val img = media.upload(me, f.uploadedFile(), f.size(), onlyImages = true)
        return streams.update(me, id, StreamPatchIn(posterMediaId = img.id))
    }

    @DELETE
    @Path("/{id}/poster")
    fun deletePoster(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): StreamOut =
        streams.update(me(auth), id, StreamPatchIn(clearPoster = true))

    /**
     * Живой кадр эфира (без авторизации — для <img src>). Обновляется раз в ~20 с;
     * адрес с ?t= приходит в StreamOut.thumbnailUrl. 404 — кадра ещё нет.
     */
    @GET
    @Path("/{id}/thumbnail")
    @Produces("image/jpeg")
    fun thumbnail(@PathParam("id") id: UUID): Response {
        val key = streams.thumbKey(id) ?: throw ApiException.notFound("кадра пока нет")
        storage.directUrl(key)?.let {
            return Response.temporaryRedirect(URI.create(it)).header("Cache-Control", "public, max-age=15").build()
        }
        val stream = storage.open(key) ?: throw ApiException.notFound("кадра пока нет")
        return Response.ok(stream, "image/jpeg").header("Cache-Control", "public, max-age=15").build()
    }

    /** Завершить эфир с сайта (отключает OBS). */
    @POST
    @Path("/{id}/end")
    fun end(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): StreamOut = streams.end(me(auth), id)

    /** Пинг зрителя раз в 30 с, пока открыт плеер → {viewers, status}. */
    @POST
    @Path("/{id}/watch")
    fun watch(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): StreamViewersOut = streams.watch(me(auth), id)

    /** Зайти в чат под видео (после этого — обычный сокет с chatId эфира). */
    @POST
    @Path("/{id}/chat")
    fun joinChat(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): StreamOut = streams.joinChat(me(auth), id)

    @DELETE
    @Path("/{id}/chat")
    fun leaveChat(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        streams.leaveChat(me(auth), id)
        return Response.noContent().build()
    }

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}

/**
 * Хук внешней авторизации MediaMTX (authMethod: http). Не для фронта.
 * 200 — пустить, 401 — нет. secret в query — общий секрет из mediamtx.yml
 * и straycatz.streams.hook-secret.
 */
@Path("/api/hooks/mediamtx")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StreamHookResource(private val streams: StreamService) {
    @POST
    @Path("/auth")
    fun auth(@QueryParam("secret") secret: String?, body: Map<String, Any?>?): Response =
        if (streams.authorize(secret, body ?: emptyMap())) Response.ok().build() else Response.status(401).build()
}
