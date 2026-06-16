package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeProvidersTest {
    @Test
    fun `local runtime scheme trims delimiter-less urls`() {
        val provider = LocalKoogRuntimeProvider()

        assertTrue(
            provider.supports(
                LettaConfig(
                    id = "local-koog",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = " local-koog ",
                )
            )
        )
    }

    @Test
    fun `local lettacode advertises honest tool and approval capabilities`() {
        val provider = localLettaCodeProvider()

        val capabilities = provider.descriptor(
            LettaConfig(
                id = "local-lettacode",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
            )
        ).capabilities

        assertTrue(capabilities.supportsStreaming)
        assertTrue(capabilities.supportsMemFs)
        // Local runtime projects AND executes tools (letta-mobile-nq1le)
        assertTrue(capabilities.supportsToolEvents)
        assertTrue(capabilities.supportsToolExecution)
        // But turns are auto-approved — no approval round-trip UI
        assertFalse(capabilities.supportsApprovals)
        // The legacy compat flag should be true (tools do work)
        assertTrue(capabilities.supportsTools)
    }

    @Test
    fun `local lettacode provider requires explicit scheme`() {
        val provider = localLettaCodeProvider()

        assertFalse(
            provider.supports(
                LettaConfig(
                    id = "local-default",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local://device",
                )
            )
        )
        assertTrue(
            provider.supports(
                LettaConfig(
                    id = "local-lettacode",
                    mode = LettaConfig.Mode.LOCAL,
                    serverUrl = "local-lettacode://device",
                )
            )
        )
    }

    @Test
    fun `local runtime capability invariant - approvals false means no approval affordance`() {
        // Invariant (letta-mobile-nq1le): when supportsApprovals=false, the runtime
        // does not emit ApprovalRequested events, so no UiApprovalRequest is ever created,
        // and thus no approval UI (approve/reject buttons) is shown. Tool cards (from
        // ToolCallObserved/ToolReturnObserved) still render, because supportsToolEvents=true.
        val provider = localLettaCodeProvider()
        val descriptor = provider.descriptor(
            LettaConfig(
                id = "local-lettacode",
                mode = LettaConfig.Mode.LOCAL,
                serverUrl = "local-lettacode://device",
            )
        )

        // The local runtime says approvals are not supported
        assertFalse(descriptor.capabilities.supportsApprovals)
        // Tool events ARE supported — tool cards will render
        assertTrue(descriptor.capabilities.supportsToolEvents)

        // The invariant is enforced by data flow: LocalRuntimeChatSendCoordinator
        // handles ApprovalRequested as a no-op (lines 233-240), so no approvalRequestId
        // reaches the timeline mapper, thus UiApprovalRequest is never constructed,
        // and ApprovalRequestControls (which only renders when UiApprovalRequest != null)
        // never displays approve/reject buttons for local turns.
        //
        // This test documents the invariant; the actual enforcement is in:
        // - LocalRuntimeChatSendCoordinator.handleRuntimeEvent (no-op for ApprovalRequested)
        // - TimelineEventToUiMessage mapper (UiApprovalRequest only when approvalRequestId exists)
        // - ChatApprovals.ApprovalRequestControls (only renders when approval != null)
    }

    private fun localLettaCodeProvider(): LocalLettaCodeRuntimeProvider = LocalLettaCodeRuntimeProvider(
        turnEngineFactory = LettaCodeTurnEngineFactory(
            client = object : LettaCodeHeadlessClient {
                override fun runTurn(command: TurnCommand, config: LettaConfig): Flow<RuntimeEventDraft> = emptyFlow()
            },
        ),
        runtimeController = object : LettaCodeRuntimeController {
            override fun submit(command: TurnCommand, config: LettaConfig): Flow<String> = emptyFlow()
            override suspend fun interrupt() = Unit
        },
    )
}
