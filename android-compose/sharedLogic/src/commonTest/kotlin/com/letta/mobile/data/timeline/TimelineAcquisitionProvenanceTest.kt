package com.letta.mobile.data.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-grrhq: pure-reducer contracts for acquisition provenance.
 *
 * The attribution classifier is the A/B/C discriminator for the whole
 * investigation, so it is tested as a pure function independent of the
 * repository, the resolver, and any coroutine machinery.
 */
class TimelineAcquisitionProvenanceTest {

    private val child = "agent-597b5756-2915-4560-ba6b-91005f085166"
    private val parent = "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
    private val conversation = "local-conv-190"

    // ---------------------------------------------------------------- outcome A

    @Test
    fun selected_conversation_owned_only_by_parent_is_PARENT_AGENT_ONLY() {
        val attribution = classifyConversationAttribution(
            selectedConversationId = conversation,
            requestedAgentId = child,
            // The record in the CHILD's cache bucket is attributed to the parent.
            selectedRecordAgentId = parent,
            requestedAgentCandidateIds = emptySet(),
            parentAgentId = parent,
            parentAgentCandidateIds = setOf(conversation),
        )
        assertEquals(TimelineConversationAttribution.PARENT_AGENT_ONLY, attribution)
    }

    @Test
    fun membership_in_the_requested_bucket_does_not_upgrade_parent_only_to_BOTH() {
        // REGRESSION GUARD. The resolver selects FROM the child's own cache
        // bucket, so the selection is always a member of it. If bucket
        // membership counted as requested-attribution, this case would report
        // BOTH and silently reclassify outcome A (a resolver defect) as outcome
        // B (a legitimate dual attribution).
        val attribution = classifyConversationAttribution(
            selectedConversationId = conversation,
            requestedAgentId = child,
            selectedRecordAgentId = parent,
            requestedAgentCandidateIds = setOf(conversation),
            parentAgentId = parent,
            parentAgentCandidateIds = setOf(conversation),
        )
        assertEquals(TimelineConversationAttribution.PARENT_AGENT_ONLY, attribution)
    }

    @Test
    fun bucket_membership_is_the_fallback_when_the_record_has_no_agent() {
        val attribution = classifyConversationAttribution(
            selectedConversationId = conversation,
            requestedAgentId = child,
            selectedRecordAgentId = null,
            requestedAgentCandidateIds = setOf(conversation),
            parentAgentId = parent,
            parentAgentCandidateIds = emptySet(),
        )
        assertEquals(TimelineConversationAttribution.REQUESTED_AGENT_ONLY, attribution)
    }

    // ---------------------------------------------------------------- outcome B

    @Test
    fun conversation_attributed_to_both_agents_is_BOTH() {
        val attribution = classifyConversationAttribution(
            selectedConversationId = conversation,
            requestedAgentId = child,
            selectedRecordAgentId = child,
            requestedAgentCandidateIds = setOf(conversation),
            parentAgentId = parent,
            parentAgentCandidateIds = setOf(conversation),
        )
        assertEquals(
            TimelineConversationAttribution.BOTH,
            attribution,
            "BOTH is the outcome that makes the resolver a NON-defect; it must be " +
                "reachable and must not collapse into REQUESTED_AGENT_ONLY.",
        )
    }

    @Test
    fun BOTH_is_reachable_through_the_record_agent_signal_alone() {
        // The child's cache bucket does not list it, but the record says child,
        // and the parent's bucket does list it. Still dual attribution.
        val attribution = classifyConversationAttribution(
            selectedConversationId = conversation,
            requestedAgentId = child,
            selectedRecordAgentId = child,
            requestedAgentCandidateIds = emptySet(),
            parentAgentId = parent,
            parentAgentCandidateIds = setOf(conversation),
        )
        assertEquals(TimelineConversationAttribution.BOTH, attribution)
    }

    // ------------------------------------------------------- the honest UNKNOWN

    @Test
    fun unknown_parent_reports_UNKNOWN_and_never_fabricates_requested_only() {
        val attribution = classifyConversationAttribution(
            selectedConversationId = conversation,
            requestedAgentId = child,
            selectedRecordAgentId = child,
            requestedAgentCandidateIds = setOf(conversation),
            parentAgentId = null,
            parentAgentCandidateIds = emptySet(),
        )
        assertEquals(
            TimelineConversationAttribution.UNKNOWN,
            attribution,
            "Without a known other claimant, 'only the requested agent' is not " +
                "assertable — reporting REQUESTED_AGENT_ONLY here would mask outcome B.",
        )
    }

    @Test
    fun requested_only_and_neither_are_distinguishable() {
        assertEquals(
            TimelineConversationAttribution.REQUESTED_AGENT_ONLY,
            classifyConversationAttribution(
                selectedConversationId = conversation,
                requestedAgentId = child,
                selectedRecordAgentId = child,
                requestedAgentCandidateIds = setOf(conversation),
                parentAgentId = parent,
                parentAgentCandidateIds = setOf("local-conv-7"),
            ),
        )
        assertEquals(
            TimelineConversationAttribution.NEITHER,
            classifyConversationAttribution(
                selectedConversationId = conversation,
                requestedAgentId = child,
                selectedRecordAgentId = "agent-someone-else",
                requestedAgentCandidateIds = setOf("local-conv-3"),
                parentAgentId = parent,
                parentAgentCandidateIds = setOf("local-conv-7"),
            ),
        )
    }

    @Test
    fun blank_selection_is_UNKNOWN() {
        assertEquals(
            TimelineConversationAttribution.UNKNOWN,
            classifyConversationAttribution(null, child, null, emptySet(), parent, emptySet()),
        )
        assertEquals(
            TimelineConversationAttribution.UNKNOWN,
            classifyConversationAttribution("", child, null, emptySet(), parent, emptySet()),
        )
    }

    // ------------------------------------------------------- bounding/redaction

    @Test
    fun over_long_identifiers_are_hashed_not_truncated() {
        val long = "x".repeat(TimelineProvenanceRedaction.MAX_IDENTIFIER_LENGTH + 1)
        val bounded = TimelineProvenanceRedaction.boundedIdentifier(long)
        assertTrue(bounded.startsWith("h:"), "expected a hash marker, got '$bounded'")
        assertTrue(
            bounded.length <= TimelineProvenanceRedaction.MAX_IDENTIFIER_LENGTH,
            "bounded identifier must respect the cap, got ${bounded.length}",
        )
        assertTrue(!bounded.contains(long), "raw over-long value must not survive")
    }

    @Test
    fun identifier_at_the_cap_is_passed_through_verbatim() {
        val exact = "y".repeat(TimelineProvenanceRedaction.MAX_IDENTIFIER_LENGTH)
        assertEquals(exact, TimelineProvenanceRedaction.boundedIdentifier(exact))
    }

    @Test
    fun null_and_empty_identifiers_bound_to_empty_string() {
        assertEquals("", TimelineProvenanceRedaction.boundedIdentifier(null))
        assertEquals("", TimelineProvenanceRedaction.boundedIdentifier(""))
    }

    @Test
    fun candidate_sample_is_capped() {
        val many = (1..100).map { "local-conv-$it" }
        val sample = TimelineProvenanceRedaction.boundedSample(many)
        assertEquals(TimelineProvenanceRedaction.MAX_CANDIDATE_SAMPLE, sample.size)
    }

    @Test
    fun candidate_digest_is_order_independent_and_set_sensitive() {
        val a = listOf("local-conv-1", "local-conv-2", "local-conv-3")
        assertEquals(
            TimelineProvenanceRedaction.setDigest(a),
            TimelineProvenanceRedaction.setDigest(a.reversed()),
            "digest must not depend on ordering",
        )
        assertNotEquals(
            TimelineProvenanceRedaction.setDigest(a),
            TimelineProvenanceRedaction.setDigest(a + "local-conv-4"),
            "a different candidate set must produce a different digest",
        )
        assertEquals("0", TimelineProvenanceRedaction.setDigest(emptyList()))
    }

    // ------------------------------------------------------------- id generator

    @Test
    fun acquisition_ids_are_unique_and_bounded() {
        val generator = SequentialAcquisitionIdGenerator(prefix = "test")
        val ids = (1..500).map { generator.next() }
        assertEquals(ids.size, ids.toSet().size, "acquisition ids must not collide")
        ids.forEach {
            assertTrue(
                it.length <= TimelineProvenanceRedaction.MAX_IDENTIFIER_LENGTH,
                "acquisition id must stay within the identifier cap: $it",
            )
        }
    }

    // ------------------------------------------------------------- source model

    @Test
    fun default_provenance_is_observably_unspecified() {
        assertEquals(
            TimelineAcquisitionSource.UNSPECIFIED,
            TimelineAcquisitionProvenance.UNSPECIFIED.source,
            "an uninstrumented call site must be observable, not silently ignored",
        )
    }

    @Test
    fun the_sources_the_rerun_must_separate_are_distinct() {
        val distinct = setOf(
            TimelineAcquisitionSource.RUNTIME_FANOUT,
            TimelineAcquisitionSource.UI_NAVIGATION,
            TimelineAcquisitionSource.UI_RESOLVED_ROUTE,
            TimelineAcquisitionSource.REPLAY_RECONCILE,
            TimelineAcquisitionSource.UNSPECIFIED,
        )
        assertEquals(5, distinct.size)
    }
}
