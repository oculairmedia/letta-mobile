package com.letta.mobile.runtime.local

import android.content.Context
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
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        )

        val error = runCatching {
            controller.submit(command(), config(localModelPath = "/tmp/does-not-exist.litertlm")).first()
        }.exceptionOrNull()

        assertEquals(
            "Embedded LettaCode model file was not found at /tmp/does-not-exist.litertlm.",
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
        )

        val events = mutableListOf<String>()
        controller.submit(toolApprovalCommand(), config()).collect { events += it }

        assertEquals(emptyList<String>(), events)
        assertFalse(nodeBridge.started)
        coVerify(exactly = 0) { onDeviceBridge.start(any()) }
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

    private fun command(): TurnCommand = TurnCommand(
        backendId = BackendId("local-lettacode:test"),
        runtimeId = RuntimeId("local-lettacode:test"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-1"),
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

    private class FakeNodeBridge : LettaCodeNodeBridge {
        override val outputLines = MutableSharedFlow<String>()
        var started = false

        override suspend fun start(request: LettaCodeNodeStartRequest): Result<Unit> {
            started = true
            return Result.success(Unit)
        }

        override suspend fun writeLine(line: String): Result<Unit> = Result.success(Unit)

        override suspend fun stop(): Result<Unit> = Result.success(Unit)
    }
}
