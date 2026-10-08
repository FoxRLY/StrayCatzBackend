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
import org.example.service.DecreeService
import org.example.service.ModerationService
import java.util.UUID

/** Кнопка «Пожаловаться» и «мои жалобы» — для всех. */
@Path("/api/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ReportResource(
    private val currentUser: CurrentUser,
    private val moderation: ModerationService,
) {
    /** {targetType, targetId, reason, comment?} → {reported, alreadyReported}. */
    @POST
    fun report(@HeaderParam("Authorization") auth: String?, req: ReportIn?): ReportOut {
        val r = req ?: ReportIn()
        return moderation.report(currentUser.require(auth), r.targetType, r.targetId, r.reason, r.comment)
    }

    /** Причины для выпадающего списка. */
    @GET
    @Path("/reasons")
    fun reasons(): List<ReportReasonOut> = moderation.reasons()

    /** Мои жалобы и чем кончились. */
    @GET
    @Path("/mine")
    fun mine(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
    ): MyReportsPageOut = moderation.myReports(currentUser.require(auth).userId, parseInstantParam(before, "before"), limit)

    /** Моё положение: ограничение, предупреждения, что у меня удалили. */
    @GET
    @Path("/standing")
    fun standing(@HeaderParam("Authorization") auth: String?): MyStandingOut =
        moderation.myStanding(currentUser.require(auth).userId)
}

/** Панель модератора. Всё — только с realm-ролью moderator (или dictator). */
@Path("/api/mod")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ModerationResource(
    private val currentUser: CurrentUser,
    private val moderation: ModerationService,
) {
    /** Кто я: показывать ли ссылку на панель. Отвечает всем (без 403). */
    @GET
    @Path("/me")
    fun me(@HeaderParam("Authorization") auth: String?): ModMeOut {
        val t = currentUser.require(auth)
        return ModMeOut(t.isModerator, t.isDictator, t.staffRole)
    }

    @GET
    @Path("/stats")
    fun stats(@HeaderParam("Authorization") auth: String?): ModStatsOut = moderation.stats(mod(auth))

    /**
     * Очередь. ?status=open (по умолчанию: open + in_progress) | collecting | in_progress | resolved | dismissed | closed | all
     * &type=post|… &assigned=me|none &sort=oldest|reports|newest &communityId= &ownerId= &offset= &limit=
     */
    @GET
    @Path("/tickets")
    fun tickets(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("status") status: String?,
        @QueryParam("type") type: String?,
        @QueryParam("assigned") assigned: String?,
        @QueryParam("sort") sort: String?,
        @QueryParam("communityId") communityId: UUID?,
        @QueryParam("ownerId") ownerId: UUID?,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): ModTicketPageOut = moderation.tickets(mod(auth), status, type, assigned, sort, communityId, ownerId, offset, limit)

    @GET
    @Path("/tickets/{id}")
    fun ticket(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): ModTicketDetailOut =
        moderation.ticket(mod(auth), id)

    /** Взять в работу. {force: true} — забрать у другого. */
    @POST
    @Path("/tickets/{id}/take")
    fun take(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: ModTakeIn?): ModTicketDetailOut =
        moderation.take(mod(auth), id, req?.force == true)

    /** Вернуть в очередь. */
    @POST
    @Path("/tickets/{id}/release")
    fun release(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): ModTicketDetailOut =
        moderation.release(mod(auth), id)

    /** Решение: {verdict: violation|no_violation, actions: [...], note?}. */
    @POST
    @Path("/tickets/{id}/resolve")
    fun resolve(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: ModResolveIn?): ModTicketDetailOut {
        val r = req ?: ModResolveIn()
        return moderation.resolve(mod(auth), id, r.verdict, r.actions, r.note)
    }

    @POST
    @Path("/tickets/{id}/reopen")
    fun reopen(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): ModTicketDetailOut =
        moderation.reopen(mod(auth), id)

    /** Мера без тикета (или с ticketId): {action, targetType, targetId, hours?, reason?, ticketId?}. */
    @POST
    @Path("/actions")
    fun act(@HeaderParam("Authorization") auth: String?, req: ModActionIn?): ModActionOut =
        moderation.act(mod(auth), req ?: ModActionIn())

    /** Журнал: ?moderatorId &userId &communityId &targetType &targetId &action &before &limit. */
    @GET
    @Path("/log")
    fun log(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("moderatorId") moderatorId: UUID?,
        @QueryParam("userId") userId: UUID?,
        @QueryParam("communityId") communityId: UUID?,
        @QueryParam("targetType") targetType: String?,
        @QueryParam("targetId") targetId: UUID?,
        @QueryParam("action") action: String?,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): ModActionPageOut {
        mod(auth)
        return moderation.log(moderatorId, userId, communityId, targetType, targetId, action, parseInstantParam(before, "before"), limit)
    }

    /** Карточка человека: {key} — id или username. */
    @GET
    @Path("/users/{key}")
    fun user(@HeaderParam("Authorization") auth: String?, @PathParam("key") key: String): ModUserOut {
        mod(auth)
        return moderation.userCard(key)
    }

    /** Карточка сообщества (в т.ч. заблокированного). */
    @GET
    @Path("/communities/{slug}")
    fun community(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): ModCommunityOut {
        mod(auth)
        return moderation.communityCard(slug)
    }

    private fun mod(auth: String?) = currentUser.requireModerator(auth)
}

/** Верховный диктатор: указы и модераторы. Только с realm-ролью dictator. */
@Path("/api/dictator")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class DictatorResource(
    private val currentUser: CurrentUser,
    private val decrees: DecreeService,
) {
    /** Выдать указ (заменяет прежний): {text, emoji?, color?}. */
    @PUT
    @Path("/users/{userId}/decree")
    fun issue(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID, req: DecreeIn?): DecreeOut =
        decrees.issue(dictator(auth), userId, req ?: DecreeIn())

    /** Отозвать указ. */
    @DELETE
    @Path("/users/{userId}/decree")
    fun revoke(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID): Response {
        decrees.revoke(dictator(auth), userId)
        return Response.noContent().build()
    }

    /** Действующие указы или ?userId= — вся история человека. */
    @GET
    @Path("/decrees")
    fun list(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("userId") userId: UUID?,
        @QueryParam("limit") @DefaultValue("200") limit: Int,
    ): List<DecreeAdminOut> {
        dictator(auth)
        return decrees.list(userId, limit)
    }

    /** Модераторы и диктатор. */
    @GET
    @Path("/staff")
    fun staff(@HeaderParam("Authorization") auth: String?): List<StaffOut> {
        dictator(auth)
        return decrees.staff()
    }

    /** Назначить модератором — действует со следующего запроса. */
    @PUT
    @Path("/moderators/{userId}")
    fun grant(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID): Response {
        decrees.setModerator(dictator(auth), userId, true)
        return Response.noContent().build()
    }

    /** Снять с модераторов. */
    @DELETE
    @Path("/moderators/{userId}")
    fun revokeModerator(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID): Response {
        decrees.setModerator(dictator(auth), userId, false)
        return Response.noContent().build()
    }

    /** Назначить ещё одного диктатора: {confirmUsername: "<его username>"}. */
    @PUT
    @Path("/dictators/{userId}")
    fun grantDictator(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID, req: DictatorGrantIn?): Response {
        decrees.setDictator(dictator(auth), userId, true, req?.confirmUsername)
        return Response.noContent().build()
    }

    /** Снять диктатора, которого назначил сам. */
    @DELETE
    @Path("/dictators/{userId}")
    fun revokeDictator(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID): Response {
        decrees.setDictator(dictator(auth), userId, false, null)
        return Response.noContent().build()
    }

    private fun dictator(auth: String?) = currentUser.requireDictator(auth)
}
