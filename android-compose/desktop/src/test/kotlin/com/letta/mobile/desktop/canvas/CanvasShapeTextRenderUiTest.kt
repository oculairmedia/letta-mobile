@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test

/** A shape's text is drawn by the canvas itself, centred in the shape, in its own colour. */
class CanvasShapeTextRenderUiTest {
    @Test
    fun shortTextSitsInTheMiddleOfItsShape() = runComposeUiTest {
        val controller = DrawBoxController(Reducer(UseCase()))
        val json = """{"bgColor":"#ffffffff","elements":[
          {"id":"a","type":"Shape","zIndex":1,"points":["120.0,200.0","420.0,360.0"],"strokeColor":"#2563ebff","strokeWidth":3.0,"shapeType":"RECTANGLE","text":"Plan the week and ship the canvas","fontSize":22.0},
          {"id":"b","type":"Shape","zIndex":2,"points":["470.0,280.0","690.0,280.0"],"strokeColor":"#16a34aff","strokeWidth":3.0,"shapeType":"CIRCLE","text":"Ideas","fontSize":24.0,"textColor":"#e5484dff"},
          {"id":"c","type":"Shape","zIndex":3,"points":["740.0,180.0","960.0,380.0"],"strokeColor":"#f59e0bff","strokeWidth":3.0,"shapeType":"TRIANGLE","text":"Go","fontSize":24.0}]}"""
        setContent { MaterialTheme(colorScheme = lightColorScheme()) { Box(Modifier.size(1000.dp, 520.dp)) { CanvasWorkspace(controller = controller, initialJson = json) } } }
        mainClock.advanceTimeBy(1000); waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        // "Ideas" is the only red on the board: its pixels centre on the circle's centre (580, 280).
        val red = buildList {
            for (x in 460 until 700) for (y in 170 until 390) {
                if (isStrongRed(image.getRGB(x, y))) add(x to y)
            }
        }
        kotlin.test.assertTrue(red.size > 20, "the circle's text should be drawn, found ${red.size} red pixels")
        val cx = red.map { it.first }.average()
        val cy = red.map { it.second }.average()
        kotlin.test.assertTrue(kotlin.math.abs(cx - 580) < 8, "text should be centred across the circle, centre x is $cx")
        kotlin.test.assertTrue(kotlin.math.abs(cy - 280) < 8, "text should be centred down the circle, centre y is $cy")
    }
}

private fun isStrongRed(rgb: Int): Boolean {
    val r = rgb shr 16 and 0xff
    val g = rgb shr 8 and 0xff
    val b = rgb and 0xff
    return r > 180 && maxOf(g, b) < 120
}
