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
    private var activeBackendName: String = CPU_BACKEND
    private var activeVision = false

    /** The GPU failed an inference in this process: every later run uses CPU. */
    private var gpuUnusable = false

    /** Serializes generations: one engine, one conversation at a time. */
    @Synchronized
    override fun generate(
        modelSelection: EmbeddedLettaCodeModelSelection,
        prompt: String,
        images: List<OnDeviceImage>,
        generation: OnDeviceGeneration,
    ): Result<String> =
        runCatching {
            // Cancelled while it waited for the engine (the client left): free the engine at once.
            if (generation.isStopped) {
                logLiteRtInfo("inference_skipped", "handle=${modelSelection.modelHandle} reason=${generation.stopReason?.wire}")
                return@runCatching generation.text
            }
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
            val engine = runOnBestBackend(modelSelection, modelFile, request, generation)
            val response = generation.text
            logLiteRtInfo("first_token", "preview=${response.take(FIRST_TOKEN_LOG_CHARS).lineSafe()}")
            logLiteRtInfo(
                "inference_complete",
                "responseChars=${response.length} backend=${engine.backendName} stopReason=${generation.stopReason?.wire ?: "model_done"}",
            )
            response
        }.onFailure { error ->
            logLiteRtError("inference_error", "handle=${modelSelection.modelHandle}", error)
        }

    /**
     * Runs on the requested accelerator. A GPU engine can initialize and still fail its first
     * inference on some devices: before any output, that falls back to CPU, and later runs of this
     * process go straight to CPU ([gpuUnusable]). Returns the engine that answered.
     */
    private fun runOnBestBackend(
        modelSelection: EmbeddedLettaCodeModelSelection,
        modelFile: File,
        request: LiteRtLmRequest,
        generation: OnDeviceGeneration,
    ): LiteRtLmEngineHandle {
        val selection = if (gpuUnusable) modelSelection.copy(accelerator = CPU_ACCELERATOR) else modelSelection
        val engine = engineFor(selection, modelFile, request.requiresVision)
        if (request.requiresVision && !engine.visionEnabled) {
            logLiteRtInfo("vision_unavailable", "handle=${modelSelection.modelHandle} images=${request.images.size} action=text_only")
        }
        try {
            engine.instance.createConversation().use { conversation ->
                LiteRtLmStreamingRun(conversation, generation).run(
                    if (shouldSendVisionContent(request, engine.visionEnabled)) request.toContents() else null,
                    request.prompt,
                )
            }
        } catch (error: Throwable) {
            if (!engine.canFallBackToCpu(generation)) throw error
            logLiteRtError("gpu_inference_error", "handle=${modelSelection.modelHandle} action=fallback_cpu", error)
            gpuUnusable = true
            return runOnBestBackend(modelSelection, modelFile, request, generation)
        }
        return engine
    }

    private fun engineFor(
        modelSelection: EmbeddedLettaCodeModelSelection,
        modelFile: File,
        requiresVision: Boolean,
    ): LiteRtLmEngineHandle {
        val key = liteRtLmEngineCacheKey(modelSelection, visionEnabled = requiresVision)
        activeEngine?.takeIf { activeKey == key }?.let { return LiteRtLmEngineHandle(it, activeVision, activeBackendName) }

        runCatching { activeEngine?.close() }
        activeEngine = null
        activeKey = null

        val requestedBackend = modelSelection.accelerator.toLiteRtLmBackend(context)
        logLiteRtInfo(
            "engine_init_start",
            "handle=${modelSelection.modelHandle} key=$key backend=${requestedBackend.javaClass.simpleName} vision=$requiresVision path=${modelFile.absolutePath}",
        )
        // GPU is unusable on some devices (OpenCL blocked for apps, or the OpenGL delegate's
        // "CreateSharedMemoryManager is not implemented" on Tensor) and selecting it bricked every
        // turn; a vision encoder can fail where text does not. Try, in order: as requested, vision
        // on CPU, then text only (images dropped), and use the first that initializes.
        var lastError: Throwable? = null
        val attempts = liteRtLmInitAttempts(LiteRtLmInitAttempt(requestedBackend.javaClass.simpleName, requiresVision))
        for (attempt in attempts) {
            val backend = if (attempt.backendName == CPU_BACKEND) Backend.CPU() else requestedBackend
            val engine = try {
                initializedEngine(modelFile, backend, modelSelection.maxTokens, attempt.vision)
            } catch (error: Throwable) {
                logLiteRtError(
                    "engine_init_error",
                    "handle=${modelSelection.modelHandle} backend=${attempt.backendName} vision=${attempt.vision} " +
                        "path=${modelFile.absolutePath}",
                    error,
                )
                lastError = error
                continue
            }
            // Which backend actually runs: a GPU request that fell back to CPU says so here.
            logLiteRtInfo(
                "engine_init_success",
                "handle=${modelSelection.modelHandle} key=$key requested=${modelSelection.accelerator} backend=${attempt.backendName} " +
                    "vision=${attempt.vision} fallback=${attempt != attempts.first()}",
            )
            activeEngine = engine
            activeKey = key
            activeBackendName = attempt.backendName
            activeVision = attempt.vision
            return LiteRtLmEngineHandle(engine, attempt.vision, attempt.backendName)
        }
        throw lastError ?: error("LiteRT-LM engine did not initialize.")
    }

    private data class LiteRtLmEngineHandle(
        val instance: Engine,
        val visionEnabled: Boolean,
        val backendName: String,
    ) {
        /** A non-CPU engine failed before any output and nobody stopped it: CPU can still answer. */
        fun canFallBackToCpu(generation: OnDeviceGeneration): Boolean =
            backendName != CPU_BACKEND && !generation.isStopped && generation.text.isEmpty()
    }

    /** The vision encoder runs where the text model does: GPU with GPU, CPU with CPU. */
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
                visionBackend = if (!visionEnabled) null else if (backend is Backend.CPU) Backend.CPU() else Backend.GPU(),
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

/** One engine initialization to try: the text backend's name and whether the vision encoder loads. */
data class LiteRtLmInitAttempt(val backendName: String, val vision: Boolean)

/**
 * The initializations [LiteRtLmOnDeviceChatCompletionEngine] tries, first that works wins: as
 * requested; vision on CPU; text only on the requested backend; text only on CPU. Duplicates drop.
 */
fun liteRtLmInitAttempts(requested: LiteRtLmInitAttempt): List<LiteRtLmInitAttempt> =
    buildList {
        add(requested)
        if (requested.vision) add(LiteRtLmInitAttempt(CPU_BACKEND, true))
        add(requested.copy(vision = false))
        add(LiteRtLmInitAttempt(CPU_BACKEND, false))
    }.distinct()

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
private const val CPU_ACCELERATOR = "cpu"
private const val CPU_BACKEND = "CPU"
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
