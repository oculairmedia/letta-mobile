package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.HostCanvasAccess
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.plugin.PluginToolDefinition
import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.PLUGIN
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.PLUGIN_ID
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.place
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.props
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementEvent
import com.letta.mobile.plugin.api.PluginEmit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-cdgpr.16: a plugin serving two conversations at once places each call's elements on
 * that call's own board, never on the board of whichever agent called it last. Two invocations are
 * interleaved deterministically: A starts, B starts, then A emits (out of band, or in its answer)
 * while B is still in flight.
 */
class CallerBoardConcurrencyTest {
    private val rig = PluginHostRig()
    private val board = CallerBoard(rig.backend)
    private var nextId = 0
    private val placement = PlacementCompiler(PLUGIN_ID, PluginHostRig.catalog, rig.backend, board, rig.refusals, newIdSuffix = { "e${++nextId}" })
    private val handlers = HostLcpHandlers(PLUGIN_ID, placement, rig.assets, SecretScrubber(emptyList()), rig.limits, rig.quota, rig.refusals)
    private val tool = PluginToolDefinition(PLUGIN, "plot", "chart_plot", "plot", JsonObject(emptyMap()))

    @Test
    fun anOutOfBandEmitDuringTwoCallsNeverLandsOnTheLaterCallersBoard() = runTest {
        val driver = GatedDriver { call -> emitOutOfBand(call); ActionResult.Ok("placed") }
        val invoker = PluginToolInvoker { LivePlugin(driver, handlers, board) }

        interleave(invoker, driver)

        assertEquals(listOf("A"), titlesOn("conv-A"), "A's element is on A's board")
        assertEquals(listOf("B"), titlesOn("conv-B"), "B's element is on B's board, and only B's")
    }

    @Test
    fun anEmitInTheAnswerLandsOnItsOwnCallersBoard() = runTest {
        val driver = GatedDriver { call -> ActionResult.Ok("placed", emit = PluginEmit(place = listOf(place(props = props(call.title))))) }
        val invoker = PluginToolInvoker { LivePlugin(driver, handlers, board) }

        interleave(invoker, driver)

        assertEquals(listOf("A"), titlesOn("conv-A"))
        assertEquals(listOf("B"), titlesOn("conv-B"))
    }

    @Test
    fun anUntaggedEmitWhileTwoCallsAreInFlightIsRefusedNotGuessed() = runTest {
        val driver = GatedDriver { call ->
            handlers.emit(PluginEmit(place = listOf(place(props = props(call.title)))))
            ActionResult.Ok("placed")
        }
        val invoker = PluginToolInvoker { LivePlugin(driver, handlers, board) }

        val a = async { invoker.invoke(tool, input("A"), ExternalToolCaller("agent-1", "conv-A", "call-a")) }
        assertEquals("A", driver.started.receive())
        val b = async { invoker.invoke(tool, input("B"), ExternalToolCaller("agent-2", "conv-B", "call-b")) }
        assertEquals("B", driver.started.receive())
        driver.release("A")
        assertIs<ExternalToolResult.Error>(a.await(), "ambiguous: two calls in flight and the emit names neither")
        driver.release("B")
        assertIs<ExternalToolResult.Success>(b.await(), "alone in flight, B's untagged emit is B's")

        assertEquals(emptyList(), titlesOn("conv-A"))
        assertEquals(listOf("B"), titlesOn("conv-B"))
    }

    @Test
    fun aLateTaggedEmitFindsItsEndedCallAndAnUntaggedOneTheLastCaller() = runTest {
        val first = board.begin(HostCanvasCaller("agent-1", "conv-A"))
        board.end(first)
        val second = board.begin(HostCanvasCaller("agent-2", "conv-B"))
        board.end(second)

        handlers.emit(PluginEmit(place = listOf(place(props = props("late A"))), callId = first.value))
        handlers.emit(PluginEmit(place = listOf(place(props = props("background")))))

        assertEquals(listOf("late A"), titlesOn("conv-A"))
        assertEquals(listOf("background"), titlesOn("conv-B"))
    }

    /** A starts and waits; B starts and waits; A finishes while B is in flight; then B finishes. */
    private suspend fun kotlinx.coroutines.test.TestScope.interleave(invoker: PluginToolInvoker, driver: GatedDriver) {
        val a = async { invoker.invoke(tool, input("A"), ExternalToolCaller("agent-1", "conv-A", "call-a")) }
        assertEquals("A", driver.started.receive())
        val b = async { invoker.invoke(tool, input("B"), ExternalToolCaller("agent-2", "conv-B", "call-b")) }
        assertEquals("B", driver.started.receive())
        driver.release("A")
        assertIs<ExternalToolResult.Success>(a.await())
        driver.release("B")
        assertIs<ExternalToolResult.Success>(b.await())
    }

    private suspend fun emitOutOfBand(call: ActionCall) {
        handlers.emit(PluginEmit(place = listOf(place(props = props(call.title))), callId = call.context.callId))
    }

    /** The titles placed on [conversationId]'s board, read as the agent that owns it (A: agent-1, B: agent-2). */
    private suspend fun titlesOn(conversationId: String): List<String> {
        val owner = if (conversationId == "conv-A") "agent-1" else "agent-2"
        val access = rig.backend.ownConversation(HostCanvasCaller(owner, conversationId))
        val entry = assertIs<HostCanvasAccess.Granted>(access).entry
        return rig.store.readAfter(entry.topic, 0L).map { it.op }
            .flatMap { if (it is CanvasOp.BatchOp) it.ops else listOf(it) }
            .filterIsInstance<CanvasOp.SetPluginElementOp>()
            .mapNotNull { it.props?.get("title")?.jsonPrimitive?.content }
    }

    private fun input(title: String) = props(title)

    private val ActionCall.title: String get() = input.getValue("title").jsonPrimitive.content

    /** A plugin whose every call announces itself on [started], then waits to be [release]d before doing [work]. */
    private class GatedDriver(private val work: suspend (ActionCall) -> ActionResult) : PluginDriver {
        val started = Channel<String>(Channel.UNLIMITED)
        private val gates = mapOf("A" to CompletableDeferred<Unit>(), "B" to CompletableDeferred())

        fun release(title: String) = gates.getValue(title).complete(Unit)

        override val pluginId: String = PLUGIN
        override val state: StateFlow<PluginDriverState> = MutableStateFlow(PluginDriverState.Running(null))

        override suspend fun start(): PluginDriverState = state.value

        override suspend fun invoke(call: ActionCall): ActionResult {
            val title = call.input.getValue("title").jsonPrimitive.content
            started.send(title)
            gates.getValue(title).await()
            return try {
                work(call)
            } catch (e: LcpCallException) {
                ActionResult.Error("refused", e.message.orEmpty())
            }
        }

        override suspend fun elementEvent(event: ElementEvent) = Unit

        override suspend fun stop() = Unit
    }
}
