package com.letta.mobile.data.canvas

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasOpsTest {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    @Test
    fun replaceSceneOpRoundTrip() {
        val op = CanvasOp.ReplaceSceneOp(
            opId = "op-1",
            actorId = "agent-42",
            lamport = 100L,
            sceneJson = """{"bgColor":-1,"elements":[]}""",
        )
        val serialized = json.encodeToString<CanvasOp>(op)
        assertTrue(serialized.contains("\"type\":\"replace_scene\""))
        val deserialized = json.decodeFromString<CanvasOp>(serialized)
        assertEquals(op, deserialized)
    }

    @Test
    fun allOpsPolymorphicRoundTrip() {
        val ops: List<CanvasOp> = listOf(
            CanvasOp.ReplaceSceneOp(
                opId = "op-1",
                actorId = "agent-1",
                lamport = 1L,
                sceneJson = "{}",
            ),
            CanvasOp.AddElementOp(
                opId = "op-2",
                actorId = "user-1",
                lamport = 2L,
                elementId = "elem-1",
                elementJson = """{"id":"elem-1","type":"path"}""",
            ),
            CanvasOp.UpdateElementOp(
                opId = "op-3",
                actorId = "user-1",
                lamport = 3L,
                elementId = "elem-1",
                elementJson = """{"id":"elem-1","type":"path","color":-16777216}""",
            ),
            CanvasOp.RemoveElementOp(
                opId = "op-4",
                actorId = "agent-1",
                lamport = 4L,
                elementId = "elem-1",
            ),
            CanvasOp.SetBackgroundOp(
                opId = "op-5",
                actorId = "user-1",
                lamport = 5L,
                colorHex = "#FFFFFF",
            ),
        )

        for (op in ops) {
            val encoded = json.encodeToString<CanvasOp>(op)
            val decoded = json.decodeFromString<CanvasOp>(encoded)
            assertEquals(op, decoded)
        }
    }

    @Test
    fun batchOpRoundTrip() {
        val innerOps = listOf(
            CanvasOp.SetBackgroundOp(
                opId = "op-1",
                actorId = "agent-1",
                lamport = 10L,
                colorHex = "#000000",
            ),
            CanvasOp.AddElementOp(
                opId = "op-2",
                actorId = "agent-1",
                lamport = 11L,
                elementId = "elem-100",
                elementJson = """{"shape":"rect"}""",
            ),
        )
        val batch = CanvasOp.BatchOp(
            opId = "batch-1",
            actorId = "agent-1",
            lamport = 12L,
            ops = innerOps,
        )

        val encoded = json.encodeToString<CanvasOp>(batch)
        assertTrue(encoded.contains("\"type\":\"batch\""))
        val decoded = json.decodeFromString<CanvasOp>(encoded)
        assertEquals(batch, decoded)
    }

    @Test
    fun toolDtosRoundTrip() {
        val createArgs = CanvasCreateArgs(title = "My Canvas", conversationId = "conv-1", agentId = "agent-1")
        val createResult = CanvasCreateResult(canvasId = "canvas-1")
        assertEquals(createArgs, json.decodeFromString<CanvasCreateArgs>(json.encodeToString(createArgs)))
        assertEquals(createResult, json.decodeFromString<CanvasCreateResult>(json.encodeToString(createResult)))

        val getArgs = CanvasGetSceneArgs(canvasId = "canvas-1")
        val getResult = CanvasGetSceneResult(sceneJson = "{}", revision = 5L)
        assertEquals(getArgs, json.decodeFromString<CanvasGetSceneArgs>(json.encodeToString(getArgs)))
        assertEquals(getResult, json.decodeFromString<CanvasGetSceneResult>(json.encodeToString(getResult)))

        val replaceArgs = CanvasReplaceSceneArgs(canvasId = "canvas-1", sceneJson = "{\"elements\":[]}")
        val replaceResult = CanvasReplaceSceneResult(ok = true, revision = 6L)
        assertEquals(replaceArgs, json.decodeFromString<CanvasReplaceSceneArgs>(json.encodeToString(replaceArgs)))
        assertEquals(replaceResult, json.decodeFromString<CanvasReplaceSceneResult>(json.encodeToString(replaceResult)))

        val applyArgs = CanvasApplyOpsArgs(
            canvasId = "canvas-1",
            ops = listOf(
                CanvasOp.SetBackgroundOp("op-1", "user-1", 1L, "#FFF"),
            ),
        )
        val applyResult = CanvasApplyOpsResult(ok = true, revision = 7L)
        assertEquals(applyArgs, json.decodeFromString<CanvasApplyOpsArgs>(json.encodeToString(applyArgs)))
        assertEquals(applyResult, json.decodeFromString<CanvasApplyOpsResult>(json.encodeToString(applyResult)))

        val exportArgs = CanvasExportSvgArgs(canvasId = "canvas-1")
        val exportResult = CanvasExportSvgResult(svg = "<svg></svg>")
        assertEquals(exportArgs, json.decodeFromString<CanvasExportSvgArgs>(json.encodeToString(exportArgs)))
        assertEquals(exportResult, json.decodeFromString<CanvasExportSvgResult>(json.encodeToString(exportResult)))

        val listArgs = CanvasListArgs(conversationId = "conv-1", agentId = null)
        val listResult = CanvasListResult(ids = listOf("canvas-1", "canvas-2"))
        assertEquals(listArgs, json.decodeFromString<CanvasListArgs>(json.encodeToString(listArgs)))
        assertEquals(listResult, json.decodeFromString<CanvasListResult>(json.encodeToString(listResult)))
    }
}
