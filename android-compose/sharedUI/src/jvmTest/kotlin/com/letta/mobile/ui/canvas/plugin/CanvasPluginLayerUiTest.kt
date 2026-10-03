@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginSnapshot
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.ui.canvas.CanvasDocumentRecorder
import com.letta.mobile.ui.canvas.LocalCanvasDocumentRecorder
import com.letta.mobile.ui.canvas.LocalNoteCompositionProbe
import io.ak1.drawbox.domain.model.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The plugin layer on a board (letta-mobile-s416w.4): the fallback card draws what the element
 * carries and what the board knows of its plugin, Open follows the link, a drag and a resize are
 * written as one USER frame op, a press selects, an unknown type and a failing renderer fall back
 * to the card without touching the other elements, a snapshot that arrives later replaces the
 * skeleton, and a pan or zoom recomposes no element.
 */
class CanvasPluginLayerUiTest {

    private class Host(
        val viewport: MutableState<Viewport> = mutableStateOf(Viewport()),
        val selected: MutableState<String?> = mutableStateOf(null),
        val renderers: PluginElementRenderers = PluginElementRenderers.Core,
        val availability: PluginAvailabilitySource = PluginAvailabilitySource { PluginAvailability.Unknown },
        val probe: ((String) -> Unit)? = null,
    ) {
        val opened = mutableListOf<String>()
        val steps = mutableListOf<CanvasHistory.Step.Documents>()
        val recorder = object : CanvasDocumentRecorder {
            override suspend fun recording(label: String, block: suspend () -> Unit) = block()

            override fun record(step: CanvasHistory.Step.Documents) {
                steps += step
            }
        }
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
    }

    /** The layer over [session]'s plugin elements as a workspace shows it, or over [elements] when given. */
    private fun ComposeUiTest.show(session: CanvasSession, assets: AssetStore?, host: Host, elements: List<CanvasPluginElement>? = null) {
        setContent {
            CompositionLocalProvider(
                LocalNoteCompositionProbe provides host.probe,
                LocalUriHandler provides host.uriHandler,
                LocalCanvasDocumentRecorder provides host.recorder,
                LocalPluginAvailability provides host.availability,
                LocalPluginElementRenderers provides host.renderers,
            ) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        val doc by session.document.collectAsState()
                        val fromScene = rememberPluginBoard(session, doc, assets)
                        val board = elements?.let { PluginBoard(session, it, fromScene.snapshots) } ?: fromScene
                        // Read here, as the workspace reads its state: every pan or zoom calls the layer
                        // again with a callback that is a new object each time.
                        val vp = host.viewport.value
                        CanvasPluginLayer(
                            board = board,
                            viewport = vp,
                            selection = PluginBoardSelection(host.selected.value, onSelect = { id -> if (vp.scale > 0f) host.selected.value = id }),
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun CanvasSession.element(id: String): CanvasPluginElement =
        CanvasOpProjector.pluginElementsOf(sceneJsonOrEmpty()).single { it.id == id }

    @Test
    fun theFallbackCardShowsTitleSnapshotStatusAndBadges() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val fixture = PluginCardFixtures.board()
        show(fixture.session, fixture.assets, Host(availability = fixture.availability))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CanvasPluginTestTags.snapshot("pe-render")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Nightly render").assertExists()
        onNodeWithText("Example · running 42%").assertExists()
        onNodeWithTag(CanvasPluginTestTags.status("pe-render")).assertExists()
        onNodeWithText("Running").assertExists()
        onNodeWithContentDescription("Snapshot of Nightly render").assertExists()
        onAllNodesWithTag(CanvasPluginTestTags.badge("pe-render", PluginCardBadge.NOT_INSTALLED)).assertCountEquals(0)

        onNodeWithTag(CanvasPluginTestTags.placeholder("pe-frame")).assertExists()
        onNodeWithTag(CanvasPluginTestTags.badge("pe-frame", PluginCardBadge.NOT_INSTALLED)).assertExists()
        onNodeWithTag(CanvasPluginTestTags.badge("pe-frame", PluginCardBadge.OFFLINE)).assertExists()
        onNodeWithText("Not installed").assertExists()

        onNodeWithTag(CanvasPluginTestTags.skeleton("pe-report")).assertExists()
        onNodeWithTag(CanvasPluginTestTags.badge("pe-report", PluginCardBadge.NEWER_VERSION)).assertExists()
        onNodeWithText("Failed").assertExists()
        onAllNodesWithTag(CanvasPluginTestTags.open("pe-report")).assertCountEquals(0)

        val card = onNodeWithTag(CanvasPluginTestTags.card("pe-render")).fetchSemanticsNode()
        assertEquals("Nightly render, plugin element", card.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.single())
    }

    @Test
    fun openFollowsTheLinkThroughThePlatformHandlerAndAsAnAccessibilityAction() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val fixture = PluginCardFixtures.board()
        val host = Host()
        show(fixture.session, fixture.assets, host)

        onNodeWithTag(CanvasPluginTestTags.open("pe-render")).performClick()
        waitForIdle()
        assertEquals(listOf("https://example.test/jobs/3d7c2e3e"), host.opened)

        val actions = onNodeWithTag(CanvasPluginTestTags.card("pe-frame")).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
        val open = assertNotNull(actions).single()
        assertEquals("Open Checkout flow", open.label)
        open.action()
        assertEquals("meridian:plugin/letta.example/open?ref=frame-17", host.opened.last())
        assertNull(onNodeWithTag(CanvasPluginTestTags.card("pe-report")).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions))
    }

    @Test
    fun aDragOnTheHandleBarIsWrittenAsOneUserFrameOp() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val fixture = PluginCardFixtures.board()
        val host = Host()
        show(fixture.session, fixture.assets, host)
        val before = fixture.session.element("pe-render")
        val opsBefore = runBlocking { fixture.session.opLog.getOps(fixture.session.canvasId) }.size

        onNodeWithTag(CanvasPluginTestTags.handle("pe-render"), useUnmergedTree = true).performTouchInput {
            swipe(start = center, end = center + Offset(120f, 60f), durationMillis = 300)
        }
        waitUntil(timeoutMillis = 5_000) { fixture.session.element("pe-render").frame != before.frame }

        val after = fixture.session.element("pe-render")
        val frame = assertNotNull(after.frame)
        assertTrue(frame.x > 40f + 80f && frame.y > 40f + 30f, "moved by the drag: $frame")
        assertEquals(320f, frame.width)
        assertEquals(CanvasGeometryOwner.USER, after.owner)
        assertEquals(before.props, after.props)
        assertEquals(before.fallback, after.fallback)
        assertEquals(before.snapshot, after.snapshot)
        val ops = runBlocking { fixture.session.opLog.getOps(fixture.session.canvasId) }
        assertEquals(opsBefore + 1, ops.size, "one op for the whole drag")
        val move = ops.last() as CanvasOp.SetPluginElementOp
        assertEquals(CanvasSession.LOCAL_USER_ACTOR_ID, move.actorId)
        assertEquals(listOf(null, null, null, null, null, null, null), listOf(move.elementType, move.v, move.ref, move.props, move.snapshot, move.fallback, move.meta))

        // One step in the board's history, and undoing it puts the element back.
        val step = host.steps.single()
        runBlocking { fixture.session.applyLocalStamped(step.undo) }
        assertEquals(before.frame, fixture.session.element("pe-render").frame)
    }

    @Test
    fun aPressSelectsAndTheSelectedElementResizesFromItsChrome() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val fixture = PluginCardFixtures.board()
        val host = Host()
        show(fixture.session, fixture.assets, host)
        onAllNodesWithContentDescription("Selection chrome").assertCountEquals(0)

        onNodeWithTag(CanvasPluginTestTags.element("pe-frame")).performClick()
        waitForIdle()
        assertEquals("pe-frame", host.selected.value)
        onAllNodesWithContentDescription("Selection chrome").assertCountEquals(1)
        onNodeWithTag(CanvasPluginTestTags.element("pe-render")).performClick()
        waitForIdle()
        assertEquals("pe-render", host.selected.value)

        onNodeWithContentDescription("Resize BottomRight").performTouchInput {
            swipe(start = center, end = center + Offset(100f, 80f), durationMillis = 300)
        }
        waitUntil(timeoutMillis = 5_000) { fixture.session.element("pe-render").frame?.width != 320f }
        val frame = assertNotNull(fixture.session.element("pe-render").frame)
        assertTrue(frame.width > 320f + 60f && frame.height > 300f + 40f, "resized: $frame")
        assertEquals(40f, frame.x)
        assertEquals(CanvasGeometryOwner.USER, fixture.session.element("pe-render").owner)
    }

    @Test
    fun anElementOfATypeWithNoRendererIsDrawnByTheFallbackCard() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = PluginCardFixtures.session()
        PluginCardFixtures.place(session, PluginCardFixtures.element("live", type = "ext:acme.charts/bar"))
        PluginCardFixtures.place(session, PluginCardFixtures.element("other", type = "ext:unknown.plugin/thing").copy(frame = CanvasDocumentFrame(400f, 40f, 300f, 240f)))
        val renderers = PluginElementRenderers.Core.register("ext:acme.charts/") { view, _ -> Text("live chart ${view.element.id}") }
        show(session, InMemoryAssetStore(), Host(renderers = renderers))

        onNodeWithText("live chart live").assertExists()
        onAllNodesWithTag(CanvasPluginTestTags.card("live")).assertCountEquals(0)
        onNodeWithTag(CanvasPluginTestTags.card("other")).assertExists()
        onNodeWithText("Card other").assertExists()
    }

    @Test
    fun aBrokenElementDoesNotBreakTheOthers() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val session = PluginCardFixtures.session()
        val assets = InMemoryAssetStore()
        val garbage = assets.put("image/png", byteArrayOf(1, 2, 3, 4, 5))
        val elements = listOf(
            PluginCardFixtures.element("broken-renderer", type = "ext:broken.plugin/view"),
            PluginCardFixtures.element("broken-frame").copy(frame = CanvasDocumentFrame(Float.NaN, 400f, Float.POSITIVE_INFINITY, -5f)),
            PluginCardFixtures.element("broken-snapshot").copy(
                frame = CanvasDocumentFrame(400f, 400f, 300f, 240f),
                snapshot = CanvasPluginSnapshot(assetRef = garbage.ref),
            ),
            PluginCardFixtures.element("broken-props", props = JsonObject(mapOf("status" to JsonPrimitive(42), "progress" to JsonPrimitive("lots"))))
                .copy(frame = CanvasDocumentFrame(800f, 400f, 300f, 240f)),
            PluginCardFixtures.element("fine").copy(frame = CanvasDocumentFrame(800f, 40f, 300f, 240f)),
        )
        val renderers = PluginElementRenderers.Core.register("ext:broken.plugin/") { view, chrome ->
            LaunchedEffect(Unit) { chrome.fail("cannot load ${view.element.id}") }
            Text("about to fail")
        }
        show(session, assets, Host(renderers = renderers), elements = elements)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CanvasPluginTestTags.placeholder("broken-snapshot")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(CanvasPluginTestTags.badge("broken-renderer", PluginCardBadge.FAULT)).assertExists()
        onNodeWithText("Live view failed").assertExists()
        val frame = onNodeWithTag(CanvasPluginTestTags.element("broken-frame")).fetchSemanticsNode().size
        assertEquals(PluginElementFrames.DEFAULT_WIDTH, frame.width.toFloat(), 1f)
        assertEquals(PluginElementFrames.DEFAULT_HEIGHT, frame.height.toFloat(), 1f)
        onAllNodesWithTag(CanvasPluginTestTags.status("broken-props")).assertCountEquals(0)
        onNodeWithTag(CanvasPluginTestTags.card("fine")).assertExists()
        onNodeWithText("Card fine").assertExists()
    }

    @Test
    fun aMissingSnapshotShowsASkeletonUntilTheStoreHasIt() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val bytes = PluginCardFixtures.picturePng()
        val ref = InMemoryAssetStore().put("image/png", bytes).ref
        val session = PluginCardFixtures.session()
        PluginCardFixtures.place(session, PluginCardFixtures.element("late").copy(snapshot = CanvasPluginSnapshot(assetRef = ref, mediaType = "image/png")))
        val assets = InMemoryAssetStore()
        show(session, assets, Host())
        onNodeWithTag(CanvasPluginTestTags.skeleton("late")).assertExists()

        assets.put("image/png", bytes)
        waitUntil(timeoutMillis = 10_000) {
            mainClock.advanceTimeBy(PluginSnapshotHydration.MAX_RETRY_MILLIS)
            onAllNodesWithTag(CanvasPluginTestTags.snapshot("late")).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithTag(CanvasPluginTestTags.skeleton("late")).assertCountEquals(0)
    }

    /**
     * A pan and zoom re-place and re-scale the elements in layout and draw only: no element composes
     * again, not even the selected one (its chrome, sized by the zoom, is its own scope), though the
     * host calls the layer again on every frame with a new callback.
     */
    @Test
    fun zoomingAndPanningRecomposesNoElement() = runDesktopComposeUiTest(width = BOARD, height = BOARD) {
        val fixture = PluginCardFixtures.board()
        val compositions = HashMap<String, Int>()
        val host = Host(selected = mutableStateOf("pe-render"), probe = { id -> compositions[id] = (compositions[id] ?: 0) + 1 })
        show(fixture.session, fixture.assets, host)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CanvasPluginTestTags.snapshot("pe-render")).fetchSemanticsNodes().isNotEmpty() }
        val before = HashMap(compositions)
        val atOne = onNodeWithTag(CanvasPluginTestTags.element("pe-frame")).fetchSemanticsNode().boundsInRoot
        assertTrue(before.keys.containsAll(listOf("pe-render", "pe-frame", "pe-report")), "every element composed: $before")

        var scale = 1f
        repeat(FRAMES) { frame ->
            scale *= if (frame < FRAMES / 2) 1.04f else 0.95f
            host.viewport.value = Viewport(offset = Offset(frame * 3f, frame * -2f), scale = scale)
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
        assertEquals(before, compositions, "a zoom recomposed an element")

        val vp = host.viewport.value
        val now = onNodeWithTag(CanvasPluginTestTags.element("pe-frame")).fetchSemanticsNode().boundsInRoot
        assertEquals(atOne.width * vp.scale, now.width, 1f)
        assertEquals(vp.worldToScreen(atOne.topLeft).x, now.left, 1.5f)
    }

    private companion object {
        const val BOARD = 1_200
        const val FRAMES = 24
    }
}
