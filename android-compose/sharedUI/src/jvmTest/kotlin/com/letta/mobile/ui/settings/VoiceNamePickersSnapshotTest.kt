@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.catalog.SpeechCatalog
import com.letta.mobile.data.voice.catalog.SpeechCatalogException
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.voice.VoiceSetupAccess
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Voice section's model and voice pickers at every checked width (360, 700, 760 and 1200 dp), in
 * dark and light, listed, with the voice list open, and after a failed listing. Fake server and names
 * only; images land in build/voice-picker-snapshots for review.
 */
class VoiceNamePickersSnapshotTest {
    private val listed = SpeechCatalog(
        listOf("whisper-1", "gpt-4o-transcribe", "tts-1", "tts-model-b"),
        listOf("voice-a", "voice-b", "voice-c", "voice-d"),
    )

    private enum class Scene { LISTED, OPEN, FAILED }

    private fun snapshot(scene: Scene, width: Dp, dark: Boolean) = runDesktopComposeUiTest(width = width.value.toInt(), height = HEIGHT) {
        val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
        store.save(VoiceSetupDraft("https://speech.example.invalid", consent = true, voice = "voice-b"))
        val access = VoiceSetupAccess(store, catalog = {
            if (scene == Scene.FAILED) throw SpeechCatalogException(VoiceErrorCode.UNREACHABLE) else listed
        }) { null }
        val host = if (width < TOUCH_BELOW) LettaHost(input = LettaInput.Touch) else LettaHost.Pointer
        setContent {
            ProvideLettaHost(host) {
                SharedMaterialTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.width(width).padding(LettaDimens.Space.lg).verticalScroll(rememberScrollState())) {
                            VoiceSettingsSection(access)
                        }
                    }
                }
            }
        }
        waitForIdle()
        if (scene == Scene.OPEN) {
            onNodeWithTag(SettingsSectionTags.ROW_PREFIX + VoiceSettingsKeys.VOICE).performClick()
            waitForIdle()
        }
        // An open list is a popup with a root of its own: draw each root over the window in order.
        val nodes = onAllNodes(isRoot()).fetchSemanticsNodes()
        val window = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val image = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_ARGB)
        val canvas = image.createGraphics()
        canvas.drawImage(window, 0, 0, null)
        nodes.indices.drop(1).forEach { index ->
            val bounds = nodes[index].boundsInWindow
            canvas.drawImage(onAllNodes(isRoot())[index].captureToImage().toAwtImage(), bounds.left.toInt(), bounds.top.toInt(), null)
        }
        canvas.dispose()
        val name = "${scene.name.lowercase()}-${width.value.toInt()}dp-${if (dark) "dark" else "light"}.png"
        val out = File("build/voice-picker-snapshots").apply { mkdirs() }.resolve(name)
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    private fun everyWidthAndTheme(scene: Scene) = WIDTHS.forEach { width -> listOf(true, false).forEach { snapshot(scene, width, it) } }

    @Test
    fun listedAtEveryWidth() = everyWidthAndTheme(Scene.LISTED)

    @Test
    fun voiceListOpenAtEveryWidth() = everyWidthAndTheme(Scene.OPEN)

    @Test
    fun failedListingAtEveryWidth() = everyWidthAndTheme(Scene.FAILED)

    private companion object {
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
        val TOUCH_BELOW = 760.dp
        const val HEIGHT = 1100
    }
}
