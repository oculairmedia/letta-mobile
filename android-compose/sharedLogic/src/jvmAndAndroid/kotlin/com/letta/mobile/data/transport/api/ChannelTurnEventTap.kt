package com.letta.mobile.data.transport.api

import com.letta.mobile.data.transport.ServerFrame

/** Synchronous tap before lossy fan-out; overlapping trusted graph generations drain independently. */
class ChannelTurnEventTap(
    private val logicalRun: (ServerFrame.TurnStarted) -> com.letta.mobile.runtime.RunId,
    private val abort: suspend (com.letta.mobile.data.model.AgentId, com.letta.mobile.runtime.ConversationId,
        com.letta.mobile.runtime.RunId) -> Boolean,
) : RuntimeTurnEventSource {
    private val observers = java.util.concurrent.CopyOnWriteArraySet<
        (com.letta.mobile.runtime.RuntimeEventDraft, com.letta.mobile.runtime.RunId, com.letta.mobile.runtime.RunId?) -> Unit
    >()
    private val turnScopes = java.util.concurrent.ConcurrentHashMap<String, com.letta.mobile.runtime.RuntimeEventDraft>()

    /** Register a session observer and return its independent detach action. */
    override fun observeTurns(
        observer: (com.letta.mobile.runtime.RuntimeEventDraft, com.letta.mobile.runtime.RunId,
            com.letta.mobile.runtime.RunId?) -> Unit,
    ): () -> Unit {
        check(observers.add(observer)) { "Session already has this turn observer" }
        return { observers.remove(observer) }
    }

    /** Abort only the supplied agent, conversation, and server run when the engine accepts it. */
    override suspend fun abortExact(
        agent: com.letta.mobile.data.model.AgentId,
        conversation: com.letta.mobile.runtime.ConversationId,
        run: com.letta.mobile.runtime.RunId,
    ): Boolean = abort(agent, conversation, run)

    /** Capture lifecycle scope before the frame is offered to presentation consumers. */
    fun publish(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.TurnStarted -> {
                val draft = com.letta.mobile.runtime.RuntimeEventDraft(
                    backendId = com.letta.mobile.runtime.BackendId("channel"),
                    runtimeId = com.letta.mobile.runtime.RuntimeId(logicalRun(frame).value),
                    agentId = com.letta.mobile.data.model.AgentId(frame.agentId),
                    conversationId = com.letta.mobile.runtime.ConversationId(frame.conversationId),
                    runId = com.letta.mobile.runtime.RunId(frame.runId),
                    source = com.letta.mobile.runtime.RuntimeEventSource.ExternalTransport,
                    payload = com.letta.mobile.runtime.RuntimeEventPayload.RunLifecycleChanged(
                        com.letta.mobile.runtime.RuntimeRunStatus.Started,
                    ),
                )
                turnScopes[frame.turnId] = draft
                publishDraft(draft, com.letta.mobile.runtime.RunId(draft.runtimeId.value))
            }
            is ServerFrame.TurnDone -> {
                val scope = turnScopes[frame.turnId] ?: return
                val status = when (frame.status) {
                    "completed" -> com.letta.mobile.runtime.RuntimeRunStatus.Completed
                    "cancelled" -> com.letta.mobile.runtime.RuntimeRunStatus.Cancelled
                    "failed" -> com.letta.mobile.runtime.RuntimeRunStatus.Failed
                    else -> return
                }
                publishDraft(scope.copy(runId = com.letta.mobile.runtime.RunId(frame.runId),
                    payload = com.letta.mobile.runtime.RuntimeEventPayload.RunLifecycleChanged(status)),
                    com.letta.mobile.runtime.RunId(scope.runtimeId.value))
            }
            else -> Unit
        }
    }

    /** Deliver an engine or observer draft under its stable command and exact server identities. */
    fun publishDraft(draft: com.letta.mobile.runtime.RuntimeEventDraft, logical: com.letta.mobile.runtime.RunId) {
        val server = draft.runId?.takeUnless { it.value.startsWith("iroh-run-") }
        observers.forEach { it(draft, logical, server) }
    }
    /** Engine-backed tap construction for channel hosts. */
    companion object {
        /** Bind the tap to the currently captured engine while retaining exact-run acceptance checks. */
        fun fromEngine(
            logicalRun: (ServerFrame.TurnStarted) -> com.letta.mobile.runtime.RunId,
            engine: suspend () -> com.letta.mobile.data.runtime.AppServerTurnEngine?,
        ): ChannelTurnEventTap {
            return ChannelTurnEventTap(logicalRun, abort = { agent, conversation, run ->
                engine()?.let { current ->
                    com.letta.mobile.data.transport.appserver.AppServerRunControls(current).stop(
                        agent, com.letta.mobile.data.model.ConversationId(conversation.value), run,
                    )
                } ?: false
            })
        }
    }

}
