@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.components.beam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion
import com.letta.mobile.ui.theme.tokens.LettaBeamPalette
import org.junit.Test

class BorderBeamIdleTest {
    @Test(timeout = 10_000)
    fun activeBeamDoesNotPreventIdle() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLettaReducedMotion provides false) {
                Box(
                    Modifier.size(100.dp).lettaBorderBeam(
                        LettaBeamSpec(LettaBeamPalette.spectrum, RoundedCornerShape(8.dp)), active = true,
                    ),
                )
            }
        }
        waitForIdle()
    }
}
