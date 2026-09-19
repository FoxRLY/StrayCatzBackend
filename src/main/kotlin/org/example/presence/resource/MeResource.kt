package org.example.presence.resource

import com.example.presence.resource.CurrentUser
import org.example.presence.dto.Me
import org.example.presence.dto.MePatchRequest
import org.example.presence.dto.Person
import org.example.presence.mapper.PresenceMapper
import org.example.presence.model.Friendship
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant

@Path("/me")
@Tag(name = "Люди и присутствие")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MeResource {

    @Inject
    lateinit var currentUser: CurrentUser

    @GET
    @Operation(summary = "Свой профиль вместе с присутствием")
    fun getMe(): Me = PresenceMapper.toMe(currentUser.resolve())

    @PATCH
    @Transactional
    @Operation(summary = "Поменять статус, настроение, подпись")
    fun patchMe(@Valid body: MePatchRequest): Me {
        val u = currentUser.resolve()
        body.status?.let { u.status = it }
        body.mood?.let { u.mood = it }
        body.tagline?.let { u.tagline = it }
        u.updatedAt = Instant.now()
        (u as PanacheEntityBase).persist()
        return PresenceMapper.toMe(u)
    }

    @GET
    @Path("/friends")
    @Operation(summary = "Друзья с присутствием")
    fun getFriends(): List<Person> {
        val me = currentUser.resolve()
        return Friendship.friendsOf(me.id!!).map { PresenceMapper.toPerson(it) }
    }
}
