package br.com.imobpro.automations

import br.com.imobpro.auth.TenantPrincipal
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AutomationEventWriter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun enqueue(
        principal: TenantPrincipal,
        triggerKey: String,
        resourceType: String,
        resourceId: UUID,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        jdbc.update(
            """
            INSERT INTO automation_events (
                organization_id, trigger_key, resource_type, resource_id,
                actor_user_id, payload
            ) VALUES (
                :org, :trigger, :resourceType, :resourceId, :actor, CAST(:payload AS jsonb)
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("org", principal.organizationId)
                .addValue("trigger", triggerKey)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("actor", principal.userId)
                .addValue("payload", objectMapper.writeValueAsString(payload)),
        )
    }
}

private data class PendingAutomationEvent(
    val id: UUID,
    val organizationId: UUID,
    val triggerKey: String,
    val resourceType: String,
    val resourceId: UUID?,
    val actorUserId: UUID,
    val payload: JsonNode,
)

private data class EnabledAutomation(
    val actionKey: String,
    val configuration: JsonNode,
)

@Component
class AutomationEventWorker(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${imobpro.automations.enabled:true}") private val enabled: Boolean,
) {
    @Scheduled(fixedDelayString = "\${imobpro.automations.poll-interval-ms:5000}")
    @Transactional
    fun dispatch() {
        if (!enabled) return
        val events = jdbc.query(
            """
            SELECT * FROM automation_events
            WHERE status = 'PENDING' AND attempts < 5 AND available_at <= NOW()
            ORDER BY created_at
            LIMIT 20
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            MapSqlParameterSource(),
            eventMapper,
        )
        events.forEach(::process)
    }

    private fun process(event: PendingAutomationEvent) {
        jdbc.update(
            "UPDATE automation_events SET status = 'PROCESSING', attempts = attempts + 1 WHERE id = :id",
            MapSqlParameterSource().addValue("id", event.id),
        )
        try {
            val rules = jdbc.query(
                """
                SELECT action_key, configuration
                FROM automation_rules
                WHERE organization_id = :org AND trigger_key = :trigger AND enabled = TRUE
                ORDER BY created_at
                """.trimIndent(),
                MapSqlParameterSource().addValue("org", event.organizationId)
                    .addValue("trigger", event.triggerKey),
                RowMapper { rs, _ ->
                    EnabledAutomation(
                        rs.getString("action_key"),
                        objectMapper.readTree(rs.getString("configuration")),
                    )
                },
            )
            rules.forEach { executeAction(event, it) }
            jdbc.update(
                """
                UPDATE automation_events
                SET status = 'PROCESSED', processed_at = NOW(), last_error = NULL
                WHERE id = :id
                """.trimIndent(),
                MapSqlParameterSource().addValue("id", event.id),
            )
        } catch (exception: Exception) {
            jdbc.update(
                """
                UPDATE automation_events
                SET status = 'FAILED', last_error = :error
                WHERE id = :id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", event.id)
                    .addValue("error", exception.message.orEmpty().take(1000)),
            )
        }
    }

    private fun executeAction(event: PendingAutomationEvent, rule: EnabledAutomation) {
        when (rule.actionKey) {
            "NOTIFY_MANAGERS" -> notifyManagers(event)
            "NOTIFY_ASSIGNEE" -> notifyAssignee(event)
            "CREATE_TASK" -> createFollowUpTask(event, rule.configuration)
            else -> error("Unsupported automation action")
        }
    }

    private fun notifyManagers(event: PendingAutomationEvent) {
        jdbc.update(
            """
            INSERT INTO notifications (
                organization_id, user_id, type, title, body, resource_type, resource_id
            )
            SELECT :org, user_id, 'AUTOMATION', 'Automação executada', :body, :type, :resource
            FROM organization_memberships
            WHERE organization_id = :org AND role IN ('OWNER', 'ADMIN', 'MANAGER')
              AND status = 'ACTIVE'
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("org", event.organizationId)
                .addValue("body", "Evento " + event.triggerKey + " em " + event.resourceType)
                .addValue("type", event.resourceType)
                .addValue("resource", event.resourceId),
        )
    }

    private fun notifyAssignee(event: PendingAutomationEvent) {
        val assignee = event.payload.path("assignedToUserId").asText().takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val active = jdbc.query(
            """
            SELECT 1 FROM organization_memberships
            WHERE organization_id = :org AND user_id = :user AND status = 'ACTIVE'
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", event.organizationId).addValue("user", assignee),
            RowMapper { rs, _ -> rs.getInt(1) },
        ).isNotEmpty()
        if (!active) return
        jdbc.update(
            """
            INSERT INTO notifications (
                organization_id, user_id, type, title, body, resource_type, resource_id
            ) VALUES (
                :org, :user, 'AUTOMATION', 'Você recebeu uma atualização',
                :body, :type, :resource
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("org", event.organizationId)
                .addValue("user", assignee)
                .addValue("body", "Evento " + event.triggerKey + " em " + event.resourceType)
                .addValue("type", event.resourceType)
                .addValue("resource", event.resourceId),
        )
    }

    private fun createFollowUpTask(event: PendingAutomationEvent, configuration: JsonNode) {
        val hours = configuration.path("dueInHours").asInt(24).coerceIn(1, 720)
        val configuredTitle = configuration.path("taskTitle").asText().take(180)
        val title = configuredTitle.ifBlank { "Acompanhar " + event.resourceType.lowercase() }
        val assignee = event.payload.path("assignedToUserId").asText().takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val activeAssignee = assignee?.takeIf {
            jdbc.query(
                """
                SELECT 1 FROM organization_memberships
                WHERE organization_id = :org AND user_id = :user AND status = 'ACTIVE'
                """.trimIndent(),
                MapSqlParameterSource().addValue("org", event.organizationId).addValue("user", it),
                RowMapper { rs, _ -> rs.getInt(1) },
            ).isNotEmpty()
        }
        jdbc.update(
            """
            INSERT INTO tasks (
                organization_id, title, status, priority, due_at, assigned_to,
                lead_id, property_id, created_by
            ) VALUES (
                :org, :title, 'OPEN', 'NORMAL', NOW() + (:hours * INTERVAL '1 hour'),
                :assignee, :lead, :property, :creator
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("org", event.organizationId)
                .addValue("title", title)
                .addValue("hours", hours)
                .addValue("assignee", activeAssignee)
                .addValue("lead", event.payload.path("leadId").asText().takeIf { it.isNotBlank() }?.let(UUID::fromString))
                .addValue("property", event.payload.path("propertyId").asText().takeIf { it.isNotBlank() }?.let(UUID::fromString))
                .addValue("creator", event.actorUserId),
        )
    }

    private val eventMapper = RowMapper<PendingAutomationEvent> { rs, _ ->
        PendingAutomationEvent(
            rs.getObject("id", UUID::class.java),
            rs.getObject("organization_id", UUID::class.java),
            rs.getString("trigger_key"),
            rs.getString("resource_type"),
            rs.getObject("resource_id", UUID::class.java),
            rs.getObject("actor_user_id", UUID::class.java),
            objectMapper.readTree(rs.getString("payload")),
        )
    }
}
