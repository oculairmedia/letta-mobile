package com.letta.mobile.di

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionGraph
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.feature.chat.coordination.SelectedChatRuntime
import com.letta.mobile.feature.chat.coordination.SelectedChatRuntimeProvider
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/** One captured config, pinned for the lifetime of a single selected-runtime capture. */
private class CapturedSettings(
    delegate: ISettingsRepository,
    config: com.letta.mobile.data.model.LettaConfig,
) : ISettingsRepository by delegate {
    override val activeConfig = MutableStateFlow<com.letta.mobile.data.model.LettaConfig?>(config)
    override val activeConfigChanges = flowOf(config)
}

class ProductionSelectedChatRuntimeProvider @Inject constructor(
    private val sessions: SessionManager,
    private val factory: AndroidCanonicalTimelineRuntimeFactory,
    private val settings: ISettingsRepository,
) : SelectedChatRuntimeProvider {
    override fun runtimes(agentId: String): StateFlow<SelectedChatRuntime?> =
        SubscriberRuntimeFlow(sessions.currentGraph) { capture(it, AgentId(agentId)) }

    /** letta-mobile-by4vm.15: every backend (Iroh, ws://, local runtime) runs on the canonical ledger. */
    private fun capture(graph: SessionGraph, agent: AgentId): SelectedChatRuntime {
        val config = checkNotNull(graph.capturedConfig) { "Production graph has no captured config" }
        return CapturedSelectedChatRuntime(graph, agent, factory, CapturedSettings(settings, config))
    }
}

/** No detached sharing job: the ViewModel collector owns collection and final retirement. */
@OptIn(InternalCoroutinesApi::class)
internal class SubscriberRuntimeFlow<G>(
    private val graphs: StateFlow<G>,
    private val capture: (G) -> SelectedChatRuntime?,
) : StateFlow<SelectedChatRuntime?> {
    private var graph = graphs.value
    private var runtime = capture(graph)
    override val value: SelectedChatRuntime? get() = runtime
    override val replayCache: List<SelectedChatRuntime?> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<SelectedChatRuntime?>): Nothing {
        graphs.collect { next ->
            if (next !== graph) {
                val captured = capture(next)
                graph = next
                runtime = captured
            }
            // The consumer drains its pipeline before retiring this handle, also in finally.
            collector.emit(runtime)
        }
        error("Session graph StateFlow completed")
    }
}

internal class ConversationBindHooks(
    var bind: suspend (String) -> AndroidCanonicalTimelineRuntime.Binding = {
        error("Conversation bind hooks not installed")
    },
    var retire: suspend () -> Unit = { error("Conversation retire hook not installed") },
)

internal class CapturedSelectedChatRuntime(
    override val generation: Long,
    override val config: com.letta.mobile.data.model.LettaConfig,
    override val descriptor: com.letta.mobile.runtime.BackendDescriptor,
    parent: CoroutineScope,
    private val agent: String,
    private val hooks: ConversationBindHooks,
) : SelectedChatRuntime {
    constructor(
        graph: SessionGraph,
        agent: AgentId,
        factory: AndroidCanonicalTimelineRuntimeFactory,
        settings: ISettingsRepository,
    ) : this(
        generation = graph.id,
        config = checkNotNull(graph.capturedConfig),
        descriptor = graph.backendDescriptor,
        parent = graph.scope,
        agent = agent.value,
        hooks = ConversationBindHooks(),
    ) {
        val runtime = factory.captured(graph, settings, scope)
        hooks.bind = { conversation ->
            runtime.bind(
                TimelineScope(descriptor.backendId.value, conversation, agent.value),
                reportFailure = { android.util.Log.e("CanonicalTimeline", "Canonical maintenance failed", it) },
            )
        }
        hooks.retire = { runtime.retire() }
    }

    private val job = SupervisorJob(parent.coroutineContext[Job])
    override val scope = parent + job
    private val bindings = RuntimeBindingCache<String, AndroidCanonicalTimelineRuntime.Binding>()

    private suspend fun binding(conversation: ConversationId) = com.letta.mobile.data.local.retryTimelineOwnership {
        bindings.get(conversation.value) {
            check(job.isActive) { "Selected runtime retired" }
            hooks.bind(conversation.value)
        }
    }

    override suspend fun ready(conversationId: String) {
        binding(ConversationId(conversationId))
    }

    override suspend fun open(conversationId: String, target: String?, scope: CoroutineScope): ChatPagingPresentation {
        // Private host: never mutate the singleton presentation router.
        val host = ChatPagingHost()
        binding(ConversationId(conversationId)).bindPresentation(host)
        return checkNotNull(host.openCanonical).invoke(agent, conversationId, target, scope)
    }

    override val writer: TimelineExternalTransportWriter = SelectedRuntimeWriter(agent) { conversation ->
        binding(ConversationId(conversation)).writer
    }

    override suspend fun retire() = withContext(NonCancellable) {
        bindings.close { hooks.retire() }
        job.cancelAndJoin()
    }
}

/** Every operation resolves only within this captured subscriber, including late terminal calls. */
internal class SelectedRuntimeWriter(
    private val agent: String,
    private val resolve: suspend (String) -> TimelineExternalTransportWriter,
) : TimelineExternalTransportWriter {
    private suspend fun writer(id: ConversationId, suppliedAgent: AgentId?): TimelineExternalTransportWriter {
        require(suppliedAgent == null || suppliedAgent.value == agent) { "Foreign agent on captured writer" }
        return resolve(id.value)
    }
    override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>) =
        appendExternalTransportLocal(agent, conversationId, content, otid, attachments)
    override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>) =
        writer(ConversationId(conversationId), agentId?.let(::AgentId))
            .appendExternalTransportLocal(agent, conversationId, content, otid, attachments)
    override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) = ingestExternalTransportMessage(agent, conversationId, message, source)
    override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) = writer(ConversationId(conversationId), agentId?.let(::AgentId)).ingestExternalTransportMessage(agent, conversationId, message, source)
    override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) = markExternalTransportLocalSent(agent, conversationId, otid)
    override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) = writer(ConversationId(conversationId), agentId?.let(::AgentId)).markExternalTransportLocalSent(agent, conversationId, otid)
    override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) = markExternalTransportLocalFailed(agent, conversationId, otid)
    override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) = writer(ConversationId(conversationId), agentId?.let(::AgentId)).markExternalTransportLocalFailed(agent, conversationId, otid)
    override suspend fun reconcileExternalTransportSend(conversationId: String, agentId: String, externalConversationId: String, otid: String) = reconcileExternalTransportSendScoped(agentId, conversationId, externalConversationId, otid)
    override suspend fun reconcileExternalTransportSendScoped(agentId: String?, conversationId: String, externalConversationId: String, otid: String) =
        writer(ConversationId(conversationId), agentId?.let(::AgentId))
            .reconcileExternalTransportSendScoped(agent, conversationId, externalConversationId, otid)
    // The canonical repair is a committed reconcile; no retained watermark rides along.
    override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) =
        repairExpiredConversationCursorScoped(agent, conversationId, fallbackSeq)
    override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) =
        writer(ConversationId(conversationId), agentId?.let(::AgentId))
            .repairExpiredConversationCursorScoped(agent, conversationId, fallbackSeq)
    override suspend fun clearExternalTransportActive(conversationId: String) = clearExternalTransportActive(agent, conversationId)
    override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) = writer(ConversationId(conversationId), agentId?.let(::AgentId)).clearExternalTransportActive(agent, conversationId)
    override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>) = writer(ConversationId(conversationId), agentId?.let(::AgentId)).cleanupAbandonedAssistantFragments(agent, conversationId, runId, turnId, reason, candidateRunIds)
    override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean, connectionGeneration: Long) = writer(ConversationId(conversationId), agentId?.let(::AgentId)).reconcileRecentMessages(agent, conversationId, reason, forceRefresh, connectionGeneration)
    override suspend fun turnStarted(agentId: String?, conversationId: String, runId: String?, turnId: String?) = writer(ConversationId(conversationId), agentId?.let(::AgentId)).turnStarted(agent, conversationId, runId, turnId)
    override suspend fun turnEnded(agentId: String?, conversationId: String, clean: Boolean) = writer(ConversationId(conversationId), agentId?.let(::AgentId)).turnEnded(agent, conversationId, clean)
}
