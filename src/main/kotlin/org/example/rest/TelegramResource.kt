package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.TelegramMirrorService
import java.util.UUID

/** Зеркала публичных Telegram-каналов. */
@Path("/api/telegram/channels")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TelegramResource(
    private val currentUser: CurrentUser,
    private val mirrors: TelegramMirrorService,
) {
    /**
     * {link} → 201 страница сообщества-зеркала (если канал уже зеркалится — 200 и оно же).
     * Посты подтянутся фоном в течение пары минут.
     */
    @POST
    fun add(@HeaderParam("Authorization") auth: String?, req: MirrorIn?): Response {
        val page = mirrors.add(me(auth), req?.link)
        return Response.status(if (page.mirror?.posts == 0L && page.mirror?.lastSyncedAt == null) 201 else 200).entity(page).build()
    }

    /** Все зеркала, новые сверху. */
    @GET
    fun list(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): List<MirrorOut> = mirrors.list(me(auth), limit, offset)

    /** Пауза / снять с паузы (только добавивший). */
    @PUT
    @Path("/{slug}/pause")
    fun pause(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): MirrorOut = mirrors.setPaused(me(auth), slug, true)

    @DELETE
    @Path("/{slug}/pause")
    fun resume(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): MirrorOut = mirrors.setPaused(me(auth), slug, false)

    /** Подтянуть новые посты сейчас (раз в минуту). */
    @POST
    @Path("/{slug}/sync")
    fun sync(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): MirrorOut = mirrors.syncNow(me(auth), slug)

    /** Скрыть перенесённую запись (только добавивший). */
    @DELETE
    @Path("/{slug}/posts/{postId}")
    fun hide(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @PathParam("postId") postId: UUID): Response {
        mirrors.hidePost(me(auth), slug, postId)
        return Response.noContent().build()
    }

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
