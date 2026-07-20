package com.letta.mobile.runtime.local

import android.content.Context
import android.util.Log
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.runtime.sensors.DeviceSensorGroundingWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

import kotlin.time.Duration.Companion.milliseconds
interface LettaCodeRuntimeController {
    fun submit(command: TurnCommand, config: LettaConfig): Flow<String>

    /**
     * Asks the embedded letta.js process to abort the in-flight generation
     * via the stdin control protocol (letta-mobile-p2mmd). No-op when no
     * session is running.
     */
    suspend fun interrupt()
}

@Singleton
class AndroidLettaCodeRuntimeController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetExtractor: EmbeddedLettaCodeAssetExtractor,
    private val nodeBridge: LettaCodeNodeBridge,
    private val runtimeStatusProvider: EmbeddedLettaCodeRuntimeStatusProvider,
    private val onDeviceOpenAiBridge: OnDeviceOpenAiBridge,
    private val localBackendStore: LettaCodeLocalBackendStore,
    private val androidNetworkBridge: AndroidNetworkBridge,
    private val deviceSensorGroundingWriter: DeviceSensorGroundingWriter? = null,
) : LettaCodeRuntimeController {
    private val submitMutex = Mutex()
    private val startMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var activeSession: EmbeddedLettaCodeSessionKey? = null
    private var activeOnDeviceBridgeSession: OnDeviceOpenAiBridgeSession? = null
    private var activeAndroidNetworkBridgeSession: AndroidNetworkBridgeSession? = null

    @androidx.annotation.VisibleForTesting
    var turnSilenceMs: Long = TURN_SILENCE_MS

    @androidx.annotation.VisibleForTesting
    var turnAbsoluteMaxMs: Long = TURN_ABSOLUTE_MAX_MS

    override fun submit(command: TurnCommand, config: LettaConfig): Flow<String> = channelFlow {
        if (command.input is TurnInput.ToolApprovalResponse) {
            Log.w(TAG, "Ignoring tool approval response for embedded LettaCode; approvals are not supported yet.")
            return@channelFlow
        }
        submitMutex.withLock {
            val firstTurnOfSession = ensureStarted(command, config)
            // Dangling-tool-call heal is EVENT-GATED, not run every turn — a
            // full transcript parse per turn would be O(history) and add
            // per-send lag that grows with the conversation. An orphan can only
            // appear when a turn ends ABNORMALLY (no terminal frame), so we only
            // pay the O(n) scan in that rare case. The happy path costs a single
            // boolean check.
            //
            // Layer 1 — heal-on-read (belt): heal BEFORE this turn only if the
            // store is dirty: a prior turn in this session ended abnormally, OR
            // this is the first turn of a fresh session (a previous app process
            // may have been killed mid-tool — the most common real cause).
            if (firstTurnOfSession || transcriptDirty) {
                healDanglingToolCalls(command.agentId.value, phase = "pre-turn")
                transcriptDirty = false
            }
            // Strip heavy base64 from images already on disk (sent on prior
            // turns) so they don't re-bloat the context / re-send every turn
            // (letta-mobile-87itk). The current turn's new image rides in the
            // wire line, not yet on disk, so it is never stripped in-flight.
            // No-ops cheaply once everything is stripped.
            stripPersistedImages(command.agentId.value)
            writeDeviceSensorGrounding()
            var endedCleanly = false
            try {
                collectTurnOutputWithLivenessWatchdog(
                    onOutput = { line ->
                        Log.d(TAG, "embedded node out: ${line.take(400)}")
                        send(line)
                        line.isTerminalFrame()
                    },
                    startTurn = {
                        nodeBridge.writeLine(command.toWireLine()).getOrThrow()
                    },
                )
                endedCleanly = true
            } finally {
                // Layer 2 — settle-on-interrupt (suspenders): ONLY when this turn
                // ended abnormally (timeout, cancel, node death mid-tool) — a
                // clean turn never strands a tool call, so it pays nothing. On an
                // abnormal end we settle immediately AND mark dirty so the next
                // turn's Layer-1 check re-verifies before replay.
                if (!endedCleanly) {
                    transcriptDirty = true
                    // lcp-im5q: a USER CANCEL lands here with this coroutine
                    // already cancelled — any plain suspend call (the heal's
                    // withContext(Dispatchers.IO)) would throw
                    // CancellationException on entry and the settle would
                    // silently never run, deferring the repair to the next
                    // turn's pre-turn check. NonCancellable lets the
                    // bounded, disk-only heal complete now so the transcript
                    // is clean the moment the turn dies.
                    withContext(NonCancellable) {
                        healDanglingToolCalls(command.agentId.value, phase = "post-turn")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectTurnOutputWithLivenessWatchdog(
        onOutput: suspend (String) -> Boolean,
        startTurn: suspend () -> Unit,
    ) = coroutineScope {
        val outputLines = Channel<String>(capacity = Channel.BUFFERED)
        val absoluteTimeout = Channel<Unit>(capacity = Channel.CONFLATED)
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                nodeBridge.outputLines.collect { line ->
                    outputLines.send(line)
                }
            } finally {
                outputLines.close()
            }
        }
        val absoluteWatchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            delay(turnAbsoluteMaxMs.milliseconds)
            absoluteTimeout.trySend(Unit)
        }
        try {
            startTurn()
            while (true) {
                val line = select<String?> {
                    outputLines.onReceiveCatching { result -> result.getOrNull() }
                    absoluteTimeout.onReceiveCatching {
                        throw TurnTimeoutException(
                            "Embedded LettaCode turn exceeded absolute maximum of ${turnAbsoluteMaxMs / 1000}s.",
                        )
                    }
                    onTimeout(turnSilenceMs.milliseconds) {
                        throw TurnTimeoutException(
                            "Embedded LettaCode turn produced no output for ${turnSilenceMs / 1000}s.",
                        )
                    }
                } ?: break
                if (onOutput(line)) break
            }
        } finally {
            reader.cancel()
            absoluteWatchdog.cancel()
        }
    }

    // Set when a turn ends abnormally; gates the next turn's pre-replay heal so
    // healthy turns do zero transcript I/O.
    @Volatile
    private var transcriptDirty: Boolean = false

    private suspend fun stripPersistedImages(agentId: String) {
        runCatching { localBackendStore.stripPersistedImageData(agentId) }
            .onSuccess { report ->
                if (report.stripped) {
                    Log.w(
                        TAG,
                        "Stripped ${report.partsStripped} persisted image(s) " +
                            "(~${report.bytesFreed / 1024}KB base64 freed) for agent=$agentId",
                    )
                }
            }
            .onFailure { error ->
                Log.w(TAG, "Image-context strip failed for agent=$agentId", error)
            }
    }

    private suspend fun writeDeviceSensorGrounding() {
        val writer = deviceSensorGroundingWriter ?: return
        runCatching { writer.writeSnapshot() }
            .onSuccess { report ->
                Log.d(TAG, "Device sensor grounding wrote ${report.bytes}B sensors=${report.sensorCount}")
            }
            .onFailure { error ->
                Log.w(TAG, "Device sensor grounding failed", error)
            }
    }

    private suspend fun healDanglingToolCalls(agentId: String, phase: String) {
        runCatching { localBackendStore.healDanglingToolCalls(agentId) }
            .onSuccess { report ->
                if (report.healed) {
                    Log.w(
                        TAG,
                        "Healed ${report.rowsAppended} dangling tool call(s) [$phase] for agent=$agentId " +
                            "ids=${report.orphanCallIds}",
                    )
                }
            }
            .onFailure { error ->
                Log.w(TAG, "Dangling-tool-call heal failed [$phase] for agent=$agentId", error)
            }
    }

    override suspend fun interrupt() {
        startMutex.withLock {
            if (activeSession == null) return
        }
        nodeBridge.writeLine(
            buildInterruptControlRequest("interrupt-${System.currentTimeMillis()}"),
        ).onFailure { error ->
            Log.w(TAG, "Failed to send interrupt to embedded LettaCode", error)
        }
    }

    /**
     * Starts the embedded session if not already running. Returns true when
     * this call started a FRESH session (first turn of the process/session) so
     * the caller can run a one-time dangling-tool-call heal — a prior app
     * process may have been killed mid-tool. Returns false when an existing
     * session was reused (no heal needed unless separately marked dirty).
     */
    private suspend fun ensureStarted(command: TurnCommand, config: LettaConfig): Boolean {
        return startMutex.withLock {
            val configModelSelection = EmbeddedLettaCodeModelSelection.from(config)
            val storedModelHandle = localBackendStore.storedModelHandle(command.agentId.value)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val modelSelection = if (
                configModelSelection.modelHandle == EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_HANDLE &&
                storedModelHandle != null
            ) {
                configModelSelection.copy(modelHandle = storedModelHandle.toLettaCodeProviderModelHandle())
            } else {
                configModelSelection
            }
            val requestedSession = EmbeddedLettaCodeSessionKey(
                agentId = command.agentId.value,
                conversationId = command.conversationId.value,
                modelKey = modelSelection.startKey,
            )
            val active = activeSession
            if (active != null) {
                if (active == requestedSession) {
                    return@withLock false
                }
                interruptActiveSessionForSwitch()
                if (active.modelKey == requestedSession.modelKey) {
                    sendSwitchSessionControl(requestedSession)
                    activeSession = requestedSession
                    transcriptDirty = true
                    return@withLock false
                }
                stopActiveRuntimeForModelSwitch()
            }

            if (!runtimeStatusProvider.status.runnable) {
                throw IllegalStateException(
                    "Embedded LettaCode is disabled in this build. " +
                        "Enable embedded native and asset prerequisites before selecting local-lettacode://device.",
                )
            }
            // OpenAI-compatible endpoints (explicit custom base URLs or remote
            // provider handles like lmstudio/) keep the agent loop embedded but
            // route LLM calls to the endpoint — no .litertlm bridge required.
            if (
                modelSelection.routesToOpenAiCompatibleProvider &&
                modelSelection.modelHandle == EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_HANDLE
            ) {
                throw IllegalStateException(
                    "Embedded LettaCode custom provider requires a selected provider model before it can start.",
                )
            }
            if (modelSelection.requiresOnDeviceModel) {
                val modelPath = modelSelection.modelPath?.let(::File)
                    ?: throw IllegalStateException(
                        "Embedded LettaCode requires an imported .litertlm model path before it can start.",
                    )
                if (!modelPath.isFile) {
                    throw IllegalStateException(
                        "Embedded LettaCode model file was not found at ${modelPath.absolutePath}.",
                    )
                }
            }

            check(LocalLettaCodeService.start(context)) {
                "Embedded LettaCode foreground service could not start."
            }
            val project = assetExtractor.prepare()
            val bridgeSession = if (modelSelection.routesToOpenAiCompatibleProvider) {
                null
            } else {
                onDeviceOpenAiBridge.start(modelSelection)
            }
            val networkBridgeSession = androidNetworkBridge.start()
            activeOnDeviceBridgeSession = bridgeSession
            activeAndroidNetworkBridgeSession = networkBridgeSession
            try {
                nodeBridge.start(
                    project.toLettaCodeNodeStartRequest(
                        session = requestedSession,
                        modelSelection = modelSelection,
                        // letta-mobile-ajcrx: an on-device LiteRT model wears an
                        // lmstudio/ prefix (to avoid letta.js's bare-id SIGABRT),
                        // which makes effectiveProviderBaseUrl return the REMOTE
                        // proxy default — but when an on-device bridgeSession
                        // exists, the turn MUST route to the loopback bridge, not
                        // the remote proxy. So the loopback bridge URL takes
                        // priority; effectiveProviderBaseUrl is only the fallback
                        // for a genuine custom/remote provider (no on-device bridge).
                        onDeviceProviderBaseUrl = bridgeSession?.baseUrl ?: modelSelection.effectiveProviderBaseUrl,
                        onDeviceProviderApiKey = bridgeSession?.authToken ?: modelSelection.effectiveProviderApiKey,
                        androidNetworkBridgeBaseUrl = networkBridgeSession.baseUrl,
                        androidNetworkBridgeToken = networkBridgeSession.authToken,
                    )
                ).getOrThrow()
            } catch (error: Throwable) {
                bridgeSession?.close()
                networkBridgeSession.close()
                activeOnDeviceBridgeSession = null
                activeAndroidNetworkBridgeSession = null
                throw error
            }
            activeSession = requestedSession
            true
        }
    }

    private suspend fun interruptActiveSessionForSwitch() {
        nodeBridge.writeLine(buildInterruptControlRequest("switch-interrupt-${System.currentTimeMillis()}")).onFailure { error ->
            Log.w(TAG, "Failed to interrupt embedded LettaCode before switching sessions", error)
        }
    }

    private suspend fun stopActiveRuntimeForModelSwitch() {
        runCatching { nodeBridge.stop().getOrThrow() }
            .onFailure { error -> Log.w(TAG, "Failed to stop embedded LettaCode for model switch", error) }
        activeOnDeviceBridgeSession?.close()
        activeOnDeviceBridgeSession = null
        activeAndroidNetworkBridgeSession?.close()
        activeAndroidNetworkBridgeSession = null
        activeSession = null
        transcriptDirty = true
    }

    private suspend fun sendSwitchSessionControl(session: EmbeddedLettaCodeSessionKey) {
        nodeBridge.writeLine(
            buildJsonObject {
                put("type", "control_request")
                put("request_id", "switch-session-${System.currentTimeMillis()}")
                put(
                    "request",
                    buildJsonObject {
                        put("subtype", "switch_session")
                        put("agent_id", session.agentId)
                        put("conversation_id", session.conversationId)
                    },
                )
                put("agent_id", session.agentId)
                put("conversation_id", session.conversationId)
            }.toString(),
        ).getOrThrow()
    }

    private suspend fun PreparedLettaCodeProject.toLettaCodeNodeStartRequest(
        session: EmbeddedLettaCodeSessionKey,
        modelSelection: EmbeddedLettaCodeModelSelection,
        onDeviceProviderBaseUrl: String? = null,
        onDeviceProviderApiKey: String? = null,
        androidNetworkBridgeBaseUrl: String,
        androidNetworkBridgeToken: String,
    ): LettaCodeNodeStartRequest {
        // Per-agent model: letta.js overwrites the agent's model with the
        // --model argv on resume. The current config selection is therefore
        // authoritative for embedded model switches and reseeds the stored
        // agent model before letta.js resumes it.
        val modelHandle = modelSelection.androidEmbeddedLaunchModelHandle(onDeviceProviderBaseUrl)
        // One line per session start: which model letta.js was actually given.
        // A session pinned to the wrong model (e.g. the 2026-06-12 codexmini
        // detour) is otherwise impossible to diagnose after the fact because
        // argv is not externally visible for the in-process node runtime.
        Log.i(
            TAG,
            "Starting embedded session agent=${session.agentId} model=$modelHandle " +
                "customProvider=${modelSelection.isCustomProvider} providerBaseUrl=$onDeviceProviderBaseUrl",
        )
        localBackendStore.seedAgent(session.agentId, modelHandle)
        return prepareAndroidEmbeddedLettaCodeLaunchSpec(
            modelSelection = modelSelection,
            onDeviceProviderBaseUrl = onDeviceProviderBaseUrl,
            onDeviceProviderApiKey = onDeviceProviderApiKey,
            androidNetworkBridgeBaseUrl = androidNetworkBridgeBaseUrl,
            androidNetworkBridgeToken = androidNetworkBridgeToken,
            deviceSensorGroundingWriter = deviceSensorGroundingWriter,
            toolInstaller = AndroidContextEmbeddedToolInstaller(context),
        ).toStreamJsonNodeStartRequest(session)
    }

    private fun TurnCommand.toWireLine(): String = when (val input = input) {
        is TurnInput.UserMessage -> encodeUserTurnWireLine(input)
        is TurnInput.ToolApprovalResponse -> error("Tool approvals are not supported by embedded LettaCode.")
    }

    private fun String.isTerminalFrame(): Boolean {
        val root = runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull() ?: return false
        return when (root["type"]?.toString()?.trim('"')) {
            "result",
            "error",
            -> true
            "stream_event" -> {
                val event = root["event"] as? JsonObject
                event?.get("type")?.toString()?.trim('"') in setOf("result", "error")
            }
            else -> false
        }
    }

    private class TurnTimeoutException(message: String) : CancellationException(message)

    private companion object {
        private const val TAG = "LettaCodeRuntime"
        private const val TURN_SILENCE_MS = 120_000L
        private const val TURN_ABSOLUTE_MAX_MS = 30 * 60 * 1000L
    }
}


private fun buildInterruptControlRequest(requestId: String): String =
    buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        put("request", buildJsonObject { put("subtype", "interrupt") })
    }.toString()

data class EmbeddedLettaCodeSessionKey(
    val agentId: String,
    val conversationId: String,
    val modelKey: String,
)

fun embeddedLettaCodePreloadRequireFiles(projectDir: File): List<File> = listOf(
    File(projectDir, "regexp-polyfill.cjs"),
    File(projectDir, "android-network-polyfill.cjs"),
    File(projectDir, "embedded-runtime-introspection-preload.cjs"),
)

/**
 * Encodes a local user turn into the embedded letta.js stdin wire line.
 *
 * Text-only sends keep the legacy plain-string `content` for back-compat.
 * When [TurnInput.UserMessage.imageParts] is non-empty, `content` becomes an
 * ARRAY `[{type:text}?, {type:image, mimeType, data}...]`.
 *
 * The embedded letta.js inbound normalization gate isBase64ImageContentPart()
 * requires the NESTED Letta union: {type:"image", source:{type:"base64",
 * media_type:<non-empty>, data:<non-empty base64>}}. The flat
 * {type,mimeType,data} shape is NOT recognized and the image is silently
 * dropped (proven by Tier 12: captured=0). Emit the nested shape.
*/
fun encodeUserTurnWireLine(input: TurnInput.UserMessage): String =
    buildJsonObject {
        put("type", "user")
        put(
            "message",
            buildJsonObject {
                put("role", "user")
                if (input.imageParts.isEmpty()) {
                    put("content", JsonPrimitive(input.text))
                } else {
                    put("content", encodeUserContentArray(input.text, input.imageParts))
                }
                put("otid", input.localMessageId)
            },
        )
    }.toString()

private fun encodeUserContentArray(
    text: String,
    imageParts: List<com.letta.mobile.runtime.TurnImagePart>,
): JsonArray = buildJsonArray {
    if (text.isNotBlank()) {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
    }
    imageParts.forEach { part ->
        // NESTED source shape — letta.js's isBase64ImageContentPart() (the
        // inbound stdin normalization gate) requires EXACTLY
        // {type:"image", source:{type:"base64", media_type:<non-empty>,
        // data:<non-empty>}}. Verified against the bundle + proven by the
        // Tier 12 device-loop test: the flat {type,mimeType,data} shape is NOT
        // recognized, so the image is passed through un-normalized and never
        // forwarded as image_url to the provider (captured=0). See
        // encodeUserTurnWireLine KDoc (letta-mobile-aobcg/nojhc).
        add(
            buildJsonObject {
                put("type", "image")
                put(
                    "source",
                    buildJsonObject {
                        put("type", "base64")
                        put("media_type", part.mediaType)
                        put("data", part.base64)
                    },
                )
            },
        )
    }
}

private fun String.toLettaCodeProviderModelHandle(): String =
    if (startsWith("lmstudio/")) this else "lmstudio/${removePrefix("local/")}"
