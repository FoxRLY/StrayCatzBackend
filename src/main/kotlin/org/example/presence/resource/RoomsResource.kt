package org.example.resource

import com.example.presence.resource.CurrentUser
import org.example.presence.model.AppUser
import org.example.model.Room as RoomEntity
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.example.model.GuestbookEntry
import org.example.dto.GuestbookPostRequest
import org.example.dto.Page
import org.example.dto.Room
import org.example.dto.RoomCard
import org.example.mapper.RoomMapper
import org.example.ratelimit.GuestbookRateLimiter
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Path("/rooms")
@Tag(name = "Комнаты")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class RoomsResource {

    @Inject
    lateinit var currentUser: CurrentUser

    @Inject
    lateinit var rateLimiter: GuestbookRateLimiter

    // ---------- GET /rooms ----------

    @GET
    @Operation(summary = "Комнаты, куда можно зайти")
    fun listRooms(@QueryParam("open") @DefaultValue("true") open: Boolean): List<RoomCard> {
        val rooms = if (open) RoomEntity.listOpen() else RoomEntity.listAll()
        return rooms.map { RoomMapper.toCard(it) }
    }

    // ---------- GET /rooms/{handle} ----------

    @GET
    @Path("/{handle}")
    @Operation(summary = "Комната целиком — раскладка, обои, словарь")
    fun getRoom(@PathParam("handle") handle: String): Room {
        val room = RoomEntity.findByHandle(handle) ?: throw NotFoundException("Room '$handle' not found")

        if (!room.open) {
            val viewer = currentUser.resolveOptional()
            val isOwner = viewer != null && viewer.id == room.ownerId
            if (!isOwner) {
                throw ForbiddenException("Room is locked by its owner")
            }
        }

        return RoomMapper.toRoom(room, ownerHandle = handle)
    }

    // ---------- GET /rooms/{handle}/guestbook ----------

    @GET
    @Path("/{handle}/guestbook")
    @Operation(summary = "Записи в гостевой")
    fun getGuestbook(
        @PathParam("handle") handle: String,
        @QueryParam("cursor") cursor: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int
    ): Page<org.example.dto.GuestbookEntry> {
        val room = RoomEntity.findByHandle(handle) ?: throw NotFoundException("Room '$handle' not found")
        val safeLimit = limit.coerceIn(1, 100)

        val (before, beforeId) = decodeCursor(cursor)
        val rows = GuestbookEntry.page(room.id!!, before, beforeId, safeLimit + 1)

        val hasMore = rows.size > safeLimit
        val pageRows = if (hasMore) rows.subList(0, safeLimit) else rows
        val next = if (hasMore) encodeCursor(pageRows.last().createdAt, pageRows.last().id!!) else null

        return Page(items = pageRows.map { RoomMapper.toGuestbookEntry(it) }, nextCursor = next)
    }

    // ---------- POST /rooms/{handle}/guestbook ----------

    @POST
    @Path("/{handle}/guestbook")
    @Transactional
    @Operation(summary = "Расписаться в гостевой")
    fun postGuestbook(
        @PathParam("handle") handle: String,
        @Valid body: GuestbookPostRequest
    ): Response {
        val author: AppUser = currentUser.resolve()
        val room = RoomEntity.findByHandle(handle) ?: throw NotFoundException("Room '$handle' not found")

        if (!rateLimiter.tryAcquire(author.handle)) {
            return Response.status(429)
                .entity(
                    mapOf(
                        "title" to "Too Many Requests",
                        "status" to 429,
                        "detail" to "Слишком много записей, попробуйте позже"
                    )
                )
                .build()
        }

        val entry = GuestbookEntry().apply {
            this.roomId = room.id!!
            this.authorId = author.id
            this.authorHandle = author.handle
            this.body = body.body
            this.createdAt = Instant.now()
        }
        entry.persist()

        return Response.status(201).entity(RoomMapper.toGuestbookEntry(entry)).build()
    }

    // ---------- курсор: base64("epochMillis:uuid") ----------

    private fun encodeCursor(createdAt: Instant, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${createdAt.toEpochMilli()}:$id".toByteArray())

    private fun decodeCursor(cursor: String?): Pair<Instant?, UUID?> {
        if (cursor.isNullOrBlank()) return null to null
        return try {
            val decoded = String(Base64.getUrlDecoder().decode(cursor))
            val (millis, id) = decoded.split(":", limit = 2)
            Instant.ofEpochMilli(millis.toLong()) to UUID.fromString(id)
        } catch (e: Exception) {
            throw BadRequestException("Invalid cursor")
        }
    }
}
