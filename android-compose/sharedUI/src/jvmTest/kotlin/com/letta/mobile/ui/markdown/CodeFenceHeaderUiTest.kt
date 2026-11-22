@file:OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)

package com.letta.mobile.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A code fence reads under a header with its language and a Copy that puts the code alone (no fence)
 * on the clipboard and then says "Copied"; a fence with no language keeps the Copy. Renders a reply
 * with a fence in both themes at the phone, tablet and desktop widths to build/code-fence-snapshots.
 */
class CodeFenceHeaderUiTest {
    private val reply = "Here is the fix:\n\n```kotlin\nval answer = 42\nprintln(answer)\n```\n\nRun it again."

    /** A clipboard that keeps what was copied. */
    private class RecordingClipboard : Clipboard {
        var copied: String? = null

        override suspend fun getClipEntry(): ClipEntry? = null

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            val clip = clipEntry?.nativeClipEntry as? Transferable
            copied = clip?.getTransferData(DataFlavor.stringFlavor) as? String
        }

        override val nativeClipboard get() = error("not used")
    }

    private fun ComposeUiTest.show(text: String, clipboard: Clipboard = RecordingClipboard()) = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                Box(Modifier.width(600.dp)) { SharedMarkdownText(text) }
            }
        }
    }

    /** The markdown is parsed off the main thread: wait for the fence's header to compose before asserting. */
    private fun ComposeUiTest.awaitHeader() =
        waitUntil(timeoutMillis = WAIT_MILLIS) { onAllNodesWithTag(CodeFenceTags.HEADER).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aFenceShowsItsLanguageAndCopiesOnlyItsCode() = runComposeUiTest {
        val clipboard = RecordingClipboard()
        show(reply, clipboard)
        awaitHeader()

        onNodeWithTag(CodeFenceTags.LANGUAGE, useUnmergedTree = true).assertTextEquals("kotlin")
        onNodeWithTag(CodeFenceTags.COPY).assertContentDescriptionEquals("Copy code")
        onNodeWithTag(CodeFenceTags.COPY).performClick()
        waitUntil(timeoutMillis = WAIT_MILLIS) { clipboard.copied != null }
        assertEquals("val answer = 42\nprintln(answer)", clipboard.copied)
        onNodeWithTag(CodeFenceTags.COPY).assertContentDescriptionEquals("Copied")
    }

    @Test
    fun aFenceWithNoLanguageStillCopies() = runComposeUiTest {
        show("```\nplain\n```")
        awaitHeader()

        onNodeWithTag(CodeFenceTags.LANGUAGE, useUnmergedTree = true).assertTextEquals("")
        assertEquals(1, onAllNodesWithTag(CodeFenceTags.COPY).fetchSemanticsNodes().size)
    }

    @Test
    fun aMermaidFenceWithARendererHasNoHeader() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMermaidDiagramRenderer provides MermaidDiagramRenderer { _, _ -> }) {
                    SharedMarkdownText("```mermaid\nflowchart LR\nA --> B\n```")
                }
            }
        }
        assertEquals(0, onAllNodesWithTag(CodeFenceTags.HEADER).fetchSemanticsNodes().size)
    }

    private fun snapshot(width: Dp, dark: Boolean) = runDesktopComposeUiTest(width = WINDOW_WIDTH, height = WINDOW_HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                Box(Modifier.width(width).background(MaterialTheme.colorScheme.background).padding(16.dp).testTag(FRAME)) {
                    SharedMarkdownText(reply)
                }
            }
        }
        awaitHeader()
        waitForIdle()
        val out = File("build/code-fence-snapshots").apply { mkdirs() }
            .resolve("fence-${if (dark) "dark" else "light"}-${width.value.toInt()}dp.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun aFenceAtEveryWidth() = WIDTHS.forEach { width -> listOf(false, true).forEach { snapshot(width, it) } }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val FRAME = "code-fence-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 500
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
