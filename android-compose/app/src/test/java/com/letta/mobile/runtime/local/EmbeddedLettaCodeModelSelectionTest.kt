package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                localModelRuntime = "  LiteRT-LM  ",
                localModelAccelerator = "  CPU  ",
                localModelMaxTokens = 8192,
            )
        )

        assertEquals("google/gemma-3n", selection.modelHandle)
        assertEquals("/sdcard/models/gemma.litertlm", selection.modelPath)
        assertEquals("litert-lm", selection.runtime)
        assertEquals("cpu", selection.accelerator)
        assertEquals(8192, selection.maxTokens)
        assertEquals("google/gemma-3n", selection.openAiModelId)
        assertEquals("lmstudio/google/gemma-3n", selection.lettaCodeModelHandle)
    }

    @Test
    fun `selection falls back to local defaults`() {
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
        assertNull(selection.modelPath)
        assertEquals(EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_RUNTIME, selection.runtime)
        assertEquals(EmbeddedLettaCodeModelSelection.DEFAULT_ACCELERATOR, selection.accelerator)
        assertEquals(EmbeddedLettaCodeModelSelection.DEFAULT_MAX_TOKENS, selection.maxTokens)
        assertEquals("default", selection.openAiModelId)
    }

    @Test
    fun `start key changes when model selection changes`() {
        val first = EmbeddedLettaCodeModelSelection(
            modelHandle = "local/gemma-3n",
            modelPath = "/data/model-a.litertlm",
            runtime = "litert-lm",
            accelerator = "gpu",
            maxTokens = 4096,
        )
        val second = first.copy(modelPath = "/data/model-b.litertlm")

        assertEquals("local/gemma-3n|/data/model-a.litertlm|litert-lm|gpu|4096|", first.startKey)
        assertEquals("local/gemma-3n|/data/model-b.litertlm|litert-lm|gpu|4096|", second.startKey)
    }

    // letta-mobile-3icw7: custom OpenAI-compatible endpoint replaces the
    // on-device model entirely.
    @Test
    fun `custom provider config selects endpoint model and needs no model path`() {
        val selection = EmbeddedLettaCodeModelSelection.from(
            com.letta.mobile.data.model.LettaConfig(
                id = "local",
                mode = com.letta.mobile.data.model.LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
                localProviderBaseUrl = "http://192.168.1.10:8082/v1/",
                localProviderApiKey = " secret ",
                localProviderModel = "claude-proxy-model",
            )
        )

        assertEquals(true, selection.isCustomProvider)
        assertEquals("http://192.168.1.10:8082/v1", selection.customProviderBaseUrl)
        assertEquals("secret", selection.customProviderApiKey)
        assertEquals("lmstudio/claude-proxy-model", selection.lettaCodeModelHandle)
        assertEquals(null, selection.modelPath)
    }
}
