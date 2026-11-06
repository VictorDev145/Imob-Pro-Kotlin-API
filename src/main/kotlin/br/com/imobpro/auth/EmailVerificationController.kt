package br.com.imobpro.auth

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class ConfirmEmailRequest(@field:NotBlank val token: String)

@Service
class EmailVerificationService(
    private val jdbc: JdbcTemplate,
    private val mailSender: JavaMailSender,
    @Value("\${app.public-base-url:http://localhost:3100}") private val publicBaseUrl: String,
    @Value("\${spring.mail.username:}") private val mailUsername: String,
) {
    private val random = SecureRandom()

    @Transactional
    fun request(userId: UUID) {
        val user = jdbc.query(
            "SELECT email, full_name FROM users WHERE id = ? AND status = 'ACTIVE'",
            { rs, _ -> rs.getString("email") to rs.getString("full_name") },
            userId,
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        jdbc.update(
            "UPDATE email_verification_tokens SET used_at = NOW() WHERE user_id = ? AND used_at IS NULL",
            userId,
        )
        jdbc.update(
            """
            INSERT INTO email_verification_tokens(user_id, token_hash, expires_at)
            VALUES (?, ?, NOW() + INTERVAL '24 hours')
            """.trimIndent(),
            userId,
            digest(token),
        )
        val message = SimpleMailMessage()
        message.setTo(user.first)
        if (mailUsername.isNotBlank()) message.from = mailUsername
        message.subject = "Confirme seu e-mail — Imob-Pro"
        message.text = "Olá, " + user.second + ". Confirme seu endereço de e-mail neste link (válido por 24 horas):\n\n" +
            publicBaseUrl.trimEnd('/') + "/verify-email?token=" + token
        try {
            mailSender.send(message)
        } catch (_: MailException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Verification email could not be sent")
        }
    }

    @Transactional
    fun confirm(token: String) {
        val userId = jdbc.query(
            """
            SELECT user_id FROM email_verification_tokens
            WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("user_id", UUID::class.java) },
            digest(token),
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification token")
        jdbc.update("UPDATE users SET email_verified_at = NOW() WHERE id = ?", userId)
        jdbc.update(
            "UPDATE email_verification_tokens SET used_at = NOW() WHERE user_id = ? AND used_at IS NULL",
            userId,
        )
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

@RestController
@RequestMapping("/api/v1/me/email-verification")
class RequestEmailVerificationController(private val service: EmailVerificationService) {
    @PostMapping
    fun request(authentication: Authentication): Map<String, String> {
        val principal = authentication.principal as? TenantPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        service.request(principal.userId)
        return mapOf("status" to "sent")
    }
}

@RestController
@RequestMapping("/api/v1/auth/email-verification")
class ConfirmEmailVerificationController(private val service: EmailVerificationService) {
    @PostMapping("/confirm")
    fun confirm(@Valid @RequestBody request: ConfirmEmailRequest): Map<String, String> {
        service.confirm(request.token)
        return mapOf("status" to "verified")
    }
}
