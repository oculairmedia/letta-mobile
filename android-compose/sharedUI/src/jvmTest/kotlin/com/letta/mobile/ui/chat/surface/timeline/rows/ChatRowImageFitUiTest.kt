@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Large decoded attachments must retain all their content in every real timeline row. */
class ChatRowImageFitUiTest {
    private data class Environment(val width: Int, val host: LettaHost)

    private enum class RowKind { Message, ToolMessage, Prompt, InlineReadTool, FoldedReadRun, OpenedReadRun }

    private val phone = Environment(360, LettaHost.Touch)
    private val desktop = Environment(900, LettaHost.Pointer)
    private val square = imageAttachment(1024, 1024)
    private val tall = imageAttachment(1024, 2048)

    @Test
    fun phoneSquareFillsItsColumnAndOpensTheViewer() = checkSquare(phone)

    @Test
    fun desktopSquareFillsItsColumnAndOpensTheViewer() = checkSquare(desktop)

    @Test
    fun phoneTallImageFitsWholeInsideTheCap() = checkTall(phone)

    @Test
    fun desktopTallImageFitsWholeInsideTheCap() = checkTall(desktop)

    @Test
    fun phoneGridFitsWholeImagesInUniformCells() = checkGrid(phone)

    @Test
    fun desktopGridFitsWholeImagesInUniformCells() = checkGrid(desktop)

    @Test
    fun storedPointersKeepThePlaceholderHeight() = checkPlaceholder(UiImageAttachment("", "image/png", storedByteSize = 100))

    @Test
    fun failedDecodesKeepThePlaceholderHeight() = checkPlaceholder(UiImageAttachment("invalid", "image/png"))

    @Test
    fun resolutionAndEncodingDoNotChangeGeometryOnTheSameSurface() {
        val smaller = imageAttachment(512, 512, "jpeg")
        for (environment in listOf(phone, desktop)) {
            val bounds = mutableListOf<Rect>()
            for (attachment in listOf(square, smaller)) {
                runDesktopComposeUiTest(width = 1100, height = 1600) {
                    show(environment, RowKind.Message, listOf(attachment))
                    awaitImages(1)
                    bounds += imageBounds()
                }
            }
            assertEquals(bounds[0], bounds[1], "1024 PNG and 512 JPEG share their row geometry")
        }
    }

    private fun checkSquare(environment: Environment) {
        for (kind in RowKind.entries) {
            runDesktopComposeUiTest(width = 1100, height = 1600) {
                show(environment, kind, listOf(square))
                awaitImages(1)
                val bounds = imageBounds()
                val column = onNodeWithTag(ChatRowTestTags.IMAGE_GRID, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertNear(column.width, bounds.width, "$kind uses its available column")
                assertNear(bounds.width, bounds.height, "$kind square aspect")
                if (kind == RowKind.Message) {
                    assertNear(with(density) { environment.width.dp.toPx() }, bounds.width, "row width")
                }
                assertPaintedAspect(0, 1f)
                onNode(
                    hasClickAction() and hasAnyAncestor(hasTestTag(ChatRowTestTags.IMAGE_GRID)), useUnmergedTree = true,
                ).performClick()
                onNodeWithContentDescription("Close image viewer").assertExists().performClick()
                onNodeWithContentDescription("Close image viewer").assertDoesNotExist()
            }
        }
    }

    private fun checkTall(environment: Environment) {
        for (kind in RowKind.entries) {
            runDesktopComposeUiTest(width = 1100, height = 1600) {
                show(environment, kind, listOf(tall))
                awaitImages(1)
                val bounds = imageBounds()
                val column = onNodeWithTag(ChatRowTestTags.IMAGE_GRID, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertNear(column.width, bounds.width, "$kind uses its available column")
                assertNear(with(density) { PORTRAIT_CAP_DP.dp.toPx() }, bounds.height, "$kind portrait cap")
                val painted = assertPaintedAspect(0, 0.5f)
                assertNear(bounds.height * PAINTED_FRACTION, painted.height, "$kind keeps the full source height")
                assertTrue(painted.width < bounds.width, "$kind has surface-colour letterboxing")
            }
        }
    }

    private fun checkGrid(environment: Environment) {
        for (kind in RowKind.entries) {
            runDesktopComposeUiTest(width = 1100, height = 1600) {
                show(environment, kind, listOf(square, tall))
                awaitImages(2)
                val images = onAllNodesWithContentDescription(IMAGE_DESCRIPTION, useUnmergedTree = true).fetchSemanticsNodes()
                if (kind == RowKind.Prompt && environment.host == LettaHost.Pointer) {
                    images.forEach {
                        assertNear(with(density) { ChatRowDimens.thumbnailHeight.toPx() }, it.boundsInRoot.height, "strip height")
                        assertNear(with(density) { ChatRowDimens.thumbnailWidth.toPx() }, it.boundsInRoot.width, "strip width")
                    }
                    assertPaintedAspect(0, 1f)
                    assertPaintedAspect(1, 0.5f)
                    return@runDesktopComposeUiTest
                }
                val column = onNodeWithTag(ChatRowTestTags.IMAGE_GRID, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val height = if (environment.host == LettaHost.Touch) {
                    ChatRowDimens.promptGridCellHeight
                } else {
                    ChatRowDimens.imageGridHeight
                }
                val gap = if (environment.host == LettaHost.Touch) LettaDimens.Space.xs else LettaDimens.Space.sm
                images.forEach {
                    assertNear(with(density) { height.toPx() }, it.boundsInRoot.height, "$kind uniform height")
                    assertNear((column.width - with(density) { gap.toPx() }) / 2, it.boundsInRoot.width, "$kind uniform width")
                }
                assertPaintedAspect(0, 1f)
                assertPaintedAspect(1, 0.5f)
            }
        }
    }

    private fun checkPlaceholder(attachment: UiImageAttachment) {
        for (environment in listOf(phone, desktop)) {
            runDesktopComposeUiTest(width = 1100, height = 1600) {
                show(environment, RowKind.Message, listOf(attachment))
                val grid = onNodeWithTag(ChatRowTestTags.IMAGE_GRID, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertNear(with(density) { ChatRowDimens.imageSingleHeight.toPx() }, grid.height, "placeholder height")
                assertNear(with(density) { environment.width.dp.toPx() }, grid.width, "placeholder width")
                if (attachment.storedByteSize != null) onNodeWithText("Image not loaded").assertExists()
            }
        }
    }

    private fun ComposeUiTest.show(environment: Environment, kind: RowKind, attachments: List<UiImageAttachment>) {
        var viewer by mutableStateOf<Pair<List<UiImageAttachment>, Int>?>(null)
        val callbacks = ChatRowCallbacks(
            actions = RecordingChatActions(), host = ChatSurfaceHost(),
            onImageTap = { images, index -> viewer = images to index },
        )
        setContent {
            CompositionLocalProvider(LocalLettaHost provides environment.host, LocalReducedMotion provides true) {
                MaterialTheme(colorScheme = lightColorScheme(surfaceVariant = Color.White)) {
                    Box(Modifier.width(environment.width.dp)) {
                        val opened = if (kind == RowKind.OpenedReadRun) setOf(RUN_ID) else emptySet()
                        RenderRow(row(kind, attachments), rowContext(itemState = renderState(collapsedRunIds = opened)), callbacks)
                    }
                    viewer?.let { (images, index) ->
                        assertEquals(attachments, images, "viewer receives this row's images")
                        assertEquals(0, index, "viewer opens the tapped square")
                        ChatImageViewer(images, index) { viewer = null }
                    }
                }
            }
        }
    }

    private fun row(kind: RowKind, attachments: List<UiImageAttachment>): ChatRenderItem {
        val role = when (kind) {
            RowKind.Prompt -> "user"
            RowKind.ToolMessage -> "tool"
            else -> "assistant"
        }
        val message = message("image-message", role, "").copy(attachments = attachments)
        if (kind !in setOf(RowKind.InlineReadTool, RowKind.FoldedReadRun, RowKind.OpenedReadRun)) return single(message)
        val read = message.copy(
            runId = RUN_ID, attachments = emptyList(),
            toolCalls = listOf(UiToolCall(
                name = "Read", arguments = """{"file_path":"/tmp/large.png"}""", result = "[Image: large.png]",
                status = "success", toolCallId = "read-image", generatedImageAttachments = attachments,
            )),
        )
        if (kind == RowKind.InlineReadTool) return single(read.copy(runId = null))
        return ChatRenderItem.RunBlock(RUN_ID, listOf(read to GroupPosition.None))
    }

    private fun ComposeUiTest.awaitImages(count: Int) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription(IMAGE_DESCRIPTION, useUnmergedTree = true).fetchSemanticsNodes().size == count
        }
    }

    private fun ComposeUiTest.imageBounds(): Rect =
        onAllNodesWithContentDescription(IMAGE_DESCRIPTION, useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot

    /** Pixel bounds and both end bands distinguish Fit from a cropped image in an otherwise correct cell. */
    private fun ComposeUiTest.assertPaintedAspect(index: Int, aspect: Float): Rect {
        val image = onAllNodesWithContentDescription(IMAGE_DESCRIPTION, useUnmergedTree = true)[index]
            .captureToImage().toAwtImage()
        val painted = paintedBounds(image)
        assertTrue(abs(painted.width / painted.height / aspect - 1) <= 0.01f, "painted aspect: $painted, expected $aspect")
        val x = painted.center.x.toInt()
        assertEquals(TOP_COLOR, image.getRGB(x, (painted.top + painted.height / 10).toInt()), "source top remains visible")
        assertEquals(BOTTOM_COLOR, image.getRGB(x, (painted.bottom - painted.height / 10).toInt()), "source bottom remains visible")
        return painted
    }

    private fun paintedBounds(image: BufferedImage): Rect {
        // Recover subpixel edges from coverage against the known white surface. Integer bounds
        // alone can be off by >1% for a 15px-wide marker in the pointer prompt's compact strip.
        val (left, right) = paintedSpan(image.width) { image.getRGB(it, image.height / 2) }
        val (top, bottom) = paintedSpan(image.height) { image.getRGB(image.width / 2, it) }
        return Rect(left, top, right, bottom)
    }

    private fun paintedSpan(length: Int, pixel: (Int) -> Int): Pair<Float, Float> {
        val first = (0 until length).firstOrNull { coverage(pixel(it)) > 0.1f }
        val last = (0 until length).lastOrNull { coverage(pixel(it)) > 0.1f }
        assertNotNull(first, "decoded pixels must be painted")
        assertNotNull(last, "decoded pixels must be painted")
        return first + 1 - coverage(pixel(first)) to last + coverage(pixel(last))
    }

    private fun coverage(argb: Int): Float {
        val red = argb shr 16 and 0xFF
        val green = argb shr 8 and 0xFF
        val blue = argb and 0xFF
        return (maxOf(red, green, blue) - minOf(red, green, blue)) / 255f
    }

    private fun assertNear(expected: Float, actual: Float, reason: String) {
        assertTrue(abs(expected - actual) <= 1f, "$reason: expected $expected px, actual $actual px")
    }

    private fun imageAttachment(width: Int, height: Int, format: String = "png"): UiImageAttachment {
        val type = if (format == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        // Inset colour markers avoid the surface's rounded corners and 1dp pointer border.
        // Their rectangle has exactly the source aspect and a known fraction of its height.
        val insetX = width * MARKER_INSET / SOURCE_WIDTH
        val insetY = height * MARKER_INSET / SOURCE_WIDTH
        for (y in insetY until height - insetY) {
            val color = when {
                y < height / 4 -> TOP_COLOR
                y >= height * 3 / 4 -> BOTTOM_COLOR
                else -> BODY_COLOR
            }
            for (x in insetX until width - insetX) image.setRGB(x, y, color)
        }
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, format, output))
            output.toByteArray()
        }
        val attachment = UiImageAttachment(Base64.encode(bytes), "image/$format")
        val decoded = assertNotNull(com.letta.mobile.ui.chat.surface.ChatImageBitmaps.decode(attachment.base64))
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        return attachment
    }

    private companion object {
        const val IMAGE_DESCRIPTION = "Attached image"
        const val RUN_ID = "large-image-run"
        // Independent acceptance value: reverting the production cap must not change the oracle.
        const val PORTRAIT_CAP_DP = 400
        const val SOURCE_WIDTH = 1024
        const val MARKER_INSET = 128
        const val PAINTED_FRACTION = 0.75f
        const val TOP_COLOR = -65536
        const val BODY_COLOR = -16776961
        const val BOTTOM_COLOR = -16711936
    }
}
