package br.com.imobpro.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIntegrationTest {
    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `register authenticate access profile and logout`() {
        val email = "test-${UUID.randomUUID()}@example.com"
        val register = RegisterRequest(
            fullName = "Integration User",
            email = email,
            password = "StrongPassword123",
            organizationName = "Integration Realty",
        )
        val registered = rest.postForEntity(
            url("/api/v1/auth/register"),
            register,
            TokenResponse::class.java,
        )

        assertEquals(HttpStatus.OK, registered.statusCode)
        val tokens = registered.body!!
        assertNotNull(tokens.accessToken)
        assertNotNull(tokens.refreshToken)

        val headers = HttpHeaders().apply {
            setBearerAuth(tokens.accessToken)
        }
        val profile = rest.exchange(
            url("/api/v1/auth/me"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            AuthenticatedUser::class.java,
        )
        assertEquals(HttpStatus.OK, profile.statusCode)
        assertEquals(email, profile.body!!.email)
        assertEquals("OWNER", profile.body!!.role)

        val logout = rest.exchange(
            url("/api/v1/auth/logout"),
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, logout.statusCode)

        val profileAfterLogout = rest.exchange(
            url("/api/v1/auth/me"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, profileAfterLogout.statusCode)
    }

    private fun url(path: String): String = "http://localhost:$port$path"

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
