package br.com.imobpro.organizations

import br.com.imobpro.auth.TenantPrincipal
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

@RestController
@RequestMapping("/api/v1/organizations/current")
class OrganizationController(private val organizationService: OrganizationService) {
    @GetMapping
    fun current(authentication: Authentication): OrganizationSummary =
        organizationService.current(authentication.tenantPrincipal())

    @GetMapping("/members")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    fun members(authentication: Authentication): List<MemberSummary> =
        organizationService.members(authentication.tenantPrincipal())

    @PatchMapping("/members/{userId}/role")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateRole(
        authentication: Authentication,
        @PathVariable userId: UUID,
        @Valid @RequestBody request: UpdateMemberRoleRequest,
    ) {
        organizationService.updateRole(authentication.tenantPrincipal(), userId, request.role)
    }

    @DeleteMapping("/members/{userId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(authentication: Authentication, @PathVariable userId: UUID) {
        organizationService.removeMember(authentication.tenantPrincipal(), userId)
    }

    private fun Authentication.tenantPrincipal(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
