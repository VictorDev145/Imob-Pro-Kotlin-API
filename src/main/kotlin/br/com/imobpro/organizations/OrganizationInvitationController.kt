package br.com.imobpro.organizations

import br.com.imobpro.auth.TenantPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class CreateInvitationRequest(
    @field:Email @field:NotBlank @field:Size(max = 254) val email: String,
    @field:Pattern(regexp = "ADMIN|MANAGER|AGENT|CUSTOMER") val role: String,
)

data class InvitationResponse(
    val id: UUID,
    val email: String,
    val role: String,
    val expiresAt: Instant,
    val acceptedAt: Instant?,
    val revokedAt: Instant?,
    val createdAt: Instant,
)

data class AcceptInvitationRequest(
    @field:NotBlank val token: String,
    @field:Size(max = 160) val fullName: String? = null,
    @field:Size(min = 12, max = 72) val password: String? = null,
)

@Service
class OrganizationInvitationService(
    private val jdbc: JdbcTemplate,
    private val mailSender: JavaMailSender,
    private val passwordEncoder: PasswordEncoder,
    @Value("${app.public-base-url:http://localhost:3000}") private val publicBaseUrl: String,
    @Value("${spring.mail.username:}") private val mailUsername: String,
) {
    private val random = SecureRandom()

    @Transactional
    fun create(principal: TenantPrincipal, request: CreateInvitationRequest): InvitationResponse {
        if (request.role == "ADMIN" && principal.role != "OWNER") {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can invite administrators")
        }
        val email = request.email.trim().lowercase()
        val activeMember = jdbc.query(
            """
            SELECT 1 FROM users u JOIN organization_memberships m ON m.user_id = u.id
            WHERE m.organization_id = ? AND lower(u.email) = ? AND m.status = 'ACTIVE'
            """.trimIndent(),
            { rs, _ -> rs.getInt(1) },
            principal.organizationId,
            email,
        ).isNotEmpty()
        if (activeMember) throw ResponseStatusException(HttpStatus.CONFLICT, "User is already an active member")
        jdbc.update(
            """
            UPDATE organization_invitations SET revoked_at = NOW()
            WHERE organization_id = ? AND lower(email) = ? AND accepted_at IS NULL AND revoked_at IS NULL
            """.trimIndent(),
            principal.organizationId,
            email,
        )
        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO organization_invitations (
                id, organization_id, email, role, token_hash, invited_by, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, NOW() + INTERVAL '7 days')
            """.trimIndent(),
            id,
            principal.organizationId,
            email,
            request.role,
            digest(token),
            principal.userId,
        )
        val orgName = jdbc.queryForObject(
            "SELECT name FROM organizations WHERE id = ?",
            String::class.java,
            principal.organizationId,
        ) ?: "Imob-Pro"
        val message = SimpleMailMessage()
        message.setTo(email)
        if (mailUsername.isNotBlank()) message.from = mailUsername
        message.subject = "Convite para $orgName — Imob-Pro"
        message.text = "Você foi convidado para participar de $orgName. Aceite o convite neste link (válido por 7 dias):\n\n${publicBaseUrl.trimEnd('/')}/accept-invitation?token=$token"
        try {
            mailSender.send(message)
        } catch (_: MailException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invitation email could not be sent")
        }
        return get(principal.organizationId, id)
    }

    fun list(principal: TenantPrincipal): List<InvitationResponse> =
        jdbc.query(
            """
            SELECT id, email, role, expires_at, accepted_at, revoked_at, created_at
            FROM organization_invitations
            WHERE organization_id = ?
            ORDER BY created_at DESC
            """.trimIndent(),
            mapper,
            principal.organizationId,
        )

    @Transactional
    fun revoke(principal: TenantPrincipal, invitationId: UUID) {
        val changed = jdbc.update(
            """
            UPDATE organization_invitations SET revoked_at = NOW()
            WHERE id = ? AND organization_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
            """.trimIndent(),
            invitationId,
            principal.organizationId,
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @Transactional
    fun accept(request: AcceptInvitationRequest) {
        val invitation = jdbc.query(
            """
            SELECT id, organization_id, email, role
            FROM organization_invitations
            WHERE token_hash = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > NOW()
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                arrayOf(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getString("email"),
                    rs.getString("role"),
                )
            },
            digest(request.token),
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired invitation")
        val invitationId = invitation[0] as UUID
        val organizationId = invitation[1] as UUID
        val email = invitation[2] as String
        val role = invitation[3] as String
        val userId = jdbc.query(
            "SELECT id FROM users WHERE email = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            email,
        ).singleOrNull() ?: run {
            val name = request.fullName?.trim().orEmpty()
            val password = request.password.orEmpty()
            if (name.isBlank() || password.isBlank() || password.toByteArray(Charsets.UTF_8).size > 72) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "New accounts require fullName and a password of 12–72 characters")
            }
            val newUserId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
                newUserId,
                email,
                passwordEncoder.encode(password),
                name,
            )
            newUserId
        }
        val existing = jdbc.query(
            """
            SELECT status FROM organization_memberships
            WHERE organization_id = ? AND user_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("status") },
            organizationId,
            userId,
        ).singleOrNull()
        if (existing == "ACTIVE") {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User is already a member")
        }
        if (existing == null) {
            jdbc.update(
                """
                INSERT INTO organization_memberships (organization_id, user_id, role, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """.trimIndent(),
                organizationId,
                userId,
                role,
            )
        } else {
            jdbc.update(
                """
                UPDATE organization_memberships
                SET role = ?, status = 'ACTIVE', updated_at = NOW()
                WHERE organization_id = ? AND user_id = ?
                """.trimIndent(),
                role,
                organizationId,
                userId,
            )
        }
        jdbc.update("UPDATE organization_invitations SET accepted_at = NOW() WHERE id = ?", invitationId)
    }

    private fun get(organizationId: UUID, id: UUID): InvitationResponse =
        jdbc.query(
            """
            SELECT id, email, role, expires_at, accepted_at, revoked_at, created_at
            FROM organization_invitations WHERE organization_id = ? AND id = ?
            """.trimIndent(),
            mapper,
            organizationId,
            id,
        ).single()

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val mapper = org.springframework.jdbc.core.RowMapper<InvitationResponse> { rs, _ ->
        InvitationResponse(
            rs.getObject("id", UUID::class.java),
            rs.getString("email"),
            rs.getString("role"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("accepted_at")?.toInstant(),
            rs.getTimestamp("revoked_at")?.toInstant(),
            rs.getTimestamp("created_at").toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/organizations/current/invitations")
class OrganizationInvitationController(private val service: OrganizationInvitationService) {
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreateInvitationRequest,
    ): InvitationResponse = service.create(authentication.tenant(), request)

    @org.springframework.web.bind.annotation.GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    fun list(authentication: Authentication): List<InvitationResponse> =
        service.list(authentication.tenant())

    @DeleteMapping("/{invitationId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(authentication: Authentication, @PathVariable invitationId: UUID) =
        service.revoke(authentication.tenant(), invitationId)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}

@RestController
@RequestMapping("/api/v1/auth/invitations")
class AcceptInvitationController(private val service: OrganizationInvitationService) {
    @PostMapping("/accept")
    fun accept(@Valid @RequestBody request: AcceptInvitationRequest): Map<String, String> {
        service.accept(request)
        return mapOf("status" to "accepted")
    }
}
