package br.com.imobpro.userdata

import br.com.imobpro.auth.TenantPrincipal
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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateCollectionRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:Size(max = 1000) val description: String? = null,
)

data class AddCollectionItemRequest(
    val propertyId: UUID,
    @field:Size(max = 1000) val comment: String? = null,
)

data class CollectionItemResponse(
    val propertyId: UUID,
    val title: String,
    val city: String,
    val state: String,
    val salePrice: BigDecimal?,
    val rentPrice: BigDecimal?,
    val comment: String?,
    val createdAt: Instant,
)

data class CollectionResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val items: List<CollectionItemResponse>,
)

@Service
class CollectionService(private val jdbc: NamedParameterJdbcTemplate) {
    fun list(principal: TenantPrincipal): List<CollectionResponse> =
        jdbc.query(
            """
            SELECT id, name, description, created_at, updated_at
            FROM collections
            WHERE organization_id = :org AND user_id = :user
            ORDER BY created_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
            collectionMapper,
        ).map { it.copy(items = items(principal, it.id)) }

    @Transactional
    fun create(principal: TenantPrincipal, request: CreateCollectionRequest): CollectionResponse {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO collections (id, organization_id, user_id, name, description)
            VALUES (:id, :org, :user, :name, :description)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("user", principal.userId).addValue("name", request.name.trim())
                .addValue("description", request.description),
        )
        return get(principal, id)
    }

    fun get(principal: TenantPrincipal, id: UUID): CollectionResponse =
        jdbc.query(
            """
            SELECT id, name, description, created_at, updated_at
            FROM collections
            WHERE id = :id AND organization_id = :org AND user_id = :user
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("org", principal.organizationId)
                .addValue("user", principal.userId),
            collectionMapper,
        ).singleOrNull()?.let { it.copy(items = items(principal, id)) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun addItem(principal: TenantPrincipal, collectionId: UUID, request: AddCollectionItemRequest) {
        get(principal, collectionId)
        val inserted = jdbc.update(
            """
            INSERT INTO collection_items (organization_id, collection_id, property_id, comment)
            SELECT :org, c.id, p.id, :comment
            FROM collections c
            JOIN properties p ON p.organization_id = c.organization_id
            WHERE c.id = :collection AND c.organization_id = :org AND c.user_id = :user
              AND p.id = :property AND p.archived_at IS NULL AND p.status = 'ACTIVE'
              AND EXISTS (
                SELECT 1 FROM listings l
                WHERE l.organization_id = p.organization_id AND l.property_id = p.id
                  AND l.status = 'PUBLISHED' AND l.channel = 'WEBSITE'
              )
            ON CONFLICT (organization_id, collection_id, property_id)
            DO UPDATE SET comment = EXCLUDED.comment
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("collection", collectionId).addValue("user", principal.userId)
                .addValue("property", request.propertyId).addValue("comment", request.comment),
        )
        if (inserted == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Collection or public property not found")
    }

    @Transactional
    fun removeItem(principal: TenantPrincipal, collectionId: UUID, propertyId: UUID) {
        get(principal, collectionId)
        jdbc.update(
            """
            DELETE FROM collection_items ci
            USING collections c
            WHERE ci.organization_id = :org AND ci.collection_id = :collection
              AND ci.property_id = :property
              AND c.id = ci.collection_id AND c.organization_id = ci.organization_id
              AND c.user_id = :user
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("collection", collectionId).addValue("property", propertyId)
                .addValue("user", principal.userId),
        )
    }

    private fun items(principal: TenantPrincipal, collectionId: UUID): List<CollectionItemResponse> =
        jdbc.query(
            """
            SELECT ci.property_id, p.title, p.city, p.state, p.sale_price, p.rent_price,
                   ci.comment, ci.created_at
            FROM collection_items ci
            JOIN properties p ON p.id = ci.property_id AND p.organization_id = ci.organization_id
            WHERE ci.organization_id = :org AND ci.collection_id = :collection
            ORDER BY ci.created_at DESC
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("collection", collectionId),
            itemMapper,
        )

    private val collectionMapper = RowMapper<CollectionResponse> { rs, _ ->
        CollectionResponse(
            rs.getObject("id", UUID::class.java),
            rs.getString("name"),
            rs.getString("description"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            emptyList(),
        )
    }

    private val itemMapper = RowMapper<CollectionItemResponse> { rs, _ ->
        CollectionItemResponse(
            rs.getObject("property_id", UUID::class.java),
            rs.getString("title"),
            rs.getString("city"),
            rs.getString("state").trim(),
            rs.getBigDecimal("sale_price"),
            rs.getBigDecimal("rent_price"),
            rs.getString("comment"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/me/collections")
class CollectionController(private val service: CollectionService) {
    @GetMapping
    fun list(authentication: Authentication): List<CollectionResponse> =
        service.list(authentication.tenant())

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreateCollectionRequest,
    ): CollectionResponse = service.create(authentication.tenant(), request)

    @PostMapping("/{collectionId}/items")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun addItem(
        authentication: Authentication,
        @PathVariable collectionId: UUID,
        @Valid @RequestBody request: AddCollectionItemRequest,
    ) = service.addItem(authentication.tenant(), collectionId, request)

    @DeleteMapping("/{collectionId}/items/{propertyId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeItem(
        authentication: Authentication,
        @PathVariable collectionId: UUID,
        @PathVariable propertyId: UUID,
    ) = service.removeItem(authentication.tenant(), collectionId, propertyId)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
