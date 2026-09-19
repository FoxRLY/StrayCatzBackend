package org.example.presence.dto

import org.example.presence.model.Status

/**
 * Person — чужой профиль или запись в списке друзей.
 * Невидимки (status = invisible) возвращаются без doing/listening.
 */
data class Person(
    val handle: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val status: Status,
    val mood: String? = null,
    val tagline: String? = null,
    val doing: Doing? = null,
    val listening: Listening? = null
)