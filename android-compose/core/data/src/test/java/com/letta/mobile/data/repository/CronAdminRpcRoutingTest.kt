package com.letta.mobile.data.repository

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-lgns8.10.4.1 — cron off the shim WS.
 *
 * `CronRepository` speaks only the common `IChannelTransport` cron surface; the
 * wire format is decided by whichever transport `SessionGraphFactory` bound for
 * the active backend:
 *
 *  - `iroh://` config -> `IrohChannelTransport`, which bridges every cron call
 *    onto the native `cron.*` admin_rpc methods (#997). This test drives the
 *    repository through a transport shaped like that bridge — its cron methods
 *    are implemented *by calling* `adminRpc` — and asserts the resulting
 *    admin_rpc method names for a read (`cron.list`) and a write
 *    (`cron.delete`).
 *  - shim config -> the legacy shim WS cron frames, which remain functional.
 *    Covered by `CronRepositoryTest` via `FakeChannelTransport`.
 *
 * (The per-field admin_rpc request/response mapping for every cron verb,
 * `cron.add` included, is separately pinned by `sharedLogic` jvmTest
 * `IrohChannelTransportCronRpcTest`.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CronAdminRpcRoutingTest {

    @Test
    fun `repository cron reads and writes route onto cron admin_rpc methods`() = runTest {
        val transport = AdminRpcBridgingTransport()
        val repository = CronRepository(transport, TestScope(testScheduler))

        val listed = repository.refresh("agent-1")
        val deleted = repository.deleteSchedule(agentId = "agent-1", taskId = TASK_ID)

        assertTrue(listed.isSuccess)
        assertTrue(deleted.isSuccess)
        assertEquals(listOf("cron.list", "cron.delete"), transport.adminRpcMethods)
    }

    /**
     * Shaped like `IrohChannelTransport`: each cron method is expressed as a
     * `cron.*` admin_rpc call. Everything else inherits `NoOpChannelTransport`,
     * whose remaining cron members throw — so a repository that reached for an
     * un-bridged cron verb would fail rather than silently pass.
     */
    private class AdminRpcBridgingTransport : NoOpChannelTransport() {
        val adminRpcMethods = mutableListOf<String>()

        override suspend fun adminRpc(
            method: String,
            path: String,
            body: String?,
        ): AppServerInboundFrame.AdminRpcResponse {
            adminRpcMethods += method
            return AppServerInboundFrame.AdminRpcResponse(requestId = REQUEST_ID, success = true)
        }

        override suspend fun sendCronList(
            agentId: String?,
            conversationId: String?,
            timeoutMs: Long,
        ): ServerFrame.CronListResponse {
            adminRpc("cron.list", "", null)
            return ServerFrame.CronListResponse(
                id = "f1",
                ts = TS,
                requestId = REQUEST_ID,
                success = true,
                tasks = emptyList(),
            )
        }

        override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse {
            adminRpc("cron.delete", "", null)
            return ServerFrame.CronDeleteResponse(id = "f3", ts = TS, requestId = REQUEST_ID, success = true)
        }
    }

    private companion object {
        const val TASK_ID = "task-1"
        const val REQUEST_ID = "rpc-1"
        const val TS = "2026-07-31T00:00:00Z"
    }
}
