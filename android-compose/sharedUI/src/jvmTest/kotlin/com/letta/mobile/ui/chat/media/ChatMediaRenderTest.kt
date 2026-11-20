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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.media.EngineMediaPlayer
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlaybackCoordinator
import com.letta.mobile.data.media.MediaPlayerFactory
import com.letta.mobile.data.media.UiMediaAttachment
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatMediaAttachments
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.SharedMaterialTheme
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
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

    /** The same cards with the video an inline test pattern playing on the real desktop engine. */
    private val engineAttachments = persistentListOf(
        attachments[0],
        UiMediaAttachment(base64 = testPatternBase64(), mediaType = "video/mp4"),
        attachments[2],
    )

    @Test
    fun theCardsRenderAtEveryReviewWidthInBothThemes() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) render(width, dark)
        }
    }

    @Test
    fun theDesktopEngineDrawsTheVideoFrameAtEveryReviewWidthInBothThemes() {
        val engineLoads = platformMediaPlayer(MediaKind.Video, CoroutineScope(Dispatchers.Unconfined)) == null
        assumeTrue("the desktop engine's native bridge does not load here", engineLoads)
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) render(width, dark, engineVideo = true)
        }
    }

    private fun render(width: Int, dark: Boolean, engineVideo: Boolean = false) = runDesktopComposeUiTest(width = width, height = HEIGHT) {
        val players = mutableListOf<ScriptedMediaPlayer>()
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val factory = MediaPlayerFactory { kind ->
            if (engineVideo && kind == MediaKind.Video) {
                EngineMediaPlayer(ComposeMediaEngine(createVideoPlayerState()), engineScope)
            } else {
                ScriptedMediaPlayer().also(players::add)
            }
        }
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
                                ChatMediaAttachments(
                                    if (engineVideo) engineAttachments else attachments,
                                    onImageTap = { _, _ -> },
                                    modifier = Modifier.fillMaxWidth(),
                                )
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
        if (engineVideo) playTheVideoUntilAFrameShows()
        waitForIdle()
        val name = "media-${if (engineVideo) "engine-" else ""}$width-${if (dark) "dark" else "light"}.png"
        val file = File("build/chat-media-render").apply { mkdirs() }.resolve(name)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        engineScope.cancel()
    }

    /** Starts the engine-backed video (the second transport) and waits, in real time, for a decoded frame. */
    private fun ComposeUiTest.playTheVideoUntilAFrameShows() {
        waitUntil(timeoutMillis = ENGINE_TIMEOUT_MS) { onAllNodesWithTag(MediaTestTags.VIDEO_SURFACE).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(MediaTestTags.PLAY_PAUSE)[1].performClick()
        waitUntil(timeoutMillis = ENGINE_TIMEOUT_MS) {
            Thread.sleep(FRAME_POLL_MS)
            surfaceShowsAFrame()
        }
    }

    private fun ComposeUiTest.surfaceShowsAFrame(): Boolean {
        val surface = onAllNodesWithTag(MediaTestTags.VIDEO_SURFACE)
        if (surface.fetchSemanticsNodes().isEmpty()) return false
        val image = surface.onFirst().captureToImage().toAwtImage()
        val colours = HashSet<Int>()
        for (x in 0 until image.width step FRAME_SAMPLE_STEP) {
            for (y in 0 until image.height step FRAME_SAMPLE_STEP) colours += image.getRGB(x, y)
        }
        return colours.size > POSTER_COLOURS
    }

    private fun testPatternBase64(): String =
        Base64.getEncoder().encodeToString(checkNotNull(javaClass.getResourceAsStream("/media/test-pattern.mp4")).use { it.readBytes() })

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 900
        const val DEMO_POSITION_MS = 42_000L
        const val ENGINE_TIMEOUT_MS = 15_000L
        const val FRAME_POLL_MS = 100L
        const val FRAME_SAMPLE_STEP = 4

        /** A poster is a flat fill and a glyph; the test pattern has far more colours. */
        const val POSTER_COLOURS = 64
    }
}
