package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The board's background - its colour, and its pattern's kind, colour and scale - reaches the other app. */
class CanvasBackgroundSyncTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Keeps what the session hands the relay. */
    private class Capturing : CanvasSyncTransport {
        val published = mutableListOf<CanvasOp>()
        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) { published += op }
        override fun subscribe(canvasId: CanvasId): kotlinx.coroutines.flow.Flow<CanvasOp> = kotlinx.coroutines.flow.emptyFlow()
    }

    private suspend fun session(id: String, transport: CanvasSyncTransport? = null) =
        CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "t", canvasId = CanvasId(id), syncTransport = transport))

    /** What the relay does to an op between two apps: encode it on one side, decode it on the other. */
    private fun overTheWire(op: CanvasOp): CanvasOp {
        val text = CanvasRelayProtocol.encode(CanvasRelayMessage.Op(topic = "canvas:c", cursor = 1, origin = "peer", op = op))
        val decoded = CanvasRelayProtocol.decode(text)
        assertTrue(decoded is CanvasRelayDecoded.Message, "$decoded")
        return (decoded.message as CanvasRelayMessage.Op).op
    }

    @Test
    fun aPatternsKindColourAndScaleSurviveTheRelay() = runTest {
        val relay = Capturing()
        val sender = session("a", relay)
        val receiver = session("b")
        val dots = CanvasBackgroundPattern(CanvasBackgroundPattern.DOTS, spacing = 64f, colorHex = "#e5484d")
        sender.setBackgroundPattern(dots)
        val op = relay.published.single { it is CanvasOp.SetBackgroundPatternOp }
        receiver.applyRemote(overTheWire(op))
        assertEquals(dots, receiver.backgroundPattern())
    }

    @Test
    fun aColourChangedOnTheBoardSurvivesTheRelay() = runTest {
        val sender = session("a")
        val receiver = session("b")
        val ops = sender.applyLocalScene("""{"bgColor":"#112233ff","elements":[]}""")
        val colour = ops.single { it is CanvasOp.SetBackgroundOp }
        receiver.applyRemote(overTheWire(colour))
        val scene = json.parseToJsonElement(receiver.sceneJsonOrEmpty()).jsonObject
        assertEquals("#112233ff", scene["bgColor"]?.jsonPrimitive?.content)
    }
}
