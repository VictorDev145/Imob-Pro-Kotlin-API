package br.com.imobpro.publicportal

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class PublicLeadRequest(
    @field:NotBlank @field:Size(max = 160) val fullName: String,
    @field:NotBlank @field:Size(max = 40) val phone: String,
    @field:Email @field:Size(max = 254) val email: String? = null,
    @field:Size(max = 10000) val message: String? = null,
    val listingSlug: String? = null,
    val propertyId: UUID? = null,
)

data class PublicImage(
    val id: UUID,
    val contentType: String,
    val altText: String?,
    val position: Int,
    val contentUrl: String,
)

data class PublicListing(
    val id: UUID,
    val propertyId: UUID,
    val slug: String,
    val title: String,
    val description: String?,
    val propertyType: String,
    val purpose: String,
    val city: String,
    val state: String,
    val neighborhood: String?,
    val bedrooms: Int?,
    val bathrooms: Int?,
    val parkingSpaces: Int?,
    val builtArea: java.math.BigDecimal?,
    val landArea: java.math.BigDecimal?,
    val salePrice: java.math.BigDecimal?,
    val rentPrice: java.math.BigDecimal?,
    val publishedAt: Instant?,
    val images: List<PublicImage>,
)

data class PublicListingPage(val items: List<PublicListing>, val offset: Int, val limit: Int)

@Service
class PublicPortalService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${imobpro.storage.local.root:./var/imobpro/uploads}") root: String,
) {
    private val storageRoot = Path.of(root).toAbsolutePath().normalize()

    fun list(
        organizationSlug: String,
        offset: Int,
        limit: Int,
        purpose: String?,
        city: String?,
    ): PublicListingPage {
        validateOrganization(organizationSlug)
        val parameters = MapSqlParameterSource()
            .addValue("orgSlug", organizationSlug)
            .addValue("offset", offset)
            .addValue("limit", limit)
        val filters = mutableListOf<String>()
        if (!purpose.isNullOrBlank()) {
            filters.add("(p.purpose = :purpose OR p.purpose = 'BOTH')")
            parameters.addValue("purpose", purpose)
        }
        if (!city.isNullOrBlank()) {
            filters.add("LOWER(p.city) = LOWER(:city)")
            parameters.addValue("city", city.trim())
        }
        val extra = filters.joinToString(" AND ", if (filters.isEmpty()) "" else " AND ")
        val rows = jdbc.query(
            """
            SELECT l.id, p.id AS property_id, l.public_slug, l.title, l.description,
                   p.property_type, p.purpose, p.city, p.state, p.neighborhood,
                   p.bedrooms, p.bathrooms, p.parking_spaces, p.built_area, p.land_area,
                   p.sale_price, p.rent_price, l.published_at
            FROM listings l
            JOIN properties p ON p.id = l.property_id AND p.organization_id = l.organization_id
            JOIN organizations o ON o.id = l.organization_id
            WHERE o.slug = :orgSlug AND o.status = 'ACTIVE'
              AND l.status = 'PUBLISHED' AND l.channel = 'WEBSITE'
              AND p.status = 'ACTIVE' AND p.archived_at IS NULL
              $extra
            ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            parameters,
            listingMapper,
        )
        return PublicListingPage(rows.map { it.copy(images = images(organizationSlug, it.id)) }, offset, limit)
    }

    fun getByPropertyId(organizationSlug: String, propertyId: UUID): PublicListing {
        validateOrganization(organizationSlug)
        val row = jdbc.query(
            """
            SELECT l.id, l.public_slug, l.title, l.description,
                   p.property_type, p.purpose, p.city, p.state, p.neighborhood,
                   p.bedrooms, p.bathrooms, p.parking_spaces, p.built_area, p.land_area,
                   p.sale_price, p.rent_price, l.published_at
            FROM listings l
            JOIN properties p ON p.id = l.property_id AND p.organization_id = l.organization_id
            JOIN organizations o ON o.id = l.organization_id
            WHERE o.slug = :org AND l.property_id = :property AND o.status = 'ACTIVE'
              AND l.status = 'PUBLISHED' AND l.channel = 'WEBSITE'
              AND p.status = 'ACTIVE' AND p.archived_at IS NULL
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", organizationSlug).addValue("property", propertyId),
            listingMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return row.copy(images = images(organizationSlug, row.id))
    }

    fun get(organizationSlug: String, listingSlug: String): PublicListing {
        validateOrganization(organizationSlug)
        val row = jdbc.query(
            """
            SELECT l.id, l.public_slug, l.title, l.description,
                   p.property_type, p.purpose, p.city, p.state, p.neighborhood,
                   p.bedrooms, p.bathrooms, p.parking_spaces, p.built_area, p.land_area,
                   p.sale_price, p.rent_price, l.published_at
            FROM listings l
            JOIN properties p ON p.id = l.property_id AND p.organization_id = l.organization_id
            JOIN organizations o ON o.id = l.organization_id
            WHERE o.slug = :org AND l.public_slug = :slug AND o.status = 'ACTIVE'
              AND l.status = 'PUBLISHED' AND l.channel = 'WEBSITE'
              AND p.status = 'ACTIVE' AND p.archived_at IS NULL
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", organizationSlug).addValue("slug", listingSlug),
            listingMapper,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return row.copy(images = images(organizationSlug, row.id))
    }

    fun image(
        organizationSlug: String,
        listingSlug: String,
        imageId: UUID,
    ): Pair<Resource, String> {
        val row = jdbc.query(
            """
            SELECT pi.storage_key, pi.content_type
            FROM property_images pi
            JOIN properties p ON p.id = pi.property_id AND p.organization_id = pi.organization_id
            JOIN listings l ON l.property_id = p.id AND l.organization_id = p.organization_id
            JOIN organizations o ON o.id = l.organization_id
            WHERE o.slug = :org AND l.public_slug = :slug AND l.status = 'PUBLISHED'
              AND l.channel = 'WEBSITE' AND o.status = 'ACTIVE' AND p.status = 'ACTIVE'
              AND p.archived_at IS NULL AND pi.id = :image
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", organizationSlug)
                .addValue("slug", listingSlug).addValue("image", imageId),
            RowMapper { rs, _ -> rs.getString("storage_key") to rs.getString("content_type") },
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val target = storageRoot.resolve(row.first).normalize()
        if (!target.startsWith(storageRoot) || !Files.isRegularFile(target)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return FileSystemResource(target) to row.second
    }

    @Transactional
    fun createLead(organizationSlug: String, request: PublicLeadRequest) {
        val context = jdbc.query(
            """
            SELECT o.id AS organization_id, m.user_id AS owner_id, l.property_id
            FROM organizations o
            JOIN organization_memberships m
              ON m.organization_id = o.id AND m.role = 'OWNER' AND m.status = 'ACTIVE'
            LEFT JOIN listings l
              ON l.organization_id = o.id
             AND (
                 (:listingSlug IS NOT NULL AND l.public_slug = :listingSlug)
                 OR (:propertyId IS NOT NULL AND l.property_id = :propertyId)
             )
             AND l.status = 'PUBLISHED' AND l.channel = 'WEBSITE'
            WHERE o.slug = :orgSlug AND o.status = 'ACTIVE'
            ORDER BY m.created_at
            LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource().addValue("listingSlug", request.listingSlug)
                .addValue("propertyId", request.propertyId).addValue("orgSlug", organizationSlug),
            RowMapper { rs, _ ->
                Triple(
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getObject("owner_id", UUID::class.java),
                    rs.getObject("property_id", UUID::class.java),
                )
            },
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if ((request.listingSlug != null || request.propertyId != null) && context.third == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Published listing not found")
        }
        val leadId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO leads (
                id, organization_id, created_by, full_name, email, phone, source, status, message, property_id
            ) VALUES (:id, :org, :creator, :name, :email, :phone, 'WEBSITE', 'NEW', :message, :property)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", leadId)
                .addValue("org", context.first)
                .addValue("creator", context.second)
                .addValue("name", request.fullName.trim())
                .addValue("email", request.email?.trim()?.lowercase())
                .addValue("phone", request.phone.trim())
                .addValue("message", request.message)
                .addValue("property", context.third),
        )
        jdbc.update(
            """
            INSERT INTO automation_events (
                organization_id, trigger_key, resource_type, resource_id, actor_user_id, payload
            ) VALUES (
                :org, 'LEAD_CREATED', 'LEAD', :lead, :actor,
                jsonb_build_object('leadId', :lead, 'propertyId', CAST(:property AS UUID))
            )
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", context.first)
                .addValue("lead", leadId).addValue("actor", context.second)
                .addValue("property", context.third),
        )
        jdbc.update(
            """
            INSERT INTO notifications (organization_id, user_id, type, title, body, resource_type, resource_id)
            SELECT organization_id, user_id, 'LEAD_CREATED', 'Novo contato pelo site',
                   :body, 'LEAD', :lead
            FROM organization_memberships
            WHERE organization_id = :org AND role IN ('OWNER', 'ADMIN') AND status = 'ACTIVE'
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("body", "Novo contato de ${request.fullName.trim()}")
                .addValue("lead", leadId)
                .addValue("org", context.first),
        )
    }

    private fun images(organizationSlug: String, listingId: UUID): List<PublicImage> =
        jdbc.query(
            """
            SELECT pi.id, pi.content_type, pi.alt_text, pi.position, l.public_slug
            FROM property_images pi
            JOIN listings l ON l.property_id = pi.property_id AND l.organization_id = pi.organization_id
            JOIN organizations o ON o.id = l.organization_id
            WHERE o.slug = :org AND l.id = :listing AND l.status = 'PUBLISHED'
              AND l.channel = 'WEBSITE' AND o.status = 'ACTIVE'
            ORDER BY pi.position, pi.created_at
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", organizationSlug).addValue("listing", listingId),
            RowMapper { rs, _ ->
                val slug = rs.getString("public_slug")
                val id = rs.getObject("id", UUID::class.java)
                PublicImage(
                    id,
                    rs.getString("content_type"),
                    rs.getString("alt_text"),
                    rs.getInt("position"),
                    "/api/v1/public/$organizationSlug/listings/$slug/images/$id",
                )
            },
        )

    private fun validateOrganization(slug: String) {
        val exists = jdbc.query(
            "SELECT 1 FROM organizations WHERE slug = :slug AND status = 'ACTIVE'",
            MapSqlParameterSource().addValue("slug", slug),
            RowMapper { rs, _ -> rs.getInt(1) },
        ).isNotEmpty()
        if (!exists) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    private val listingMapper = RowMapper<PublicListing> { rs, _ ->
        PublicListing(
            rs.getObject("id", UUID::class.java),
            rs.getObject("property_id", UUID::class.java),
            rs.getString("public_slug"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("property_type"),
            rs.getString("purpose"),
            rs.getString("city"),
            rs.getString("state").trim(),
            rs.getString("neighborhood"),
            rs.getObject("bedrooms")?.let { (it as Number).toInt() },
            rs.getObject("bathrooms")?.let { (it as Number).toInt() },
            rs.getObject("parking_spaces")?.let { (it as Number).toInt() },
            rs.getBigDecimal("built_area"),
            rs.getBigDecimal("land_area"),
            rs.getBigDecimal("sale_price"),
            rs.getBigDecimal("rent_price"),
            rs.getTimestamp("published_at")?.toInstant(),
            emptyList(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/public/{organizationSlug}")
class PublicPortalController(private val service: PublicPortalService) {
    @GetMapping("/listings")
    fun list(
        @PathVariable organizationSlug: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "24") limit: Int,
        @RequestParam(required = false) purpose: String?,
        @RequestParam(required = false) city: String?,
    ): PublicListingPage {
        if (offset < 0 || limit !in 1..100) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        if (purpose != null && purpose !in setOf("SALE", "RENT", "BOTH")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        return service.list(organizationSlug, offset, limit, purpose, city)
    }

    @GetMapping("/listings/by-property/{propertyId}")
    fun getByProperty(
        @PathVariable organizationSlug: String,
        @PathVariable propertyId: UUID,
    ): PublicListing = service.getByPropertyId(organizationSlug, propertyId)

    @GetMapping("/listings/{listingSlug}")
    fun get(
        @PathVariable organizationSlug: String,
        @PathVariable listingSlug: String,
    ): PublicListing = service.get(organizationSlug, listingSlug)

    @GetMapping("/listings/{listingSlug}/images/{imageId}")
    fun image(
        @PathVariable organizationSlug: String,
        @PathVariable listingSlug: String,
        @PathVariable imageId: UUID,
    ): ResponseEntity<Resource> {
        val (resource, contentType) = service.image(organizationSlug, listingSlug, imageId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePublic())
            .body(resource)
    }

    @PostMapping("/leads")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun createLead(
        @PathVariable organizationSlug: String,
        @Valid @RequestBody request: PublicLeadRequest,
    ): Map<String, String> {
        service.createLead(organizationSlug, request)
        return mapOf("status" to "received")
    }
}
