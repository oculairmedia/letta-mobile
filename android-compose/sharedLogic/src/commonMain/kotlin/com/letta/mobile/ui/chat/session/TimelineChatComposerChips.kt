package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.runtime.PermissionModeGateway
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * letta-mobile-ckmub.17.2: the composer chips of a host whose page serves one conversation
 * ([TimelineChatSessionPort]: the web client), over the same shared sources desktop's controller
 * binds: [ConversationModels] for the model chip and [GatewayPermissionModes] for the mode chip.
 */

/** Where the one conversation's models come from. */
class TimelineModelSource(
    val backend: ConversationModelBackend,
    /** The conversation as the host records it, whose own model a switch left there. */
    val conversation: suspend (ConversationId) -> Conversation,
    /** The agent's model, shown while the conversation names none. */
    val agentModel: String?,
)

/** The sources of a one-conversation host's model and permission-mode chips. */
class TimelineChipSources(
    val models: TimelineModelSource,
    /** Null where the session governs no modes (an Iroh host sets its own): no mode chip. */
    val permissionModes: PermissionModeGateway?,
)

/** These options with [chips]' model and mode chips for [target]'s conversation, living as long as [scope]. */
fun TimelineChatSessionOptions.withChips(
    scope: CoroutineScope,
    target: TimelineChatTarget,
    chips: TimelineChipSources?,
): TimelineChatSessionOptions {
    chips ?: return this
    return copy(
        models = timelineConversationModels(scope, target, chips.models),
        permissionModes = timelinePermissionModes(scope, target, chips.permissionModes),
    )
}

/**
 * The model chip of [target]'s conversation: loads [source]'s model list, shows the
 * conversation's recorded model (else the agent's), and switches it through the shared
 * [ConversationModels]. Lives as long as [scope].
 */
internal fun timelineConversationModels(
    scope: CoroutineScope,
    target: TimelineChatTarget,
    source: TimelineModelSource,
): ConversationModels {
    val conversation = ConversationId(target.conversationId)
    val host = TargetConversationModelHost(conversation, AgentId(target.agentId), source.backend)
    return ConversationModels(scope, host).also { models ->
        models.load(source.backend)
        scope.launch {
            readRecordedConversation(source, conversation)?.let { models.picks.seedFromHost(listOf(it)) }
            models.showModel(conversation, source.agentModel)
        }
    }
}

/** [target]'s permission-mode chip on [gateway]; null (no chip) where the session governs no modes. */
internal fun timelinePermissionModes(
    scope: CoroutineScope,
    target: TimelineChatTarget,
    gateway: PermissionModeGateway?,
): ChatPermissionModeSource? {
    val modes = gateway?.takeIf { it.permissionModes != null } ?: return null
    val runtime = ChatPermissionModeTarget(modes, AppServerRuntimeScope(target.agentId, target.conversationId))
    return GatewayPermissionModes(scope, flowOf(runtime)) { runtime }
}

/** A failed read keeps the agent's model on the chip; the next open reads it again. */
private suspend fun readRecordedConversation(source: TimelineModelSource, conversation: ConversationId): Conversation? = try {
    source.conversation(conversation)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Telemetry.event(TELEMETRY_TAG, "conversationModel.readFailed", "error" to failure.message, level = Telemetry.Level.WARN)
    null
}

/** The [ConversationModelHost] of a page that serves exactly one conversation. */
private class TargetConversationModelHost(
    private val conversation: ConversationId,
    agent: AgentId,
    override val backend: ConversationModelBackend,
) : ConversationModelHost {
    private val state = MutableStateFlow(ConversationModelFocus(conversationId = conversation, agentId = agent))

    override val isClosed: Boolean get() = false
    override val focus: StateFlow<ConversationModelFocus> = state.asStateFlow()

    override fun showModel(update: ConversationModelUpdate) {
        if (update.conversationId != conversation) return
        update.label?.let { label -> state.update { it.copy(label = label) } }
        update.error?.let { Telemetry.event(TELEMETRY_TAG, "conversationModel.switchFailed", "error" to it, level = Telemetry.Level.WARN) }
    }
}

private const val TELEMETRY_TAG = "TimelineChatModels"
