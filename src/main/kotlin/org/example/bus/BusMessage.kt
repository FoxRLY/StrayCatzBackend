package org.example.bus

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BusMessage(
    val scope: Scope,
    val chatId: UUID? = null,
    val roomOwnerId: UUID? = null,
    val userIds: List<UUID>? = null,
    val liveOnly: Boolean = false,
    val pointer: Pointer? = null,
    val frame: JsonNode? = null,
)