package com.letta.mobile.data.shell

import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recreation before source binding must retain the entire envelope without publishing its identity. */
class ShellPendingFocusTest {
    private val saved = CurrentFocusSnapshot(
        sourceKey = "source",
        focus = CurrentFocus("chat", conversationId = ConversationId("exact"), chatMode = CurrentFocusChatMode.Exact),
        pinnedConversationIds = listOf(ConversationId("second"), ConversationId("first")),
        previewConversationId = ConversationId("exact"),
        editorRoute = "agent-editor?agent=editor",
        railExpanded = true,
    )

    @Test
    fun saverCanReadBeforeBindingAndPendingNeverPublishesFocus() {
        val state = ShellWorkspaceState()
        val token = state.requestToken()
        assertNull(state.snapshotOrPending())
        assertTrue(state.holdPending(saved))
        assertEquals(saved, state.snapshotOrPending())
        assertEquals(CurrentFocus(), state.currentFocus)
        assertEquals(ShellDestination.Home, state.destination)
        assertEquals(ConversationTabsState(), state.tabs)
        assertNull(state.agentEditor)
        assertNull(state.sourceKey)
        assertNull(state.settledSnapshot)
        assertFalse(state.railExpanded)
        assertEquals(0L, state.focusRevision)
        assertTrue(state.accepts(token))
    }

    @Test
    fun sameSourceBindRestoresEverythingOnceAndFencesOldRequests() {
        val editors = listOf(
            null to null,
            "agent-editor" to ShellDestination.AgentEditor(),
            "agent-editor?agent=editor" to ShellDestination.AgentEditor("editor"),
        )
        for ((route, editor) in editors) {
            val snapshot = saved.copy(editorRoute = route)
            val state = ShellWorkspaceState()
            val token = state.requestToken()
            assertTrue(state.holdPending(snapshot))
            state.bindSource("source", 7)
            assertEquals(snapshot, state.snapshot())
            assertEquals(snapshot, state.snapshotOrPending())
            assertEquals(snapshot.focus, state.currentFocus)
            assertEquals(CurrentFocusChatMode.Exact, state.currentFocus.chatMode)
            assertNull(state.currentFocus.agentId, "an unresolved conversation owner must stay unresolved")
            assertEquals(ConversationTabsState(listOf("second", "first"), "exact"), state.tabs)
            assertEquals(editor, state.agentEditor)
            assertTrue(state.railExpanded)
            assertEquals(7L, state.sourceGeneration)
            assertEquals(1L, state.focusRevision, "binding and restoration must be one transition")
            assertEquals(FocusAvailability.Unknown, state.agentAvailability)
            assertEquals(FocusAvailability.Unknown, state.conversationAvailability)
            assertNull(state.settledSnapshot)
            assertFalse(state.accepts(token))
            assertFalse(state.holdPending(saved))
            assertFalse(state.restore(saved))
            assertFalse(state.initialize(CurrentFocusInputs("source")))
            state.railExpanded = false
            state.bindSource("source", 7)
            assertEquals(1L, state.focusRevision)
            state.bindSource("source", 8)
            assertEquals(snapshot.copy(railExpanded = false), state.snapshot(), "reconnect must not replay pending state")
            assertEquals(2L, state.focusRevision)
        }
    }

    @Test
    fun bindingPendingRebuildsRuntimeEvidenceInsteadOfRetainingIt() {
        val oldAgent = AgentId("old")
        val oldConversation = ConversationId("old-chat")
        val state = ShellWorkspaceState(ShellDestination.Chat(oldAgent.value, oldConversation.value))
        state.conversationsReported(mapOf(ConversationId("exact") to oldAgent), emptySet())
        assertTrue(state.completeFocus(state.requestToken(), oldConversation, oldAgent,
            ShellConversationRef(oldConversation, oldAgent, "2026-11-07")))
        assertTrue(state.reportAvailability(state.requestToken(), FocusAvailability.Unavailable, FocusAvailability.Available))
        assertEquals(oldConversation, state.resolveAgentChat(oldAgent, emptyList()), "establish runtime history before binding")
        val revision = state.focusRevision
        assertTrue(state.holdPending(saved))
        state.bindSource("source", 1)
        assertEquals(revision + 1, state.focusRevision)
        assertNull(state.resolveAgentChat(oldAgent, emptyList()))
        assertEquals(FocusAvailability.Unknown, state.agentAvailability)
        assertEquals(FocusAvailability.Unknown, state.conversationAvailability)
        assertTrue(state.completeFocus(state.requestToken(), ConversationId("exact"), AgentId("resolved")),
            "old ownership evidence must not veto the restored unresolved owner")
    }

    @Test
    fun resumeAndNewModesRemainDistinctWhenPendingIsBound() {
        for (mode in listOf(CurrentFocusChatMode.Resume, CurrentFocusChatMode.New)) {
            val snapshot = saved.copy(focus = CurrentFocus("chat", AgentId("agent"), chatMode = mode))
            val state = ShellWorkspaceState()
            assertTrue(state.holdPending(snapshot))
            state.bindSource("source", 1)
            assertEquals(snapshot, state.snapshot())
            assertEquals(mode, state.currentFocus.chatMode)
            assertNull(state.currentFocus.conversationId)
        }
    }

    @Test
    fun midStartupRecreationCanSaveThePendingEnvelopeAgain() {
        val first = ShellWorkspaceState()
        assertTrue(first.holdPending(saved))
        val recreated = ShellWorkspaceState()
        assertTrue(recreated.holdPending(assertNotNull(first.snapshotOrPending())))
        assertEquals(CurrentFocus(), recreated.currentFocus)
        recreated.bindSource("source", 1)
        assertEquals(saved, recreated.snapshot())
    }

    @Test
    fun latestHoldReplacesTheOnlyPendingEnvelopeBeforeBinding() {
        val state = ShellWorkspaceState()
        val latest = saved.copy(focus = CurrentFocus("memory", AgentId("new")))
        assertTrue(state.holdPending(saved))
        assertTrue(state.holdPending(latest))
        assertEquals(latest, state.snapshotOrPending())
        assertEquals(0L, state.focusRevision)
        state.bindSource("source", 1)
        assertEquals(latest, state.snapshot())
    }

    @Test
    fun foreignSourceBindDiscardsPendingAndCannotReplayItLater() {
        val state = ShellWorkspaceState(ShellDestination.Chat("initial", "initial-chat"))
        assertTrue(state.holdPending(saved))
        state.bindSource("foreign", 1)
        assertEquals(CurrentFocusSnapshot("foreign"), state.snapshotOrPending())
        assertEquals(CurrentFocus(), state.currentFocus)
        assertNull(state.agentEditor)
        assertTrue(state.tabs.openConversationIds.isEmpty())
        state.bindSource("source", 2)
        assertEquals(CurrentFocusSnapshot("source"), state.snapshot())
        assertFalse(state.holdPending(saved))
    }

    @Test
    fun userNavigationBeforeBindingInvalidatesPending() {
        val actions: List<(ShellWorkspaceState) -> Unit> = listOf(
            { it.navigate(ShellDestination.Home) },
            { it.navigate(ShellDestination.Chat("chosen")) },
            { it.openRoute("memory?agent=chosen") },
            { it.showPage(ShellDestination.Memory("chosen")) },
            { it.openNewAgent() },
            { it.agentEditor = null },
            { it.focusedAgentId = AgentId("chosen") },
            { it.selectFocus(CurrentFocus("chat", AgentId("chosen"), chatMode = CurrentFocusChatMode.New)) },
        )
        for (navigate in actions) {
            val state = ShellWorkspaceState()
            assertTrue(state.holdPending(saved))
            navigate(state)
            val chosen = state.currentFocus
            val editor = state.agentEditor
            assertNull(state.snapshotOrPending(), "navigation discards pending while still unbound")
            assertFalse(state.holdPending(saved), "navigation closes the startup window")
            state.bindSource("source", 1)
            assertEquals(chosen, state.currentFocus)
            assertEquals(editor, state.agentEditor)
        }
    }

    @Test
    fun initializeBeforeBindingSupersedesPendingForEveryStartupEntry() {
        val entry = CurrentFocusSnapshot("source", CurrentFocus("chat", AgentId("chosen"), chatMode = CurrentFocusChatMode.New))
        val candidates = listOf(
            CurrentFocusInputs("source"),
            CurrentFocusInputs("source", deepLink = entry),
            CurrentFocusInputs("source", explicitRoute = entry),
            CurrentFocusInputs("source", settingsSeed = entry),
            CurrentFocusInputs("source", restored = entry),
            CurrentFocusInputs("source", deepLink = entry, explicitRoute = saved),
        )
        for (inputs in candidates) {
            val state = ShellWorkspaceState()
            assertTrue(state.holdPending(saved))
            assertTrue(state.initialize(inputs))
            val expected = if (inputs == candidates.first()) CurrentFocusSnapshot("source") else entry
            assertEquals(expected, state.snapshotOrPending())
            assertFalse(state.holdPending(saved))
            state.bindSource("source", 3)
            assertEquals(expected, state.snapshot())
        }
    }

    @Test
    fun restoreAttemptAlwaysDiscardsPendingEvenWhenUnboundOrInvalid() {
        for (snapshot in listOf(saved, saved.copy(contractVersion = 2))) {
            val state = ShellWorkspaceState()
            assertTrue(state.holdPending(saved))
            assertFalse(state.restore(snapshot))
            assertNull(state.snapshotOrPending())
            state.bindSource("source", 1)
            assertEquals(CurrentFocusSnapshot("source"), state.snapshot())
            assertTrue(state.restore(saved), "rejected restore must keep its existing startup semantics")
            assertFalse(state.holdPending(saved))
        }
    }

    @Test
    fun invalidPendingIsRejectedAtomicallyAndCannotReplaceAValidEnvelope() {
        val invalid = listOf(
            saved.copy(contractVersion = 0),
            saved.copy(contractVersion = 2),
            saved.copy(sourceKey = " "),
            saved.copy(focus = saved.focus.copy(pageRoute = "unknown")),
            saved.copy(focus = saved.focus.copy(pageRoute = "chat?agent=foreign")),
            saved.copy(focus = saved.focus.copy(chatMode = CurrentFocusChatMode.New)),
            saved.copy(focus = saved.focus.copy(conversationId = null)),
            saved.copy(focus = saved.focus.copy(conversationId = ConversationId("default"))),
            saved.copy(pinnedConversationIds = listOf(ConversationId("duplicate"), ConversationId("duplicate"))),
            saved.copy(previewConversationId = saved.pinnedConversationIds.first()),
            saved.copy(editorRoute = "memory?agent=editor"),
            saved.copy(editorRoute = "agent-editor?agent="),
        )
        val state = ShellWorkspaceState()
        for (candidate in invalid) {
            assertFalse(state.holdPending(candidate))
            assertNull(state.snapshotOrPending())
            assertEquals(CurrentFocus(), state.currentFocus)
            assertEquals(0L, state.focusRevision)
        }
        assertTrue(state.holdPending(saved))
        for (candidate in invalid) {
            assertFalse(state.holdPending(candidate))
            assertEquals(saved, state.snapshotOrPending())
        }
        state.bindSource("source", 1)
        assertEquals(saved, state.snapshot())
    }

    @Test
    fun pendingAndReturnedSnapshotsDoNotAliasTheCallersMutableTabList() {
        val pins = saved.pinnedConversationIds.toMutableList()
        val state = ShellWorkspaceState()
        assertTrue(state.holdPending(saved.copy(pinnedConversationIds = pins)))
        pins.clear()
        val returned = assertNotNull(state.snapshotOrPending())
        (returned.pinnedConversationIds as MutableList<ConversationId>).clear()
        assertEquals(saved, state.snapshotOrPending())
        state.bindSource("source", 1)
        assertEquals(saved, state.snapshot())
    }

    @Test
    fun sourceBindingBeforeAHoldRequiresTheExistingRestoreApi() {
        val state = ShellWorkspaceState()
        state.bindSource("source", 1)
        assertFalse(state.holdPending(saved))
        assertTrue(state.restore(saved))
        assertEquals(saved, state.snapshotOrPending())
    }
}
