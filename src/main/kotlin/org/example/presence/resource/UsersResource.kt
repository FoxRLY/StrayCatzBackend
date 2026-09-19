package org.example.presence.resource

import org.example.presence.dto.Person
import org.example.presence.dto.Problem
import org.example.presence.mapper.PresenceMapper
import org.example.presence.model.AppUser
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@Path("/users")
@Tag(name = "Люди и присутствие")
@Produces(MediaType.APPLICATION_JSON)
class UsersResource {

    @GET
    @Path("/{handle}")
    @Operation(summary = "Чужой профиль")
    @APIResponse(
        responseCode = "404",
        content = [Content(schema = Schema(implementation = Problem::class))]
    )
    fun getUser(@PathParam("handle") handle: String): Person {
        val u: AppUser = AppUser.findByHandle(handle)
            ?: throw NotFoundException("User '$handle' not found")
        return PresenceMapper.toPerson(u)
    }
}
