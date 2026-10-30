package com.letta.mobile.desktop.touch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.desktop.input.TabletBridge
import com.letta.mobile.desktop.input.TabletPenDecoder
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop fingers only work while the window's Compose scene can be reached. The scene is private,
 * so a Compose upgrade can move it; this fails on that upgrade rather than leaving fingers dead.
 */
class ComposeTouchInjectorTest {
    @Test
    fun thisComposeBuildStillLetsFingersReachTheScene() {
        assertEquals(emptyList(), ComposeTouchInjector.missingSceneSteps())
    }

    // letta-mobile-erx7m: a finger's down waits for its first move to be placed. A still tap (down,
    // then up, no move between) used to lift a finger Compose never pressed, so the tap was dropped;
    // only a sloppy or repeated tap, which moves, got through.
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun aStillTapThatLiftsBeforeAnyMoveIsStillAClick() {
        var clicks = 0
        val recomposer = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(SIZE, SIZE))
        try {
            scene.setContent { Box(Modifier.fillMaxSize().clickable { clicks++ }) }
            recomposer.performFrame(0L)
            val injector = ComposeTouchInjector(scene, JPanel(), JPanel()) { Offset.Zero }

            injector.onSample(JPanel(), finger(TabletBridge.KIND_DOWN))
            injector.lift(CONTACT, at = Offset(SIZE / 2f, SIZE / 2f))

            assertEquals(1, clicks)
        } finally {
            scene.close()
            recomposer.close()
        }
    }

    private fun finger(kind: Int) = TabletPenDecoder.DecodedSample(
        kind = kind,
        x = SIZE / 2f,
        y = SIZE / 2f,
        rawX = SIZE / 2f,
        rawY = SIZE / 2f,
        force = 0f,
        tool = TabletBridge.TOOL_TOUCH,
        contact = CONTACT,
    )

    private companion object {
        const val SIZE = 200
        const val CONTACT = 7
    }
}
