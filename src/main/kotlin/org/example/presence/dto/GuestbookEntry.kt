package org.example.dto

import java.time.Instant

/** Запись в гостевой книге. */
data class GuestbookEntry(
    val id: String,
    val authorHandle: String,
    val body: String,
    val createdAt: Instant
)