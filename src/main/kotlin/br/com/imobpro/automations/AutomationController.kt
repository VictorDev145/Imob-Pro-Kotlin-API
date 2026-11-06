package br.com.imobpro.automations

import br.com.imobpro.auth.TenantPrincipal
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
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

data class CreateAutomationRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:Pattern(regexp = "LEAD_CREATED|LEAD_STATUS_CHANGED|VISIT_SCHEDULED|PROPERTY_PUBLISHED")
    val triggerKey: String,
    @field:Pattern(regexp = "CREATE_TASK|NOTIFY_ASSIGNEE|NOTIFY_MANAGERS")
    val actionKey: String,
    val configuration: JsonNode? = null,
)

data class SetAutomationEnabledRequest(val enabled: Boolean)

data class AutomationResponse(
    val id: UUID,
    val name: String,
    val triggerKey: String,
    val actionKey: String,
    val configuration: JsonNode,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Service
class AutomationService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun list(principal: TenantPrincipal): List<AutomationResponse> =
        jdbc.query(
            """
            SELECT * FROM automation_rules
            WHERE organization_id = :org
            ORDER BY created_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId),
            mapper,
        )

    @Transactional
    fun create(principal: TenantPrincipal, request: CreateAutomationRequest): AutomationResponse {
        val configuration = request.configuration ?: objectMapper.createObjectNode()
        if (!configuration.isObject || configuration.toString().length > 8000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Configuration must be a JSON object up to 8 KB")
        }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO automation_rules (
                id, organization_id, name, trigger_key, action_key, configuration, created_by
            ) VALUES (
                :id, :org, :name, :trigger, :action, CAST(:configuration AS jsonb), :creator
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", principal.organizationId)
                .addValue("name", request.name.trim())
                .addValue("trigger", request.triggerKey)
                .addValue("action", request.actionKey)
                .addValue("configuration", configuration.toString())
                .addValue("creator", principal.userId),
        )
        return get(principal, id)
    }

    fun get(principal: TenantPrincipal, id: UUID): AutomationResponse =
        jdbc.query(
            """
            SELECT * FROM automation_rules
            WHERE id = :id AND organization_id = :org
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId),
            mapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun setEnabled(principal: TenantPrincipal, id: UUID, enabled: Boolean) {
        val changed = jdbc.update(
            """
            UPDATE automation_rules
            SET enabled = :enabled, updated_at = NOW()
            WHERE id = :id AND organization_id = :org
            """.trimIndent(),
            MapSqlParameterSource().addValue("enabled", enabled)
                .addValue("id", id).addValue("org", principal.organizationId),
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @Transactional
    fun delete(principal: TenantPrincipal, id: UUID) {
        val changed = jdbc.update(
            "DELETE FROM automation_rules WHERE id = :id AND organization_id = :org",
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId),
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    private val mapper = RowMapper<AutomationResponse> { rs, _ ->
        AutomationResponse(
            rs.getObject("id", UUID::class.java),
            rs.getString("name"),
            rs.getString("trigger_key"),
            rs.getString("action_key"),
            objectMapper.readTree(rs.getString("configuration")),
            rs.getBoolean("enabled"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/automations")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
class AutomationController(private val service: AutomationService) {
    @GetMapping
    fun list(authentication: Authentication): List<AutomationResponse> =
        service.list(authentication.tenant())

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreateAutomationRequest,
    ): AutomationResponse = service.create(authentication.tenant(), request)

    @PatchMapping("/{id}/enabled")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun setEnabled(
        authentication: Authentication,
        @PathVariable id: UUID,
        @Valid @RequestBody request: SetAutomationEnabledRequest,
    ) = service.setEnabled(authentication.tenant(), id, request.enabled)

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(authentication: Authentication, @PathVariable id: UUID) =
        service.delete(authentication.tenant(), id)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
