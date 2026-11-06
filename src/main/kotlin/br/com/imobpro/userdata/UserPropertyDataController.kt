package br.com.imobpro.userdata

import br.com.imobpro.auth.TenantPrincipal
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

data class FavoriteResponse(val propertyId: UUID, val createdAt: Instant)

data class SaveSearchRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    val criteria: JsonNode,
    val alertsEnabled: Boolean = false,
)

data class SavedSearchResponse(
    val id: UUID,
    val name: String,
    val criteria: JsonNode,
    val alertsEnabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Service
class UserPropertyDataService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun addFavorite(principal: TenantPrincipal, propertyId: UUID): FavoriteResponse {
        val changed = jdbc.update(
            """
            INSERT INTO favorites (organization_id, user_id, property_id)
            SELECT :org, :user, p.id FROM properties p
            WHERE p.id = :property AND p.organization_id = :org AND p.archived_at IS NULL
            ON CONFLICT (organization_id, user_id, property_id) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId).addValue("property", propertyId),
        )
        if (changed == 0) {
            val exists = jdbc.query(
                """
                SELECT 1 FROM properties
                WHERE id = :property AND organization_id = :org AND archived_at IS NULL
                """.trimIndent(),
                MapSqlParameterSource().addValue("org", principal.organizationId)
                    .addValue("property", propertyId),
            ) { rs, _ -> rs.getInt(1) }.isNotEmpty()
            if (!exists) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return jdbc.query(
            """
            SELECT property_id, created_at FROM favorites
            WHERE organization_id = :org AND user_id = :user AND property_id = :property
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId).addValue("property", propertyId),
            RowMapper { rs, _ ->
                FavoriteResponse(rs.getObject("property_id", UUID::class.java), rs.getTimestamp("created_at").toInstant())
            },
        ).single()
    }

    fun favorites(principal: TenantPrincipal): List<FavoriteResponse> =
        jdbc.query(
            """
            SELECT property_id, created_at FROM favorites
            WHERE organization_id = :org AND user_id = :user
            ORDER BY created_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
            RowMapper { rs, _ ->
                FavoriteResponse(rs.getObject("property_id", UUID::class.java), rs.getTimestamp("created_at").toInstant())
            },
        )

    @Transactional
    fun removeFavorite(principal: TenantPrincipal, propertyId: UUID) {
        jdbc.update(
            """
            DELETE FROM favorites
            WHERE organization_id = :org AND user_id = :user AND property_id = :property
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId).addValue("property", propertyId),
        )
    }

    @Transactional
    fun saveSearch(principal: TenantPrincipal, request: SaveSearchRequest): SavedSearchResponse {
        val id = UUID.randomUUID()
        val criteria = objectMapper.writeValueAsString(request.criteria)
        jdbc.update(
            """
            INSERT INTO saved_searches (id, organization_id, user_id, name, criteria, alerts_enabled)
            VALUES (:id, :org, :user, :name, CAST(:criteria AS jsonb), :alerts)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("user", principal.userId).addValue("name", request.name.trim())
                .addValue("criteria", criteria).addValue("alerts", request.alertsEnabled),
        )
        return getSavedSearch(principal, id)
    }

    fun savedSearches(principal: TenantPrincipal): List<SavedSearchResponse> =
        jdbc.query(
            """
            SELECT * FROM saved_searches
            WHERE organization_id = :org AND user_id = :user
            ORDER BY updated_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
            savedSearchMapper,
        )

    fun getSavedSearch(principal: TenantPrincipal, id: UUID): SavedSearchResponse =
        jdbc.query(
            """
            SELECT * FROM saved_searches
            WHERE id = :id AND organization_id = :org AND user_id = :user
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
            savedSearchMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun deleteSavedSearch(principal: TenantPrincipal, id: UUID) {
        jdbc.update(
            """
            DELETE FROM saved_searches
            WHERE id = :id AND organization_id = :org AND user_id = :user
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
        )
    }

    private val savedSearchMapper = RowMapper<SavedSearchResponse> { rs, _ ->
        SavedSearchResponse(
            rs.getObject("id", UUID::class.java), rs.getString("name"),
            objectMapper.readTree(rs.getString("criteria")), rs.getBoolean("alerts_enabled"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/me")
class UserPropertyDataController(private val service: UserPropertyDataService) {
    @GetMapping("/favorites")
    fun favorites(authentication: Authentication): List<FavoriteResponse> =
        service.favorites(authentication.tenant())

    @PostMapping("/favorites/{propertyId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun addFavorite(authentication: Authentication, @PathVariable propertyId: UUID): FavoriteResponse =
        service.addFavorite(authentication.tenant(), propertyId)

    @DeleteMapping("/favorites/{propertyId}")
    fun removeFavorite(authentication: Authentication, @PathVariable propertyId: UUID) =
        service.removeFavorite(authentication.tenant(), propertyId)

    @GetMapping("/saved-searches")
    fun savedSearches(authentication: Authentication): List<SavedSearchResponse> =
        service.savedSearches(authentication.tenant())

    @PostMapping("/saved-searches")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun saveSearch(
        authentication: Authentication,
        @Valid @RequestBody request: SaveSearchRequest,
    ): SavedSearchResponse = service.saveSearch(authentication.tenant(), request)

    @DeleteMapping("/saved-searches/{id}")
    fun deleteSavedSearch(authentication: Authentication, @PathVariable id: UUID) =
        service.deleteSavedSearch(authentication.tenant(), id)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
