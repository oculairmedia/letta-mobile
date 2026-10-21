package com.letta.mobile.desktop.runtime

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-k5nbq.8: pinned MinGit download verifies SHA-256 and extracts `cmd/git.exe`.
 * letta-mobile-k5nbq.13: staged extract renamed into place, nothing left behind on failure.
 */
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
        assertEquals(emptyList(), root.listFiles().orEmpty().map { it.name }, "no zip or staging left behind")
    }

    @Test
    fun aFailedExtractNeverExposesAHalfTree() {
        // git.exe unpacks first, then a bad entry aborts: the poll must still see no cmd/git.exe.
        val zipBytes = zipOf("cmd/git.exe" to byteArrayOf(0x4d, 0x5a), "C:evil.dll" to byteArrayOf(1))
        assertFailsWith<IllegalStateException> { installer(zipBytes).install() }
        assertFalse(File(root, "test/cmd/git.exe").exists())
        assertEquals(emptyList(), root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun theVersionedTreeAppearsOnlyAfterTheDownloadIsVerified() {
        var seenDuringInstall: Boolean? = null
        PortableGitInstaller(
            root = root,
            release = PortableGitRelease("test", "https://example.test/mingit.zip", sha256 = "ab"),
            download = { _, dest -> dest.writeBytes(minGitZip()) },
            digest = { seenDuringInstall = File(root, "test").exists(); "ab" },
        ).install()
        assertEquals(false, seenDuringInstall)
        assertTrue(File(root, "test/cmd/git.exe").isFile)
    }

    @Test
    fun aTreeWithoutGitIsReplaced() {
        File(root, "test/cmd").mkdirs()
        File(root, "test/leftover.txt").writeText("half")
        val cmd = installer(minGitZip()).install()
        assertTrue(File(cmd, "git.exe").isFile)
        assertFalse(File(root, "test/leftover.txt").exists())
    }

    @Test
    fun theInstalledCmdIsFoundOffThePathAndLeadsTheChildPath() {
        val installer = installer(minGitZip())
        val cmd = installer.install()
        val status = DesktopGitProbe(
            DesktopGitProbeInputs(
                platform = DesktopGitPlatform.WINDOWS,
                inheritedPath = "C:/Windows/system32",
                currentPath = { null },
                wellKnownDirectories = listOf(installer.cmdDirectory()),
                isExecutable = { it.isFile },
            ),
        ).check()
        assertEquals(DesktopGitStatus.Available(cmd, onInheritedPath = false), status)

        val path = desktopRuntimeLaunchContext(
            DesktopRuntimeLaunchInputs(
                totalMemoryBytes = null,
                instanceId = "install-1",
                workingDirectory = root,
                extraPathDirectory = (status as DesktopGitStatus.Available).directory,
                inheritedPath = "C:/Windows/system32",
            ),
        ).environment["PATH"]
        assertEquals(cmd.absolutePath + File.pathSeparator + "C:/Windows/system32", path)
    }

    @Test
    fun matchingChecksumExtractsCmdGit() {
        val cmd = installer(minGitZip()).install()
        assertEquals(File(root, "test/cmd"), cmd)
        assertTrue(File(cmd, "git.exe").isFile)
    }

    @Test
    fun zipEntryThatEscapesTheDestIsRefused() {
        assertTrue(isUnsafeZipPath("../evil.exe"))
        assertTrue(isUnsafeZipPath("/etc/passwd"))
        assertTrue(isUnsafeZipPath("cmd/../../Windows/git.exe"))
        assertTrue(isUnsafeZipPath("C:Windows/git.exe"), "drive-relative")
        assertTrue(isUnsafeZipPath("C:\\Windows\\git.exe"), "drive-absolute")
        assertTrue(isUnsafeZipPath("cmd/git.exe:stream"), "alternate data stream")
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

    private fun installer(zipBytes: ByteArray) = PortableGitInstaller(
        root = root,
        release = PortableGitRelease("test", "https://example.test/mingit.zip", sha256 = "ab"),
        download = { _, dest -> dest.writeBytes(zipBytes) },
        digest = { "ab" },
    )

    private fun minGitZip(): ByteArray = zipOf("cmd/git.exe" to byteArrayOf(0x4d, 0x5a))

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
