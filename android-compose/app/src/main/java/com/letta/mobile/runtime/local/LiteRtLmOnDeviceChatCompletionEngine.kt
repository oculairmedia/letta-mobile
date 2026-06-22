package com.letta.mobile.runtime.local

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
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
    override fun generate(
        modelSelection: EmbeddedLettaCodeModelSelection,
        prompt: String,
        images: List<OnDeviceImage>,
    ): Result<String> =
        runCatching {
            logLiteRtInfo(
                "model_handle_received",
                "handle=${modelSelection.modelHandle} runtime=${modelSelection.runtime} accelerator=${modelSelection.accelerator} maxTokens=${modelSelection.maxTokens}",
            )
            val modelFile = modelSelection.modelPath
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?: error("No on-device model file has been imported.")
            logLiteRtInfo(
                "model_path_resolved",
                "path=${modelFile.absolutePath} exists=${modelFile.exists()} isFile=${modelFile.isFile} size=${modelFile.takeIf { it.exists() }?.length() ?: -1L}",
            )
            check(modelFile.isFile) {
                "On-device model file was not found at ${modelFile.absolutePath}."
            }

            val sanitizedPrompt = sanitizeForLiteRt(prompt)
            val request = LiteRtLmRequest.from(sanitizedPrompt, images)
            logLiteRtInfo(
                "inference_start",
                "promptChars=${sanitizedPrompt.length} images=${images.size} requiresVision=${request.requiresVision}",
            )
            val engine = engineFor(modelSelection, modelFile, request.requiresVision)
            val response = engine.instance.createConversation().use { conversation ->
                if (shouldSendVisionContent(request, engine.visionEnabled)) {
                    conversation.sendMessage(request.toContents()).toString()
                } else {
                    conversation.sendMessage(sanitizedPrompt).toString()
                }
            }
            logLiteRtInfo("first_token", "preview=${response.take(FIRST_TOKEN_LOG_CHARS).lineSafe()}")
            logLiteRtInfo("inference_complete", "responseChars=${response.length}")
            response
        }.onFailure { error ->
            logLiteRtError("inference_error", "handle=${modelSelection.modelHandle}", error)
        }

    private fun engineFor(
        modelSelection: EmbeddedLettaCodeModelSelection,
        modelFile: File,
        requiresVision: Boolean,
    ): LiteRtLmEngineHandle {
        val key = liteRtLmEngineCacheKey(modelSelection, visionEnabled = requiresVision)
        activeEngine?.takeIf { activeKey == key }?.let { return LiteRtLmEngineHandle(it, requiresVision) }

        runCatching { activeEngine?.close() }
        activeEngine = null
        activeKey = null

        val requestedBackend = modelSelection.accelerator.toLiteRtLmBackend(context)
        logLiteRtInfo(
            "engine_init_start",
            "handle=${modelSelection.modelHandle} key=$key backend=${requestedBackend.javaClass.simpleName} vision=$requiresVision path=${modelFile.absolutePath}",
        )
        val engine = try {
            initializedEngine(modelFile, requestedBackend, modelSelection.maxTokens, visionEnabled = requiresVision)
        } catch (error: Throwable) {
            logLiteRtError(
                "engine_init_error",
                "handle=${modelSelection.modelHandle} backend=${requestedBackend.javaClass.simpleName} vision=$requiresVision path=${modelFile.absolutePath}",
                error,
            )
            // GPU is unusable on some devices (e.g. OpenCL blocked for apps,
            // OpenGL delegate unimplemented: "CreateSharedMemoryManager is
            // not implemented" on Tensor) and selecting it bricked every
            // turn. Fall back to CPU instead of failing the session.
            if (requiresVision) {
                android.util.Log.w(
                    "LiteRtLmEngine",
                    "Vision engine initialization failed; dropping image inputs and retrying text-only generation",
                    error,
                )
                return engineFor(modelSelection, modelFile, requiresVision = false)
            }
            if (requestedBackend is Backend.CPU) throw error
            // TODO(letta-mobile-37uvo): The UI does not yet display this live fallback. Plumb it up.
            android.util.Log.w(
                "LiteRtLmEngine",
                "Accelerator '${modelSelection.accelerator}' failed to initialize; falling back to CPU",
                error,
            )
            initializedEngine(modelFile, Backend.CPU(), modelSelection.maxTokens, visionEnabled = false)
        }
        logLiteRtInfo("engine_init_success", "handle=${modelSelection.modelHandle} key=$key vision=$requiresVision")
        activeEngine = engine
        activeKey = key
        return LiteRtLmEngineHandle(engine, requiresVision)
    }

    private data class LiteRtLmEngineHandle(
        val instance: Engine,
        val visionEnabled: Boolean,
    )

    private fun initializedEngine(modelFile: File, backend: Backend, maxTokens: Int, visionEnabled: Boolean): Engine {
        val cacheDir = File(context.cacheDir, "litert-lm").apply { mkdirs() }
        logLiteRtInfo(
            "engine_initialize_call",
            "modelPath=${modelFile.absolutePath} backend=${backend.javaClass.simpleName} vision=$visionEnabled cacheDir=${cacheDir.absolutePath}",
        )
        val engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                visionBackend = if (visionEnabled) Backend.GPU() else null,
                maxNumTokens = maxTokens,
                maxNumImages = if (visionEnabled) DEFAULT_MAX_IMAGES else null,
                cacheDir = cacheDir.absolutePath,
            )
        )
        try {
            engine.initialize()
        } catch (error: Throwable) {
            runCatching { engine.close() }
            logLiteRtError(
                "engine_initialize_error",
                "modelPath=${modelFile.absolutePath} backend=${backend.javaClass.simpleName} vision=$visionEnabled",
                error,
            )
            throw error
        }
        logLiteRtInfo("engine_initialize_success", "modelPath=${modelFile.absolutePath} backend=${backend.javaClass.simpleName} vision=$visionEnabled")
        return engine
    }
}

/**
 * Strips UTF-16 surrogate code units from [input] before it crosses the
 * LiteRT-LM native bridge.
 *
 * Kotlin strings are UTF-16; supplementary-plane code points (most emoji
 * like 🗺️🎉🔥) are encoded as surrogate pairs. When serialized
 * across the JNI boundary they become *modified* UTF-8, but nlohmann::json
 * inside LiteRT-LM's native JNI layer parses only *standard* UTF-8 and
 * crashes with "ill-formed UTF-8 byte" on the first surrogate.
 *
 * This drops only supplementary-plane characters (surrogates). All BMP
 * code points — ASCII, accented Latin, CJK, BMP symbols like ⚔️♻️❤️ —
 * pass through unchanged.
 *
 * The rest of the Kotlin/Android stack handles UTF-16 correctly, so
 * sanitization MUST stay confined to this native boundary and MUST NOT
 * be applied in storage, display, or any other non-LiteRT path.
 */
fun sanitizeForLiteRt(input: String): String {
    if (input.none { it.isSurrogate() }) return input
    return input.filterNot { it.isSurrogate() }
}

fun liteRtLmEngineCacheKey(modelSelection: EmbeddedLettaCodeModelSelection, visionEnabled: Boolean): String =
    "${modelSelection.startKey}|vision=$visionEnabled"

fun shouldSendVisionContent(request: LiteRtLmRequest, engineVisionEnabled: Boolean): Boolean =
    engineVisionEnabled && request.requiresVision

data class LiteRtLmRequest(
    val prompt: String,
    val images: List<OnDeviceImage>,
) {
    val requiresVision: Boolean = images.isNotEmpty()

    fun toContents(): Contents {
        val contents = mutableListOf<Content>()
        images.forEach { image -> contents.add(Content.ImageBytes(image.bytes)) }
        if (prompt.isNotBlank()) contents.add(Content.Text(prompt))
        return Contents.of(contents)
    }

    companion object {
        fun from(prompt: String, images: List<OnDeviceImage>): LiteRtLmRequest =
            LiteRtLmRequest(prompt = prompt, images = images)
    }
}

private const val DEFAULT_MAX_IMAGES = 8
private const val FIRST_TOKEN_LOG_CHARS = 160

private fun String.lineSafe(): String = replace('\n', ' ').replace('\r', ' ')

private fun String.toLiteRtLmBackend(context: Context): Backend =
    when (trim().lowercase(Locale.US)) {
        "cpu" -> Backend.CPU()
        "npu",
        "tpu",
        -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        "gpu" -> Backend.GPU()
        else -> Backend.GPU()
    }
