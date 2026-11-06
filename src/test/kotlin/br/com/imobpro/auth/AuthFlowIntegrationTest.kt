package br.com.imobpro.auth

import br.com.imobpro.leads.CreateLeadRequest
import br.com.imobpro.leads.LeadResponse
import br.com.imobpro.leads.UpdateLeadStatusRequest
import br.com.imobpro.listings.CreateListingRequest
import br.com.imobpro.listings.ListingResponse
import br.com.imobpro.listings.ListingStatusRequest
import br.com.imobpro.publicportal.PublicLeadRequest
import br.com.imobpro.userdata.AddCollectionItemRequest
import br.com.imobpro.userdata.CollectionResponse
import br.com.imobpro.userdata.CreateCollectionRequest
import br.com.imobpro.properties.PropertyResponse
import br.com.imobpro.properties.SavePropertyRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIntegrationTest {
    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `register authenticate access profile and logout`() {
        val email = "test-${UUID.randomUUID()}@example.com"
        val register =
            RegisterRequest(
                fullName = "Integration User",
                email = email,
                password = "StrongPassword123",
                organizationName = "Integration Realty",
            )
        val registered =
            rest.postForEntity(
                url("/api/v1/auth/register"),
                register,
                TokenResponse::class.java,
            )

        assertEquals(HttpStatus.OK, registered.statusCode)
        val tokens = registered.body!!
        assertNotNull(tokens.accessToken)
        assertNotNull(tokens.refreshToken)

        val headers =
            HttpHeaders().apply {
                setBearerAuth(tokens.accessToken)
            }
        val profile =
            rest.exchange(
                url("/api/v1/auth/me"),
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                AuthenticatedUser::class.java,
            )
        assertEquals(HttpStatus.OK, profile.statusCode)
        assertEquals(email, profile.body!!.email)
        assertEquals("OWNER", profile.body!!.role)

        val propertyRequest =
            SavePropertyRequest(
                propertyType = "APARTMENT",
                purpose = "SALE",
                title = "Apartamento de integração",
                city = "Fortaleza",
                state = "CE",
            )
        val propertyHeaders =
            HttpHeaders().apply {
                setBearerAuth(tokens.accessToken)
                contentType = MediaType.APPLICATION_JSON
            }
        val createdProperty =
            rest.postForEntity(
                url("/api/v1/properties"),
                HttpEntity(propertyRequest, propertyHeaders),
                PropertyResponse::class.java,
            )
        assertEquals(HttpStatus.CREATED, createdProperty.statusCode)
        val property = createdProperty.body!!
        assertTrue(property.referenceCode.startsWith("IMB-"))

        val leadCreated =
            rest.postForEntity(
                url("/api/v1/leads"),
                HttpEntity(
                    CreateLeadRequest(
                        fullName = "Potential Buyer",
                        email = "buyer@example.com",
                        source = "WEBSITE",
                        propertyId = property.id,
                    ),
                    propertyHeaders,
                ),
                LeadResponse::class.java,
            )
        assertEquals(HttpStatus.CREATED, leadCreated.statusCode)
        val lead = leadCreated.body!!
        assertEquals(property.id, lead.propertyId)

        val statusHeaders =
            HttpHeaders().apply {
                setBearerAuth(tokens.accessToken)
                contentType = MediaType.APPLICATION_JSON
            }
        val leadStatus =
            rest.exchange(
                url("/api/v1/leads/${lead.id}/status"),
                HttpMethod.PATCH,
                HttpEntity(UpdateLeadStatusRequest("CONTACTED"), statusHeaders),
                Void::class.java,
            )
        assertEquals(HttpStatus.NO_CONTENT, leadStatus.statusCode)


        val listingCreated =
            rest.postForEntity(
                url("/api/v1/listings"),
                HttpEntity(
                    CreateListingRequest(
                        propertyId = property.id,
                        title = "Apartamento público de integração",
                    ),
                    propertyHeaders,
                ),
                ListingResponse::class.java,
            )
        assertEquals(HttpStatus.CREATED, listingCreated.statusCode)
        val listing = listingCreated.body!!
        val published =
            rest.exchange(
                url("/api/v1/listings/${listing.id}/status"),
                HttpMethod.PATCH,
                HttpEntity(ListingStatusRequest("PUBLISHED"), propertyHeaders),
                Void::class.java,
            )
        assertEquals(HttpStatus.NO_CONTENT, published.statusCode)

        val organizationChoices =
            rest.postForEntity(
                url("/api/v1/auth/organizations"),
                LoginRequest(email, "StrongPassword123"),
                Array<OrganizationChoice>::class.java,
            )
        assertEquals(HttpStatus.OK, organizationChoices.statusCode)
        val organizationSlug = organizationChoices.body!!.first().slug
        val publicListing =
            rest.getForEntity(
                url("/api/v1/public/$organizationSlug/listings/${listing.publicSlug}"),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, publicListing.statusCode)

        val publicLead =
            rest.postForEntity(
                url("/api/v1/public/$organizationSlug/leads"),
                PublicLeadRequest(
                    fullName = "Public Portal Buyer",
                    phone = "+5585999999999",
                    email = "public-buyer@example.com",
                    listingSlug = listing.publicSlug,
                ),
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, publicLead.statusCode)

        val collection =
            rest.postForEntity(
                url("/api/v1/me/collections"),
                HttpEntity(CreateCollectionRequest("Favoritos da integração"), propertyHeaders),
                CollectionResponse::class.java,
            )
        assertEquals(HttpStatus.CREATED, collection.statusCode)
        val addToCollection =
            rest.exchange(
                url("/api/v1/me/collections/${collection.body!!.id}/items"),
                HttpMethod.POST,
                HttpEntity(AddCollectionItemRequest(property.id), propertyHeaders),
                Void::class.java,
            )
        assertEquals(HttpStatus.NO_CONTENT, addToCollection.statusCode)

        val secondRegistration =
            rest.postForEntity(
                url("/api/v1/auth/register"),
                RegisterRequest(
                    fullName = "Another Tenant",
                    email = "other-${UUID.randomUUID()}@example.com",
                    password = "AnotherStrongPassword123",
                    organizationName = "Other Realty",
                ),
                TokenResponse::class.java,
            )
        val otherTenantHeaders =
            HttpHeaders().apply {
                setBearerAuth(secondRegistration.body!!.accessToken)
            }
        val crossTenantRead =
            rest.exchange(
                url("/api/v1/properties/${property.id}"),
                HttpMethod.GET,
                HttpEntity<Void>(otherTenantHeaders),
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantRead.statusCode)

        val crossTenantLeadRead =
            rest.exchange(
                url("/api/v1/leads/${lead.id}"),
                HttpMethod.GET,
                HttpEntity<Void>(otherTenantHeaders),
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, crossTenantLeadRead.statusCode)

        val logout =
            rest.exchange(
                url("/api/v1/auth/logout"),
                HttpMethod.POST,
                HttpEntity<Void>(headers),
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, logout.statusCode)

        val profileAfterLogout =
            rest.exchange(
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
