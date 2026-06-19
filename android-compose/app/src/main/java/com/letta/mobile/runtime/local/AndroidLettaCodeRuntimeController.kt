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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val embeddedProviderAuthJson = Json { prettyPrint = true }

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
                withTimeout(TURN_TIMEOUT_MS) {
                    val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            nodeBridge.outputLines.collect { line ->
                                Log.d(TAG, "embedded node out: ${line.take(400)}")
                                send(line)
                                if (line.isTerminalFrame()) {
                                    throw TerminalResultSeen()
                                }
                            }
                        } catch (_: TerminalResultSeen) {
                            Unit
                        }
                    }
                    nodeBridge.writeLine(command.toWireLine()).getOrThrow()
                    reader.join()
                    endedCleanly = true
                }
            } finally {
                // Layer 2 — settle-on-interrupt (suspenders): ONLY when this turn
                // ended abnormally (timeout, cancel, node death mid-tool) — a
                // clean turn never strands a tool call, so it pays nothing. On an
                // abnormal end we settle immediately AND mark dirty so the next
                // turn's Layer-1 check re-verifies before replay.
                if (!endedCleanly) {
                    transcriptDirty = true
                    healDanglingToolCalls(command.agentId.value, phase = "post-turn")
                }
            }
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
            val modelSelection = storedModelHandle
                ?.let { configModelSelection.copy(modelHandle = it.toLettaCodeProviderModelHandle()) }
                ?: configModelSelection
            val requestedSession = EmbeddedLettaCodeSessionKey(
                agentId = command.agentId.value,
                conversationId = command.conversationId.value,
                modelKey = modelSelection.startKey,
            )
            val active = activeSession
            if (active != null) {
                if (active != requestedSession) {
                    interruptActiveSessionForSwitch()
                    sendSwitchSessionControl(requestedSession)
                    activeSession = requestedSession
                    transcriptDirty = true
                }
                return@withLock false
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
                        onDeviceProviderBaseUrl = modelSelection.effectiveProviderBaseUrl ?: bridgeSession?.baseUrl,
                        onDeviceProviderApiKey = modelSelection.effectiveProviderApiKey ?: bridgeSession?.authToken,
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

    private fun PreparedLettaCodeProject.toLettaCodeNodeStartRequest(
        session: EmbeddedLettaCodeSessionKey,
        modelSelection: EmbeddedLettaCodeModelSelection,
        onDeviceProviderBaseUrl: String? = null,
        onDeviceProviderApiKey: String? = null,
        androidNetworkBridgeBaseUrl: String,
        androidNetworkBridgeToken: String,
    ): LettaCodeNodeStartRequest {
        workingDirectory.mkdirs()
        storageDirectory.mkdirs()
        homeDirectory.mkdirs()
        val modelCacheDirectory = File(storageDirectory, "model-cache").apply { mkdirs() }
        val tempDirectory = File(storageDirectory.parentFile, "tmp").apply { mkdirs() }
        val backgroundDirectory = File(storageDirectory.parentFile, "background").apply { mkdirs() }
        // Per-agent model: letta.js overwrites the agent's model with the
        // --model argv on resume, so the stored record's handle must win;
        // the config-level selection is only the seed default for brand-new
        // agents (letta-mobile-3icw7). Stored handles are normalized to the
        // lmstudio/ provider prefix: letta.js aborts the whole process on an
        // unroutable bare id ("Invalid model" → node exit → SIGABRT), and
        // records written by older picker builds persisted bare ids.
        val modelHandle = if (onDeviceProviderBaseUrl == null) modelSelection.modelHandle else modelSelection.lettaCodeModelHandle
        // One line per session start: which model letta.js was actually given.
        // A session pinned to the wrong model (e.g. the 2026-06-12 codexmini
        // detour) is otherwise impossible to diagnose after the fact because
        // argv is not externally visible for the in-process node runtime.
        Log.i(
            TAG,
            "Starting embedded session agent=${session.agentId} model=$modelHandle " +
                "customProvider=${modelSelection.isCustomProvider} remoteProvider=${modelSelection.isRemoteProviderModel} " +
                "providerBaseUrl=$onDeviceProviderBaseUrl",
        )
        localBackendStore.seedAgent(session.agentId, modelHandle)
        if (onDeviceProviderBaseUrl != null) {
            writeEmbeddedLettaCodeProviderAuth(
                baseUrl = onDeviceProviderBaseUrl,
                apiKey = onDeviceProviderApiKey ?: "not-needed",
            )
        }
        return LettaCodeNodeStartRequest(
            arguments = buildList {
                add("node")
                add("--max-old-space-size=384")
                add("--max-semi-space-size=16")
                // ICU-less V8 rejects \p{...} regexes; preload a RegExp wrapper
                // that rewrites them through regexpu-core (see asset prep task).
                embeddedLettaCodePreloadRequireFiles(projectDir).forEach { preload ->
                    add("--require")
                    add(preload.absolutePath)
                }
                add(entrypoint.absolutePath)
                add("--backend")
                add("local")
                add("--model")
                add(modelHandle)
                add("--agent")
                add(session.agentId)
                // letta.js rejects --conversation <custom-id> together with
                // --agent ("--conversation cannot be used with --agent") —
                // only the literal "default" is allowed alongside --agent.
                // The Kotlin session key still tracks the real conversation id;
                // the embedded process is bound to one session anyway.
                add("--conversation")
                add("default")
                add("--input-format")
                add("stream-json")
                add("--output-format")
                add("stream-json")
                // The app has no approval channel yet (ToolApprovalResponse is
                // ignored), so gated tools would stall a turn forever. The
                // embedded runtime runs on the user's own device against the
                // app sandbox; unrestricted is the deliberate interim policy
                // until an approvals UI exists (letta-mobile-bm6x2).
                add("--permission-mode")
                add("unrestricted")
            },
            environment = buildMap {
                put("HOME", homeDirectory.absolutePath)
                put("LETTA_LOCAL_BACKEND_EXPERIMENTAL", "1")
                put("LETTA_LOCAL_BACKEND_DIR", storageDirectory.absolutePath)
                put("LETTA_LOCAL_BACKEND_EXECUTOR", "pi")
                putAll(memfsEnvironment())
                // letta-mobile-nojhc: declare which custom-provider models are
                // image-capable. Consumed by the build-time letta.js patch on
                // customOpenAICompatibleModel (matched as case-insensitive
                // substrings against the model id), mirroring the shim's
                // LETTA_VISION_MODELS so both surfaces share one list.
                put("LETTA_CODE_VISION_MODEL_IDS", VISION_MODEL_PATTERNS.joinToString(","))
                put("LETTA_ANDROID_ON_DEVICE_MODEL_HANDLE", modelHandle)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_RUNTIME", modelSelection.runtime)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_ACCELERATOR", modelSelection.accelerator)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_MAX_TOKENS", modelSelection.maxTokens.toString())
                put("LETTA_ANDROID_ON_DEVICE_MODEL_CACHE_DIR", modelCacheDirectory.absolutePath)
                onDeviceProviderBaseUrl?.let { put("LMSTUDIO_BASE_URL", it) }
                onDeviceProviderApiKey?.let { put("LMSTUDIO_API_KEY", it) }
                modelSelection.modelPath?.let { put("LETTA_ANDROID_ON_DEVICE_MODEL_PATH", it) }
                // letta.js's Bash tool resolves its shell from $SHELL first,
                // then /bin/bash, /bin/sh, /usr/bin/env … — none of which
                // exist on Android. Without this the Bash tool is dead on
                // device ("there is no bash"); toybox sh handles -c fine.
                put("SHELL", "/system/bin/sh")
                put("NO_COLOR", "1")
                put("UV_USE_IO_URING", "0")
                put("UV_THREADPOOL_SIZE", "2")
                put("TMPDIR", tempDirectory.absolutePath)
                put("TMP", tempDirectory.absolutePath)
                put("TEMP", tempDirectory.absolutePath)
                put("LETTA_BACKGROUND_DIR", backgroundDirectory.absolutePath)
                put("LETTA_TASK_OUTPUT_DIR", backgroundDirectory.absolutePath)
                put("LETTA_ANDROID_NETWORK_BRIDGE_URL", androidNetworkBridgeBaseUrl)
                put("LETTA_ANDROID_NETWORK_BRIDGE_TOKEN", androidNetworkBridgeToken)
                deviceSensorGroundingWriter?.let {
                    put("LETTA_MOBILE_DEVICE_SENSOR_GROUNDING_PATH", it.outputFile.absolutePath)
                }
                put("NODE_OPTIONS", "--max-old-space-size=384 --max-semi-space-size=16")
            },
            workingDirectory = workingDirectory,
        )
    }

    /**
     * Local memfs (letta-mobile-xa92p): letta.js memory-git spawns the
     * literal binary "git" via PATH, which Android lacks. We ship git built
     * for android-arm64 as a jniLib (libgit.so — app filesDir is noexec on
     * API 29+, but nativeLibraryDir is executable) and symlink it onto a
     * PATH entry for the node process. When the binary isn't packaged
     * (builds without scripts/build-android-git.sh output), memfs stays
     * disabled so turns keep working.
     */
    private fun PreparedLettaCodeProject.memfsEnvironment(): Map<String, String> {
        val binDirectory = File(storageDirectory.parentFile, "bin").apply { mkdirs() }
        // letta.js's Bash tool spawns the literal executable "bash" via PATH
        // on every non-Windows platform — no launcher fallback, no $SHELL
        // (shell-runner.ts spawnCommand). Android ships no bash, so without
        // this link every Bash tool call fails with "Executable not found:
        // bash". Prefer the bundled real bash (libbash.so, executable in
        // nativeLibraryDir); fall back to aliasing /system/bin/sh, which
        // runs simple commands but with toybox/mksh semantics, not bash's.
        val bashLib = File(context.applicationInfo.nativeLibraryDir, "libbash.so")
        val bashTarget = if (bashLib.canExecute()) bashLib.absolutePath else "/system/bin/sh"
        val bashLink = File(binDirectory, "bash")
        // nativeLibraryDir changes across installs; recreate unless the link
        // already resolves to the wanted target and is executable.
        val bashLinkCurrent = runCatching { android.system.Os.readlink(bashLink.absolutePath) }.getOrNull()
        if (bashLinkCurrent != bashTarget || !bashLink.canExecute()) {
            bashLink.delete()
            runCatching { android.system.Os.symlink(bashTarget, bashLink.absolutePath) }
                .onFailure { error -> Log.w(TAG, "Failed to link bash; Bash tool will be unavailable", error) }
        }
        linkPackagedTool(binDirectory, "curl", "libcurl.so", "curl helper")
        linkPackagedTool(binDirectory, "node", "libnodecli.so", "node CLI")
        // npm: the data partition is noexec, so a shell-script wrapper in
        // files/ can't be exec'd via PATH. Instead the node launcher is
        // argv0-aware — symlink `npm` to the same native binary and point it at
        // npm-cli.js via LETTA_NPM_CLI_JS. See letta-mobile-iq24j.
        val npmCli = File(projectDir, "node_modules/npm/bin/npm-cli.js")
        val nodeLink = File(binDirectory, "node")
        val npmEnvironment = if (npmCli.isFile && nodeLink.canExecute()) {
            linkPackagedTool(binDirectory, "npm", "libnodecli.so", "npm launcher")
            mapOf(
                "LETTA_NPM_CLI_JS" to npmCli.absolutePath,
                "LETTA_NODE_BIN" to nodeLink.absolutePath,
            )
        } else {
            Log.i(TAG, "npm-cli.js not bundled or node missing; npm unavailable")
            emptyMap()
        }
        val pathEnvironment = mapOf(
            "PATH" to "${binDirectory.absolutePath}:${System.getenv("PATH") ?: "/system/bin"}",
        ) + npmEnvironment
        if (!linkPackagedTool(binDirectory, "git", "libgit.so", "git-backed local memfs")) {
            return pathEnvironment + ("LETTA_LOCAL_BACKEND_NO_MEMFS" to "1")
        }
        // letta.js memory-git installs bash pre/post-commit hooks; the repo
        // lives on the noexec data partition (and Android has no bash), so
        // any hook exec fails the commit. Route hook lookup to an empty dir:
        // pre-commit is advisory frontmatter linting and post-commit only
        // pushes when a remote memory repository is configured.
        val disabledHooksDirectory = File(storageDirectory.parentFile, "git-hooks-disabled").apply { mkdirs() }
        // Regenerated every start — it's ours, and hooksPath must track the
        // current path.
        File(homeDirectory, ".gitconfig").writeText(
            """
            [user]
            	name = Letta Mobile
            	email = letta-mobile@localhost
            [core]
            	hooksPath = ${disabledHooksDirectory.absolutePath}
            [maintenance]
            	auto = false
            [safe]
            	directory = *
            """.trimIndent() + "\n",
        )
        return pathEnvironment + mapOf(
            "GIT_EXEC_PATH" to binDirectory.absolutePath,
            "GIT_TEMPLATE_DIR" to "",
            "GIT_CONFIG_NOSYSTEM" to "1",
        )
    }

    private fun PreparedLettaCodeProject.writeEmbeddedLettaCodeProviderAuth(baseUrl: String, apiKey: String) {
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
                                    put("key", apiKey)
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

    private fun linkPackagedTool(binDirectory: File, commandName: String, libraryName: String, label: String): Boolean {
        val library = File(context.applicationInfo.nativeLibraryDir, libraryName)
        if (!library.canExecute()) {
            Log.i(TAG, "$libraryName not packaged; $label unavailable")
            return false
        }
        val link = File(binDirectory, commandName)
        val current = runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull()
        if (current != library.absolutePath || !link.canExecute()) {
            link.delete()
            runCatching { android.system.Os.symlink(library.absolutePath, link.absolutePath) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to link $commandName; $label will be unavailable", error)
                    return false
                }
        }
        return link.canExecute()
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

    private class TerminalResultSeen : CancellationException()

    private companion object {
        private const val TAG = "LettaCodeRuntime"
        private const val TURN_TIMEOUT_MS = 120_000L

        /**
         * Vision-capable model id substrings (case-insensitive). KEEP IN SYNC
         * with the shim's VISION_MODEL_PATTERNS (admin-shim/lib/model-catalog.ts
         * → LETTA_VISION_MODELS) so the embedded runtime and the remote shim
         * agree on which custom models accept images (letta-mobile-nojhc).
         */
        private val VISION_MODEL_PATTERNS = listOf(
            "llava", "vision", "opus", "sonnet", "haiku", "claude", "fable",
            "gpt-", "gpt5", "gemini", "grok", "minimax",
            "qwen-vl", "qwen2-vl", "qwen2.5-vl",
        )
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
