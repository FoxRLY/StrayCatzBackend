package org.example.presence.dto

/** Чем человек сейчас занят (например, работает в приложении). */
data class Doing(
    val app: String? = null,
    val activity: String? = null
)