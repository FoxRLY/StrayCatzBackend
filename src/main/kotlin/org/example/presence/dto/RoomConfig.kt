package org.example.dto

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Тело PUT /me/room — полная замена конфигурации.
 * backdropDim ниже 0.25 сервер обязан поднять до 0.25 (см. Room-сущность/ресурс).
 */
data class RoomConfig(
    @field:NotBlank
    @field:Size(max = 120)
    val title: String,

    val wallpaperUrl: String? = null,

    val layout: Map<String, Any?>? = null,

    val dictionary: Map<String, String>? = null,

    @field:DecimalMin(value = "0.0")
    @field:DecimalMax(value = "1.0")
    val backdropDim: Double = 1.0,

    val open: Boolean = true
)