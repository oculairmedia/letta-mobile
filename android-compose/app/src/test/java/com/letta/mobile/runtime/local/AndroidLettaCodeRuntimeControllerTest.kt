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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun `different local session stops active binding and starts requested session`() = runTest {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit
        val modelFile = File.createTempFile("model", ".litertlm").apply { deleteOnExit() }
        val baseDir = requireNotNull(modelFile.parentFile)
        val nodeBridge = FakeNodeBridge()
        val onDeviceBridge = FakeOnDeviceBridge()
        val networkBridge = FakeAndroidNetworkBridge()
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = FakeAssetExtractor(baseDir),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = onDeviceBridge,
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = networkBridge,
        )

        controller.submit(command(agentId = "agent-1", conversationId = "conv-1"), config(modelFile.absolutePath)).toList()
        controller.submit(command(agentId = "agent-2", conversationId = "conv-2"), config(modelFile.absolutePath)).toList()

        assertEquals(listOf("agent-1", "agent-2"), nodeBridge.startedAgents)
        assertEquals(2, nodeBridge.startCalls)
        assertEquals(1, nodeBridge.stopCalls)
        assertEquals("agent-2", nodeBridge.lastRequest?.arguments?.after("--agent"))
        assertEquals(1, onDeviceBridge.closedSessions)
        assertEquals(1, networkBridge.closedSessions)
    }

    @Test
    fun `same local session reuses binding without teardown`() = runTest {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit
        val modelFile = File.createTempFile("model", ".litertlm").apply { deleteOnExit() }
        val baseDir = requireNotNull(modelFile.parentFile)
        val nodeBridge = FakeNodeBridge()
        val onDeviceBridge = FakeOnDeviceBridge()
        val networkBridge = FakeAndroidNetworkBridge()
        val controller = AndroidLettaCodeRuntimeController(
            context = mockk<Context>(relaxed = true),
            assetExtractor = FakeAssetExtractor(baseDir),
            nodeBridge = nodeBridge,
            runtimeStatusProvider = statusProvider(runnable = true),
            onDeviceOpenAiBridge = onDeviceBridge,
            localBackendStore = mockk(relaxed = true),
            androidNetworkBridge = networkBridge,
        )

        controller.submit(command(agentId = "agent-1", conversationId = "conv-1"), config(modelFile.absolutePath)).toList()
        controller.submit(command(agentId = "agent-1", conversationId = "conv-1"), config(modelFile.absolutePath)).toList()

        assertEquals(1, nodeBridge.startCalls)
        assertEquals(0, nodeBridge.stopCalls)
        assertEquals(0, onDeviceBridge.closedSessions)
        assertEquals(0, networkBridge.closedSessions)
    }

    @Test
    fun `released stale binding does not block new session`() = runTest {
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

        controller.submit(command(agentId = "agent-1", conversationId = "conv-1"), config(modelFile.absolutePath)).toList()
        controller.releaseActiveSession()
        controller.submit(command(agentId = "agent-2", conversationId = "conv-2"), config(modelFile.absolutePath)).toList()

        assertEquals(listOf("agent-1", "agent-2"), nodeBridge.startedAgents)
        assertEquals(2, nodeBridge.startCalls)
        assertEquals(1, nodeBridge.stopCalls)
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

    private fun config(localModelPath: String? = null): LettaConfig = LettaConfig(
        id = "local-lettacode",
        mode = LettaConfig.Mode.LOCAL,
        serverUrl = "local-lettacode://device",
        localModelPath = localModelPath,
    )

    private fun command(agentId: String = "agent-1", conversationId: String = "conv-1"): TurnCommand = TurnCommand(
        backendId = BackendId("local-lettacode:test"),
        runtimeId = RuntimeId("local-lettacode:test"),
        agentId = AgentId(agentId),
        conversationId = ConversationId(conversationId),
        input = TurnInput.UserMessage(
            localMessageId = "local-1",
            text = "hello",
        ),
    )

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
        var closedSessions = 0

        override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession =
            OnDeviceOpenAiBridgeSession(
                baseUrl = "http://127.0.0.1:2/v1",
                authToken = "openai-loopback-token",
                closeAction = { closedSessions += 1 },
            )
    }

    private class FakeAndroidNetworkBridge : AndroidNetworkBridge {
        var closedSessions = 0

        override fun start(): AndroidNetworkBridgeSession = AndroidNetworkBridgeSession(
            baseUrl = "http://127.0.0.1:1",
            authToken = "test-token",
            closeAction = { closedSessions += 1 },
        )
    }

    private class FakeNodeBridge : LettaCodeNodeBridge {
        override val outputLines = flowOf("""{"type":"result"}""")
        var started = false
        var startCalls = 0
        var stopCalls = 0
        var lastRequest: LettaCodeNodeStartRequest? = null
        val startedAgents = mutableListOf<String>()

        override suspend fun start(request: LettaCodeNodeStartRequest): Result<Unit> {
            started = true
            startCalls += 1
            lastRequest = request
            request.arguments.after("--agent")?.let(startedAgents::add)
            return Result.success(Unit)
        }

        override suspend fun writeLine(line: String): Result<Unit> = Result.success(Unit)

        override suspend fun stop(): Result<Unit> {
            stopCalls += 1
            started = false
            return Result.success(Unit)
        }
    }

    private fun List<String>.after(flag: String): String? {
        val index = indexOf(flag)
        return if (index >= 0) getOrNull(index + 1) else null
    }
}
