package com.letta.mobile.desktop.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.1 (G07): finding git for the runtime, and restarting once after an install. */
class DesktopGitEnvironmentTest {
    private val programFilesGit = File("C:/Program Files/Git/cmd")

    private fun windows(
        inherited: String = "C:/Windows/system32",
        current: String? = null,
        installed: Set<File> = emptySet(),
    ) = DesktopGitProbe(
        DesktopGitProbeInputs(
            platform = DesktopGitPlatform.WINDOWS,
            inheritedPath = inherited,
            currentPath = { current },
            wellKnownDirectories = listOf(programFilesGit),
            isExecutable = { it in installed },
        ),
    )

    @Test
    fun gitOnTheInheritedPathIsUsedAsIs() {
        val status = windows(inherited = "C:/Windows;C:/Tools/Git/cmd", installed = setOf(File("C:/Tools/Git/cmd/git.exe"))).check()
        assertEquals(DesktopGitStatus.Available(File("C:/Tools/Git/cmd"), onInheritedPath = true), status)
    }

    @Test
    fun gitInstalledAfterLaunchIsFoundOffThePath() {
        val status = windows(installed = setOf(File(programFilesGit, "git.exe"))).check()
        assertEquals(DesktopGitStatus.Available(programFilesGit, onInheritedPath = false), status)
    }

    @Test
    fun theRegistryPathFindsAnInstallInACustomDirectory() {
        val status = windows(current = "D:/Git/cmd", installed = setOf(File("D:/Git/cmd/git.exe"))).check()
        assertEquals(DesktopGitStatus.Available(File("D:/Git/cmd"), onInheritedPath = false), status)
    }

    @Test
    fun noGitAnywhereIsMissingWithTheInstallPage() {
        val status = assertIs<DesktopGitStatus.Missing>(windows().check())
        assertEquals("https://git-scm.com/download/win", status.platform.installUrl)
    }

    @Test
    fun theMacGitStubDoesNotCountWithoutTheCommandLineTools() {
        val stub = File("/usr/bin/git")
        val tools = File("/Library/Developer/CommandLineTools/usr/bin/git")
        fun mac(installed: Set<File>) = DesktopGitProbe(
            DesktopGitProbeInputs(
                platform = DesktopGitPlatform.MAC,
                inheritedPath = "/usr/bin:/bin",
                currentPath = { null },
                wellKnownDirectories = emptyList(),
                isExecutable = { it in installed },
                macDeveloperToolsGit = listOf(tools),
            ),
        ).check()

        assertIs<DesktopGitStatus.Missing>(mac(setOf(stub)))
        assertIs<DesktopGitStatus.Available>(mac(setOf(stub, tools)))
    }

    @Test
    fun anInstallRestartsTheRunningRuntimeOnceWithGitOnItsPath() {
        var status: DesktopGitStatus = DesktopGitStatus.Missing(DesktopGitPlatform.WINDOWS)
        var restarts = 0
        var pathDirectory: File? = null
        val monitor = DesktopGitEnvironmentMonitor(
            probe = { status },
            runtimeActive = { true },
            restartRuntime = { restarts += 1 },
            applyPathDirectory = { pathDirectory = it },
        )

        monitor.refresh()
        assertEquals(0, restarts, "missing git alone does not restart")
        assertNull(pathDirectory)

        status = DesktopGitStatus.Available(programFilesGit, onInheritedPath = false)
        monitor.refresh()
        monitor.refresh()

        assertEquals(1, restarts, "the install restarts the runtime exactly once")
        assertEquals(programFilesGit, pathDirectory)
        assertEquals(status, monitor.status.value)
    }

    @Test
    fun gitAlreadyOnThePathNeverRestarts() {
        var restarts = 0
        val monitor = DesktopGitEnvironmentMonitor(
            probe = { DesktopGitStatus.Available(File("C:/Git/cmd"), onInheritedPath = true) },
            runtimeActive = { true },
            restartRuntime = { restarts += 1 },
            applyPathDirectory = {},
        )
        monitor.refresh()
        assertEquals(0, restarts)
    }

    @Test
    fun anInstallWhileTheRuntimeIsOffOnlyFixesTheNextSpawn() {
        var restarts = 0
        var status: DesktopGitStatus = DesktopGitStatus.Missing(DesktopGitPlatform.WINDOWS)
        var pathDirectory: File? = null
        val monitor = DesktopGitEnvironmentMonitor({ status }, { false }, { restarts += 1 }, { pathDirectory = it })
        monitor.refresh()
        status = DesktopGitStatus.Available(programFilesGit, onInheritedPath = false)
        monitor.refresh()

        assertEquals(0, restarts)
        assertEquals(programFilesGit, pathDirectory)
    }

    @Test
    fun regQueryOutputIsParsedAndExpanded() {
        val output = """

            HKEY_CURRENT_USER\Environment
                Path    REG_EXPAND_SZ    %USERPROFILE%\bin;C:\Program Files\Git\cmd

        """.trimIndent()
        val raw = parseRegQueryPath(output)
        assertEquals("%USERPROFILE%\\bin;C:\\Program Files\\Git\\cmd", raw)
        assertEquals(
            "C:\\Users\\me\\bin;C:\\Program Files\\Git\\cmd",
            expandWindowsVariables(raw!!) { if (it == "USERPROFILE") "C:\\Users\\me" else null },
        )
        assertTrue(expandWindowsVariables("%NOPE%\\x") { null }.startsWith("%NOPE%"))
        assertNull(parseRegQueryPath("ERROR: The system was unable to find the specified registry key or value."))
    }
}
