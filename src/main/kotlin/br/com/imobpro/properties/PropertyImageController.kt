package br.com.imobpro.properties

import br.com.imobpro.auth.TenantPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class PropertyImageResponse(
    val id: UUID,
    val propertyId: UUID,
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val position: Int,
    val altText: String?,
    val createdAt: Instant,
)

@Service
class PropertyImageService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val propertyService: PropertyService,
    @Value("${imobpro.storage.local.root:./var/imobpro/uploads}") root: String,
) {
    private val storageRoot: Path = Path.of(root).toAbsolutePath().normalize()
    private val maxFileBytes = 10L * 1024 * 1024

    fun list(principal: TenantPrincipal, propertyId: UUID): List<PropertyImageResponse> {
        propertyService.get(principal, propertyId)
        return jdbc.query(
            """
            SELECT * FROM property_images
            WHERE organization_id = :org AND property_id = :property
            ORDER BY position, created_at
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("property", propertyId),
            mapper,
        )
    }

    fun upload(
        principal: TenantPrincipal,
        propertyId: UUID,
        files: List<MultipartFile>,
    ): List<PropertyImageResponse> {
        propertyService.get(principal, propertyId)
        if (files.isEmpty() || files.size > 20) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Send between 1 and 20 images")
        }
        val existing = jdbc.queryForObject(
            """
            SELECT COALESCE(MAX(position), -1) + 1
            FROM property_images
            WHERE organization_id = :org AND property_id = :property
            """.trimIndent(),
            MapSqlParameterSource().addValue("org", principal.organizationId)
                .addValue("property", propertyId),
            Int::class.java,
        ) ?: 0
        val written = mutableListOf<Path>()
        try {
            Files.createDirectories(storageRoot.resolve(principal.organizationId.toString()))
            files.forEachIndexed { index, file ->
                if (file.isEmpty || file.size > maxFileBytes) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Each image must be between 1 byte and 10 MB")
                }
                val detected = detectImageType(file)
                val imageId = UUID.randomUUID()
                val key = "${principal.organizationId}/$imageId.${detected.second}"
                val target = storageRoot.resolve(key).normalize()
                if (!target.startsWith(storageRoot)) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid storage key")
                }
                Files.createDirectories(target.parent)
                file.inputStream.use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }
                written.add(target)
                jdbc.update(
                    """
                    INSERT INTO property_images (
                        id, organization_id, property_id, storage_key, original_filename,
                        content_type, size_bytes, position
                    ) VALUES (
                        :id, :org, :property, :key, :filename, :contentType, :size, :position
                    )
                    """.trimIndent(),
                    MapSqlParameterSource()
                        .addValue("id", imageId)
                        .addValue("org", principal.organizationId)
                        .addValue("property", propertyId)
                        .addValue("key", key)
                        .addValue("filename", safeFilename(file.originalFilename))
                        .addValue("contentType", detected.first)
                        .addValue("size", file.size)
                        .addValue("position", existing + index),
                )
            }
        } catch (exception: Exception) {
            written.forEach { Files.deleteIfExists(it) }
            throw exception
        }
        return list(principal, propertyId)
    }

    fun content(principal: TenantPrincipal, propertyId: UUID, imageId: UUID): Pair<Resource, String> {
        propertyService.get(principal, propertyId)
        val row = jdbc.query(
            """
            SELECT storage_key, content_type FROM property_images
            WHERE id = :id AND organization_id = :org AND property_id = :property
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", imageId)
                .addValue("org", principal.organizationId).addValue("property", propertyId),
        ) { rs, _ -> rs.getString("storage_key") to rs.getString("content_type") }
            .singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val target = storageRoot.resolve(row.first).normalize()
        if (!target.startsWith(storageRoot) || !Files.isRegularFile(target)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return FileSystemResource(target) to row.second
    }

    fun delete(principal: TenantPrincipal, propertyId: UUID, imageId: UUID) {
        propertyService.get(principal, propertyId)
        val key = jdbc.query(
            """
            DELETE FROM property_images
            WHERE id = :id AND organization_id = :org AND property_id = :property
            RETURNING storage_key
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", imageId)
                .addValue("org", principal.organizationId).addValue("property", propertyId),
        ) { rs, _ -> rs.getString(1) }.singleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val target = storageRoot.resolve(key).normalize()
        if (target.startsWith(storageRoot)) Files.deleteIfExists(target)
    }

    private fun detectImageType(file: MultipartFile): Pair<String, String> {
        val header = file.inputStream.use { it.readNBytes(12) }
        return when {
            header.size >= 8 && header.take(8).map { it.toInt() and 0xff } ==
                listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png" to "png"
            header.size >= 3 && header[0] == 0xff.toByte() &&
                header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg" to "jpg"
            header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp" to "webp"
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only valid PNG, JPEG and WebP images are accepted")
        }
    }

    private fun safeFilename(value: String?): String =
        value.orEmpty().replace(Regex("[\\\\/\\r\\n\\u0000-\\u001f]"), "_")
            .take(255).ifBlank { "image" }

    private val mapper = RowMapper<PropertyImageResponse> { rs, _ ->
        PropertyImageResponse(
            rs.getObject("id", UUID::class.java),
            rs.getObject("property_id", UUID::class.java),
            rs.getString("original_filename"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getInt("position"),
            rs.getString("alt_text"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/images")
class PropertyImageController(private val service: PropertyImageService) {
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    fun list(authentication: Authentication, @PathVariable propertyId: UUID): List<PropertyImageResponse> =
        service.list(authentication.tenant(), propertyId)

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    fun upload(
        authentication: Authentication,
        @PathVariable propertyId: UUID,
        @RequestParam("files") files: List<MultipartFile>,
    ): List<PropertyImageResponse> = service.upload(authentication.tenant(), propertyId, files)

    @GetMapping("/{imageId}/content")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'AGENT')")
    fun content(
        authentication: Authentication,
        @PathVariable propertyId: UUID,
        @PathVariable imageId: UUID,
    ): ResponseEntity<Resource> {
        val (resource, contentType) = service.content(authentication.tenant(), propertyId, imageId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .body(resource)
    }

    @DeleteMapping("/{imageId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    fun delete(
        authentication: Authentication,
        @PathVariable propertyId: UUID,
        @PathVariable imageId: UUID,
    ) = service.delete(authentication.tenant(), propertyId, imageId)

    private fun Authentication.tenant(): TenantPrincipal =
        principal as? TenantPrincipal ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
