package br.com.imobpro.publicportal

import br.com.imobpro.auth.AuthService
import br.com.imobpro.auth.LoginRequest
import br.com.imobpro.auth.TokenResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class PublicCustomerRegistrationRequest(
    @field:NotBlank @field:Size(max = 160) val fullName: String,
    @field:Email @field:NotBlank @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(min = 12, max = 72) val password: String,
    @field:Size(max = 40) val phone: String? = null,
)

@RestController
@RequestMapping("/api/v1/public/{organizationSlug}/customers")
class PublicCustomerRegistrationController(
    private val jdbc: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
    private val authService: AuthService,
) {
    @PostMapping("/register")
    @Transactional
    fun register(
        @PathVariable organizationSlug: String,
        @Valid @RequestBody request: PublicCustomerRegistrationRequest,
    ): TokenResponse {
        if (request.password.toByteArray(Charsets.UTF_8).size > 72) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds BCrypt's 72-byte limit")
        }
        val organizationId = jdbc.query(
            "SELECT id FROM organizations WHERE slug = ? AND status = 'ACTIVE'",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            organizationSlug,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val email = request.email.trim().lowercase()
        val exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users WHERE email = ?)",
            Boolean::class.java,
            email,
        )
        if (exists == true) throw ResponseStatusException(HttpStatus.CONFLICT, "Email already registered")

        val userId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO users (id, email, password_hash, full_name, phone, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            userId,
            email,
            passwordEncoder.encode(request.password),
            request.fullName.trim(),
            request.phone?.trim(),
        )
        jdbc.update(
            """
            INSERT INTO organization_memberships (organization_id, user_id, role, status)
            VALUES (?, ?, 'CUSTOMER', 'ACTIVE')
            """.trimIndent(),
            organizationId,
            userId,
        )
        return authService.login(LoginRequest(email, request.password, organizationId))
    }
}
