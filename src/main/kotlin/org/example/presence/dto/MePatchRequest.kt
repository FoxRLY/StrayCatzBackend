package org.example.presence.dto

import jakarta.validation.constraints.Size
import org.example.presence.model.Status

/** Тело PATCH /me */
data class MePatchRequest(
    val status: Status? = null,
    @field:Size(max = 140) val mood: String? = null,
    @field:Size(max = 140) val tagline: String? = null
)