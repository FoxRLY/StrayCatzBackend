package org.example.presence.dto

import org.example.presence.model.Status

/** Me — свой профиль вместе с присутствием (плюс приватные поля вроде email). */
data class Me(
    val handle: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val email: String? = null,
    val status: Status,
    val mood: String? = null,
    val tagline: String? = null,
    val doing: Doing? = null,
    val listening: Listening? = null
)