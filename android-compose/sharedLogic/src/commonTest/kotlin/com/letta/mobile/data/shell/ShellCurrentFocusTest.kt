package com.letta.mobile.data.shell

import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live owner acceptance, separate from the serialization-only contract fixture. */
class ShellCurrentFocusTest {
    private val a = AgentId("a")
    private val b = AgentId("b")
    private val a1 = ConversationId("a1")
    private val b1 = ConversationId("b1")
    private val b2 = ConversationId("b2")

    @Test
    fun aThenBThenExactB2RejectsEveryStaleCompletion() {
        val state = workspace()
        state.selectFocus(CurrentFocus("chat", a))
        val first = state.requestToken()
        state.selectFocus(CurrentFocus("chat", b))
        val resume = state.requestToken()
        assertFalse(state.completeFocus(first, a1, a))
        assertFalse(state.completeFocus(first, null, a))
        assertFalse(state.reportAvailability(first, FocusAvailability.Unavailable))
        assertEquals(CurrentFocus("chat", b), state.currentFocus)
        state.selectFocus(exact(b, b2))
        val selectionRevision = state.focusRevision
        assertFalse(state.completeFocus(resume, b1, b))
        assertFalse(state.reportAvailability(resume, conversation = FocusAvailability.Unavailable))
        assertFalse(state.completeFocus(state.requestToken(), b1, b))
        assertFalse(state.completeFocus(state.requestToken(), b2, a))
        assertTrue(state.completeFocus(state.requestToken(), b2, b))
        assertEquals(exact(b, b2), state.currentFocus)
        assertEquals(selectionRevision, state.focusRevision)
        assertTrue(state.completeFocus(state.requestToken(), b2, b))
        assertEquals(selectionRevision, state.focusRevision, "same-focus hydration must not restart selection effects")
        assertEquals(exact(b, b2), assertNotNull(state.settledSnapshot).focus)
    }

    @Test
    fun reconnectAndSourceSwitchFenceOldWorkAndNeverReseed() {
        val state = workspace()
        state.selectFocus(exact(a, a1))
        assertTrue(state.completeFocus(state.requestToken(), a1, a, ShellConversationRef(a1, a, "2026-11-07")))
        assertEquals(a1, state.resolveAgentChat(a, emptyList()))
        state.openNewAgent()
        state.railExpanded = true
        val original = state.requestToken()
        val saved = state.snapshot()
        state.bindSource("source", 2)
        assertEquals(saved, state.snapshot(), "same-source reconnect retains navigation")
        assertFalse(state.completeFocus(original, a1, a))
        val reconnect = state.requestToken()
        state.bindSource("other-principal", 2)
        assertFalse(state.completeFocus(reconnect, a1, a))
        assertFalse(state.conversationsReported(reconnect, mapOf(a1 to a), setOf(a)))
        assertFalse(state.rosterChanged(reconnect, listOf(a)))
        assertEquals(CurrentFocus(), state.currentFocus)
        assertEquals(CurrentFocusSnapshot("other-principal", railExpanded = true), state.snapshot())
        assertNull(state.resolveAgentChat(a, emptyList()))
        assertFalse(state.initialize(CurrentFocusInputs("other-principal", settingsSeed = saved.copy(sourceKey = "other-principal"))))
        assertFalse(state.restore(saved), "a profile namespace change invalidates its old saved focus")
    }

    @Test
    fun requestsCannotCrossOwnerRecreationEvenWithIdenticalCounters() {
        val first = workspace()
        first.selectFocus(exact(a, a1))
        val token = first.requestToken()
        val restored = workspace()
        assertTrue(restored.restore(first.snapshot()))
        assertFalse(restored.completeFocus(token, a1, a))
        assertTrue(restored.completeFocus(restored.requestToken(), a1, a))
    }

    @Test
    fun missingAndDeletedTargetsRetainIdentityAndAnUnavailableTab() {
        val state = workspace()
        state.selectFocus(exact(a, a1))
        val token = state.requestToken()
        state.rosterChanged(emptyList())
        state.rosterChanged(listOf(b))
        assertEquals(FocusAvailability.Unknown, state.agentAvailability)
        assertTrue(state.reportAvailability(token, FocusAvailability.Unavailable, FocusAvailability.Unavailable))
        assertEquals(exact(a, a1), state.currentFocus)
        assertEquals(listOf("a1"), state.tabs.openConversationIds)
        assertEquals(FocusAvailability.Unavailable, state.agentAvailability)
        assertEquals(FocusAvailability.Unavailable, state.conversationAvailability)
        state.rosterChanged(emptyList())
        assertEquals(FocusAvailability.Unavailable, state.agentAvailability)
        state.conversationsReported(emptyMap(), setOf(a))
        assertEquals(exact(a, a1), state.currentFocus)
        assertEquals(listOf("a1"), state.tabs.openConversationIds)
        assertTrue(state.completeFocus(token, a1, a), "targeted recovery is allowed outside roster/recents")
        assertEquals(FocusAvailability.Available, state.conversationAvailability)
        assertEquals(FocusAvailability.Unavailable, state.agentAvailability, "conversation hydration cannot invent a missing agent object")
    }

    @Test
    fun completePagesPruneOnlyInactiveReferencesOfTheirOwnAgent() {
        val state = workspace()
        for ((agent, id) in listOf(a to a1, b to b1, b to b2)) {
            state.selectFocus(exact(agent, id))
            assertTrue(state.completeFocus(state.requestToken(), id, agent, ShellConversationRef(id, agent, "2026-11-07")))
            state.tabs = ConversationTabsReducer.pin(state.tabs, state.tabs.openConversationIds.lastIndex)
        }
        state.conversationsReported(emptyMap(), emptySet())
        assertEquals(listOf("a1", "b1", "b2"), state.tabs.openConversationIds)
        state.conversationsReported(emptyMap(), setOf(a))
        assertEquals(listOf("b1", "b2"), state.tabs.openConversationIds)
        assertEquals(b2, state.resolveAgentChat(b, emptyList()), "another agent's complete list cannot clear this history")
        state.conversationsReported(emptyMap(), setOf(b))
        assertEquals(listOf("b2"), state.tabs.openConversationIds)
        assertEquals(exact(b, b2), state.currentFocus)
        assertEquals(FocusAvailability.Unavailable, state.conversationAvailability)
        assertNull(state.resolveAgentChat(b, emptyList()), "trustworthy deletion clears history")
    }

    @Test
    fun closingTabsUsesOwnerSelectionAndRightThenLeftOrder() {
        val state = workspace()
        state.conversationsReported(mapOf(a1 to a, b1 to b, b2 to b), emptySet())
        state.tabs = ConversationTabsState(listOf("a1", "b1", "b2"))
        state.selectFocus(exact(b, b1))
        state.closeConversationTab(b1)
        assertEquals(exact(b, b2), state.currentFocus)
        assertEquals(listOf("a1", "b2"), state.tabs.openConversationIds)
        state.closeConversationTab(b2)
        assertEquals(exact(a, a1), state.currentFocus)
        state.closeConversationTab(a1)
        assertEquals(CurrentFocus(), state.currentFocus)
        assertTrue(state.tabs.openConversationIds.isEmpty())
    }

    @Test
    fun unknownAdjacentOwnerIsHeldUnresolvedAndNeverBorrowsCurrentAgent() {
        val state = workspace()
        state.tabs = ConversationTabsState(listOf("unknown", "a1"))
        state.selectFocus(exact(a, a1))
        state.closeConversationTab(a1)
        assertEquals(exact(null, ConversationId("unknown")), state.currentFocus)
        assertEquals(listOf("unknown"), state.tabs.openConversationIds)
        assertTrue(state.completeFocus(state.requestToken(), ConversationId("unknown"), b))
        assertEquals(b, state.currentFocus.agentId)
    }

    @Test
    fun editorOverPendingChatDoesNotSelectItsAgentOrInvalidateResolution() {
        val state = workspace()
        state.selectFocus(CurrentFocus("chat", a))
        val request = state.requestToken()
        state.navigate(ShellDestination.AgentEditor("b"))
        assertTrue(state.completeFocus(request, a1, a))
        val saved = state.snapshot()
        assertEquals("agent-editor?agent=b", saved.editorRoute)
        assertEquals(exact(a, a1), saved.focus)
        state.editAgentId = null
        assertEquals("chat?agent=a&conversation=a1", state.route)
    }

    @Test
    fun invalidSnapshotsAndModesAreRejectedAsAUnit() {
        val state = ShellWorkspaceState(ShellDestination.Chat(a.value, a1.value)).apply { bindSource("source", 1) }
        state.tabs = ConversationTabsState(listOf("a1"), "b1")
        val before = state.snapshot()
        val invalid = listOf(
            before.copy(contractVersion = 2),
            before.copy(sourceKey = "foreign"),
            before.copy(focus = CurrentFocus("agent-editor?agent=b")),
            before.copy(focus = CurrentFocus("chat?agent=b")),
            before.copy(focus = CurrentFocus("chat?conversation=b2")),
            before.copy(focus = CurrentFocus("unknown")),
            before.copy(focus = CurrentFocus("settings?section=unknown")),
            before.copy(focus = CurrentFocus("chat", b, b2, CurrentFocusChatMode.New)),
            before.copy(focus = CurrentFocus("chat", b, chatMode = CurrentFocusChatMode.Exact)),
            before.copy(pinnedConversationIds = listOf(a1, a1)),
            before.copy(previewConversationId = a1),
            before.copy(editorRoute = "memory?agent=b"),
            before.copy(editorRoute = "agent-editor?agent="),
        )
        for (candidate in invalid) {
            assertFalse(state.restore(candidate), candidate.toString())
            assertEquals(before, state.snapshot())
        }
        assertFalse(state.restoreJson("{broken"))
        assertFalse(state.restoreJson(state.snapshotJson().replace("\"exact\"", "\"unsupported\"")))
        assertEquals(before, state.snapshot())
    }

    @Test
    fun snapshotRoundTripsAllModesPayloadsEditorTargetsAndTabs() {
        val pages = listOf("home", "chat", "memory", "schedules", "skills?section=tools", "settings?section=pairing", "canvas?id=board")
        val targets = listOf(CurrentFocus(), CurrentFocus(agentId = a),
            CurrentFocus(agentId = b, chatMode = CurrentFocusChatMode.New), exact(a, a1), exact(null, b2))
        for (page in pages) for (target in targets) for (editor in listOf(null, "agent-editor", "agent-editor?agent=b")) {
            val snapshot = CurrentFocusSnapshot(
                "source", target.copy(pageRoute = page), listOf(a1, b1), b2, editor, railExpanded = true,
            )
            val state = workspace()
            assertTrue(state.restore(snapshot))
            val restored = workspace()
            assertTrue(restored.restoreJson(state.snapshotJson()))
            assertEquals(snapshot, restored.snapshot())
            assertFalse(restored.accepts(state.requestToken()))
        }
    }

    @Test
    fun rotationRetainsAgentConversationAndUnknownOwnerAcrossNullRosterEmissions() {
        for (agent in listOf(a, null)) {
            val state = workspace()
            state.selectFocus(exact(agent, a1))
            val restored = workspace()
            assertTrue(restored.restoreJson(state.snapshotJson()))
            restored.rosterChanged(emptyList())
            restored.conversationShown(null, null)
            assertEquals(exact(agent, a1), restored.currentFocus)
            assertEquals(listOf("a1"), restored.tabs.openConversationIds)
        }
    }

    @Test
    fun startupValidatesWholeCandidatesAndIgnoresLateSettings() {
        val saved = CurrentFocusSnapshot("source", exact(a, a1), listOf(a1), b1, "agent-editor?agent=a", true)
        val invalidEntry = CurrentFocusSnapshot("source", CurrentFocus("chat", b, b2))
        val entry = CurrentFocusSnapshot("source", exact(null, b2))
        val state = ShellWorkspaceState()
        assertTrue(state.initialize(CurrentFocusInputs("source", invalidEntry, entry, saved)))
        assertEquals(entry.focus, state.currentFocus)
        assertNull(state.agentEditor)
        assertEquals(listOf("a1", "b1"), state.tabs.openConversationIds)
        assertTrue(state.railExpanded)
        assertFalse(state.initialize(CurrentFocusInputs("source", settingsSeed = saved)))
        assertEquals(entry.focus, state.currentFocus)
        val unknownLegacy = ShellWorkspaceState()
        assertTrue(unknownLegacy.initialize(CurrentFocusInputs("source")))
        assertEquals(CurrentFocus(), unknownLegacy.currentFocus, "unknown-source legacy record remains outside the owner")
    }

    @Test
    fun defaultIsAgentScopedAndNeverIdentifiedGlobally() {
        val state = workspace()
        val default = ConversationId("default")
        assertFalse(state.selectFocus(exact(null, default)))
        for (agent in listOf(a, b)) {
            assertTrue(state.selectFocus(exact(agent, default)))
            assertTrue(state.completeFocus(state.requestToken(), default, agent))
            assertEquals(exact(agent, default), state.currentFocus)
        }
    }

    private fun workspace(): ShellWorkspaceState = ShellWorkspaceState().apply { bindSource("source", 1) }
    private fun exact(agent: AgentId?, id: ConversationId): CurrentFocus =
        CurrentFocus("chat", agent, id, CurrentFocusChatMode.Exact)
}
