package br.com.imobpro.health

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class HealthController {
    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf(
        "status" to "UP",
        "service" to "imob-pro-kotlin-api",
        "version" to "0.1.0",
    )
}
