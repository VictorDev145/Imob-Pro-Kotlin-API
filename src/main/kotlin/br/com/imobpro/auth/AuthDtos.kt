package br.com.imobpro.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank @field:Size(max = 160) val fullName: String,
    @field:Email @field:NotBlank @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(min = 12, max = 72) val password: String,
    @field:NotBlank @field:Size(max = 160) val organizationName: String,
)
data class LoginRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val password: String,
    val organizationId: UUID? = null,
)
data class RefreshRequest(@field:NotBlank val refreshToken: String)
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    val refreshExpiresAt: Instant,
)
data class AuthenticatedUser(val userId: UUID, val organizationId: UUID, val email: String, val fullName: String, val role: String)
