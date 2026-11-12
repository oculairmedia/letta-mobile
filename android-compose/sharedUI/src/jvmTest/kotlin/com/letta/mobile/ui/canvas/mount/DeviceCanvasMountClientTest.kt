package com.letta.mobile.ui.canvas.mount

import com.letta.mobile.data.canvas.mount.client.CanvasMountChange
import com.letta.mobile.data.canvas.mount.client.CanvasMountClient
import com.letta.mobile.data.canvas.mount.client.CanvasMountLookup
import com.letta.mobile.data.canvas.mount.client.CanvasMountReach
import com.letta.mobile.data.canvas.mount.client.CanvasMountSource
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasResolver
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasState
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.canvas.mount.client.DeviceCanvasMountClient
import com.letta.mobile.data.canvas.mount.client.DeviceFallbackCanvasMountClient
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Mounts held on this device for a host without them, and the one place that picks between the two. */
class DeviceCanvasMountClientTest {
    private val target = ConversationCanvasTarget(AgentId("agent-1"), ConversationId("conv-1"))
    private val other = target.copy(conversationId = ConversationId("conv-2"))
    private val board = CanvasId("canvas-b")

    @Test
    fun aMountIsHeldPerConversationAndAnUnmountPutsTheOwnBoardBack() = runTest {
        val client = DeviceCanvasMountClient()
        assertTrue(client.supported.value)
        assertEquals(CanvasMountLookup.None, client.resolve(target))

        assertEquals(CanvasMountChange.Applied(board), client.mount(target, board))

        assertEquals(CanvasMountLookup.Found(board, CanvasMountSource.Binding), client.resolve(target))
        assertEquals(CanvasMountLookup.None, client.resolve(other), "another conversation keeps its own board")

        assertEquals(CanvasMountChange.Applied(target.ownCanvas), client.unmount(target))
        assertEquals(CanvasMountLookup.None, client.resolve(target))
    }

    @Test
    fun mountingTheOwnCanvasIsAnUnmount() = runTest {
        val client = DeviceCanvasMountClient()
        client.mount(target, board)
        client.mount(target, target.ownCanvas)
        assertEquals(CanvasMountLookup.None, client.resolve(target))
    }

    @Test
    fun changesFollowOneConversationOnly() = runTest {
        val client = DeviceCanvasMountClient()
        val seen = async { client.changes(target).take(2).toList() }
        runCurrent()

        client.mount(other, board)
        runCurrent()
        client.mount(target, board)
        runCurrent()
        client.unmount(target)

        assertEquals(listOf(board, target.ownCanvas), seen.await())
    }

    @Test
    fun itsMountsReachThisDeviceOnly() = runTest {
        assertEquals(CanvasMountReach.Device, DeviceCanvasMountClient().reach.first())
        assertEquals(CanvasMountReach.Host, CanvasMountClient.Unsupported.reach.first())
    }

    @Test
    fun theHostsSupportPicksTheClient() = runTest {
        val host = TestHostMounts(supported = false)
        val device = DeviceCanvasMountClient()
        val client = DeviceFallbackCanvasMountClient(host, device)
        assertTrue(client.supported.value)

        assertSame(device, client.active)
        assertEquals(CanvasMountReach.Device, client.reach.first())
        client.mount(target, board)
        assertTrue(host.stored.isEmpty())
        assertEquals(CanvasMountLookup.Found(board, CanvasMountSource.Binding), device.resolve(target))

        host.support.value = true

        assertSame(host, client.active)
        assertEquals(CanvasMountReach.Host, client.reach.first())
        assertEquals(CanvasMountLookup.None, client.resolve(target), "the host's answer, not the device's")
        client.mount(target, board)
        assertEquals(board, host.stored[target])
    }

    @Test
    fun theBoardFollowsADeviceMountButTheAgentsToolsKeepTheOwnCanvas() = runTest {
        val resolver = ConversationCanvasResolver(DeviceFallbackCanvasMountClient(CanvasMountClient.Unsupported))
        resolver.mount(target, board)

        assertEquals(ConversationCanvasState.Mounted(board, CanvasMountSource.Binding), resolver.current(target))
        assertNull(resolver.mountedCanvas(target.agentId, target.conversationId))
    }

    @Test
    fun theAgentsToolsFollowAHostMount() = runTest {
        val host = TestHostMounts(supported = true).apply { stored[target] = board }
        val resolver = ConversationCanvasResolver(DeviceFallbackCanvasMountClient(host))
        assertEquals(board, resolver.mountedCanvas(target.agentId, target.conversationId))
    }
}
