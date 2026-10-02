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
import org.example.service.GifService
import org.example.service.MediaService
import org.example.service.StickerImportService
import org.example.service.StickerService
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.util.UUID

/** Пикер гифок и внешних стикеров. Отправка — message.send {gifId}. */
@Path("/api/gifs")
@Produces(MediaType.APPLICATION_JSON)
class GifResource(
    private val currentUser: CurrentUser,
    private val gifs: GifService,
) {
    /** ?q=котик&kind=gif|sticker&page=1&limit=24 (пустой q — то же, что trending). */
    @GET
    @Path("/search")
    fun search(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("q") q: String?,
        @QueryParam("kind") kind: String?,
        @QueryParam("page") @DefaultValue("1") page: Int,
        @QueryParam("limit") @DefaultValue("24") limit: Int,
    ): GifPageOut = gifs.search(currentUser.require(auth).userId, kind, q, page, limit)

    @GET
    @Path("/trending")
    fun trending(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("kind") kind: String?,
        @QueryParam("page") @DefaultValue("1") page: Int,
        @QueryParam("limit") @DefaultValue("24") limit: Int,
    ): GifPageOut = gifs.search(currentUser.require(auth).userId, kind, null, page, limit)

    /** Диагностика: настроен ли провайдер, отвечает ли, сколько ждали и что за ошибка. */
    @GET
    @Path("/status")
    fun status(@HeaderParam("Authorization") auth: String?): GifStatusOut = gifs.status(currentUser.require(auth).userId)

    /** Мои недавние (?kind=gif|sticker). */
    @GET
    @Path("/recent")
    fun recent(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("kind") kind: String?,
        @QueryParam("limit") @DefaultValue("24") limit: Int,
    ): GifPageOut = gifs.recent(currentUser.require(auth).userId, kind, limit)
}

/** Наборы стикеров как в телеге. Отправка — message.send {stickerId}. */
@Path("/api/stickers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StickerResource(
    private val currentUser: CurrentUser,
    private val stickers: StickerService,
    private val media: MediaService,
    private val importer: StickerImportService,
) {
    /** Мои наборы со стикерами — для панели. */
    @GET
    @Path("/mine")
    fun mine(@HeaderParam("Authorization") auth: String?): List<StickerPackOut> = stickers.mine(me(auth))

    @GET
    @Path("/recent")
    fun recent(@HeaderParam("Authorization") auth: String?, @QueryParam("limit") @DefaultValue("30") limit: Int): List<StickerOut> =
        stickers.recent(me(auth), limit)

    /** Каталог публичных наборов: ?q=&limit=20&offset=0. */
    @GET
    @Path("/packs")
    fun catalog(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): List<StickerPackOut> = stickers.catalog(me(auth), q, limit, offset)

    @GET
    @Path("/packs/{id}")
    fun pack(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): StickerPackOut = stickers.get(me(auth), id)

    /** {title, isPublic?} → 201 (набор сразу добавлен себе). */
    @POST
    @Path("/packs")
    fun create(@HeaderParam("Authorization") auth: String?, req: StickerPackIn?): Response =
        Response.status(201).entity(stickers.create(me(auth), req ?: StickerPackIn())).build()

    @PATCH
    @Path("/packs/{id}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: StickerPackIn?): StickerPackOut =
        stickers.update(me(auth), id, req ?: StickerPackIn())

    @DELETE
    @Path("/packs/{id}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        stickers.delete(me(auth), id)
        return Response.noContent().build()
    }

    /** Стикер файлом: multipart, поля file (png/webp/gif до 1 МБ) и emoji. */
    @POST
    @Path("/packs/{id}/stickers")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadSticker(
        @HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID,
        @RestForm("file") file: FileUpload?, @RestForm("emoji") emoji: String?,
    ): StickerPackOut {
        val me = me(auth)
        val f = file ?: throw ApiException.badRequest("no_file", "нужно поле file (multipart/form-data)")
        val img = media.upload(me, f.uploadedFile(), f.size(), onlyImages = true, maxOverride = StickerService.MAX_BYTES)
        return stickers.addSticker(me, id, img.id, emoji)
    }

    /** Стикер из уже загруженной картинки: {mediaId, emoji?}. */
    @POST
    @Path("/packs/{id}/stickers")
    @Consumes(MediaType.APPLICATION_JSON)
    fun addSticker(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: StickerIn?): StickerPackOut =
        stickers.addSticker(me(auth), id, req?.mediaId ?: throw ApiException.badRequest("invalid_media", "нужен mediaId"), req.emoji)

    /** {stickerIds: [...все по порядку]} */
    @PUT
    @Path("/packs/{id}/order")
    fun reorder(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: StickerOrderIn?): StickerPackOut =
        stickers.reorder(me(auth), id, req?.stickerIds ?: emptyList())

    @DELETE
    @Path("/{stickerId}")
    fun deleteSticker(@HeaderParam("Authorization") auth: String?, @PathParam("stickerId") stickerId: UUID): StickerPackOut =
        stickers.deleteSticker(me(auth), stickerId)

    /**
     * Импорт набора из Telegram: {link: "https://t.me/addstickers/ИМЯ"} → набор (сразу добавлен себе).
     * Стикеры докачиваются фоном — прогресс в pack.importing {status, done, total}; перечитывай GET /packs/{id}.
     */
    @POST
    @Path("/import/telegram")
    fun importTelegram(@HeaderParam("Authorization") auth: String?, req: StickerImportIn?): StickerPackOut =
        importer.importFromTelegram(me(auth), req?.link)

    /** Добавить набор себе / убрать. */
    @PUT
    @Path("/packs/{id}/install")
    fun install(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): StickerPackOut = stickers.install(me(auth), id)

    @DELETE
    @Path("/packs/{id}/install")
    fun uninstall(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        stickers.uninstall(me(auth), id)
        return Response.noContent().build()
    }

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
