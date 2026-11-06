package br.com.imobpro.auth

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
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

data class ForgotPasswordRequest(
    @field:Email @field:NotBlank @field:Size(max = 254) val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank val token: String,
    @field:NotBlank @field:Size(min = 12, max = 72) val newPassword: String,
)

@Service
class AccountRecoveryService(
    private val jdbc: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
    private val mailSender: JavaMailSender,
    @Value("${app.public-base-url:http://localhost:3000}") private val publicBaseUrl: String,
    @Value("${spring.mail.username:}") private val mailUsername: String,
) {
    private val random = SecureRandom()

    @Transactional
    fun requestReset(emailValue: String) {
        val email = emailValue.trim().lowercase()
        val user = jdbc.query(
            "SELECT id, full_name FROM users WHERE email = ? AND status = 'ACTIVE'",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("full_name") },
            email,
        ).singleOrNull() ?: return
        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val hash = digest(token)
        jdbc.update(
            "UPDATE password_reset_tokens SET used_at = NOW() WHERE user_id = ? AND used_at IS NULL",
            user.first,
        )
        jdbc.update(
            """
            INSERT INTO password_reset_tokens(user_id, token_hash, expires_at)
            VALUES (?, ?, NOW() + INTERVAL '30 minutes')
            """.trimIndent(),
            user.first,
            hash,
        )
        val link = "${publicBaseUrl.trimEnd('/')}/reset-password?token=$token"
        try {
            val message = SimpleMailMessage()
            message.setTo(email)
            message.subject = "Redefinição de senha — Imob-Pro"
            message.text = "Olá, ${user.second}. Use este link para redefinir sua senha (válido por 30 minutos):\n\n$link\n\nSe você não solicitou, ignore este e-mail."
            if (mailUsername.isNotBlank()) message.from = mailUsername
            mailSender.send(message)
        } catch (_: MailException) {
            // Keep the response indistinguishable to avoid leaking account existence.
        }
    }

    @Transactional
    fun reset(token: String, newPassword: String) {
        if (newPassword.toByteArray(Charsets.UTF_8).size > 72) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds BCrypt's 72-byte limit")
        }
        val row = jdbc.query(
            """
            SELECT id, user_id FROM password_reset_tokens
            WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("user_id", UUID::class.java) },
            digest(token),
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token")
        jdbc.update(
            "UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ? AND status = 'ACTIVE'",
            passwordEncoder.encode(newPassword),
            row.second,
        ).also {
            if (it == 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Account is not active")
        }
        jdbc.update("UPDATE password_reset_tokens SET used_at = NOW() WHERE user_id = ? AND used_at IS NULL", row.second)
        jdbc.update("UPDATE auth_sessions SET revoked_at = NOW() WHERE user_id = ? AND revoked_at IS NULL", row.second)
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

@RestController
@RequestMapping("/api/v1/auth/password")
class AccountRecoveryController(private val service: AccountRecoveryService) {
    @PostMapping("/forgot")
    fun forgot(@Valid @RequestBody request: ForgotPasswordRequest): Map<String, String> {
        service.requestReset(request.email)
        return mapOf("message" to "Se a conta existir, enviaremos as instruções para o e-mail cadastrado.")
    }

    @PostMapping("/reset")
    fun reset(@Valid @RequestBody request: ResetPasswordRequest): Map<String, String> {
        service.reset(request.token, request.newPassword)
        return mapOf("message" to "Senha redefinida. Entre novamente com sua nova senha.")
    }
}
