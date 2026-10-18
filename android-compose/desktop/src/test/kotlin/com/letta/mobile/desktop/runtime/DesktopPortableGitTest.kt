package com.letta.mobile.desktop.runtime

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.8: pinned MinGit download verifies SHA-256 and extracts `cmd/git.exe`. */
class DesktopPortableGitTest {
    private val root = createTempDirectory("portable-git").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun checksumMismatchFailsClosed() {
        val zipBytes = minGitZip()
        val release = PortableGitRelease("test", "https://example.test/mingit.zip", sha256 = "00".repeat(32))
        val error = assertFailsWith<IllegalStateException> {
            PortableGitInstaller(
                root = root,
                release = release,
                download = { _, dest -> dest.writeBytes(zipBytes) },
                digest = { "ff".repeat(32) },
            ).install()
        }
        assertTrue(error.message.orEmpty().contains("checksum mismatch"))
        assertTrue(root.walkTopDown().none { it.name == "git.exe" })
    }

    @Test
    fun matchingChecksumExtractsCmdGit() {
        val zipBytes = minGitZip()
        val cmd = PortableGitInstaller(
            root = root,
            release = PortableGitRelease("test", "https://example.test/mingit.zip", sha256 = "ab"),
            download = { _, dest -> dest.writeBytes(zipBytes) },
            digest = { "ab" },
        ).install()
        assertEquals(File(root, "test/cmd"), cmd)
        assertTrue(File(cmd, "git.exe").isFile)
    }

    @Test
    fun zipEntryThatEscapesTheDestIsRefused() {
        assertTrue(isUnsafeZipPath("../evil.exe"))
        assertTrue(isUnsafeZipPath("/etc/passwd"))
        assertTrue(isUnsafeZipPath("cmd/../../Windows/git.exe"))
        assertTrue(!isUnsafeZipPath("cmd/git.exe"))
    }

    @Test
    fun portableGitCmdSitsUnderTheVersionedAppDataDir() {
        val home = File(root, "home")
        assertEquals(
            File(home, ".letta-mobile/portable-git/2.51.0/cmd"),
            portableGitCmdDirectory(home),
        )
    }

    private fun minGitZip(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("cmd/git.exe"))
            zip.write(byteArrayOf(0x4d, 0x5a))
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }
}
