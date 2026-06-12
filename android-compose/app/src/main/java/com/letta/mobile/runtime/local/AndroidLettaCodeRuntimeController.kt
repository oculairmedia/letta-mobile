package com.letta.mobile.runtime.local

import android.content.Context
import android.util.Log
import com.letta.mobile.data.model.LettaConfig
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

@Singleton
class AndroidLettaCodeRuntimeController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetExtractor: EmbeddedLettaCodeAssetExtractor,
    private val nodeBridge: LettaCodeNodeBridge,
    private val runtimeStatusProvider: EmbeddedLettaCodeRuntimeStatusProvider,
    private val onDeviceOpenAiBridge: OnDeviceOpenAiBridge,
    private val localBackendStore: LettaCodeLocalBackendStore,
) : LettaCodeRuntimeController {
    private val submitMutex = Mutex()
    private val startMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var activeSession: EmbeddedLettaCodeSessionKey? = null
    private var activeOnDeviceBridgeSession: OnDeviceOpenAiBridgeSession? = null

    override fun submit(command: TurnCommand, config: LettaConfig): Flow<String> = channelFlow {
        if (command.input is TurnInput.ToolApprovalResponse) {
            Log.w(TAG, "Ignoring tool approval response for embedded LettaCode; approvals are not supported yet.")
            return@channelFlow
        }
        submitMutex.withLock {
            ensureStarted(command, config)
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
                        "Enable embedded native and asset prerequisites before selecting local-lettacode://device.",
                )
            }
            val modelPath = modelSelection.modelPath?.let(::File)
                ?: throw IllegalStateException(
                    "Embedded LettaCode requires an imported .litertlm model path before it can start.",
                )
            if (!modelPath.isFile) {
                throw IllegalStateException(
                    "Embedded LettaCode model file was not found at ${modelPath.absolutePath}.",
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
        val modelHandle =
            if (onDeviceProviderBaseUrl == null) modelSelection.modelHandle else modelSelection.lettaCodeModelHandle
        localBackendStore.seedAgent(session.agentId, modelHandle)
        if (onDeviceProviderBaseUrl != null) {
            writeEmbeddedLettaCodeProviderAuth(onDeviceProviderBaseUrl)
        }
        return LettaCodeNodeStartRequest(
            arguments = buildList {
                add("node")
                add("--max-old-space-size=384")
                add("--max-semi-space-size=16")
                // ICU-less V8 rejects \p{...} regexes; preload a RegExp wrapper
                // that rewrites them through regexpu-core (see asset prep task).
                add("--require")
                add(File(projectDir, "regexp-polyfill.cjs").absolutePath)
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
            },
            environment = buildMap {
                put("HOME", homeDirectory.absolutePath)
                put("LETTA_LOCAL_BACKEND_EXPERIMENTAL", "1")
                put("LETTA_LOCAL_BACKEND_DIR", storageDirectory.absolutePath)
                put("LETTA_LOCAL_BACKEND_EXECUTOR", "pi")
                putAll(memfsEnvironment())
                put("LETTA_ANDROID_ON_DEVICE_MODEL_HANDLE", modelSelection.modelHandle)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_RUNTIME", modelSelection.runtime)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_ACCELERATOR", modelSelection.accelerator)
                put("LETTA_ANDROID_ON_DEVICE_MODEL_MAX_TOKENS", modelSelection.maxTokens.toString())
                put("LETTA_ANDROID_ON_DEVICE_MODEL_CACHE_DIR", modelCacheDirectory.absolutePath)
                onDeviceProviderBaseUrl?.let { put("LMSTUDIO_BASE_URL", it) }
                modelSelection.modelPath?.let { put("LETTA_ANDROID_ON_DEVICE_MODEL_PATH", it) }
                put("NO_COLOR", "1")
                put("UV_USE_IO_URING", "0")
                put("UV_THREADPOOL_SIZE", "2")
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
        val gitLib = File(context.applicationInfo.nativeLibraryDir, "libgit.so")
        if (!gitLib.canExecute()) {
            Log.i(TAG, "libgit.so not packaged; local memfs disabled")
            return mapOf("LETTA_LOCAL_BACKEND_NO_MEMFS" to "1")
        }
        val binDirectory = File(storageDirectory.parentFile, "bin").apply { mkdirs() }
        val gitLink = File(binDirectory, "git")
        // nativeLibraryDir changes across installs, so an existing link may
        // dangle; canExecute() follows the link and catches that.
        if (!gitLink.canExecute()) {
            gitLink.delete()
            try {
                android.system.Os.symlink(gitLib.absolutePath, gitLink.absolutePath)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to link git; local memfs disabled", error)
                return mapOf("LETTA_LOCAL_BACKEND_NO_MEMFS" to "1")
            }
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
        return mapOf(
            "PATH" to "${binDirectory.absolutePath}:${System.getenv("PATH") ?: "/system/bin"}",
            "GIT_EXEC_PATH" to binDirectory.absolutePath,
            "GIT_TEMPLATE_DIR" to "",
            "GIT_CONFIG_NOSYSTEM" to "1",
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
    }
}

data class EmbeddedLettaCodeSessionKey(
    val agentId: String,
    val conversationId: String,
    val modelKey: String,
)
