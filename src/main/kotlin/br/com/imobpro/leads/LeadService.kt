package br.com.imobpro.leads

import br.com.imobpro.auth.TenantPrincipal
import br.com.imobpro.automations.AutomationEventWriter
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class LeadService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val automationEvents: AutomationEventWriter,
) {
    @Transactional
    fun create(principal: TenantPrincipal, request: CreateLeadRequest): LeadResponse {
        if (request.propertyId != null) {
            val propertyBelongsToTenant = jdbc.query(
                """
                SELECT 1 FROM properties
                WHERE id = :propertyId
                  AND organization_id = :organizationId
                  AND archived_at IS NULL
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("propertyId", request.propertyId)
                    .addValue("organizationId", principal.organizationId),
            ) { rs, _ -> rs.getInt(1) }.isNotEmpty()
            if (!propertyBelongsToTenant) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Property not found")
            }
        }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO leads (
                id, organization_id, created_by, full_name, email, phone, source, message, property_id
            ) VALUES (
                :id, :organizationId, :createdBy, :fullName, :email, :phone, :source, :message, :propertyId
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("organizationId", principal.organizationId)
                .addValue("createdBy", principal.userId)
                .addValue("fullName", request.fullName.trim())
                .addValue("email", request.email?.trim()?.lowercase())
                .addValue("phone", request.phone?.trim())
                .addValue("source", request.source)
                .addValue("message", request.message)
                .addValue("propertyId", request.propertyId),
        )
        automationEvents.enqueue(
            principal, "LEAD_CREATED", "LEAD", id,
            mapOf("leadId" to id, "propertyId" to request.propertyId),
        )
        return get(principal, id)
    }

    fun list(
        principal: TenantPrincipal,
        offset: Int,
        limit: Int,
        status: String?,
    ): LeadPage {
        val parameters = MapSqlParameterSource()
            .addValue("organizationId", principal.organizationId)
            .addValue("offset", offset)
            .addValue("limit", limit)
        val statusFilter = if (status.isNullOrBlank()) {
            ""
        } else {
            parameters.addValue("status", status)
            " AND status = :status"
        }
        val items = jdbc.query(
            """
            SELECT * FROM leads
            WHERE organization_id = :organizationId
            $statusFilter
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            parameters,
            leadRowMapper(),
        )
        return LeadPage(items, offset, limit)
    }

    fun get(principal: TenantPrincipal, leadId: UUID): LeadResponse =
        jdbc.query(
            """
            SELECT * FROM leads
            WHERE id = :id AND organization_id = :organizationId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", leadId)
                .addValue("organizationId", principal.organizationId),
            leadRowMapper(),
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun updateStatus(principal: TenantPrincipal, leadId: UUID, status: String) {
        val updated = jdbc.update(
            """
            UPDATE leads SET status = :status, updated_at = NOW()
            WHERE id = :id AND organization_id = :organizationId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", status)
                .addValue("id", leadId)
                .addValue("organizationId", principal.organizationId),
        )
        if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        automationEvents.enqueue(principal, "LEAD_STATUS_CHANGED", "LEAD", leadId, mapOf("leadId" to leadId))
    }

    @Transactional
    fun assign(principal: TenantPrincipal, leadId: UUID, assignedToUserId: UUID?) {
        if (assignedToUserId != null) {
            val activeMembership = jdbc.query(
                """
                SELECT 1 FROM organization_memberships
                WHERE organization_id = :organizationId
                  AND user_id = :userId
                  AND status = 'ACTIVE'
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("organizationId", principal.organizationId)
                    .addValue("userId", assignedToUserId),
            ) { rs, _ -> rs.getInt(1) }.isNotEmpty()
            if (!activeMembership) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Assignee is not an active member of this organization",
                )
            }
        }
        val updated = jdbc.update(
            """
            UPDATE leads SET assigned_to = :assignedTo, updated_at = NOW()
            WHERE id = :id AND organization_id = :organizationId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("assignedTo", assignedToUserId)
                .addValue("id", leadId)
                .addValue("organizationId", principal.organizationId),
        )
        if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    private fun leadRowMapper() = org.springframework.jdbc.core.RowMapper<LeadResponse> { rs, _ ->
        LeadResponse(
            id = rs.getObject("id", UUID::class.java),
            fullName = rs.getString("full_name"),
            email = rs.getString("email"),
            phone = rs.getString("phone"),
            source = rs.getString("source"),
            status = rs.getString("status"),
            message = rs.getString("message"),
            propertyId = rs.getObject("property_id", UUID::class.java),
            assignedToUserId = rs.getObject("assigned_to", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
