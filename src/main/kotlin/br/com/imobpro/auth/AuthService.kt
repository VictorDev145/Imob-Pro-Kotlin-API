package br.com.imobpro.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class AuthService(
    private val jdbc: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
) {
    private val random = SecureRandom()

    @Transactional
    fun register(request: RegisterRequest): TokenResponse {
        if (request.password.toByteArray(Charsets.UTF_8).size > 72) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds BCrypt's 72-byte limit")
        }
        val email = request.email.trim().lowercase()
        val exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users WHERE email = ?)",
            Boolean::class.java,
            email,
        )
        if (exists == true) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email already registered")
        }

        val organizationId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val baseSlug = request.organizationName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
            .ifBlank { "organization" }
        val slug = "$baseSlug-${organizationId.toString().take(8)}"

        jdbc.update(
            "INSERT INTO organizations(id, name, slug) VALUES (?, ?, ?)",
            organizationId,
            request.organizationName.trim(),
            slug,
        )
        jdbc.update(
            """
            INSERT INTO users(id, email, password_hash, full_name, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            userId,
            email,
            passwordEncoder.encode(request.password),
            request.fullName.trim(),
        )
        jdbc.update(
            """
            INSERT INTO organization_memberships(organization_id, user_id, role, status)
            VALUES (?, ?, 'OWNER', 'ACTIVE')
            """.trimIndent(),
            organizationId,
            userId,
        )
        return createSession(userId, organizationId)
    }

    @Transactional(readOnly = true)
    fun organizations(request: LoginRequest): List<OrganizationChoice> {
        val userId = authenticateCredentials(request)
        return jdbc.query(
            """
            SELECT o.id, o.name, o.slug, m.role
            FROM organization_memberships m
            JOIN organizations o ON o.id = m.organization_id
            WHERE m.user_id = ?
              AND m.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
            ORDER BY o.name
            """.trimIndent(),
            { rs, _ ->
                OrganizationChoice(
                    organizationId = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    slug = rs.getString("slug"),
                    role = rs.getString("role"),
                )
            },
            userId,
        )
    }

    @Transactional
    fun login(request: LoginRequest): TokenResponse {
        val userId = authenticateCredentials(request)
        val organizationId = if (request.organizationId == null) {
            jdbc.query(
                """
                SELECT m.organization_id
                FROM organization_memberships m
                JOIN organizations o ON o.id = m.organization_id
                WHERE m.user_id = ?
                  AND m.status = 'ACTIVE'
                  AND o.status = 'ACTIVE'
                ORDER BY m.created_at
                LIMIT 2
                """.trimIndent(),
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                userId,
            ).singleOrNull()
                ?: throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Select an active organization",
                )
        } else {
            jdbc.query(
                """
                SELECT m.organization_id
                FROM organization_memberships m
                JOIN organizations o ON o.id = m.organization_id
                WHERE m.user_id = ?
                  AND m.organization_id = ?
                  AND m.status = 'ACTIVE'
                  AND o.status = 'ACTIVE'
                """.trimIndent(),
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                userId,
                request.organizationId,
            ).singleOrNull()
                ?: throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Organization membership not found",
                )
        }
        return createSession(userId, organizationId)
    }

    @Transactional
    fun refresh(refreshToken: String): TokenResponse {
        val hash = digest(refreshToken)
        val row = try {
            jdbc.queryForMap(
                """
                SELECT s.user_id, s.organization_id
                FROM auth_sessions s
                JOIN users u ON u.id = s.user_id
                JOIN organization_memberships m
                  ON m.user_id = s.user_id AND m.organization_id = s.organization_id
                JOIN organizations o ON o.id = s.organization_id
                WHERE s.refresh_token_hash = ?
                  AND s.revoked_at IS NULL
                  AND s.refresh_expires_at > NOW()
                  AND u.status = 'ACTIVE'
                  AND m.status = 'ACTIVE'
                  AND o.status = 'ACTIVE'
                FOR UPDATE OF s
                """.trimIndent(),
                hash,
            )
        } catch (_: EmptyResultDataAccessException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
        }
        val userId = row["user_id"] as UUID
        val organizationId = row["organization_id"] as UUID
        jdbc.update(
            "UPDATE auth_sessions SET revoked_at = NOW() WHERE refresh_token_hash = ?",
            hash,
        )
        return createSession(userId, organizationId)
    }

    @Transactional
    fun logout(accessToken: String) {
        jdbc.update(
            """
            UPDATE auth_sessions
            SET revoked_at = NOW()
            WHERE access_token_hash = ? AND revoked_at IS NULL
            """.trimIndent(),
            digest(accessToken),
        )
    }

    fun currentUser(userId: UUID, organizationId: UUID): AuthenticatedUser =
        try {
            jdbc.queryForObject(
                """
                SELECT u.id, m.organization_id, u.email, u.full_name, m.role
                FROM users u
                JOIN organization_memberships m ON m.user_id = u.id
                WHERE u.id = ?
                  AND m.organization_id = ?
                  AND u.status = 'ACTIVE'
                  AND m.status = 'ACTIVE'
                """.trimIndent(),
                { rs, _ ->
                    AuthenticatedUser(
                        userId = rs.getObject("id", UUID::class.java),
                        organizationId = rs.getObject("organization_id", UUID::class.java),
                        email = rs.getString("email"),
                        fullName = rs.getString("full_name"),
                        role = rs.getString("role"),
                    )
                },
                userId,
                organizationId,
            ) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        } catch (_: EmptyResultDataAccessException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }

    private fun authenticateCredentials(request: LoginRequest): UUID {
        val email = request.email.trim().lowercase()
        val user = try {
            jdbc.queryForMap(
                "SELECT id, password_hash, status FROM users WHERE email = ?",
                email,
            )
        } catch (_: EmptyResultDataAccessException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        if (
            user["status"] != "ACTIVE" ||
            !passwordEncoder.matches(request.password, user["password_hash"].toString())
        ) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        return user["id"] as UUID
    }

    private fun createSession(userId: UUID, organizationId: UUID): TokenResponse {
        val access = randomToken()
        val refresh = randomToken()
        val accessExpiry = Instant.now().plus(15, ChronoUnit.MINUTES)
        val refreshExpiry = Instant.now().plus(30, ChronoUnit.DAYS)
        jdbc.update(
            """
            INSERT INTO auth_sessions(
                user_id, organization_id, access_token_hash, refresh_token_hash,
                access_expires_at, refresh_expires_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            userId,
            organizationId,
            digest(access),
            digest(refresh),
            accessExpiry,
            refreshExpiry,
        )
        return TokenResponse(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = accessExpiry,
            refreshExpiresAt = refreshExpiry,
        )
    }

    private fun randomToken(): String =
        ByteArray(48)
            .also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
