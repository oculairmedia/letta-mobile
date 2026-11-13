package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.mount.lookup.HostCanvasMount
import com.letta.mobile.data.canvas.mount.lookup.HostCanvasMountLookup
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.PLUGIN_ID
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.catalog
import com.letta.mobile.data.plugin.host.PluginHostRig.Companion.place
import com.letta.mobile.plugin.api.PluginEmit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Documents CURRENT behaviour, pending an owner decision: plugin placement inherits the canvas mount grant of the
 * call it is attributed to. A plugin called by agent A in conversation A, which is mounted on agent B's board, places
 * on B's board, and so does that plugin's later background emit (attributed to the last caller once no call is in
 * flight). The grant still follows the live mount: once the mount is gone, the same emit lands on A's own board.
 * If the owner decides placement must not inherit the grant, this test changes with that decision.
 */
class PluginPlacementMountGrantTest {
    private val store = InMemoryCanvasRelayStore()
    private val directory = InMemoryHostCanvasDirectory()

    @Volatile private var mounted: CanvasId? = null

    private val backend = HostCanvasBackend(
        CanvasRelayHost(store, hostId = { "host-1" }),
        store,
        directory,
        kinds = catalog,
        mounts = HostCanvasMountLookup { _, conversation ->
            mounted?.takeIf { conversation == CONVERSATION_A }?.let { HostCanvasMount.Mounted(it.value) } ?: HostCanvasMount.None
        },
    )
    private val board = CallerBoard(backend)
    private var nextId = 0
    private val placement = PlacementCompiler(PLUGIN_ID, catalog, backend, board, PluginRefusals(), newIdSuffix = { "e${++nextId}" })

    @Test
    fun aPluginCallFromAMountedConversationPlacesOnTheMountedBoardIncludingLaterBackgroundEmits() = runTest {
        val othersBoard = backend.create(HostCanvasCaller(AGENT_B, CONVERSATION_B), "Agent B's board")
        mounted = othersBoard.id

        val call = board.begin(HostCanvasCaller(AGENT_A, CONVERSATION_A))
        placement.emit(PluginEmit(place = listOf(place()), callId = call.value))
        board.end(call)
        assertEquals(1, placedOn(othersBoard), "the call's emit lands on the board mounted for its conversation")

        // Background work after the call ended: an untagged emit is attributed to the last caller.
        placement.emit(PluginEmit(place = listOf(place())))
        assertEquals(2, placedOn(othersBoard), "a background emit inherits the same mount grant")

        // The grant follows the live mount: unmounted, the same late emit lands on agent A's own board.
        mounted = null
        placement.emit(PluginEmit(place = listOf(place()), callId = call.value))
        assertEquals(2, placedOn(othersBoard))
        val own = checkNotNull(directory.forConversation(com.letta.mobile.data.model.ConversationId(CONVERSATION_A)))
        assertTrue(placedOn(own) >= 1)
    }

    private suspend fun placedOn(entry: HostCanvasEntry): Int =
        store.readAfter(entry.topic, 0L).map { it.op }.filterIsInstance<CanvasOp.BatchOp>().size

    private companion object {
        const val AGENT_A = "agent-a"
        const val AGENT_B = "agent-b"
        const val CONVERSATION_A = "conv-a"
        const val CONVERSATION_B = "conv-b"
    }
}
