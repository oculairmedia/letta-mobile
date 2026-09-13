package com.letta.mobile.feature.chat.zoom

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.TimelineZoomScope
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.scaledBy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-tgypm.1. Every assertion reads the size off the layout result Compose produced, not
 * off the helper meant to produce it: the defect this fixes was invisible to the helper, because
 * both scale channels looked right alone and only multiplied when combined.
 *
 * The live scale is driven as state inside one composition, so these exercise a pinch the way a
 * pinch happens - the same nodes changing size - rather than two unrelated renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TimelineZoomScopeTest {
    @get:Rule val compose = createComposeRule()

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    private fun sp(tag: String) = compose.onNodeWithText(tag).layout().layoutInput.style.fontSize.value
    private fun paragraph(): TextLayoutResult = compose.onNodeWithTag("paragraph").layout()
    private fun lineHeight(tag: String) =
        compose.onNodeWithText(tag).layout().layoutInput.style.lineHeight.value


    /** [committed] is the theme's scale, fixed for a test; [live] is what the row publishes. */
    private fun mount(committed: Float, live: Float = committed): MutableState<Float> {
        val liveScale = mutableStateOf(live)
        compose.setContent {
            val current by liveScale
            LettaTheme(appTheme = AppTheme.LIGHT, themePreset = ThemePreset.DEFAULT, dynamicColor = false) {
                LettaChatTheme(fontScale = committed) {
                    Column {
                    TimelineZoomScope(current) {
                        Text("body", style = MaterialTheme.chatTypography.messageBody)
                        Text("code", style = MaterialTheme.chatTypography.codeBlock)
                        Text("stamp", style = MaterialTheme.chatTypography.timestamp)
                        Text(
                            "material",
                            style = MaterialTheme.typography.bodyMedium.scaledBy(LocalChatFontScale.current),
                        )
                        // Width-constrained so the laid-out box has to respond to the text size;
                        // an unconstrained word is sized by its container and hides the change.
                        Text(
                            "paragraph that has to wrap inside a narrow column and therefore grows taller",
                            modifier = Modifier.width(120.dp).testTag("paragraph"),
                            style = MaterialTheme.chatTypography.messageBody,
                        )
                    }
                    Text("outside", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        return liveScale
    }

    private fun pinchTo(scale: MutableState<Float>, value: Float) {
        scale.value = value
        compose.waitForIdle()
    }

    @Test fun anUnzoomedTimelineRendersAtTheBaseSize() {
        mount(committed = 1f)
        assertEquals(sp("outside"), sp("body"), 0.01f)
        assertEquals(sp("outside"), sp("material"), 0.01f)
    }

    /**
     * Mid-gesture the theme still holds the old committed scale. Every style must already be
     * moving, each from its own base. Before the zoom scope, chatTypography stayed put for the
     * whole pinch while Material-styled siblings grew, so a card's title and its body disagreed.
     */
    @Test fun everyStyleFollowsTheGestureBeforeItIsCommitted() {
        val scale = mount(committed = 1f)
        val before = listOf("body", "code", "stamp", "material").associateWith { sp(it) }
        pinchTo(scale, 2f)
        before.forEach { (tag, was) -> assertEquals("$tag did not follow the gesture", was * 2f, sp(tag), 0.01f) }
    }

    /** After release both channels hold the same number, and agreeing must not mean multiplying. */
    @Test fun aCommittedZoomIsAppliedExactlyOnce() {
        mount(committed = 1.5f)
        // bodyMedium is 14sp, so a correct 1.5x is 21sp. Squaring it gives 31.5sp, which is what
        // shipped before this scope existed.
        assertEquals(21f, sp("body"), 0.01f)
        assertEquals(21f, sp("material"), 0.01f)
    }

    /** Shrinking is the same rule in the other direction, with no floor of its own. */
    @Test fun shrinkingFollowsTheGestureToo() {
        val scale = mount(committed = 1f)
        val was = sp("body")
        pinchTo(scale, 0.5f)
        assertEquals(was * 0.5f, sp("body"), 0.01f)
    }

    /** Line height travels with the size, or wrapping and clipping go wrong at scale. */
    /**
     * Line height travels with the size, or text tightens as it grows and wrapping goes wrong.
     *
     * Asserted on the layout input Compose produced, not on pixel box height: Robolectric draws
     * with a synthetic font whose metrics barely move with size, so a measured box grows by a pixel
     * or two for a doubled style and cannot tell a correct layout from a clipped one. Pixel
     * geometry - wrapping, clipping and hit targets at scale - is the instrumented half of
     * letta-mobile-tgypm.6 and is not claimed here.
     */
    @Test fun lineHeightScalesWithTheText() {
        val scale = mount(committed = 1f)
        val wasLine = lineHeight("body")
        val wasParagraphLine = paragraph().layoutInput.style.lineHeight.value
        pinchTo(scale, 2f)
        assertEquals(wasLine * 2f, lineHeight("body"), 0.05f)
        assertEquals(wasParagraphLine * 2f, paragraph().layoutInput.style.lineHeight.value, 0.05f)
    }

    /** Chrome outside the scope keeps its size no matter what the timeline is doing. */
    @Test fun contentOutsideTheScopeDoesNotZoom() {
        val scale = mount(committed = 1f)
        val idle = sp("outside")
        pinchTo(scale, 3f)
        assertEquals(idle, sp("outside"), 0.01f)
    }

    /**
     * Fail-on-revert guard. Reverting TimelineZoomScope to provide only LocalChatFontScale - the
     * shape it replaced - leaves chatTypography on the theme's committed scale. This drives the
     * live scale while the committed one is pinned at 1, so a scope that stops providing the
     * typography reports a ratio of 1 here and fails.
     */
    @Test fun theScopeAndNotTheThemeDecidesTheTypographySize() {
        val scale = mount(committed = 1f)
        val atRest = sp("code")
        pinchTo(scale, 2f)
        assertEquals(2f, sp("code") / atRest, 0.01f)
    }
}
