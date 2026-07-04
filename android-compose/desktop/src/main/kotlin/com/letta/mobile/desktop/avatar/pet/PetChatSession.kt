package com.letta.mobile.desktop.avatar.pet

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.desktop.DesktopBootstrapState
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Coarse chat phase the pet surfaces care about (drives director + status chip). */
enum class PetChatPhase { CONNECTING, IDLE, THINKING, REPLYING, ERROR }

/**
 * The pet-facing projection of the desktop chat stack (PRD §5 B1). Everything
 * the reply popup, speech bubble and status chip need, distilled from the
 * [DesktopChatController]'s several flows into one value the spike observes.
 */
data class PetChatUiState(
    val agent: PetChatAgent? = null,
    val phase: PetChatPhase = PetChatPhase.CONNECTING,
    /** One-line progress for the feet chip: "thinking…", "replying…", agent name when idle. */
    val statusLine: String = "connecting…",
    /** Latest assistant text for the speech bubble; blank = nothing to show. */
    val bubbleText: String = "",
    /** Transient send/stream error to flash in the chip. */
    val errorMessage: String? = null,
    /** True once an agent is resolved and the backend is live — the popup can send. */
    val canSend: Boolean = false,
)

/**
 * Owns the real [DesktopChatController] for the pet, wired to the user's actual
 * backend (the same `~/.letta-mobile/desktop-settings.properties` the desktop
 * app reads via [DesktopLettaConfigStore]). We instantiate the real controller
 * rather than hand-rolling HTTP so we inherit send, SSE streaming, thinking
 * tracking and conversation creation for free (PRD §5 B1 strong recommendation).
 *
 * Construction mirrors the minimal path [LettaDesktopApp] uses:
 * file secure store → config store → default data bindings → bootstrap state →
 * controller. Agent-name resolution is fed by the shared session graph's agent
 * repository so the popup header shows a real display name.
 *
 * Exposes a single [state] the spike observes; [send] forwards to the controller,
 * which routes the reply back through [state] as streamed [bubbleText].
 */
class PetChatSession(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val settingsStore = DesktopFileSecureSettingsStore()
    private val configStore = DesktopLettaConfigStore(settingsStore)
    private val activeConfig = configStore.load()
    private val dataBindings = createDefaultDesktopDataBindings(
        secureSettingsStore = settingsStore,
        configProvider = { activeConfig },
    )
    private val bootstrapState: DesktopBootstrapState =
        defaultDesktopBootstrapState(dataBindings, activeConfig)

    private val controller = DesktopChatController(
        bootstrapState = bootstrapState,
        scope = scope,
        agentNamesByIdProvider = { agentIds ->
            val agentRepository = dataBindings.sessionGraphProvider.current.agentRepository
            runCatching { agentRepository.refreshAgentsIfStale(maxAgeMs = 30_000L) }
            val resolved = mutableMapOf<String, String>()
            agentRepository.agents.value.forEach { agent ->
                agent.name.takeIf { it.isNotBlank() }?.let { resolved[agent.id.value] = it }
            }
            agentIds.filter { it !in resolved }.forEach { id ->
                val name = agentRepository.getCachedAgent(id)?.name
                    ?: runCatching { agentRepository.getAgent(id).first() }.getOrNull()?.name
                name?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
            }
            resolved
        },
    )

    private val _state = MutableStateFlow(PetChatUiState())
    val state: StateFlow<PetChatUiState> = _state.asStateFlow()

    /** Selected agent, resolved once conversations load; cached so send() can create as needed. */
    private var resolvedAgent: PetChatAgent? = null

    fun start() {
        controller.start()
        scope.launch {
            combine(
                controller.state,
                controller.thinkingConversationId,
                controller.streamingConversationId,
            ) { chat, thinkingId, streamingId ->
                project(chat, thinkingId, streamingId)
            }.collect { projected ->
                resolvedAgent = projected.agent
                _state.value = projected
            }
        }
    }

    /**
     * Send [text] to the pet's agent. If nothing is selected yet but we know the
     * agent, create a conversation for it first; the controller then selects it,
     * so a subsequent send lands in it. Returns false when we can't send (no
     * agent / not live) so the caller can keep the composer open.
     */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val chat = controller.state.value
        if (chat.connectionState != DesktopChatConnectionState.Live) return false

        val selected = chat.selectedConversationId
        if (selected != null) {
            controller.updateComposerText(trimmed)
            controller.send()
            return true
        }
        // No conversation selected yet but we have an agent: spin one up. The
        // controller selects it on creation; the user re-sends into it. Rare —
        // the newest conversation is normally auto-selected on load.
        val agentId = resolvedAgent?.agentId ?: return false
        controller.createConversationForAgent(agentId)
        log("no conversation selected; created one for agent $agentId — resend to deliver")
        return false
    }

    fun close() {
        controller.close()
    }

    /** Distill the controller's flows into the pet projection. */
    private fun project(
        chat: DesktopChatSurfaceState,
        thinkingConversationId: String?,
        streamingConversationId: String?,
    ): PetChatUiState {
        val agent = selectPetChatAgent(chat.conversations)
        val live = chat.connectionState == DesktopChatConnectionState.Live
        val selected = chat.selectedConversationId
        val thinking = thinkingConversationId != null && thinkingConversationId == selected
        val streaming = streamingConversationId != null && streamingConversationId == selected

        val bubble = if (streaming) latestAssistantText(chat) else ""

        val phase = when {
            !live -> PetChatPhase.CONNECTING
            chat.errorMessage != null -> PetChatPhase.ERROR
            thinking -> PetChatPhase.THINKING
            streaming -> PetChatPhase.REPLYING
            else -> PetChatPhase.IDLE
        }

        val statusLine = when (phase) {
            PetChatPhase.CONNECTING -> "connecting…"
            PetChatPhase.THINKING -> "thinking…"
            PetChatPhase.REPLYING -> "replying…"
            PetChatPhase.ERROR -> chat.errorMessage ?: "error"
            PetChatPhase.IDLE -> agent?.displayName ?: "no agent"
        }

        return PetChatUiState(
            agent = agent,
            phase = phase,
            statusLine = statusLine,
            bubbleText = bubble,
            errorMessage = chat.errorMessage,
            canSend = live && agent != null,
        )
    }

    /**
     * The latest assistant text in the selected conversation, for the bubble.
     * Walks from the tail to the newest non-empty, non-reasoning assistant
     * message — reasoning/tool-only frames carry no speech, so we skip them so
     * the bubble streams the actual reply rather than "thinking" scaffolding.
     */
    private fun latestAssistantText(chat: DesktopChatSurfaceState): String {
        val messages: List<UiMessage> = chat.selectedConversationId
            ?.let { chat.messagesByConversationId[it] }
            .orEmpty()
        return messages.lastOrNull { message ->
            !message.role.equals("user", ignoreCase = true) &&
                !message.isReasoning &&
                message.content.isNotBlank()
        }?.content.orEmpty()
    }
}
