package com.letta.mobile.designsystem

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.letta.mobile.ui.components.PreviewDateSeparatorContent
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag

@Ignore("Paparazzi 1.3.5 incompatible with compileSdk 36")
@Tag("screenshot")
class DateSeparatorScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    @Test
    fun dateSeparator() {
        paparazzi.snapshot { PreviewDateSeparatorContent() }
    }
}
