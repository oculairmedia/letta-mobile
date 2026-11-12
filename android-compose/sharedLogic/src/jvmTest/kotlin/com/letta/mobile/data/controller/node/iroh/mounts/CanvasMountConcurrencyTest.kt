package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.model.CanvasId
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PEERS = 8
private const val ROUNDS = 12

class CanvasMountConcurrencyTest {
    @Test
    fun concurrentPeersBindingOneConversationGetExactlyOneWinnerAndAllSeeIt() = runBlocking {
        val harness = MountHarness()
        val peers = (0 until PEERS).map { Peer.of("0123456789abcdef"[it]) }
        val proposals = peers.indices.map { CanvasId("canvas-peer-$it") }
        peers.forEachIndexed { index, peer ->
            proposals.forEach { harness.grant(peer, it, CanvasMountGrant.SEE) }
            harness.grant(peer, proposals[index], CanvasMountGrant.MOUNT)
        }
        val replies = peers.indices.map { index ->
            async(Dispatchers.Default) {
                harness.call(CanvasMountRoute.BIND, bindingParams(AGENT_A, CONV_A, proposals[index]), peers[index])
            }
        }.awaitAll()
        assertEquals(setOf(CanvasMountWire.BOUND), replies.map { it.status }.toSet())
        val winner = replies.map { it.canvas }.toSet().single()
        assertEquals(1, replies.count { it.won == true })
        assertEquals(winner, harness.store().readBinding(AGENT_A, CONV_A)?.value)
    }

    @Test
    fun twoHostProcessesReplacingDifferentKeysNeverEraseEachOther() = runBlocking {
        val root = Files.createTempDirectory("canvas-mount-two-hosts")
        val first = MountHarness(root)
        val second = MountHarness(root)
        val canvases = (0 until ROUNDS).map { CanvasId("canvas-round-$it") }
        canvases.forEach { first.grant(PEER, it, CanvasMountGrant.MOUNT) }
        val writes = canvases.flatMap { canvas ->
            listOf(
                async(Dispatchers.Default) { first.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, canvas), PEER) },
                async(Dispatchers.Default) { second.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A2, canvas), PEER) },
            )
        }.awaitAll()
        assertEquals(setOf(CanvasMountWire.REPLACED), writes.map { it.status }.toSet())
        val last = canvases.last()
        first.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A, last), PEER)
        second.call(CanvasMountRoute.REPLACE, bindingParams(AGENT_A, CONV_A2, canvases.first()), PEER)
        assertEquals(last, second.store().readBinding(AGENT_A, CONV_A))
        assertEquals(canvases.first(), first.store().readBinding(AGENT_A, CONV_A2))
    }
}
