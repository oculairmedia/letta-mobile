package com.letta.mobile.ui.canvas.plugin

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.plugin.CanvasPluginSnapshot
import com.letta.mobile.data.storage.InMemoryAssetStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The plain parts of the plugin layer (letta-mobile-s416w.4): status, links, frames, the registry, edits and hydration. */
class PluginElementModelTest {
    private fun props(vararg pairs: Pair<String, JsonPrimitive>) = JsonObject(pairs.toMap())

    @Test
    fun theStatusReadsOnlyAStringStatusAndAProgressBetweenZeroAndOne() {
        assertEquals(PluginElementStatus("running", 0.42f), PluginElementStatus.of(props("status" to JsonPrimitive("running"), "progress" to JsonPrimitive(0.42))))
        assertEquals(PluginStatusTone.RUNNING, PluginElementStatus.of(props("status" to JsonPrimitive("Running")))?.tone)
        assertEquals(PluginStatusTone.OTHER, PluginElementStatus.of(props("status" to JsonPrimitive("queued")))?.tone)
        assertEquals(1f, PluginElementStatus.of(props("progress" to JsonPrimitive(7)))?.progress)
        assertNull(PluginElementStatus.of(props("status" to JsonPrimitive(3), "progress" to JsonPrimitive("half"))))
        assertNull(PluginElementStatus.of(props("label" to JsonPrimitive("Nightly"))))
        assertNull(PluginElementStatus.of(props("status" to JsonPrimitive("  "))))
    }

    @Test
    fun onlyWebAndMeridianLinksAreOpened() {
        assertEquals("https://example.test/a", PluginLinks.openable(" https://example.test/a "))
        assertEquals("meridian:plugin/x", PluginLinks.openable("meridian:plugin/x"))
        assertNull(PluginLinks.openable("javascript:alert(1)"))
        assertNull(PluginLinks.openable("file:///etc/passwd"))
        assertNull(PluginLinks.openable("no scheme"))
        assertNull(PluginLinks.openable(null))
    }

    @Test
    fun aFrameOutOfRangeIsBroughtBackIntoIt() {
        assertEquals(CanvasDocumentFrame(0f, 0f, 320f, 240f), PluginElementFrames.sanitized(null))
        assertEquals(CanvasDocumentFrame(0f, 5f, 320f, 240f), PluginElementFrames.sanitized(CanvasDocumentFrame(Float.NaN, 5f, Float.POSITIVE_INFINITY, -1f)))
        assertEquals(CanvasDocumentFrame(1f, 2f, 48f, 16_384f), PluginElementFrames.sanitized(CanvasDocumentFrame(1f, 2f, 3f, 1e9f)))
    }

    @Test
    fun theLongestRegisteredPrefixWinsAndAFaultedElementFallsBack() {
        val plugin: PluginElementRenderer = { _, _ -> }
        val kind: PluginElementRenderer = { _, _ -> }
        val renderers = PluginElementRenderers.Core.register("ext:acme.charts/", plugin).register("ext:acme.charts/bar", kind)
        val bar = PluginElementView(PluginCardFixtures.element("a", type = "ext:acme.charts/bar"))
        assertSame(kind, renderers.rendererFor(bar))
        assertSame(plugin, renderers.rendererFor(PluginElementView(PluginCardFixtures.element("b", type = "ext:acme.charts/pie"))))
        assertSame(renderers.fallback, renderers.rendererFor(PluginElementView(PluginCardFixtures.element("c", type = "ext:other/x"))))
        assertSame(renderers.fallback, renderers.rendererFor(bar.copy(faulted = true)))
        assertTrue(PluginElementRenderers.Core.prefixes.isEmpty(), "core registers only the fallback")
        assertFailsWith<IllegalArgumentException> { PluginElementRenderers.Core.register("acme", plugin) }
    }

    @Test
    fun badgesFollowWhatTheBoardKnows() {
        val element = PluginCardFixtures.element("a")
        assertEquals(emptyList(), badgesOf(PluginElementView(element)))
        assertEquals(
            listOf(PluginCardBadge.NOT_INSTALLED, PluginCardBadge.OFFLINE, PluginCardBadge.FAULT),
            badgesOf(PluginElementView(element, PluginAvailability(PluginInstallState.NOT_INSTALLED, offline = true), faulted = true)),
        )
        assertEquals(listOf(PluginCardBadge.NEWER_VERSION), badgesOf(PluginElementView(element, PluginAvailability(PluginInstallState.NEWER_VERSION))))
    }

    @Test
    fun aMoveCarriesOnlyTheFrameOwnedByThePerson() {
        val op = PluginElementEdits.moveOp("pe", CanvasDocumentFrame(1f, 2f, 3f, 4f))
        assertEquals(CanvasGeometryOwner.USER, op.owner)
        assertEquals(CanvasSession.LOCAL_USER_ACTOR_ID, op.actorId)
        assertEquals(listOf(null, null, null, null, null, null, null), listOf(op.elementType, op.v, op.ref, op.props, op.snapshot, op.fallback, op.meta))
    }

    @Test
    fun aRemovalRemovesTheElementAndIgnoresOneThatIsNotThere() = runBlocking {
        val session = PluginCardFixtures.session()
        PluginCardFixtures.place(session, PluginCardFixtures.element("a"))
        PluginElementEdits.remove(session, "missing")
        assertEquals(listOf("a"), PluginElementEdits.elementsOf(session).map { it.id })
        PluginElementEdits.remove(session, "a")
        assertTrue(PluginElementEdits.elementsOf(session).isEmpty())
    }

    @Test
    fun aSnapshotIsReadFromTheStoreElseFetchedAndKept() = runBlocking {
        val bytes = PluginCardFixtures.picturePng()
        val store = InMemoryAssetStore()
        val ref = InMemoryAssetStore().put("image/png", bytes).ref
        val snapshot = CanvasPluginSnapshot(assetRef = ref, mediaType = "image/png")

        assertNull(PluginSnapshotHydration.bytesOf(snapshot, PluginSnapshotSources(store)))
        val fetched = PluginSnapshotHydration.bytesOf(snapshot, PluginSnapshotSources(store) { if (it == ref) bytes else null })
        assertTrue(fetched.contentEquals(bytes))
        assertTrue(store.has(ref), "fetched bytes are kept in the store")
        assertTrue(PluginSnapshotHydration.bytesOf(snapshot, PluginSnapshotSources(store) { error("not asked") }).contentEquals(bytes))
        assertNull(PluginSnapshotHydration.bytesOf(snapshot, PluginSnapshotSources(null) { throw IllegalStateException("offline") }))

        assertIs<PluginSnapshotImage.Ready>(PluginSnapshotHydration.decode(bytes))
        assertEquals(PluginSnapshotImage.Unreadable, PluginSnapshotHydration.decode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun theFixtureDecodes() {
        val cards = PluginCardFixtures.cards()
        assertEquals(listOf("pe-render", "pe-frame", "pe-report"), cards.map { it.element.id })
        assertEquals(PluginInstallState.NOT_INSTALLED, cards[1].availability.install)
    }
}
