package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLettaCodeModelSelectionTest {
    @Test
    fun `selection trims configured local model fields`() {
        val selection = EmbeddedLettaCodeModelSelection.from(
            LettaConfig(
                id = "local",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
                localModelPath = "  /sdcard/models/gemma.litertlm  ",
                localModelHandle = "  google/gemma-3n  ",
                localModelRuntime = "  litert-lm  ",
                localModelAccelerator = "  cpu  ",
                localModelMaxTokens = 8192,
            )
        )

        assertEquals("google/gemma-3n", selection.modelHandle)
        assertEquals("/sdcard/models/gemma.litertlm", selection.modelPath)
        assertEquals("litert-lm", selection.runtime)
        assertEquals("cpu", selection.accelerator)
        assertEquals(8192, selection.maxTokens)
    }

    @Test
    fun `selection falls back to Gallery-inspired local defaults`() {
        val selection = EmbeddedLettaCodeModelSelection.from(
            LettaConfig(
                id = "local",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
                localModelPath = " ",
                localModelHandle = " ",
                localModelRuntime = null,
                localModelAccelerator = null,
                localModelMaxTokens = -1,
            )
        )

        assertEquals(EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_HANDLE, selection.modelHandle)
        assertEquals(null, selection.modelPath)
        assertEquals(EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_RUNTIME, selection.runtime)
        assertEquals(EmbeddedLettaCodeModelSelection.DEFAULT_ACCELERATOR, selection.accelerator)
        assertEquals(EmbeddedLettaCodeModelSelection.DEFAULT_MAX_TOKENS, selection.maxTokens)
    }

    @Test
    fun `start request passes model args env hints and model cache directory`() {
        val root = Files.createTempDirectory("letta-code-start-request").toFile()
        try {
            val project = PreparedLettaCodeProject(
                projectDir = File(root, "project"),
                entrypoint = File(root, "project/letta.js"),
                workingDirectory = File(root, "workdir"),
                storageDirectory = File(root, "local-backend"),
                homeDirectory = File(root, "home"),
            )
            val selection = EmbeddedLettaCodeModelSelection(
                modelHandle = "google/gemma-3n",
                modelPath = "/sdcard/models/gemma.litertlm",
                runtime = "litert-lm",
                accelerator = "gpu",
                maxTokens = 32768,
            )

            val request = project.toLettaCodeNodeStartRequest(
                session = EmbeddedLettaCodeSessionKey(
                    agentId = "agent-1",
                    conversationId = "conv-1",
                    modelKey = selection.startKey,
                ),
                modelSelection = selection,
            )

            assertEquals(
                listOf(
                    "node",
                    project.entrypoint.absolutePath,
                    "--backend",
                    "local",
                    "--model",
                    "google/gemma-3n",
                    "--agent",
                    "agent-1",
                    "--conversation",
                    "conv-1",
                    "--input-format",
                    "stream-json",
                    "--output-format",
                    "stream-json",
                ),
                request.arguments,
            )
            assertEquals(project.homeDirectory.absolutePath, request.environment["HOME"])
            assertEquals(project.storageDirectory.absolutePath, request.environment["LETTA_LOCAL_BACKEND_DIR"])
            assertEquals("1", request.environment["LETTA_LOCAL_BACKEND_EXPERIMENTAL"])
            assertEquals("pi", request.environment["LETTA_LOCAL_BACKEND_EXECUTOR"])
            assertEquals("google/gemma-3n", request.environment["LETTA_ANDROID_ON_DEVICE_MODEL_HANDLE"])
            assertEquals("/sdcard/models/gemma.litertlm", request.environment["LETTA_ANDROID_ON_DEVICE_MODEL_PATH"])
            assertEquals("litert-lm", request.environment["LETTA_ANDROID_ON_DEVICE_MODEL_RUNTIME"])
            assertEquals("gpu", request.environment["LETTA_ANDROID_ON_DEVICE_MODEL_ACCELERATOR"])
            assertEquals("32768", request.environment["LETTA_ANDROID_ON_DEVICE_MODEL_MAX_TOKENS"])
            assertEquals("1", request.environment["LETTA_LOCAL_BACKEND_EXPERIMENTAL"])
            assertTrue(File(request.environment.getValue("LETTA_ANDROID_ON_DEVICE_MODEL_CACHE_DIR")).isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `start request omits path env when model path is blank`() {
        val root = Files.createTempDirectory("letta-code-start-request").toFile()
        try {
            val project = PreparedLettaCodeProject(
                projectDir = File(root, "project"),
                entrypoint = File(root, "project/letta.js"),
                workingDirectory = File(root, "workdir"),
                storageDirectory = File(root, "local-backend"),
                homeDirectory = File(root, "home"),
            )
            val selection = EmbeddedLettaCodeModelSelection.from(
                LettaConfig(
                    id = "local",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local-lettacode://device",
                )
            )

            val request = project.toLettaCodeNodeStartRequest(
                session = EmbeddedLettaCodeSessionKey(
                    agentId = "agent-1",
                    conversationId = "conv-1",
                    modelKey = selection.startKey,
                ),
                modelSelection = selection,
            )

            assertFalse(request.environment.containsKey("LETTA_ANDROID_ON_DEVICE_MODEL_PATH"))
        } finally {
            root.deleteRecursively()
        }
    }
}
