@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.a2ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiLink
import com.letta.mobile.data.a2ui.A2uiLinkDecision
import com.letta.mobile.data.a2ui.A2uiLinkPolicy
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.A2uiSurfaceLimits
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.a2ui.A2uiUpdateComponentsPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-by4vm.3: one server frame cannot freeze the renderer or open an arbitrary URI. */
class A2uiRenderLimitsUiTest {

    private class RecordingUriHandler : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    @Test
    fun diamondDagOfDepthTwentyRendersABoundedNodeCount() = runComposeUiTest {
        val depth = 20
        val components = (0..depth).associate { level ->
            val id = "level$level"
            id to if (level == depth) text(id, "leaf") else container(id, "Column", List(2) { "level${level + 1}" })
        }

        setContent { A2uiSurfaceRenderer(surface = surface(components, root = "level0")) }

        val leaves = onAllNodesWithText("leaf", useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue(leaves in 1..A2uiSurfaceLimits.MAX_INSTANCES_PER_COMPONENT, "leaves=$leaves")
        onAllNodesWithTag(A2uiTestTags.RENDER_LIMIT, useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue(it.isNotEmpty(), "repeated instances past the cap render as placeholders")
        }
    }

    @Test
    fun tenThousandChildColumnTruncatesWithPlaceholder() = runComposeUiTest {
        val count = 10_000
        val items = (0 until count).associate { "t$it" to text("t$it", "item $it") }
        val components = items + ("col" to container("col", "Column", items.keys.toList()))

        setContent { A2uiSurfaceRenderer(surface = surface(components, root = "col")) }

        val shown = onAllNodesWithText("item ", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(A2uiSurfaceLimits.MAX_CHILDREN_PER_CONTAINER, shown)
        onNodeWithTag(A2uiTestTags.CHILDREN_TRUNCATED, useUnmergedTree = true).assertExists()
        onNodeWithText("${count - A2uiSurfaceLimits.MAX_CHILDREN_PER_CONTAINER} more not shown").assertExists()
    }

    @Test
    fun oversizedUpdateRendersSurfaceTooLargePlaceholder() = runComposeUiTest {
        val manager = A2uiSurfaceManager()
        val tooMany = (0..A2uiSurfaceLimits.MAX_COMPONENTS_PER_SURFACE).map { text("t$it", "item $it") }
        manager.applyMessage(A2uiMessage.UpdateComponents(updateComponents = A2uiUpdateComponentsPayload("s1", tooMany)))

        setContent { A2uiRenderer(surfaceId = "s1", surfaceManager = manager) }

        onNodeWithTag(A2uiTestTags.SURFACE_TOO_LARGE).assertExists()
        onAllNodesWithText("item ", substring = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun javascriptUrlNeverReachesTheUriHandler() = runComposeUiTest {
        val handler = RecordingUriHandler()
        val asked = mutableListOf<A2uiLink>()
        // Even a host policy that would open anything never sees a javascript: URL.
        val openAll = A2uiLinkPolicy { asked += it; A2uiLinkDecision.Open }

        setContent {
            CompositionLocalProvider(LocalUriHandler provides handler, LocalA2uiLinkPolicy provides openAll) {
                A2uiSurfaceRenderer(surface = surface(mapOf("b" to openUrlButton("b", "javascript:alert(1)")), root = "b"))
            }
        }
        onNodeWithText("Visit").assertIsNotEnabled().performClick()
        waitForIdle()

        assertTrue(handler.opened.isEmpty())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun httpUrlGoesThroughThePolicyAndDefaultConfirmation() = runComposeUiTest {
        val handler = RecordingUriHandler()
        val asked = mutableListOf<String>()
        val confirmAll = A2uiLinkPolicy { asked += it.url; A2uiLinkDecision.Confirm }

        setContent {
            CompositionLocalProvider(LocalUriHandler provides handler, LocalA2uiLinkPolicy provides confirmAll) {
                A2uiSurfaceRenderer(surface = surface(mapOf("b" to openUrlButton("b", "https://letta.com/docs")), root = "b"))
            }
        }
        onNodeWithText("Visit").performClick()
        waitForIdle()

        assertEquals(listOf("https://letta.com/docs"), asked)
        assertTrue(handler.opened.isEmpty(), "nothing opens before the person confirms")
        onNodeWithTag(A2uiTestTags.LINK_CONFIRM).assertExists()
        onNodeWithText("https://letta.com/docs").assertExists()

        onNodeWithText("Open").performClick()
        waitForIdle()

        assertEquals(listOf("https://letta.com/docs"), handler.opened)
        onNodeWithTag(A2uiTestTags.LINK_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun deniedLinkIsNeverOpened() = runComposeUiTest {
        val handler = RecordingUriHandler()

        setContent {
            CompositionLocalProvider(LocalUriHandler provides handler, LocalA2uiLinkPolicy provides A2uiLinkPolicy.DenyAll) {
                A2uiSurfaceRenderer(surface = surface(mapOf("b" to openUrlButton("b", "https://letta.com")), root = "b"))
            }
        }
        onNodeWithText("Visit").performClick()
        waitForIdle()

        assertTrue(handler.opened.isEmpty())
        onNodeWithTag(A2uiTestTags.LINK_CONFIRM).assertDoesNotExist()
    }

    private fun surface(components: Map<String, A2uiComponent>, root: String) =
        A2uiSurfaceState(surfaceId = "s1", rootComponentId = root, components = components)

    private fun text(id: String, value: String) =
        A2uiComponent(id, "Text", JsonObject(mapOf("text" to JsonPrimitive(value))))

    private fun container(id: String, type: String, children: List<String>) =
        A2uiComponent(id, type, JsonObject(mapOf("children" to JsonArray(children.map(::JsonPrimitive)))))

    private fun openUrlButton(id: String, url: String) = A2uiComponent(
        id,
        "Button",
        buildJsonObject {
            put("label", "Visit")
            put(
                "action",
                buildJsonObject {
                    put(
                        "functionCall",
                        buildJsonObject {
                            put("call", "openUrl")
                            put("args", buildJsonObject { put("url", url) })
                        },
                    )
                },
            )
        },
    )
}
