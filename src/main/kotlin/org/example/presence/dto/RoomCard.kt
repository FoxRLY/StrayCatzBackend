package org.example.dto

/** Карточка комнаты — для списка /rooms. */
data class RoomCard(
    val handle: String,
    val title: String,
    val wallpaperThumbUrl: String? = null,
    val open: Boolean
)