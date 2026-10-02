package org.example.auth

import com.fasterxml.jackson.annotation.JsonProperty

/** Ответ token endpoint'а Keycloak'а. */
data class KcTokenResponse(
    @JsonProperty("access_token") val accessToken: String = "",
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    @JsonProperty("expires_in") val expiresIn: Long = 0,
    @JsonProperty("refresh_expires_in") val refreshExpiresIn: Long = 0,
    @JsonProperty("token_type") val tokenType: String = "Bearer",
)