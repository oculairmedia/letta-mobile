package com.letta.mobile.desktop.touch

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
}
