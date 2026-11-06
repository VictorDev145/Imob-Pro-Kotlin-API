package br.com.imobpro.organizations

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

data class UpdateOrganizationSettingsRequest(val settings: Map<String, JsonNode>)

data class SaveSitePageRequest(
    @field:NotBlank @field:Pattern(regexp = "[a-z0-9-]{1,120}") val slug: String,
    @field:NotBlank @field:Size(max = 180) val title: String,
    @field:Size(max = 50000) val content: String,
    val published: Boolean = false,
)

data class SitePageResponse(
    val id: UUID,
    val slug: String,
    val title: String,
    val content: String,
    val published: Boolean,
    val updatedAt: Instant,
)

@Service
class OrganizationContentService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val editableKeys = setOf(
        "company_name", "phone", "email", "whatsapp", "instagram", "facebook", "linkedin",
        "hero_title", "hero_subtitle", "primary_color", "city", "state",
    )

    fun settings(principal: TenantPrincipal): Map<String, JsonNode> =
        jdbc.query(
            """
            SELECT setting_key, setting_value
            FROM organization_settings
            WHERE organization_id = :org
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId),
        ) { rs, _ -> rs.getString("setting_key") to objectMapper.readTree(rs.getString("setting_value")) }
            .toMap()

    @Transactional
    fun updateSettings(principal: TenantPrincipal, settings: Map<String, JsonNode>) {
        if (settings.isEmpty() || settings.size > editableKeys.size || settings.keys.any { it !in editableKeys }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported organization setting")
        }
        settings.forEach { (key, value) ->
            if (!value.isValueNode || value.asText().length > 1000) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Settings must be scalar values up to 1000 characters")
            }
            jdbc.update(
                """
                INSERT INTO organization_settings (
                    organization_id, setting_key, setting_value, updated_by
                ) VALUES (
                    :org, :key, CAST(:value AS jsonb), :user
                )
                ON CONFLICT (organization_id, setting_key)
                DO UPDATE SET setting_value = EXCLUDED.setting_value,
                              updated_by = EXCLUDED.updated_by,
                              updated_at = NOW()
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("org", principal.organizationId)
                    .addValue("key", key)
                    .addValue("value", objectMapper.writeValueAsString(value))
                    .addValue("user", principal.userId),
            )
        }
    }

    fun publicSettings(organizationSlug: String): Map<String, JsonNode> {
        val allowed = editableKeys
        return jdbc.query(
            """
            SELECT os.setting_key, os.setting_value
            FROM organization_settings os
            JOIN organizations o ON o.id = os.organization_id
            WHERE o.slug = :slug AND o.status = 'ACTIVE'
            """.trimIndent(),
            MapSqlParameterSource().addValue("slug", organizationSlug),
        ) { rs, _ -> rs.getString("setting_key") to objectMapper.readTree(rs.getString("setting_value")) }
            .filter { it.first in allowed }
            .toMap()
    }

    @Transactional
    fun savePage(principal: TenantPrincipal, request: SaveSitePageRequest): SitePageResponse {
        val id = jdbc.query(
            """
            SELECT id FROM site_pages
            WHERE organization_id = ? AND slug = ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            principal.organizationId,
            request.slug,
        ).singleOrNull() ?: UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO site_pages (
                id, organization_id, slug, title, content, published, updated_by
            ) VALUES (
                :id, :org, :slug, :title, :content, :published, :user
            )
            ON CONFLICT (organization_id, slug)
            DO UPDATE SET title = EXCLUDED.title, content = EXCLUDED.content,
                          published = EXCLUDED.published, updated_by = EXCLUDED.updated_by,
                          updated_at = NOW()
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", principal.organizationId)
                .addValue("slug", request.slug)
                .addValue("title", request.title.trim())
                .addValue("content", request.content)
                .addValue("published", request.published)
                .addValue("user", principal.userId),
        )
        return page(principal, request.slug)
    }

    fun pages(principal: TenantPrincipal): List<SitePageResponse> =
        jdbc.query(
            """
            SELECT id, slug, title, content, published, updated_at
            FROM site_pages WHERE organization_id = :org
            ORDER BY slug
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId),
            pageMapper,
        )

    fun page(principal: TenantPrincipal, slug: String): SitePageResponse =
        jdbc.query(
            """
            SELECT id, slug, title, content, published, updated_at
            FROM site_pages WHERE organization_id = :org AND slug = :slug
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId).addValue("slug", slug),
            pageMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    fun publicPage(organizationSlug: String, slug: String): SitePageResponse =
        jdbc.query(
            """
            SELECT sp.id, sp.slug, sp.title, sp.content, sp.published, sp.updated_at
            FROM site_pages sp
            JOIN organizations o ON o.id = sp.organization_id
            WHERE o.slug = :orgSlug AND o.status = 'ACTIVE'
              AND sp.slug = :slug AND sp.published = TRUE
            """.trimIndent(),
            MapSqlParameterSource().addValue("orgSlug", organizationSlug).addValue("slug", slug),
            pageMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private val pageMapper = RowMapper<SitePageResponse> { rs, _ ->
        SitePageResponse(
            rs.getObject("id", UUID::class.java),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("content"),
            rs.getBoolean("published"),
            rs.getTimestamp("updated_at").toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/organizations/current")
class OrganizationContentController(private val service: OrganizationContentService) {
    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    fun settings(authentication: Authentication): Map<String, JsonNode> =
        service.settings(authentication.tenant())

    @PatchMapping("/settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    fun updateSettings(
        authentication: Authentication,
        @Valid @RequestBody request: UpdateOrganizationSettingsRequest,
    ): Map<String, String> {
        service.updateSettings(authentication.tenant(), request.settings)
        return mapOf("status" to "updated")
    }

    @GetMapping("/site-pages")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    fun pages(authentication: Authentication): List<SitePageResponse> =
        service.pages(authentication.tenant())

    @PostMapping("/site-pages")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun savePage(
        authentication: Authentication,
        @Valid @RequestBody request: SaveSitePageRequest,
    ): SitePageResponse = service.savePage(authentication.tenant(), request)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}

@RestController
@RequestMapping("/api/v1/public/{organizationSlug}")
class PublicOrganizationContentController(private val service: OrganizationContentService) {
    @GetMapping("/settings")
    fun settings(@PathVariable organizationSlug: String): Map<String, JsonNode> =
        service.publicSettings(organizationSlug)

    @GetMapping("/pages/{slug}")
    fun page(
        @PathVariable organizationSlug: String,
        @PathVariable slug: String,
    ): SitePageResponse = service.publicPage(organizationSlug, slug)
}
