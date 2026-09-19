package org.example.dto

/** Комната целиком — раскладка, обои, словарь. */
data class Room(
    val handle: String,
    val ownerHandle: String,
    val title: String,
    val wallpaperUrl: String? = null,
    val layout: Map<String, Any?>? = null,
    val dictionary: Map<String, String>? = null,
    val backdropDim: Double,
    val open: Boolean
)