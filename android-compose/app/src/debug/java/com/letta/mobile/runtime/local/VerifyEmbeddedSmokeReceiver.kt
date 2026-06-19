package com.letta.mobile.runtime.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.letta.mobile.BuildConfig
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.InMemoryRuntimeEventOutbox
import com.letta.mobile.runtime.LocalLettaBackend
import com.letta.mobile.runtime.RuntimeEventEnvelope
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.runtime.actions.DeviceActionCommandRunner
import com.letta.mobile.runtime.actions.InMemoryMobileActionAuditSink
import com.letta.mobile.runtime.actions.MobileActionRegistry
import com.letta.mobile.runtime.hardware.AndroidDeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.mobileactions.AndroidProviderReadTool
import com.letta.mobile.runtime.mobileactions.MobileIntentActionTool
import com.letta.mobile.runtime.sensors.AndroidDeviceSensorSnapshotProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dev-only ADB hook for the embedded-runtime merge gate.
 *
 * Usage:
 *   adb shell am broadcast -a com.letta.mobile.VERIFY_EMBEDDED \
 *     -n com.letta.mobile.dev/com.letta.mobile.runtime.local.VerifyEmbeddedSmokeReceiver \
 *     --es base_url http://192.168.50.90:8082/v1 --es model default
 */
class VerifyEmbeddedSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        val pendingResult = goAsync()
        Thread {
            runBlocking {
                VerifyEmbeddedSmoke(context.applicationContext, intent).run()
            }
            pendingResult.finish()
        }.start()
    }

    companion object {
        const val ACTION = "com.letta.mobile.VERIFY_EMBEDDED"
    }
}

private class VerifyEmbeddedSmoke(
    private val context: Context,
    private val intent: Intent,
) {
    private val bridgesToStop = mutableListOf<LettaCodeNodeBridge>()
    private val seededAgentIds = mutableListOf<String>()
    private val baseUrl = intent.getStringExtra("base_url")?.trim()?.trimEnd('/')
        ?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
    private val model = intent.getStringExtra("model")?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    private val apiKey = intent.getStringExtra("api_key")?.trim()?.takeIf { it.isNotBlank() } ?: "not-needed"

    suspend fun run() {
        Log.i(TAG, "VERIFY_EMBEDDED: START base_url=$baseUrl model=$model")
        try {
            pathwayLaunchAlive()
            pathwayRemoteModelStarts()
            pathwaySwitchTwoAgents()
            pathwayLocalTurnCompletes()
        } finally {
            bridgesToStop.forEach { bridge -> runCatching { bridge.stop() } }
            cleanUpSeededAgents()
            Log.i(TAG, "VERIFY_EMBEDDED: END")
        }
    }

    private fun pathwayLaunchAlive() {
        report(
            pathway = "launch_alive",
            ok = BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED && BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED,
            detail = "letta-mobile-esoaw process=${android.os.Process.myPid()} native=${BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED} assets=${BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED}",
        )
    }

    private suspend fun pathwayRemoteModelStarts() {
        val result = runRemoteTurns("remote_model_starts", listOf(agentId("remote-start")))
        val detail = result.detail
        report(
            pathway = "remote_model_starts",
            ok = result.ok && !detail.contains(LITERTLM_GATE, ignoreCase = true),
            detail = "letta-mobile-4qe0a ${result.proof} ${detail.oneLine()}",
        )
    }

    private suspend fun pathwaySwitchTwoAgents() {
        val result = runRemoteTurns("switch_two_agents", listOf(agentId("switch-a"), agentId("switch-b")))
        val detail = result.detail
        report(
            pathway = "switch_two_agents",
            ok = result.ok && !detail.contains(ALREADY_BOUND, ignoreCase = true),
            detail = "letta-mobile-st78v ${result.proof} ${detail.oneLine()}",
        )
    }

    private suspend fun pathwayLocalTurnCompletes() {
        val result = runRemoteTurns("local_turn_e2e", listOf(agentId("local-turn")))
        report(
            pathway = "local_turn_e2e",
            ok = result.ok,
            detail = "embedded-node->provider->assistant ${result.proof} ${result.detail.oneLine()}",
        )
    }

    private suspend fun runRemoteTurns(pathway: String, agents: List<AgentId>): TurnResult {
        if (!BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED || !BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED) {
            return TurnResult(false, "build missing embedded native/assets", "build_gate")
        }
        val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val sensorSnapshotProvider = AndroidDeviceSensorSnapshotProvider(context)
        val hardwareControlProvider = AndroidDeviceHardwareControlProvider(context)
        val mobileActionRegistry = MobileActionRegistry(emptySet(), emptySet(), InMemoryMobileActionAuditSink())
        val mobileIntentActionTool = MobileIntentActionTool(context)
        val hardwareControlTool = DeviceHardwareControlTool(hardwareControlProvider)
        val deviceActionCommandRunner = DeviceActionCommandRunner(
            sensorReadTool = com.letta.mobile.runtime.sensors.DeviceSensorReadTool(sensorSnapshotProvider),
            mobileActionRegistry = mobileActionRegistry,
            mobileIntentActionTool = mobileIntentActionTool,
            hardwareControlTool = hardwareControlTool,
            providerReadTool = AndroidProviderReadTool(context),
        )
        val controller = AndroidLettaCodeRuntimeController(
            context = context,
            assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
            localBackendStore = LettaCodeLocalBackendStore(context),
            androidNetworkBridge = LocalAndroidNetworkBridge(
                sensorSnapshotProvider = sensorSnapshotProvider,
                mobileActionRegistry = mobileActionRegistry,
                mobileIntentActionTool = mobileIntentActionTool,
                hardwareControlProvider = hardwareControlProvider,
                deviceActionCommandRunner = deviceActionCommandRunner,
            ),
            onDeviceOpenAiBridge = object : OnDeviceOpenAiBridge {
                override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession =
                    error("verify-embedded remote-provider path must not start on-device .litertlm bridge")
            },
        )
        val backend = LocalLettaBackend(
            descriptor = descriptor(pathway),
            engine = LettaCodeTurnEngine(
                client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                config = config(pathway),
            ),
            outbox = InMemoryRuntimeEventOutbox(
                eventIdFactory = { _, offset -> RuntimeEventId("verify-embedded-$pathway-${offset.value}") },
                clock = { EpochMillis(System.currentTimeMillis()) },
            ),
            memFsStore = NoopMemFsStore,
        )
        var proof = "reached_provider"
        var lastDetail = "no terminal event"
        for (agent in agents) {
            val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
                backend.runTurn(command(pathway, agent)).firstOrNull { envelope -> envelope.isTerminalForSmoke() }
            }
            val payload = event?.payload
            when (payload) {
                is RuntimeEventPayload.RemoteStreamFrame -> {
                    if (payload.body.isNotBlank()) {
                        proof = "full_response"
                        lastDetail = "assistant=${payload.body.take(120)}"
                    }
                }
                is RuntimeEventPayload.RunLifecycleChanged -> {
                    lastDetail = "status=${payload.status} reason=${payload.reason.orEmpty()}"
                    if (payload.status == RuntimeRunStatus.Failed) {
                        return TurnResult(false, lastDetail, proof)
                    }
                }
                null -> return TurnResult(false, "timeout waiting for terminal event", proof)
                else -> lastDetail = payload.toString()
            }
        }
        return TurnResult(true, lastDetail, proof)
    }

    private fun report(pathway: String, ok: Boolean, detail: String) {
        val status = if (ok) "PASS" else "FAIL"
        Log.i(TAG, "VERIFY_EMBEDDED: $pathway $status ${detail.oneLine()}")
    }

    private fun descriptor(pathway: String): BackendDescriptor = BackendDescriptor(
        backendId = BackendId("local-lettacode:verify-embedded:$pathway"),
        runtimeId = RuntimeId("local-lettacode:verify-embedded:$pathway"),
        kind = BackendKind.LocalLettaCode,
        label = "Verify embedded $pathway",
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsToolEvents = true,
            supportsToolExecution = true,
            supportsApprovals = false,
            supportsAgentFileImport = false,
            supportsAgentFileExport = false,
        ),
    )

    private fun config(pathway: String): LettaConfig = LettaConfig(
        id = "local-lettacode:verify-embedded:$pathway",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://device",
        localProviderBaseUrl = baseUrl,
        localProviderApiKey = apiKey,
        localProviderModel = if (model.startsWith("lmstudio/")) model else "lmstudio/$model",
    )

    private fun command(pathway: String, agent: AgentId): TurnCommand = TurnCommand(
        backendId = BackendId("local-lettacode:verify-embedded:$pathway"),
        runtimeId = RuntimeId("local-lettacode:verify-embedded:$pathway"),
        agentId = agent,
        conversationId = ConversationId("verify-embedded-conversation-${UUID.randomUUID()}"),
        input = TurnInput.UserMessage(
            localMessageId = "verify-embedded-message-${UUID.randomUUID()}",
            text = "Reply with exactly: verify-embedded-ok",
        ),
    )

    private fun agentId(suffix: String): AgentId = AgentId(
        "verify-embedded-$suffix-${UUID.randomUUID()}".also(seededAgentIds::add),
    )

    private fun RuntimeEventEnvelope.isTerminalForSmoke(): Boolean = when (val eventPayload = payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> eventPayload.body.isNotBlank()
        is RuntimeEventPayload.RunLifecycleChanged -> eventPayload.status == RuntimeRunStatus.Failed
        else -> false
    }

    private fun cleanUpSeededAgents() {
        val storage = File(context.filesDir, "embedded-lettacode/local-backend")
        seededAgentIds.forEach { agentId ->
            File(File(storage, "agents"), "${base64Url(agentId)}.json").delete()
            File(File(storage, "conversations"), base64Url("default:$agentId")).deleteRecursively()
            File(File(storage, "memfs"), agentId).deleteRecursively()
        }
        seededAgentIds.clear()
    }

    private fun base64Url(value: String): String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun String.oneLine(): String = replace(Regex("\\s+"), " ").take(500)

    private data class TurnResult(val ok: Boolean, val detail: String, val proof: String)

    private companion object {
        private const val TAG = "VerifyEmbedded"
        private const val DEFAULT_BASE_URL = "http://192.168.50.90:8082/v1"
        private const val DEFAULT_MODEL = "default"
        private const val LOCAL_TURN_TIMEOUT_MS = 180_000L
        private const val LITERTLM_GATE = "requires an imported .litertlm"
        private const val ALREADY_BOUND = "already bound"
    }

    private object NoopMemFsStore : com.letta.mobile.runtime.MemFsStore {
        override suspend fun read(path: com.letta.mobile.runtime.MemFsPath): com.letta.mobile.runtime.MemFsFile? = null
        override suspend fun write(command: com.letta.mobile.runtime.MemFsWriteCommand): com.letta.mobile.runtime.MemFsCommit =
            error("MemFS writes are not used by verify-embedded.")
        override suspend fun delete(command: com.letta.mobile.runtime.MemFsDeleteCommand): com.letta.mobile.runtime.MemFsCommit =
            error("MemFS deletes are not used by verify-embedded.")
        override fun commits(afterRevision: com.letta.mobile.runtime.MemFsRevision) = emptyFlow<com.letta.mobile.runtime.MemFsCommit>()
    }
}
