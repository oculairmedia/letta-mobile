package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Assert.assertTrue
import com.letta.mobile.ui.markdown.LocalSharedRichMarkdownRenderer
import com.letta.mobile.ui.markdown.SharedMarkdownText
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-qvkmg: the Android prose renderer (the designsystem renderer S4 wired into the
 * shared chat page) paired the `$`s of shell command substitution into an inline TeX span, so
 * `T=$(grep …); A=$(cat …)` drew as red serif math with the text between the `$`s gone. The
 * shell line must come through as one verbatim text run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class SharedChatRichMarkdownShellTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shellCommandSubstitutionRendersVerbatimNotAsMath() {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSharedRichMarkdownRenderer provides SharedChatRichMarkdown) {
                    SharedMarkdownText(text = SHELL)
                }
            }
        }
        composeRule.waitForIdle()
        val tree = composeRule.onRoot(useUnmergedTree = true).printToString()
        assertTrue("the shell span must render as text, not a math atom:\n$tree",tree.contains(MATH_PAIRED_SPAN))
    }

    private companion object {
        const val SHELL = "Ran: T=\$(grep -E foo /tmp/f.txt); A=\$(cat /tmp/f.txt)"

        // Everything between the two `$`s: the old rule swallowed exactly this into a math atom.
        const val MATH_PAIRED_SPAN = "(grep -E foo /tmp/f.txt); A="
    }
}
