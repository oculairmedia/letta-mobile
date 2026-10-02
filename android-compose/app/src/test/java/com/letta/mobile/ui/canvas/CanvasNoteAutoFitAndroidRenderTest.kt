package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve
import com.letta.mobile.ui.test.setLettaTestContent
import com.letta.mobile.ui.theme.LettaDimens
import io.ak1.drawbox.domain.model.Viewport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The C4 estimator gate on an Android runtime at a phone's density (letta-mobile-bglj6.17; plan 3.4).
 *
 * Note cards are laid out in world units, so on an xxhdpi display (density 3) a 320-unit note is 320
 * px wide with 16-unit type, exactly as on a desktop, and the height `CanvasComposeReserve` books
 * (in world units, with no font or density) covers what Android really renders. Before, the type
 * was in sp: three times larger here than the estimator booked, so agent-booked notes overflowed.
 *
 * The cases are the C4 reserve fixtures (sharedLogic commonTest `CanvasComposeFixtures`, copied in
 * sharedUI jvmTest `CanvasNoteAutoFitFixtures`), rebuilt here because neither is visible to this
 * module; the reserve is computed, not pinned, so a fixture change cannot leave this gate stale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("integration")
class CanvasNoteAutoFitAndroidRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** [indent] is `attributes.indentationLevel`: cascade stores nested list items as a flat list. */
    private data class B(val typeId: String, val text: String = "", val level: Int? = null, val checked: Boolean? = null, val indent: Int = 0)

    private fun document(vararg blocks: B): String = document(blocks.toList())

    private fun document(blocks: List<B>): String {
        var next = 0
        fun encode(list: List<B>): JsonArray = buildJsonArray {
            list.forEach { block ->
                next++
                add(
                    buildJsonObject {
                        put("id", "b$next")
                        put(
                            "type",
                            buildJsonObject {
                                put("typeId", if (block.typeId == "heading" && block.level != null) "heading_${block.level}" else block.typeId)
                                block.checked?.let { put("checked", it) }
                            },
                        )
                        if (block.indent > 0) put("attributes", buildJsonObject { put("indentationLevel", block.indent) })
                        put(
                            "content",
                            buildJsonObject {
                                put("kind", "text")
                                put("version", 1)
                                put("text", block.text)
                                put("spans", JsonArray(emptyList()))
                            },
                        )
                    },
                )
            }
        }
        return buildJsonObject {
            put("version", JsonPrimitive(2))
            put("blocks", encode(blocks))
        }.toString()
    }

    private val lorem = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."

    private val cases: List<Pair<String, String>> = listOf(
        "empty" to document(),
        "one word" to document(B("paragraph", "Hello")),
        "weekend meals" to document(
            B("heading", "Saturday", level = 2),
            B("bullet_list", "Pasta"),
            B("heading", "Sunday", level = 2),
            B("bullet_list", "Roast"),
            B("todo", "Buy a chicken", checked = false),
        ),
        "shopping checklist" to document(B("todo", "Milk", checked = false), B("todo", "Eggs", checked = true), B("todo", "Bread", checked = false)),
        "card with fields" to document(B("heading", "Walk", level = 2), B("paragraph", "When: Sat 09:00"), B("paragraph", "Where: Park")),
        "long paragraph" to document(B("paragraph", "$lorem $lorem $lorem")),
        "capitals" to document(B("paragraph", "WORLD WIDE WEB MOMENTUM ".repeat(6).trim())),
        "unbroken url" to document(B("paragraph", "https://example.com/" + "a".repeat(180))),
        "cjk" to document(B("paragraph", "漢字かな交じり文".repeat(8))),
        "code" to document(B("code", "fun main() {\n    println(\"hello\")\n}")),
        "nested lists" to document(
            // Nested as cascade-editor and the compose compiler store it: a flat list, the child indented.
            B("bullet_list", "Groceries for the week ahead"),
            B("bullet_list", "Apples and pears from the market", indent = 1),
            B("numbered_list", "Call the plumber about the kitchen sink"),
            B("quote", "Simplicity is prerequisite for reliability."),
            B("divider"),
        ),
    )

    @Test
    fun theReserveCoversEveryFixtureAsAndroidRendersItAtXxhdpi() {
        val session = runBlocking {
            CanvasSession.create(store = InMemoryCanvasDocumentStore(), options = CanvasCreateOptions(title = "Gate", initialSceneJson = ""))
        }
        cases.forEachIndexed { i, (_, json) ->
            val frame = CanvasDocumentFrame(20f + (i % 2) * 360f, 20f + (i / 2) * 620f, WIDTH, 4000f)
            runBlocking { session.setDocument("fx$i", json, frame = frame, owner = CanvasGeometryOwner.AUTO) }
        }
        var density = 0f
        composeRule.setLettaTestContent {
            density = LocalDensity.current.density
            Box(Modifier.fillMaxSize()) {
                val doc by session.document.collectAsState()
                val documents = remember(doc) { session.documents() }
                CanvasNotesLayer(session = session, documents = documents, viewport = Viewport())
            }
        }
        composeRule.waitForIdle()
        assertEquals("the test runs at a phone's density", 3f, density)

        val report = cases.mapIndexed { i, (name, json) ->
            val node = composeRule.onNodeWithContentDescription("Note fx$i").fetchSemanticsNode()
            Triple(name, node.size, CanvasComposeReserve.reserveDocument(json, WIDTH))
        }
        println(report.joinToString("\n") { (name, size, reserve) -> "android-reserve-gate@xxhdpi $name: rendered=${size.height} reserve=$reserve" })
        report.forEach { (name, size, reserve) ->
            // World units: the card is as wide in px as its frame is in world units, at density 3.
            assertEquals("$name card width", WIDTH, size.width.toFloat(), 0.5f)
            assertTrue("$name: rendered ${size.height} > reserve $reserve", size.height <= reserve)
            // Not the empty measure of a renderer that drew nothing: every card has its handle bar.
            assertTrue("$name: rendered ${size.height} has no room for the handle bar", size.height >= LettaDimens.Control.iconButton.value)
        }
    }

    private companion object {
        const val WIDTH = 320f
    }
}
