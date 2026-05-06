package com.letta.mobile.designsystem

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.letta.mobile.ui.components.PreviewAccordionsContent
import com.letta.mobile.ui.components.PreviewEmptyStateContent
import com.letta.mobile.ui.components.PreviewErrorDialogContent
import com.letta.mobile.ui.components.PreviewLatencyTextContent
import com.letta.mobile.ui.components.PreviewMessageActionButtonsContent
import com.letta.mobile.ui.components.PreviewMessageBubbleContent
import com.letta.mobile.ui.components.PreviewMessageSenderContent
import com.letta.mobile.ui.components.PreviewRotationalLoaderContent
import com.letta.mobile.ui.components.PreviewThinkingSectionContent
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
    fun messageBubble() {
        paparazzi.snapshot { PreviewMessageBubbleContent() }
    }

    @Test
    fun thinkingSection() {
        paparazzi.snapshot { PreviewThinkingSectionContent() }
    }

    @Test
    fun emptyState() {
        paparazzi.snapshot { PreviewEmptyStateContent() }
    }

    @Test
    fun messageActionButtons() {
        paparazzi.snapshot { PreviewMessageActionButtonsContent() }
    }

    @Test
    fun latencyText() {
        paparazzi.snapshot { PreviewLatencyTextContent() }
    }

    @Test
    fun errorDialog() {
        paparazzi.snapshot { PreviewErrorDialogContent() }
    }

    @Test
    fun rotationalLoader() {
        paparazzi.snapshot { PreviewRotationalLoaderContent() }
    }

    @Test
    fun accordions() {
        paparazzi.snapshot { PreviewAccordionsContent() }
    }

    @Test
    fun messageSender() {
        paparazzi.snapshot { PreviewMessageSenderContent() }
    }
}
