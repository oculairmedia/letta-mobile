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

    @After
    fun stopBridges() = runBlocking {
        bridgesToStop.forEach { bridge -> bridge.stop() }
        bridgesToStop.clear()
    }

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
        assertEquals("0.26.1", status.version)
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
                client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper()),
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
    }

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
        localModelAccelerator = "cpu",
        localModelMaxTokens = 256,
    )

    private fun command(): TurnCommand = TurnCommand(
        backendId = BACKEND_ID,
        runtimeId = RUNTIME_ID,
        agentId = AgentId("device-loop-agent-${UUID.randomUUID()}"),
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
