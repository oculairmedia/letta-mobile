package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The side menu's pick, and the page following it without losing an edit. */
class ConversationCanvasPickerTest {
    private val target = ConversationCanvasTarget(AgentId("agent-1"), ConversationId("conv-1"))
    private val foreign = CanvasId("canvas-foreign")

    private class PickCase(val name: String, val supported: Boolean, val inCanvasMode: Boolean, val mounts: Boolean)

    @Test
    fun aPickMountsOnlyInCanvasModeOnAHostWithMounts() = runTest {
        val cases = listOf(
            PickCase("canvas mode, mounts", supported = true, inCanvasMode = true, mounts = true),
            PickCase("not in canvas mode", supported = true, inCanvasMode = false, mounts = false),
            PickCase("host without mounts", supported = false, inCanvasMode = true, mounts = false),
        )
        for (case in cases) {
            val client = FakeCanvasMountClient(case.supported)
            val picker = ConversationCanvasPicker(ConversationCanvasResolver(client))
            if (case.inCanvasMode) picker.focus.enter(target)
            val pick = picker.pick(foreign)
            assertEquals(case.mounts, pick is CanvasPick.Mounted, case.name)
            assertEquals(case.mounts, client.stored[target] == foreign, case.name)
            assertEquals(case.mounts, picker.state.first().mountsOnPick, case.name)
        }
    }

    @Test
    fun theMenuSeesTheMountedCanvasAndTheOwnBoardComesBack() = runTest {
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(FakeCanvasMountClient()))
        picker.focus.enter(target)
        picker.pick(foreign)
        assertEquals(foreign, picker.state.first { it.mounted != null }.mounted)
        assertIs<CanvasPick.Mounted>(picker.useOwnCanvas())
        assertNull(picker.state.first { it.mounted == null }.mounted)
    }

    @Test
    fun aRefusedPickSaysWhy() = runTest {
        val client = FakeCanvasMountClient().apply { writeAnswers.add(CanvasMountChange.Failed(CanvasMountProblem.Denied)) }
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(client))
        picker.focus.enter(target)
        assertEquals(CanvasPick.Failed(CanvasMountProblem.Denied), picker.pick(foreign))
    }

    @Test
    fun leavingCanvasModeKeepsANewerConversation() {
        val focus = CanvasModeFocus()
        val other = target.copy(conversationId = ConversationId("conv-2"))
        focus.enter(target)
        focus.enter(other)
        focus.leave(target)
        assertEquals(other, focus.current.value)
        focus.leave(other)
        assertNull(focus.current.value)
    }

    @Test
    fun aSwitchArrivingMidEditSavesTheDrawingFirst() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val board = CanvasSession.create(store, CanvasCreateOptions(title = "Own", conversationId = "conv-1", agentId = "agent-1"))
        val gate = CanvasBoardSwitchGate()
        val unsaved = """{"elements":[{"id":"stroke-1"}]}"""
        gate.attach { board.saveScene(unsaved); true }
        val switch = ConversationBoardSwitch(gate)

        assertTrue(switch.follow(ConversationCanvasState.Mounted(foreign, CanvasMountSource.Binding)))

        assertEquals(foreign, switch.shown.value)
        assertTrue(store.get(board.canvasId)?.sceneJson.orEmpty().contains("stroke-1"))
    }

    @Test
    fun aFailedSaveKeepsTheBoardAndItsDrawing() = runTest {
        val gate = CanvasBoardSwitchGate()
        gate.attach { false }
        val switch = ConversationBoardSwitch(gate)
        assertFalse(switch.follow(ConversationCanvasState.Mounted(foreign, CanvasMountSource.Binding)))
        assertNull(switch.shown.value)
    }

    @Test
    fun resolvingKeepsTheBoardAndAnOutagePutsTheOwnBoardBack() = runTest {
        val switch = ConversationBoardSwitch(CanvasBoardSwitchGate())
        switch.follow(ConversationCanvasState.Mounted(foreign, CanvasMountSource.Binding))
        switch.follow(ConversationCanvasState.Resolving)
        assertEquals(foreign, switch.shown.value)
        switch.follow(ConversationCanvasState.Unavailable(CanvasMountProblem.Denied))
        assertNull(switch.shown.value)
    }
}
