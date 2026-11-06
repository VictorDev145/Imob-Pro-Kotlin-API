package br.com.imobpro.organizations

import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

data class OrganizationSummary(
    val id: UUID,
    val name: String,
    val slug: String,
    val status: String,
)

data class MemberSummary(
    val userId: UUID,
    val fullName: String,
    val email: String,
    val phone: String?,
    val role: String,
    val status: String,
    val joinedAt: Instant,
)

data class UpdateMemberRoleRequest(
    @field:Pattern(regexp = "ADMIN|MANAGER|AGENT|CUSTOMER|PROPERTY_OWNER")
    val role: String,
)
