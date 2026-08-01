package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client half of the 2026-08-01 `block.list` regression fix.
 *
 * The store tier serves the union of every agent's memory files — ~1.83 MB on the
 * live host, over the 1 MiB Iroh admin_rpc frame cap — so it now windows by
 * limit/offset. This pins that the client actually sweeps the cursor (and does not
 * settle for page 1), and that it understands both wire shapes: the paged envelope
 * and the legacy bare array.
 */
class IrohAdminRpcBlockSourcePagingTest {
    private fun source(transport: FakeChannelTransport): IrohAdminRpcBlockSource {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        return IrohAdminRpcBlockSource(transport, settings)
    }

    private fun ok(result: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req",
        success = true,
        result = Json.parseToJsonElement(result),
    )

    private fun block(id: String) = """{"id":"$id","label":"persona","value":"v"}"""

    /** A server that windows correctly, answering with the paged envelope. */
    private fun pagingTransport(total: Int) = FakeChannelTransport().apply {
        adminRpcHandler = { _, _, body ->
            val limit = Regex("\"limit\":\"(\\d+)\"").find(body.orEmpty())!!.groupValues[1].toInt()
            val offset = Regex("\"offset\":\"(\\d+)\"").find(body.orEmpty())!!.groupValues[1].toInt()
            val ids = (offset until minOf(offset + limit, total)).map { "block-$it" }
            ok(
                """{"blocks":[${ids.joinToString(",") { block(it) }}],""" +
                    """"total":$total,"offset":$offset,"limit":$limit,""" +
                    """"has_more":${offset + ids.size < total}}""",
            )
        }
    }

    @Test
    fun `listAllBlocks sweeps the cursor across every page`() = runTest {
        val total = IrohAdminRpcBlockSource.BLOCK_LIST_PAGE_SIZE * 3 + 7
        val transport = pagingTransport(total)

        val blocks = source(transport).listAllBlocks()

        assertEquals(total, blocks.size)
        assertEquals(total, blocks.map { it.id.value }.toSet().size)
        assertTrue("must have made more than one round trip", transport.adminRpcCalls.size > 1)
        assertEquals("block.list", transport.adminRpcCalls.first().method)
        assertTrue(
            "every request must carry the cursor",
            transport.adminRpcCalls.all { it.body.orEmpty().contains("\"offset\"") },
        )
    }

    @Test
    fun `listAllBlocks requests distinct offsets rather than page one forever`() = runTest {
        val transport = pagingTransport(IrohAdminRpcBlockSource.BLOCK_LIST_PAGE_SIZE * 2)

        source(transport).listAllBlocks()

        val offsets = transport.adminRpcCalls.map {
            Regex("\"offset\":\"(\\d+)\"").find(it.body.orEmpty())!!.groupValues[1].toInt()
        }
        assertEquals(offsets.distinct(), offsets)
        assertTrue("second page must ask past the first", offsets.contains(IrohAdminRpcBlockSource.BLOCK_LIST_PAGE_SIZE))
    }

    @Test
    fun `a bare array is understood as the complete set`() = runTest {
        // Back-compat: the server answers with the legacy shape when the whole
        // union fits, and that must terminate the sweep in one round trip.
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("""[${block("block-1")},${block("block-2")}]""") }
        }

        val blocks = source(transport).listAllBlocks()

        assertEquals(2, blocks.size)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `a backend that ignores the cursor cannot spin the pager`() = runTest {
        // The pre-fix backend re-served page 1 for every offset. The dedup guard
        // must stop immediately instead of looping to the iteration cap.
        val page = (0 until IrohAdminRpcBlockSource.BLOCK_LIST_PAGE_SIZE).joinToString(",") { block("block-$it") }
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> ok("[$page]") }
        }

        val blocks = source(transport).listAllBlocks()

        assertEquals(IrohAdminRpcBlockSource.BLOCK_LIST_PAGE_SIZE, blocks.size)
        assertTrue(
            "must not hammer the backend to the iteration cap: ${transport.adminRpcCalls.size} calls",
            transport.adminRpcCalls.size <= 2,
        )
    }

    @Test
    fun `countBlocks uses the server declared total instead of counting what it swept`() = runTest {
        val transport = pagingTransport(total = 1447)

        val count = source(transport).countBlocks()

        // The authoritative total, from ONE cheap probe — not a number inferred
        // from however many rows a truncated sweep accumulated.
        assertEquals(1447, count)
        assertEquals(1, transport.adminRpcCalls.size)
    }
}
