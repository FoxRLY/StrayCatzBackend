package org.example.rest

import java.time.Instant
import java.time.format.DateTimeParseException

/** Курсоры ?before= / ?after= — ISO-время, например 2026-09-27T12:00:00Z. */
fun parseInstantParam(value: String?, name: String): Instant? = value?.takeIf { it.isNotBlank() }?.let {
    try {
        Instant.parse(it)
    } catch (e: DateTimeParseException) {
        throw ApiException.badRequest("invalid_$name", "$name — ISO-время, например 2026-09-27T12:00:00Z")
    }
}
