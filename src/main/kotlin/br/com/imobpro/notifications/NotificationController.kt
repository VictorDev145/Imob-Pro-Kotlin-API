package br.com.imobpro.notifications

import br.com.imobpro.auth.TenantPrincipal
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

data class NotificationResponse(
    val id: UUID,
    val type: String,
    val title: String,
    val body: String?,
    val resourceType: String?,
    val resourceId: UUID?,
    val readAt: Instant?,
    val createdAt: Instant,
)

data class NotificationInbox(val items: List<NotificationResponse>, val unreadCount: Int)

@Service
class NotificationService(private val jdbc: NamedParameterJdbcTemplate) {
    fun inbox(principal: TenantPrincipal, limit: Int): NotificationInbox {
        val parameters = MapSqlParameterSource()
            .addValue("org", principal.organizationId)
            .addValue("user", principal.userId)
            .addValue("limit", limit)
        val items = jdbc.query(
            """
            SELECT * FROM notifications
            WHERE organization_id = :org AND user_id = :user
            ORDER BY created_at DESC LIMIT :limit
            """.trimIndent(),
            parameters,
            mapper,
        )
        val unread = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM notifications
            WHERE organization_id = :org AND user_id = :user AND read_at IS NULL
            """.trimIndent(),
            parameters,
            Long::class.java,
        ) ?: 0L
        return NotificationInbox(items, unread.toInt())
    }

    @Transactional
    fun markRead(principal: TenantPrincipal, id: UUID) {
        val changed = jdbc.update(
            """
            UPDATE notifications SET read_at = COALESCE(read_at, NOW())
            WHERE id = :id AND organization_id = :org AND user_id = :user
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id)
                .addValue("org", principal.organizationId).addValue("user", principal.userId),
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @Transactional
    fun markAllRead(principal: TenantPrincipal) {
        jdbc.update(
            """
            UPDATE notifications SET read_at = NOW()
            WHERE organization_id = :org AND user_id = :user AND read_at IS NULL
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
        )
    }

    private val mapper = RowMapper<NotificationResponse> { rs, _ ->
        NotificationResponse(
            rs.getObject("id", UUID::class.java),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("body"),
            rs.getString("resource_type"),
            rs.getObject("resource_id", UUID::class.java),
            rs.getTimestamp("read_at")?.toInstant(),
            rs.getTimestamp("created_at").toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val service: NotificationService) {
    @GetMapping
    fun inbox(
        authentication: Authentication,
        @RequestParam(defaultValue = "50") limit: Int,
    ): NotificationInbox {
        if (limit !in 1..100) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid limit")
        return service.inbox(authentication.tenant(), limit)
    }

    @PatchMapping("/{id}/read")
    fun markRead(authentication: Authentication, @PathVariable id: UUID) =
        service.markRead(authentication.tenant(), id)

    @PatchMapping("/read-all")
    fun markAllRead(authentication: Authentication) = service.markAllRead(authentication.tenant())

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
