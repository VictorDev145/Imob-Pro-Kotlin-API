package br.com.imobpro.auth

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val authService: AuthService) {
    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): TokenResponse = authService.register(request)
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): TokenResponse = authService.login(request)

    @PostMapping("/organizations")
    fun organizations(@Valid @RequestBody request: LoginRequest): List<OrganizationChoice> =
        authService.organizations(request)
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenResponse = authService.refresh(request.refreshToken)
    @PostMapping("/logout")
    fun logout(@RequestHeader("Authorization") authorization: String): Map<String, String> {
        val token = authorization.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        authService.logout(token)
        return mapOf("status" to "logged_out")
    }
    @GetMapping("/me")
    fun me(authentication: Authentication): AuthenticatedUser {
        val principal = authentication.principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return authService.currentUser(principal.userId, principal.organizationId)
    }
}
