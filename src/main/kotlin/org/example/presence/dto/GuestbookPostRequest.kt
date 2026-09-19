package org.example.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Тело POST .../guestbook */
data class GuestbookPostRequest(
    @field:NotBlank
    @field:Size(max = 600)
    val body: String
)