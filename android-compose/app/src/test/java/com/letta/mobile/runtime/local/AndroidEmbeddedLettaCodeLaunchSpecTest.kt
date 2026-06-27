package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.sensors.DeviceSensorGroundingWriter
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshot
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshotProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEmbeddedLettaCodeLaunchSpecTest {
    @Test
    fun `shared launch spec preserves Android env bridge preload and stream-json fallback parity`() {
        val baseDir = tempDir("letta-launch-parity")
        val project = preparedProject(baseDir, withNpm = true)
        val sensorGrounding = DeviceSensorGroundingWriter(
            provider = object : DeviceSensorSnapshotProvider {
                override fun snapshot(nowMillis: Long): DeviceSensorSnapshot =
                    DeviceSensorSnapshot(capturedAtMillis = nowMillis)
            },
            outputFile = File(baseDir, "device-sensor-grounding.json"),
        )
        val selection = EmbeddedLettaCodeModelSelection(
            modelHandle = "google/gemma-3n-E2B-it-litert-lm",
            modelPath = File(baseDir, "gemma.litertlm").apply { writeText("model") }.absolutePath,
            runtime = "litert-lm",
            accelerator = "gpu",
            maxTokens = 2048,
        )

        val spec = project.prepareAndroidEmbeddedLettaCodeLaunchSpec(
            modelSelection = selection,
            onDeviceProviderBaseUrl = "http://127.0.0.1:3456/v1",
            onDeviceProviderApiKey = "openai-loopback-token",
            androidNetworkBridgeBaseUrl = "http://127.0.0.1:4567",
            androidNetworkBridgeToken = "android-bridge-token",
            deviceSensorGroundingWriter = sensorGrounding,
            toolInstaller = FakeToolInstaller(availableCommands = setOf("curl", "node", "npm", "git")),
        )
        val request = spec.toStreamJsonNodeStartRequest(
            EmbeddedLettaCodeSessionKey(
                agentId = "agent-1",
                conversationId = "conv-1",
                modelKey = selection.startKey,
            ),
        )
        val env = spec.environment
        val binDir = File(project.storageDirectory.parentFile, "bin")
        val backgroundDir = File(project.storageDirectory.parentFile, "background")
        val tmpDir = File(project.storageDirectory.parentFile, "tmp")

        assertEquals("lmstudio/google/gemma-3n-E2B-it-litert-lm", spec.modelHandle)
        assertEquals(project.storageDirectory.absolutePath, env["LETTA_LOCAL_BACKEND_DIR"])
        assertEquals("1", env["LETTA_LOCAL_BACKEND_EXPERIMENTAL"])
        assertEquals("pi", env["LETTA_LOCAL_BACKEND_EXECUTOR"])
        assertEquals(spec.modelHandle, env["LETTA_ANDROID_ON_DEVICE_MODEL_HANDLE"])
        assertEquals(selection.modelPath, env["LETTA_ANDROID_ON_DEVICE_MODEL_PATH"])
        assertEquals("litert-lm", env["LETTA_ANDROID_ON_DEVICE_MODEL_RUNTIME"])
        assertEquals("gpu", env["LETTA_ANDROID_ON_DEVICE_MODEL_ACCELERATOR"])
        assertEquals("2048", env["LETTA_ANDROID_ON_DEVICE_MODEL_MAX_TOKENS"])
        assertEquals(
            File(project.storageDirectory, "model-cache").absolutePath,
            env["LETTA_ANDROID_ON_DEVICE_MODEL_CACHE_DIR"],
        )
        assertEquals("http://127.0.0.1:3456/v1", env["LMSTUDIO_BASE_URL"])
        assertEquals("openai-loopback-token", env["LMSTUDIO_API_KEY"])
        assertEquals("http://127.0.0.1:4567", env["LETTA_ANDROID_NETWORK_BRIDGE_URL"])
        assertEquals("android-bridge-token", env["LETTA_ANDROID_NETWORK_BRIDGE_TOKEN"])
        assertEquals(sensorGrounding.outputFile.absolutePath, env["LETTA_MOBILE_DEVICE_SENSOR_GROUNDING_PATH"])
        assertEquals(tmpDir.absolutePath, env["TMPDIR"])
        assertEquals(tmpDir.absolutePath, env["TMP"])
        assertEquals(tmpDir.absolutePath, env["TEMP"])
        assertEquals(backgroundDir.absolutePath, env["LETTA_BACKGROUND_DIR"])
        assertEquals(backgroundDir.absolutePath, env["LETTA_TASK_OUTPUT_DIR"])
        assertEquals("/system/bin/sh", env["SHELL"])
        assertEquals("--max-old-space-size=384 --max-semi-space-size=16", env["NODE_OPTIONS"])
        assertTrue(env["PATH"].orEmpty().startsWith("${binDir.absolutePath}:"))
        assertEquals(
            File(project.projectDir, "node_modules/npm/bin/npm-cli.js").absolutePath,
            env["LETTA_NPM_CLI_JS"],
        )
        assertEquals(File(binDir, "node").absolutePath, env["LETTA_NODE_BIN"])
        assertEquals(binDir.absolutePath, env["GIT_EXEC_PATH"])
        assertEquals("", env["GIT_TEMPLATE_DIR"])
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
        assertNull(env["LETTA_LOCAL_BACKEND_NO_MEMFS"])
        val disabledHooksPath = File(baseDir, "git-hooks-disabled").absolutePath
        assertTrue(File(project.homeDirectory, ".gitconfig").readText().contains("hooksPath = $disabledHooksPath"))

        assertEquals(
            listOf(
                "regexp-polyfill.cjs",
                "android-network-polyfill.cjs",
                "embedded-runtime-introspection-preload.cjs",
            ),
            spec.preloadRequireFiles.map { it.name },
        )
        assertStreamJsonArgs(request, spec)
    }

    @Test
    fun `shared launch spec disables memfs when packaged git is unavailable but keeps Android path setup`() {
        val baseDir = tempDir("letta-launch-no-git")
        val project = preparedProject(baseDir, withNpm = false)
        val selection = EmbeddedLettaCodeModelSelection(
            modelHandle = "lmstudio/minimax-m3",
            modelPath = "/models/minimax.litertlm",
            runtime = "litert-lm",
            accelerator = "cpu",
            maxTokens = 4096,
        )

        val spec = project.prepareAndroidEmbeddedLettaCodeLaunchSpec(
            modelSelection = selection,
            androidNetworkBridgeBaseUrl = "http://127.0.0.1:4567",
            androidNetworkBridgeToken = "android-bridge-token",
            toolInstaller = FakeToolInstaller(availableCommands = setOf("bash", "curl", "node")),
        )
        val env = spec.environment

        assertEquals("lmstudio/minimax-m3", spec.modelHandle)
        assertEquals("1", env["LETTA_LOCAL_BACKEND_NO_MEMFS"])
        assertFalse(File(project.homeDirectory, ".gitconfig").exists())
        assertNull(env["LETTA_NPM_CLI_JS"])
        assertNull(env["LETTA_NODE_BIN"])
        assertTrue(env["PATH"].orEmpty().startsWith("${File(baseDir, "bin").absolutePath}:"))
        assertEquals("/system/bin/sh", env["SHELL"])
        assertEquals("http://127.0.0.1:4567", env["LETTA_ANDROID_NETWORK_BRIDGE_URL"])
        assertEquals("android-bridge-token", env["LETTA_ANDROID_NETWORK_BRIDGE_TOKEN"])
    }

    private fun assertStreamJsonArgs(
        request: LettaCodeNodeStartRequest,
        spec: AndroidEmbeddedLettaCodeLaunchSpec,
    ) {
        assertEquals(spec.environment, request.environment)
        assertEquals(spec.workingDirectory, request.workingDirectory)
        assertEquals("node", request.arguments[0])
        assertEquals("--max-old-space-size=384", request.arguments[1])
        assertEquals("--max-semi-space-size=16", request.arguments[2])
        val requirePairs = spec.preloadRequireFiles.flatMap { listOf("--require", it.absolutePath) }
        assertEquals(requirePairs, request.arguments.drop(3).take(requirePairs.size))
        assertTrue(
            request.arguments.containsAll(
                listOf("--backend", "local", "--input-format", "stream-json", "--output-format", "stream-json"),
            ),
        )
        assertTrue(request.arguments.containsAll(listOf("--permission-mode", "unrestricted")))
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
