package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatSubagentTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The timeline hands the rows the host's agent names and subagent opener (letta-mobile-bglj6.1). */
class TimelineRowBindingTest {

    @Test
    fun rowsResolveAgentNamesThroughTheHost() {
        val host = ChatSurfaceHost(resolveAgentName = { id -> if (id == "agent-1") "Meridian" else null })
        val callbacks = rowCallbacksFor(RecordingChatActions(), host) { _, _ -> }

        assertEquals("Meridian", callbacks.resolveAgentName("agent-1"))
        assertNull(callbacks.resolveAgentName("agent-2"))
    }

    @Test
    fun aHostWithoutNamesFallsBackToTheIdLabel() {
        val callbacks = rowCallbacksFor(RecordingChatActions(), ChatSurfaceHost()) { _, _ -> }

        assertNull(callbacks.resolveAgentName("agent-1"))
    }

    @Test
    fun rowsOpenSubagentsThroughTheHost() {
        val opened = mutableListOf<Triple<String, String?, String>>()
        val host = ChatSurfaceHost(openSubagent = { callId, agentId, description -> opened += Triple(callId, agentId, description) })
        val callbacks = rowCallbacksFor(RecordingChatActions(), host) { _, _ -> }

        callbacks.openSubagent?.invoke(ChatSubagentTarget("call-1", "Audit the build", "agent-sub"))

        assertEquals(listOf<Triple<String, String?, String>>(Triple("call-1", "agent-sub", "Audit the build")), opened)
    }

    @Test
    fun aHostWithoutASubagentOpenerHidesTheAffordance() {
        val callbacks = rowCallbacksFor(RecordingChatActions(), ChatSurfaceHost()) { _, _ -> }

        assertNull(callbacks.openSubagent)
    }
}
