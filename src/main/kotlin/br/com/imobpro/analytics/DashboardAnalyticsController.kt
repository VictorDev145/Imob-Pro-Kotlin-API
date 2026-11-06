package br.com.imobpro.analytics

import br.com.imobpro.auth.TenantPrincipal
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class DashboardSummary(
    val properties: Long,
    val publishedListings: Long,
    val newLeads: Long,
    val upcomingVisits: Long,
    val openTasks: Long,
    val activeMembers: Long,
    val leads: Long,
    val owners: Long,
    val customers: Long,
    val agents: Long,
)

data class ActivityResponse(
    val id: UUID,
    val actorUserId: UUID?,
    val actorName: String?,
    val action: String,
    val resourceType: String,
    val resourceId: UUID?,
    val metadata: JsonNode,
    val createdAt: Instant,
)

data class DailyViewCount(val date: String, val views: Long)

data class PropertyAnalyticsResponse(
    val property: PropertyAnalyticsIdentity,
    val total: Long,
    val unique: Long,
    val favorites: Long,
    val leads: Long,
    val visits: Long,
    val dailyViews: List<DailyViewCount>,
)

data class PropertyAnalyticsIdentity(
    val id: UUID,
    val title: String,
)

data class PublicViewRequest(
    @field:Size(max = 200) val sessionKey: String? = null,
    @field:Pattern(regexp = "DIRECT|SEARCH|SOCIAL|PORTAL|EMAIL|OTHER") val source: String = "DIRECT",
)

@Service
class DashboardAnalyticsService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun summary(principal: TenantPrincipal): DashboardSummary =
        jdbc.query(
            """
            SELECT
                (SELECT COUNT(*) FROM properties
                 WHERE organization_id = :org AND archived_at IS NULL) AS properties,
                (SELECT COUNT(*) FROM listings
                 WHERE organization_id = :org AND status = 'PUBLISHED') AS published_listings,
                (SELECT COUNT(*) FROM leads
                 WHERE organization_id = :org AND status = 'NEW') AS new_leads,
                (SELECT COUNT(*) FROM visits
                 WHERE organization_id = :org AND scheduled_at >= NOW()
                   AND status IN ('SCHEDULED', 'CONFIRMED')) AS upcoming_visits,
                (SELECT COUNT(*) FROM tasks
                 WHERE organization_id = :org AND status IN ('OPEN', 'IN_PROGRESS')) AS open_tasks,
                (SELECT COUNT(*) FROM organization_memberships
                 WHERE organization_id = :org AND status = 'ACTIVE') AS active_members,
                (SELECT COUNT(*) FROM leads WHERE organization_id = :org) AS leads,
                (SELECT COUNT(*) FROM organization_memberships
                 WHERE organization_id = :org AND status = 'ACTIVE' AND role = 'OWNER') AS owners,
                (SELECT COUNT(*) FROM organization_memberships
                 WHERE organization_id = :org AND status = 'ACTIVE' AND role = 'CUSTOMER') AS customers,
                (SELECT COUNT(*) FROM organization_memberships
                 WHERE organization_id = :org AND status = 'ACTIVE' AND role = 'AGENT') AS agents
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId),
            RowMapper { rs, _ ->
                DashboardSummary(
                    rs.getLong("properties"),
                    rs.getLong("published_listings"),
                    rs.getLong("new_leads"),
                    rs.getLong("upcoming_visits"),
                    rs.getLong("open_tasks"),
                    rs.getLong("active_members"),
                    rs.getLong("leads"),
                    rs.getLong("owners"),
                    rs.getLong("customers"),
                    rs.getLong("agents"),
                )
            },
        ).single()

    fun activity(principal: TenantPrincipal, limit: Int): List<ActivityResponse> =
        jdbc.query(
            """
            SELECT a.id, a.actor_user_id, u.full_name AS actor_name, a.action,
                   a.resource_type, a.resource_id, a.metadata, a.created_at
            FROM activity_log a
            LEFT JOIN users u ON u.id = a.actor_user_id
            WHERE a.organization_id = :org
            ORDER BY a.created_at DESC
            LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId).addValue("limit", limit),
            RowMapper { rs, _ ->
                ActivityResponse(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("actor_user_id", UUID::class.java),
                    rs.getString("actor_name"),
                    rs.getString("action"),
                    rs.getString("resource_type"),
                    rs.getObject("resource_id", UUID::class.java),
                    objectMapper.readTree(rs.getString("metadata")),
                    rs.getTimestamp("created_at").toInstant(),
                )
            },
        )

    fun property(principal: TenantPrincipal, propertyId: UUID): PropertyAnalyticsResponse {
        val identity = jdbc.query(
            """
            SELECT id, title FROM properties
            WHERE id = :property AND organization_id = :org AND archived_at IS NULL
            """.trimIndent(),
            MapSqlParameterSource().addValue("property", propertyId).addValue("org", principal.organizationId),
            RowMapper { rs, _ ->
                PropertyAnalyticsIdentity(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("title"),
                )
            },
        ).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val totals = jdbc.query(
            """
            SELECT
                (SELECT COUNT(*) FROM property_views
                 WHERE organization_id = :org AND property_id = :property) AS total,
                (SELECT COUNT(DISTINCT session_hash) FROM property_views
                 WHERE organization_id = :org AND property_id = :property) AS unique_sessions,
                (SELECT COUNT(*) FROM favorites
                 WHERE organization_id = :org AND property_id = :property) AS favorites,
                (SELECT COUNT(*) FROM leads
                 WHERE organization_id = :org AND property_id = :property) AS leads,
                (SELECT COUNT(*) FROM visits
                 WHERE organization_id = :org AND property_id = :property) AS visits
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId).addValue("property", propertyId),
            RowMapper { rs, _ ->
                listOf(
                    rs.getLong("total"),
                    rs.getLong("unique_sessions"),
                    rs.getLong("favorites"),
                    rs.getLong("leads"),
                    rs.getLong("visits"),
                )
            },
        ).single()
        val daily = jdbc.query(
            """
            SELECT TO_CHAR(created_at::date, 'YYYY-MM-DD') AS view_date, COUNT(*) AS views
            FROM property_views
            WHERE organization_id = :org AND property_id = :property
              AND created_at >= NOW() - INTERVAL '30 days'
            GROUP BY created_at::date ORDER BY created_at::date
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId).addValue("property", propertyId),
            RowMapper { rs, _ -> DailyViewCount(rs.getString("view_date"), rs.getLong("views")) },
        )
        return PropertyAnalyticsResponse(
            property = identity,
            total = totals[0],
            unique = totals[1],
            favorites = totals[2],
            leads = totals[3],
            visits = totals[4],
            dailyViews = daily,
        )
    }
}

@Service
class PublicViewService(private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional
    fun record(organizationSlug: String, listingSlug: String, request: PublicViewRequest) {
        val sessionHash = request.sessionKey?.trim()?.takeIf { it.isNotBlank() }?.let(::digest)
        jdbc.update(
            """
            INSERT INTO property_views (organization_id, property_id, session_hash, source)
            SELECT l.organization_id, l.property_id, :sessionHash, :source
            FROM listings l
            JOIN organizations o ON o.id = l.organization_id
            JOIN properties p ON p.id = l.property_id AND p.organization_id = l.organization_id
            WHERE o.slug = :orgSlug AND o.status = 'ACTIVE'
              AND l.public_slug = :listingSlug AND l.status = 'PUBLISHED'
              AND l.channel = 'WEBSITE' AND p.status = 'ACTIVE' AND p.archived_at IS NULL
              AND (
                CAST(:sessionHash AS CHAR(64)) IS NULL OR NOT EXISTS (
                    SELECT 1 FROM property_views pv
                    WHERE pv.organization_id = l.organization_id
                      AND pv.property_id = l.property_id
                      AND pv.session_hash = CAST(:sessionHash AS CHAR(64))
                      AND pv.created_at >= CURRENT_DATE
                )
              )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("sessionHash", sessionHash)
                .addValue("source", request.source)
                .addValue("orgSlug", organizationSlug)
                .addValue("listingSlug", listingSlug),
        ).also { if (it == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND) }
    }

    @Transactional
    fun recordByPropertyId(organizationSlug: String, propertyId: UUID, request: PublicViewRequest) {
        val sessionHash = request.sessionKey?.trim()?.takeIf { it.isNotBlank() }?.let(::digest)
        jdbc.update(
            """
            INSERT INTO property_views (organization_id, property_id, session_hash, source)
            SELECT p.organization_id, p.id, :sessionHash, :source
            FROM properties p
            JOIN organizations o ON o.id = p.organization_id
            JOIN listings l ON l.property_id = p.id AND l.organization_id = p.organization_id
            WHERE o.slug = :orgSlug AND o.status = 'ACTIVE'
              AND p.id = :propertyId AND p.status = 'ACTIVE' AND p.archived_at IS NULL
              AND l.status = 'PUBLISHED' AND l.channel = 'WEBSITE'
              AND (
                CAST(:sessionHash AS CHAR(64)) IS NULL OR NOT EXISTS (
                    SELECT 1 FROM property_views pv
                    WHERE pv.organization_id = p.organization_id
                      AND pv.property_id = p.id
                      AND pv.session_hash = CAST(:sessionHash AS CHAR(64))
                      AND pv.created_at >= CURRENT_DATE
                )
              )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("sessionHash", sessionHash)
                .addValue("source", request.source)
                .addValue("orgSlug", organizationSlug)
                .addValue("propertyId", propertyId),
        ).also { if (it == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND) }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
class DashboardController(private val service: DashboardAnalyticsService) {
    @GetMapping("/summary")
    fun summary(authentication: Authentication): DashboardSummary =
        service.summary(authentication.tenant())

    @GetMapping("/activity")
    fun activity(
        authentication: Authentication,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<ActivityResponse> {
        if (limit !in 1..100) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        return service.activity(authentication.tenant(), limit)
    }

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}

@RestController
@RequestMapping("/api/v1/analytics/properties")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
class PropertyAnalyticsController(private val service: DashboardAnalyticsService) {
    @GetMapping("/{propertyId}")
    fun property(
        authentication: Authentication,
        @PathVariable propertyId: UUID,
    ): PropertyAnalyticsResponse = service.property(authentication.tenant(), propertyId)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}

@RestController
@RequestMapping("/api/v1/public/{organizationSlug}/properties/{propertyId}/view")
class PublicPropertyViewController(private val service: PublicViewService) {
    @PostMapping
    fun record(
        @PathVariable organizationSlug: String,
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: PublicViewRequest,
    ): Map<String, String> {
        service.recordByPropertyId(organizationSlug, propertyId, request)
        return mapOf("status" to "recorded")
    }
}

@RestController
@RequestMapping("/api/v1/public/{organizationSlug}/listings/{listingSlug}/view")
class PublicViewController(private val service: PublicViewService) {
    @PostMapping
    fun record(
        @PathVariable organizationSlug: String,
        @PathVariable listingSlug: String,
        @Valid @RequestBody request: PublicViewRequest,
    ): Map<String, String> {
        service.record(organizationSlug, listingSlug, request)
        return mapOf("status" to "recorded")
    }
}
