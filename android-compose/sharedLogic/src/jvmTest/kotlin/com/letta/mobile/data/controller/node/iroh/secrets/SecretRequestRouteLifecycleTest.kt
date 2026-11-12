package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.secrets.host.TEST_VAULT
import com.letta.mobile.data.secrets.host.TARGET_REF
import com.letta.mobile.data.secrets.journal.FileSecretRequestJournal
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Single use, expiry, decline, races, restarts and the write rules, through the real routes. */
class SecretRequestRouteLifecycleTest {
    private val directory = Files.createTempDirectory("secret-route-lifecycle")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private suspend fun SecretRouteHarness.submit(id: SecretRequestId, secret: TestSecret): String =
        dispatch(SecretRequestRoute.SUBMIT, submitParams(id, secret), HUMAN)

    @Test
    fun aSubmissionIsAnsweredOnceAndAReplayWritesNothing() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        assertEquals(savedResult(), resultOf(harness.submit(id, TestSecret("first-value"))))
        assertEquals(refusalEnvelope("not_pending"), harness.submit(id, TestSecret("replayed-value")))
        assertEquals("first-value", harness.provider.storedText(TARGET_REF))
        assertEquals(1, harness.provider.writes)
        assertEquals(ExternalToolResult.Success("""{"receipt":${savedResult().getValue("receipt")}}"""), harness.status(id))
    }

    @Test
    fun anExpiredRequestRefusesTheValueAndTheAgentLearnsExpired() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        harness.clock.nowMs += SecretRequestLimits.DEFAULT_TTL_MS
        assertEquals(refusalEnvelope("not_pending"), harness.submit(id, TestSecret("late-value")))
        assertEquals(receiptText("expired"), (harness.status(id) as ExternalToolResult.Success).content)
        assertEquals(0, harness.provider.writes)
    }

    @Test
    fun aDeclinedRequestRefusesTheValue() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val declined = harness.dispatch(SecretRequestRoute.CANCEL, idParams(id), HUMAN)
        assertEquals(receiptText("cancelled"), resultOf(declined).toString())
        assertEquals(refusalEnvelope("not_pending"), harness.submit(id, TestSecret("after-decline")))
        assertEquals(refusalEnvelope("not_pending"), harness.dispatch(SecretRequestRoute.CANCEL, idParams(id), HUMAN))
        assertEquals(0, harness.provider.writes)
    }

    @Test
    fun concurrentSubmissionsStoreExactlyOneValueAndWipeEveryOne() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val gate = CompletableDeferred<Unit>()
        harness.provider.putGate = gate
        val first = async { harness.submit(id, TestSecret("racer-0")) }
        harness.provider.putStarted.await()
        val others = (1..CONTENDERS).map { index -> async { harness.submit(id, TestSecret("racer-$index")) } }.awaitAll()
        gate.complete(Unit)
        assertEquals(savedResult(), resultOf(first.await()))
        assertTrue(others.all { it == refusalEnvelope("not_pending") }, "every other submission is refused")
        assertEquals(1, harness.provider.writes)
        assertEquals("racer-0", harness.provider.storedText(TARGET_REF))
        assertEquals(CONTENDERS + 1, harness.parts.decoded.size)
        assertTrue(harness.parts.decoded.all { it.isWiped }, "every decoded value is wiped")
    }

    @Test
    fun aHostRestartMidSubmitSettlesInterruptedAndTheValueMayHaveBeenStored() = runTest {
        val file = directory.resolve("requests.json")
        val before = SecretRouteHarness(SecretRouteParts(journal = FileSecretRequestJournal(file)))
        val id = before.create()
        before.provider.putGate = CompletableDeferred()
        val stuck = launch { before.submit(id, TestSecret("in-flight-value")) }
        before.provider.putStarted.await()

        val after = SecretRouteHarness(SecretRouteParts(journal = FileSecretRequestJournal(file), clock = before.clock))
        assertEquals(receiptText("failed", "interrupted"), (after.status(id) as ExternalToolResult.Success).content)
        assertEquals(refusalEnvelope("not_pending"), after.submit(id, TestSecret("retry-value")))
        assertEquals(0, after.provider.writes)
        stuck.cancel()
    }

    @Test
    fun aNewFieldInAnExistingItemIsRefusedAndNothingIsWritten() = runTest {
        val harness = SecretRouteHarness()
        harness.provider.seed(SecretRef(TEST_VAULT, TARGET_REF.path, SecretField("other_field")), "existing")
        val id = harness.create()
        assertEquals(receiptText("failed", "target_not_writable"), resultOf(harness.submit(id, TestSecret("new-field")))
            .toString())
        assertEquals(0, harness.provider.writes)
        assertEquals(null, harness.provider.storedText(TARGET_REF))
    }

    @Test
    fun anOversizeValueIsRefusedBeforeTheKeystoreIsCalledAndTheRequestStaysPending() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val oversize = TestSecret("x".repeat(SecretRequestLimits.MAX_VALUE_BYTES + 1))
        assertEquals(refusalEnvelope("invalid_value"), harness.submit(id, oversize))
        val tooLongText = valueParams(id, "A".repeat(MAX_TEXT + 4))
        assertEquals(refusalEnvelope("invalid_value"), harness.dispatch(SecretRequestRoute.SUBMIT, tooLongText, HUMAN))
        assertTrue(harness.parts.decoded.isEmpty(), "nothing was decoded")
        kotlin.test.assertFalse(harness.provider.putStarted.isCompleted, "the keystore was never called")
        assertEquals(savedResult(), resultOf(harness.submit(id, TestSecret("y".repeat(SecretRequestLimits.MAX_VALUE_BYTES)))))
    }

    @Test
    fun aValueThatIsNotCanonicalBase64IsRefused() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        for (text in listOf("", "not base64!", "QQ", "QR==", "QUJD\u000A", "QUJ=RA==")) {
            assertEquals(refusalEnvelope("invalid_value"), harness.dispatch(SecretRequestRoute.SUBMIT, valueParams(id, text), HUMAN), text)
        }
        assertEquals(0, harness.provider.writes)
    }

    @Test
    fun paramsMustBeExactlyTheRoutesFieldsSoNoPrincipalOrTargetCanBeSmuggled() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val smuggled = JsonObject(submitParams(id, TestSecret("smuggle")) + ("agentId" to idParams(id).getValue("requestId")))
        val wrongType = buildJsonObject { put(SecretRequestRouteWire.REQUEST_ID, 7) }
        val invalid = refusalEnvelope("invalid_params")
        assertEquals(invalid, harness.dispatch(SecretRequestRoute.SUBMIT, smuggled, HUMAN))
        assertEquals(invalid, harness.dispatch(SecretRequestRoute.GET, wrongType, HUMAN))
        assertEquals(invalid, harness.dispatch(SecretRequestRoute.LIST, idParams(id), HUMAN))
        assertEquals(invalid, harness.dispatch(SecretRequestRoute.SUBMIT, idParams(id), HUMAN))
        assertEquals(0, harness.provider.writes)
    }

    @Test
    fun aMalformedRequestIdIsDeniedLikeAnUnknownOne() = runTest {
        val harness = SecretRouteHarness()
        val malformed = SecretRequestId("sr_short")
        assertEquals(refusalEnvelope("not_pending"), harness.submit(malformed, TestSecret("malformed-id")))
        assertEquals(refusalEnvelope("unknown_request"), harness.dispatch(SecretRequestRoute.GET, idParams(malformed), HUMAN))
        assertEquals(ExternalToolResult.Error("unknown_request"), harness.status(malformed))
        assertTrue(harness.parts.decoded.all { it.isWiped })
    }

    private companion object {
        const val CONTENDERS = 8
        const val MAX_TEXT = com.letta.mobile.data.secrets.wire.SecretValueBase64.MAX_ENCODED_CHARS
    }
}

private fun valueParams(id: SecretRequestId, text: String): JsonObject = buildJsonObject {
    put(SecretRequestRouteWire.REQUEST_ID, id.value)
    put(SecretRequestRouteWire.VALUE, text)
}

/** The status result for a settled request: the receipt and nothing else. */
private fun receiptText(status: String, reason: String? = null): String {
    val reasonField = reason?.let { ""","reason":"$it"""" }.orEmpty()
    return """{"receipt":{"status":"$status","itemId":"team/openai","fieldName":"api_key"$reasonField}}"""
}

private fun savedResult(): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(receiptText("saved")) as JsonObject
