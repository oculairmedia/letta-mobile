package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopUpdateChannelTest {
    @Test
    fun stableVersionsPollLatest() {
        assertEquals("latest", updateChannelFor("0.2.0"))
        assertEquals("latest", updateChannelFor("1.0.15"))
    }

    @Test
    fun alphaAndBetaKeepTheirOwnChannels() {
        assertEquals("alpha", updateChannelFor("0.2.0-alpha.3"))
        assertEquals("beta", updateChannelFor("0.2.0-beta.1"))
        assertEquals("beta", updateChannelFor("0.2.0-Beta.1"))
    }

    @Test
    fun unsupportedPrereleaseLabelsMapToBeta() {
        assertEquals("beta", updateChannelFor("0.2.0-rc.1"))
        assertEquals("beta", updateChannelFor("0.0.0-dev"))
        assertEquals("beta", updateChannelFor("0.2.0-hotfix.2"))
        // Degenerate suffix still stays off the stable channel.
        assertEquals("beta", updateChannelFor("0.2.0-1"))
    }
}
