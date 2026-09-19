package org.example.dto

/** components/schemas/Page — обёртка курсорной пагинации. */
data class Page<T>(
    val items: List<T>,
    val nextCursor: String? = null
)
