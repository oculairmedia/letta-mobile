package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val embeddedProviderAuthJson = Json { prettyPrint = true }

interface LettaCodeRuntimeController {
    fun submit(command: TurnCommand, config: LettaConfig): Flow<String>
}

data class OnDeviceOpenAiBridgeSession(
    val baseUrl: String,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    override fun close() = closeAction()
}

interface OnDeviceOpenAiBridge {
    fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession
}

@Singleton
class DisabledOnDeviceOpenAiBridge @Inject constructor() : OnDeviceOpenAiBridge {
    override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession {
        error("Embedded LettaCode on-device provider bridge is disabled until bridge/device smoke is ready.")
    }
}

@Singleton
class AndroidLettaCodeRuntimeController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetExtractor: EmbeddedLettaCodeAssetExtractor,
    private val nodeBridge: LettaCodeNodeBridge,
    private val runtimeStatusProvider: EmbeddedLettaCodeRuntimeStatusProvider,
    private val onDeviceOpenAiBridge: OnDeviceOpenAiBridge,
) : LettaCodeRuntimeController {
    private val submitMutex = Mutex()
    private val startMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var activeSession: EmbeddedLettaCodeSessionKey? = null
    private var activeOnDeviceBridgeSession: OnDeviceOpenAiBridgeSession? = null

    override fun submit(command: TurnCommand, config: LettaConfig): Flow<String> = channelFlow {
        submitMutex.withLock {
            ensureStarted(command, config)
            withTimeout(TURN_TIMEOUT_MS) {
                val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        nodeBridge.outputLines.collect { line ->
                            send(line)
                            if (line.isTerminalResult()) {
                                throw TerminalResultSeen()
                            }
                        }
                    } catch (_: TerminalResultSeen) {
                        Unit
                    }
                }
                nodeBridge.writeLine(command.toWireLine()).getOrThrow()
                reader.join()
            }
        }
    }

    private suspend fun ensureStarted(command: TurnCommand, config: LettaConfig) {
        startMutex.withLock {
            val modelSelection = EmbeddedLettaCodeModelSelection.from(config)
            val requestedSession = EmbeddedLettaCodeSessionKey(
                agentId = command.agentId.value,
                conversationId = command.conversationId.value,
                modelKey = modelSelection.startKey,
            )
            val active = activeSession
            if (active != null) {
                if (active != requestedSession) {
                    throw IllegalStateException(
                        "Embedded LettaCode is already bound to agent ${active.agentId} " +
                            "and conversation ${active.conversationId}. Restart the app before switching local sessions.",
                    )
                }
                return@withLock
            }

            if (!runtimeStatusProvider.status.runnable) {
                throw IllegalStateException(
                    "Embedded LettaCode is disabled in this build. " +
                        "Bridge/device smoke must pass before it can run on device.",
                )
            }

            check(LocalLettaCodeService.start(context)) {
                "Embedded LettaCode foreground service could not start."
            }
            val project = assetExtractor.prepare()
            val bridgeSession = onDeviceOpenAiBridge.start(modelSelection)
            activeOnDeviceBridgeSession = bridgeSession
            try {
                nodeBridge.start(
                    project.toLettaCodeNodeStartRequest(
                        session = requestedSession,
                        modelSelection = modelSelection,
                        onDeviceProviderBaseUrl = bridgeSession.baseUrl,
                    )
                ).getOrThrow()
            } catch (error: Throwable) {
                bridgeSession.close()
                activeOnDeviceBridgeSession = null
                throw error
            }
            activeSession = requestedSession
        }
    }

    private fun PreparedLettaCodeProject.toLettaCodeNodeStartRequest(
        session: EmbeddedLettaCodeSessionKey,
        modelSelection: EmbeddedLettaCodeModelSelection,
        onDeviceProviderBaseUrl: String? = null,
    ): LettaCodeNodeStartRequest {
        workingDirectory.mkdirs()
        storageDirectory.mkdirs()
        homeDirectory.mkdirs()
        val modelCacheDirectory = File(storageDirectory, "model-cache").apply { mkdirs() }
        if (onDeviceProviderBaseUrl != null) {
            writeEmbeddedLettaCodeProviderAuth(onDeviceProviderBaseUrl)
        }
        return LettaCodeNodeStartRequest(
            arguments = buildList {
                add("node")
                add(entrypoint.absolutePath)
                add("--backend")
                add("local")
                add("--model")
                add(if (onDeviceProviderBaseUrl == null) modelSelection.modelHandle else modelSelection.lettaCodeModelHandle)
                add("--agent")
                add(session.agentId)
                add("--conversation")
                add(session.conversationId)
                add("--input-format")
                add("stream-json")
                add("--output-format")
                add("stream-json")
            },
            environment = buildMap {
                put("HOME", homeDirectory.absolutePath)
                put("LETTA_LOCAL_BACKEND_EXPERIMENTAL", "1")
                put("LETTA_LOCAL_BACKEND_DIR", storageDirectory.absolutePath)
                put("LETTA_LOCAL_BACKEND_EXECUTOR", "pi")
                put("LETTA_ANDROID_ON_DEVICE_MODEL_HANDLE", modelSelection.modelHandle)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_RUNTIME", modelSelection.runtime)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_ACCELERATOR", modelSelection.accelerator)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_MAX_TOKENS", modelSelection.maxTokens.toString())
                put("LETTA_ANDROID_ON_DEVICE_MODEL_CACHE_DIR", modelCacheDirectory.absolutePath)
                onDeviceProviderBaseUrl?.let { put("LMSTUDIO_BASE_URL", it) }
                modelSelection.modelPath?.let { put("LETTA_ANDROID_ON_DEVICE_MODEL_PATH", it) }
                put("NO_COLOR", "1")
            },
            workingDirectory = workingDirectory,
        )
    }

    private fun PreparedLettaCodeProject.writeEmbeddedLettaCodeProviderAuth(baseUrl: String) {
        val authFile = File(storageDirectory, "providers/auth.json")
        val root = buildJsonObject {
            put("version", 1)
            put(
                "providers",
                buildJsonObject {
                    put(
                        "lc-lmstudio",
                        buildJsonObject {
                            put("id", "local-provider-lc-lmstudio")
                            put("name", "lc-lmstudio")
                            put("provider_type", "lmstudio")
                            put("provider_category", "byok")
                            put(
                                "auth",
                                buildJsonObject {
                                    put("type", "api")
                                    put("key", "not-needed")
                                },
                            )
                            put("base_url", baseUrl)
                        },
                    )
                },
            )
        }
        authFile.parentFile?.mkdirs()
        authFile.writeText(embeddedProviderAuthJson.encodeToString(JsonObject.serializer(), root))
    }

    private fun TurnCommand.toWireLine(): String = when (val input = input) {
        is TurnInput.UserMessage -> buildJsonObject {
            put("type", "user")
            put(
                "message",
                buildJsonObject {
                    put("role", "user")
                    put("content", input.text)
                    put("otid", input.localMessageId)
                },
            )
        }.toString()

        is TurnInput.ToolApprovalResponse -> {
            val allow = input.decision.decision == ToolApprovalDecisionValue.Approved
            buildJsonObject {
                put("type", "control_response")
                put(
                    "response",
                    buildJsonObject {
                        put("subtype", "success")
                        put("request_id", "perm-${input.decision.callId.value}")
                        put(
                            "response",
                            buildJsonObject {
                                put("behavior", if (allow) "allow" else "deny")
                                input.decision.response?.let { put("message", it) }
                            },
                        )
                    },
                )
            }.toString()
        }
    }

    private fun String.isTerminalResult(): Boolean {
        val root = runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull() ?: return false
        return root["type"]?.toString()?.trim('"') == "result"
    }

    private class TerminalResultSeen : CancellationException()

    private companion object {
        private const val TURN_TIMEOUT_MS = 120_000L
    }
}

data class EmbeddedLettaCodeSessionKey(
    val agentId: String,
    val conversationId: String,
    val modelKey: String,
)

data class EmbeddedLettaCodeModelSelection(
    val modelHandle: String,
    val modelPath: String?,
    val runtime: String,
    val accelerator: String,
    val maxTokens: Int,
) {
    val startKey: String =
        listOf(modelHandle, modelPath.orEmpty(), runtime, accelerator, maxTokens.toString()).joinToString("|")
    val openAiModelId: String = modelHandle.toOpenAiModelId()
    val lettaCodeModelHandle: String = "lmstudio/$openAiModelId"

    companion object {
        const val DEFAULT_MODEL_HANDLE = "local/default"
        const val DEFAULT_MODEL_RUNTIME = "litert-lm"
        const val DEFAULT_ACCELERATOR = "gpu"
        const val DEFAULT_MAX_TOKENS = 4096

        fun from(config: LettaConfig): EmbeddedLettaCodeModelSelection = EmbeddedLettaCodeModelSelection(
            modelHandle = config.localModelHandle.trimmedOrNull() ?: DEFAULT_MODEL_HANDLE,
            modelPath = config.localModelPath.trimmedOrNull(),
            runtime = config.localModelRuntime.trimmedOrNull() ?: DEFAULT_MODEL_RUNTIME,
            accelerator = config.localModelAccelerator.trimmedOrNull() ?: DEFAULT_ACCELERATOR,
            maxTokens = config.localModelMaxTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS,
        )
    }
}

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private fun String.toOpenAiModelId(): String =
    trim()
        .removePrefix("local/")
        .removePrefix("lmstudio/")
        .removePrefix("llama-cpp/")
        .removePrefix("llama.cpp/")
        .ifBlank { "default" }
