package com.letta.mobile.avatar.pipeline

import com.letta.mobile.avatar.catalog.AvatarCatalogCodec
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarImportVisibility
import com.letta.mobile.avatar.core.AvatarLicense
import com.letta.mobile.avatar.core.AvatarManifestCodec
import com.letta.mobile.avatar.core.GlbContainer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AvatarImporterTest {
    private val catalogDir: Path = Files.createTempDirectory("avatar-import-test")

    @AfterTest
    fun cleanup() {
        catalogDir.toFile().deleteRecursively()
    }

    @Test
    fun importsGlbWritesLayoutAndRegistersCatalogEntry() = runTest {
        val bytes = glb("""{"asset":{"version":"2.0"},"materials":[{}],"textures":[{},{}]}""")
        val result = AvatarImporter(catalogDir).import(
            AvatarImportRequest(
                sourceFileName = "Test Prop.glb",
                bytes = bytes,
                license = AvatarLicense(licenseName = "CC0", allowRedistribution = true),
                visibility = AvatarImportVisibility.SHARED_CATALOG,
            ),
        )

        val imported = assertIs<AvatarImportResult.Imported>(result)
        assertEquals(AvatarFormat.GLB, imported.manifest.format)
        assertEquals(AvatarImportVisibility.SHARED_CATALOG, imported.visibility)
        assertTrue(imported.model.id.startsWith("test-prop-"))
        assertEquals(sha256(bytes), imported.manifest.sha256)
        assertEquals(1, imported.manifest.stats.materialCount)
        assertEquals(2, imported.manifest.stats.textureCount)

        // Files written where the layout promises.
        assertContentEquals(bytes, Files.readAllBytes(imported.assetPath))
        assertContentEquals(bytes, Files.readAllBytes(imported.preservedSourcePath))
        val manifestOnDisk = AvatarManifestCodec.decode(Files.readString(imported.manifestPath))
        assertEquals(imported.manifest, manifestOnDisk)

        // Catalog registry contains the entry.
        val entries = AvatarCatalogCodec.decode(
            Files.readString(catalogDir.resolve(AvatarCatalogCodec.FILE_NAME)),
        )
        assertEquals(listOf(imported.model), entries)
    }

    @Test
    fun importsVrm1WithNormalizedMetadata() = runTest {
        val bytes = glb(
            """
            {
              "asset": {"version": "2.0"},
              "extensionsUsed": ["VRMC_vrm"],
              "extensions": {
                "VRMC_vrm": {
                  "humanoid": {"humanBones": {"hips": {"node": 0}, "head": {"node": 1}}},
                  "expressions": {"preset": {"happy": {}, "aa": {}}}
                }
              }
            }
            """.trimIndent(),
        )
        val result = AvatarImporter(catalogDir).import(
            AvatarImportRequest(
                sourceFileName = "buddy.vrm",
                bytes = bytes,
                license = AvatarLicense(allowRedistribution = true),
            ),
        )

        val imported = assertIs<AvatarImportResult.Imported>(result)
        assertEquals(AvatarFormat.VRM_1, imported.manifest.format)
        assertEquals(listOf("happy"), imported.manifest.expressions)
        assertEquals(listOf("aa"), imported.manifest.visemes)
        assertTrue(imported.assetPath.fileName.toString().endsWith(".vrm"))
        assertTrue(imported.manifest.capabilities.supportsHumanoid)
    }

    @Test
    fun unknownLicenseIsDowngradedToLocalWithWarning() = runTest {
        val result = AvatarImporter(catalogDir).import(
            AvatarImportRequest(
                sourceFileName = "mystery.glb",
                bytes = glb("""{"asset":{"version":"2.0"}}"""),
                visibility = AvatarImportVisibility.PRIVATE_LOCAL,
            ),
        )

        val imported = assertIs<AvatarImportResult.Imported>(result)
        assertEquals(AvatarImportVisibility.PRIVATE_LOCAL, imported.visibility)
        assertTrue(imported.warnings.any { "Redistribution rights unknown" in it })
    }

    @Test
    fun unknownLicenseIsRejectedForSharedCatalogAndWritesNothing() = runTest {
        val result = AvatarImporter(catalogDir).import(
            AvatarImportRequest(
                sourceFileName = "mystery.glb",
                bytes = glb("""{"asset":{"version":"2.0"}}"""),
                visibility = AvatarImportVisibility.SHARED_CATALOG,
            ),
        )

        assertIs<AvatarImportResult.Rejected>(result)
        assertTrue(Files.notExists(catalogDir.resolve("assets")))
        assertTrue(Files.notExists(catalogDir.resolve(AvatarCatalogCodec.FILE_NAME)))
    }

    @Test
    fun structuralErrorsRejectTheImport() = runTest {
        // Missing asset.version → builtin inspector error.
        val result = AvatarImporter(catalogDir).import(
            AvatarImportRequest(
                sourceFileName = "broken.glb",
                bytes = glb("""{"scenes":[]}"""),
                license = AvatarLicense(allowRedistribution = true),
            ),
        )

        val rejected = assertIs<AvatarImportResult.Rejected>(result)
        assertTrue("asset.version" in rejected.reason)
        assertTrue(Files.notExists(catalogDir.resolve("assets")))
    }

    @Test
    fun nonGltfBytesAreRejected() = runTest {
        val result = AvatarImporter(catalogDir).import(
            AvatarImportRequest(sourceFileName = "cat.png", bytes = byteArrayOf(1, 2, 3)),
        )
        assertIs<AvatarImportResult.Rejected>(result)
    }

    @Test
    fun explicitIdWinsOverDerivedSlug() = runTest {
        val result = AvatarImporter(catalogDir).import(
            AvatarImportRequest(
                sourceFileName = "x.glb",
                bytes = glb("""{"asset":{"version":"2.0"}}"""),
                id = "my-avatar",
                displayName = "My Avatar",
                license = AvatarLicense(allowRedistribution = true),
            ),
        )
        val imported = assertIs<AvatarImportResult.Imported>(result)
        assertEquals("my-avatar", imported.model.id)
        assertEquals("My Avatar", imported.model.displayName)
    }

    @Test
    fun reimportingSameIdReplacesTheCatalogEntry() = runTest {
        val importer = AvatarImporter(catalogDir)
        val request = AvatarImportRequest(
            sourceFileName = "x.glb",
            bytes = glb("""{"asset":{"version":"2.0"}}"""),
            id = "same-id",
            license = AvatarLicense(allowRedistribution = true),
        )

        importer.import(request)
        AvatarImporter(catalogDir).import(request) // fresh importer, same dir

        val entries = AvatarCatalogCodec.decode(
            Files.readString(catalogDir.resolve(AvatarCatalogCodec.FILE_NAME)),
        )
        assertEquals(1, entries.size)
    }

    private fun glb(json: String): ByteArray {
        val jsonBytes = json.encodeToByteArray()
        val total = 12 + 8 + jsonBytes.size
        val out = ByteArray(total)
        var offset = 0
        fun writeU32(value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
            offset += 4
        }
        writeU32(GlbContainer.MAGIC)
        writeU32(2)
        writeU32(total)
        writeU32(jsonBytes.size)
        writeU32(GlbContainer.CHUNK_JSON)
        jsonBytes.copyInto(out, offset)
        return out
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
