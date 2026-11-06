package br.com.imobpro.leads

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateLeadRequest(
    @field:NotBlank
    @field:Size(max = 160)
    val fullName: String,
    @field:Email
    @field:Size(max = 254)
    val email: String? = null,
    @field:Size(max = 40)
    val phone: String? = null,
    @field:Pattern(regexp = "WEBSITE|PHONE|WHATSAPP|WALK_IN|REFERRAL|OTHER")
    val source: String = "OTHER",
    @field:Size(max = 10000)
    val message: String? = null,
    val propertyId: UUID? = null,
)

data class UpdateLeadStatusRequest(
    @field:Pattern(regexp = "NEW|CONTACTED|QUALIFIED|CONVERTED|LOST")
    val status: String,
)

data class AssignLeadRequest(val assignedToUserId: UUID?)

data class LeadResponse(
    val id: UUID,
    val fullName: String,
    val email: String?,
    val phone: String?,
    val source: String,
    val status: String,
    val message: String?,
    val propertyId: UUID?,
    val assignedToUserId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class LeadPage(val items: List<LeadResponse>, val offset: Int, val limit: Int)
