package com.letta.mobile.runtime.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidEmbeddedLettaCodeLaunchSpecTest {
    @Test
    fun `app server launch puts npm and the git-backed memfs helpers on the device path`() {
        val baseDir = tempDir("letta-launch-tools")
        val project = preparedProject(baseDir, withNpm = true)

        val env = project.androidLocalAppServerLaunch(
            networkBridge = AndroidNetworkBridgeEndpoint("http://127.0.0.1:4567", "android-bridge-token"),
            toolInstaller = FakeToolInstaller(availableCommands = setOf("curl", "node", "npm", "git")),
        ).environment
        val binDir = File(baseDir, "bin")

        assertEquals("pi", env["LETTA_LOCAL_BACKEND_EXECUTOR"])
        assertEquals("/system/bin/sh", env["SHELL"])
        assertTrue(env["PATH"].orEmpty().startsWith("${binDir.absolutePath}:"))
        assertEquals(File(project.projectDir, "node_modules/npm/bin/npm-cli.js").absolutePath, env["LETTA_NPM_CLI_JS"])
        assertEquals(File(binDir, "node").absolutePath, env["LETTA_NODE_BIN"])
        assertEquals(binDir.absolutePath, env["GIT_EXEC_PATH"])
        assertEquals("", env["GIT_TEMPLATE_DIR"])
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
        val disabledHooksPath = File(baseDir, "git-hooks-disabled").absolutePath
        assertTrue(File(project.homeDirectory, ".gitconfig").readText().contains("hooksPath = $disabledHooksPath"))
    }

    @Test
    fun `app server launch skips git setup when packaged git is unavailable`() {
        val baseDir = tempDir("letta-launch-no-git")
        val project = preparedProject(baseDir, withNpm = false)

        val env = project.androidLocalAppServerLaunch(
            networkBridge = AndroidNetworkBridgeEndpoint("http://127.0.0.1:4567", "android-bridge-token"),
            toolInstaller = FakeToolInstaller(availableCommands = setOf("bash", "curl", "node")),
        ).environment

        assertNull(env["GIT_EXEC_PATH"])
        assertFalse(File(project.homeDirectory, ".gitconfig").exists())
        assertNull(env["LETTA_NPM_CLI_JS"])
        assertTrue(env["PATH"].orEmpty().startsWith("${File(baseDir, "bin").absolutePath}:"))
    }

    @Test
    fun `local provider file points lc-lmstudio at the endpoint without a models list`() {
        val backendDir = tempDir("letta-provider-auth")

        backendDir.writeLocalProviderAuth(ProviderEndpoint("http://127.0.0.1:3456/v1", "loopback-token"))

        val provider = kotlinx.serialization.json.Json.parseToJsonElement(File(backendDir, "providers/auth.json").readText())
            .jsonObject.getValue("providers").jsonObject.getValue("lc-lmstudio").jsonObject
        assertEquals("lmstudio", provider.getValue("provider_type").jsonPrimitive.content)
        assertEquals("http://127.0.0.1:3456/v1", provider.getValue("base_url").jsonPrimitive.content)
        assertEquals("loopback-token", provider.getValue("auth").jsonObject.getValue("key").jsonPrimitive.content)
        assertFalse(provider.containsKey("models"))
    }

    @Test
    fun `app server launch runs letta js behind the compat preloads with the device environment`() {
        val baseDir = tempDir("letta-app-server-launch")
        val project = preparedProject(baseDir, withNpm = false)

        val launch = project.androidLocalAppServerLaunch(
            networkBridge = AndroidNetworkBridgeEndpoint("http://127.0.0.1:4567", "android-bridge-token"),
            toolInstaller = FakeToolInstaller(availableCommands = setOf("curl", "node", "git")),
        )
        val env = launch.environment

        assertEquals(
            "the host appends `server …`; node18-compat first, no device-tools preload",
            listOf("node", "--max-old-space-size=512", "--max-semi-space-size=16") +
                listOf("node18-compat.cjs", "regexp-polyfill.cjs", "android-network-polyfill.cjs")
                    .flatMap { listOf("--require", File(project.projectDir, it).absolutePath) } +
                project.entrypoint.absolutePath,
            launch.command,
        )
        assertEquals(project.storageDirectory.absolutePath, launch.backendDir)
        assertEquals(project.workingDirectory.absolutePath, launch.workingDirectory)
        assertEquals("--max-old-space-size=512 --max-semi-space-size=16", env["NODE_OPTIONS"])
        assertEquals(project.homeDirectory.absolutePath, env["HOME"])
        assertEquals("http://127.0.0.1:4567", env["LETTA_ANDROID_NETWORK_BRIDGE_URL"])
        assertEquals("android-bridge-token", env["LETTA_ANDROID_NETWORK_BRIDGE_TOKEN"])
        assertEquals(File(baseDir, "bin").absolutePath, env["GIT_EXEC_PATH"])
        assertEquals(File(baseDir, "tmp").absolutePath, env["TMPDIR"])
        assertTrue(env.keys.none { it.startsWith("LMSTUDIO_") || it.startsWith("LETTA_ANDROID_ON_DEVICE_MODEL") })
        assertNull("the host owns the backend variables", env["LETTA_LOCAL_BACKEND_DIR"])
    }

    private fun preparedProject(baseDir: File, withNpm: Boolean): PreparedLettaCodeProject {
        val projectDir = File(baseDir, "nodejs-project").apply { mkdirs() }
        val entrypoint = File(projectDir, "letta.js").apply { writeText("") }
        if (withNpm) {
            File(projectDir, "node_modules/npm/bin").mkdirs()
            File(projectDir, "node_modules/npm/bin/npm-cli.js").writeText("")
        }
        return PreparedLettaCodeProject(
            projectDir = projectDir,
            entrypoint = entrypoint,
            workingDirectory = File(baseDir, "workdir"),
            storageDirectory = File(baseDir, "storage"),
            homeDirectory = File(baseDir, "home"),
        )
    }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }

    private class FakeToolInstaller(
        private val availableCommands: Set<String>,
    ) : AndroidEmbeddedToolInstaller {
        override fun ensureBashLink(binDirectory: File) {
            File(binDirectory, "bash").apply {
                parentFile?.mkdirs()
                writeText("bash")
                setExecutable(true, false)
            }
        }

        override fun linkPackagedTool(
            binDirectory: File,
            commandName: String,
            libraryName: String,
            label: String,
        ): Boolean {
            if (commandName !in availableCommands) return false
            File(binDirectory, commandName).apply {
                parentFile?.mkdirs()
                writeText(libraryName)
                setExecutable(true, false)
            }
            return true
        }
    }
}
