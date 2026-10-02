package org.example.rest

import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.example.service.NexusService

/** Экран входа (нексус) одним запросом — его зовёт api.nexus() в routes/+page.ts. */
@Path("/api/nexus")
@Produces(MediaType.APPLICATION_JSON)
class NexusResource(
    private val currentUser: CurrentUser,
    private val nexus: NexusService,
) {
    @GET
    fun get(@HeaderParam("Authorization") auth: String?): NexusOut = nexus.nexus(currentUser.require(auth).userId)
}
