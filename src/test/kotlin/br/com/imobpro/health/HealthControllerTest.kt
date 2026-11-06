package br.com.imobpro.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthControllerTest {
    private val controller = HealthController()

    @Test
    fun `health endpoint returns service status`() {
        val response = controller.health()
        assertEquals("UP", response["status"])
        assertEquals("imob-pro-kotlin-api", response["service"])
        assertEquals("0.1.0", response["version"])
    }
}
