package com.letta.mobile.designsystem

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.letta.mobile.ui.components.PreviewAccordionsContent
import com.letta.mobile.ui.components.PreviewEmptyStateContent
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag

@Ignore("Paparazzi 1.3.5 incompatible with compileSdk 36 — see letta-mobile-nos3")
@Tag("screenshot")
class PreviewScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    @Test
    fun emptyState() {
        paparazzi.snapshot { PreviewEmptyStateContent() }
    }

    @Test
    fun accordions() {
        paparazzi.snapshot { PreviewAccordionsContent() }
    }
}
