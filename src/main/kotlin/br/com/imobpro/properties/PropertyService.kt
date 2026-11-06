package br.com.imobpro.properties

import br.com.imobpro.auth.TenantPrincipal
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class PropertyService(private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional
    fun create(principal: TenantPrincipal, request: SavePropertyRequest): PropertyResponse {
        val id = UUID.randomUUID()
        val reference = "IMB-${id.toString().take(8).uppercase()}"
        val parameters = parameters(principal, request)
            .addValue("id", id)
            .addValue("reference", reference)
        jdbc.update(
            """
            INSERT INTO properties (
                id, organization_id, created_by, reference_code, property_type, purpose,
                title, description, street, street_number, complement, neighborhood,
                city, state, postal_code, bedrooms, bathrooms, parking_spaces,
                built_area, land_area, sale_price, rent_price
            ) VALUES (
                :id, :organizationId, :createdBy, :reference, :propertyType, :purpose,
                :title, :description, :street, :streetNumber, :complement, :neighborhood,
                :city, :state, :postalCode, :bedrooms, :bathrooms, :parkingSpaces,
                :builtArea, :landArea, :salePrice, :rentPrice
            )
            """.trimIndent(),
            parameters,
        )
        return get(principal, id)
    }

    fun list(
        principal: TenantPrincipal,
        offset: Int,
        limit: Int,
        city: String?,
        status: String?,
    ): PropertyPage {
        val parameters = MapSqlParameterSource()
            .addValue("organizationId", principal.organizationId)
            .addValue("offset", offset)
            .addValue("limit", limit)
        val filters = mutableListOf<String>()
        if (!city.isNullOrBlank()) {
            filters.add("LOWER(city) = LOWER(:city)")
            parameters.addValue("city", city.trim())
        }
        if (!status.isNullOrBlank()) {
            filters.add("status = :status")
            parameters.addValue("status", status)
        }
        val filterSql = filters.joinToString(separator = " AND ", prefix = if (filters.isEmpty()) "" else " AND ")
        val items = jdbc.query(
            """
            SELECT * FROM properties
            WHERE organization_id = :organizationId
              AND archived_at IS NULL
              $filterSql
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            parameters,
            propertyRowMapper(),
        )
        return PropertyPage(items, offset, limit)
    }

    fun get(principal: TenantPrincipal, propertyId: UUID): PropertyResponse =
        jdbc.query(
            """
            SELECT * FROM properties
            WHERE id = :id AND organization_id = :organizationId AND archived_at IS NULL
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", propertyId)
                .addValue("organizationId", principal.organizationId),
            propertyRowMapper(),
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @Transactional
    fun update(
        principal: TenantPrincipal,
        propertyId: UUID,
        request: SavePropertyRequest,
    ): PropertyResponse {
        val parameters = parameters(principal, request)
            .addValue("id", propertyId)
        val updated = jdbc.update(
            """
            UPDATE properties SET
                property_type = :propertyType,
                purpose = :purpose,
                title = :title,
                description = :description,
                street = :street,
                street_number = :streetNumber,
                complement = :complement,
                neighborhood = :neighborhood,
                city = :city,
                state = :state,
                postal_code = :postalCode,
                bedrooms = :bedrooms,
                bathrooms = :bathrooms,
                parking_spaces = :parkingSpaces,
                built_area = :builtArea,
                land_area = :landArea,
                sale_price = :salePrice,
                rent_price = :rentPrice,
                updated_at = NOW()
            WHERE id = :id AND organization_id = :organizationId AND archived_at IS NULL
            """.trimIndent(),
            parameters,
        )
        if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return get(principal, propertyId)
    }

    @Transactional
    fun archive(principal: TenantPrincipal, propertyId: UUID) {
        val updated = jdbc.update(
            """
            UPDATE properties
            SET status = 'ARCHIVED', archived_at = NOW(), updated_at = NOW()
            WHERE id = :id AND organization_id = :organizationId AND archived_at IS NULL
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", propertyId)
                .addValue("organizationId", principal.organizationId),
        )
        if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    private fun parameters(
        principal: TenantPrincipal,
        request: SavePropertyRequest,
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("organizationId", principal.organizationId)
        .addValue("createdBy", principal.userId)
        .addValue("propertyType", request.propertyType)
        .addValue("purpose", request.purpose)
        .addValue("title", request.title.trim())
        .addValue("description", request.description)
        .addValue("street", request.street)
        .addValue("streetNumber", request.streetNumber)
        .addValue("complement", request.complement)
        .addValue("neighborhood", request.neighborhood)
        .addValue("city", request.city.trim())
        .addValue("state", request.state.uppercase())
        .addValue("postalCode", request.postalCode)
        .addValue("bedrooms", request.bedrooms)
        .addValue("bathrooms", request.bathrooms)
        .addValue("parkingSpaces", request.parkingSpaces)
        .addValue("builtArea", request.builtArea)
        .addValue("landArea", request.landArea)
        .addValue("salePrice", request.salePrice)
        .addValue("rentPrice", request.rentPrice)

    private fun propertyRowMapper() = org.springframework.jdbc.core.RowMapper<PropertyResponse> { rs, _ ->
        PropertyResponse(
            id = rs.getObject("id", UUID::class.java),
            referenceCode = rs.getString("reference_code"),
            propertyType = rs.getString("property_type"),
            purpose = rs.getString("purpose"),
            status = rs.getString("status"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            street = rs.getString("street"),
            streetNumber = rs.getString("street_number"),
            complement = rs.getString("complement"),
            neighborhood = rs.getString("neighborhood"),
            city = rs.getString("city"),
            state = rs.getString("state").trim(),
            postalCode = rs.getString("postal_code"),
            bedrooms = (rs.getObject("bedrooms") as? Number)?.toInt(),
            bathrooms = (rs.getObject("bathrooms") as? Number)?.toInt(),
            parkingSpaces = (rs.getObject("parking_spaces") as? Number)?.toInt(),
            builtArea = rs.getBigDecimal("built_area"),
            landArea = rs.getBigDecimal("land_area"),
            salePrice = rs.getBigDecimal("sale_price"),
            rentPrice = rs.getBigDecimal("rent_price"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
