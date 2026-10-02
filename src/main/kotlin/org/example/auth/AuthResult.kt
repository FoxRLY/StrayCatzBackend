package org.example.auth

sealed class AuthResult {
    data class Ok(val ticket: AuthTicket) : AuthResult()
    data object Missing : AuthResult()
    data object Invalid : AuthResult()
    data object Blocked : AuthResult()
}