package br.com.imobpro.messaging

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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

data class CreateConversationRequest(
    @field:NotBlank @field:Size(max = 180) val subject: String,
    val leadId: UUID? = null,
    val participantUserIds: List<UUID> = emptyList(),
)

data class SendMessageRequest(
    @field:NotBlank @field:Size(max = 20000) val body: String,
)

data class ConversationResponse(
    val id: UUID,
    val subject: String,
    val status: String,
    val leadId: UUID?,
    val updatedAt: Instant,
)

data class MessageResponse(
    val id: UUID,
    val senderUserId: UUID,
    val body: String,
    val createdAt: Instant,
    val editedAt: Instant?,
)

data class ConversationContact(val userId: UUID, val fullName: String, val role: String)

data class AdminMessageResponse(
    val id: UUID,
    val conversationId: UUID,
    val senderUserId: UUID,
    val senderName: String,
    val body: String,
    val createdAt: Instant,
)

@Service
class MessagingService(private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional
    fun create(principal: TenantPrincipal, request: CreateConversationRequest): ConversationResponse {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO conversations (id, organization_id, lead_id, subject, created_by)
            VALUES (:id, :org, :lead, :subject, :creator)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("lead", request.leadId).addValue("subject", request.subject.trim())
                .addValue("creator", principal.userId),
        )
        val participantIds = (request.participantUserIds + principal.userId).distinct()
        if (participantIds.size > 21) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A conversation can have at most 20 invited participants")
        }
        val activeParticipants = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM organization_memberships
            WHERE organization_id = :org AND user_id IN (:users) AND status = 'ACTIVE'
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId).addValue("users", participantIds),
            Long::class.java,
        ) ?: 0L
        if (activeParticipants != participantIds.size.toLong()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "One or more participants are not active members of this organization")
        }
        participantIds.forEach { participantId ->
            jdbc.update(
                """
                INSERT INTO conversation_members (organization_id, conversation_id, user_id)
                VALUES (:org, :conversation, :user)
                """.trimIndent(),
                MapSqlParameterSource().addValue("org", principal.organizationId)
                    .addValue("conversation", id).addValue("user", participantId),
            )
        }
        return get(principal, id)
    }

    fun list(principal: TenantPrincipal, withUserId: UUID? = null): List<ConversationResponse> =
        jdbc.query(
            """
            SELECT c.id, c.subject, c.status, c.lead_id, c.updated_at
            FROM conversations c
            JOIN conversation_members cm
              ON cm.conversation_id = c.id AND cm.organization_id = c.organization_id
            WHERE c.organization_id = :org AND cm.user_id = :user
              AND (CAST(:withUserId AS UUID) IS NULL OR EXISTS (
                  SELECT 1 FROM conversation_members other
                  WHERE other.organization_id = c.organization_id
                    AND other.conversation_id = c.id AND other.user_id = CAST(:withUserId AS UUID)
              ))
            ORDER BY c.updated_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId).addValue("withUserId", withUserId),
            conversationMapper,
        )

    fun contacts(principal: TenantPrincipal): List<ConversationContact> =
        jdbc.query(
            """
            SELECT u.id, u.full_name, m.role
            FROM organization_memberships m
            JOIN users u ON u.id = m.user_id
            WHERE m.organization_id = :org AND m.status = 'ACTIVE'
              AND u.status = 'ACTIVE' AND u.id <> :user
            ORDER BY CASE m.role WHEN 'AGENT' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END, u.full_name
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId).addValue("user", principal.userId),
            RowMapper { rs, _ ->
                ConversationContact(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("full_name"),
                    rs.getString("role"),
                )
            },
        )

    fun unreadCount(principal: TenantPrincipal): Long =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM messages msg
            JOIN conversation_members cm
              ON cm.organization_id = msg.organization_id
             AND cm.conversation_id = msg.conversation_id
            WHERE msg.organization_id = :org AND cm.user_id = :user
              AND msg.sender_user_id <> :user AND msg.deleted_at IS NULL
              AND msg.created_at > COALESCE(cm.last_read_at, cm.joined_at)
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId).addValue("user", principal.userId),
            Long::class.java,
        ) ?: 0L

    fun adminMessages(principal: TenantPrincipal, limit: Int): List<AdminMessageResponse> =
        jdbc.query(
            """
            SELECT msg.id, msg.conversation_id, msg.sender_user_id,
                   u.full_name AS sender_name, msg.body, msg.created_at
            FROM messages msg
            JOIN users u ON u.id = msg.sender_user_id
            WHERE msg.organization_id = :org AND msg.deleted_at IS NULL
            ORDER BY msg.created_at DESC LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId).addValue("limit", limit),
            RowMapper { rs, _ ->
                AdminMessageResponse(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("conversation_id", UUID::class.java),
                    rs.getObject("sender_user_id", UUID::class.java),
                    rs.getString("sender_name"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            },
        )

    fun get(principal: TenantPrincipal, id: UUID): ConversationResponse =
        jdbc.query(
            """
            SELECT c.id, c.subject, c.status, c.lead_id, c.updated_at
            FROM conversations c
            JOIN conversation_members cm
              ON cm.conversation_id = c.id AND cm.organization_id = c.organization_id
            WHERE c.id = :id AND c.organization_id = :org AND cm.user_id = :user
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
            conversationMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun send(principal: TenantPrincipal, id: UUID, request: SendMessageRequest): MessageResponse {
        get(principal, id)
        val messageId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO messages (id, organization_id, conversation_id, sender_user_id, body)
            VALUES (:id, :org, :conversation, :sender, :body)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", messageId)
                .addValue("org", principal.organizationId).addValue("conversation", id)
                .addValue("sender", principal.userId).addValue("body", request.body.trim()),
        )
        jdbc.update(
            "UPDATE conversations SET updated_at = NOW() WHERE id = :id AND organization_id = :org",
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId),
        )
        return message(principal, id, messageId)
    }

    fun messages(principal: TenantPrincipal, id: UUID): List<MessageResponse> {
        get(principal, id)
        return jdbc.query(
            """
            SELECT id, sender_user_id, body, created_at, edited_at
            FROM messages
            WHERE organization_id = :org AND conversation_id = :conversation AND deleted_at IS NULL
            ORDER BY created_at
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("conversation", id),
            messageMapper,
        )
    }

    @Transactional
    fun markRead(principal: TenantPrincipal, id: UUID) {
        get(principal, id)
        jdbc.update(
            """
            UPDATE conversation_members SET last_read_at = NOW()
            WHERE organization_id = :org AND conversation_id = :conversation AND user_id = :user
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("conversation", id).addValue("user", principal.userId),
        )
    }

    private fun message(principal: TenantPrincipal, conversationId: UUID, messageId: UUID): MessageResponse =
        jdbc.query(
            """
            SELECT id, sender_user_id, body, created_at, edited_at
            FROM messages
            WHERE id = :id AND organization_id = :org AND conversation_id = :conversation
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", messageId)
                .addValue("org", principal.organizationId).addValue("conversation", conversationId),
            messageMapper,
        ).single()

    private val conversationMapper = RowMapper<ConversationResponse> { rs, _ ->
        ConversationResponse(
            rs.getObject("id", UUID::class.java), rs.getString("subject"), rs.getString("status"),
            rs.getObject("lead_id", UUID::class.java), rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private val messageMapper = RowMapper<MessageResponse> { rs, _ ->
        MessageResponse(
            rs.getObject("id", UUID::class.java), rs.getObject("sender_user_id", UUID::class.java),
            rs.getString("body"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("edited_at")?.toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/conversations")
class MessagingController(private val service: MessagingService) {
    @GetMapping("/contacts")
    fun contacts(authentication: Authentication): List<ConversationContact> =
        service.contacts(authentication.tenant())

    @GetMapping("/unread")
    fun unread(authentication: Authentication): Map<String, Long> =
        mapOf("count" to service.unreadCount(authentication.tenant()))

    @GetMapping("/admin/messages")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    fun adminMessages(
        authentication: Authentication,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "200") limit: Int,
    ): List<AdminMessageResponse> {
        if (limit !in 1..200) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        return service.adminMessages(authentication.tenant(), limit)
    }

    @GetMapping
    fun list(
        authentication: Authentication,
        @org.springframework.web.bind.annotation.RequestParam(required = false) withUserId: UUID?,
    ): List<ConversationResponse> = service.list(authentication.tenant(), withUserId)

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreateConversationRequest,
    ): ConversationResponse = service.create(authentication.tenant(), request)

    @GetMapping("/{id}/messages")
    fun messages(authentication: Authentication, @PathVariable id: UUID): List<MessageResponse> =
        service.messages(authentication.tenant(), id)

    @PostMapping("/{id}/messages")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun send(
        authentication: Authentication,
        @PathVariable id: UUID,
        @Valid @RequestBody request: SendMessageRequest,
    ): MessageResponse = service.send(authentication.tenant(), id, request)

    @PostMapping("/{id}/read")
    fun markRead(authentication: Authentication, @PathVariable id: UUID) =
        service.markRead(authentication.tenant(), id)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
