package br.com.imobpro.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.UUID

@Component
class AccessTokenFilter(
    private val jdbc: JdbcTemplate,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
        if (!token.isNullOrBlank() && SecurityContextHolder.getContext().authentication == null) {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val principal = jdbc.query(
                """
                SELECT s.user_id, s.organization_id, m.role
                FROM auth_sessions s
                JOIN users u ON u.id = s.user_id
                JOIN organization_memberships m
                  ON m.user_id = s.user_id AND m.organization_id = s.organization_id
                JOIN organizations o ON o.id = s.organization_id
                WHERE s.access_token_hash = ?
                  AND s.revoked_at IS NULL
                  AND s.access_expires_at > NOW()
                  AND u.status = 'ACTIVE'
                  AND m.status = 'ACTIVE'
                  AND o.status = 'ACTIVE'
                """.trimIndent(),
                { rs, _ ->
                    TenantPrincipal(
                        userId = rs.getObject("user_id", UUID::class.java),
                        organizationId = rs.getObject("organization_id", UUID::class.java),
                        role = rs.getString("role"),
                    )
                },
                hash,
            ).singleOrNull()
            if (principal != null) {
                val authorities = listOf(SimpleGrantedAuthority("ROLE_${principal.role}"))
                val authentication = UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    authorities,
                )
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
