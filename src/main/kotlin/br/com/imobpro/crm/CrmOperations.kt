package br.com.imobpro.crm

import br.com.imobpro.auth.TenantPrincipal
import br.com.imobpro.automations.AutomationEventWriter
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
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
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

data class CreateTaskRequest(
    @field:NotBlank @field:Size(max = 180) val title: String,
    @field:Size(max = 10000) val description: String? = null,
    @field:Pattern(regexp = "LOW|NORMAL|HIGH|URGENT") val priority: String = "NORMAL",
    val dueAt: Instant? = null,
    val assignedToUserId: UUID? = null,
    val leadId: UUID? = null,
    val propertyId: UUID? = null,
)

data class TaskStatusRequest(
    @field:Pattern(regexp = "OPEN|IN_PROGRESS|DONE|CANCELLED") val status: String,
)

data class TaskResponse(
    val id: UUID,
    val title: String,
    val description: String?,
    val status: String,
    val priority: String,
    val dueAt: Instant?,
    val assignedToUserId: UUID?,
    val leadId: UUID?,
    val propertyId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CreateVisitRequest(
    val propertyId: UUID,
    val leadId: UUID? = null,
    val assignedToUserId: UUID? = null,
    val scheduledAt: Instant,
    @field:Min(15) @field:Max(480) val durationMinutes: Int = 60,
    @field:Size(max = 10000) val notes: String? = null,
)

data class VisitStatusRequest(
    @field:Pattern(regexp = "SCHEDULED|CONFIRMED|COMPLETED|CANCELLED|NO_SHOW") val status: String,
)

data class VisitResponse(
    val id: UUID,
    val propertyId: UUID,
    val leadId: UUID?,
    val assignedToUserId: UUID?,
    val scheduledAt: Instant,
    val durationMinutes: Int,
    val status: String,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CrmPage<T>(val items: List<T>, val offset: Int, val limit: Int)

@Service
class CrmOperationsService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val automationEvents: AutomationEventWriter,
) {
    @Transactional
    fun createTask(principal: TenantPrincipal, request: CreateTaskRequest): TaskResponse {
        requireActiveAssignee(principal, request.assignedToUserId)
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO tasks (
                id, organization_id, title, description, priority, due_at,
                assigned_to, lead_id, property_id, created_by
            ) VALUES (
                :id, :org, :title, :description, :priority, :dueAt,
                :assignee, :lead, :property, :creator
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", principal.organizationId)
                .addValue("title", request.title.trim())
                .addValue("description", request.description)
                .addValue("priority", request.priority)
                .addValue("dueAt", request.dueAt?.let(java.sql.Timestamp::from))
                .addValue("assignee", request.assignedToUserId)
                .addValue("lead", request.leadId)
                .addValue("property", request.propertyId)
                .addValue("creator", principal.userId),
        )
        record(principal, "TASK_CREATED", "TASK", id)
        return getTask(principal, id)
    }

    fun listTasks(principal: TenantPrincipal, offset: Int, limit: Int): CrmPage<TaskResponse> {
        val items = jdbc.query(
            """
            SELECT * FROM tasks WHERE organization_id = :org
            ORDER BY CASE status WHEN 'OPEN' THEN 0 WHEN 'IN_PROGRESS' THEN 1 ELSE 2 END,
                due_at NULLS LAST, created_at DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("limit", limit).addValue("offset", offset),
            taskMapper,
        )
        return CrmPage(items, offset, limit)
    }

    fun getTask(principal: TenantPrincipal, id: UUID): TaskResponse =
        jdbc.query(
            "SELECT * FROM tasks WHERE id = :id AND organization_id = :org",
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId),
            taskMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun updateTaskStatus(principal: TenantPrincipal, id: UUID, status: String) {
        val changed = jdbc.update(
            """
            UPDATE tasks SET status = :status,
                completed_at = CASE WHEN :status = 'DONE' THEN NOW() ELSE NULL END,
                updated_at = NOW()
            WHERE id = :id AND organization_id = :org
            """.trimIndent(),
            MapSqlParameterSource().addValue("status", status).addValue("id", id)
                .addValue("org", principal.organizationId),
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        record(principal, "TASK_STATUS_CHANGED", "TASK", id)
    }

    @Transactional
    fun createVisit(principal: TenantPrincipal, request: CreateVisitRequest): VisitResponse {
        requireActiveAssignee(principal, request.assignedToUserId)
        if (!request.scheduledAt.isAfter(Instant.now().minusSeconds(60))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Visit must be scheduled in the future")
        }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO visits (
                id, organization_id, property_id, lead_id, assigned_to,
                scheduled_at, duration_minutes, notes, created_by
            ) VALUES (
                :id, :org, :property, :lead, :assignee,
                :scheduledAt, :duration, :notes, :creator
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", principal.organizationId)
                .addValue("property", request.propertyId)
                .addValue("lead", request.leadId)
                .addValue("assignee", request.assignedToUserId)
                .addValue("scheduledAt", java.sql.Timestamp.from(request.scheduledAt))
                .addValue("duration", request.durationMinutes)
                .addValue("notes", request.notes)
                .addValue("creator", principal.userId),
        )
        record(principal, "VISIT_SCHEDULED", "VISIT", id)
        automationEvents.enqueue(
            principal, "VISIT_SCHEDULED", "VISIT", id,
            mapOf("leadId" to request.leadId, "propertyId" to request.propertyId,
                "assignedToUserId" to request.assignedToUserId),
        )
        return getVisit(principal, id)
    }

    fun listVisits(principal: TenantPrincipal, offset: Int, limit: Int): CrmPage<VisitResponse> {
        val items = jdbc.query(
            """
            SELECT * FROM visits WHERE organization_id = :org
            ORDER BY scheduled_at DESC LIMIT :limit OFFSET :offset
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("limit", limit).addValue("offset", offset),
            visitMapper,
        )
        return CrmPage(items, offset, limit)
    }

    fun getVisit(principal: TenantPrincipal, id: UUID): VisitResponse =
        jdbc.query(
            "SELECT * FROM visits WHERE id = :id AND organization_id = :org",
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId),
            visitMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun updateVisitStatus(principal: TenantPrincipal, id: UUID, status: String) {
        val changed = jdbc.update(
            """
            UPDATE visits SET status = :status, updated_at = NOW()
            WHERE id = :id AND organization_id = :org
            """.trimIndent(),
            MapSqlParameterSource().addValue("status", status).addValue("id", id)
                .addValue("org", principal.organizationId),
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        record(principal, "VISIT_STATUS_CHANGED", "VISIT", id)
    }

    private fun requireActiveAssignee(principal: TenantPrincipal, assigneeId: UUID?) {
        if (assigneeId == null) return
        val active = jdbc.query(
            """
            SELECT 1 FROM organization_memberships
            WHERE organization_id = :org AND user_id = :user AND status = 'ACTIVE'
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", assigneeId),
        ) { rs, _ -> rs.getInt(1) }.isNotEmpty()
        if (!active) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee is not an active organization member")
        }
    }

    private fun record(principal: TenantPrincipal, action: String, type: String, id: UUID) {
        jdbc.update(
            """
            INSERT INTO activity_log (organization_id, actor_user_id, action, resource_type, resource_id)
            VALUES (:org, :actor, :action, :type, :id)
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("actor", principal.userId).addValue("action", action)
                .addValue("type", type).addValue("id", id),
        )
    }

    private val taskMapper = RowMapper<TaskResponse> { rs, _ ->
        TaskResponse(
            rs.getObject("id", UUID::class.java), rs.getString("title"), rs.getString("description"),
            rs.getString("status"), rs.getString("priority"),
            rs.getTimestamp("due_at")?.toInstant(),
            rs.getObject("assigned_to", UUID::class.java), rs.getObject("lead_id", UUID::class.java),
            rs.getObject("property_id", UUID::class.java), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private val visitMapper = RowMapper<VisitResponse> { rs, _ ->
        VisitResponse(
            rs.getObject("id", UUID::class.java), rs.getObject("property_id", UUID::class.java),
            rs.getObject("lead_id", UUID::class.java), rs.getObject("assigned_to", UUID::class.java),
            rs.getTimestamp("scheduled_at").toInstant(), rs.getInt("duration_minutes"),
            rs.getString("status"), rs.getString("notes"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        )
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/crm")
class CrmOperationsController(private val service: CrmOperationsService) {
    @GetMapping("/tasks")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    fun tasks(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
    ): CrmPage<TaskResponse> = service.listTasks(authentication.tenant(), offset, limit)

    @PostMapping("/tasks")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTask(
        authentication: Authentication,
        @Valid @RequestBody request: CreateTaskRequest,
    ): TaskResponse = service.createTask(authentication.tenant(), request)

    @PatchMapping("/tasks/{id}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateTask(
        authentication: Authentication,
        @PathVariable id: UUID,
        @Valid @RequestBody request: TaskStatusRequest,
    ) = service.updateTaskStatus(authentication.tenant(), id, request.status)

    @GetMapping("/visits")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    fun visits(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
    ): CrmPage<VisitResponse> = service.listVisits(authentication.tenant(), offset, limit)

    @PostMapping("/visits")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    @ResponseStatus(HttpStatus.CREATED)
    fun createVisit(
        authentication: Authentication,
        @Valid @RequestBody request: CreateVisitRequest,
    ): VisitResponse = service.createVisit(authentication.tenant(), request)

    @PatchMapping("/visits/{id}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateVisit(
        authentication: Authentication,
        @PathVariable id: UUID,
        @Valid @RequestBody request: VisitStatusRequest,
    ) = service.updateVisitStatus(authentication.tenant(), id, request.status)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
