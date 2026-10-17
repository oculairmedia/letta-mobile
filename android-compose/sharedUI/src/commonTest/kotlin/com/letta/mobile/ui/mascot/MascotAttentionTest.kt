package com.letta.mobile.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.avatar.core.AttentionInterest
import com.letta.mobile.avatar.core.AttentionKey
import com.letta.mobile.avatar.core.AttentionKind
import com.letta.mobile.avatar.core.GazeReach
import com.letta.mobile.avatar.core.GazeRect
import com.letta.mobile.avatar.core.GazeWorld
import com.letta.mobile.data.model.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.2: the registry writers behind attention - a source's slot, Android's last
 * touch standing in for a cursor - and the gaze world [MascotLive] builds from the registry.
 */
class MascotAttentionTest {
    @Test
    fun aPublisherWritesOnlyOnChangeAndRetractsItself() {
        val registry = MascotIdentityRegistry()
        val key = AttentionKey("approval:1")
        val publisher = AttentionPublisher(registry.attention, key)
        val rect = GazeRect(10f, 10f, 110f, 60f)
        publisher.publish(AttentionKind.APPROVAL, AttentionInterest.APPROVAL, rect)
        val first = registry.attention[key]
        publisher.publish(AttentionKind.APPROVAL, AttentionInterest.APPROVAL, rect)
        assertTrue(first === registry.attention[key], "an unchanged rect is not rewritten")
        publisher.retract()
        assertNull(registry.attention[key])
    }

    @Test
    fun theLastTouchIsTheCursorUntilItExpires() {
        val registry = MascotIdentityRegistry()
        val touch = MascotTouchPresence(registry.cursor)
        touch.touched(Offset(40f, 300f))
        assertEquals(Offset(40f, 300f), registry.cursor.value)
        touch.expire()
        assertNull(registry.cursor.value, "a lifted finger stops drawing the eyes")
        assertEquals(1_500L, MASCOT_TOUCH_PRESENCE.inWholeMilliseconds)
    }

    @Test
    fun anExpiringTouchLeavesTheDesktopCursorAlone() {
        val registry = MascotIdentityRegistry()
        val touch = MascotTouchPresence(registry.cursor)
        touch.touched(Offset(40f, 300f))
        registry.cursor.value = Offset(500f, 20f) // the mouse moved since
        touch.expire()
        assertEquals(Offset(500f, 20f), registry.cursor.value)
    }

    @Test
    fun theGazeWorldCarriesEveryAttentionPoint() {
        val registry = MascotIdentityRegistry()
        AttentionPublisher(registry.attention, AttentionKey("tool:a"))
            .publish(AttentionKind.TOOL, AttentionInterest.STEADY, GazeRect(200f, 100f, 600f, 130f))
        AttentionPublisher(registry.attention, AttentionKey("composer-caret"))
            .publish(AttentionKind.CARET, AttentionInterest.STEADY, GazeRect(300f, 600f, 316f, 616f))
        val window = registry.gazeWindow(AgentId("agent"), Rect(0f, 560f, 64f, 624f), GazeReach(360f))
        assertEquals(2, window.rects.attention.size)
        val world = GazeWorld.fromWindow(window)
        val tool = assertNotNull(world.attentionOf(AttentionKind.TOOL)).point
        assertTrue(tool.x > 0f && tool.y < 0f, "the tool row is up and right of the mascot: $tool")
        assertNotNull(world.attentionOf(AttentionKind.CARET))
    }
}
