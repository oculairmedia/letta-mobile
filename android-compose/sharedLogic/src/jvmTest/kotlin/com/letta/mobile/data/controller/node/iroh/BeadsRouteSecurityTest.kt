package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.controller.node.iroh.beads.BeadsRouteClient
import com.letta.mobile.data.controller.node.iroh.beads.BeadsRoutes
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeadsRouteSecurityTest {
    private val read = setOf(
        "beads.ready", "beads.ready.count", "beads.issues.get", "beads.issues.list",
        "beads.dependencies.tree", "beads.dependencies.cycles", "beads.events.list", "beads.stats",
        "beads.context", "beads.config.get", "beads.memories.list",
    )
    private val write = setOf(
        "beads.issues.create", "beads.issues.update", "beads.issues.close", "beads.issues.claim",
        "beads.issues.release", "beads.issues.claimNext", "beads.dependencies.add", "beads.dependencies.remove",
        "beads.config.set", "beads.memories.write",
    )

    @Test
    fun everyConfiguredMethodHasAnExplicitTierAndIsAdvertised() {
        val absent = AdminRpcRegistry.buildRouter()
        assertTrue(absent.registeredMethods.none { it.startsWith("beads.") })
        val router = AdminRpcRegistry.buildRouter(services = services("http://127.0.0.1:9"))
        assertEquals(read + write, router.registeredMethods.filter { it.startsWith("beads.") }.toSet())
        read.forEach { assertEquals("beads.read", IrohPeerCapabilities.forAdminMethod(it), it) }
        write.forEach { assertEquals("beads.write", IrohPeerCapabilities.forAdminMethod(it), it) }
        assertEquals("admin.full", IrohPeerCapabilities.forAdminMethod("beads.unclassified"))
        assertTrue((read + write).all { it in IrohNodeConnection.advertisedCapabilities(router) })
        assertTrue((read + write).none { it in IrohNodeConnection.advertisedCapabilities(absent) })
        assertTrue(setOf("beads.read", "beads.write").all { it in IrohPeerCapabilities.ALL })
        assertTrue(setOf("beads.read", "beads.write").all { it in IrohPeerCapabilities.ROLES.getValue("admin") })
        assertFalse("beads.read" in IrohPeerCapabilities.VIBESYNC_ROLE)
        assertFalse("beads.write" in IrohPeerCapabilities.VIBESYNC_ROLE)
    }

    @Test
    fun streamGateAndBackendFailuresDoNotLeakBearer() = runTest {
        FakeBeads().use { fake ->
            fake.assertRejectsUnknownRequests()
            val recorder = TelemetryRecorder()
            Telemetry.installRecorder(recorder).use {
                val router = AdminRpcRegistry.buildRouter(services = services(fake.base, fake.tokenFile.toString()))
                val observed = deniedCalls(router, fake, recorder) + allowedAndFailedCalls(router, fake)
                assertFalse(fake.secret in (observed.joinToString() + recorder.snapshot()))
            }
        }
    }

    private suspend fun deniedCalls(router: AdminRpcRouter, fake: FakeBeads, recorder: TelemetryRecorder): List<JsonObject> {
        val responses = mutableListOf<JsonObject>()
        val noCaps = call(router, "beads.ready", setOf("chat.read"))
        responses += noCaps
        assertEquals("forbidden", noCaps["error"]?.jsonPrimitive?.content)
        assertEquals("beads.read", noCaps["capability"]?.jsonPrimitive?.content)
        assertTrue(recorder.snapshot().any { it.name == "authz.denied" })
        val noGet = call(router, "beads.issues.get", setOf("chat.read"), params = buildJsonObject { put("id", "bd-1") })
        responses += noGet
        assertEquals("beads.read", noGet["capability"]?.jsonPrimitive?.content)
        val scoped = call(router, "beads.ready", setOf("beads.read"), scope = setOf("agent-A"))
        responses += scoped
        assertEquals(false, scoped["success"]?.jsonPrimitive?.boolean)
        for (method in read + write) {
            val result = call(router, method, setOf("admin.full"), scope = setOf("agent-A"))
            responses += result
            assertEquals(AgentScopeGuard.DENIED, result["error"]?.jsonPrimitive?.content, method)
        }
        val anonymous = call(router, "beads.ready", emptySet(), authenticated = false)
        responses += anonymous
        assertEquals("unauthorized", anonymous["error"]?.jsonPrimitive?.content)
        assertTrue(recorder.snapshot().any { it.name == "auth.required" })
        assertEquals(0, fake.requests.get())
        return responses
    }

    private suspend fun allowedAndFailedCalls(router: AdminRpcRouter, fake: FakeBeads): List<JsonObject> {
        val ready = call(router, "beads.ready", setOf("beads.read"))
        assertEquals(true, ready["success"]?.jsonPrimitive?.boolean)
        val get = call(router, "beads.issues.get", setOf("beads.read"), params = buildJsonObject { put("id", "bd-1") })
        assertEquals(true, get["success"]?.jsonPrimitive?.boolean)
        assertEquals(listOf("/v0/beads/context", "/v0/beads/ready", "/v0/beads/issues/bd-1"), fake.paths.toList())
        val closeParams = buildJsonObject { put("id", "bd-1"); put("body", buildJsonObject {}) }
        val close = call(router, "beads.issues.close", setOf("beads.write"), params = closeParams)
        assertEquals(true, close["success"]?.jsonPrimitive?.boolean)
        fake.echoSecret.set(true)
        val echoed = call(router, "beads.ready", setOf("beads.read"))
        assertEquals("beads.invalid_response", echoed["error"]?.jsonPrimitive?.content)
        assertFalse(fake.secret in echoed.toString())
        fake.echoSecret.set(false)
        val beforeDenied = fake.requests.get()
        val denied = call(router, "beads.issues.close", setOf("beads.read"))
        assertEquals("forbidden", denied["error"]?.jsonPrimitive?.content)
        assertEquals("beads.write", denied["capability"]?.jsonPrimitive?.content)
        assertEquals(beforeDenied, fake.requests.get())
        val unsupported = call(router, "beads.stats", setOf("beads.read"))
        assertTrue(unsupported["error"]?.jsonPrimitive?.content?.startsWith("capability_unavailable:") == true)
        assertEquals(beforeDenied, fake.requests.get())
        val missing = call(router, "beads.dependencies.cycles", setOf("beads.read"))
        assertEquals("beads.upstream_error", missing["error"]?.jsonPrimitive?.content)
        fake.badCredentials.set(true)
        val rejected = call(router, "beads.ready", setOf("beads.read"))
        assertEquals("beads.unauthorized", rejected["error"]?.jsonPrimitive?.content)
        val fresh = AdminRpcRegistry.buildRouter(services = services(fake.base, fake.tokenFile.toString()))
        val rejectedHandshake = call(fresh, "beads.ready", setOf("beads.read"))
        assertEquals("beads.unauthorized", rejectedHandshake["error"]?.jsonPrimitive?.content)
        val unavailable = AdminRpcRegistry.buildRouter(services = services("http://127.0.0.1:9", fake.tokenFile.toString()))
        val failedTransport = call(unavailable, "beads.ready", setOf("beads.read"))
        assertEquals("beads.unavailable", failedTransport["error"]?.jsonPrimitive?.content)
        return listOf(ready, get, close, echoed, denied, unsupported, missing, rejected, rejectedHandshake, failedTransport)
    }

    private class FakeBeads : AutoCloseable {
        val secret = "beads-secret-canary-a83d5"
        val tokenFile = Files.createTempFile("beads-token", ".txt").also { Files.writeString(it, secret) }
        val requests = AtomicInteger()
        val paths = ConcurrentLinkedQueue<String>()
        val badCredentials = AtomicBoolean()
        val echoSecret = AtomicBoolean()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base: String

        init {
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                val authorized = exchange.requestHeaders.getFirst("Authorization") == "Bearer $secret" && !badCredentials.get()
                val path = exchange.requestURI.path
                paths.add(path)
                val known = path.endsWith("/context") || path.endsWith("/ready") ||
                    path.endsWith("/issues/bd-1") || path.endsWith("/issues/bd-1:close")
                val status = if (!authorized) 401 else if (!known) 404 else 200
                val body = responseBody(path, authorized).toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            base = "http://127.0.0.1:${server.address.port}"
        }

        private fun responseBody(path: String, authorized: Boolean): String = when {
            !authorized -> "{\"error\":\"$secret\"}"
            path.endsWith("/context") -> """{"api_version":"v0","bd_version":"1.3.2","schema_version":1,""" +
                """"backend":"dolt","dolt_mode":"server","database":"beads","project_id":"p",""" +
                """"capabilities":["ready.list","issues.get","issues.close","dependencies.cycles"]}"""
            path.endsWith("/ready") -> if (echoSecret.get()) {
                "{\"items\":[],\"note\":\"$secret\"}"
            } else {
                """{"items":[],"next_cursor":null}"""
            }
            else -> """{"issue":{"id":"bd-1","title":"ok"}}"""
        }

        fun assertRejectsUnknownRequests() {
            listOf(null, "Bearer wrong").forEach { authorization ->
                val connection = URI("$base/v0/beads/context").toURL().openConnection() as HttpURLConnection
                authorization?.let { connection.setRequestProperty("Authorization", it) }
                assertEquals(401, connection.responseCode)
                connection.disconnect()
            }
            val unknown = URI("$base/v0/beads/stats").toURL().openConnection() as HttpURLConnection
            unknown.setRequestProperty("Authorization", "Bearer $secret")
            assertEquals(404, unknown.responseCode)
            unknown.disconnect()
            requests.set(0)
            paths.clear()
        }

        override fun close() {
            server.stop(0)
            Files.deleteIfExists(tokenFile)
        }
    }

    private fun services(url: String, tokenFile: String? = null): AdminRpcHostServices {
        val client = BeadsRouteClient(url, tokenFile)
        return AdminRpcHostServices(beadsBaseUrl = url, beadsTokenFile = tokenFile, beadsRoutes = AdminRpcRoutes { router ->
            (BeadsRoutes.reads + BeadsRoutes.writes).forEach { (method, operation) ->
                router.register(method) { params -> client.execute(operation, params) }
            }
        })
    }

    private suspend fun call(
        router: AdminRpcRouter,
        method: String,
        capabilities: Set<String>,
        params: JsonObject = buildJsonObject {},
        authenticated: Boolean = true,
        scope: Set<String>? = null,
    ): JsonObject {
        val request = buildJsonObject { put("type", "admin_rpc"); put("request_id", "test"); put("method", method); put("params", params) }
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
        val pairing = IrohPairingService(InMemoryPairedPeerStore())
        val session = PeerSessionAuth(policy, IrohBearerAuthVerifier(policy), pairing, "a".repeat(64))
        if (authenticated) {
            val invite = pairing.createInvite(IrohPairingService.InviteRequest("test", capabilities = capabilities, agentIds = scope))
            assertTrue(session.authenticate("invite:${invite.secret}", "test") is PeerSessionAuth.Decision.Accepted)
        }
        AdminRpcStreamServer(
            router, session.authenticated,
            requestContextProvider = { AdminRpcRequestContext(session.authenticated.get(), authorizedAgentIds = session.agentScope()) },
            capabilityGate = { name -> IrohPeerCapabilities.forAdminMethod(name).takeUnless {
                IrohPeerCapabilities.isAllowed(session.effectiveCapabilities(), it)
            } },
        ).handleStream(stream)
        val decoder = IrohFrameCodec.Decoder()
        return Json.parseToJsonElement(output.flatMap { decoder.feed(it) }.single()).jsonObject
    }
}
