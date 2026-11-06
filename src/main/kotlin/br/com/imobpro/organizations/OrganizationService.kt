package br.com.imobpro.organizations

import br.com.imobpro.auth.TenantPrincipal
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class OrganizationService(private val jdbc: JdbcTemplate) {
    fun current(principal: TenantPrincipal): OrganizationSummary =
        try {
            jdbc.queryForObject(
                """
                SELECT id, name, slug, status
                FROM organizations
                WHERE id = ?
                """.trimIndent(),
                { rs, _ ->
                    OrganizationSummary(
                        id = rs.getObject("id", UUID::class.java),
                        name = rs.getString("name"),
                        slug = rs.getString("slug"),
                        status = rs.getString("status"),
                    )
                },
                principal.organizationId,
            ) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        } catch (_: EmptyResultDataAccessException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }

    fun members(principal: TenantPrincipal): List<MemberSummary> =
        jdbc.query(
            """
            SELECT u.id, u.full_name, u.email, m.role, m.status, m.created_at
            FROM organization_memberships m
            JOIN users u ON u.id = m.user_id
            WHERE m.organization_id = ?
            ORDER BY m.created_at, u.full_name
            """.trimIndent(),
            { rs, _ ->
                MemberSummary(
                    userId = rs.getObject("id", UUID::class.java),
                    fullName = rs.getString("full_name"),
                    email = rs.getString("email"),
                    role = rs.getString("role"),
                    status = rs.getString("status"),
                    joinedAt = rs.getTimestamp("created_at").toInstant(),
                )
            },
            principal.organizationId,
        )

    @Transactional
    fun updateRole(
        principal: TenantPrincipal,
        targetUserId: UUID,
        newRole: String,
    ) {
        if (principal.userId == targetUserId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "You cannot change your own role")
        }
        if (principal.role !in setOf("OWNER", "ADMIN")) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
        val targetRole = jdbc.query(
            """
            SELECT role FROM organization_memberships
            WHERE organization_id = ? AND user_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("role") },
            principal.organizationId,
            targetUserId,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (targetRole == "OWNER" || newRole == "OWNER") {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Owner transfer requires a dedicated process")
        }
        if (principal.role == "ADMIN" && newRole == "ADMIN") {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only an owner can assign administrators")
        }
        jdbc.update(
            """
            UPDATE organization_memberships
            SET role = ?, updated_at = NOW()
            WHERE organization_id = ? AND user_id = ?
            """.trimIndent(),
            newRole,
            principal.organizationId,
            targetUserId,
        )
    }

    @Transactional
    fun removeMember(principal: TenantPrincipal, targetUserId: UUID) {
        if (principal.userId == targetUserId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "You cannot remove yourself")
        }
        if (principal.role !in setOf("OWNER", "ADMIN")) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
        val targetRole = jdbc.query(
            """
            SELECT role FROM organization_memberships
            WHERE organization_id = ? AND user_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("role") },
            principal.organizationId,
            targetUserId,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (targetRole == "OWNER") {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Owner cannot be removed")
        }
        jdbc.update(
            """
            UPDATE organization_memberships
            SET status = 'REMOVED', updated_at = NOW()
            WHERE organization_id = ? AND user_id = ?
            """.trimIndent(),
            principal.organizationId,
            targetUserId,
        )
        jdbc.update(
            """
            UPDATE auth_sessions SET revoked_at = NOW()
            WHERE user_id = ? AND organization_id = ? AND revoked_at IS NULL
            """.trimIndent(),
            targetUserId,
            principal.organizationId,
        )
    }
}
