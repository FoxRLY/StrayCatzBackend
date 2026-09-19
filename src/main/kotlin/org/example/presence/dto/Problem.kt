package org.example.presence.dto

/** RFC7807-подобный формат ошибки (components/responses/Problem). */
data class Problem(
    val title: String,
    val status: Int,
    val detail: String? = null
)
