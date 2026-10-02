package com.letta.mobile.data.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * The host's [PluginPackageReader] (letta-mobile-s416w.24): unpacks a `.lcp` zip in memory within
 * [PluginPackageLimits], refusing an unsafe entry name (a path traversal, an absolute path) before
 * reading it, and hashes the package so it is pinned by sha256 (R3). The layout and the manifest
 * are then held by [PluginPackages.assemble].
 */
class ZipPluginPackageReader(
    private val maxUnpackedBytes: Long = PluginPackageLimits.MAX_UNPACKED_BYTES,
) : PluginPackageReader {
    override fun read(bytes: ByteArray, expectedSha256: String?): PluginPackageResult {
        if (bytes.size > PluginPackageLimits.MAX_PACKAGE_BYTES) {
            return PluginPackageResult.Refused(PluginPackageProblemCode.TOO_LARGE, "a package is at most ${PluginPackageLimits.MAX_PACKAGE_BYTES} bytes (got ${bytes.size})")
        }
        val sha256 = sha256Hex(bytes)
        if (expectedSha256 != null && expectedSha256 != sha256) {
            return PluginPackageResult.Refused(PluginPackageProblemCode.HASH_MISMATCH, "the package hashes to $sha256, not the pinned $expectedSha256")
        }
        return try {
            when (val unpacked = Unpacker(maxUnpackedBytes).unpack(bytes)) {
                is Unpacked.Files -> PluginPackages.assemble(sha256, unpacked.entries)
                is Unpacked.Refused -> PluginPackageResult.Refused(unpacked.code, unpacked.message, unpacked.entry)
            }
        } catch (broken: ZipException) {
            PluginPackageResult.Refused(PluginPackageProblemCode.NOT_A_PACKAGE, "not a zip: ${broken.message}")
        } catch (broken: IOException) {
            PluginPackageResult.Refused(PluginPackageProblemCode.NOT_A_PACKAGE, "the package cannot be read: ${broken.message}")
        }
    }

    private sealed interface Unpacked {
        data class Files(val entries: Map<String, ByteArray>) : Unpacked

        data class Refused(val code: PluginPackageProblemCode, val message: String, val entry: String? = null) : Unpacked
    }

    /** One pass over the zip, keeping count of entries and unpacked bytes. */
    private class Unpacker(private val maxUnpackedBytes: Long) {
        private val entries = linkedMapOf<String, ByteArray>()
        private var unpackedBytes = 0L

        fun unpack(bytes: ByteArray): Unpacked {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    entryProblem(entry.name)?.let { return it }
                    entries[entry.name] = readCapped(zip) ?: return tooLarge()
                }
            }
            if (entries.isEmpty()) return Unpacked.Refused(PluginPackageProblemCode.NOT_A_PACKAGE, "the package is empty or not a zip")
            return Unpacked.Files(entries)
        }

        private fun entryProblem(name: String): Unpacked.Refused? = when {
            !PluginPackagePaths.isInside(name) -> Unpacked.Refused(PluginPackageProblemCode.UNSAFE_PATH, "'$name' points outside the package", name)
            name in entries -> Unpacked.Refused(PluginPackageProblemCode.DUPLICATE_ENTRY, "'$name' is in the package twice", name)
            entries.size >= PluginPackageLimits.MAX_ENTRIES ->
                Unpacked.Refused(PluginPackageProblemCode.TOO_MANY_ENTRIES, "a package has at most ${PluginPackageLimits.MAX_ENTRIES} files")
            else -> null
        }

        /** The entry's bytes, or null once the package unpacks past its cap. */
        private fun readCapped(zip: ZipInputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                unpackedBytes += read
                if (unpackedBytes > maxUnpackedBytes) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        private fun tooLarge() = Unpacked.Refused(PluginPackageProblemCode.TOO_LARGE, "the package unpacks to more than $maxUnpackedBytes bytes")
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
