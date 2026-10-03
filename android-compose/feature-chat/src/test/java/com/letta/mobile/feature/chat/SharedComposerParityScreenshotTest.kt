package com.letta.mobile.feature.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.feature.chat.screen.ChatComposer
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.composer.ChatComposerSnapshot
import com.letta.mobile.ui.chat.surface.composer.ComposerSnapshotContent
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * letta-mobile-bglj6.1.9: the shared composer in the Touch idiom (sharedUI ChatComposerSnapshot)
 * under the legacy Android composer (feature-chat ChatComposer), at phone width, from the same
 * draft under the same theme. The legacy composer is the visual spec.
 *
 * Each PNG stacks legacy above shared. Written to build/outputs/roborazzi/shared-composer-parity;
 * no baseline is recorded. Neither side shows the mic here: the legacy one needs the Hilt voice
 * model, so both are drawn without a dictation slot (the phone renders in sharedUI show it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w412dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("screenshot")
class SharedComposerParityScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleDark() = capture("idle-dark", AppTheme.DARK, Draft())

    @Test
    fun idleLight() = capture("idle-light", AppTheme.LIGHT, Draft())

    @Test
    fun textDark() = capture("text-dark", AppTheme.DARK, Draft(text = DRAFT))

    @Test
    fun textLight() = capture("text-light", AppTheme.LIGHT, Draft(text = DRAFT))

    @Test
    fun streamingDark() = capture("streaming-stop-dark", AppTheme.DARK, Draft(streaming = true))

    @Test
    fun streamingLight() = capture("streaming-stop-light", AppTheme.LIGHT, Draft(streaming = true))

    @Test
    fun attachmentDark() = capture("attachment-dark", AppTheme.DARK, Draft(text = DRAFT, attachments = persistentListOf(sampleImage())))

    @Test
    fun attachmentLight() = capture("attachment-light", AppTheme.LIGHT, Draft(text = DRAFT, attachments = persistentListOf(sampleImage())))

    private data class Draft(
        val text: String = "",
        val streaming: Boolean = false,
        val attachments: ImmutableList<MessageContentPart.Image> = persistentListOf(),
    )

    private fun capture(name: String, theme: AppTheme, draft: Draft) {
        composeRule.setContent {
            LettaTheme(appTheme = theme, themePreset = ThemePreset.DEFAULT, dynamicColor = false) {
                LettaChatTheme {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Pane("legacy Android", Modifier.weight(1f)) { Legacy(draft) }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                        Pane("shared (Touch)", Modifier.weight(1f)) { Shared(draft) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/outputs/roborazzi/shared-composer-parity").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        assertTrue(out.length() > 0)
    }

    /** A pane whose composer sits at its bottom edge, as on the page. */
    @Composable
    private fun Pane(label: String, modifier: Modifier, content: @Composable () -> Unit) {
        Box(modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(LettaDimens.Space.xs),
            )
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { content() }
        }
    }

    @Composable
    private fun Legacy(draft: Draft) {
        ChatComposer(
            inputText = draft.text,
            pendingAttachments = draft.attachments,
            isStreaming = draft.streaming,
            canSendMessages = true,
            onTextChange = {},
            onSend = {},
            onStop = {},
            onRemoveAttachment = {},
            onAttachImage = {},
        )
    }

    @Composable
    private fun Shared(draft: Draft) {
        ChatComposerSnapshot(
            ComposerSnapshotContent(
                composer = ChatComposerUiState(
                    text = draft.text,
                    attachments = draft.attachments,
                    canSend = true,
                    // On a phone the model is not in the bar.
                    model = ChatModelUiState(currentHandle = "lmstudio/MiniMax-M3", currentLabel = "lmstudio/MiniMax-M3"),
                ),
                state = ChatUiState(
                    conversationState = ConversationState.Ready("conv-parity"),
                    isLoadingMessages = false,
                    isStreaming = draft.streaming,
                    agentName = "Meridian",
                ),
                actions = NoOpChatActions,
                appearance = ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch),
                platform = ChatSurfacePlatform(showKeyboardHints = false),
            ),
        )
    }

    private companion object {
        const val PNG_QUALITY = 100
        const val DRAFT = "Draft a release announcement for v0.4.2."

        /** A small gradient PNG standing in for a picked photo. */
        fun sampleImage(): MessageContentPart.Image {
            val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, 160f, 120f, 0xFF1B2A3A.toInt(), 0xFF3C6E9F.toInt(), Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawRect(0f, 0f, 160f, 120f, paint)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }.toByteArray()
            return MessageContentPart.Image(base64 = Base64.getEncoder().encodeToString(bytes), mediaType = "image/png")
        }
    }
}
