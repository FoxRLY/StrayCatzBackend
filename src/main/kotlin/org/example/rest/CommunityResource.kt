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
import org.example.service.CommunityService
import org.example.service.DiscussionService
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.service.EventService
import org.example.service.MediaService
import org.example.service.PostService
import org.example.service.WikiService
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.util.UUID

@Path("/api/communities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class CommunityResource(
    private val currentUser: CurrentUser,
    private val communities: CommunityService,
    private val posts: PostService,
    private val discussions: DiscussionService,
    private val events: EventService,
    private val wiki: WikiService,
    private val media: MediaService,
    @ConfigProperty(name = "straycatz.media.max-avatar-bytes", defaultValue = "5242880")
    private val maxAvatarBytes: Long,
) {
    // ================================================================ каталог и страница

    /** ?q=ночь&filter=all|joined|following&limit=20&offset=0 — самые людные сверху. */
    @GET
    fun list(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("q") q: String?,
        @QueryParam("filter") filter: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): CommunityListOut = communities.list(me(auth), q, filter, limit, offset)

    /** {slug, name, about?, hue?, color?, avatar?, sections?, rules?} -> 201, ты owner. */
    @POST
    fun create(@HeaderParam("Authorization") auth: String?, req: CommunityCreateIn?): Response =
        Response.status(201).entity(communities.create(me(auth), req ?: CommunityCreateIn())).build()

    @GET
    @Path("/{slug}")
    fun page(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): CommunityPageOut =
        communities.page(slug, me(auth))

    /** admin: {name?, about?, hue?, color?, avatar?, rules?} */
    @PATCH
    @Path("/{slug}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: CommunityPatchIn?): CommunityPageOut =
        communities.update(me(auth), slug, req ?: CommunityPatchIn())

    // ================================================================ аватар и шапка (admin)

    /** Аватар файлом: multipart, поле file (png/jpeg/gif/webp, до 5 МБ) → страница сообщества. */
    @PUT
    @Path("/{slug}/avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadAvatar(
        @HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @RestForm("file") file: FileUpload?,
    ): CommunityPageOut {
        val me = me(auth)
        val f = file ?: throw ApiException.badRequest("no_file", "нужно поле file (multipart/form-data)")
        val img = media.upload(me, f.uploadedFile(), f.size(), onlyImages = true, maxOverride = maxAvatarBytes)
        return communities.setAvatar(me, slug, img.id)
    }

    /** Аватар из уже загруженной картинки: {mediaId}. */
    @PUT
    @Path("/{slug}/avatar")
    @Consumes(MediaType.APPLICATION_JSON)
    fun setAvatar(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: CommunityAvatarIn?): CommunityPageOut =
        communities.setAvatar(me(auth), slug, req?.mediaId ?: throw ApiException.badRequest("invalid_media", "нужен mediaId"))

    @DELETE
    @Path("/{slug}/avatar")
    fun deleteAvatar(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): CommunityPageOut =
        communities.clearAvatar(me(auth), slug)

    /** Шапка файлом: multipart, поле file (картинка до 10 МБ), необязательное поле focus (0..1). */
    @PUT
    @Path("/{slug}/banner")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadBanner(
        @HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String,
        @RestForm("file") file: FileUpload?, @RestForm("focus") focus: String?,
    ): CommunityPageOut {
        val me = me(auth)
        val f = file ?: throw ApiException.badRequest("no_file", "нужно поле file (multipart/form-data)")
        val fy = focus?.takeIf { it.isNotBlank() }?.let {
            it.toDoubleOrNull() ?: throw ApiException.badRequest("invalid_focus", "focus: число от 0 до 1")
        }
        val img = media.upload(me, f.uploadedFile(), f.size(), onlyImages = true)
        return communities.setBanner(me, slug, img.id, fy)
    }

    /** Шапка из уже загруженной картинки и/или сдвиг кадра: {mediaId?, focus?}. */
    @PUT
    @Path("/{slug}/banner")
    @Consumes(MediaType.APPLICATION_JSON)
    fun setBanner(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: CommunityBannerIn?): CommunityPageOut =
        communities.setBanner(me(auth), slug, req?.mediaId, req?.focus)

    @DELETE
    @Path("/{slug}/banner")
    fun deleteBanner(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): CommunityPageOut =
        communities.clearBanner(me(auth), slug)

    @DELETE
    @Path("/{slug}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): Response {
        communities.delete(me(auth), slug)
        return Response.noContent().build()
    }

    /** admin: {dialect?, lexicon?: {ключ: слово}} — язык сообщества. */
    @PUT
    @Path("/{slug}/lexicon")
    fun lexicon(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: LexiconIn?): CommunityPageOut =
        communities.setLexicon(me(auth), slug, req ?: LexiconIn())

    /** admin: {sections: ["posts","discussions",...]} (или подписи "Записи", ...) — разделы и порядок. */
    @PUT
    @Path("/{slug}/sections")
    fun sections(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: SectionsIn?): CommunityPageOut =
        communities.setSections(me(auth), slug, req ?: SectionsIn())

    /** Журнал модерации — TBD, пока пустой. */
    @GET
    @Path("/{slug}/modlog")
    fun modlog(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): List<Any> {
        me(auth); communities.bySlug(slug)
        return emptyList()
    }

    // ================================================================ участие

    @POST
    @Path("/{slug}/join")
    fun join(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): CommunityPageOut =
        communities.join(me(auth), slug)

    /** {reason?} */
    @POST
    @Path("/{slug}/leave")
    fun leave(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: LeaveIn?): Response {
        communities.leave(me(auth), slug, req?.reason)
        return Response.noContent().build()
    }

    /** «Читать без вступления». */
    @POST
    @Path("/{slug}/follow")
    fun follow(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): CommunityPageOut =
        communities.follow(me(auth), slug)

    @DELETE
    @Path("/{slug}/follow")
    fun unfollow(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): Response {
        communities.unfollow(me(auth), slug)
        return Response.noContent().build()
    }

    // ================================================================ участники и модераторы

    /** Участники с онлайн-статусом, по репутации. */
    @GET
    @Path("/{slug}/members")
    fun members(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @QueryParam("limit") @DefaultValue("60") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): CommunityMembersPageOut {
        me(auth)
        return communities.members(slug, limit, offset)
    }

    /** «Кто следит»: owner + admin'ы. */
    @GET
    @Path("/{slug}/admins")
    fun admins(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): List<ModOut> {
        me(auth)
        return communities.admins(slug)
    }

    @DELETE
    @Path("/{slug}/members/{userId}")
    fun kick(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @PathParam("userId") userId: UUID): Response {
        communities.kick(me(auth), slug, userId)
        return Response.noContent().build()
    }

    /** owner: {role: "admin" | "member"} */
    @PUT
    @Path("/{slug}/members/{userId}/role")
    fun setRole(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @PathParam("userId") userId: UUID,
        req: RoleIn?,
    ): ModOut = communities.setRole(me(auth), slug, userId, req?.role)

    // ================================================================ записи

    /** ?kind= (пусто — всё кроме гайдов; guide; media; text/image/video/track), ?before=<createdAt>. */
    @GET
    @Path("/{slug}/posts")
    fun posts(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @QueryParam("kind") kind: String?,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("tag") tag: String?,
    ): PostPageOut = posts.ofCommunity(slug, me(auth), kind, parseInstantParam(before, "before"), limit, tag)

    /** {title?, body, kind?, meta?, mediaId?} — только участники. Гайд = kind "guide". */
    @POST
    @Path("/{slug}/posts")
    fun createPost(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: PostIn?): Response =
        Response.status(201).entity(posts.create(me(auth), slug, req ?: PostIn())).build()

    @GET
    @Path("/{slug}/guides")
    fun guides(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): PostPageOut = posts.ofCommunity(slug, me(auth), "guide", parseInstantParam(before, "before"), limit)

    /** «Медиа» пока собирается из записей-картинок и видео. */
    @GET
    @Path("/{slug}/media")
    fun media(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("24") limit: Int,
    ): PostPageOut = posts.ofCommunity(slug, me(auth), "media", parseInstantParam(before, "before"), limit)

    // ================================================================ обсуждения

    @GET
    @Path("/{slug}/discussions")
    fun discussions(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): List<DiscussionOut> =
        discussions.list(slug, me(auth))

    /** {title} — завести обсуждение (участники). */
    @POST
    @Path("/{slug}/discussions")
    fun createDiscussion(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: DiscussionIn?): Response =
        Response.status(201).entity(discussions.create(me(auth), slug, req?.title)).build()

    /** Зайти в обсуждение (стать участником чата). */
    @POST
    @Path("/{slug}/discussions/{chatId}/enter")
    fun enter(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @PathParam("chatId") chatId: UUID): DiscussionOut =
        discussions.enter(me(auth), slug, chatId)

    @POST
    @Path("/{slug}/discussions/{chatId}/leave")
    fun leaveDiscussion(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @PathParam("chatId") chatId: UUID): Response {
        discussions.leave(me(auth), slug, chatId)
        return Response.noContent().build()
    }

    /** admin: {pinned: true|false} */
    @PUT
    @Path("/{slug}/discussions/{chatId}/pin")
    fun pinDiscussion(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @PathParam("chatId") chatId: UUID,
        req: PinIn?,
    ): DiscussionOut = discussions.pin(me(auth), slug, chatId, req?.pinned ?: true)

    @DELETE
    @Path("/{slug}/discussions/{chatId}")
    fun deleteDiscussion(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @PathParam("chatId") chatId: UUID): Response {
        discussions.delete(me(auth), slug, chatId)
        return Response.noContent().build()
    }

    // ================================================================ события

    /** ?past=true — все, включая прошедшие. */
    @GET
    @Path("/{slug}/events")
    fun events(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @QueryParam("past") @DefaultValue("false") past: Boolean,
    ): List<EventOut> = events.list(slug, me(auth), past)

    /** admin: {title, startsAt, endsAt?, description?, location?} — участникам придёт уведомление. */
    @POST
    @Path("/{slug}/events")
    fun createEvent(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: EventIn?): Response =
        Response.status(201).entity(events.create(me(auth), slug, req ?: EventIn())).build()

    // ================================================================ вики

    @GET
    @Path("/{slug}/wiki")
    fun wikiList(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): List<WikiListItemOut> {
        me(auth)
        return wiki.list(slug)
    }

    @GET
    @Path("/{slug}/wiki/{page}")
    fun wikiPage(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @PathParam("page") page: String): WikiPageOut {
        me(auth)
        return wiki.get(slug, page)
    }

    /** {title?, body} — создать или обновить (участники), правка уходит в историю. */
    @PUT
    @Path("/{slug}/wiki/{page}")
    fun wikiPut(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @PathParam("page") page: String,
        req: WikiIn?,
    ): WikiPageOut = wiki.put(me(auth), slug, page, req ?: WikiIn())

    @GET
    @Path("/{slug}/wiki/{page}/history")
    fun wikiHistory(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @PathParam("page") page: String): List<WikiRevisionOut> {
        me(auth)
        return wiki.history(slug, page)
    }

    @DELETE
    @Path("/{slug}/wiki/{page}")
    fun wikiDelete(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, @PathParam("page") page: String): Response {
        wiki.delete(me(auth), slug, page)
        return Response.noContent().build()
    }

    // ================================================================ проекты и таблица

    @GET
    @Path("/{slug}/projects")
    fun projects(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): List<CommunityCardOut> =
        communities.projects(slug, me(auth))

    /** Проект: {slug, name, about?, hue?} — создать может участник; разделы фиксированы. */
    @POST
    @Path("/{slug}/projects")
    fun createProject(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: CommunityCreateIn?): Response =
        Response.status(201).entity(communities.createProject(me(auth), slug, req ?: CommunityCreateIn())).build()

    /** Таблица активности за ?days=7. */
    @GET
    @Path("/{slug}/leaderboard")
    fun leaderboard(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @QueryParam("days") @DefaultValue("7") days: Int,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): List<LeaderboardRowOut> {
        me(auth)
        return communities.leaderboard(slug, days, limit)
    }

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
