@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ComposerImageAttacher
import com.letta.mobile.ui.chat.surface.composer.ComposerImageSource
import com.letta.mobile.ui.chat.surface.composer.LocalComposerImageAttacher
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * letta-mobile-bglj6.1 (R11): a picked image still attaches when the person docks or expands the
 * page while it is encoding. The page, not one composer panel, owns the encode.
 */
class ComposerImageAttachUiTest {
    /** A small opaque PNG the JVM's ImageIO decodes. */
    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB), "png", out)
    }.toByteArray()

    private val ready = ChatUiState(conversationState = ConversationState.Ready("conv-1"), isLoadingMessages = false)

    @Test
    fun switchingModeMidEncodeStillAttaches() = runComposeUiTest {
        val port = ChatSurfaceUiTest.TestPort(
            ready,
            ChatComposerUiState(canSend = true, attachmentLimits = AttachmentLimits(maxLongestEdgePx = 64)),
        )
        var presentation by mutableStateOf(ChatSurfacePresentation.ChatFirst)
        var attacher: ComposerImageAttacher? = null
        var fullScreenDisposed = false
        // The full-screen timeline overlay is composed inside the page, beside the full-screen
        // composer: it reads the page's attacher and notices when the full-screen layer goes away.
        val platform = ChatSurfacePlatform(
            timelineOverlay = {
                attacher = LocalComposerImageAttacher.current
                DisposableEffect(Unit) { onDispose { fullScreenDisposed = true } }
            },
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 480.dp, height = 720.dp)) {
                    ChatSurface(
                        port = port,
                        presentation = presentation,
                        onIntent = {},
                        host = ChatSurfaceHost(),
                        platform = platform,
                        canvas = { _ -> Text("CANVAS") },
                    )
                }
            }
        }
        val reading = CompletableDeferred<Unit>()
        var job: Job? = null
        var started = false
        runOnIdle {
            job = assertNotNull(attacher).attach(listOf<ComposerImageSource>({ started = true; reading.await(); png }))
        }
        assertNotNull(job)

        // Dock while the image is still being read: the full-screen composer panel leaves.
        runOnIdle { presentation = ChatSurfacePresentation.CanvasFirst }
        mainClock.advanceTimeBy(MORPH_OUTLAST_MS)
        waitForIdle()
        assertTrue(fullScreenDisposed, "the full-screen layer should be gone before the encode finishes")
        assertFalse(job!!.isCancelled)

        reading.complete(Unit)
        val attached = runCatching {
            waitUntil(timeoutMillis = 10_000) { port.recording.count("attachImage") == 1 }
        }
        assertTrue(
            attached.isSuccess,
            "no image attached: calls=${port.recording.calls} started=$started job=${job}",
        )
        assertFalse(job!!.isCancelled)
    }

    private companion object {
        const val MORPH_OUTLAST_MS = 2_000L
    }
}
