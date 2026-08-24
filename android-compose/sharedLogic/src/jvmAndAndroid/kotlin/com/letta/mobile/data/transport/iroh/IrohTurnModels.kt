package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.Job

@JvmInline
value class IrohConversationId(val value: String)

@JvmInline
value class IrohTurnId(val value: String)

@JvmInline
value class IrohRunId(val value: String)

@JvmInline
value class IrohAgentId(val value: String)

@JvmInline
value class IrohTerminalStatus(val value: String)

enum class IrohTerminalSource {
    Engine,
    Observer,
    CancelSynthetic,
}

enum class IrohFrameOwner {
    Engine,
    Observer,
}

/** Unique owner token for a registered Iroh turn. */
data class IrohTurnToken(
    val conversationId: IrohConversationId,
    val generation: Long,
    val turnId: IrohTurnId,
)

/** Immutable identity supplied when registering a new turn. */
data class IrohTurnRequest(
    val token: IrohTurnToken,
    val runId: IrohRunId,
    val agentId: IrohAgentId,
)

data class IrohSendJobRegistration(
    val conversationId: IrohConversationId,
    val job: Job,
)

data class IrohRunPromotion(
    val token: IrohTurnToken,
    val runId: IrohRunId,
)

data class IrohTerminalPublication(
    val turn: IrohActiveTurn,
    val status: IrohTerminalStatus,
    val source: IrohTerminalSource,
)
