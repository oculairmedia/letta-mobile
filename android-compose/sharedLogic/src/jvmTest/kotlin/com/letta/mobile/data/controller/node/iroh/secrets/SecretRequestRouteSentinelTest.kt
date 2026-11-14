package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import com.letta.mobile.data.controller.node.iroh.AdminRpcBiStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRecvStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcSendStream
import com.letta.mobile.data.controller.node.iroh.AdminRpcStreamServer
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.secrets.host.TARGET_REF
import com.letta.mobile.data.secrets.host.TestKeystoreProvider
import com.letta.mobile.data.secrets.journal.FileSecretRequestJournal
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestMethods
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sends unique marker values through every route flow (the real router, the Iroh stream server
 * and its frame codec, the Iroh control channel's frame handler, the tool and the handler as an
 * agent) and scans every sink for any 12-character slice of each marker's raw, base64, base64url
 * and hex forms (and the whole reversed forms): journal bytes, every telemetry event as its log
 * line, attributes and throwable, everything printed to stdout and stderr, every response to every
 * principal on every channel, tool results, exception messages and the `toString` of every model.
 * A slice catches a parser that quotes part of a frame. The test keystore is the only place a
 * marker may be. Marker names stay at most 11 characters, so every slice holds part of the shared
 * prefix and an ordinary word in a sink can never match.
 */
class SecretRequestRouteSentinelTest {
    private val directory: Path = Files.createTempDirectory("secret-route-sentinel")
    private val recorder = TelemetryRecorder()
    private lateinit var recording: AutoCloseable
    private val printed = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err
    private val observed = mutableListOf<String>()

    private val markers = listOf(
        "routed", "replay", "admin-token", "unauth", "limited", "wrong-agent", "agent", "smuggled",
        "not-base64", "locked", "exploded", "expired", "stream", "torn-frame", "tool-arg", "claimed",
        "ctl-torn", "ctl-open",
    ).associateWith { TestSecret("SENTINEL-s2-91c4e7-$it") }

    private fun marker(name: String): TestSecret = markers.getValue(name)

    @BeforeTest
    fun capture() {
        recording = Telemetry.installRecorder(recorder)
        System.setOut(PrintStream(printed, true))
        System.setErr(PrintStream(printed, true))
    }

    @AfterTest
    fun release() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        recording.close()
        directory.toFile().deleteRecursively()
    }

    private fun observe(vararg values: Any?) {
        values.forEach { observed += it.toString() }
    }

    private var journals = 0

    /** A fresh host with its own durable journal file (each harness numbers its ids from the start). */
    private fun harness(provider: TestKeystoreProvider = TestKeystoreProvider()): SecretRouteHarness =
        SecretRouteHarness(SecretRouteParts(FileSecretRequestJournal(directory.resolve("requests-${journals++}.json")), provider))

    private suspend fun SecretRouteHarness.submitAs(context: AdminRpcRequestContext, id: SecretRequestId, secret: TestSecret) {
        val params = submitParams(id, secret)
        observe(dispatch(SecretRequestRoute.SUBMIT, params, context), AdminRpcInvocation("s", SecretRequestMethods.SUBMIT, params, context))
        observe(AppServerCommand.AdminRpc("s", SecretRequestMethods.SUBMIT, params))
        observe(status(id), dispatch(SecretRequestRoute.GET, idParams(id), HUMAN), dispatch(SecretRequestRoute.LIST, null, HUMAN))
    }

    private suspend fun answeredFlows(vault: TestKeystoreProvider) {
        val routes = harness(vault)
        val id = routes.create()
        routes.submitAs(HUMAN, id, marker("routed"))
        routes.submitAs(HUMAN, id, marker("replay"))
        val denied = mapOf(
            "admin-token" to ADMIN_TOKEN,
            "unauth" to UNAUTHENTICATED,
            "limited" to LIMITED_HUMAN,
            "wrong-agent" to WRONG_AGENT_HUMAN,
        )
        denied.forEach { (name, context) -> routes.submitAs(context, routes.create(), marker(name)) }
        val asAgent = routes.feature.handlers.handle(
            SecretRequestRoute.SUBMIT, submitParams(routes.create(), marker("agent")), SecretRequestCaller.Agent(REQUESTER), "test",
        )
        observe(asAgent, (asAgent as SecretRouteAnswer.Refused).text)
        routes.parts.decoded.forEach { observe(it, it.isWiped) }
        assertTrue(routes.parts.decoded.all { it.isWiped }, "every decoded value is wiped")
    }

    private suspend fun refusedValueFlows() {
        val routes = harness()
        val id = routes.create()
        val smuggled = JsonObject(submitParams(id, marker("smuggled")) + ("note" to JsonPrimitive(marker("smuggled").text)))
        observe(routes.dispatch(SecretRequestRoute.SUBMIT, smuggled, HUMAN))
        val raw = buildJsonObject {
            put("requestId", id.value)
            put("value", marker("not-base64").text)
        }
        observe(routes.dispatch(SecretRequestRoute.SUBMIT, raw, HUMAN))
        observe(routes.tool(JsonObject(statusInput(id) + ("value" to JsonPrimitive(marker("tool-arg").base64()))), REQUESTER))
        val claimed = JsonObject(statusInput(id) + ("value" to JsonPrimitive(marker("claimed").base64())))
        observe(routes.tool(claimed, REQUESTER, CallerIdentity.CLAIMED))
        observe(routes.tool(createInput(marker("claimed").text), REQUESTER, CallerIdentity.CLAIMED))
    }

    private suspend fun failingFlows() {
        val locked = harness(TestKeystoreProvider().also { it.failWith = KeystoreError.Locked })
        locked.submitAs(HUMAN, locked.create(), marker("locked"))
        val exploding = harness(TestKeystoreProvider().also { it.throwOnCall = true })
        exploding.submitAs(HUMAN, exploding.create(), marker("exploded"))
        val late = harness()
        val id = late.create()
        late.clock.nowMs += SecretRequestLimits.DEFAULT_TTL_MS
        late.submitAs(HUMAN, id, marker("expired"))
    }

    private suspend fun streamFlows() {
        val routes = harness()
        val id = routes.create()
        val frame = buildJsonObject {
            put("type", "admin_rpc")
            put("request_id", "stream")
            put("method", SecretRequestMethods.SUBMIT)
            put("params", submitParams(id, marker("stream")))
        }.toString()
        observe(serveOne(routes, IrohFrameCodec.encodeFrame(frame)))
        val torn = frame.replace(marker("stream").base64(), marker("torn-frame").base64()).dropLast(2)
        observe(serveOne(routes, IrohFrameCodec.encodeFrame(torn)))
    }

    /**
     * Malformed submissions on the Iroh control channel, through the connection's real frame
     * handler: a torn frame and a frame whose value string never ends. Each is answered with fixed
     * text.
     */
    private suspend fun controlChannelFlows() {
        val routes = harness()
        val id = routes.create()
        val connection = controlConnection(routes)
        val torn = submitFrame(id, marker("ctl-torn")).dropLast(3)
        val open = submitFrame(id, marker("ctl-open")).substringBeforeLast(marker("ctl-open").base64()) +
            marker("ctl-open").base64()
        val answers = listOf(torn, open).map { frame -> connection.controlFrame(frame) }
        observe(*answers.toTypedArray())
        assertTrue(answers.all { it.orEmpty().startsWith("""{"type":"error","message":"Failed to parse frame (""") }, "$answers")
    }

    private fun submitFrame(id: SecretRequestId, secret: TestSecret): String = buildJsonObject {
        put("type", "admin_rpc")
        put("request_id", "control")
        put("method", SecretRequestMethods.SUBMIT)
        put("params", submitParams(id, secret))
    }.toString()

    /** One admin_rpc stream exchange through the real stream server and frame codec; answers the frames written back. */
    private suspend fun serveOne(routes: SecretRouteHarness, encoded: ByteArray): List<String> {
        val stream = OneShotStream(encoded)
        val server = AdminRpcStreamServer(
            router = routes.router,
            authenticated = AtomicBoolean(true),
            remoteEndpointId = HUMAN_ENDPOINT,
            requestContextProvider = { HUMAN },
            capabilityGate = { method ->
                IrohPeerCapabilities.forAdminMethod(method).takeUnless { IrohPeerCapabilities.isAllowed(HUMAN.grantedCapabilities, it) }
            },
        )
        server.handleStream(stream)
        return stream.written.flatMap { IrohFrameCodec.Decoder().feed(it) }
    }

    @Test
    fun noMarkerReachesTheJournalTelemetryLogsResponsesToolResultsErrorsOrAnyToString() = runTest {
        val vault = TestKeystoreProvider()
        answeredFlows(vault)
        refusedValueFlows()
        failingFlows()
        streamFlows()
        controlChannelFlows()

        assertEquals(marker("routed").text, vault.storedText(TARGET_REF), "positive control: the vault holds the answered value")
        assertTrue(observed.any { "\"success\":true" in it }, "positive control: a submission succeeded")
        val journal = directory.toFile().walkTopDown().filter { it.isFile }.map { String(it.readBytes(), Charsets.ISO_8859_1) }.toList()
        assertTrue(journal.isNotEmpty(), "the journal was written")
        val telemetry = recorder.snapshot().flatMap { event ->
            listOf(event.toLine(), event.throwable?.stackTraceToString().orEmpty()) + event.attrs.values.map { it.toString() }
        }
        assertTrue(telemetry.any { "route.outcome" in it }, "the recorder captured the routes' events")
        val sinks = mapOf("journal" to journal, "telemetry" to telemetry, "printed" to listOf(printed.toString()), "results" to observed)
        for ((sink, texts) in sinks) {
            for ((name, secret) in markers) {
                forms(secret).forEach { form -> assertFalse(texts.any { form in it }, "$sink holds a form of marker $name") }
            }
        }
    }

    @Test
    fun theScanWouldSeeAMarkerInTheParamsItSent() {
        val params = submitParams(UNKNOWN_ID, marker("routed")).toString()
        assertTrue(forms(marker("routed")).any { it in params }, "the scan recognizes the encoded value")
    }

    /** Positive control for the slices: what the control channel used to answer quoted part of the value. */
    @Test
    fun theScanWouldSeeAParserQuotingPartOfAFrame() {
        val torn = submitFrame(UNKNOWN_ID, marker("ctl-torn")).dropLast(3)
        val parserText = assertFailsWith<Exception> { kotlinx.serialization.json.Json.parseToJsonElement(torn) }.message.orEmpty()
        assertTrue(forms(marker("ctl-torn")).any { it in parserText }, "a parser message quotes the frame: $parserText")
        assertFalse(forms(marker("ctl-torn")).any { it in "Failed to parse frame (JsonDecodingException)" })
    }
}

/** Every 12-character slice of [secret]'s raw, base64, base64url and hex forms, and the whole reversed forms. */
private fun forms(secret: TestSecret): Set<String> {
    val bytes = secret.bytes()
    val base64 = Base64.getEncoder().encodeToString(bytes)
    val encodings = listOf(
        secret.text,
        base64,
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        bytes.joinToString("") { "%02x".format(it) },
    )
    val slices = encodings.flatMap { it.windowed(SLICE_LENGTH) }
    return (slices + listOf(secret.text.reversed(), base64.reversed())).toSet()
}

private const val SLICE_LENGTH = 12

/** A stream that delivers [request] once, then end of stream, and keeps what the server writes. */
private class OneShotStream(private val request: ByteArray) : AdminRpcBiStream {
    val written = mutableListOf<ByteArray>()
    private var delivered = false

    override fun recv(): AdminRpcRecvStream = object : AdminRpcRecvStream {
        override suspend fun read(maxBytes: UInt): ByteArray = if (delivered) ByteArray(0) else request.also { delivered = true }
    }

    override fun send(): AdminRpcSendStream = object : AdminRpcSendStream {
        override suspend fun writeAll(bytes: ByteArray) {
            written += bytes
        }

        override suspend fun finish() = Unit
    }
}
