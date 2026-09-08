package com.letta.mobile.di

import com.letta.mobile.feature.chat.coordination.SelectedChatRuntime
import com.letta.mobile.feature.chat.screen.ChatPagingHost
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelectedRuntimeBindingTest {
    @Test fun migrationStepBudgetFailsExplicitlyAndRetryCanResume() = runTest {
        var steps = 0
        try {
            boundedSteps("copy", 3) { steps++; false }
            fail("Unbounded migration accepted")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("budget exhausted"))
        }
        assertEquals(3, steps)
        boundedSteps("copy", 3) { ++steps == 5 }
        assertEquals(5, steps)
    }

    @Test fun migrationSliceYieldsWithoutFailingReadiness() = runTest {
        var steps = 0
        boundedSteps("copy", maxSteps = 10_000) { ++steps == 300 }
        assertEquals(300, steps)
    }

    @Test fun migrationFailureDoesNotConsumeRemainingBudget() = runTest {
        var calls = 0
        try {
            boundedSteps("validation") { calls++; error("certificate mismatch") }
            fail("Validation failure swallowed")
        } catch (failure: IllegalStateException) {
            assertEquals("certificate mismatch", failure.message)
        }
        assertEquals(1, calls)
    }

    @Test fun subscriptionsCaptureSeparateHandlesAndConsumerOwnsRetirement() = runTest {
        val firstGraph = Any()
        val graphs = MutableStateFlow(firstGraph)
        val created = mutableListOf<SelectedChatRuntime>()
        fun flow() = SubscriberRuntimeFlow(graphs) {
            mockk<SelectedChatRuntime>(relaxed = true).also { created += it }
        }
        val first = flow()
        val second = flow()
        assertNotSame(first.value, second.value)
        val old = checkNotNull(first.value)
        var observed: SelectedChatRuntime? = null
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { first.collect { observed = it } }
        assertSame(old, observed)
        graphs.value = Any()
        runCurrent()
        assertNotSame(old, observed)
        coVerify(exactly = 0) { old.retire() }
        collector.cancelAndJoin()
        coVerify(exactly = 0) { old.retire() }
    }

    @Test fun capturedWriterRejectsForeignAgentBeforeResolution() = runTest {
        var resolutions = 0
        val writer = SelectedRuntimeWriter("agent-a") { resolutions++; error("not expected") }
        try {
            writer.markExternalTransportLocalSent("agent-b", "conversation", "otid")
            fail("Foreign agent accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, resolutions)
    }

    @Test fun expiryWithoutExpectedWatermarkIsRejectedBeforeDelegate() = runTest {
        val delegate = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>()
        val writer = SelectedRuntimeWriter("agent") { delegate }
        try {
            writer.repairExpiredConversationCursor("conversation", 10)
            fail("Unfenced cursor repair accepted")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains("expected watermark"))
        }
        coVerify(exactly = 0) { delegate.repairExpiredConversationCursorScoped(any(), any(), any()) }
        coVerify(exactly = 0) { delegate.repairExpiredConversationCursorScoped(any(), any(), any(), any()) }
    }

    @Test fun expiryWithExpectedWatermarkDelegatesThroughResolvedWriter() = runTest {
        val delegate = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>(relaxed = true)
        val writer = SelectedRuntimeWriter("agent") { delegate }
        writer.repairExpiredConversationCursorScoped("agent", "conversation", fallbackSeq = 12L, expectedWatermark = 4L)
        coVerify(exactly = 1) {
            delegate.repairExpiredConversationCursorScoped("agent", "conversation", 12L, 4L)
        }
        coVerify(exactly = 0) { delegate.repairExpiredConversationCursorScoped(any(), any(), any()) }
    }

    @Test fun manifestOnlyReadinessDefersWithoutDrainOrEnvelopeDecode() = runTest {
        val scope = com.letta.mobile.data.timeline.snapshot.TimelineScope("backend", "conversation", "agent")
        val storage = mockk<com.letta.mobile.data.local.TimelineOwnedStorageFactory>()
        val authority = mockk<com.letta.mobile.data.local.TimelineOwnershipAuthority>()
        val repository = mockk<com.letta.mobile.data.timeline.TimelineRepository>(relaxed = true)
        val transport = mockk<com.letta.mobile.data.timeline.GenerationTimelineTransport>(relaxed = true)
        coEvery { authority.state(any()) } returns com.letta.mobile.data.local.TimelineOwnershipAuthority.State(
            scope, 0, com.letta.mobile.data.local.TimelineOwnershipAuthority.Phase.Legacy,
        )
        coEvery { storage.classifyCopySource(any()) } returns com.letta.mobile.data.local.LegacyLedgerCopyHead(
            "legacy-manifest", 0, false, com.letta.mobile.data.local.LegacyLedgerCopyKind.ManifestOnly,
        )
        val runtime = AndroidCanonicalTimelineRuntime(
            "backend", transport, repository, authority, storage, backgroundScope, "legacy",
        )
        val result = runtime.bind(scope, repairCommittedCursor = { _, _, _ -> }, reportFailure = {})
        assertEquals(AndroidCanonicalTimelineRuntime.BindResult.LegacyDeferred, result)
        assertEquals(0L, runtime.lastMeasurement.envelopeDecodes)
        assertEquals(0L, runtime.lastMeasurement.copyBytes)
        assertEquals(0L, runtime.lastMeasurement.convertBytes)
        assertEquals(0, runtime.lastMeasurement.copyRows)
        assertEquals(0, runtime.lastMeasurement.copySteps)
        coVerify(exactly = 1) { storage.classifyCopySource(any()) }
        coVerify(exactly = 0) { repository.drainForCanonicalHandoff(any()) }
        coVerify(exactly = 0) { storage.beginMappedMigrationAfterDrain(any(), any()) }
        runtime.retire()
    }

    @Test fun readinessMeasurementCountsEnvelopeDecodeDuringClassify() = runTest {
        val scope = com.letta.mobile.data.timeline.snapshot.TimelineScope("backend", "conversation", "agent")
        val storage = mockk<com.letta.mobile.data.local.TimelineOwnedStorageFactory>()
        val authority = mockk<com.letta.mobile.data.local.TimelineOwnershipAuthority>()
        val repository = mockk<com.letta.mobile.data.timeline.TimelineRepository>(relaxed = true)
        val transport = mockk<com.letta.mobile.data.timeline.GenerationTimelineTransport>(relaxed = true)
        coEvery { authority.state(any()) } returns com.letta.mobile.data.local.TimelineOwnershipAuthority.State(
            scope, 0, com.letta.mobile.data.local.TimelineOwnershipAuthority.Phase.Legacy,
        )
        val encoded = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.encode(
            com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope(scope = scope, revision = 1),
        )
        coEvery { storage.classifyCopySource(any()) } answers {
            checkNotNull(com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.decode(encoded))
            com.letta.mobile.data.local.LegacyLedgerCopyHead(
                "legacy-manifest", 0, false, com.letta.mobile.data.local.LegacyLedgerCopyKind.ManifestOnly,
            )
        }
        val runtime = AndroidCanonicalTimelineRuntime(
            "backend", transport, repository, authority, storage, backgroundScope, "legacy",
        )
        val result = runtime.bind(scope, repairCommittedCursor = { _, _, _ -> }, reportFailure = {})
        assertEquals(AndroidCanonicalTimelineRuntime.BindResult.LegacyDeferred, result)
        assertEquals(1L, runtime.lastMeasurement.envelopeDecodes)
        runtime.retire()
    }

    @Test fun capturedDeferredOpenAndSendUseLegacyWriterWithoutCanonicalWrites() = runTest {
        val canonicalWriter = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>(relaxed = true)
        val legacyWriter = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>(relaxed = true)
        val presentation = ChatPagingPresentation(
            settled = flowOf(androidx.paging.PagingData.empty()),
            live = MutableStateFlow(emptyList()),
            close = {},
        )
        val binding = mockk<AndroidCanonicalTimelineRuntime.Binding>()
        every { binding.writer } returns canonicalWriter
        every { binding.bindPresentation(any()) } answers {
            (invocation.args[0] as ChatPagingHost).openCanonical = { _, _, _, _ -> presentation }
        }
        val captured = CapturedSelectedChatRuntime(
            generation = 7L,
            config = mockk(relaxed = true),
            descriptor = mockk(relaxed = true),
            parent = this,
            agent = "agent",
            legacyWriter = legacyWriter,
            hooks = ConversationBindHooks(
                bind = { conversation ->
                    if (conversation == "deferred") AndroidCanonicalTimelineRuntime.BindResult.LegacyDeferred
                    else AndroidCanonicalTimelineRuntime.BindResult.Canonical(binding)
                },
                retire = {},
            ),
        )
        assertEquals(
            com.letta.mobile.feature.chat.coordination.SelectedTimelineRoute.LegacyDeferred,
            captured.ready("deferred"),
        )
        try {
            captured.open("deferred", null, this)
            fail("Deferred conversation opened canonical paging")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("legacy observer"))
        }
        captured.writer.markExternalTransportLocalSent("deferred", "otid")
        coVerify(exactly = 1) { legacyWriter.markExternalTransportLocalSent("agent", "deferred", "otid") }
        coVerify(exactly = 0) { canonicalWriter.markExternalTransportLocalSent(any(), any(), any()) }
        captured.retire()
        try {
            captured.writer.markExternalTransportLocalSent("deferred", "stale")
            fail("Retired captured runtime accepted a send")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("retired"))
        }
    }

    @Test fun capturedNormalizedOpenAndSendStayOnCanonicalTogether() = runTest {
        val canonicalWriter = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>(relaxed = true)
        val legacyWriter = mockk<com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter>(relaxed = true)
        val presentation = ChatPagingPresentation(
            settled = flowOf(androidx.paging.PagingData.empty()),
            live = MutableStateFlow(emptyList()),
            close = {},
        )
        val binding = mockk<AndroidCanonicalTimelineRuntime.Binding>()
        every { binding.writer } returns canonicalWriter
        every { binding.bindPresentation(any()) } answers {
            (invocation.args[0] as ChatPagingHost).openCanonical = { _, _, _, _ -> presentation }
        }
        val captured = CapturedSelectedChatRuntime(
            generation = 8L,
            config = mockk(relaxed = true),
            descriptor = mockk(relaxed = true),
            parent = this,
            agent = "agent",
            legacyWriter = legacyWriter,
            hooks = ConversationBindHooks(
                bind = { AndroidCanonicalTimelineRuntime.BindResult.Canonical(binding) },
                retire = {},
            ),
        )
        assertEquals(
            com.letta.mobile.feature.chat.coordination.SelectedTimelineRoute.Canonical,
            captured.ready("normalized"),
        )
        assertSame(presentation, captured.open("normalized", "target", this))
        captured.writer.markExternalTransportLocalSent("normalized", "otid")
        coVerify(exactly = 1) { canonicalWriter.markExternalTransportLocalSent("agent", "normalized", "otid") }
        coVerify(exactly = 0) { legacyWriter.markExternalTransportLocalSent(any(), any(), any()) }
        captured.retire()
    }
}
