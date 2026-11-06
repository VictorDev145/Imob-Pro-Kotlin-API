package br.com.imobpro.listings

import br.com.imobpro.auth.TenantPrincipal
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

data class CreateListingRequest(
    val propertyId: UUID,
    @field:NotBlank @field:Size(max = 180) val title: String,
    @field:Size(max = 20000) val description: String? = null,
    @field:Pattern(regexp = "WEBSITE|PORTAL|SOCIAL|INTERNAL") val channel: String = "WEBSITE",
)

data class ListingStatusRequest(
    @field:Pattern(regexp = "DRAFT|PUBLISHED|PAUSED|ARCHIVED") val status: String,
)

data class ListingResponse(
    val id: UUID,
    val propertyId: UUID,
    val title: String,
    val description: String?,
    val channel: String,
    val status: String,
    val publicSlug: String?,
    val publishedAt: Instant?,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Service
class ListingService(private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional
    fun create(principal: TenantPrincipal, request: CreateListingRequest): ListingResponse {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO listings (
                id, organization_id, property_id, title, description, channel, public_slug, created_by
            )
            SELECT :id, :org, p.id, :title, :description, :channel, :slug, :creator
            FROM properties p
            WHERE p.id = :property AND p.organization_id = :org AND p.archived_at IS NULL
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", principal.organizationId)
                .addValue("property", request.propertyId)
                .addValue("title", request.title.trim())
                .addValue("description", request.description)
                .addValue("channel", request.channel)
                .addValue("slug", "${request.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(140)}-${id.toString().take(8)}")
                .addValue("creator", principal.userId),
        ).also { if (it == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Property not found") }
        return get(principal, id)
    }

    fun list(principal: TenantPrincipal, offset: Int, limit: Int): List<ListingResponse> =
        jdbc.query(
            """
            SELECT * FROM listings WHERE organization_id = :org
            ORDER BY updated_at DESC LIMIT :limit OFFSET :offset
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("limit", limit).addValue("offset", offset),
            mapper,
        )

    fun get(principal: TenantPrincipal, id: UUID): ListingResponse =
        jdbc.query(
            "SELECT * FROM listings WHERE id = :id AND organization_id = :org",
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId),
            mapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun updateStatus(principal: TenantPrincipal, id: UUID, status: String) {
        val changed = jdbc.update(
            """
            UPDATE listings
            SET status = :status,
                published_at = CASE WHEN :status = 'PUBLISHED' THEN COALESCE(published_at, NOW()) ELSE published_at END,
                public_slug = CASE WHEN :status = 'PUBLISHED' THEN COALESCE(public_slug, id::text) ELSE public_slug END,
                updated_at = NOW()
            WHERE id = :id AND organization_id = :org
            """.trimIndent(),
            MapSqlParameterSource().addValue("status", status).addValue("id", id)
                .addValue("org", principal.organizationId),
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    private val mapper = RowMapper<ListingResponse> { rs, _ ->
        ListingResponse(
            rs.getObject("id", UUID::class.java),
            rs.getObject("property_id", UUID::class.java),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("channel"),
            rs.getString("status"),
            rs.getString("public_slug"),
            rs.getTimestamp("published_at")?.toInstant(),
            rs.getTimestamp("expires_at")?.toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
        )
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/listings")
class ListingController(private val service: ListingService) {
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    fun list(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<ListingResponse> {
        if (offset < 0 || limit !in 1..100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination")
        }
        return service.list(authentication.tenant(), offset, limit)
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreateListingRequest,
    ): ListingResponse = service.create(authentication.tenant(), request)

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateStatus(
        authentication: Authentication,
        @PathVariable id: UUID,
        @Valid @RequestBody request: ListingStatusRequest,
    ) = service.updateStatus(authentication.tenant(), id, request.status)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
