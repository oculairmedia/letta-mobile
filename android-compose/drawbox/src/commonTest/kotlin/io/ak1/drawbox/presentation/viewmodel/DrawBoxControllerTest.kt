package io.ak1.drawbox.presentation.viewmodel

import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DrawBoxControllerTest {

    private fun newController() = DrawBoxController(Reducer(UseCase()))

    @Test
    fun intentsFlowEmitsEveryProcessedIntent() = runTest(StandardTestDispatcher()) {
        val controller = newController()
        val collected = mutableListOf<Intent>()
        val job = launch { controller.intents.take(2).toList(collected) }

        // Yield so the collector actually subscribes before we emit.
        testScheduler.runCurrent()

        controller.onIntent(Intent.SetStrokeColor(Color.Red))
        controller.onIntent(Intent.SetStrokeWidth(7f))

        job.join()

        assertEquals(2, collected.size)
        assertTrue(collected[0] is Intent.SetStrokeColor)
        assertTrue(collected[1] is Intent.SetStrokeWidth)
    }

    @Test
    fun eventsEmittedInABurstAllArrive() = runTest(StandardTestDispatcher()) {
        val controller = newController()
        val collected = mutableListOf<io.ak1.drawbox.domain.model.Event>()
        val job = launch { controller.events.take(3).toList(collected) }
        testScheduler.runCurrent()

        // Back to back, before the collector has had a chance to run.
        controller.exportJson()
        controller.exportSvg()
        controller.exportJson()

        job.join()
        assertEquals(3, collected.size, "every export in a burst should be delivered")
    }

    @Test
    fun intentsFlowEmitsAfterStateUpdate() = runTest(StandardTestDispatcher()) {
        val controller = newController()
        var stateAtEmission: Color? = null
        val job = launch {
            controller.intents.take(1).collect {
                // Guarantee: at emission time, state.value already reflects the
                // reduced intent.
                stateAtEmission = controller.state.value.strokeColor
            }
        }
        testScheduler.runCurrent()

        controller.onIntent(Intent.SetStrokeColor(Color.Blue))
        job.join()

        assertEquals(Color.Blue, stateAtEmission)
    }

    private fun square(id: String) = io.ak1.drawbox.domain.model.Element.Shape(
        id = id,
        shapeType = io.ak1.drawbox.domain.model.ShapeType.RECTANGLE,
        points = listOf(androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(10f, 10f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
    )

    @Test
    fun aNewElementLandsAboveOneBroughtToFront() {
        val controller = newController()
        listOf("a", "b", "c").forEach { controller.onIntent(Intent.AddElement(square(it))) }
        controller.selectIds(setOf("a"))
        controller.onIntent(Intent.BringSelectionToFront)
        controller.selectIds(setOf("b"))
        controller.onIntent(Intent.DeleteSelected)
        controller.onIntent(Intent.AddElement(square("d")))
        val z = controller.state.value.elements.associate { it.id to it.zIndex }
        assertTrue(z.getValue("d") > z.getValue("a"), "zIndex $z")
    }

    @Test
    fun selectIdsSelectsExactlyTheElementsThatExist() {
        val controller = newController()
        listOf("a", "b").forEach { controller.onIntent(Intent.AddElement(square(it))) }
        controller.selectIds(setOf("b", "missing"))
        assertEquals(setOf("b"), controller.state.value.selectedIds)
    }

    @Test
    fun importingADrawingKeepsTheHostsSettings() {
        val controller = newController()
        controller.onIntent(Intent.SetSelectInsideHollowShapes(true))
        controller.onIntent(Intent.SetStrokeColor(Color.Blue))
        val json = io.ak1.drawbox.domain.model.DrawingSerializer.serialize(
            io.ak1.drawbox.domain.model.PayLoad(bgColor = Color.White, elements = listOf(square("a"))),
        )
        controller.importPath(json)
        val state = controller.state.value
        assertEquals(listOf("a"), state.elements.map { it.id })
        assertTrue(state.selectInsideHollowShapes)
        assertEquals(Color.Blue, state.strokeColor)
    }

    @Test
    fun aDrawingChangedElsewhereKeepsThisBoardsCameraToolAndSelection() {
        val controller = newController()
        listOf("a", "b").forEach { controller.onIntent(Intent.AddElement(square(it))) }
        controller.onIntent(Intent.SetMode(io.ak1.drawbox.domain.model.Mode.SELECT))
        controller.panBy(androidx.compose.ui.geometry.Offset(-420f, 260f))
        controller.zoomBy(2f, androidx.compose.ui.geometry.Offset.Zero)
        controller.selectIds(setOf("a", "b"))
        val camera = controller.state.value.viewport
        val json = io.ak1.drawbox.domain.model.DrawingSerializer.serialize(
            io.ak1.drawbox.domain.model.PayLoad(bgColor = Color.White, elements = listOf(square("a"), square("c"))),
        )
        controller.importExternal(json)
        val state = controller.state.value
        assertEquals(listOf("a", "c"), state.elements.map { it.id })
        assertEquals(camera, state.viewport)
        assertEquals(io.ak1.drawbox.domain.model.Mode.SELECT, state.mode)
        assertEquals(setOf("a"), state.selectedIds)
    }

    @Test
    fun saveBitmapStillCapturesRightAfterAnIntent() {
        val controller = newController()
        var captured = 0
        controller.state.value.invokeBitmap = { captured++ }
        controller.onIntent(Intent.SetStrokeWidth(3f))
        controller.saveBitmap()
        assertEquals(1, captured)
    }

    @Test
    fun mergedUndoStepsUndoTogether() {
        val controller = newController()
        controller.onIntent(Intent.AddElement(square("a")))
        controller.onIntent(Intent.AddElement(square("b")))
        controller.onIntent(Intent.AddElement(square("c")))
        controller.onIntent(Intent.MergeUndoSteps(2))
        controller.onIntent(Intent.Undo)
        assertEquals(listOf("a"), controller.state.value.elements.map { it.id })
    }

    @Test
    fun anAdditiveTapTogglesAnElementInAndOutOfTheSelection() {
        val controller = newController()
        controller.onIntent(Intent.AddElement(square("a")))
        controller.onIntent(
            Intent.AddElement(
                square("b").copy(points = listOf(androidx.compose.ui.geometry.Offset(50f, 50f), androidx.compose.ui.geometry.Offset(60f, 60f))),
            ),
        )
        val a = square("a").points.first()
        controller.selectIds(setOf("b"))
        controller.onIntent(Intent.SelectAt(a + androidx.compose.ui.geometry.Offset(1f, 1f), 4f, additive = true))
        assertTrue("a" in controller.state.value.selectedIds && "b" in controller.state.value.selectedIds)
        controller.onIntent(Intent.SelectAt(a + androidx.compose.ui.geometry.Offset(1f, 1f), 4f, additive = true))
        assertEquals(setOf("b"), controller.state.value.selectedIds)
    }
}
