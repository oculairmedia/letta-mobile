@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import kotlin.test.Test

/** Images reach the board from the menu, the clipboard and (on desktop) a drop. */
class CanvasImageInputUiTest {

    @Test
    fun theBoardMenuOffersImages() = runComposeUiTest {
        setContent { CanvasWorkspace() }
        onNodeWithContentDescription("Canvas board").performMouseInput { rightClick(Offset(400f, 300f)) }
        onNodeWithText("Image").assertExists()
        // Pasting is Ctrl/Cmd+V, not a menu entry.
        onNodeWithText("Paste image").assertDoesNotExist()
    }

    @Test
    fun ctrlVPastesAnImageFromTheClipboard() {
        // The system clipboard needs a display; headless CI has none (HeadlessException).
        org.junit.Assume.assumeFalse("No system clipboard when headless", java.awt.GraphicsEnvironment.isHeadless())
        pastesAnImageFromTheClipboard()
    }

    private fun pastesAnImageFromTheClipboard() = runComposeUiTest {
        val image = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
                override fun getTransferData(flavor: DataFlavor): Any = image
            },
            null,
        )
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(controller = controller) }
        onNodeWithContentDescription("Canvas workspace").requestFocus()
        onNodeWithContentDescription("Canvas workspace").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        waitUntil(timeoutMillis = 5000) { controller.state.value.elements.any { it is Element.Image } }
    }
}
