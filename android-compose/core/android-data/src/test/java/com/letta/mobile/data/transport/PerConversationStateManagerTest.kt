package com.letta.mobile.data.transport

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PerConversationStateManagerTest {
    @Test
    fun `state is isolated per conversation`() {
        val manager = PerConversationStateManager()
        val first = manager.stateForConversation("conv-1")
        val second = manager.stateForConversation("conv-2")

        first.inFlight.set(true)
        first.currentRunId.set("run-1")
        first.currentTurnId.set("turn-1")

        assertSame(first, manager.stateForConversation("conv-1"))
        assertFalse(second.inFlight.get())
        assertNull(second.currentRunId.get())
        assertNull(second.currentTurnId.get())
        assertEquals(2, manager.activeConversationCount())
    }

    @Test
    fun `run mapping records and clears active conversation`() {
        val manager = PerConversationStateManager()

        manager.recordRunConversation("run-1", "conv-1")

        assertEquals("conv-1", manager.activeConversationForRun("run-1"))

        manager.clearRunConversation("run-1")


        assertNull(manager.activeConversationForRun("run-1"))
    }

    @Test
    fun `clearing turn state keeps queued actions intact`() {
        val manager = PerConversationStateManager()
        val state = manager.stateForConversation("conv-1")
        state.inFlight.set(true)
        state.currentRunId.set("run-1")
        state.currentTurnId.set("turn-1")
        manager.pendingA2uiActions("conv-1").addLast(userActionFrame("frame-1"))
        manager.recordRunConversation("run-1", "conv-1")

        manager.clearConversationTurnState("conv-1")

        assertFalse(state.inFlight.get())
        assertNull(state.currentRunId.get())
        assertNull(state.currentTurnId.get())
        assertEquals(1, manager.pendingA2uiActionCount())
        assertEquals("conv-1", manager.activeConversationForRun("run-1"))
    }

    @Test
    fun `clearing all turn state resets conversations and run mapping`() {
        val manager = PerConversationStateManager()
        val state = manager.stateForConversation("conv-1")
        state.inFlight.set(true)
        state.currentRunId.set("run-1")
        state.currentTurnId.set("turn-1")
        manager.recordRunConversation("run-1", "conv-1")

        manager.clearAllTurnState()

        assertFalse(state.inFlight.get())
        assertNull(state.currentRunId.get())
        assertNull(state.currentTurnId.get())
        assertNull(manager.activeConversationForRun("run-1"))
    }

    @Test
    fun `pending actions can be inspected and cleared by conversation`() {
        val manager = PerConversationStateManager()
        val first = userActionFrame("frame-1")
        val second = userActionFrame("frame-2")
        manager.pendingA2uiActions("conv-1").addLast(first)
        manager.pendingA2uiActions("conv-2").addLast(second)

        assertEquals(2, manager.pendingA2uiActionCount())
        assertEquals(1, manager.clearPendingA2uiActions("conv-1"))

        assertTrue(manager.pendingA2uiActions("conv-1").isEmpty())
        assertEquals(second, manager.existingPendingA2uiActions("conv-2")?.single())
        assertEquals(1, manager.pendingA2uiActionCount())

        manager.clearPendingA2uiActions()

        assertEquals(0, manager.pendingA2uiActionCount())
    }

    private fun userActionFrame(id: String): UserActionFrame = UserActionFrame(
        id = id,
        ts = "1970-01-01T00:00:00Z",
        name = "tap",
        context = JsonObject(emptyMap()),
    )
}
