package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.PLUGIN
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.TYPE
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.place
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.props
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpErrorCode
import com.letta.mobile.plugin.api.PlaceImage
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.SnapshotSource
import com.letta.mobile.plugin.api.UpdateElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** letta-mobile-cdgpr.5: a plugin places only its own kinds and touches only its own elements, through the one validator. */
class PlacementCompilerTest {
    private fun LcpCallException.reason(): String? = (error.data as? JsonObject)?.get("reason")?.jsonPrimitive?.content

    private suspend fun refused(rig: PluginHostRig, emit: PluginEmit): LcpCallException =
        assertFailsWith<LcpCallException> { rig.handlers.emit(emit) }

    @Test
    fun anOwnKindReachesTheBoardAsThePlugin() = runTest {
        val rig = PluginHostRig().apply { openBoard() }

        val receipt = rig.handlers.emit(PluginEmit(place = listOf(place())))

        assertEquals(listOf("p-letta.chart-e1"), receipt.placed)
        assertTrue(receipt.refused.isEmpty())
        val ops = rig.written()
        val batch = ops.filterIsInstance<CanvasOp.BatchOp>().single()
        assertEquals("plugin:$PLUGIN", batch.actorId)
        val set = ops.filterIsInstance<CanvasOp.SetPluginElementOp>().single()
        assertEquals("plugin:$PLUGIN", set.actorId)
        assertEquals(TYPE, set.elementType)
        assertEquals("p-letta.chart-e1", set.elementId)
    }

    @Test
    fun theFullTypeOfAnOwnKindIsAcceptedAndItsElementsUpdatedAndRemoved() = runTest {
        val rig = PluginHostRig().apply { openBoard() }
        val id = rig.handlers.emit(PluginEmit(place = listOf(place(kind = TYPE)))).placed.single()

        rig.handlers.emit(PluginEmit(update = listOf(UpdateElement(id, props = props("Costs")))))
        rig.handlers.emit(PluginEmit(remove = listOf(id)))

        val ops = rig.written()
        assertEquals(2, ops.filterIsInstance<CanvasOp.SetPluginElementOp>().size)
        assertEquals(id, ops.filterIsInstance<CanvasOp.RemovePluginElementOp>().single().elementId)
    }

    @Test
    fun aForeignKindRefusesTheWholeEmitAndWritesNothing() = runTest {
        val rig = PluginHostRig().apply { openBoard() }

        val error = refused(rig, PluginEmit(place = listOf(place(), place(kind = "ext:other.plugin/chart"))))

        assertEquals(LcpErrorCode.CAPABILITY_REFUSED, error.code)
        assertEquals("foreign_kind", error.reason())
        assertTrue(rig.written().isEmpty())
        assertEquals(1, rig.refusals.count(PLUGIN, PluginRefusal.FOREIGN_KIND))
    }

    @Test
    fun aKindNamedWithAPathIsForeign() = runTest {
        val rig = PluginHostRig().apply { openBoard() }

        assertEquals("foreign_kind", refused(rig, PluginEmit(place = listOf(place(kind = "other.plugin/chart")))).reason())
        assertTrue(rig.written().isEmpty())
    }

    @Test
    fun aForeignElementIdRefusesTheWholeEmitAndWritesNothing() = runTest {
        val rig = PluginHostRig().apply { openBoard() }

        val update = refused(rig, PluginEmit(place = listOf(place()), update = listOf(UpdateElement("note-1", props = props("x")))))
        val remove = refused(rig, PluginEmit(remove = listOf("p-other.plugin-e1")))
        val bare = refused(rig, PluginEmit(remove = listOf("p-letta.chart-")))

        listOf(update, remove, bare).forEach {
            assertEquals(LcpErrorCode.CAPABILITY_REFUSED, it.code)
            assertEquals("foreign_element", it.reason())
        }
        assertEquals(1, (update.error.data as JsonObject)["index"]?.jsonPrimitive?.content?.toInt())
        assertTrue(rig.written().isEmpty())
        assertEquals(3, rig.refusals.count(PLUGIN, PluginRefusal.FOREIGN_ELEMENT))
    }

    @Test
    fun oversizedPropsAreRefusedInTheReceiptAndNothingIsWritten() = runTest {
        val rig = PluginHostRig().apply { openBoard() }

        val receipt = rig.handlers.emit(PluginEmit(place = listOf(place(props = props("x".repeat(5000))))))

        assertTrue(receipt.placed.isEmpty())
        assertEquals(0, receipt.refused.single().index)
        assertTrue(receipt.refused.single().reason.startsWith("props_too_large:"), receipt.refused.single().reason)
        assertTrue(rig.written().isEmpty())
        assertEquals(1, rig.refusals.count(PLUGIN, PluginRefusal.PROPS_TOO_LARGE))
    }

    @Test
    fun propsAgainstTheKindsSchemaAreRefusedByTheValidatorAndNothingIsWritten() = runTest {
        val rig = PluginHostRig().apply { openBoard() }
        val wrong = buildJsonObject { put("title", "Sales"); put("color", "red") }

        val error = refused(rig, PluginEmit(place = listOf(place(props = wrong))))

        assertEquals(LcpErrorCode.INVALID_PARAMS, error.code)
        assertEquals("invalid_batch", error.reason())
        assertTrue(rig.written().isEmpty())
    }

    @Test
    fun unknownVersionsInlineBytesAndImagesAreRefusedPerEntryWhileTheRestIsPlaced() = runTest {
        val rig = PluginHostRig().apply { openBoard() }
        val emit = PluginEmit(
            place = listOf(place().copy(v = 2), place(), place().copy(snapshot = SnapshotSource.Bytes("image/png", byteArrayOf(1)))),
            placeImages = listOf(PlaceImage(SnapshotSource.Asset("sha256:" + "a".repeat(64)))),
        )

        val receipt = rig.handlers.emit(emit)

        assertEquals(listOf("p-letta.chart-e1"), receipt.placed)
        assertEquals(listOf(0, 2, 3), receipt.refused.map { it.index })
        assertEquals(
            listOf("unknown_kind", "inline_bytes_unsupported", "place_images_unsupported"),
            receipt.refused.map { it.reason.substringBefore(':') },
        )
        assertEquals(1, rig.written().filterIsInstance<CanvasOp.SetPluginElementOp>().size)
    }

    @Test
    fun noBoardRefusesTheEmit() = runTest {
        val rig = PluginHostRig()

        val error = refused(rig, PluginEmit(place = listOf(place())))

        assertEquals(LcpErrorCode.CAPABILITY_REFUSED, error.code)
        assertEquals("no_canvas", error.reason())
    }

    @Test
    fun entriesOnTwoBoardsAreRefused() = runTest {
        val rig = PluginHostRig().apply { openBoard() }

        val error = refused(rig, PluginEmit(place = listOf(place().copy(canvasId = "a"), place().copy(canvasId = "b"))))

        assertEquals(LcpErrorCode.INVALID_PARAMS, error.code)
        assertEquals("mixed_canvas", error.reason())
        assertTrue(rig.written().isEmpty())
    }
}
