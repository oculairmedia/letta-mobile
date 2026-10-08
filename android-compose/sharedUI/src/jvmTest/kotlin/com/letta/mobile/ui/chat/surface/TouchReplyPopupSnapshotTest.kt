@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.devfixtures.FixtureChatSessionPort
import com.letta.mobile.ui.devfixtures.PhoneFixtures
import com.letta.mobile.ui.devfixtures.PhoneScene
import com.letta.mobile.ui.devfixtures.PhoneSceneSurface
import com.letta.mobile.ui.devfixtures.PhoneScenes
import com.letta.mobile.ui.markdown.LocalSharedRichMarkdownRenderer
import com.letta.mobile.ui.markdown.SharedRichMarkdownRenderer
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

/**
 * The Touch canvas's reply popup at phone size (412 x 915 dp, drawn at 2x), written to
 * build/chat-surface-snapshots/touch-reply-popup-*.png for a reviewer. The "android" shots stand
 * in for Android's rich markdown renderer, which paints its own (timeline-sized) type: the popup
 * must keep its compact look under it too. Asserts only that each rendered.
 */
class TouchReplyPopupSnapshotTest {
    private fun snapshot(name: String, scene: PhoneScene, androidRenderer: Boolean = false) = runDesktopComposeUiTest(
        width = PhoneFixtures.PHONE_WIDTH_DP * SCALE,
        height = PhoneFixtures.PHONE_HEIGHT_DP * SCALE,
    ) {
        mainClock.autoAdvance = false
        val port = FixtureChatSessionPort(scene.state, scene.composer)
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(SCALE.toFloat(), 1f),
                LocalSharedRichMarkdownRenderer provides if (androidRenderer) AndroidLikeRenderer else null,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    PhoneSceneSurface(scene = scene, port = port, presentation = scene.presentation, onIntent = {})
                }
            }
        }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("touch-reply-popup-$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun shortReply() = snapshot("short", shortScene)

    @Test
    fun shortReplyUnderAndroidsRenderer() = snapshot("short-android", shortScene, androidRenderer = true)

    @Test
    fun longReply() = snapshot("long", longScene)

    @Test
    fun longReplyUnderAndroidsRenderer() = snapshot("long-android", longScene, androidRenderer = true)

    @Test
    fun headTopRight() = snapshot("head-top-right", PhoneScenes.canvasHeadRightTop)

    @Test
    fun headLeft() = snapshot("head-left", PhoneScenes.canvasHeadLeft)

    private companion object {
        const val SCALE = 2
        const val SETTLE_FRAMES = 90
        const val FRAME_MILLIS = 16L

        val shortScene: PhoneScene = PhoneScenes.canvasHeadRightTop.copy(
            id = "touch-reply-popup-short",
            dock = ChatDockGeometry(anchorX = 1f, anchorY = 1f),
            state = PhoneFixtures.state.copy(
                messages = persistentListOf(
                    PhoneFixtures.messages[2],
                    PhoneFixtures.messages[3].copy(content = "Done on my end. HSTS is live and the backup is in place."),
                ),
            ),
        )

        val longScene: PhoneScene = shortScene.copy(
            id = "touch-reply-popup-long",
            state = PhoneFixtures.state.copy(
                messages = persistentListOf(
                    PhoneFixtures.messages[2],
                    PhoneFixtures.messages[3].copy(
                        content = "Done on my end. HSTS is live and the backup is in place. I also rotated the " +
                            "certificates, checked the renewal timer, wrote the rollback steps to the board and " +
                            "left a note on the two hosts that still need a restart tonight.",
                    ),
                ),
            ),
        )

        /** Android's renderer as it was bound: it paints the timeline's large body, whatever the paint asks. */
        val AndroidLikeRenderer = SharedRichMarkdownRenderer { text, paint, _, modifier ->
            Text(text, modifier, color = paint.textColor, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
