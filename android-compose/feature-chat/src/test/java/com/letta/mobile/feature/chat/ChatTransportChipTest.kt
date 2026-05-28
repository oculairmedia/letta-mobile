package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.letta.mobile.ui.test.setLettaTestContent
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ChatTransportChipTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun restTransportChipIsVisible() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            ChatTransportChip(
                transport = ChatTransport.Rest,
                a2uiFrameCount = 0,
            )
        }

        composeRule
            .onNodeWithContentDescription("Chat transport: REST")
            .assertIsDisplayed()
    }

    @Test
    fun wsA2uiTransportChipShowsCatalogAndFrameCount() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            ChatTransportChip(
                transport = ChatTransport.WsConnected(a2uiEnabled = true, catalog = "basic"),
                a2uiFrameCount = 3,
            )
        }

        composeRule
            .onNodeWithContentDescription("Chat transport: WS · A2UI basic · 3")
            .assertIsDisplayed()
    }

    @Test
    fun cleanWsCloseIsDistinguishedFromAbnormalClose() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            ChatTransportChip(
                transport = ChatTransport.WsDisconnected(code = 1000, reason = "client closing"),
                a2uiFrameCount = 0,
            )
        }

        composeRule
            .onNodeWithContentDescription("Chat transport: WS off (1000), clean close")
            .assertIsDisplayed()
    }

    @Test
    fun abnormalWsCloseStaysDistinguishedFromCleanClose() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            ChatTransportChip(
                transport = ChatTransport.WsDisconnected(code = 1006, reason = "abnormal closure"),
                a2uiFrameCount = 0,
            )
        }

        composeRule
            .onNodeWithContentDescription("Chat transport: WS off (1006), abnormal close")
            .assertIsDisplayed()
    }
}
