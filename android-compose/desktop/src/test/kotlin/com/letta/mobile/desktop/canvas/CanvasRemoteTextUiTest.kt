@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Text placed on another app arrives as text, not as a caret: it must not open this device's keyboard. */
class CanvasRemoteTextUiTest {
    private class Incoming : CanvasSyncTransport {
        val ops = MutableSharedFlow<CanvasOp>(replay = 8)
        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) = Unit
        override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = ops
    }

    @Test
    fun emptyTextPlacedElsewhereGetsNoCaretHere() = runComposeUiTest {
        val relay = Incoming()
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = "", syncTransport = relay))
        }
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }
        mainClock.advanceTimeBy(3_000)
        waitForIdle()

        val placed = Element.Text(
            id = "remote-text", text = "", fontSize = 24f, color = Color.Black,
            topLeft = Offset(200f, 200f), wrapWidth = 240f, measuredHeight = 32f,
        )
        val scene = DrawingSerializer.serialize(PayLoad(bgColor = Color.White, elements = listOf(placed)))
        runBlocking { relay.ops.emit(CanvasOp.ReplaceSceneOp("remote-1", CanvasSession.LOCAL_USER_ACTOR_ID, 50, scene)) }
        mainClock.advanceTimeBy(3_000)
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.any { it.id == "remote-text" } }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertTrue(
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(),
            "no text editor should open for text another app placed",
        )
    }
}
