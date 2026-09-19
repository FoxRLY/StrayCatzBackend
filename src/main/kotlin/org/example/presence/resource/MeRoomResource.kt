package org.example.resource

import com.example.presence.resource.CurrentUser
import org.example.dto.Room
import org.example.dto.RoomConfig
import org.example.mapper.RoomMapper
import org.example.model.Room as RoomEntity
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant

/** Минимальное значение backdropDim — гарантия читаемости, применяется на сервере. */
private const val MIN_BACKDROP_DIM = 0.25

@Path("/me/room")
@Tag(name = "Комнаты")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MeRoomResource {

    @Inject
    lateinit var currentUser: CurrentUser

    @PUT
    @Transactional
    @Operation(summary = "Сохранить свою комнату")
    fun putMyRoom(@Valid config: RoomConfig): Room {
        val me = currentUser.resolve()

        val room = RoomEntity.findByOwnerId(me.id!!) ?: RoomEntity().apply {
            this.ownerId = me.id!!
            this.handle = me.handle
            this.createdAt = Instant.now()
        }

        // Полная замена конфигурации.
        room.title = config.title
        room.wallpaperUrl = config.wallpaperUrl
        room.layout = config.layout
        room.dictionary = config.dictionary
        room.open = config.open

        // backdropDim ниже 0.25 — не вкусовщина, а гарантия платформы: поднимаем принудительно.
        room.backdropDim = config.backdropDim.coerceAtLeast(MIN_BACKDROP_DIM)

        room.updatedAt = Instant.now()
        room.persist()

        return RoomMapper.toRoom(room, ownerHandle = me.handle)
    }
}
