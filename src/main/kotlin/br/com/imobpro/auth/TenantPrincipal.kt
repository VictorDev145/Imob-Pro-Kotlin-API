package br.com.imobpro.auth

import java.util.UUID

data class TenantPrincipal(val userId: UUID, val organizationId: UUID, val role: String)
