package br.com.imobpro.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun imobProOpenAPI(): OpenAPI = OpenAPI().info(
        Info().title("Imob-Pro API").version("0.1.0")
            .description("API multiempresa do Imob-Pro. Contratos ainda em evolução."),
    )
}
