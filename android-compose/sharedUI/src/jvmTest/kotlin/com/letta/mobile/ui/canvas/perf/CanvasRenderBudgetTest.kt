package com.letta.mobile.ui.canvas.perf

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.DrawBoxRenderStats
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.translate
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a big board costs per frame, counted rather than timed so it holds on any machine
 * (letta-mobile-24zur.1). On 5,000 elements a 1280 x 800 view draws only what is near it, a pan
 * draws nothing, and moving one element draws only what shares its part of the board.
 */
class CanvasRenderBudgetTest {
    @Test
    fun aLargeBoardDrawsOnlyWhatIsNearTheViewAndAPanDrawsNothing() {
        val payload = CanvasLargeSceneFixtures.scene(5_000, withImages = false)
        val controller = DrawBoxController(Reducer(UseCase()), State())
        controller.importPath(payload)
        val scene = ImageComposeScene(width = 1280, height = 800, density = Density(1f)) {
            val s by controller.state.collectAsState()
            DrawBox(state = s, onIntent = controller::onIntent, showGrid = true)
        }
        var clock = 0L
        fun frame() {
            clock += 16_000_000L
            scene.render(clock)
        }
        try {
            DrawBoxRenderStats.reset()
            repeat(3) { frame() }
            // The view and half a view around it: 2560 x 1600 world units, about 40 of the 320-unit
            // cells. Everything else (about 4,900 elements) is never drawn.
            assertTrue(DrawBoxRenderStats.elementsDrawn < MAX_FIRST, "first frames drew ${DrawBoxRenderStats.elementsDrawn}")

            DrawBoxRenderStats.reset()
            repeat(30) {
                controller.onIntent(Intent.PanBy(Offset(-7f, -3f)))
                frame()
            }
            assertEquals(0L, DrawBoxRenderStats.layerRecords, "a pan within the margin replays the recording")
            assertEquals(0L, DrawBoxRenderStats.elementsDrawn)

            DrawBoxRenderStats.reset()
            var moved: Element = payload.elements.first { it is Element.Shape }
            repeat(10) {
                moved = moved.translate(Offset(2f, 1f))
                controller.onIntent(Intent.UpdateElement(moved))
                frame()
            }
            assertTrue(DrawBoxRenderStats.elementsDrawn < 10 * MAX_FIRST, "moving one element drew ${DrawBoxRenderStats.elementsDrawn}")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val MAX_FIRST = 400L
    }
}
