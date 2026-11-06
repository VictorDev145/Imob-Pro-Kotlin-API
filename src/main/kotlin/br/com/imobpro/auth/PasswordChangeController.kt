package br.com.imobpro.auth

import br.com.imobpro.auth.TenantPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:Size(min = 12, max = 72) val newPassword: String,
)

@Service
class PasswordChangeService(
    private val jdbc: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun change(principal: TenantPrincipal, request: ChangePasswordRequest) {
        if (request.newPassword.toByteArray(Charsets.UTF_8).size > 72) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds BCrypt's 72-byte limit")
        }
        val hash = jdbc.query(
            """
            SELECT u.password_hash
            FROM users u
            JOIN organization_memberships m ON m.user_id = u.id
            WHERE u.id = ? AND m.organization_id = ? AND m.status = 'ACTIVE'
              AND u.status = 'ACTIVE'
            """.trimIndent(),
            { rs, _ -> rs.getString("password_hash") },
            principal.userId,
            principal.organizationId,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (!passwordEncoder.matches(request.currentPassword, hash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect")
        }
        jdbc.update(
            "UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?",
            passwordEncoder.encode(request.newPassword),
            principal.userId,
        )
        jdbc.update(
            "UPDATE auth_sessions SET revoked_at = NOW() WHERE user_id = ? AND revoked_at IS NULL",
            principal.userId,
        )
    }
}

@RestController
@RequestMapping("/api/v1/me/password")
class PasswordChangeController(private val service: PasswordChangeService) {
    @PostMapping
    fun change(
        authentication: Authentication,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): Map<String, String> {
        val principal = authentication.principal as? TenantPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        service.change(principal, request)
        return mapOf("status" to "password_changed", "next" to "login")
    }
}
