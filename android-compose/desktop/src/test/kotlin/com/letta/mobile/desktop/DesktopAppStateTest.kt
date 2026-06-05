package com.letta.mobile.desktop

import com.letta.mobile.data.model.LettaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAppStateTest {
    @Test
    fun defaultBootstrapTargetsSelfHostedLocalEndpoint() {
        val state = defaultDesktopBootstrapState()

        assertEquals(LettaConfig.Mode.SELF_HOSTED, state.config.mode)
        assertEquals("http://localhost:8283", state.config.serverUrl)
    }

    @Test
    fun defaultBootstrapMarksWindowsRuntimeReady() {
        val state = defaultDesktopBootstrapState()

        assertTrue(
            state.featureReadiness.any {
                it.title == "Windows desktop runtime" &&
                    it.state == DesktopFeatureState.Ready
            },
        )
    }
}
