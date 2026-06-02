package com.letta.mobile.feature.chat

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.feature.chat.screen.ChatComposer
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import com.letta.mobile.feature.chat.screen.ChatComposerTestTags

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ChatComposerAttachmentThumbnailTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `composer renders attached image bitmap instead of empty placeholder`() {
        composeRule.setContent {
            LettaTheme {
                ChatComposer(
                    inputText = "",
                    pendingAttachments = persistentListOf(testImage()),
                    isStreaming = false,
                    canSendMessages = true,
                    onTextChange = {},
                    onSend = {},
                    onStop = {},
                    onRemoveAttachment = {},
                    onAttachImage = {},
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentThumbnailImage, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentThumbnailPlaceholder, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `thumbnail opens preview dialog and remove clears staged attachment`() {
        var attachments by mutableStateOf(persistentListOf(testImage()))

        composeRule.setContent {
            LettaTheme {
                ChatComposer(
                    inputText = "",
                    pendingAttachments = attachments,
                    isStreaming = false,
                    canSendMessages = true,
                    onTextChange = {},
                    onSend = {},
                    onStop = {},
                    onRemoveAttachment = { index ->
                        attachments = attachments.removingAt(index)
                    },
                    onAttachImage = {},
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentThumbnail)
            .performClick()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentPreviewDialog)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentPreviewImage)
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Close")
            .performClick()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentPreviewDialog)
            .assertDoesNotExist()

        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentThumbnailRemoveButton, useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentThumbnailImage, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    private fun testImage(): MessageContentPart.Image {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return MessageContentPart.Image(
            base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
            mediaType = "image/png",
        )
    }
}
