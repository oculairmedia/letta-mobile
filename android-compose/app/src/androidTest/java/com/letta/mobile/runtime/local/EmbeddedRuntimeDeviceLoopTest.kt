package com.letta.mobile.runtime.local

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
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
import com.letta.mobile.runtime.MemFsCommit
import com.letta.mobile.runtime.MemFsDeleteCommand
import com.letta.mobile.runtime.MemFsFile
import com.letta.mobile.runtime.MemFsPath
import com.letta.mobile.runtime.MemFsRevision
import com.letta.mobile.runtime.MemFsStore
import com.letta.mobile.runtime.MemFsWriteCommand
import com.letta.mobile.runtime.RuntimeEventEnvelope
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EmbeddedRuntimeDeviceLoopTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bridgesToStop = mutableListOf<LettaCodeNodeBridge>()
    private val seededAgentIds = mutableListOf<String>()

    @After
    fun stopBridges() = runBlocking {
        bridgesToStop.forEach { bridge -> bridge.stop() }
        bridgesToStop.clear()
        cleanUpSeededAgents()
    }

    /**
     * Tier 3 seeds real agent/conversation/memfs records into the shared
     * local-backend store; without cleanup every run leaves a dead
     * "device-loop-agent-…" conversation in the app's conversations screen.
     */
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

    @Test
    fun tier1NodeBootSmokePrintsEmbeddedNodeVersion() = runBlocking {
        assumeEmbeddedNative()
        val bridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val workingDirectory = File(context.filesDir, "embedded-node-smoke").apply { mkdirs() }
        val output = async(Dispatchers.Default) {
            withTimeoutOrNull(NODE_SMOKE_TIMEOUT_MS) {
                bridge.outputLines.first { line -> line.contains(EXPECTED_NODE_VERSION) }
            }
        }

        val started = bridge.start(
            LettaCodeNodeStartRequest(
                arguments = listOf("node", "-e", "console.log(process.version)"),
                environment = mapOf(
                    "HOME" to File(workingDirectory, "home").apply { mkdirs() }.absolutePath,
                    "NO_COLOR" to "1",
                    "UV_USE_IO_URING" to "0",
                    "UV_THREADPOOL_SIZE" to "2",
                    "NODE_OPTIONS" to "--max-old-space-size=384 --max-semi-space-size=16",
                ),
                workingDirectory = workingDirectory,
            )
        )

        assertTrue(started.exceptionOrNull()?.stackTraceToString(), started.isSuccess)
        assertEquals(EXPECTED_NODE_VERSION, output.await()?.trim())
        SystemClock.sleep(NODE_EXIT_SETTLE_MS)
        val stopped = bridge.stop()
        assertTrue(stopped.exceptionOrNull()?.stackTraceToString(), stopped.isSuccess)
    }

    @Test
    fun tier2RuntimeStatusReportsRunnableForEmbeddedBuild() {
        assumeEmbeddedNative()
        val status = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider().status

        assertTrue("embedded assets must be enabled for runnable status", status.assetsEnabled)
        assertTrue("embedded runtime should be runnable", status.runnable)
        // The version carries the asset revision suffix (e.g. 0.26.1-r6);
        // pin only the letta-code release.
        assertTrue(
            "unexpected embedded version: ${status.version}",
            status.version.startsWith("0.26.1"),
        )
    }

    @Test
    fun tier3LocalTurnProducesRuntimeEventWhenModelExists() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 3 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val model = findLitertLmModel()
        assumeTrue("Tier 3 requires a .litertlm under filesDir/embedded-lettacode/models", model != null)
        requireNotNull(model)

        val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val controller = AndroidLettaCodeRuntimeController(
            context = context,
            assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
            localBackendStore = LettaCodeLocalBackendStore(context),
            onDeviceOpenAiBridge = LocalOpenAiOnDeviceBridge(
                engine = object : OnDeviceChatCompletionEngine {
                    override fun generate(
                        modelSelection: EmbeddedLettaCodeModelSelection,
                        prompt: String,
                    ): Result<String> = Result.success("device-loop-ok")
                }
            ),
        )
        val backend = LocalLettaBackend(
            descriptor = descriptor(),
            engine = LettaCodeTurnEngine(
                client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                config = config(model),
            ),
            outbox = InMemoryRuntimeEventOutbox(
                eventIdFactory = { _, offset -> RuntimeEventId("device-loop-${offset.value}") },
                clock = { EpochMillis(System.currentTimeMillis()) },
            ),
            memFsStore = NoopMemFsStore,
        )

        val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
            backend.runTurn(command()).firstOrNull { envelope -> envelope.isAssistantTextOrCleanFailure() }
        }

        assertTrue("expected assistant text or clean failure runtime event", event != null)
        if (requireAssistantText()) {
            val payload = event?.payload
            assertTrue(
                "strict mode: expected assistant text but got $payload",
                payload is RuntimeEventPayload.RemoteStreamFrame && payload.body.isNotBlank(),
            )
        }
    }

    /**
     * letta-mobile-69i0z: full tool-call round trip on device. The scripted
     * engine first replies with a tool_call (Bash echo); letta.js must parse
     * it through the bridge's OpenAI translation, EXECUTE the tool in-process
     * (sh exists on Android), feed the tool result back, and only then does
     * the engine produce final text. Assistant text therefore proves the
     * entire loop: prompt-format tools → tool_calls → execution → result →
     * follow-up generation.
     */
    @Test
    fun tier4LocalToolCallRoundTripExecutesToolAndCompletes() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 4 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val model = findLitertLmModel()
        assumeTrue("Tier 4 requires a .litertlm under filesDir/embedded-lettacode/models", model != null)
        requireNotNull(model)

        val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val controller = AndroidLettaCodeRuntimeController(
            context = context,
            assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
            localBackendStore = LettaCodeLocalBackendStore(context),
            onDeviceOpenAiBridge = LocalOpenAiOnDeviceBridge(
                engine = object : OnDeviceChatCompletionEngine {
                    override fun generate(
                        modelSelection: EmbeddedLettaCodeModelSelection,
                        prompt: String,
                    ): Result<String> = Result.success(
                        if (prompt.contains("tool result")) {
                            "tool-loop-ok"
                        } else {
                            "```tool_call\n{\"name\": \"Bash\", \"arguments\": {\"command\": \"echo device-tool-roundtrip\"}}\n```"
                        }
                    )
                }
            ),
        )
        val backend = LocalLettaBackend(
            descriptor = descriptor(),
            engine = LettaCodeTurnEngine(
                client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                config = config(model),
            ),
            outbox = InMemoryRuntimeEventOutbox(
                eventIdFactory = { _, offset -> RuntimeEventId("device-loop-${offset.value}") },
                clock = { EpochMillis(System.currentTimeMillis()) },
            ),
            memFsStore = NoopMemFsStore,
        )

        val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
            backend.runTurn(command()).firstOrNull { envelope ->
                val payload = envelope.payload
                payload is RuntimeEventPayload.RemoteStreamFrame && payload.body.contains("tool-loop-ok")
            }
        }

        assertTrue(
            "expected the post-tool assistant text to arrive (tool round trip)",
            event != null,
        )
    }

    /**
     * letta-mobile-3icw7: custom OpenAI-compatible endpoint ("remote brain,
     * local agent"). A second bridge instance plays the remote endpoint; the
     * controller must route letta.js at it, require NO .litertlm model, and
     * never start its own on-device bridge. The scripted endpoint demands a
     * tool round trip before answering, proving native tool calling through
     * the custom provider.
     */
    @Test
    fun tier5CustomProviderTurnSkipsOnDeviceModelAndCallsTools() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 5 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)

        // Plays the user's remote OpenAI-compatible server.
        val remoteEndpoint = LocalOpenAiOnDeviceBridge(
            engine = object : OnDeviceChatCompletionEngine {
                override fun generate(
                    modelSelection: EmbeddedLettaCodeModelSelection,
                    prompt: String,
                ): Result<String> = Result.success(
                    if (prompt.contains("tool result")) {
                        "custom-provider-ok"
                    } else {
                        "```tool_call\n{\"name\": \"Bash\", \"arguments\": {\"command\": \"echo custom-provider-tool\"}}\n```"
                    }
                )
            }
        ).start(
            EmbeddedLettaCodeModelSelection(
                modelHandle = "remote-test-model",
                modelPath = null,
                runtime = "openai",
                accelerator = "cpu",
                maxTokens = 1024,
            )
        )

        try {
            val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
            val controller = AndroidLettaCodeRuntimeController(
                context = context,
                assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
                nodeBridge = nodeBridge,
                runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
                localBackendStore = LettaCodeLocalBackendStore(context),
                onDeviceOpenAiBridge = object : OnDeviceOpenAiBridge {
                    override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession =
                        error("on-device bridge must not start when a custom provider is configured")
                },
            )
            val backend = LocalLettaBackend(
                descriptor = descriptor(),
                engine = LettaCodeTurnEngine(
                    client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                    config = LettaConfig(
                        id = BACKEND_ID.value,
                        mode = LettaConfig.Mode.LOCAL,
                        serverUrl = "local-lettacode://device",
                        localProviderBaseUrl = remoteEndpoint.baseUrl,
                        localProviderModel = "remote-test-model",
                    ),
                ),
                outbox = InMemoryRuntimeEventOutbox(
                    eventIdFactory = { _, offset -> RuntimeEventId("device-loop-${offset.value}") },
                    clock = { EpochMillis(System.currentTimeMillis()) },
                ),
                memFsStore = NoopMemFsStore,
            )

            val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
                backend.runTurn(command()).firstOrNull { envelope ->
                    val payload = envelope.payload
                    payload is RuntimeEventPayload.RemoteStreamFrame && payload.body.contains("custom-provider-ok")
                }
            }

            assertTrue(
                "expected assistant text from the custom provider after a tool round trip",
                event != null,
            )
        } finally {
            remoteEndpoint.close()
        }
    }

    /**
     * Manual/dev-only smoke against a REAL OpenAI-compatible endpoint.
     * Skipped unless instrumentation args provide the endpoint:
     *   -e customProviderBaseUrl http://host:port/v1 [-e customProviderModel <id>]
     */
    @Test
    fun tier6RealCustomProviderProducesAssistantText() = runBlocking {
        assumeEmbeddedNative()
        assumeTrue("Tier 6 requires embedded LettaCode assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString("customProviderBaseUrl")
        assumeTrue("pass -e customProviderBaseUrl to run tier6", !baseUrl.isNullOrBlank())
        requireNotNull(baseUrl)
        val model = arguments.getString("customProviderModel") ?: "default"

        val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
        val controller = AndroidLettaCodeRuntimeController(
            context = context,
            assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
            localBackendStore = LettaCodeLocalBackendStore(context),
            onDeviceOpenAiBridge = object : OnDeviceOpenAiBridge {
                override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession =
                    error("on-device bridge must not start when a custom provider is configured")
            },
        )
        val backend = LocalLettaBackend(
            descriptor = descriptor(),
            engine = LettaCodeTurnEngine(
                client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                config = LettaConfig(
                    id = BACKEND_ID.value,
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local-lettacode://device",
                    localProviderBaseUrl = baseUrl,
                    localProviderModel = model,
                ),
            ),
            outbox = InMemoryRuntimeEventOutbox(
                eventIdFactory = { _, offset -> RuntimeEventId("device-loop-${offset.value}") },
                clock = { EpochMillis(System.currentTimeMillis()) },
            ),
            memFsStore = NoopMemFsStore,
        )

        val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
            backend.runTurn(command()).firstOrNull { envelope ->
                val payload = envelope.payload
                payload is RuntimeEventPayload.RemoteStreamFrame &&
                    payload.messageType == "assistant_message" &&
                    payload.body.isNotBlank()
            }
        }

        assertTrue("expected assistant text from the real custom provider", event != null)
    }

    /**
     * When run with -Pandroid.testInstrumentationRunnerArguments.requireAssistantText=true
     * a "clean failure" lifecycle event is NOT accepted — only real assistant text passes.
     * This is how the device loop distinguishes "runtime crashed politely" from
     * "the on-device model actually answered".
     */
    private fun requireAssistantText(): Boolean =
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("requireAssistantText") == "true"

    private fun assumeEmbeddedNative() {
        assumeTrue(
            "Embedded LettaCode native runtime is disabled; build with -PembedLettaCodeNative=true",
            BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED,
        )
    }

    private fun findLitertLmModel(): File? = File(context.filesDir, "embedded-lettacode/models")
        .walkTopDown()
        .firstOrNull { file -> file.isFile && file.extension == "litertlm" }

    private fun descriptor(): BackendDescriptor = BackendDescriptor(
        backendId = BACKEND_ID,
        runtimeId = RUNTIME_ID,
        kind = BackendKind.LocalLettaCode,
        label = "Embedded LettaCode device loop",
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsTools = true,
            supportsApprovals = false,
            supportsAgentFileImport = false,
            supportsAgentFileExport = false,
        ),
    )

    private fun config(model: File): LettaConfig = LettaConfig(
        id = BACKEND_ID.value,
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://device",
        localModelHandle = "local/${model.nameWithoutExtension}",
        localModelPath = model.absolutePath,
        localModelRuntime = EmbeddedLettaCodeModelSelection.DEFAULT_MODEL_RUNTIME,
        // Overridable for accelerator-specific repros:
        // -e localModelAccelerator gpu
        localModelAccelerator = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("localModelAccelerator") ?: "cpu",
        localModelMaxTokens = 256,
    )

    private fun command(): TurnCommand = TurnCommand(
        backendId = BACKEND_ID,
        runtimeId = RUNTIME_ID,
        agentId = AgentId("device-loop-agent-${UUID.randomUUID()}".also(seededAgentIds::add)),
        conversationId = ConversationId("device-loop-conversation-${UUID.randomUUID()}"),
        input = TurnInput.UserMessage(
            localMessageId = "device-loop-message-${UUID.randomUUID()}",
            text = "Reply with a short device loop acknowledgement.",
        ),
    )

    private fun RuntimeEventEnvelope.isAssistantTextOrCleanFailure(): Boolean = when (val eventPayload = payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> eventPayload.body.isNotBlank()
        is RuntimeEventPayload.RunLifecycleChanged -> eventPayload.status == RuntimeRunStatus.Failed
        else -> false
    }

    private object NoopMemFsStore : MemFsStore {
        override suspend fun read(path: MemFsPath): MemFsFile? = null

        override suspend fun write(command: MemFsWriteCommand): MemFsCommit =
            error("MemFS writes are not used by device-loop tests.")

        override suspend fun delete(command: MemFsDeleteCommand): MemFsCommit =
            error("MemFS deletes are not used by device-loop tests.")

        override fun commits(afterRevision: MemFsRevision) = emptyFlow<MemFsCommit>()
    }

    private companion object {
        private const val EXPECTED_NODE_VERSION = "v18.20.4"
        private const val NODE_SMOKE_TIMEOUT_MS = 30_000L
        private const val NODE_EXIT_SETTLE_MS = 1_000L
        private const val LOCAL_TURN_TIMEOUT_MS = 180_000L
        private val BACKEND_ID = BackendId("local-lettacode:device-loop")
        private val RUNTIME_ID = RuntimeId("local-lettacode:device-loop")
    }
}
