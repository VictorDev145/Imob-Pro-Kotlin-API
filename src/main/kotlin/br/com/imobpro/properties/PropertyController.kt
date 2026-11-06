package br.com.imobpro.properties

import br.com.imobpro.auth.TenantPrincipal
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/properties")
class PropertyController(private val propertyService: PropertyService) {
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT', 'PROPERTY_OWNER')")
    fun list(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) status: String?,
    ): PropertyPage = propertyService.list(authentication.tenantPrincipal(), offset, limit, city, status)

    @GetMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT', 'PROPERTY_OWNER')")
    fun get(
        authentication: Authentication,
        @PathVariable propertyId: UUID,
    ): PropertyResponse = propertyService.get(authentication.tenantPrincipal(), propertyId)

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT', 'PROPERTY_OWNER')")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: SavePropertyRequest,
    ): PropertyResponse = propertyService.create(authentication.tenantPrincipal(), request)

    @PutMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT', 'PROPERTY_OWNER')")
    fun update(
        authentication: Authentication,
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: SavePropertyRequest,
    ): PropertyResponse = propertyService.update(authentication.tenantPrincipal(), propertyId, request)

    @org.springframework.web.bind.annotation.PatchMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT', 'PROPERTY_OWNER')")
    fun patch(
        authentication: Authentication,
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: SavePropertyRequest,
    ): PropertyResponse = propertyService.update(authentication.tenantPrincipal(), propertyId, request)

    @DeleteMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'PROPERTY_OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun archive(authentication: Authentication, @PathVariable propertyId: UUID) {
        propertyService.archive(authentication.tenantPrincipal(), propertyId)
    }

    private fun Authentication.tenantPrincipal(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
