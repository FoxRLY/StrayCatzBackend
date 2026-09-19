package org.example.presence.dto

/** Что человек сейчас слушает — для «слушаем вместе». */
data class Listening(
    val track: String? = null,
    val artist: String? = null
)