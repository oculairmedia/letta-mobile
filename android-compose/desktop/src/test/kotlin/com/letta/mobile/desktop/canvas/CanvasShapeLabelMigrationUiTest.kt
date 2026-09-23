@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasTextStyle
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shape text used to be a separate label document laid over the shape. A canvas saved that way
 * opens with each label folded into its shape's own text, and the label gone.
 */
class CanvasShapeLabelMigrationUiTest {

    private val scene = """{"bgColor":"#ffffffff","elements":[
        {"id":"rect-1","type":"Shape","zIndex":1,"points":["40.0,40.0","240.0,160.0"],
         "strokeColor":"#000000ff","strokeWidth":4.0,"shapeType":"RECTANGLE","modifiedAt":1},
        {"id":"rect-2","type":"Shape","zIndex":2,"points":["300.0,40.0","500.0,160.0"],
         "strokeColor":"#000000ff","strokeWidth":4.0,"shapeType":"RECTANGLE","modifiedAt":1}]}"""

    private fun label(text: String) =
        """{"version":2,"blocks":[{"id":"b","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"$text","spans":[]}}]}"""

    @Test
    fun legacyLabelsFoldIntoTheirShapes() = runComposeUiTest {
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = scene)).also { s ->
                s.setDocument(
                    "label-rect-1", label("fdafd"),
                    frame = CanvasDocumentFrame(50f, 50f, 180f, 100f), color = "#00000000",
                    style = CanvasTextStyle(textColor = "#e5484d", align = "start"),
                )
                s.setLabelOwner("label-rect-1", "rect-1")
                // A note that happens to be called label-something, owned by nothing, stays a note.
                s.setDocument("label-report", label("keep me"), color = "#fef3c7")
            }
        }
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller) }

        fun shape(id: String) = controller.state.value.elements.first { it.id == id } as Element.Shape
        waitUntil(timeoutMillis = 10_000) { controller.state.value.elements.size == 2 && shape("rect-1").text == "fdafd" }
        assertEquals(Color(0xFFE5484D), shape("rect-1").textColor, "the label's colour carries over")
        assertEquals(TextAlignment.LEFT, shape("rect-1").textAlignment, "and its alignment")
        assertEquals("", shape("rect-2").text)

        waitUntil(timeoutMillis = 10_000) { session.documents().none { it.id == "label-rect-1" } }
        assertTrue(session.labelOwners().isEmpty(), "the ownership record goes with the label")
        assertEquals(listOf("label-report"), session.documents().map { it.id }, "an unowned note is not a label")

        // The text is now part of the drawing that is saved and synced.
        waitUntil(timeoutMillis = 10_000) { session.sceneJsonOrEmpty().contains("fdafd") && session.sceneJsonOrEmpty().contains("\"text\"") }
    }
}
