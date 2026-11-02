package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.records.FileWrapperRecordStore
import com.letta.mobile.data.controller.node.iroh.records.WrapperRecordStore
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import java.time.Instant
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import java.security.MessageDigest
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ECMAScript String.trim whitespace, including BOM (letta.js uses String.trim, not Kotlin isBlank).
private const val JS_TRIM_CHARS = "\t\n\u000b\u000c\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004" +
    "\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
private fun String.trimJs(): String = trim { it in JS_TRIM_CHARS }

/** Exercises the real authenticated stream boundary and router, including the capability matrix. */
class WrapperRunRecordsRpcTest {
    private val scopeDenied = "forbidden: agent not in this device's scope"
    private val epoch = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
    private fun router(store: WrapperRecordStore = FileWrapperRecordStore(clock = { epoch })) =
        AdminRpcRegistry.buildRouter(native = AdminRpcNativeSources(nativeClient = upstream(), wrapperRecords = store))

    @Test
    fun recordAndUpstreamMergeBeforePagingWithNullableProvenance() = runTest {
        val router = router()
        val recorded = call(router, "run.record", runParams())
        assertEquals(true, recorded["success"]?.jsonPrimitive?.boolean, "run.record must accept an authorized producer: $recorded")
        val page = call(router, "cron.runs", buildJsonObject { put("task_id", "task-A") })
            .getValue("result").jsonObject.getValue("page").jsonObject
        assertEquals(2, page.getValue("total").jsonPrimitive.int)
        val rows = page.getValue("entries").jsonArray
        assertEquals(2, rows.size)
        assertEquals("requested by operator", rows[0].jsonObject["reason"]?.jsonPrimitive?.content)
        assertEquals("human", rows[0].jsonObject["source"]?.jsonPrimitive?.content)
        assertEquals("upstream-1", rows[1].jsonObject["runId"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, rows[1].jsonObject["reason"])
        assertEquals(JsonNull, rows[1].jsonObject["source"])
        assertEquals(0, page.getValue("offset").jsonPrimitive.int)
        assertEquals(50, page.getValue("limit").jsonPrimitive.int)
        assertEquals(false, page.getValue("hasMore").jsonPrimitive.boolean)
        assertEquals(JsonNull, page["nextOffset"])
        val first = call(router, "cron.runs", buildJsonObject { put("task_id", "task-A"); put("limit", 1) })
            .getValue("result").jsonObject.getValue("page").jsonObject
        assertEquals(2, first.getValue("total").jsonPrimitive.int)
        assertEquals(true, first.getValue("hasMore").jsonPrimitive.boolean)
        assertEquals(1, first.getValue("nextOffset").jsonPrimitive.int)
        val last = call(router, "cron.runs", buildJsonObject { put("task_id", "task-A"); put("limit", 1); put("offset", 1) })
            .getValue("result").jsonObject.getValue("page").jsonObject
        assertEquals("upstream-1", last.getValue("entries").jsonArray.single().jsonObject["runId"]?.jsonPrimitive?.content)
        assertEquals(false, last.getValue("hasMore").jsonPrimitive.boolean)
        assertEquals(JsonNull, last["nextOffset"])
    }

    @Test
    fun recoveredLeasePreservesFirstSeenAndReportsLastUpdate() = runTest {
        val router = router()
        val active = call(router, "lease.upsert", leaseParams("active"))
        assertEquals(true, active["success"]?.jsonPrimitive?.boolean, "lease.upsert must accept an authorized producer: $active")
        val created = active.getValue("result").jsonObject.getValue("lease").jsonObject.getValue("createdAt")
        assertEquals(true, call(router, "lease.upsert", leaseParams("recovered"))["success"]?.jsonPrimitive?.boolean)
        val row = call(router, "lease.list", buildJsonObject {}).getValue("result").jsonObject
            .getValue("leases").jsonArray.single().jsonObject
        assertEquals("recovered", row.getValue("state").jsonPrimitive.content)
        assertEquals(created, row["createdAt"])
        assertTrue(row.containsKey("updatedAt"))
    }

    @Test
    fun unauthenticatedAndMissingCapabilityWritesEmitTelemetryAndStoreNothing() = runTest {
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            val store = FileWrapperRecordStore(clock = { epoch })
            val router = router(store)
            val writes = mapOf("run.record" to runParams(), "lease.upsert" to leaseParams("active"))
            for ((method, params) in writes) {
                recorder.clear()
                val unauth = call(router, method, params, authenticated = false)
                assertEquals(false, unauth["success"]?.jsonPrimitive?.boolean)
                assertEquals("unauthorized", unauth["error"]?.jsonPrimitive?.content)
                assertTrue(recorder.snapshot().any { it.name == "auth.required" })
                assertTrue(store.snapshot().runs.isEmpty() && store.snapshot().leases.isEmpty())
                recorder.clear()
                val denied = call(router, method, params, capabilities = setOf(IrohPeerCapabilities.CHAT_READ))
                assertEquals(false, denied["success"]?.jsonPrimitive?.boolean)
                assertEquals("forbidden", denied["error"]?.jsonPrimitive?.content)
                assertEquals("workactivity.report", denied["capability"]?.jsonPrimitive?.content)
                assertTrue(recorder.snapshot().any { it.name == "authz.denied" })
                assertTrue(recorder.snapshot().none { it.toString().contains("requested by operator") })
                assertTrue(store.snapshot().runs.isEmpty() && store.snapshot().leases.isEmpty())
            }
        }
    }

    @Test
    fun scopedVibesyncPeerCanWriteAAndCannotReadOrWriteBIncludingOwnerOnlyParams() = runTest {
        val store = FileWrapperRecordStore(clock = { epoch })
        val router = router(store)
        val runB = JsonObject(runParams() + ("agentId" to JsonPrimitive("agent-B")))
        val leaseB = JsonObject(leaseParams("active") + ("owner" to JsonPrimitive("agent-B")))
        for ((method, params) in mapOf("run.record" to runB, "lease.upsert" to leaseB)) {
            assertEquals(scopeDenied, call(router, method, params)["error"]?.jsonPrimitive?.content)
        }
        assertTrue(store.snapshot().runs.isEmpty() && store.snapshot().leases.isEmpty())
        assertEquals(true, call(router, "run.record", runB, scope = setOf("agent-B"))["success"]?.jsonPrimitive?.boolean)
        assertEquals(true, call(router, "lease.upsert", leaseB, scope = setOf("agent-B"))["success"]?.jsonPrimitive?.boolean)
        assertEquals(true, call(router, "run.record", runParams())["success"]?.jsonPrimitive?.boolean)
        assertEquals(false, call(router, "lease.upsert", leaseParams("active"))["success"]?.jsonPrimitive?.boolean)
        // The global lease id already belongs to B; even omitting agentId cannot take it over.
        assertEquals("agent-B", store.snapshot().leases.single().input.owner)
        assertEquals(0, call(router, "lease.list", buildJsonObject {}).getValue("result").jsonObject.getValue("total").jsonPrimitive.int)
        val page = call(router, "cron.runs", buildJsonObject { put("task_id", "task-A") })
            .getValue("result").jsonObject.getValue("page").jsonObject
        assertEquals(2, page.getValue("total").jsonPrimitive.int)
        assertTrue(page.getValue("entries").jsonArray.all { it.jsonObject["agentId"] == JsonPrimitive("agent-A") })
        val deniedLeases = call(router, "lease.list", buildJsonObject { put("agentId", "agent-B") })
        assertEquals(scopeDenied, deniedLeases["error"]?.jsonPrimitive?.content)
        val deniedRuns = call(router, "cron.runs", buildJsonObject { put("task_id", "task-A"); put("agentId", "agent-B") })
        assertEquals(scopeDenied, deniedRuns["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun malformedWireParamsAreTypedAndUnknownEnumsRoundTripAsUnknown() = runTest {
        val store = FileWrapperRecordStore(clock = { epoch })
        val router = router(store)
        val malformed = listOf(
            "run.record" to buildJsonObject {},
            "run.record" to JsonObject(runParams() + ("instant" to JsonPrimitive("not-time"))),
            "run.record" to JsonObject(runParams() + ("scheduleId" to JsonPrimitive("unsafe/task"))),
            "run.record" to JsonObject(runParams() + ("source" to JsonPrimitive(2))),
            "lease.upsert" to JsonObject(leaseParams("active") + ("dueAt" to JsonPrimitive("2026-10-01T00:00:00Z"))),
            "lease.upsert" to JsonObject(leaseParams("active") + ("label" to JsonPrimitive(""))),
            "lease.list" to buildJsonObject { put("limit", 201) },
            "lease.list" to buildJsonObject { put("offset", -1) },
            "lease.list" to buildJsonObject { put("from", "2026-11-03T00:00:00Z"); put("to", "2026-11-01T00:00:00Z") },
        )
        malformed.forEach { (method, params) ->
            val response = call(router, method, params)
            assertEquals(false, response["success"]?.jsonPrimitive?.boolean)
            assertTrue(response["error"]?.jsonPrimitive?.content?.startsWith("validation.invalid_params:") == true, "$response")
        }
        assertTrue(store.snapshot().runs.isEmpty() && store.snapshot().leases.isEmpty())
        val future = JsonObject(runParams() + mapOf("status" to JsonPrimitive("future"), "source" to JsonPrimitive("future")))
        val unknown = call(router, "run.record", future)
            .getValue("result").jsonObject.getValue("record").jsonObject
        assertEquals("unknown", unknown["status"]?.jsonPrimitive?.content)
        assertEquals("unknown", unknown["source"]?.jsonPrimitive?.content)
        val retry = call(router, "run.record", future)
        assertEquals(unknown, retry.getValue("result").jsonObject["record"])
        val lease = call(router, "lease.upsert", leaseParams("future")).getValue("result").jsonObject.getValue("lease").jsonObject
        assertEquals("unknown", lease["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun leaseFiltersApplyScopeStateAndInclusiveOverlapBeforePagination() = runTest {
        val router = router()
        repeat(3) { index ->
            val input = JsonObject(leaseParams(if (index == 2) "done" else "active") + ("id" to JsonPrimitive("lease-$index")))
            assertEquals(true, call(router, "lease.upsert", input)["success"]?.jsonPrimitive?.boolean)
        }
        val page = call(router, "lease.list", buildJsonObject {
            put("agentId", "agent-A"); put("state", "active"); put("from", "2026-11-03T00:00:00Z")
            put("to", "2026-11-04T00:00:00Z"); put("limit", 1)
        }).getValue("result").jsonObject
        assertEquals(2, page.getValue("total").jsonPrimitive.int)
        assertEquals("lease-0", page.getValue("leases").jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals(true, page.getValue("hasMore").jsonPrimitive.boolean)
        assertEquals(1, page.getValue("nextOffset").jsonPrimitive.int)
        val excluded = call(router, "lease.list", buildJsonObject { put("from", "2026-11-03T00:00:00.001Z") })
            .getValue("result").jsonObject
        assertEquals(0, excluded.getValue("total").jsonPrimitive.int)
        assertEquals(JsonNull, excluded["nextOffset"])
    }

    @Test
    fun mergeFetchesMultipleRealSizedUpstreamPagesBeforeSlicing() = runTest {
        val store = FileWrapperRecordStore(clock = { epoch })
        val router = AdminRpcRegistry.buildRouter(
            native = AdminRpcNativeSources(nativeClient = upstream(extraRows = 205), wrapperRecords = store),
        )
        assertEquals(true, call(router, "run.record", runParams())["success"]?.jsonPrimitive?.boolean)
        val page = call(router, "cron.runs", buildJsonObject { put("task_id", "task-A"); put("offset", 205); put("limit", 1) })
            .getValue("result").jsonObject.getValue("page").jsonObject
        assertEquals(207, page.getValue("total").jsonPrimitive.int)
        assertEquals("older-203", page.getValue("entries").jsonArray.single().jsonObject["runId"]?.jsonPrimitive?.content)
        assertEquals(true, page.getValue("hasMore").jsonPrimitive.boolean)
        assertEquals(206, page.getValue("nextOffset").jsonPrimitive.int)
    }

    @Test
    fun safeUnknownTasksMatchUpstreamEmptyPageButUnsafeIdsAndMissingParamsReject() = runTest {
        val router = router()
        for (taskId in listOf("unknown-safe-task", "other")) {
            val unknown = call(router, "cron.runs", buildJsonObject { put("task_id", taskId) })
                .getValue("result").jsonObject.getValue("page").jsonObject
            assertEquals(0, unknown.getValue("total").jsonPrimitive.int)
            assertEquals(emptyList(), unknown.getValue("entries").jsonArray.toList())
        }
        for (params in listOf(buildJsonObject {}, buildJsonObject { put("task_id", "../task-A") })) {
            assertEquals(false, call(router, "cron.runs", params)["success"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun untrustedUpstreamPageCannotBecomeAPartialSuccess() = runTest {
        val native = Proxy.newProxyInstance(AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java)) {
            _, method, args ->
            check(method.name == "cronRuns")
            val command = args[0] as AppServerCommand.CronRuns
            AppServerInboundFrame.CronRunsResponse(command.requestId, true, buildJsonObject {
                put("entries", JsonArray(emptyList())); put("total", 1); put("offset", 0); put("limit", 200)
                put("hasMore", true); put("nextOffset", 0)
            })
        } as AppServerClient
        val router = AdminRpcRegistry.buildRouter(native = AdminRpcNativeSources(nativeClient = native))
        val failed = call(router, "cron.runs", buildJsonObject { put("task_id", "task-A") })
        assertEquals(false, failed["success"]?.jsonPrimitive?.boolean)
        assertEquals("cron_runs invalid page", failed["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun producerSuccessFixturesMatchRealStreamResponsesExactly() = runTest {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "docs/reference/contracts/run.record/success-examples-v1.json").isFile }
        val fixtureFile = File(root, "docs/reference/contracts/run.record/success-examples-v1.json")
        val fixture = Json.parseToJsonElement(fixtureFile.readText()).jsonObject
        var now = epoch
        val router = router(FileWrapperRecordStore(clock = { now }))
        for (case in fixture.getValue("cases").jsonArray) {
            val example = case.jsonObject
            if (example.getValue("name").jsonPrimitive.content == "lease-recovered") now = epoch + 3_600_000L
            val request = example.getValue("request").jsonObject
            val actual = call(router, request.getValue("method").jsonPrimitive.content, request.getValue("params").jsonObject)
            assertEquals(example["response"], actual, "${example["name"]} producer contract")
        }
    }

    @Test
    fun schedulelessRecordHasABoundedReadableTaskKeyForEveryValidOwner() = runTest {
        val owner = "agent/" + "x".repeat(250)
        val input = JsonObject(runParams().filterKeys { it != "scheduleId" } + ("agentId" to JsonPrimitive(owner)))
        val router = router()
        val recorded = call(router, "run.record", input, scope = setOf(owner))
        assertEquals(true, recorded["success"]?.jsonPrimitive?.boolean)
        val task = "wrapper:" + MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8)).toHexString()
        val read = call(router, "cron.runs", buildJsonObject { put("task_id", task) }, scope = setOf(owner))
        assertEquals(true, read["success"]?.jsonPrimitive?.boolean)
        val page = read.getValue("result").jsonObject.getValue("page").jsonObject
        assertEquals(1, page.getValue("total").jsonPrimitive.int, "a valid schedule-less record must be retrievable")
        val row = page.getValue("entries").jsonArray.single().jsonObject
        assertEquals(owner, row["agentId"]?.jsonPrimitive?.content)
        assertEquals(task, row["jobId"]?.jsonPrimitive?.content)
    }

    private fun runParams() = Json.parseToJsonElement(
        """{
            "agentId":"agent-A","scheduleId":"task-A","scheduleName":"daily","instant":"2026-11-02T00:00:00Z",
            "status":"done","reason":"requested by operator","source":"human"
        }""",
    ).jsonObject

    private fun leaseParams(state: String) = buildJsonObject {
        put("id", "lease-1"); put("owner", "agent-A"); put("label", "work")
        put("startedAt", "2026-11-01T00:00:00Z"); put("dueAt", "2026-11-03T00:00:00Z"); put("state", state)
    }

    private suspend fun call(
        router: AdminRpcRouter,
        method: String,
        params: JsonObject,
        authenticated: Boolean = true,
        capabilities: Set<String> = IrohPeerCapabilities.VIBESYNC_ROLE,
        scope: Set<String>? = setOf("agent-A"),
    ): JsonObject {
        val request = buildJsonObject {
            put("type", "admin_rpc"); put("request_id", "test"); put("method", method); put("params", params)
        }
        var input = IrohFrameCodec.encodeFrame(request.toString())
        val output = mutableListOf<ByteArray>()
        val stream = object : AdminRpcBiStream {
            override fun recv() = object : AdminRpcRecvStream {
                override suspend fun read(maxBytes: UInt): ByteArray = input.also { input = byteArrayOf() }
            }
            override fun send() = object : AdminRpcSendStream {
                override suspend fun writeAll(bytes: ByteArray) { output.add(bytes) }
                override suspend fun finish() = Unit
            }
        }
        val policy = IrohAuthPolicy.PairedPeersOnly
        val pairing = IrohPairingService(InMemoryPairedPeerStore(), nowMs = { epoch })
        val session = PeerSessionAuth(policy, IrohBearerAuthVerifier(policy, nowMs = { epoch }), pairing, "a".repeat(64))
        if (authenticated) {
            val invite = pairing.createInvite(IrohPairingService.InviteRequest(
                suggestedName = "producer", capabilities = capabilities, agentIds = scope,
            ))
            assertTrue(session.authenticate("invite:${invite.secret}", "producer") is PeerSessionAuth.Decision.Accepted)
        }
        AdminRpcStreamServer(
            router, session.authenticated,
            requestContextProvider = { AdminRpcRequestContext(
                session.authenticated.get(),
                authorizedConversationIds = IrohPeerCapabilities.conversationScope(session.effectiveCapabilities(), null),
                authorizedAgentIds = session.agentScope(),
            ) },
            capabilityGate = { name -> IrohPeerCapabilities.forAdminMethod(name).takeUnless {
                IrohPeerCapabilities.isAllowed(session.effectiveCapabilities(), it)
            } },
        ).handleStream(stream)
        val decoder = IrohFrameCodec.Decoder()
        return Json.parseToJsonElement(output.flatMap { decoder.feed(it) }.single()).jsonObject
    }

    /**
     * Copies letta.js 0.34.14:193781-193926 and 265380-265402: safe path id,
     * finished action, nonblank matching job id, finite numeric ts, last 5000,
     * newest-first, clamped page bounds. Unknown safe ids return an empty page;
     * cron_runs does NOT require a resolvable task (unlike cron_get).
     * All other native commands fail, rather than inventing a resolvable id.
     */
    private fun upstream(extraRows: Int = 0): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
    ) { _, method, args ->
        check(method.name == "cronRuns") { "Unexpected native command: ${method.name}" }
        val command = args[0] as AppServerCommand.CronRuns
        val safe = command.taskId.trimJs()
        if (safe.isEmpty() || safe.any { it == '/' || it == '\\' || it == '\u0000' }) {
            AppServerInboundFrame.CronRunsResponse(command.requestId, false, error = "invalid cron run log job id")
        } else {
            val raw = listOf(
                """{"action":"started","jobId":"task-A","ts":1793491200000}""",
                """{"action":"finished","jobId":"other","ts":1793491200000}""",
                """{"action":"finished","jobId":"","ts":1793491200000}""",
                """{"action":"finished","jobId":"task-A","ts":"bad"}""",
                "\uFEFF" + """{"action":"finished","jobId":"task-A","ts":1793491200000,"status":"ok",""" +
                    """"agentId":"agent-A","runId":"upstream-1","reason":"scheduler reason"}""",
                """{"action":"finished","jobId":"task-A","ts":1793491200000,
                    "agentId":"agent-A","runId":"invalid-multiline-jsonl"}""",
                "partial-json",
            ) + (0 until extraRows).map { index ->
                """{"action":"finished","jobId":"task-A","ts":${1793491200000L - index - 1},"agentId":"agent-A","runId":"older-$index"}"""
            }
            // These lines belong only to task-A's file; unknown safe ids have no file.
            val fileRows = raw.takeIf { safe == "task-A" }.orEmpty().flatMap { it.split('\n') }
            val rows = fileRows.mapNotNull { runCatching { Json.parseToJsonElement(it.trimJs()) as? JsonObject }.getOrNull() }
                .filter { it["action"] == JsonPrimitive("finished") }
                .filter { row -> (row["jobId"] as? JsonPrimitive)?.let { it.isString && it.content.trimJs().isNotEmpty() } == true }
                .filter { (it["ts"] as? JsonPrimitive)?.let { ts -> !ts.isString && ts.doubleOrNull?.isFinite() == true } == true }
                .filter { it["jobId"] == JsonPrimitive(command.taskId) }
                .map { row -> JsonObject(row.filterKeys { it in setOf(
                    "ts", "jobId", "action", "status", "outcome", "reason", "error", "summary", "agentId",
                    "conversationId", "runId", "runAtMs", "queueItemId", "scheduledFor", "firedAt",
                    "missedCount", "windowStart", "windowEnd",
                ) }) }.takeLast(5000)
                .sortedByDescending { it.getValue("ts").jsonPrimitive.double }
            val limit = (command.limit ?: 50).coerceIn(1, 200)
            val offset = (command.offset ?: 0).coerceAtLeast(0)
            val entries = rows.drop(offset).take(limit)
            val next = offset + entries.size
            AppServerInboundFrame.CronRunsResponse(command.requestId, true, buildJsonObject {
                put("entries", JsonArray(entries)); put("total", rows.size); put("offset", offset); put("limit", limit)
                put("hasMore", next < rows.size); put("nextOffset", if (next < rows.size) JsonPrimitive(next) else JsonNull)
            })
        }
    } as AppServerClient
}
