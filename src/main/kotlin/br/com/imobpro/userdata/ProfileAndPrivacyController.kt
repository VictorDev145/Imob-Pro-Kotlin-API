package br.com.imobpro.userdata

import br.com.imobpro.auth.TenantPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

data class UpdateProfileRequest(
    @field:NotBlank @field:Size(max = 160) val fullName: String,
    @field:Size(max = 40) val phone: String? = null,
)

data class ProfileResponse(
    val userId: UUID,
    val organizationId: UUID,
    val email: String,
    val fullName: String,
    val phone: String?,
    val role: String,
    val createdAt: Instant,
    val emailVerifiedAt: Instant?,
)

data class CreatePrivacyRequest(
    @field:NotBlank val type: String,
    @field:Size(max = 5000) val details: String? = null,
)

data class UpdatePrivacyStatusRequest(
    @field:NotBlank val status: String,
    @field:Size(max = 5000) val resolutionNotes: String? = null,
)

data class PrivacyRequestResponse(
    val id: UUID,
    val userId: UUID,
    val requestType: String,
    val status: String,
    val details: String?,
    val resolutionNotes: String?,
    val createdAt: Instant,
    val resolvedAt: Instant?,
)

@Service
class ProfileAndPrivacyService(private val jdbc: NamedParameterJdbcTemplate) {
    fun profile(principal: TenantPrincipal): ProfileResponse =
        jdbc.query(
            """
            SELECT u.id, m.organization_id, u.email, u.full_name, u.phone, m.role,
                   u.created_at, u.email_verified_at
            FROM users u
            JOIN organization_memberships m ON m.user_id = u.id
            WHERE u.id = :user AND m.organization_id = :org AND m.status = 'ACTIVE'
            """.trimIndent(),
            MapSqlParameterSource().addValue("user", principal.userId)
                .addValue("org", principal.organizationId),
            RowMapper { rs, _ ->
                ProfileResponse(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getString("email"),
                    rs.getString("full_name"),
                    rs.getString("phone"),
                    rs.getString("role"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("email_verified_at")?.toInstant(),
                )
            },
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun updateProfile(principal: TenantPrincipal, request: UpdateProfileRequest) {
        jdbc.update(
            """
            UPDATE users SET full_name = :name, phone = :phone, updated_at = NOW()
            WHERE id = :user
              AND EXISTS (
                SELECT 1 FROM organization_memberships
                WHERE organization_id = :org AND user_id = :user AND status = 'ACTIVE'
              )
            """.trimIndent(),
            MapSqlParameterSource().addValue("name", request.fullName.trim())
                .addValue("phone", request.phone?.trim()).addValue("user", principal.userId)
                .addValue("org", principal.organizationId),
        ).also { if (it == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND) }
    }

    fun export(principal: TenantPrincipal): Map<String, Any?> {
        val profile = profile(principal)
        val favorites = jdbc.queryForList(
            """
            SELECT property_id, created_at FROM favorites
            WHERE organization_id = :org AND user_id = :user
            ORDER BY created_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
        )
        val searches = jdbc.queryForList(
            """
            SELECT id, name, criteria::text AS criteria, alerts_enabled, created_at
            FROM saved_searches WHERE organization_id = :org AND user_id = :user
            ORDER BY created_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
        )
        return mapOf(
            "exportedAt" to Instant.now(),
            "profile" to profile,
            "organizationId" to principal.organizationId,
            "favorites" to favorites,
            "savedSearches" to searches,
        )
    }

    @Transactional
    fun createPrivacyRequest(principal: TenantPrincipal, request: CreatePrivacyRequest): PrivacyRequestResponse {
        val type = request.type.uppercase()
        if (type !in setOf("ACCESS", "CORRECTION", "DELETION", "PORTABILITY", "REVOCATION", "INFORMATION")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported privacy request type")
        }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO privacy_requests (id, organization_id, user_id, request_type, details)
            VALUES (:id, :org, :user, :type, :details)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("user", principal.userId).addValue("type", type)
                .addValue("details", request.details),
        )
        return privacyRequest(principal, id)
    }

    fun myPrivacyRequests(principal: TenantPrincipal): List<PrivacyRequestResponse> =
        jdbc.query(
            """
            SELECT * FROM privacy_requests
            WHERE organization_id = :org AND user_id = :user
            ORDER BY created_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
            privacyMapper,
        )

    fun allPrivacyRequests(principal: TenantPrincipal): List<PrivacyRequestResponse> =
        jdbc.query(
            """
            SELECT * FROM privacy_requests
            WHERE organization_id = :org ORDER BY created_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId),
            privacyMapper,
        )

    @Transactional
    fun updatePrivacyRequest(
        principal: TenantPrincipal,
        id: UUID,
        request: UpdatePrivacyStatusRequest,
    ) {
        val status = request.status.uppercase()
        if (status !in setOf("IN_PROGRESS", "RESOLVED", "REJECTED")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported privacy request status")
        }
        val changed = jdbc.update(
            """
            UPDATE privacy_requests
            SET status = :status, resolution_notes = :notes,
                resolved_at = CASE WHEN :status IN ('RESOLVED', 'REJECTED') THEN NOW() ELSE NULL END
            WHERE id = :id AND organization_id = :org
            """.trimIndent(),
            MapSqlParameterSource().addValue("status", status)
                .addValue("notes", request.resolutionNotes).addValue("id", id)
                .addValue("org", principal.organizationId),
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    private fun privacyRequest(principal: TenantPrincipal, id: UUID): PrivacyRequestResponse =
        jdbc.query(
            """
            SELECT * FROM privacy_requests
            WHERE id = :id AND organization_id = :org AND user_id = :user
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
            privacyMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private val privacyMapper = RowMapper<PrivacyRequestResponse> { rs, _ ->
        PrivacyRequestResponse(
            rs.getObject("id", UUID::class.java),
            rs.getObject("user_id", UUID::class.java),
            rs.getString("request_type"),
            rs.getString("status"),
            rs.getString("details"),
            rs.getString("resolution_notes"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("resolved_at")?.toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/me")
class ProfileAndPrivacyController(private val service: ProfileAndPrivacyService) {
    @GetMapping("/profile")
    fun profile(authentication: Authentication): ProfileResponse =
        service.profile(authentication.tenant())

    @PatchMapping("/profile")
    fun updateProfile(
        authentication: Authentication,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): Map<String, String> {
        service.updateProfile(authentication.tenant(), request)
        return mapOf("status" to "updated")
    }

    @GetMapping("/export")
    fun export(authentication: Authentication): Map<String, Any?> =
        service.export(authentication.tenant())

    @PostMapping("/privacy-requests")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun createPrivacyRequest(
        authentication: Authentication,
        @Valid @RequestBody request: CreatePrivacyRequest,
    ): PrivacyRequestResponse = service.createPrivacyRequest(authentication.tenant(), request)

    @GetMapping("/privacy-requests")
    fun myPrivacyRequests(authentication: Authentication): List<PrivacyRequestResponse> =
        service.myPrivacyRequests(authentication.tenant())

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}

@RestController
@RequestMapping("/api/v1/privacy-requests")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
class PrivacyAdministrationController(private val service: ProfileAndPrivacyService) {
    @GetMapping
    fun list(authentication: Authentication): List<PrivacyRequestResponse> =
        service.allPrivacyRequests(authentication.tenant())

    @PatchMapping("/{id}")
    fun update(
        authentication: Authentication,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdatePrivacyStatusRequest,
    ) = service.updatePrivacyRequest(authentication.tenant(), id, request)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
