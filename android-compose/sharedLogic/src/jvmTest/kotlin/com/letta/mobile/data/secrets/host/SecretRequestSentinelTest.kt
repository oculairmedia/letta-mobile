package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.journal.FileSecretRequestJournal
import com.letta.mobile.data.secrets.request.PrincipalAccess
import com.letta.mobile.data.secrets.request.PrincipalOrigin
import com.letta.mobile.data.secrets.journal.SecretRequestJournalException
import com.letta.mobile.data.secrets.request.SecretRequestPrincipal
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.SecretValue
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sends sentinel values through every service path against a durable journal, a capturing
 * telemetry recorder, then scans every observable sink for the sentinel in
 * its raw, base64, base64url and hex forms. The test vault itself is the only place it may be.
 */
class SecretRequestSentinelTest {
    private val directory: Path = Files.createTempDirectory("secret-request-sentinel")
    private val recorder = TelemetryRecorder()
    private lateinit var recording: AutoCloseable
    private val observed = mutableListOf<String>()

    private val sentinels = listOf("happy", "failed", "mismatch", "replay", "agent", "expired", "throw")
        .map { Sentinel("SENTINEL-sr-7f3a9c-$it") }

    @BeforeTest
    fun capture() {
        recording = Telemetry.installRecorder(recorder)
    }

    @AfterTest
    fun release() {
        recording.close()
        directory.toFile().deleteRecursively()
    }

    private fun observe(vararg values: Any?) {
        values.forEach { observed += it.toString() }
    }

    private fun forms(sentinel: Sentinel): List<String> {
        val bytes = sentinel.bytes()
        return listOf(
            sentinel.text,
            Base64.getEncoder().encodeToString(bytes),
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            bytes.joinToString("") { "%02x".format(it) },
        )
    }

    private suspend fun submitThrough(harness: ServiceHarness, sentinel: Sentinel, principal: SecretRequestPrincipal = HUMAN) {
        val id = harness.pending()
        val value = SecretValue.ofBytes(sentinel.bytes())
        observe(value, harness.service.submit(principal, id, value), value)
        observe(harness.service.receipt(REQUESTER, id), harness.service.get(HUMAN, id), harness.service.list(HUMAN))
        assertTrue(value.isWiped, sentinel.text)
    }

    @Test
    fun noSentinelReachesTheJournalTelemetryLogsResultsErrorsOrAnyToString() = runTest {
        val clock = TestClock()
        val random = CountingRandom()
        val agentPrincipal = SecretRequestPrincipal(HUMAN.endpoint, PrincipalOrigin.AGENT_TOOL_CALL, PrincipalAccess.ADMIN_FULL)
        fun harness(provider: TestKeystoreProvider = TestKeystoreProvider()) =
            ServiceHarness(FileSecretRequestJournal(directory.resolve("requests.json")), provider, clock, random)

        val vault = TestKeystoreProvider()
        submitThrough(harness(vault), sentinels[0])
        submitThrough(harness(TestKeystoreProvider().also { it.failWith = KeystoreError.Locked }), sentinels[1])
        submitThrough(harness(TestKeystoreProvider().also { it.corruptReadBack = true }), sentinels[2])
        val replayHarness = harness(vault)
        val answered = replayHarness.service.list(HUMAN) as SecretServiceResult.Ok
        val replayValue = SecretValue.ofBytes(sentinels[3].bytes())
        observe(replayHarness.service.submit(HUMAN, answered.value.requests.last().requestId, replayValue), replayValue)
        submitThrough(harness(vault), sentinels[4], agentPrincipal)
        val expiredHarness = harness(vault)
        val expiredId = expiredHarness.pending()
        clock.nowMs += 60L * 60 * 1000
        observe(expiredHarness.service.submit(HUMAN, expiredId, SecretValue.ofBytes(sentinels[5].bytes())))
        submitThrough(harness(TestKeystoreProvider().also { it.throwOnCall = true }), sentinels[6])
        observe(expiredHarness.service.awaitReceipt(REQUESTER, expiredId), expiredHarness.service.receipt(BYSTANDER, expiredId))

        Files.writeString(directory.resolve("requests.json.pending"), "barrier")
        val journalError = assertFailsWith<SecretRequestJournalException> {
            FileSecretRequestJournal(directory.resolve("requests.json")).read()
        }
        observe(journalError, journalError.cause, harness(vault).service.submit(HUMAN, expiredId, secretOf(sentinels[0].text)))
        observe(HUMAN, agentPrincipal, REQUESTER, vault.lent.joinToString())

        assertEquals(sentinels[0].text, vault.storedText(TARGET_REF), "the sentinel did reach the vault")
        val journalBytes = directory.toFile().walkTopDown().filter { it.isFile }
            .map { String(it.readBytes(), Charsets.ISO_8859_1) }.toList()
        assertTrue(journalBytes.isNotEmpty(), "the journal was written")
        // Each event as the log line it is written as, its attributes, and anything a logged throwable would print.
        val telemetry = recorder.snapshot().flatMap { event ->
            listOf(event.toLine(), event.throwable?.stackTraceToString().orEmpty()) + event.attrs.values.map { it.toString() }
        }
        assertTrue(telemetry.any { it.contains("request.settled") }, "the recorder captured the service's events")
        val sinks = mapOf("journal" to journalBytes, "telemetry" to telemetry, "results" to observed)
        for ((sink, texts) in sinks) {
            for (form in sentinels.flatMap(::forms)) {
                assertFalse(texts.any { it.contains(form) }, "$sink holds a sentinel form")
            }
        }
    }

    @Test
    fun theTestDataIsNotASentinel() {
        val texts = listOf(SecretRequestTestData.TARGET.toString(), SecretRequestTestData.request().toString())
        assertFalse(texts.any { text -> sentinels.any { it.text in text } })
    }

    /** One marker value; typed so the helpers take a sentinel, not any string. */
    private class Sentinel(val text: String) {
        fun bytes(): ByteArray = text.encodeToByteArray()
    }
}
