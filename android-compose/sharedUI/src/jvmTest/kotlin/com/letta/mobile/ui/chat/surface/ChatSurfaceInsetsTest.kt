@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.CompanionTestPort
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ckmub.1 (hard cut S4): the chat page renders in LettaPageScaffold, which owns the
 * keyboard inset once; the composer lifts by exactly the keyboard and, with the keyboard down, runs
 * flush to the page's foot (the bar is drawn under the navigation bar, bglj6.1.9). Fake insets stand
 * in for a phone's keyboard.
 */
class ChatSurfaceInsetsTest {
    private fun ComposeUiTest.page(keyboard: Dp) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalReducedMotion provides true,
                LocalChatSurfaceImeInsets provides WindowInsets(0.dp, 0.dp, 0.dp, keyboard),
            ) {
                MaterialTheme {
                    Box(Modifier.size(WIDTH, HEIGHT)) {
                        ChatSurface(
                            port = CompanionTestPort(typing = false),
                            presentation = ChatSurfacePresentation.ChatFirst,
                            onIntent = {},
                            host = ChatSurfaceHost(),
                            modifier = Modifier.fillMaxSize(),
                            appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                            platform = ChatSurfacePlatform(showKeyboardHints = false),
                        )
                    }
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    @Test
    fun composerRunsFlushToThePageFootWithTheKeyboardDown() = runComposeUiTest {
        page(keyboard = 0.dp)
        assertEquals(HEIGHT, barBottom())
    }

    @Test
    fun composerLiftsByExactlyTheKeyboard() = runComposeUiTest {
        page(keyboard = KEYBOARD)
        assertEquals(HEIGHT - KEYBOARD, barBottom())
    }

    private fun ComposeUiTest.barBottom(): Dp =
        onNodeWithTag(ComposerTestTags.TOUCH_BAR).getBoundsInRoot().bottom - onRoot().getBoundsInRoot().top

    private companion object {
        val WIDTH = 400.dp
        val HEIGHT = 700.dp
        val KEYBOARD = 300.dp
        const val SETTLE_MILLIS = 1_000L
    }
}
