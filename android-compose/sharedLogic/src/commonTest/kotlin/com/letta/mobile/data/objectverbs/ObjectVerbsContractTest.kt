package com.letta.mobile.data.objectverbs

import com.letta.mobile.data.objectverbs.refs.agent
import com.letta.mobile.data.objectverbs.refs.conversation
import com.letta.mobile.data.objectverbs.refs.project
import com.letta.mobile.data.objectverbs.refs.canvas
import com.letta.mobile.data.objectverbs.refs.shortcut
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.projects.ProjectKey
import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ObjectVerbsContractTest {
    private val fixture = LettaJson.wire.decodeFromString<List<ObjectVerbSnapshot>>(objectVerbsContractText)
    private val kinds = setOf("agent", "conversation", "project", "canvas", "shortcut")
    private val verbs = setOf("core.pin", "core.archive", "core.rename", "core.open")

    @Test
    fun installedRegistryAgreesWithTheSharedFixturePinState() {
        val pins = com.letta.mobile.data.pins.DevicePinStore(com.letta.mobile.data.storage.InMemorySecureSettingsStore())
        val registry = com.letta.mobile.data.objectverbs.registry.VerbRegistry(
            com.letta.mobile.data.objectverbs.registry.CoreVerbProvider(pins, { "fixture" }) { ref -> fixture.any { it.objectRef == ref } },
        )
        for (expected in fixture.take(2)) {
            pins.setPinned(com.letta.mobile.data.pins.ScopedObjectRef("fixture", expected.objectRef), expected.facets.pinned == true)
            val actual = assertNotNull(registry.describe(expected.objectRef, expected.context))
            assertEquals(expected.facets.pinned, actual.facets.pinned)
            assertEquals(expected.facets.pinOrder, actual.facets.pinOrder)
            assertEquals(expected.verbs.single().toggle, actual.verbs.single().toggle)
            assertEquals(expected.verbs.single().icon, actual.verbs.single().icon)
        }
    }

    @Test
    fun pinIsOneVerbWithCurrentStateAndIndependentFacets() {
        val pinned = fixture[0]
        val unpinned = fixture[1]
        assertEquals(ObjectRef.agent(AgentId("scout")), pinned.objectRef)
        assertEquals("core.pin", pinned.verbs.single().id)
        assertEquals("Unpin", pinned.verbs.single().label)
        assertEquals(true, pinned.verbs.single().toggle?.pinned)
        assertEquals(false, unpinned.verbs.single().toggle?.pinned)
        assertEquals("Pin", unpinned.verbs.single().label)
        assertEquals(true, pinned.facets.pinned)
        assertEquals(0, pinned.facets.pinOrder)
        assertEquals(false, unpinned.facets.pinned)
        assertNull(unpinned.facets.pinOrder)
        assertEquals(true, pinned.facets.running)
        assertEquals(true, pinned.facets.needsAttention)
        assertEquals("utility", pinned.facets.kindTag)
        assertEquals("rail", pinned.context.lens)
        assertEquals("core", pinned.contribution.owner)
    }

    @Test
    fun disabledArchiveKeepsItsReasonWhileHiddenAndUnknownEntriesRenderNothing() {
        val conversation = fixture[2]
        // Unknown descriptors decode even with a payload this version does not understand.
        assertEquals(listOf("core.archive", "future.teleport", "core.open"), conversation.verbs.map { it.id })
        val visible = assertNotNull(conversation.forRendering(kinds, verbs))
        assertEquals(listOf("core.archive"), visible.verbs.map { it.id })
        val archive = visible.verbs.single()
        assertEquals("core.archive", archive.id)
        assertEquals("disabled", archive.availability.state)
        assertEquals("Wait for the running turn to finish", archive.availability.reason)
        assertEquals(false, archive.toggle?.archived)
        assertEquals("conversation", visible.objectRef.kind)
        assertEquals(true, visible.facets.needsAttention)
    }

    @Test
    fun unknownKindAndIncompatibleVersionCannotExposeFacetsOrEvenKnownVerbs() {
        assertEquals("future.robot", fixture[3].objectRef.kind)
        assertNull(fixture[3].forRendering(kinds, verbs))
        assertNull(fixture[0].copy(contractVersion = 2).forRendering(kinds, verbs))
        assertNull(fixture[0].copy(contribution = fixture[0].contribution.copy(contractVersion = 2)).forRendering(kinds, verbs))
        assertTrue(assertNotNull(fixture[0].forRendering(kinds, emptySet())).verbs.isEmpty())
    }

    @Test
    fun futureAvailabilityAndDisabledWithoutReasonAreNotActionable() {
        val snapshot = fixture[0]
        listOf(VerbAvailability("future"), VerbAvailability("disabled"), VerbAvailability("disabled", " ")).forEach { availability ->
            val changed = snapshot.copy(verbs = listOf(snapshot.verbs.single().copy(availability = availability)))
            assertTrue(assertNotNull(changed.forRendering(kinds, verbs)).verbs.isEmpty())
        }
        assertFalse(assertNotNull(snapshot.forRendering(kinds, verbs)).verbs.isEmpty())
    }

    @Test
    fun typedFactoriesReuseExistingIdsAndCanvasWireIdStaysFlat() {
        assertEquals(ObjectRef("conversation", "chat"), ObjectRef.conversation(ConversationId("chat")))
        assertEquals(ObjectRef("project", "workspace"), ObjectRef.project(ProjectId("workspace")))
        assertEquals(ObjectRef("project", "workspace"), ObjectRef.project(ProjectKey("workspace")))
        assertEquals(ObjectRef("shortcut", "TOOLS"), ObjectRef.shortcut(HomeShortcut.TOOLS))
        val canvas = ObjectRef.canvas(CanvasId("board"))
        assertEquals("{\"kind\":\"canvas\",\"id\":\"board\"}", LettaJson.wire.encodeToString(canvas))
        assertEquals(canvas, LettaJson.wire.decodeFromString<ObjectRef>(LettaJson.wire.encodeToString(canvas)))
    }

    @Test
    fun invocationAndTypedOutcomesDecodeWithoutExecutingAnything() {
        val request = LettaJson.wire.decodeFromString<VerbInvocation>(
            """{"objectRef":{"kind":"agent","id":"scout"},"verbId":"core.pin","context":{"lens":"rail"},"input":{"pinned":false}}""",
        )
        assertEquals(fixture[0].objectRef, request.objectRef)
        assertEquals("core.pin", request.verbId)
        assertNotNull(request.input)
        val outcomes: List<VerbOutcome> = listOf(
            VerbOutcome.Updated(fixture[0]),
            VerbOutcome.Open(request.objectRef),
            VerbOutcome.Cancelled,
            VerbOutcome.Unavailable("Provider removed"),
            VerbOutcome.InvalidInput(mapOf("name" to "Required")),
            VerbOutcome.Failed("Write failed", retryable = true),
        )
        outcomes.forEach { outcome ->
            assertEquals(outcome, LettaJson.wire.decodeFromString<VerbOutcome>(LettaJson.wire.encodeToString(outcome)))
        }
    }
}
