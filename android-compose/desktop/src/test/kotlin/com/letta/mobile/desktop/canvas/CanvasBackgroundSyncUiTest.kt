@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/** A background colour picked on the board is sent to the other apps on its own, not with the next stroke. */
class CanvasBackgroundSyncUiTest {
    private class Capturing : CanvasSyncTransport {
        val published = CopyOnWriteArrayList<CanvasOp>()
        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) { published += op }
        override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = emptyFlow()
    }

    @Test
    fun aBackgroundColourAloneIsPublished() = runComposeUiTest {
        val relay = Capturing()
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = "", syncTransport = relay))
        }
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }
        // Past the load and its first autosave, so nothing else is on its way out that the
        // colour could ride along with.
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        relay.published.clear()
        controller.setBgColor(Color(0xFF112233))
        mainClock.advanceTimeBy(3_000)
        waitUntil(timeoutMillis = 5000) {
            relay.published.any { it is CanvasOp.SetBackgroundOp && it.colorHex.startsWith("#112233", ignoreCase = true) }
        }
    }

    /** Delivers what another app sent. */
    private class Incoming : CanvasSyncTransport {
        val ops = MutableSharedFlow<CanvasOp>(replay = 8)
        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) = Unit
        override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = ops
    }

    @Test
    fun aPatternSetOnAnotherAppChangesThisBoard() = runComposeUiTest {
        val relay = Incoming()
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = "", syncTransport = relay))
        }
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        val dots = CanvasBackgroundPattern(CanvasBackgroundPattern.DOTS, spacing = 64f, colorHex = "#e5484d")
        runBlocking { relay.ops.emit(CanvasOp.SetBackgroundPatternOp("remote-1", CanvasSession.LOCAL_USER_ACTOR_ID, 50, dots)) }
        mainClock.advanceTimeBy(3_000)
        waitUntil(timeoutMillis = 5000) { session.backgroundPattern() == dots }
        waitUntil(timeoutMillis = 5000) { controller.state.value.bgPattern?.tint == Color(0xFFE5484D) }
    }
}
