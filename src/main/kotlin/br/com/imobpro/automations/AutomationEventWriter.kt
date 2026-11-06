package br.com.imobpro.automations

import br.com.imobpro.auth.TenantPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AutomationEventWriter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun enqueue(
        principal: TenantPrincipal,
        triggerKey: String,
        resourceType: String,
        resourceId: UUID?,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        require(triggerKey.length in 1..80)
        require(resourceType.length in 1..60)
        jdbc.update(
            """
            INSERT INTO automation_events (
                organization_id, trigger_key, resource_type, resource_id,
                actor_user_id, payload
            ) VALUES (
                :organizationId, :triggerKey, :resourceType, :resourceId,
                :actorUserId, CAST(:payload AS JSONB)
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("organizationId", principal.organizationId)
                .addValue("triggerKey", triggerKey)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("actorUserId", principal.userId)
                .addValue("payload", objectMapper.writeValueAsString(payload)),
        )
    }
}
