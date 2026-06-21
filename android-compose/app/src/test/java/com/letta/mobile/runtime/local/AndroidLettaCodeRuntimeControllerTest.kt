package com.letta.mobile.runtime.local

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.ToolApprovalDecision
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalScope
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLettaCodeRuntimeControllerTest {
    @Test
    fun `disabled status fails before starting bridge or node`() = runTest {
        val nodeBridge = FakeNodeBridge()
        val onDeviceBridge = mockk<OnDeviceOpenAiBridge>(relaxed = true)
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = mockk(relaxed = true),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = false),
            onDeviceOpenAiBridge = onDeviceBridge,
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        val error = runCatching { controller.submit(command(), config()).first() }.exceptionOrNull()

        assertEquals(
            "Embedded LettaCode is disabled in this build. " +
                "Enable embedded native and asset prerequisites before selecting local-lettacode://device.",
            error?.message,
        )
        assertFalse(nodeBridge.started)
        coVerify(exactly = 0) { onDeviceBridge.start(any()) }
    }

    @Test
    fun `missing model path fails before starting bridge or node`() = runTest {
        val nodeBridge = FakeNodeBridge()
        val onDeviceBridge = mockk<OnDeviceOpenAiBridge>(relaxed = true)
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = mockk(relaxed = true),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = onDeviceBridge,
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        val error = runCatching { controller.submit(command(), config()).first() }.exceptionOrNull()

        assertEquals(
            "Embedded LettaCode requires an imported .litertlm model path before it can start.",
            error?.message,
        )
        assertFalse(nodeBridge.started)
        coVerify(exactly = 0) { onDeviceBridge.start(any()) }
    }

    @Test
    fun `missing model file fails before starting bridge or node`() = runTest {
        val nodeBridge = FakeNodeBridge()
        val onDeviceBridge = mockk<OnDeviceOpenAiBridge>(relaxed = true)
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = mockk(relaxed = true),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = onDeviceBridge,
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        val missingModel = File(
            System.getProperty("java.io.tmpdir"),
            "letta-missing-model-${System.nanoTime()}.litertlm",
        )
        val error = runCatching {
            controller.submit(command(), config(localModelPath = missingModel.absolutePath)).first()
        }.exceptionOrNull()

        assertEquals(
            "Embedded LettaCode model file was not found at ${missingModel.absolutePath}.",
            error?.message,
        )
        assertFalse(nodeBridge.started)
        coVerify(exactly = 0) { onDeviceBridge.start(any()) }
    }

    @Test
    fun `lmstudio-prefixed on-device model starts bridge and registers bare lookup id`() = runTest {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit
        val modelFile = File(System.getProperty("java.io.tmpdir"), "letta-model-${System.nanoTime()}.litertlm")
            .apply { writeText("model") }
        val baseDir = File(System.getProperty("java.io.tmpdir"), "letta-on-device-model-${System.nanoTime()}").apply { mkdirs() }
        val nodeBridge = FakeNodeBridge()
        val onDeviceBridge = spyk(FakeOnDeviceBridge())
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = FakeAssetExtractor(baseDir),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = onDeviceBridge,
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        runCatching {
            controller.submit(
                command(),
                config(
                    localModelPath = modelFile.absolutePath,
                    localModelHandle = "lmstudio/google/gemma-3n-E2B-it-litert-lm",
                ),
            ).first()
        }

        val authJson = File(baseDir, "storage/providers/auth.json").readText()
        assertTrue(nodeBridge.started)
        assertEquals("http://127.0.0.1:2/v1", nodeBridge.lastRequest?.environment?.get("LMSTUDIO_BASE_URL"))
        assertEquals("openai-loopback-token", nodeBridge.lastRequest?.environment?.get("LMSTUDIO_API_KEY"))
        assertTrue(authJson.contains("\"id\": \"google/gemma-3n-E2B-it-litert-lm\""))
        assertFalse(authJson.contains("\"id\": \"lmstudio/google/gemma-3n-E2B-it-litert-lm\""))
        coVerify(exactly = 1) { onDeviceBridge.start(any()) }
    }

    @Test
    fun `tool approval responses are ignored before starting bridge or node`() = runTest {
        val nodeBridge = FakeNodeBridge()
        val onDeviceBridge = mockk<OnDeviceOpenAiBridge>(relaxed = true)
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = mockk(relaxed = true),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = onDeviceBridge,
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        val events = mutableListOf<String>()
        controller.submit(toolApprovalCommand(), config()).collect { events += it }

        assertEquals(emptyList<String>(), events)
        assertFalse(nodeBridge.started)
        coVerify(exactly = 0) { onDeviceBridge.start(any()) }
    }

    @Test
    fun `on-device bridge token is injected as lmstudio api key`() = runTest {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit
        val modelFile = File.createTempFile("model", ".litertlm").apply { deleteOnExit() }
        val baseDir = requireNotNull(modelFile.parentFile)
        val nodeBridge = FakeNodeBridge()
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = FakeAssetExtractor(baseDir),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = FakeOnDeviceBridge(),
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        runCatching { controller.submit(command(), config(localModelPath = modelFile.absolutePath)).first() }

        assertEquals("http://127.0.0.1:2/v1", nodeBridge.lastRequest?.environment?.get("LMSTUDIO_BASE_URL"))
        assertEquals("openai-loopback-token", nodeBridge.lastRequest?.environment?.get("LMSTUDIO_API_KEY"))
    }


    @Test
    fun `first session starts node once and same session does not restart`() = runTest {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit
        val baseDir = File(System.getProperty("java.io.tmpdir"), "letta-same-session-${System.nanoTime()}").apply { mkdirs() }
        val modelPath = tempModelPath()
        val nodeBridge = FakeNodeBridge()
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = FakeAssetExtractor(baseDir),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = mockk(relaxed = true),
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        controller.submit(command(), config(localModelPath = modelPath, localModelHandle = "lmstudio/gemma")).first()
        controller.submit(command(), config(localModelPath = modelPath, localModelHandle = "lmstudio/gemma")).first()

        assertTrue(nodeBridge.started)
        assertEquals(1, nodeBridge.startCalls)
        assertEquals(2, nodeBridge.writtenLines.count { it.contains("\"type\":\"user\"") })
        assertFalse(nodeBridge.writtenLines.any { it.contains("switch_session") })
    }

    @Test
    fun `switching session sends live stdin switch without restarting node`() = runTest {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit
        val baseDir = File(System.getProperty("java.io.tmpdir"), "letta-switch-session-${System.nanoTime()}").apply { mkdirs() }
        val modelPath = tempModelPath()
        val nodeBridge = FakeNodeBridge()
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = FakeAssetExtractor(baseDir),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = mockk(relaxed = true),
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        controller.submit(command(), config(localModelPath = modelPath, localModelHandle = "lmstudio/gemma")).first()
        controller.submit(
            command(agentId = "agent-2", conversationId = "conv-2"),
            config(localModelPath = modelPath, localModelHandle = "lmstudio/gemma"),
        ).first()

        assertTrue(nodeBridge.started)
        assertEquals(1, nodeBridge.startCalls)
        assertEquals(0, nodeBridge.stopCalls)
        assertTrue(nodeBridge.writtenLines.any { it.contains("\"subtype\":\"interrupt\"") })
        val switchLine = nodeBridge.writtenLines.first { it.contains("\"subtype\":\"switch_session\"") }
        assertTrue(switchLine.contains("\"agent_id\":\"agent-2\""))
        assertTrue(switchLine.contains("\"conversation_id\":\"conv-2\""))
    }

    @Test
    fun `switching embedded model restarts node with selected model instead of live session switch`() = runTest {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit
        val baseDir = File(System.getProperty("java.io.tmpdir"), "letta-switch-model-${System.nanoTime()}").apply { mkdirs() }
        val gemmaPath = tempModelPath()
        val deepSeekPath = tempModelPath()
        val nodeBridge = FakeNodeBridge()
        val onDeviceBridge = FakeOnDeviceBridge()
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = FakeAssetExtractor(baseDir),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = onDeviceBridge,
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        controller.submit(command(), config(localModelPath = gemmaPath, localModelHandle = "lmstudio/gemma")).first()
        controller.submit(command(), config(localModelPath = deepSeekPath, localModelHandle = "lmstudio/deepseek")).first()

        assertEquals(2, nodeBridge.startCalls)
        assertEquals(1, nodeBridge.stopCalls)
        assertEquals(2, onDeviceBridge.startCalls)
        assertEquals(1, onDeviceBridge.closeCalls)
        assertFalse(nodeBridge.writtenLines.any { it.contains("switch_session") })
        assertEquals("lmstudio/deepseek", nodeBridge.lastRequest?.environment?.get("LETTA_ANDROID_ON_DEVICE_MODEL_HANDLE"))
        assertEquals(deepSeekPath, nodeBridge.lastRequest?.environment?.get("LETTA_ANDROID_ON_DEVICE_MODEL_PATH"))
    }

    @Test
    fun `config selected embedded model wins over stale stored model handle`() = runTest {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit
        val baseDir = File(System.getProperty("java.io.tmpdir"), "letta-stale-stored-model-${System.nanoTime()}").apply { mkdirs() }
        val deepSeekPath = tempModelPath()
        val localBackendStore = mockk<LettaCodeLocalBackendStore>(relaxed = true)
        coEvery { localBackendStore.storedModelHandle("agent-1") } returns "lmstudio/gemma"
        val nodeBridge = FakeNodeBridge()
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = FakeAssetExtractor(baseDir),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = FakeOnDeviceBridge(),
            localBackendStore = localBackendStore,
            androidNetworkBridge = FakeAndroidNetworkBridge(),
        )

        controller.submit(command(), config(localModelPath = deepSeekPath, localModelHandle = "lmstudio/deepseek")).first()

        assertEquals("lmstudio/deepseek", nodeBridge.lastRequest?.environment?.get("LETTA_ANDROID_ON_DEVICE_MODEL_HANDLE"))
        assertEquals(deepSeekPath, nodeBridge.lastRequest?.environment?.get("LETTA_ANDROID_ON_DEVICE_MODEL_PATH"))
        coVerify(exactly = 0) { localBackendStore.storedModelHandle(any()) }
        coVerify { localBackendStore.seedAgent("agent-1", "lmstudio/deepseek") }
    }

    @Test
    fun `streaming output keeps turn alive past old total timeout`() = runTest {
        val nodeBridge = FakeNodeBridge(
            outputLines = flow {
                repeat(5) { index ->
                    delay(40_000L)
                    emit("""{"type":"stream_event","event":{"type":"reasoning_message","message":"$index"}}""")
                }
                emit("""{"type":"result"}""")
            }
        )
        val controller = controller(nodeBridge, turnSilenceMs = 120_000L, turnAbsoluteMaxMs = 300_000L)

        val events = controller.submit(command(), config(localModelPath = tempModelPath(), localModelHandle = "lmstudio/minimax-m3")).let { flow -> async { flow.toList() } }

        advanceTimeBy(160_000L)
        runCurrent()

        assertFalse(events.isCompleted)
        advanceTimeBy(40_000L)
        runCurrent()
        assertEquals(6, events.await().size)
    }

    @Test
    fun `silent turn times out after idle window`() = runTest {
        val nodeBridge = FakeNodeBridge(outputLines = MutableSharedFlow<String>())
        val controller = controller(nodeBridge, turnSilenceMs = 1_000L, turnAbsoluteMaxMs = 10_000L)

        val events = controller.submit(command(), config(localModelPath = tempModelPath(), localModelHandle = "lmstudio/minimax-m3")).let { flow -> async { flow.toList() } }
        advanceTimeBy(1_000L)
        runCurrent()

        val error = runCatching { events.await() }.exceptionOrNull()
        assertEquals("Embedded LettaCode turn produced no output for 1s.", error?.message)
    }

    @Test
    fun `absolute ceiling bounds output spamming turn`() = runTest {
        val nodeBridge = FakeNodeBridge(
            outputLines = flow {
                while (true) {
                    delay(500L)
                    emit("""{"type":"stream_event","event":{"type":"status","message":"working"}}""")
                }
            }
        )
        val controller = controller(nodeBridge, turnSilenceMs = 1_000L, turnAbsoluteMaxMs = 3_000L)

        val events = controller.submit(command(), config(localModelPath = tempModelPath(), localModelHandle = "lmstudio/minimax-m3")).let { flow -> async { flow.toList() } }
        advanceTimeBy(3_000L)
        runCurrent()

        val error = runCatching { events.await() }.exceptionOrNull()
        assertEquals("Embedded LettaCode turn exceeded absolute maximum of 3s.", error?.message)
    }

    @Test
    fun `cancel interrupts turn promptly`() = runTest {
        val nodeBridge = FakeNodeBridge(outputLines = MutableSharedFlow<String>())
        val controller = controller(nodeBridge, turnSilenceMs = 120_000L, turnAbsoluteMaxMs = 300_000L)

        val job = launch { controller.submit(command(), config(localModelPath = tempModelPath(), localModelHandle = "lmstudio/minimax-m3")).collect {} }
        runCurrent()
        job.cancel(CancellationException("stop"))
        runCurrent()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `model selection normalizes config defaults and handles`() {
        val selection = EmbeddedLettaCodeModelSelection.from(
            LettaConfig(
                id = "local-lettacode",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
                localModelHandle = " lmstudio/gemma-3n ",
                localModelPath = "  ",
                localModelRuntime = null,
                localModelAccelerator = "cpu",
                localModelMaxTokens = -1,
            )
        )

        assertEquals("lmstudio/gemma-3n", selection.modelHandle)
        assertEquals(null, selection.modelPath)
        assertEquals("litert-lm", selection.runtime)
        assertEquals("cpu", selection.accelerator)
        assertEquals(4096, selection.maxTokens)
        assertEquals("gemma-3n", selection.openAiModelId)
        assertEquals("lmstudio/gemma-3n", selection.lettaCodeModelHandle)
    }

    private fun statusProvider(runnable: Boolean): EmbeddedLettaCodeRuntimeStatusProvider =
        object : EmbeddedLettaCodeRuntimeStatusProvider {
            override val status: EmbeddedLettaCodeRuntimeStatus = objectStatus(runnable)
        }

    private fun objectStatus(runnable: Boolean): EmbeddedLettaCodeRuntimeStatus =
        if (runnable) {
            EmbeddedLettaCodeRuntimeStatus(true, true, "test", "test")
        } else {
            EmbeddedLettaCodeRuntimeStatus(false, false, "", "")
        }

    private fun tempModelPath(): String = File.createTempFile("model", ".litertlm")
        .apply { writeText("model"); deleteOnExit() }
        .absolutePath

    private fun config(
        localModelPath: String? = null,
        localModelHandle: String? = null,
        localProviderBaseUrl: String? = null,
        localProviderModel: String? = null,
    ): LettaConfig = LettaConfig(
        id = "local-lettacode",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://device",
        localModelPath = localModelPath,
        localModelHandle = localModelHandle,
        localProviderBaseUrl = localProviderBaseUrl,
        localProviderModel = localProviderModel,
    )

    private fun command(
        agentId: String = "agent-1",
        conversationId: String = "conv-1",
    ): TurnCommand = TurnCommand(
        backendId = BackendId("local-lettacode:test"),
        runtimeId = RuntimeId("local-lettacode:test"),
        agentId = AgentId(agentId),
        conversationId = ConversationId(conversationId),
        input = TurnInput.UserMessage(
            localMessageId = "local-1",
            text = "hello",
        ),
    )

    private fun controller(
        nodeBridge: FakeNodeBridge,
        turnSilenceMs: Long,
        turnAbsoluteMaxMs: Long,
    ): AndroidLettaCodeRuntimeController = AndroidLettaCodeRuntimeController(
        context = mockk<Context>(relaxed = true),
        assetExtractor = FakeAssetExtractor(File(System.getProperty("java.io.tmpdir"), "letta-timeout-${System.nanoTime()}").apply { mkdirs() }),
        nodeBridge = nodeBridge,
        runtimeStatusProvider = statusProvider(runnable = true),
        onDeviceOpenAiBridge = mockk(relaxed = true),
        localBackendStore = mockk(relaxed = true),
        androidNetworkBridge = FakeAndroidNetworkBridge(),
    ).apply {
        this.turnSilenceMs = turnSilenceMs
        this.turnAbsoluteMaxMs = turnAbsoluteMaxMs
    }


    private fun toolApprovalCommand(): TurnCommand = command().copy(
        input = TurnInput.ToolApprovalResponse(
            decision = ToolApprovalDecision(
                approvalId = ToolApprovalId("approval-1"),
                callId = ToolCallId("call-1"),
                decision = ToolApprovalDecisionValue.Approved,
                scope = ToolApprovalScope.Once,
            )
        )
    )

    private class FakeAssetExtractor(private val baseDir: File) : EmbeddedLettaCodeAssetExtractor(mockk(relaxed = true)) {
        override suspend fun prepare(): PreparedLettaCodeProject {
            val projectDir = File(baseDir, "nodejs-project").apply { mkdirs() }
            val entrypoint = File(projectDir, "letta.js").apply { writeText("") }
            return PreparedLettaCodeProject(
                projectDir = projectDir,
                entrypoint = entrypoint,
                workingDirectory = File(baseDir, "workdir"),
                storageDirectory = File(baseDir, "storage"),
                homeDirectory = File(baseDir, "home"),
            )
        }
    }

    private class FakeOnDeviceBridge : OnDeviceOpenAiBridge {
        var startCalls = 0
        var closeCalls = 0

        override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession {
            startCalls += 1
            return OnDeviceOpenAiBridgeSession(
                baseUrl = "http://127.0.0.1:2/v1",
                authToken = "openai-loopback-token",
                closeAction = { closeCalls += 1 },
            )
        }
    }

    private class FakeAndroidNetworkBridge : AndroidNetworkBridge {
        override fun start(): AndroidNetworkBridgeSession = AndroidNetworkBridgeSession(
            baseUrl = "http://127.0.0.1:1",
            authToken = "test-token",
            closeAction = {},
        )
    }

    private class FakeNodeBridge(
        override val outputLines: Flow<String> = flowOf("""{"type":"result"}"""),
    ) : LettaCodeNodeBridge {
        var started = false
        var startCalls = 0
        var stopCalls = 0
        var lastRequest: LettaCodeNodeStartRequest? = null
        val writtenLines = mutableListOf<String>()

        override suspend fun start(request: LettaCodeNodeStartRequest): Result<Unit> {
            startCalls += 1
            started = true
            lastRequest = request
            return Result.success(Unit)
        }

        override suspend fun writeLine(line: String): Result<Unit> {
            writtenLines += line
            return Result.success(Unit)
        }

        override suspend fun stop(): Result<Unit> {
            stopCalls += 1
            started = false
            return Result.success(Unit)
        }
    }
}
