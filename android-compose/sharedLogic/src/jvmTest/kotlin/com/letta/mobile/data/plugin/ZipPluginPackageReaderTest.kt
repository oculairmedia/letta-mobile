package com.letta.mobile.data.plugin

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The host's `.lcp` reader (letta-mobile-s416w.24): zip round trip, hash pinning, traversal and size caps. */
class ZipPluginPackageReaderTest {
    private val reader = ZipPluginPackageReader()

    private fun manifest(): String =
        checkNotNull(javaClass.getResource("/canvas/plugin/v1/manifest-example.json")).readText()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun example(): ByteArray = zip(
        PluginPackagePaths.MANIFEST to manifest().encodeToByteArray(),
        "plugin.jar" to byteArrayOf(1, 2, 3),
        "pages/widget.html" to "<html></html>".encodeToByteArray(),
    )

    private fun refusedWith(result: PluginPackageResult): List<PluginPackageProblemCode> =
        assertIs<PluginPackageResult.Refused>(result).problems.map { it.code }

    @Test
    fun aPackageRoundTripsThroughTheZipAndIsPinnedByItsHash() {
        val bytes = example()
        val sha = ZipPluginPackageReader.sha256Hex(bytes)
        val pkg = assertIs<PluginPackageResult.Read>(reader.read(bytes, expectedSha256 = sha)).pkg
        assertEquals(PluginPackageRef(sha), pkg.ref)
        assertEquals("letta.example", pkg.manifest.id)
        assertEquals(setOf(PluginPackagePaths.MANIFEST, "plugin.jar", "pages/widget.html"), pkg.paths)
        assertEquals(listOf<Byte>(1, 2, 3), pkg.file("plugin.jar")?.toList())
        assertEquals(listOf(PluginPackageProblemCode.HASH_MISMATCH), refusedWith(reader.read(bytes, expectedSha256 = "0".repeat(64))))
    }

    @Test
    fun aPathTraversalIsRefusedBeforeItIsRead() {
        val bytes = zip(PluginPackagePaths.MANIFEST to manifest().encodeToByteArray(), "../../etc/cron.d/x" to byteArrayOf(1))
        val refused = assertIs<PluginPackageResult.Refused>(reader.read(bytes, null))
        assertEquals(listOf(PluginPackageProblemCode.UNSAFE_PATH), refused.problems.map { it.code })
        assertEquals("../../etc/cron.d/x", refused.problems.single().entry)
        assertEquals(listOf(PluginPackageProblemCode.UNSAFE_PATH), refusedWith(reader.read(zip("/abs" to byteArrayOf(1)), null)))
    }

    @Test
    fun aPackageOverTheCapIsRefusedUnread() {
        val tooBig = ByteArray((PluginPackageLimits.MAX_PACKAGE_BYTES + 1).toInt())
        assertEquals(listOf(PluginPackageProblemCode.TOO_LARGE), refusedWith(reader.read(tooBig, null)))
    }

    @Test
    fun aPackageThatUnpacksPastItsCapIsRefused() {
        val small = ZipPluginPackageReader(maxUnpackedBytes = 64 * 1024)
        val bytes = zip(PluginPackagePaths.MANIFEST to manifest().encodeToByteArray(), "data/zeros.bin" to ByteArray(1024 * 1024))
        assertEquals(listOf(PluginPackageProblemCode.TOO_LARGE), refusedWith(small.read(bytes, null)))
        assertEquals(4 * PluginPackageLimits.MAX_PACKAGE_BYTES, PluginPackageLimits.MAX_UNPACKED_BYTES)
    }

    @Test
    fun somethingThatIsNotAPackageIsRefused() {
        assertEquals(listOf(PluginPackageProblemCode.NOT_A_PACKAGE), refusedWith(reader.read("not a zip".encodeToByteArray(), null)))
        assertEquals(listOf(PluginPackageProblemCode.NO_MANIFEST), refusedWith(reader.read(zip("plugin.jar" to byteArrayOf(1)), null)))
    }
}
