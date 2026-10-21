package com.letta.mobile.desktop.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AccessDeniedException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        DesktopRuntimeLaunchPreference.extraPathDirectory = null
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
    fun aTreeFromAnUnmarkedInstallIsReplacedAndNotOfferedToTheProbe() {
        // An older non-atomic install (or a crash) can leave cmd/git.exe beside a half tree.
        val home = File(root, "home")
        val old = File(portableGitRoot(home), "${PortableGitRelease.Current.version}/cmd").apply { mkdirs() }
        File(old, "git.exe").writeText("half")
        assertNull(installedPortableGitCmdDirectory(home))

        val legacy = File(root, "test/cmd").apply { mkdirs() }
        File(legacy, "git.exe").writeText("half")
        installer(minGitZip()).install()
        assertContentEquals(byteArrayOf(0x4d, 0x5a), File(legacy, "git.exe").readBytes())
    }

    @Test
    fun stagingLeftByACrashIsClearedOnTheNextInstall() {
        File(root, ".test-123").mkdirs()
        File(root, ".test-456.zip").writeText("partial")
        installer(minGitZip()).install()
        assertEquals(listOf("test"), root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun aRenameHeldByAScannerIsRetriedThenGivesUp() {
        var attempts = 0
        val pauses = mutableListOf<Long>()
        moveWithRetry(root.toPath(), root.toPath(), move = { _, _ ->
            attempts += 1
            if (attempts < 3) throw AccessDeniedException("held")
        }, pause = { pauses += it })
        assertEquals(3, attempts)
        assertEquals(2, pauses.size)

        attempts = 0
        assertFailsWith<AccessDeniedException> {
            moveWithRetry(root.toPath(), root.toPath(), move = { _, _ -> attempts += 1; throw AccessDeniedException("held") }, pause = {})
        }
        assertEquals(5, attempts, "bounded")
    }

    @Test
    fun aDownloadPastItsDeadlineFails() {
        var clock = 0L
        val input = ByteArrayInputStream(ByteArray(300_000))
        val error = assertFailsWith<IllegalStateException> {
            copyBefore(deadline = 100, input = input, output = ByteArrayOutputStream(), now = { clock += 60; clock })
        }
        assertTrue(error.message.orEmpty().contains("did not finish in time"))
        val copied = ByteArrayOutputStream()
        copyBefore(deadline = Long.MAX_VALUE, input = ByteArrayInputStream(ByteArray(10)), output = copied)
        assertEquals(10, copied.size())
    }

    @Test
    fun anInstallPutsItsCmdFirstOnTheNextSpawnsPathAndRestartsOnce() {
        val home = File(root, "home")
        val release = PortableGitRelease("test", "https://example.test/mingit.zip", sha256 = "ab")
        var restarts = 0
        val monitor = DesktopGitEnvironmentMonitor(
            probe = {
                DesktopGitProbe(
                    DesktopGitProbeInputs(
                        platform = DesktopGitPlatform.WINDOWS,
                        inheritedPath = "C:/Windows/system32",
                        currentPath = { null },
                        wellKnownDirectories = listOfNotNull(installedPortableGitCmdDirectory(home, release)),
                        isExecutable = { it.isFile },
                    ),
                ).check()
            },
            runtimeActive = { true },
            restartRuntime = { restarts += 1 },
        )
        assertIs<DesktopGitStatus.Missing>(monitor.refresh())

        monitor.installPortableGit(installer(minGitZip(), portableGitRoot(home)))

        val cmd = portableGitCmdDirectory(home, release)
        assertEquals(DesktopGitStatus.Available(cmd, onInheritedPath = false), monitor.status.value)
        assertEquals(1, restarts)
        val path = DesktopRuntimeLaunchPreference.currentContext().environment.getValue("PATH")
        assertTrue(path.startsWith(cmd.absolutePath + File.pathSeparator), path)
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

    private fun installer(zipBytes: ByteArray, installRoot: File = root) = PortableGitInstaller(
        root = installRoot,
        release = PortableGitRelease("test", "https://example.test/mingit.zip", sha256 = "ab"),
        download = { _, dest -> dest.writeBytes(zipBytes) },
        digest = { "ab" },
    )

    private fun minGitZip(): ByteArray = zipOf("cmd/git.exe" to byteArrayOf(0x4d, 0x5a))

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
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
