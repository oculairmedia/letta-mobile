package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.util.Telemetry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * letta-mobile-grrhq: the acquisition-provenance chain, proven at the
 * repository seam.
 *
 * The device evidence is a three-line sequence — an acquisition, a
 * `loop.aliasRefused`, and a `getOrCreate.cacheMiss` whose snapshot outcome is
 * ReconciliationRequired(METADATA_INVALID). These tests assert that the three
 * lines carry ONE shared acquisitionId, that the instrumentation changes no
 * holder decision, and that genuinely distinct agents still cannot alias.
 */
class TimelineRepositoryAcquisitionProvenanceTest {

    private val parentAgent = "agent-c356b54a"
    private val childAgent = "agent-597b5756"
    private val conversation = "local-conv-190"

    @BeforeTest
    fun setUp() {
        Telemetry.clear()
        timelineAcquisitionProvenanceEnabled.set(true)
    }

    @AfterTest
    fun tearDown() {
        timelineAcquisitionProvenanceEnabled.set(true)
        Telemetry.clear()
    }

    /**
     * Reproduces the persistence half: the head is found by
     * (backendId, conversationId) but rejected on the agentId check, exactly as
     * RoomConfirmedTimelineStore does, so a second scoped holder gets
     * ReconciliationRequired(METADATA_INVALID).
     */
    private class AgentMismatchStore(private val headAgentId: String) : ConfirmedTimelineStore {
        override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = null

        override suspend fun readSnapshotResult(scope: TimelineScope): ConfirmedTimelineReadResult =
            if (scope.agentId == headAgentId) {
                ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.MISSING)
            } else {
                ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.METADATA_INVALID)
            }

        override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean = true
        override suspend fun deleteSnapshot(scope: TimelineScope) = Unit
        override suspend fun clearForBackend(backendId: String) = Unit
        override suspend fun prune(backendId: String, maxRetainedConversations: Int) = Unit
    }

    private fun newRepo(
        scope: CoroutineScope,
        store: ConfirmedTimelineStore = AgentMismatchStore(parentAgent),
        ids: AcquisitionIdGenerator = AcquisitionIdGenerator { "acq-fixed" },
    ): TimelineRepository = TimelineRepository(
        timelineTransport = EmptyTimelineTransport,
        pendingLocalStore = NoOpPendingLocalStore,
        conversationCursorStore = NoOpConversationCursorStore,
        confirmedTimelineStore = store,
        repositoryScope = scope,
        startLoopStreamSubscribers = false,
        acquisitionIdGenerator = ids,
    )

    private fun events(name: String) = Telemetry.snapshot().filter { it.name == name }

    private fun attr(name: String, key: String): Any? =
        events(name).lastOrNull()?.attrs?.get(key)

    private fun provenance(
        acquisitionId: String,
        source: TimelineAcquisitionSource,
        selectionMode: TimelineConversationSelectionMode = TimelineConversationSelectionMode.UNKNOWN,
        frameFamily: TimelineAcquisitionFrameFamily = TimelineAcquisitionFrameFamily.NONE,
        isReplay: Boolean = false,
        attribution: TimelineConversationAttributionCapture? = null,
    ) = TimelineAcquisitionProvenance(
        acquisitionId = acquisitionId,
        source = source,
        operation = "test.op",
        callSite = "TimelineRepositoryAcquisitionProvenanceTest.kt",
        frameFamily = frameFamily,
        selectionMode = selectionMode,
        isReplay = isReplay,
        attribution = attribution,
    )

    // ------------------------------------------------ the full correlated chain

    @Test
    fun parent_holder_plus_child_attributed_acquisition_emits_a_complete_chain() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = newRepo(scope)
            try {
                // Canonical parent holder for the conversation.
                repo.getOrCreate(parentAgent, conversation, provenance("acq-parent", TimelineAcquisitionSource.UI_NAVIGATION))
                Telemetry.clear()

                // The child-scoped acquisition under investigation.
                val capture = TimelineConversationAttributionCapture(
                    requestedAgentId = childAgent,
                    selectedConversationId = conversation,
                    selectedRecordAgentId = parentAgent,
                    candidateCount = 1,
                    parentAgentId = parentAgent,
                    selectedAlsoAttributedToParent = true,
                    attribution = TimelineConversationAttribution.PARENT_AGENT_ONLY,
                    selectionMode = TimelineConversationSelectionMode.MOST_RECENT_FALLBACK,
                    candidateListSource = TimelineCandidateListSource.AGENT_SCOPED_CACHE,
                    candidateListFreshness = TimelineCandidateListFreshness.FRESH,
                    candidateIdSample = listOf(conversation),
                    candidateIdDigest = "d",
                )
                repo.getOrCreate(
                    childAgent,
                    conversation,
                    provenance(
                        acquisitionId = "acq-child",
                        source = TimelineAcquisitionSource.UI_RESOLVED_ROUTE,
                        selectionMode = TimelineConversationSelectionMode.MOST_RECENT_FALLBACK,
                        attribution = capture,
                    ),
                )

                // 1. acquisition entry
                assertEquals("acq-child", attr("acquisition.entry", "acquisitionId"))
                assertEquals(TimelineAcquisitionSource.UI_RESOLVED_ROUTE.name, attr("acquisition.entry", "source"))

                // 1b. the resolver/route decision, same id
                assertEquals("acq-child", attr("acquisition.conversationAttribution", "acquisitionId"))
                assertEquals(
                    TimelineConversationAttribution.PARENT_AGENT_ONLY.name,
                    attr("acquisition.conversationAttribution", "attribution"),
                )

                // 2. alias refusal, same id, with both competing agents named
                val refusal = events("loop.aliasRefused").lastOrNull()
                assertNotNull(refusal, "the child-scoped acquisition must refuse to alias the parent holder")
                assertEquals("acq-child", refusal.attrs["acquisitionId"])
                assertEquals(parentAgent, refusal.attrs["existingAgentId"])
                assertEquals(childAgent, refusal.attrs["requestedAgentId"])

                // 3. second-holder creation, same id, with the METADATA_INVALID signature
                val miss = events("getOrCreate.cacheMiss").lastOrNull()
                assertNotNull(miss, "the refusal must be followed by a second holder")
                assertEquals("acq-child", miss.attrs["acquisitionId"])
                assertEquals("ReconciliationRequired", miss.attrs["snapshotOutcome"])
                assertEquals(SnapshotReadFailure.METADATA_INVALID.name, miss.attrs["remoteReconciliationReason"])
            } finally {
                scope.cancel()
            }
        }
    }

    // -------------------------------------------------------- source separation

    @Test
    fun navigation_resolver_fallback_replay_fanout_and_unspecified_are_distinguishable() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = newRepo(scope)
            try {
                val cases = listOf(
                    "c-nav" to provenance(
                        "a1", TimelineAcquisitionSource.UI_NAVIGATION,
                        selectionMode = TimelineConversationSelectionMode.EXPLICIT_CONVERSATION_ID,
                        frameFamily = TimelineAcquisitionFrameFamily.DIRECT_NAVIGATION,
                    ),
                    "c-resolved" to provenance(
                        "a2", TimelineAcquisitionSource.UI_RESOLVED_ROUTE,
                        selectionMode = TimelineConversationSelectionMode.MOST_RECENT_FALLBACK,
                    ),
                    "c-replay" to provenance(
                        "a3", TimelineAcquisitionSource.REPLAY_RECONCILE,
                        frameFamily = TimelineAcquisitionFrameFamily.REPLAY, isReplay = true,
                    ),
                    "c-fanout" to provenance(
                        "a4", TimelineAcquisitionSource.RUNTIME_FANOUT,
                        frameFamily = TimelineAcquisitionFrameFamily.CHILD_STREAM_DELTA,
                    ),
                )
                cases.forEach { (conv, prov) -> repo.getOrCreate(parentAgent, conv, prov) }
                // An UNSPECIFIED acquisition: no provenance argument at all.
                repo.getOrCreate(parentAgent, "c-unspecified")

                val byId = events("acquisition.entry").associateBy { it.attrs["acquisitionId"] }
                assertEquals(TimelineAcquisitionSource.UI_NAVIGATION.name, byId["a1"]?.attrs?.get("source"))
                assertEquals(
                    TimelineConversationSelectionMode.EXPLICIT_CONVERSATION_ID.name,
                    byId["a1"]?.attrs?.get("selectionMode"),
                )
                assertEquals(TimelineAcquisitionSource.UI_RESOLVED_ROUTE.name, byId["a2"]?.attrs?.get("source"))
                assertEquals(
                    TimelineConversationSelectionMode.MOST_RECENT_FALLBACK.name,
                    byId["a2"]?.attrs?.get("selectionMode"),
                )
                assertEquals(TimelineAcquisitionSource.REPLAY_RECONCILE.name, byId["a3"]?.attrs?.get("source"))
                assertEquals("true", byId["a3"]?.attrs?.get("isReplay"))
                assertEquals(TimelineAcquisitionSource.RUNTIME_FANOUT.name, byId["a4"]?.attrs?.get("source"))
                assertEquals("false", byId["a4"]?.attrs?.get("isReplay"))

                // UNSPECIFIED is OBSERVABLE, not dropped.
                val unspecified = events("acquisition.entry")
                    .single { it.attrs["conversationId"] == "c-unspecified" }
                assertEquals(TimelineAcquisitionSource.UNSPECIFIED.name, unspecified.attrs["source"])
            } finally {
                scope.cancel()
            }
        }
    }

    // ------------------------------------------------ both creator APIs covered

    @Test
    fun the_second_creator_path_also_emits_provenance() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = newRepo(scope)
            try {
                // warmConversations reaches getOrCreateLoopWithoutHydrate WITHOUT
                // passing through getOrCreate. It has no production caller today;
                // it is covered so the blind spot cannot return silently.
                repo.warmConversations(listOf(parentAgent to "c-warm"))
                val warm = events("acquisition.entry").single { it.attrs["conversationId"] == "c-warm" }
                assertEquals(TimelineAcquisitionSource.WARM.name, warm.attrs["source"])
                assertEquals("getOrCreateLoopWithoutHydrate", warm.attrs["creator"])
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun cursor_repair_reports_replay_reconcile_with_a_minted_id() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = newRepo(scope, ids = AcquisitionIdGenerator { "acq-repair" })
            try {
                runCatching {
                    repo.repairExpiredConversationCursorScoped(parentAgent, "c-repair", fallbackSeq = null)
                }
                val entry = events("acquisition.entry").single { it.attrs["conversationId"] == "c-repair" }
                assertEquals(TimelineAcquisitionSource.REPLAY_RECONCILE.name, entry.attrs["source"])
                assertEquals("acq-repair", entry.attrs["acquisitionId"])
                assertEquals("true", entry.attrs["isReplay"])
            } finally {
                scope.cancel()
            }
        }
    }

    // ------------------------------------------------------- behaviour neutrality

    @Test
    fun instrumentation_changes_no_holder_or_alias_decision() = runTest {
        withContext(Dispatchers.Default) {
            suspend fun run(withProvenance: Boolean): List<Pair<String?, Boolean>> {
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val repo = newRepo(scope)
                return try {
                    val parent = repo.getOrCreate(
                        parentAgent, conversation,
                        if (withProvenance) provenance("p", TimelineAcquisitionSource.UI_NAVIGATION)
                        else TimelineAcquisitionProvenance.UNSPECIFIED,
                    )
                    val unscoped = repo.getOrCreate(null, conversation)
                    val child = repo.getOrCreate(
                        childAgent, conversation,
                        if (withProvenance) provenance("c", TimelineAcquisitionSource.UI_RESOLVED_ROUTE)
                        else TimelineAcquisitionProvenance.UNSPECIFIED,
                    )
                    val parentAgain = repo.getOrCreate(parentAgent, conversation)
                    listOf(
                        // identity relationships, not object addresses
                        "unscoped-aliases-parent" to (unscoped === parent),
                        "child-is-separate-holder" to (child !== parent),
                        "parent-is-stable" to (parentAgain === parent),
                        "loopCount" to (repo.cachedLoopCount() == 2),
                    )
                } finally {
                    scope.cancel()
                }
            }

            val instrumented = run(withProvenance = true)
            Telemetry.clear()
            val bare = run(withProvenance = false)
            assertEquals(
                bare, instrumented,
                "supplying provenance must not change any aliasing or holder decision",
            )
            assertTrue(instrumented.all { it.second }, "expected relationships: $instrumented")
        }
    }

    @Test
    fun genuinely_distinct_scoped_agents_still_refuse_to_alias() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = newRepo(scope)
            try {
                val a = repo.getOrCreate("agent-a", "shared-conv")
                val b = repo.getOrCreate("agent-b", "shared-conv")
                assertNotSame(a, b, "two DIFFERENT scoped agents must never share one holder")
                assertEquals(2, repo.cachedLoopCount())
                assertTrue(
                    events("loop.aliasRefused").isNotEmpty(),
                    "the isolation guard must still fire — this is the invariant the fix must not weaken",
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun same_scoped_agent_still_reuses_one_holder() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = newRepo(scope)
            try {
                val first = repo.getOrCreate("agent-a", "conv-1")
                val second = repo.getOrCreate("agent-a", "conv-1")
                assertSame(first, second)
                assertEquals(1, repo.cachedLoopCount())
            } finally {
                scope.cancel()
            }
        }
    }

    // ------------------------------------------------------- bounding/redaction

    @Test
    fun telemetry_stays_bounded_and_carries_no_raw_metadata() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = newRepo(scope)
            try {
                val secret = "SECRET-BODY-" + "z".repeat(400)
                repo.getOrCreate(
                    childAgent,
                    conversation,
                    TimelineAcquisitionProvenance(
                        acquisitionId = secret,
                        source = TimelineAcquisitionSource.RUNTIME_FANOUT,
                        operation = secret,
                        callSite = secret,
                        parentAgentId = secret,
                        runId = secret,
                        toolCallId = secret,
                        subagentId = secret,
                        otid = secret,
                    ),
                )
                val entry = events("acquisition.entry").single()
                entry.attrs.forEach { (key, value) ->
                    if (value is String) {
                        assertTrue(
                            value.length <= TimelineProvenanceRedaction.MAX_IDENTIFIER_LENGTH,
                            "attr '$key' exceeded the identifier cap: ${value.length}",
                        )
                        assertTrue(
                            !value.contains("SECRET-BODY"),
                            "attr '$key' leaked an unbounded raw value",
                        )
                    }
                }
                // Fixed allowlist: attribute cardinality cannot grow per call.
                assertEquals(
                    setOf(
                        "acquisitionId", "source", "operation", "callSite", "frameFamily",
                        "selectionMode", "agentId", "conversationId", "parentAgentId",
                        "parentConversationId", "runId", "toolCallId", "subagentId", "otid",
                        "isReplay", "attribution", "creator",
                    ),
                    entry.attrs.keys,
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun disabling_the_flag_silences_the_new_events_but_keeps_the_existing_ones() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = newRepo(scope)
            try {
                timelineAcquisitionProvenanceEnabled.set(false)
                repo.getOrCreate(parentAgent, conversation, provenance("x", TimelineAcquisitionSource.UI_NAVIGATION))
                repo.getOrCreate(childAgent, conversation, provenance("y", TimelineAcquisitionSource.UI_NAVIGATION))
                assertTrue(events("acquisition.entry").isEmpty(), "new diagnostic must be silenced")
                assertTrue(
                    events("loop.aliasRefused").isNotEmpty(),
                    "the pre-existing refusal event must keep firing regardless of the flag",
                )
                assertEquals(2, repo.cachedLoopCount(), "the flag must not change holder behavior")
            } finally {
                timelineAcquisitionProvenanceEnabled.set(true)
                scope.cancel()
            }
        }
    }
}
