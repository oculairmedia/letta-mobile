package com.letta.mobile.runtime.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real LiteRT-LM inference smoke, accelerator-parameterized:
 *   -e localModelAccelerator gpu   (default cpu)
 *
 * Exists to reproduce/pin accelerator-specific native crashes — a GPU
 * delegate abort kills the whole app process, so this must stay a
 * dedicated run, not part of the tier loop.
 */
class LiteRtLmEngineDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun realEngineGeneratesText() {
        val model = File(context.filesDir, "embedded-lettacode/models")
            .walkTopDown()
            .firstOrNull { it.isFile && it.extension == "litertlm" }
        assumeTrue("requires a .litertlm under filesDir/embedded-lettacode/models", model != null)
        requireNotNull(model)
        val accelerator = InstrumentationRegistry.getArguments()
            .getString("localModelAccelerator") ?: "cpu"

        val engine = LiteRtLmOnDeviceChatCompletionEngine(context)
        val result = engine.generate(
            EmbeddedLettaCodeModelSelection(
                modelHandle = "local/${model.nameWithoutExtension}",
                modelPath = model.absolutePath,
                runtime = EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_RUNTIME,
                accelerator = accelerator,
                maxTokens = 256,
            ),
            "user: Reply with the single word ok.",
        )

        assertTrue(
            "generate($accelerator) failed: ${result.exceptionOrNull()}",
            result.isSuccess && result.getOrThrow().isNotBlank(),
        )
    }
}
