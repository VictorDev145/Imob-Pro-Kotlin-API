package br.com.imobpro.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface ObjectStorage {
    fun put(key: String, input: InputStream, sizeBytes: Long)
    fun get(key: String): Resource?
    fun delete(key: String)
}

class LocalObjectStorage(root: String) : ObjectStorage {
    private val storageRoot = Path.of(root).toAbsolutePath().normalize()

    override fun put(key: String, input: InputStream, sizeBytes: Long) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun get(key: String): Resource? {
        val target = resolve(key)
        return if (Files.isRegularFile(target)) FileSystemResource(target) else null
    }

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    private fun resolve(key: String): Path {
        val target = storageRoot.resolve(key).normalize()
        require(target.startsWith(storageRoot)) { "Invalid object storage key" }
        return target
    }
}

class S3ObjectStorage(
    private val client: S3Client,
    private val bucket: String,
) : ObjectStorage {
    override fun put(key: String, input: InputStream, sizeBytes: Long) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromInputStream(input, sizeBytes),
        )
    }

    override fun get(key: String): Resource? =
        try {
            client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()).use { response ->
                ByteArrayResource(response.readAllBytes())
            }
        } catch (_: NoSuchKeyException) {
            null
        }

    override fun delete(key: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }
}

@Configuration
class ObjectStorageConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "imobpro.storage.s3", name = ["enabled"], havingValue = "true")
    fun s3Client(
        @Value("\${imobpro.storage.s3.region:us-east-1}") region: String,
        @Value("\${imobpro.storage.s3.endpoint:}") endpoint: String,
        @Value("\${imobpro.storage.s3.path-style:true}") pathStyle: Boolean,
    ): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
        if (endpoint.isNotBlank()) builder.endpointOverride(URI.create(endpoint))
        return builder.build()
    }

    @Bean
    @ConditionalOnProperty(prefix = "imobpro.storage.s3", name = ["enabled"], havingValue = "true")
    fun s3ObjectStorage(
        client: S3Client,
        @Value("\${imobpro.storage.s3.bucket}") bucket: String,
    ): ObjectStorage = S3ObjectStorage(client, bucket)

    @Bean
    @ConditionalOnProperty(prefix = "imobpro.storage.s3", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun localObjectStorage(
        @Value("\${imobpro.storage.local.root:./var/imobpro/uploads}") root: String,
    ): ObjectStorage = LocalObjectStorage(root)
}
