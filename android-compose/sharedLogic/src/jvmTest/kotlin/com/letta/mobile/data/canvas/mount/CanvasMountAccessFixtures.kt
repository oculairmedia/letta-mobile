package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId

/** A test authority's answer; [THROW] proves a failing authority denies instead of escaping. */
internal enum class Answer { ALLOW, DENY, ABSTAIN, THROW }

internal fun principal(endpoint: String): CanvasMountPrincipal =
    checkNotNull(CanvasMountPrincipal.fromVerifiedConnection(endpoint))

internal fun bindingRequest(agent: String, conversation: String, caller: String = "caller") =
    CanvasMountRequest(principal(caller), CanvasMountScope.Conversation(AgentId(agent), ConversationId(conversation)))

internal fun defaultRequest(agent: String, caller: String = "caller") =
    CanvasMountRequest(principal(caller), CanvasMountScope.AgentDefault(AgentId(agent)))

/** Answers each of the three questions with a fixed [Answer], whatever the request. */
internal class TableAuthority(
    private val scope: Answer,
    private val proposal: Answer = Answer.ALLOW,
    private val winner: Answer = Answer.ALLOW,
) : CanvasMountAuthority {
    override suspend fun mayAddress(request: CanvasMountRequest<CanvasMountScope>) = decide(scope)

    override suspend fun mayPropose(request: CanvasMountRequest<CanvasMountScope>, proposed: CanvasId) = decide(proposal)

    override suspend fun maySee(request: CanvasMountRequest<CanvasMountScope>, winner: CanvasId) = decide(this.winner)

    private fun decide(answer: Answer): CanvasMountDecision = when (answer) {
        Answer.ALLOW -> CanvasMountDecision.ALLOW
        Answer.DENY -> CanvasMountDecision.DENY
        Answer.ABSTAIN -> CanvasMountDecision.ABSTAIN
        Answer.THROW -> error("authority unavailable")
    }
}

/** Logs each question (with the canvas it is about) into the shared [log] before delegating. */
internal class RecordingAuthority(
    private val log: MutableList<String>,
    private val answers: CanvasMountAuthority,
) : CanvasMountAuthority {
    override suspend fun mayAddress(request: CanvasMountRequest<CanvasMountScope>): CanvasMountDecision {
        log += "address"
        return answers.mayAddress(request)
    }

    override suspend fun mayPropose(request: CanvasMountRequest<CanvasMountScope>, proposed: CanvasId): CanvasMountDecision {
        log += "propose:${proposed.value}"
        return answers.mayPropose(request, proposed)
    }

    override suspend fun maySee(request: CanvasMountRequest<CanvasMountScope>, winner: CanvasId): CanvasMountDecision {
        log += "see:${winner.value}"
        return answers.maySee(request, winner)
    }
}

/** A storage double that logs every touch into the shared [log], so tests see exactly when storage ran. */
internal class RecordingStorage(
    private val log: MutableList<String>,
    var stored: CanvasId? = null,
    var failure: Throwable? = null,
    var suspendForever: Boolean = false,
) : CanvasMountStorage {
    override suspend fun readBinding(agentId: AgentId, conversationId: ConversationId) = touch("readBinding")

    override suspend fun readDefault(agentId: AgentId) = touch("readDefault")

    override suspend fun bindIfAbsent(agentId: AgentId, conversationId: ConversationId, proposed: CanvasId): CanvasId =
        touch("bindIfAbsent") ?: proposed.also { stored = it }

    override suspend fun replaceBinding(agentId: AgentId, conversationId: ConversationId, canvasId: CanvasId) {
        touch("replaceBinding")
        stored = canvasId
    }

    override suspend fun setDefault(agentId: AgentId, canvasId: CanvasId) {
        touch("setDefault")
        stored = canvasId
    }

    private suspend fun touch(operation: String): CanvasId? {
        log += "store.$operation"
        if (suspendForever) kotlinx.coroutines.awaitCancellation()
        failure?.let { throw it }
        return stored
    }
}
