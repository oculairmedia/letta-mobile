package com.letta.mobile.feature.chat

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.feature.chat.screen.shared.SharedChatRichMarkdown
import com.letta.mobile.ui.markdown.LocalSharedRichMarkdownRenderer
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.LettaTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * letta-mobile-bglj6.1.16: the shared chat page's rich markdown binding (Android: the
 * designsystem renderer the legacy chat used) over a fixture with the surfaces the stock shared
 * paint lost — a highlighted code fence with its language header and copy button, rounded inline
 * code, editorial list padding and an autolinked bare URL. Math and Mermaid render through the
 * same binding at runtime but need a WebView, so they stay out of the Robolectric fixture.
 *
 * Each PNG is written to build/outputs/roborazzi/shared-rich-markdown; no baseline is recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w412dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("screenshot")
class SharedRichMarkdownScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settledDark() = capture("settled-dark", AppTheme.DARK, streaming = false)

    @Test
    fun settledLight() = capture("settled-light", AppTheme.LIGHT, streaming = false)

    @Test
    fun streamingDark() = capture("streaming-dark", AppTheme.DARK, streaming = true)

    @Test
    fun streamingLight() = capture("streaming-light", AppTheme.LIGHT, streaming = true)

    private fun capture(name: String, theme: AppTheme, streaming: Boolean) {
        composeRule.setContent {
            LettaTheme(appTheme = theme, themePreset = ThemePreset.DEFAULT, dynamicColor = false) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    CompositionLocalProvider(LocalSharedRichMarkdownRenderer provides SharedChatRichMarkdown) {
                        SharedMarkdownText(text = FIXTURE, isStreaming = streaming, retainState = false)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/outputs/roborazzi/shared-rich-markdown").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        assertTrue(out.length() > 0)
    }

    private companion object {
        const val PNG_QUALITY = 100
        const val FIXTURE = """
### Release notes v0.4.2

The composer now flushes to the glass edge. See https://example.com/notes for details.

- Highlighted code fences with a copy button
- Rounded `inline code` spans
- Autolinked bare URLs

```kotlin
fun greet(name: String): String {
    return "Hello, " + name + "!"
}
```
"""
    }
}