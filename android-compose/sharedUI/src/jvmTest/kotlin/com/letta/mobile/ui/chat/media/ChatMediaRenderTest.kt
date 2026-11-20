@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.media.MediaPlaybackCoordinator
import com.letta.mobile.data.media.MediaPlayerFactory
import com.letta.mobile.data.media.UiMediaAttachment
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatMediaAttachments
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

/**
 * The media cards at the review widths (360, 700, 760, 1200 dp) in Dark and Light, written to
 * build/chat-media-render/ for review: an audio card playing (0:42 of 3:20), a video card's poster and
 * a linked file, beside an image so the shared shape and spacing show. Demo data only.
 */
class ChatMediaRenderTest {
    private val attachments = persistentListOf(
        UiMediaAttachment(base64 = "", mediaType = "audio/mpeg", url = "https://media.example/standup-summary.mp3"),
        UiMediaAttachment(base64 = "", mediaType = "video/mp4", url = "https://media.example/onboarding-demo.mp4"),
        UiMediaAttachment(base64 = "", mediaType = "application/pdf", url = "https://media.example/release-notes.pdf"),
    )

    @Test
    fun theCardsRenderAtEveryReviewWidthInBothThemes() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) render(width, dark)
        }
    }

    private fun render(width: Int, dark: Boolean) = runDesktopComposeUiTest(width = width, height = HEIGHT) {
        val players = mutableListOf<ScriptedMediaPlayer>()
        val factory = MediaPlayerFactory { ScriptedMediaPlayer().also(players::add) }
        val touch = width < LettaDimens.Pane.wideBreakpoint.value
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                    CompositionLocalProvider(
                        LocalReducedMotion provides true,
                        LocalMediaPlayerFactory provides factory,
                        LocalMediaPlaybackCoordinator provides MediaPlaybackCoordinator(),
                    ) {
                        Surface(Modifier.fillMaxSize()) {
                            // The timeline caps its column at the content width; the cards are drawn in that column.
                            Box(Modifier.widthIn(max = LettaDimens.Pane.contentMaxWidth).fillMaxWidth().padding(LettaDimens.Space.lg)) {
                                ChatMediaAttachments(attachments, onImageTap = { _, _ -> }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag(MediaTestTags.PLAY_PAUSE).onFirst().performClick()
        waitForIdle()
        players.firstOrNull { it.state.value.isPlaying }?.advanceTo(DEMO_POSITION_MS)
        waitForIdle()
        val file = File("build/chat-media-render").apply { mkdirs() }.resolve("media-$width-${if (dark) "dark" else "light"}.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 900
        const val DEMO_POSITION_MS = 42_000L
    }
}
