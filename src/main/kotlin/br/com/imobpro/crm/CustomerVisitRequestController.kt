package br.com.imobpro.crm

import br.com.imobpro.auth.TenantPrincipal
import br.com.imobpro.automations.AutomationEventWriter
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

data class CustomerVisitRequest(
    val propertyId: UUID,
    val scheduledAt: Instant,
    @field:Size(max = 10000) val notes: String? = null,
)

@RestController
@RequestMapping("/api/v1/me/visit-requests")
@PreAuthorize("hasRole('CUSTOMER')")
class CustomerVisitRequestController(
    private val jdbc: JdbcTemplate,
    private val automationEvents: AutomationEventWriter,
) {
    @PostMapping
    @Transactional
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CustomerVisitRequest,
    ): Map<String, Any> {
        val principal = authentication.principal as? TenantPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (!request.scheduledAt.isAfter(Instant.now().minusSeconds(60))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Visit must be scheduled in the future")
        }
        val visible = jdbc.query(
            """
            SELECT 1
            FROM properties p
            JOIN listings l ON l.property_id = p.id AND l.organization_id = p.organization_id
            WHERE p.id = ? AND p.organization_id = ?
              AND p.status = 'ACTIVE' AND p.archived_at IS NULL
              AND l.status = 'PUBLISHED' AND l.channel = 'WEBSITE'
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getInt(1) },
            request.propertyId,
            principal.organizationId,
        ).isNotEmpty()
        if (!visible) throw ResponseStatusException(HttpStatus.NOT_FOUND)

        val visitId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO visits (
                id, organization_id, property_id, scheduled_at,
                status, notes, created_by
            ) VALUES (?, ?, ?, ?, 'SCHEDULED', ?, ?)
            """.trimIndent(),
            visitId,
            principal.organizationId,
            request.propertyId,
            java.sql.Timestamp.from(request.scheduledAt),
            request.notes,
            principal.userId,
        )
        jdbc.update(
            """
            INSERT INTO notifications (organization_id, user_id, type, title, body, resource_type, resource_id)
            SELECT organization_id, user_id, 'VISIT_REQUESTED', 'Nova solicitação de visita',
                   'Um cliente solicitou uma visita a um imóvel.', 'VISIT', ?
            FROM organization_memberships
            WHERE organization_id = ? AND role IN ('OWNER', 'ADMIN', 'MANAGER') AND status = 'ACTIVE'
            """.trimIndent(),
            visitId,
            principal.organizationId,
        )
        automationEvents.enqueue(principal, "VISIT_SCHEDULED", "VISIT", visitId, mapOf("propertyId" to request.propertyId))
        return mapOf("id" to visitId, "status" to "SCHEDULED", "scheduledAt" to request.scheduledAt)
    }
}
