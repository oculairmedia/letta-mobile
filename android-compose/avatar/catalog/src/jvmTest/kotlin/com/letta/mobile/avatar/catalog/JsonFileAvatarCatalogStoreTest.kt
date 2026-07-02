package com.letta.mobile.avatar.catalog

import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarModel
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class JsonFileAvatarCatalogStoreTest {
    private fun model(id: String) = AvatarModel(
        id = id,
        displayName = id,
        uri = "file:///avatars/$id.glb",
        format = AvatarFormat.GLB,
    )

    @Test
    fun persistsAcrossInstancesAndCreatesParentDirs() = runTest {
        val dir = Files.createTempDirectory("avatar-catalog-test")
        val file = dir.resolve("nested/catalog.json")
        try {
            JsonFileAvatarCatalogStore(file).save(listOf(model("a"), model("b")))

            val reloaded = JsonFileAvatarCatalogStore(file).load()
            assertEquals(listOf("a", "b"), reloaded.map { it.id })
            assertTrue(file.readText().contains("\"schemaVersion\": 1"))
            // No temp file left behind.
            assertTrue(Files.list(file.parent).use { it.count() } == 1L)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingFileLoadsAsEmptyCatalog() = runTest {
        val dir = Files.createTempDirectory("avatar-catalog-test")
        try {
            assertEquals(
                emptyList(),
                JsonFileAvatarCatalogStore(dir.resolve("absent.json")).load(),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
