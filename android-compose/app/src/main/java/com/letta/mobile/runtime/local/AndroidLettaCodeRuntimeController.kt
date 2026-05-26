package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.BuildConfig
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import dagger.hilt.android.qualifiers.ApplicationContext
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

interface LettaCodeRuntimeController {
    fun submit(command: TurnCommand): Flow<String>
}

@Singleton
class AndroidLettaCodeRuntimeController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetExtractor: EmbeddedLettaCodeAssetExtractor,
    private val nodeBridge: LettaCodeNodeBridge,
) : LettaCodeRuntimeController {
    private val submitMutex = Mutex()
    private val startMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var activeSession: SessionKey? = null

    override fun submit(command: TurnCommand): Flow<String> = channelFlow {
        submitMutex.withLock {
            ensureStarted(command)
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

    private suspend fun ensureStarted(command: TurnCommand) {
        startMutex.withLock {
            val requestedSession = SessionKey(
                agentId = command.agentId.value,
                conversationId = command.conversationId.value,
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

            if (!BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED || !BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED) {
                throw IllegalStateException(
                    "Embedded LettaCode is disabled in this build. " +
                        "Enable -PembedLettaCodeNative=true -PembedLettaCodeAssets=true to run it on device.",
                )
            }

            LocalLettaCodeService.start(context)
            val project = assetExtractor.prepare()
            nodeBridge.start(project.startRequest(requestedSession)).getOrThrow()
            activeSession = requestedSession
        }
    }

    private fun PreparedLettaCodeProject.startRequest(session: SessionKey): LettaCodeNodeStartRequest {
        workingDirectory.mkdirs()
        storageDirectory.mkdirs()
        homeDirectory.mkdirs()
        return LettaCodeNodeStartRequest(
            arguments = listOf(
                "node",
                entrypoint.absolutePath,
                "--backend",
                "local",
                "--agent",
                session.agentId,
                "--conversation",
                session.conversationId,
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
            ),
            environment = mapOf(
                "HOME" to homeDirectory.absolutePath,
                "LETTA_LOCAL_BACKEND_EXPERIMENTAL" to "1",
                "LETTA_LOCAL_BACKEND_DIR" to storageDirectory.absolutePath,
                "NO_COLOR" to "1",
            ),
            workingDirectory = workingDirectory,
        )
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

    private data class SessionKey(
        val agentId: String,
        val conversationId: String,
    )

    private class TerminalResultSeen : CancellationException()

    private companion object {
        private const val TURN_TIMEOUT_MS = 120_000L
    }
}
