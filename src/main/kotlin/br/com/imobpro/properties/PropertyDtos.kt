package br.com.imobpro.properties

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class SavePropertyRequest(
    @field:NotBlank
    @field:Pattern(regexp = "HOUSE|APARTMENT|LAND|COMMERCIAL|RURAL|OTHER")
    val propertyType: String,
    @field:NotBlank
    @field:Pattern(regexp = "SALE|RENT|BOTH")
    val purpose: String,
    @field:NotBlank
    @field:Size(max = 180)
    val title: String,
    @field:Size(max = 10000)
    val description: String? = null,
    @field:Size(max = 180)
    val street: String? = null,
    @field:Size(max = 30)
    val streetNumber: String? = null,
    @field:Size(max = 100)
    val complement: String? = null,
    @field:Size(max = 100)
    val neighborhood: String? = null,
    @field:NotBlank
    @field:Size(max = 100)
    val city: String,
    @field:NotBlank
    @field:Pattern(regexp = "[A-Za-z]{2}")
    val state: String,
    @field:Size(max = 12)
    val postalCode: String? = null,
    @field:Min(0)
    val bedrooms: Int? = null,
    @field:Min(0)
    val bathrooms: Int? = null,
    @field:Min(0)
    val parkingSpaces: Int? = null,
    @field:DecimalMin("0.01")
    val builtArea: BigDecimal? = null,
    @field:DecimalMin("0.01")
    val landArea: BigDecimal? = null,
    @field:DecimalMin("0.00")
    val salePrice: BigDecimal? = null,
    @field:DecimalMin("0.00")
    val rentPrice: BigDecimal? = null,
)

data class PropertyResponse(
    val id: UUID,
    val referenceCode: String,
    val propertyType: String,
    val purpose: String,
    val status: String,
    val title: String,
    val description: String?,
    val street: String?,
    val streetNumber: String?,
    val complement: String?,
    val neighborhood: String?,
    val city: String,
    val state: String,
    val postalCode: String?,
    val bedrooms: Int?,
    val bathrooms: Int?,
    val parkingSpaces: Int?,
    val builtArea: BigDecimal?,
    val landArea: BigDecimal?,
    val salePrice: BigDecimal?,
    val rentPrice: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PropertyPage(
    val items: List<PropertyResponse>,
    val offset: Int,
    val limit: Int,
)
