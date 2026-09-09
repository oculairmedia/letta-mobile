package com.letta.mobile.di

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.isIrohBackendUrl
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
class ProductionSelectedChatRuntimeProvider @Inject constructor(
    private val sessions: SessionManager,
    private val factory: AndroidCanonicalTimelineRuntimeFactory,
    private val settings: ISettingsRepository,
    private val legacyWriter: TimelineExternalTransportWriter,
) : SelectedChatRuntimeProvider {
    override fun runtimes(agentId: String): StateFlow<SelectedChatRuntime?> =
        SubscriberRuntimeFlow(sessions.currentGraph) { capture(it, agentId) }

    private fun capture(graph: SessionGraph, agent: String): SelectedChatRuntime? {
        val config = checkNotNull(graph.capturedConfig) { "Production graph has no captured config" }
        if (!allowsCapturedCanonicalRuntime(graph.localRuntimeBackend != null, config.serverUrl)) return null
        val capturedSettings = object : ISettingsRepository by settings {
            override val activeConfig = MutableStateFlow<com.letta.mobile.data.model.LettaConfig?>(config)
            override val activeConfigChanges = flowOf(config)
        }
        return CapturedSelectedChatRuntime(graph, agent, factory, capturedSettings, legacyWriter)
    }
}

/** HTTP/local backends keep the legacy observer and writer; Iroh success is not universal coverage. */
internal fun allowsCapturedCanonicalRuntime(hasLocalRuntime: Boolean, serverUrl: String?): Boolean =
    !hasLocalRuntime && isIrohBackendUrl(serverUrl)

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
    var bind: suspend (String) -> AndroidCanonicalTimelineRuntime.BindResult = {
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
    private val legacyWriter: TimelineExternalTransportWriter,
    private val hooks: ConversationBindHooks,
) : SelectedChatRuntime {
    constructor(
        graph: SessionGraph,
        agent: String,
        factory: AndroidCanonicalTimelineRuntimeFactory,
        settings: ISettingsRepository,
        legacyWriter: TimelineExternalTransportWriter,
    ) : this(
        generation = graph.id,
        config = checkNotNull(graph.capturedConfig),
        descriptor = graph.backendDescriptor,
        parent = graph.scope,
        agent = agent,
        legacyWriter = legacyWriter,
        hooks = ConversationBindHooks(),
    ) {
        val runtime = factory.capturedIroh(graph, settings, scope)
        val cursors = checkNotNull(graph.conversationCursorStore)
        hooks.bind = { conversation ->
            runtime.bind(
                TimelineScope(descriptor.backendId.value, conversation, agent),
                repairCommittedCursor = { owner, expected, committed ->
                    check(job.isActive) { "Selected runtime retired during cursor repair" }
                    val conversationId = owner.selection.scope.conversationId
                    val expectedWatermark = expected
                        ?: cursors.getCursor(conversationId)
                        ?: error("Cursor repair requires retained expected watermark for ${cursors.backendId}")
                    val replacement = checkNotNull(committed) {
                        "Cursor repair requires committed sequence for ${cursors.backendId}"
                    }
                    if (!cursors.replaceExpiredWatermark(conversationId, expectedWatermark, replacement)) {
                        val current = cursors.getCursor(conversationId)
                        check(current != null && current != expectedWatermark) {
                            "Cursor replacement lost without a newer watermark for ${cursors.backendId}"
                        }
                    }
                },
                reportFailure = { android.util.Log.e("CanonicalTimeline", "Canonical maintenance failed", it) },
            )
        }
        hooks.retire = { runtime.retire() }
    }

    private val job = SupervisorJob(parent.coroutineContext[Job])
    override val scope = parent + job
    private val bindings = RuntimeBindingCache<String, AndroidCanonicalTimelineRuntime.BindResult>()

    private suspend fun binding(conversation: String) = com.letta.mobile.data.local.retryTimelineOwnership {
        bindings.get(conversation) {
            check(job.isActive) { "Selected runtime retired" }
            hooks.bind(conversation)
        }
    }

    override suspend fun ready(conversationId: String) = when (binding(conversationId)) {
        is AndroidCanonicalTimelineRuntime.BindResult.Canonical ->
            com.letta.mobile.feature.chat.coordination.SelectedTimelineRoute.Canonical
        AndroidCanonicalTimelineRuntime.BindResult.LegacyDeferred ->
            com.letta.mobile.feature.chat.coordination.SelectedTimelineRoute.LegacyDeferred
    }

    override suspend fun open(conversationId: String, target: String?, scope: CoroutineScope): ChatPagingPresentation {
        val bound = binding(conversationId) as? AndroidCanonicalTimelineRuntime.BindResult.Canonical
            ?: error("Deferred conversations use the legacy observer, not canonical paging")
        // Private host: never mutate the singleton presentation router.
        val host = ChatPagingHost()
        bound.binding.bindPresentation(host)
        return checkNotNull(host.openCanonical).invoke(agent, conversationId, target, scope)
    }

    override val writer: TimelineExternalTransportWriter = SelectedRuntimeWriter(agent) { conversation ->
        when (val bound = binding(conversation)) {
            is AndroidCanonicalTimelineRuntime.BindResult.Canonical -> bound.binding.writer
            AndroidCanonicalTimelineRuntime.BindResult.LegacyDeferred -> legacyWriter
        }
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
    private suspend fun writer(id: String, suppliedAgent: String? = null): TimelineExternalTransportWriter {
        require(suppliedAgent == null || suppliedAgent == agent) { "Foreign agent on captured writer" }
        return resolve(id)
    }
    override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>) =
        appendExternalTransportLocal(agent, conversationId, content, otid, attachments)
    override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>) =
        writer(conversationId, agentId).appendExternalTransportLocal(agent, conversationId, content, otid, attachments)
    override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) = ingestExternalTransportMessage(agent, conversationId, message, source)
    override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) = writer(conversationId, agentId).ingestExternalTransportMessage(agent, conversationId, message, source)
    override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) = markExternalTransportLocalSent(agent, conversationId, otid)
    override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) = writer(conversationId, agentId).markExternalTransportLocalSent(agent, conversationId, otid)
    override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) = markExternalTransportLocalFailed(agent, conversationId, otid)
    override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) = writer(conversationId, agentId).markExternalTransportLocalFailed(agent, conversationId, otid)
    override suspend fun reconcileExternalTransportSend(conversationId: String, agentId: String, externalConversationId: String, otid: String) = reconcileExternalTransportSendScoped(agentId, conversationId, externalConversationId, otid)
    override suspend fun reconcileExternalTransportSendScoped(agentId: String?, conversationId: String, externalConversationId: String, otid: String) = writer(conversationId, agentId).reconcileExternalTransportSendScoped(agent, conversationId, externalConversationId, otid)
    override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) =
        repairExpiredConversationCursorScoped(agent, conversationId, fallbackSeq, expectedWatermark = null)
    override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) =
        repairExpiredConversationCursorScoped(agentId, conversationId, fallbackSeq, expectedWatermark = null)
    override suspend fun repairExpiredConversationCursorScoped(
        agentId: String?,
        conversationId: String,
        fallbackSeq: Long?,
        expectedWatermark: Long?,
    ) {
        require(expectedWatermark != null) {
            "Cursor expiry recovery requires commit-spanning graph fence and expected watermark"
        }
        writer(conversationId, agentId)
            .repairExpiredConversationCursorScoped(agent, conversationId, fallbackSeq, expectedWatermark)
    }
    override suspend fun clearExternalTransportActive(conversationId: String) = clearExternalTransportActive(agent, conversationId)
    override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) = writer(conversationId, agentId).clearExternalTransportActive(agent, conversationId)
    override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>) = writer(conversationId, agentId).cleanupAbandonedAssistantFragments(agent, conversationId, runId, turnId, reason, candidateRunIds)
    override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean, connectionGeneration: Long) = writer(conversationId, agentId).reconcileRecentMessages(agent, conversationId, reason, forceRefresh, connectionGeneration)
    override suspend fun turnStarted(agentId: String?, conversationId: String, runId: String?, turnId: String?) = writer(conversationId, agentId).turnStarted(agent, conversationId, runId, turnId)
    override suspend fun turnEnded(agentId: String?, conversationId: String, clean: Boolean) = writer(conversationId, agentId).turnEnded(agent, conversationId, clean)
}
