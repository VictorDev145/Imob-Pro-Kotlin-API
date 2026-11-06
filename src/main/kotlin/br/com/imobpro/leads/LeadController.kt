package br.com.imobpro.leads

import br.com.imobpro.auth.TenantPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
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
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/leads")
class LeadController(private val leadService: LeadService) {
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    fun list(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
        @RequestParam(required = false)
        @Pattern(regexp = "NEW|CONTACTED|QUALIFIED|CONVERTED|LOST")
        status: String?,
    ): LeadPage = leadService.list(authentication.tenantPrincipal(), offset, limit, status)

    @GetMapping("/{leadId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    fun get(authentication: Authentication, @PathVariable leadId: UUID): LeadResponse =
        leadService.get(authentication.tenantPrincipal(), leadId)

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreateLeadRequest,
    ): LeadResponse = leadService.create(authentication.tenantPrincipal(), request)

    @PatchMapping("/{leadId}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateStatus(
        authentication: Authentication,
        @PathVariable leadId: UUID,
        @Valid @RequestBody request: UpdateLeadStatusRequest,
    ) {
        leadService.updateStatus(authentication.tenantPrincipal(), leadId, request.status)
    }

    @PatchMapping("/{leadId}/assignment")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun assign(
        authentication: Authentication,
        @PathVariable leadId: UUID,
        @Valid @RequestBody request: AssignLeadRequest,
    ) {
        leadService.assign(authentication.tenantPrincipal(), leadId, request.assignedToUserId)
    }

    private fun Authentication.tenantPrincipal(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
