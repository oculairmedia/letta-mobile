package com.letta.mobile.data.controller.extras

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): shared test doubles for
 * the [IrohCliRunner] interface, used by [CustomIrohMessagingToolTest],
 * [CustomIrohMessagingToolIntegrationTest], and the runner tests.
 *
 * Lifted from per-file declarations that CodeScene was flagging for
 * code duplication: each file's [CapturingRunner] / [FixedRunner] was
 * structurally identical to the others, just declared three times.
 */
internal class CapturingRunner : IrohCliRunner {
    data class Call(
        val binary: String,
        val fromAgentId: String,
        val toAgentId: String,
        val body: String,
        val identityDir: String?,
        val addressStore: String?,
    )
    val calls: MutableList<Call> = mutableListOf()
    override suspend fun send(
        binary: String,
        fromAgentId: String,
        toAgentId: String,
        body: String,
        identityDir: String?,
        addressStore: String?,
    ): IrohCliSendResult {
        calls += Call(binary, fromAgentId, toAgentId, body, identityDir, addressStore)
        return IrohCliSendResult.Delivered("captured-${calls.size}")
    }
}

/** Always returns the same result — for the dispatch-mapping tests. */
internal class FixedRunner(private val result: IrohCliSendResult) : IrohCliRunner {
    override suspend fun send(
        binary: String,
        fromAgentId: String,
        toAgentId: String,
        body: String,
        identityDir: String?,
        addressStore: String?,
    ): IrohCliSendResult = result
}