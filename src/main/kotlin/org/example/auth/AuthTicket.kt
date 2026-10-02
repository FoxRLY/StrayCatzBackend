package org.example.auth

import java.util.UUID

/** Кто пришёл. email есть только у JWT из Keycloak (у dev-токена null). */
data class AuthTicket(val userId: UUID, val username: String, val email: String? = null)