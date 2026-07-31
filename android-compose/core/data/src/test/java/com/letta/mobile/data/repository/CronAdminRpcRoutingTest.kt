package com.letta.mobile.data.repository

import com.letta.mobile.data.model.CronTask
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
 *    admin_rpc method names.
 *  - shim config -> the legacy shim WS cron frames, which remain functional.
 *    Covered by `CronRepositoryTest` via `FakeChannelTransport`.
 *
 * (The per-field admin_rpc request/response mapping is separately pinned by
 * `sharedLogic` jvmTest `IrohChannelTransportCronRpcTest`.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CronAdminRpcRoutingTest {

    @Test
    fun `repository cron reads and writes route onto cron admin_rpc methods`() = runTest {
        val transport = AdminRpcBridgingTransport()
        val repository = CronRepository(transport, TestScope(testScheduler))

        val listed = repository.refresh("agent-1")
        val added = repository.addSchedule(
            CronAddParams(
                agentId = "agent-1",
                name = "nightly",
                description = "nightly run",
                prompt = "go",
                recurring = true,
                cron = "0 3 * * *",
            ),
        )
        val deleted = repository.deleteSchedule(agentId = "agent-1", taskId = "task-1")

        assertTrue(listed.isSuccess)
        assertTrue(added.isSuccess)
        assertTrue(deleted.isSuccess)
        assertEquals(listOf("cron.list", "cron.add", "cron.delete"), transport.adminRpcMethods)
        // The repository must not reach for any other transport verb: no shim
        // chat/control frame may be emitted to service a cron operation.
        assertTrue(transport.otherVerbs.isEmpty())
    }

    /**
     * Shaped like `IrohChannelTransport`: each cron method is expressed as a
     * `cron.*` admin_rpc call. Everything the legacy shim path would use
     * (`send`, `subscribe`, `cancel`) is recorded so the test can assert it
     * stays untouched.
     */
    private class AdminRpcBridgingTransport : NoOpChannelTransport() {
        val adminRpcMethods = mutableListOf<String>()
        val otherVerbs = mutableListOf<String>()

        override suspend fun adminRpc(
            method: String,
            path: String,
            body: String?,
        ): AppServerInboundFrame.AdminRpcResponse {
            adminRpcMethods += method
            return AppServerInboundFrame.AdminRpcResponse(requestId = "rpc-1", success = true)
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
                requestId = "rpc-1",
                success = true,
                tasks = emptyList(),
            )
        }

        override suspend fun sendCronAdd(
            agentId: String,
            name: String,
            description: String,
            prompt: String,
            recurring: Boolean,
            cron: String?,
            every: String?,
            at: String?,
            timezone: String?,
            conversationId: String?,
            timeoutMs: Long,
        ): ServerFrame.CronAddResponse {
            adminRpc("cron.add", "", null)
            return ServerFrame.CronAddResponse(
                id = "f2",
                ts = TS,
                requestId = "rpc-1",
                success = true,
                task = CronTask(
                    id = "task-1",
                    agentId = agentId,
                    conversationId = conversationId.orEmpty(),
                    name = name,
                    description = description,
                    cron = cron.orEmpty(),
                    timezone = timezone.orEmpty(),
                    recurring = recurring,
                    prompt = prompt,
                    status = "active",
                    createdAt = TS,
                ),
            )
        }

        override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse {
            adminRpc("cron.delete", "", null)
            return ServerFrame.CronDeleteResponse(id = "f3", ts = TS, requestId = "rpc-1", success = true)
        }

        override fun send(
            agentId: String,
            conversationId: String,
            text: String,
            otid: String?,
            contentParts: kotlinx.serialization.json.JsonArray?,
            startNewConversation: Boolean,
        ): Boolean {
            otherVerbs += "send"
            return false
        }

        override fun subscribe(runId: String, cursor: Long): Boolean {
            otherVerbs += "subscribe"
            return false
        }

        override fun cancel(conversationId: String): Boolean {
            otherVerbs += "cancel"
            return false
        }

        private companion object {
            const val TS = "2026-07-31T00:00:00Z"
        }
    }
}
