package org.example.auth

import java.time.Instant

sealed class AuthResult {
    data class Ok(val ticket: AuthTicket) : AuthResult()
    data object Missing : AuthResult()
    data object Invalid : AuthResult()
    data object Blocked : AuthResult()
    /** Заблокирован модератором. until = Staff.FOREVER — навсегда. */
    data class Banned(val until: Instant, val reason: String?) : AuthResult()
}
