package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.ui.canvas.plugin.CanvasPluginLayer
import com.letta.mobile.ui.canvas.plugin.CanvasPluginTestTags
import com.letta.mobile.ui.canvas.plugin.rememberPluginBoard
import com.letta.mobile.ui.test.setLettaTestContent
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The plugin fallback card on an Android runtime at a phone's density (letta-mobile-s416w.4): the
 * elements of the shared `canvas/plugin/v1/fallback-card.json` fixture (sharedUI commonTest) render
 * with their titles, and each card is as wide in px as its frame is in world units, at density 3
 * exactly as on a desktop, because board content is laid out in world units.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("integration")
class CanvasPluginFallbackAndroidRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val json = Json { ignoreUnknownKeys = true }

    /** The fixture's elements, read from sharedUI's test resources (this module cannot see them on its classpath). */
    private fun fixtureElements(): List<CanvasPluginElement> {
        val relative = "sharedUI/src/commonTest/resources/canvas/plugin/v1/fallback-card.json"
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .first { it.isFile }
        return json.parseToJsonElement(file.readText()).jsonObject.getValue("cards").jsonArray.map { card ->
            json.decodeFromJsonElement(CanvasPluginElement.serializer(), card.jsonObject.getValue("element"))
        }
    }

    @Test
    fun theFixtureCardsRenderInWorldUnitsAtXxhdpi() {
        val elements = fixtureElements()
        val session = runBlocking {
            CanvasSession.create(store = InMemoryCanvasDocumentStore(), options = CanvasCreateOptions(title = "Plugins", initialSceneJson = ""))
        }
        elements.forEach { element ->
            runBlocking {
                session.applyLocalStamped(
                    listOf(
                        CanvasOp.SetPluginElementOp(
                            opId = "",
                            actorId = CanvasSession.LOCAL_USER_ACTOR_ID,
                            lamport = 0L,
                            elementId = element.id,
                            elementType = element.type,
                            v = element.v,
                            frame = element.frame,
                            owner = element.owner,
                            props = element.props,
                            snapshot = element.snapshot,
                            fallback = element.fallback,
                        ),
                    ),
                )
            }
        }
        val assets = InMemoryAssetStore()
        var density = 0f
        composeRule.setLettaTestContent {
            density = LocalDensity.current.density
            Box(Modifier.fillMaxSize()) {
                val doc by session.document.collectAsState()
                CanvasPluginLayer(board = rememberPluginBoard(session, doc, assets), viewport = io.ak1.drawbox.domain.model.Viewport())
            }
        }
        composeRule.waitForIdle()
        assertEquals("the test runs at a phone's density", 3f, density)

        elements.forEach { element ->
            composeRule.onNodeWithText(element.fallback.title).assertExists()
            val card = composeRule.onNodeWithTag(CanvasPluginTestTags.card(element.id)).fetchSemanticsNode()
            val frame = checkNotNull(element.frame)
            assertEquals("${element.id} card width", frame.width, card.size.width.toFloat(), 0.5f)
            assertEquals("${element.id} card height", frame.height, card.size.height.toFloat(), 0.5f)
        }
    }
}
