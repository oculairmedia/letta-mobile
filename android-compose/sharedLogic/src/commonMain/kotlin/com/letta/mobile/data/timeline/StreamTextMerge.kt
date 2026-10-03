package com.letta.mobile.data.timeline

/**
 * Branch taken while merging a streamed text frame into an existing timeline
 * event. Kept as production code so diagnostics and the live reducer cannot
 * drift apart.
 */
enum class StreamTextMergeBranch {
    EMPTY_INCOMING,
    EQUAL,
    CUMULATIVE,
    STALE,
    SUFFIX_DUPLICATE,
    // letta-mobile-k9y5d: two seq-id-carrying snapshots that share no clean
    // prefix/suffix relationship. Appending them would duplicate/garble the
    // text, so we keep the longer (more complete) snapshot instead.
    SNAPSHOT_CONFLICT,
    // letta-mobile-bglj6.1.12: a newer snapshot of a cumulative stream that shares
    // a long opening with the text held, then says something else. The reply was
    // rewritten upstream; the newer body replaces the old one.
    SNAPSHOT_REWRITE,
    APPEND,
}

data class StreamTextMergeResult(
    val text: String,
    val branch: StreamTextMergeBranch,
    val garbleRisk: Boolean,
)

/**
 * Merge streamed text using the same rule for TimelineStreamReducer and CLI
 * diagnostics.
 *
 * Snapshot-style merges are only safe when both frames carry seq ids. Without
 * that ordering signal, unrelated deltas must append even if they happen to
 * resemble a prefix/suffix.
 *
 * letta-mobile-k9y5d: [incomingIsForwardDelta] tells us whether the incoming
 * frame is genuinely newer than the existing text (a higher seq id). A
 * forward delta that shares no prefix/suffix is an incremental continuation
 * and must APPEND (e.g. "Y" + "es ..." -> "Yes ..."). A NON-forward frame
 * (lower-or-equal seq id) that shares no clean prefix/suffix is a replayed /
 * re-delivered snapshot colliding with a stranded partial; appending it would
 * duplicate/garble the body, so we keep the longer (complete) text instead.
 * Defaults to true so existing callers keep the historical append behaviour.
 *
 * letta-mobile-mvcr4: a forward (higher-seq) snapshot whose body overlaps
 * the existing text WITHOUT a clean prefix/suffix relationship is also
 * effectively a non-clean snapshot — the same upstream re-tokenization or
 * repair can produce a near-match either at the start or mid/tail. With
 * the original APPEND rule, we duplicated the partial body and the
 * reveal/smoother downstream then visibly dropped the duplicated chars
 * (e.g. "complet " + "completed" -> "complet complet ed" -> rendered
 * truncated). Treat such near-overlap forward snapshots the same as
 * SNAPSHOT_CONFLICT: keep the longer complete text. This is strictly
 * safer than APPEND for seq-carrying snapshots and does not regress
 * forward delta appending because genuine forward deltas that share no
 * prefix/suffix (e.g. "Y" + "es ...") are exactly the case the test
 * suite guards; any near-match there is a coalesced snapshot, not an
 * increment.
 */
/**
 * letta-mobile-h30cy (the reducer-side token drop): when the stream delivers
 * INCREMENTAL single-token deltas (the live Iroh assistant path: each frame is a
 * new token like "I", "'m", " Lester" under one stable otid, NOT a cumulative
 * snapshot), a forward token that COINCIDENTALLY equals a prefix of the
 * accumulated text ("I" after "...bindings).\n\n", where the reply already
 * starts with "I'm") was misclassified as a STALE prefix-snapshot and DROPPED —
 * so the streamed row silently lost that character. Downstream, the reconciled
 * message.list final (the full text) then no longer matched the streamed row as
 * a prefix/superset, and mergeServerMessages appended it as a DUPLICATE row.
 *
 * [incrementalForwardAppend] tells the merge that this stream is incremental
 * (append-mode), so a genuine FORWARD delta must never be dropped as a STALE
 * prefix or a SUFFIX duplicate just because its bytes happen to coincide with the
 * start/end of the accumulated text — those coincidences are new tokens to
 * append, not re-delivered snapshots. Cumulative growth (incoming.startsWith
 * existing → CUMULATIVE) and non-forward re-deliveries are unaffected, so the
 * stable-id cumulative snapshot path (WS) must leave this false.
 *
 * letta-mobile-bn008 / letta-mobile-wucn: [isCumulativeStream] is the
 * CUMULATIVE-STREAM SHAPE signal that the upstream caller must derive from a
 * stream-shape property (NOT from per-frame seq-id availability). The Iroh
 * client boundary stamps a stable `cm-stream-<otid>` id and a stable
 * synthesized otid on every cumulative frame (the App Server's
 * `CumulativeStreamText` accumulator emits the cumulative text on every
 * wire-frame, and `tagStreamDeltaForOptimisticDedup` rewrites the id to
 * `cm-stream-<otid>`); an incremental HTTP-API SSE stream only stamps the
 * otid on the first frame and leaves subsequent frames otid=null. So
 *     confirmed.otid != null && confirmed.otid == existing.otid
 * is a reliable shape signal: true on a cumulative stream, false on an
 * incremental stream. The reducer derives this once per frame and passes it
 * here. EQUAL stays gated on (isCumulativeStream || canUseSnapshotMerge)
 * — true on EITHER an explicit ordering signal (seq ids on both sides) OR a
 * stream-shape signal (stable otid confirms the upstream is cumulative). A
 * strictly longer body that already starts with the accumulated text is always
 * CUMULATIVE: that chunk is a snapshot of the reply, and appending it stacks
 * copies. An identical chunk is not — an incremental token can repeat the
 * accumulator and must still append (wucn). The
 * seq-gated branches below (STALE, SUFFIX_DUPLICATE, SNAPSHOT_CONFLICT) remain
 * gated on canUseSnapshotMerge only, because those can DROP text and need the
 * full ordering signal.
 *
 * Why not gate on `incoming.startsWith(existing)` alone? Because the
 * letta-mobile-wucn counterexample demonstrates that an INCREMENTAL stream can
 * deliver a delta byte-identical to the accumulation so far (the 5th fragment
 * "The quick brown fox jumps over the lazy dog " equals the accumulator at
 * that point). On an incremental stream that delta is a genuine forward token
 * and MUST append; ungating EQUAL/CUMULATIVE on the byte shape alone (the
 * original bn008 fix) drops the 5th fragment. The shape signal must come from
 * the stream SHAPE — not the per-frame content. Defaults to false so existing
 * callers keep the historical append behaviour.
 */
fun mergeStreamText(
    existing: String,
    incoming: String,
    canUseSnapshotMerge: Boolean,
    incomingIsForwardDelta: Boolean = true,
    incrementalForwardAppend: Boolean = false,
    isCumulativeStream: Boolean = false,
): StreamTextMergeResult {
    val frames = MergeFrames(existing = existing, incoming = incoming)
    val signals = MergeSignals(
        canUseSnapshotMerge = canUseSnapshotMerge,
        incomingIsForwardDelta = incomingIsForwardDelta,
        incrementalForwardAppend = incrementalForwardAppend,
        isCumulativeStream = isCumulativeStream,
    )
    val branch = classifyMerge(frames, signals)
    return StreamTextMergeResult(
        text = frames.mergedText(branch),
        branch = branch,
        garbleRisk = branch == StreamTextMergeBranch.APPEND && frames.isShortAppend(),
    )
}

/** The stream-shape and ordering signals the caller derives once per frame. */
private data class MergeSignals(
    val canUseSnapshotMerge: Boolean,
    val incomingIsForwardDelta: Boolean,
    val incrementalForwardAppend: Boolean,
    val isCumulativeStream: Boolean,
) {
    /** A forward delta in an incremental stream is always new text: a prefix/suffix coincidence must not drop it. */
    val forwardIncrement: Boolean get() = incrementalForwardAppend && incomingIsForwardDelta

    /**
     * letta-mobile-bn008 + letta-mobile-wucn: an identical frame is only dropped when the stream is
     * known to be cumulative (seq-id ordering OR stable-otid stream shape). Ungating EQUAL broke
     * wucn: an incremental token can be byte-identical to the accumulator and must APPEND.
     */
    val cumulativeShapeAccepted: Boolean get() = isCumulativeStream || canUseSnapshotMerge

    /** STALE / SUFFIX_DUPLICATE drop text, so they need the full ordering signal and no forced append. */
    val mayDropRedelivery: Boolean get() = canUseSnapshotMerge && !forwardIncrement
}

/** The text held and the text arriving, with every comparison between them. */
private class MergeFrames(val existing: String, val incoming: String) {
    fun isEqual(): Boolean = incoming == existing

    /**
     * Longer text that already contains the reply so far is a snapshot. Appending it is the
     * staircase ("...555" + "...5555 returned").
     */
    fun isGrowth(): Boolean =
        existing.isNotEmpty() && incoming.length > existing.length && incoming.startsWith(existing)

    fun isPrefixOfExisting(): Boolean = existing.startsWith(incoming)

    fun isSuffixOfExisting(): Boolean = existing.endsWith(incoming)

    /**
     * letta-mobile-mvcr4: the two texts share all but a few characters at one end: at least 4 in
     * common, covering three quarters of the longer side.
     */
    fun nearlyOverlaps(): Boolean {
        if (minOf(existing.length, incoming.length) < 4) return false
        val overlapLen = maxOf(
            longestCommonPrefixLength(existing, incoming),
            longestCommonSuffixLength(existing, incoming),
        )
        return overlapLen >= 4 &&
            overlapLen.toDouble() / maxOf(existing.length, incoming.length).toDouble() >= 0.75
    }

    /**
     * True when [incoming] is a whole snapshot of the reply [existing] holds, rewritten past a
     * shared opening: both run on after [REWRITE_MIN_SHARED_OPENING] or more characters in common,
     * each in its own direction. Appending such a snapshot to a cumulative stream stacks a
     * near-copy of the reply under itself on every frame (the owner's desktop panel, 2026-10-03).
     * An incremental token never opens with that much of the reply and then diverges; a repeat of
     * the reply is EQUAL, a growth CUMULATIVE, and both are decided before this.
     */
    fun isRewrittenSnapshot(): Boolean {
        val shared = longestCommonPrefixLength(existing, incoming)
        return shared >= REWRITE_MIN_SHARED_OPENING && shared < existing.length && shared < incoming.length
    }

    /** An append of a chunk under half the held text: where a garbled merge would show. */
    fun isShortAppend(): Boolean =
        existing.isNotEmpty() && incoming.isNotEmpty() && incoming.length < existing.length / 2

    fun mergedText(branch: StreamTextMergeBranch): String = when (branch) {
        StreamTextMergeBranch.EMPTY_INCOMING,
        StreamTextMergeBranch.EQUAL,
        StreamTextMergeBranch.STALE,
        StreamTextMergeBranch.SUFFIX_DUPLICATE -> existing
        StreamTextMergeBranch.CUMULATIVE,
        StreamTextMergeBranch.SNAPSHOT_REWRITE -> incoming
        StreamTextMergeBranch.SNAPSHOT_CONFLICT -> if (incoming.length > existing.length) incoming else existing
        StreamTextMergeBranch.APPEND -> existing + incoming
    }
}

private fun classifyMerge(frames: MergeFrames, signals: MergeSignals): StreamTextMergeBranch =
    classifyWholeSnapshot(frames, signals) ?: classifyDivergent(frames, signals)

/** Branches decided by one text containing the other (or an empty frame). */
private fun classifyWholeSnapshot(frames: MergeFrames, signals: MergeSignals): StreamTextMergeBranch? = when {
    frames.incoming.isEmpty() -> StreamTextMergeBranch.EMPTY_INCOMING
    frames.isEqual() && signals.cumulativeShapeAccepted -> StreamTextMergeBranch.EQUAL
    // Not gated on the cumulative flag: App Server frames often arrive without one.
    frames.isGrowth() -> StreamTextMergeBranch.CUMULATIVE
    signals.mayDropRedelivery && frames.isPrefixOfExisting() -> StreamTextMergeBranch.STALE
    signals.mayDropRedelivery && frames.isSuffixOfExisting() -> StreamTextMergeBranch.SUFFIX_DUPLICATE
    else -> null
}

/** Branches for frames that neither contain nor repeat the held text. */
private fun classifyDivergent(frames: MergeFrames, signals: MergeSignals): StreamTextMergeBranch = when {
    signals.isCumulativeStream && signals.incomingIsForwardDelta && frames.isRewrittenSnapshot() ->
        StreamTextMergeBranch.SNAPSHOT_REWRITE
    // letta-mobile-mvcr4: near-overlap forward snapshot -> coalesce to the longer complete text.
    signals.canUseSnapshotMerge && frames.nearlyOverlaps() -> StreamTextMergeBranch.SNAPSHOT_CONFLICT
    // letta-mobile-k9y5d: a replayed/out-of-order snapshot keeps the longer text; a forward delta appends.
    signals.canUseSnapshotMerge && !signals.incomingIsForwardDelta -> StreamTextMergeBranch.SNAPSHOT_CONFLICT
    else -> StreamTextMergeBranch.APPEND
}

private const val REWRITE_MIN_SHARED_OPENING = 24

private fun longestCommonPrefixLength(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}

private fun longestCommonSuffixLength(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
    return i
}
