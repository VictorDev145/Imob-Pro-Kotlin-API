package br.com.imobpro.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path

class ObjectStorageTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun localStorageWritesReadsAndDeletesObjects() {
        val storage = LocalObjectStorage(temporaryDirectory.toString())
        val payload = "image bytes".toByteArray()

        storage.put("tenant/image.jpg", ByteArrayInputStream(payload), payload.size.toLong())

        val resource = storage.get("tenant/image.jpg")
        assertTrue(resource?.exists() == true)
        assertArrayEquals(payload, resource!!.inputStream.use { it.readAllBytes() })

        storage.delete("tenant/image.jpg")
        assertTrue(storage.get("tenant/image.jpg") == null)
    }

    @Test
    fun localStorageRejectsPathTraversal() {
        val storage = LocalObjectStorage(temporaryDirectory.toString())
        assertThrows(IllegalArgumentException::class.java) {
            storage.put("../../outside.txt", ByteArrayInputStream(byteArrayOf(1)), 1)
        }
    }
}
