package com.letta.mobile.runtime.local

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRtLmOnDeviceChatCompletionEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : OnDeviceChatCompletionEngine {
    private var activeKey: String? = null
    private var activeEngine: Engine? = null

    @Synchronized
    override fun generate(modelSelection: EmbeddedLettaCodeModelSelection, prompt: String): Result<String> =
        runCatching {
            val modelFile = modelSelection.modelPath
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?: error("No on-device model file has been imported.")
            check(modelFile.isFile) {
                "On-device model file was not found at ${modelFile.absolutePath}."
            }

            val engine = engineFor(modelSelection, modelFile)
            engine.createConversation().use { conversation ->
                conversation.sendMessage(prompt).toString()
            }
        }

    private fun engineFor(modelSelection: EmbeddedLettaCodeModelSelection, modelFile: File): Engine {
        val key = modelSelection.startKey
        activeEngine?.takeIf { activeKey == key }?.let { return it }

        runCatching { activeEngine?.close() }
        activeEngine = null
        activeKey = null

        val cacheDir = File(context.cacheDir, "litert-lm").apply { mkdirs() }
        val engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = modelSelection.accelerator.toLiteRtLmBackend(context),
                maxNumTokens = modelSelection.maxTokens,
                cacheDir = cacheDir.absolutePath,
            )
        )
        try {
            engine.initialize()
        } catch (error: Throwable) {
            runCatching { engine.close() }
            throw error
        }
        activeEngine = engine
        activeKey = key
        return engine
    }
}

private fun String.toLiteRtLmBackend(context: Context): Backend =
    when (trim().lowercase(Locale.US)) {
        "cpu" -> Backend.CPU()
        "npu",
        "tpu",
        -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        "gpu" -> Backend.GPU()
        else -> Backend.GPU()
    }
