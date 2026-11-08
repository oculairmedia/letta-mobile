package com.letta.mobile.data.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/**
 * The workspace/window's sole navigation owner. One immutable publication contains the focus,
 * tabs, editor and rail; compatibility properties dispatch transitions rather than storing copies.
 * Transitions serialize token validation and publication, including background completions.
 * Effects capture [requestToken] and return it to
 * [completeFocus] or [reportAvailability]; navigation always commits before an effect starts.
 */
@Stable
// Retain the existing dispatch facades during the seven-slice migration alongside the token API.
@Suppress("TooManyFunctions")
class ShellWorkspaceState(initialPage: ShellDestination = ShellDestination.Home) {
    private val transitionLock = SynchronizedObject()
    private val requestOwner = FocusRequestOwner()
    private var value by mutableStateOf(WorkspaceFocusState(initialFocusSnapshot(initialPage)))
    private var startupAvailable = true
    private var pendingSnapshot: CurrentFocusSnapshot? = null

    /** Atomically published requested identity; absence in a roster never changes it. */
    val currentFocus: CurrentFocus get() = value.saved.focus
    /** Trusted durable namespace; null until the host binds its source. */
    val sourceKey: String? get() = value.saved.sourceKey.takeIf(String::isNotBlank)
    /** Live connection generation, independent of durable source identity. */
    val sourceGeneration: Long get() = value.generation
    /** Selection revision; hydration of the same target does not increment it. */
    val focusRevision: Long get() = value.revision
    /** Availability evidence for the requested agent. */
    val agentAvailability: FocusAvailability get() = value.agentAvailability
    /** Availability evidence for the exact requested conversation. */
    val conversationAvailability: FocusAvailability get() = value.conversationAvailability

    /** Confirmed, source-scoped next-start seed. Observe this output; never feed it back as selection. */
    val settledSnapshot: CurrentFocusSnapshot? get() = value.settled?.detached()

    /** Main pane without duplicated identity; [destination] projects the full current route. */
    val page: ShellDestination get() = checkNotNull(ShellRoute.decode(currentFocus.pageRoute))

    /** Editor navigation beside the underlying chat; drafts belong to the editor controller. */
    var agentEditor: ShellDestination.AgentEditor?
        get() = value.saved.editorRoute?.let(ShellRoute::decode) as? ShellDestination.AgentEditor
        set(editor) = transition {
            if (editor == null) {
                closeStartup()
                value = value.copy(saved = value.saved.copy(editorRoute = null))
            } else {
                openEditor(editor)
            }
        }

    /** Compatibility editor target; setting null closes either editor mode. */
    var editAgentId: AgentId?
        get() = agentEditor?.agentId?.let(::AgentId)
        set(agent) = transition<Unit> { agentEditor = agent?.let { ShellDestination.AgentEditor(it.value) } }

    /** Compatibility agent selection: switching agents clears the old conversation in the same write. */
    var focusedAgentId: AgentId?
        get() = currentFocus.agentId
        set(agent) = transition<Unit> {
            if (agent != currentFocus.agentId) {
                selectFocus(currentFocus.copy(agentId = agent, conversationId = null, chatMode = CurrentFocusChatMode.Resume))
            }
        }

    /** Compatibility projection of the committed conversation target. */
    val selectedConversationId: ConversationId? get() = currentFocus.conversationId

    /** Ordered kept tabs and optional preview; malformed assignments are rejected together. */
    var tabs: ConversationTabsState
        get() = value.saved.tabs()
        set(tabs) = transition<Unit> {
            val candidate = value.saved.withTabs(tabs)
            if (candidate.copy(sourceKey = sourceKey ?: "unbound").valid()) value = value.copy(saved = candidate)
        }

    /** Saved rail expansion, independent of source identity. */
    var railExpanded: Boolean
        get() = value.saved.railExpanded
        set(expanded) = transition { value = value.copy(saved = value.saved.copy(railExpanded = expanded)) }

    /** Work/Play presentation lens, retained across source changes. */
    var workPlayMode: WorkPlayMode by mutableStateOf(WorkPlayMode.Work)
    /** Visibility of the independent background-tasks pane. */
    var showBackgroundTasks: Boolean by mutableStateOf(false)
    /** Memory-only pairing secret; never serialized in focus or a route. */
    var pendingPairLink: String? by mutableStateOf(null)
    /** Memory-only overlay flags; excluded from focus snapshots. */
    val overlays: ShellOverlays = ShellOverlays()

    /** Editor destination when open, otherwise the main page with committed identity. */
    val destination: ShellDestination
        get() {
            val saved = value.saved
            val editor = saved.editorRoute?.let(ShellRoute::decode)
            val focus = saved.focus
            return editor ?: checkNotNull(ShellRoute.decode(focus.pageRoute)).refinedBy(
                focus.agentId?.value, focus.conversationId?.value,
            )
        }
    /** Canonical compatibility route; use the snapshot envelope for complete recreation. */
    val route: String get() = ShellRoute.encode(destination)

    /** Compatibility projection: caller-owned chat/roster values cannot refine owner identity. */
    @Suppress("UNUSED_PARAMETER")
    fun pageFor(agent: AgentId?, conversation: ConversationId?): ShellDestination {
        val focus = currentFocus
        return checkNotNull(ShellRoute.decode(focus.pageRoute)).refinedBy(focus.agentId?.value, focus.conversationId?.value)
    }

    /** Select an entire validated target, closing any editor. Invalid values leave everything untouched. */
    fun selectFocus(focus: CurrentFocus): Boolean {
        return transition<Boolean> {
            if (!focus.valid()) return@transition false
            closeStartup()
            publishSelection(value.saved.copy(focus = focus, editorRoute = null))
            return@transition true
        }
    }

    /** Capture once before starting resolution, lookup, creation or hydration. */
    fun requestToken(): CurrentFocusRequest {
        return transition {
            CurrentFocusRequest(sourceKey, sourceGeneration, focusRevision, currentFocus.agentId,
                currentFocus.chatMode, currentFocus.conversationId, requestOwner)
        }
    }

    /** All request fields and the owner incarnation must still match; requests are never restored. */
    fun accepts(token: CurrentFocusRequest): Boolean {
        return transition {
            val sameSource = token.owner === requestOwner && token.sourceKey == sourceKey && token.sourceGeneration == sourceGeneration
            val focus = currentFocus
            val sameSelection = token.focusRevision == focusRevision && token.requestedAgent == focus.agentId &&
                token.mode == focus.chatMode && token.exactConversation == focus.conversationId
            sameSource && sameSelection
        }
    }

    /**
     * Commit trusted same-source ownership plus a resolved conversation as one publication. Null,
     * wrong-owner and wrong-exact completions cannot clear/replace selection. Hydration keeps revision.
     * An exact completion contributes resume history only with a human-lane [reference]; Resume/New
     * effects already resolve only human chats. A reference must match the complete target.
     */
    fun completeFocus(
        token: CurrentFocusRequest,
        conversation: ConversationId?,
        agent: AgentId?,
        reference: ShellConversationRef? = null,
    ): Boolean {
        return transition<Boolean> {
            if (!accepts(token)) return@transition false
            if (conversation == null || agent == null) return@transition false
            if (!matchesCompletion(token, conversation, agent, reference)) return@transition false
            val focus = currentFocus.copy(agentId = agent, conversationId = conversation, chatMode = CurrentFocusChatMode.Exact)
            if (!focus.valid()) return@transition false
            val saved = value.saved.copy(focus = focus).withTabs(tabFor(conversation))
            val resolvedOwners = if (conversation.value == IdFormats.DEFAULT_CONVERSATION_ID) {
                value.owners
            } else {
                value.owners + (conversation to agent)
            }
            value = value.copy(
                saved = saved,
                agentAvailability =
                    if (agentAvailability == FocusAvailability.Unavailable) agentAvailability else FocusAvailability.Available,
                conversationAvailability = FocusAvailability.Available,
                history = humanHistory(token, reference, agent, conversation),
                owners = resolvedOwners,
                settled = saved.takeIf { sourceKey != null },
            )
            true
        }
    }

    /** Lookup failure/deletion changes availability only. Unavailable exact IDs and tabs survive. */
    fun reportAvailability(
        token: CurrentFocusRequest,
        agent: FocusAvailability = agentAvailability,
        conversation: FocusAvailability = conversationAvailability,
    ): Boolean {
        return transition<Boolean> {
            if (!accepts(token)) return@transition false
            val deleted = currentFocus.conversationId.takeIf { conversation == FocusAvailability.Unavailable }
            val selected = currentFocus.conversationId
            value = value.copy(
                saved = selected?.let { value.saved.withTabs(tabFor(it)) } ?: value.saved,
                agentAvailability = agent,
                conversationAvailability = conversation,
                history = value.history.filter { (owner, id) -> owner != currentFocus.agentId || id != deleted },
            )
            return@transition true
        }
    }

    /**
     * Bind a trusted namespace and live generation. A matching pending envelope restores atomically
     * once; a foreign envelope is discarded and opens Home. Reconnect only fences requests.
     * Submit new startup entries to [initialize] before binding so they precede pending restoration.
     */
    fun bindSource(key: String, generation: Long) {
        return transition<Unit> {
            require(key.isNotBlank())
            val pending = pendingSnapshot
            pendingSnapshot = null
            if (pending != null && pending.sourceKey == key) {
                commitRestoration(pending, generation)
                return@transition
            }
            if (sourceKey == key && sourceGeneration == generation) return@transition
            val changedSource = pending != null || sourceKey != null && sourceKey != key
            if (changedSource) {
                closeStartup()
                value = WorkspaceFocusState(
                    initialFocusSnapshot(ShellDestination.Home, key, railExpanded),
                    generation = generation, revision = focusRevision + 1,
                )
                return@transition
            }
            value = value.copy(
                saved = value.saved.copy(sourceKey = key), generation = generation,
                revision = focusRevision + 1, settled = null,
            )
        }
    }

    /** Legacy session key is a namespace, not a roster refresh. New bindings use [bindSource]. */
    fun sessionChanged(key: String) {
        return transition {
            bindSource(key, if (sourceKey == key) sourceGeneration else sourceGeneration + 1)
        }
    }

    /** One versioned envelope; no requests, jobs, history, drafts or secrets. Requires a trusted source. */
    fun snapshot(): CurrentFocusSnapshot {
        return transition<CurrentFocusSnapshot> {
            check(sourceKey != null) { "Bind a trusted source before saving focus" }
            return@transition value.saved.detached()
        }
    }

    /**
     * Save the bound workspace, or a detached pending envelope before source binding, or null when
     * neither exists. This lets a saver survive recreation during startup without inventing a source
     * or publishing unverified identity as [currentFocus].
     */
    fun snapshotOrPending(): CurrentFocusSnapshot? = transition {
        if (sourceKey != null) snapshot() else pendingSnapshot?.detached()
    }

    /**
     * Hold one complete validated envelope before source binding and before navigation or startup
     * arbitration commits. A later valid hold replaces it; invalid envelopes leave it unchanged.
     * Holding never publishes focus or changes revision. [bindSource] consumes it once; navigation,
     * [initialize] and any [restore] attempt discard it. After binding, use [restore] instead.
     */
    fun holdPending(snapshot: CurrentFocusSnapshot): Boolean = transition {
        if (!startupAvailable || sourceKey != null) return@transition false
        if (!snapshot.valid()) return@transition false
        pendingSnapshot = snapshot.detached()
        true
    }

    /** Encode the entire snapshot with the canonical persisted JSON policy. */
    fun snapshotJson(): String = LettaJson.persisted.encodeToString(snapshot())

    /**
     * Restore once before user navigation from a complete same-source envelope; rebuild runtime
     * evidence. Every attempt discards a held pending envelope, including a rejected restore.
     */
    fun restore(snapshot: CurrentFocusSnapshot): Boolean {
        return transition<Boolean> {
            pendingSnapshot = null
            val canRestore = startupAvailable && snapshot.valid() && snapshot.sourceKey == sourceKey
            if (!canRestore) return@transition false
            commitRestoration(snapshot, sourceGeneration)
            return@transition true
        }
    }

    /** Malformed JSON and unknown versions/modes are rejected together, without partial writes. */
    fun restoreJson(json: String): Boolean = try {
        restore(LettaJson.persisted.decodeFromString<CurrentFocusSnapshot>(json))
    } catch (_: SerializationException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Validate every candidate before the single startup arbitration; late settings cannot reseed. */
    fun initialize(inputs: CurrentFocusInputs): Boolean {
        return transition<Boolean> {
            if (!startupAvailable || inputs.sourceKey.isBlank()) return@transition false
            if (sourceKey != null && sourceKey != inputs.sourceKey) return@transition false
            val validated = inputs.copy(
                deepLink = inputs.deepLink?.takeIf { it.valid() },
                explicitRoute = inputs.explicitRoute?.takeIf { it.valid() },
                restored = inputs.restored?.takeIf { it.valid() },
                settingsSeed = inputs.settingsSeed?.takeIf { it.valid() },
            )
            val initial = initialCurrentFocus(validated)
            value = WorkspaceFocusState(initial.detached(), generation = sourceGeneration, revision = focusRevision + 1)
            closeStartup()
            return@transition true
        }
    }

    /** Navigate through the owner; an editor preserves the underlying chat identity. */
    fun navigate(target: ShellDestination) {
        return transition<Unit> {
            if (target is ShellDestination.AgentEditor) {
                openEditor(target)
                return@transition
            }
            val next = target.initialFocus()
            val focus = when {
                target == ShellDestination.Home -> next
                target.conversationId != null -> next
                target.agentId != null && next.agentId != currentFocus.agentId -> next
                target is ShellDestination.Chat && target.agentId != null -> next
                else -> currentFocus.copy(pageRoute = next.pageRoute)
            }
            selectFocus(focus)
        }
    }

    /** Shows a different page kind without closing the editor or changing the underlying target. */
    fun showPage(target: ShellDestination) {
        return transition<Unit> {
            if (target.kind == page.kind) return@transition
            val focus = currentFocus.copy(pageRoute = target.initialFocus().pageRoute)
            if (!focus.valid()) return@transition
            closeStartup()
            publishSelection(value.saved.copy(focus = focus))
        }
    }

    /** Show chat without closing its editor or changing the requested target. */
    fun showChat() {
        return transition<Unit> {
            if (!page.isChatPage) showPage(ShellDestination.Chat())
        }
    }

    /** Open create-agent navigation beside the current chat, without restoring a draft. */
    fun openNewAgent() {
        return transition {
            openEditor(ShellDestination.AgentEditor())
        }
    }

    /** Decode a route entry and dispatch navigation; false for an unknown destination. */
    fun openRoute(route: String): Boolean {
        val target = ShellRoute.decode(route) ?: return false
        navigate(target)
        return true
    }

    /**
     * Tokenless compatibility report: only confirm an identical exact target with a known owner.
     * It cannot supply the missing provenance of a resume/create/owner lookup. Migrated hosts return
     * the originally captured token; null and conflicting legacy emissions never navigate.
     */
    fun conversationShown(conversation: ConversationId?, agent: AgentId?) {
        return transition<Unit> {
            if (currentFocus.chatMode != CurrentFocusChatMode.Exact || currentFocus.agentId == null) return@transition
            if (currentFocus.conversationId != conversation || currentFocus.agentId != agent) return@transition
            completeFocus(requestToken(), conversation, agent)
        }
    }

    /** Return the token captured when the chat effect began, including retained-owner rebinding. */
    fun conversationShown(token: CurrentFocusRequest, conversation: ConversationId?, agent: AgentId?): Boolean =
        completeFocus(token, conversation, agent)

    /** Token-bound roster observation; absence remains unknown until a targeted not-found. */
    fun rosterChanged(token: CurrentFocusRequest, agents: List<AgentId>): Boolean {
        return transition<Boolean> {
            if (!accepts(token)) return@transition false
            rosterChanged(agents)
            return@transition true
        }
    }

    /** Roster absence, including an empty/truncated roster, is unknown rather than deletion. */
    fun rosterChanged(agents: List<AgentId>) {
        return transition<Unit> {
            value = value.copy(agentAvailability = when {
                currentFocus.agentId in agents -> FocusAvailability.Available
                agentAvailability == FocusAvailability.Unavailable -> FocusAvailability.Unavailable
                else -> FocusAvailability.Unknown
            })
        }
    }

    /** Compatibility read adapter; caller selection and roster cannot override the owner. */
    @Suppress("UNUSED_PARAMETER")
    fun focusFor(selectedConversationAgent: AgentId?, roster: List<AgentId>): AgentId? = currentFocus.agentId

    /** Complete legacy/global catalog: prune inactive references, retaining the selected target. */
    fun conversationsReported(available: Set<ConversationId>) {
        return transition<Unit> {
            val availableValues = available.mapTo(mutableSetOf()) { it.value }
            val selected = selectedConversationId
            val gone = selected != null && selected.value != IdFormats.DEFAULT_CONVERSATION_ID && selected !in available
            value = value.copy(
                saved = value.saved.withTabs(ConversationTabsReducer.retainAvailable(tabs, availableValues, selected?.value)),
                history = value.history.filterValues { it.value == IdFormats.DEFAULT_CONVERSATION_ID || it in available },
                owners = value.owners.filterKeys { it in available },
                conversationAvailability = if (gone) FocusAvailability.Unavailable else conversationAvailability,
            )
        }
    }

    /** Partial pages can prune only owners whose entire page sequence completed. */
    fun conversationsReported(available: Map<ConversationId, AgentId>, authoritativeAgents: Set<AgentId>) {
        return transition<Unit> {
            applyCatalog(available, authoritativeAgents, available.keys)
        }
    }

    /** Token-bound catalog completion for migrated consumers. */
    fun conversationsReported(
        token: CurrentFocusRequest,
        available: Map<ConversationId, AgentId>,
        authoritativeAgents: Set<AgentId>,
    ): Boolean {
        return transition<Boolean> {
            if (!accepts(token)) return@transition false
            return@transition applyCatalog(available, authoritativeAgents, available.keys)
        }
    }

    /** Resume policy uses home-lane semantics, then trusted history, then eligible human recency. */
    fun resolveAgentChat(agent: AgentId, conversations: List<ShellConversationRef>): ConversationId? =
        resolveChat(agent, conversations, value.history[agent])

    /** Close in strip order, right neighbour then left; unresolved neighbours never borrow an agent. */
    fun closeConversationTab(conversation: ConversationId) {
        return transition<Unit> {
            val result = ConversationTabsReducer.close(tabs, conversation.value)
            if (conversation != selectedConversationId) {
                tabs = result.state
                return@transition
            }
            val fallback = result.fallbackConversationId?.let(::ConversationId)
            val focus = if (fallback == null) CurrentFocus() else CurrentFocus(
                "chat", value.owners[fallback], fallback, CurrentFocusChatMode.Exact,
            )
            closeStartup()
            val validFocus = focus.takeIf { it.valid() } ?: CurrentFocus()
            publishSelection(value.saved.copy(focus = validFocus, editorRoute = null).withTabs(result.state))
        }
    }

    private fun matchesCompletion(
        token: CurrentFocusRequest,
        conversation: ConversationId,
        agent: AgentId,
        reference: ShellConversationRef?,
    ): Boolean {
        val matchingOwner = conversation.value == IdFormats.DEFAULT_CONVERSATION_ID ||
            value.owners[conversation]?.let { it == agent } != false
        return requestedTargetMatches(token, conversation, agent) && matchingOwner &&
            referenceMatches(token, conversation, agent, reference)
    }

    private fun requestedTargetMatches(token: CurrentFocusRequest, conversation: ConversationId, agent: AgentId): Boolean {
        val matchingAgent = token.requestedAgent == agent || token.requestedAgent == null && token.mode == CurrentFocusChatMode.Exact
        val matchingConversation = token.mode != CurrentFocusChatMode.Exact || token.exactConversation == conversation
        return matchingAgent && matchingConversation
    }

    private fun referenceMatches(
        token: CurrentFocusRequest,
        conversation: ConversationId,
        agent: AgentId,
        reference: ShellConversationRef?,
    ): Boolean {
        if (reference == null) return true
        val matchingTarget = reference.id == conversation && reference.agent == agent
        val humanResume = token.mode == CurrentFocusChatMode.Exact || reference.lane.isHumanFallback
        return matchingTarget && humanResume
    }

    private fun humanHistory(
        token: CurrentFocusRequest,
        reference: ShellConversationRef?,
        agent: AgentId,
        conversation: ConversationId,
    ): Map<AgentId, ConversationId> {
        val eligible = reference?.lane?.isHumanFallback ?: (token.mode != CurrentFocusChatMode.Exact ||
            value.history[agent] == conversation)
        return when {
            eligible -> value.history + (agent to conversation)
            value.history[agent] == conversation -> value.history - agent
            else -> value.history
        }
    }

    private inline fun <T> transition(block: () -> T): T = synchronized(transitionLock, block)

    private fun closeStartup() {
        startupAvailable = false
        pendingSnapshot = null
    }

    private fun commitRestoration(snapshot: CurrentFocusSnapshot, generation: Long) {
        closeStartup()
        value = WorkspaceFocusState(snapshot.detached(), generation = generation, revision = focusRevision + 1)
    }

    private fun openEditor(editor: ShellDestination.AgentEditor) {
        val route = ShellRoute.encode(editor)
        if (!validFocusEditorRoute(route)) return
        closeStartup()
        val focus = currentFocus.copy(pageRoute = "chat")
        val saved = value.saved.copy(focus = focus, editorRoute = route)
        if (focus == currentFocus) value = value.copy(saved = saved) else publishSelection(saved)
    }

    private fun publishSelection(saved: CurrentFocusSnapshot) {
        check(saved.copy(sourceKey = sourceKey ?: "unbound").valid())
        val sameAgent = saved.focus.agentId == currentFocus.agentId
        val sameConversation = sameAgent && saved.focus.conversationId == currentFocus.conversationId &&
            saved.focus.chatMode == currentFocus.chatMode
        val selected = saved.focus.conversationId
        val nextTabs = if (selected != null && ShellRoute.decode(saved.focus.pageRoute)?.isChatPage == true) {
            ConversationTabsReducer.select(saved.tabs(), selected.value)
        } else {
            saved.tabs()
        }
        value = value.copy(
            saved = saved.withTabs(nextTabs),
            revision = focusRevision + 1,
            agentAvailability = if (sameAgent) agentAvailability else FocusAvailability.Unknown,
            conversationAvailability = if (sameConversation) conversationAvailability else FocusAvailability.Unknown,
            settled = null,
        )
    }

    private fun tabFor(conversation: ConversationId): ConversationTabsState =
        if (page.isChatPage) ConversationTabsReducer.select(tabs, conversation.value) else tabs

    private fun catalogOwnerMatches(id: ConversationId, agent: AgentId): Boolean {
        val validIds = id.value.isNotBlank() && agent.value.isNotBlank()
        val matchingOwner = value.owners[id]?.let { it == agent } != false
        return validIds && matchingOwner
    }

    private fun applyCatalog(
        available: Map<ConversationId, AgentId>,
        authoritative: Set<AgentId>,
        ids: Set<ConversationId>,
    ): Boolean {
        if (available.any { (id, agent) -> !catalogOwnerMatches(id, agent) }) return false
        val owners = value.owners + available.filterKeys { it.value != IdFormats.DEFAULT_CONVERSATION_ID }
        val gone = owners.filter { (id, agent) -> agent in authoritative && id !in ids }.keys
        val kept = tabs.openConversationIds.filterNot { ConversationId(it) in gone }.toSet()
        val nextTabs = ConversationTabsReducer.retainAvailable(tabs, kept, selectedConversationId?.value)
        value = value.copy(
            saved = value.saved.withTabs(nextTabs),
            owners = owners - gone,
            history = value.history.filterValues { it !in gone },
            conversationAvailability = if (selectedConversationId in gone) FocusAvailability.Unavailable else conversationAvailability,
        )
        return true
    }
}

/** The overlay stack's flags: each is open or not. */
@Stable
class ShellOverlays {
    var commandPalette: Boolean by mutableStateOf(false)
    var newConversation: Boolean by mutableStateOf(false)
    /** `/rename`'s dialog for the open chat. */
    var renameChat: Boolean by mutableStateOf(false)
    var irohResetConfirm: Boolean by mutableStateOf(false)
    var shortcutHelp: Boolean by mutableStateOf(false)
}
