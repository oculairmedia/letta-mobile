package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Drives the form controller down every exit path with a sentinel typed in, checking each time
 * that the buffer and the handed-over [SecretInput] are zeroed, and that no state, no
 * `toString()` and no telemetry event ever holds the sentinel. The fake host's [FakeSecretRequestClient.received]
 * is the only place it may reach.
 */
class SecretFormControllerTest {
    private val recorder = TelemetryRecorder()
    private lateinit var recording: AutoCloseable
    private val observed = mutableListOf<String>()

    @BeforeTest
    fun capture() {
        recording = Telemetry.installRecorder(recorder)
    }

    @AfterTest
    fun release() {
        recording.close()
    }

    /** A clock that reads the test scheduler, so the expiry timer and the overdue check agree. */
    private class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(SecretRequestTestData.NOW.toEpochMilliseconds() + scheduler.currentTime)
    }

    private class Form(val controller: SecretFormController, val client: FakeSecretRequestClient)

    private fun TestScope.form(record: SecretRequestRecord = SecretRequestTestData.record(), scope: CoroutineScope = this): Form {
        val client = FakeSecretRequestClient(record)
        return Form(SecretFormController(record, client, scope, SchedulerClock(testScheduler)), client)
    }

    private fun Form.type(text: String) {
        controller.open()
        controller.edit(at(0), text)
        observe()
    }

    private fun Form.observe() {
        observed += controller.state.value.toString()
        observed += controller.toString()
    }

    private fun Form.bufferLength(): Int = controller.reveal { it.length }

    private fun assertNoSentinelAnywhere() {
        val telemetry = recorder.snapshot().flatMap { event -> listOf(event.toLine()) + event.attrs.values.map { it.toString() } }
        assertFalse((observed + telemetry).any { it.contains(SENTINEL) }, "a state, toString or telemetry event holds the sentinel")
    }

    @Test
    fun aSavedSubmissionWipesTheBufferAndTheInputAndShowsOnlyTheReceipt() = runTest {
        val form = form()
        form.type(SENTINEL)
        assertTrue(form.controller.submit())
        assertEquals(0, form.bufferLength(), "the buffer is zeroed before the call starts")
        runCurrent()
        form.observe()
        assertEquals(SecretFormState.Done(FakeSecretRequestClient.receipt(SecretReceiptStatus.SAVED)), form.controller.state.value)
        assertEquals(listOf(SENTINEL), form.client.received)
        assertTrue(form.client.inputs.single().isWiped)
        assertNoSentinelAnywhere()
    }

    @Test
    fun aFailedWriteWipesEverythingAndCannotBeRetried() = runTest {
        val form = form()
        val locked = FakeSecretRequestClient.receipt(SecretReceiptStatus.FAILED).copy(reason = SecretFailureReason.VAULT_LOCKED)
        form.client.answer = { SecretClientOutcome.Settled(locked) }
        form.type(SENTINEL)
        form.controller.submit()
        runCurrent()
        form.controller.retry()
        form.observe()
        assertEquals(SecretFormState.Failed(SecretFormFailure.WriteFailed(SecretFailureReason.VAULT_LOCKED)), form.controller.state.value)
        assertTrue(form.client.inputs.single().isWiped)
        assertEquals(0, form.bufferLength())
        assertNoSentinelAnywhere()
    }

    @Test
    fun aThrowingClientIsUnreachableWipesTheInputAndRetryStartsEmpty() = runTest {
        val form = form()
        form.client.answer = { error("transport failed for $SENTINEL") }
        form.type(SENTINEL)
        form.controller.submit()
        runCurrent()
        form.observe()
        assertEquals(SecretFormState.Failed(SecretFormFailure.Unreachable), form.controller.state.value)
        assertTrue(form.client.inputs.single().isWiped)
        form.controller.retry()
        assertEquals(SecretFormState.Editing(SecretInputPresence.EMPTY), form.controller.state.value)
        assertEquals(0, form.bufferLength())
        assertNoSentinelAnywhere()
    }

    @Test
    fun declineWipesTheBufferAndTellsTheHost() = runTest {
        val form = form()
        form.type(SENTINEL)
        form.controller.decline()
        runCurrent()
        form.observe()
        assertEquals(SecretFormState.Declined, form.controller.state.value)
        assertEquals(0, form.bufferLength())
        assertEquals(listOf(SecretRequestTestData.id(1)), form.client.declined)
        assertTrue(form.client.received.isEmpty())
        assertNoSentinelAnywhere()
    }

    @Test
    fun theExpiryTimerWipesTheBufferAndASubmitAfterItDoesNothing() = runTest {
        val form = form()
        form.type(SENTINEL)
        advanceTimeBy(SecretRequestLimits.DEFAULT_TTL_MS + 1)
        form.observe()
        assertEquals(SecretFormState.Expired, form.controller.state.value)
        assertEquals(0, form.bufferLength())
        assertFalse(form.controller.submit())
        assertTrue(form.client.inputs.isEmpty())
        assertNoSentinelAnywhere()
    }

    @Test
    fun aRequestGoneFromTheHostAndDisposalBothWipeTheBuffer() = runTest {
        val gone = form()
        gone.type(SENTINEL)
        gone.controller.expire()
        val disposed = form()
        disposed.type(SENTINEL)
        disposed.controller.dispose()
        gone.observe()
        disposed.observe()
        assertEquals(SecretFormState.Expired, gone.controller.state.value)
        assertEquals(0, gone.bufferLength())
        assertEquals(0, disposed.bufferLength())
        assertEquals(SecretFormState.Editing(SecretInputPresence.EMPTY), disposed.controller.state.value)
        assertFalse(disposed.controller.submit(), "a disposed form has nothing to send")
        assertNoSentinelAnywhere()
    }

    @Test
    fun aStaleRequestCannotBeOpenedOrSubmitted() = runTest {
        val fresh = SecretRequestTestData.record()
        val stale = fresh.copy(timing = fresh.timing.copy(expiresAtMs = SecretRequestTestData.NOW.toEpochMilliseconds()))
        val form = form(stale)
        form.controller.open()
        assertEquals(SecretFormState.Expired, form.controller.state.value)
        assertFalse(form.controller.edit(at(0), SENTINEL))
        assertFalse(form.controller.submit())
        assertTrue(form.client.inputs.isEmpty())
    }

    @Test
    fun aDoubleSubmitSendsOnceAndAnEmptyFieldSendsNothing() = runTest {
        val form = form()
        form.controller.open()
        assertFalse(form.controller.submit(), "an empty field cannot be submitted")
        form.controller.edit(at(0), SENTINEL)
        assertTrue(form.controller.submit())
        assertFalse(form.controller.submit())
        form.controller.decline()
        runCurrent()
        assertEquals(1, form.client.inputs.size)
        assertTrue(form.client.declined.isEmpty(), "a decline while submitting does nothing")
    }

    @Test
    fun cancellingAnInFlightSubmissionStillWipesTheInput() = runTest {
        val formScope = CoroutineScope(coroutineContext + Job())
        val form = form(scope = formScope)
        val never = CompletableDeferred<SecretClientOutcome>()
        form.client.answer = { never.await() }
        form.type(SENTINEL)
        form.controller.submit()
        runCurrent()
        formScope.cancel()
        runCurrent()
        assertTrue(form.client.inputs.single().isWiped)
        assertEquals(SecretFormState.Submitting, form.controller.state.value)
    }

    @Test
    fun theTargetComesFromTheRequest() = runTest {
        val form = form()
        assertEquals(SecretRequestTestData.TARGET, form.controller.target)
        assertEquals(SecretRequestTestData.id(1), form.controller.requestId)
    }

    private companion object {
        const val SENTINEL = "SENTINEL-form-4be1d2"
    }
}
