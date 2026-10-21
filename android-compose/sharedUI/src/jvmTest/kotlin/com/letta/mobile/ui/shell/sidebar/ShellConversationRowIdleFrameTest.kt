package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-hv54v: a drawer of idle conversations lets the frame clock sleep. Before, every row
 * ran its thinking pulse, so the window was redrawn every vsync while the drawer was on screen.
 * A scene with pending invalidations after it has settled is one that will ask for another frame.
 */
class ShellConversationRowIdleFrameTest {
    @Test
    fun aDrawerOfIdleConversationsStopsAskingForFrames() {
        assertFalse(settledPanelWantsFrames(thinking = false), "an idle drawer kept requesting frames")
    }

    @Test
    fun aThinkingConversationKeepsItsPulseRunning() {
        assertTrue(settledPanelWantsFrames(thinking = true), "the thinking pulse stopped")
    }

    private fun settledPanelWantsFrames(thinking: Boolean): Boolean {
        val conversations = (1..20).map { index ->
            ShellConversationRowModel(
                id = "c$index",
                title = "Chat $index",
                preview = "",
                timeLabel = "${index}m",
                thinking = thinking && index == 1,
            )
        }
        val state = ShellAgentPanelState(agent = ShellPanelAgent(name = "Meridian", agentId = "agent-1"), conversations = conversations)
        val scene = ImageComposeScene(width = 320, height = 900, density = Density(1f)) {
            MaterialTheme {
                ShellAgentPanel(state = state, actions = ShellAgentPanelActions(), modifier = Modifier.width(280.dp).fillMaxHeight())
            }
        }
        return try {
            var clock = 0L
            // Long enough for entrance animations and first-frame effects to finish.
            repeat(SETTLE_FRAMES) {
                clock += FRAME_NANOS
                scene.render(clock)
            }
            scene.hasInvalidations()
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
        const val SETTLE_FRAMES = 120
    }
}
