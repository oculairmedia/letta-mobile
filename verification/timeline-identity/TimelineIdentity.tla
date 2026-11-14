-------------------------- MODULE TimelineIdentity --------------------------
(***************************************************************************)
(* Row identity of the shared timeline (bead letta-mobile-o1hju).          *)
(*                                                                         *)
(* PROMISE  The list never presents one key twice, never drops a drawn     *)
(*          row, and gives an unchanged record the same key in every       *)
(*          generation, under every interleaving of live frames, durable   *)
(*          writes, generation replacement, memory trims, scrolling and    *)
(*          Paging loads.                                                  *)
(* HARM     A repeated key makes TimelineRowKeyGuard blank a row every     *)
(*          frame (timeline.duplicateKeyDropped); a changed key remounts   *)
(*          the row. The user sees rows vanish and reappear (flicker).     *)
(*                                                                         *)
(* SYNC     Confirmed against origin/main 624012ec297e65003062991f5a07a2a5 *)
(*          945f316c (2026-11-14). Paths below are under                   *)
(*          android-compose/sharedLogic/src/commonMain/kotlin/com/letta/   *)
(*          mobile/data/ unless stated.                                    *)
(*                                                                         *)
(* COVERS (one indivisible code step = one model step)                     *)
(*  timeline/TimelinePageProjection.kt                                     *)
(*    aggregatePreparedRuns :118, runClaim :153, claimRun :163,            *)
(*    spansVisibleGap :191, containsComplete :209, runEnvelope :222-256    *)
(*  chat/projection/MessageGrouping.kt                                     *)
(*    groupMessagesForRender :182 (runIdCounts :194, unique Single :266),  *)
(*    runBlockKey :541                                                     *)
(*  timeline/TimelineLivePublication.kt  per-record projection key         *)
(*    segment-<identity> :117-124, overlayEvents :245                      *)
(*  timeline/CanonicalTimelinePresentation.kt                              *)
(*    settledRows :113, projectLiveItems :180, filterUnsettledEvents :210, *)
(*    onResidentRows :245, withSettledKey :320, project :353               *)
(*  timeline/SettledLiveAdoptions.kt adoptLocked :26                       *)
(*  timeline/SettledRunKeys.kt livePresentationFor :43, sharesShapeWith :69*)
(*  chat/projection/TimelineRowAssembly.kt assemble :34, displayedLive :43 *)
(*  chat/projection/TimelineRowKeyGuard.kt duplicateRows :20               *)
(*  timeline/CanonicalTimelineSessionPaging.kt observePublications :65     *)
(*  timeline/TimelineLedgerPagingSource.kt invalidateIfBehind :25,         *)
(*    getRefreshKey :31, load :41                                          *)
(*  timeline/CanonicalTimelineEngine.kt preparePage :579,                  *)
(*    reconcilePageDetailed :502, shouldDeferLiveReconcile :556            *)
(*  core/android-data/.../RoomTimelineBoundedStore.kt read positions       *)
(*    :181-189 (Tail / Before / After / Around)                            *)
(*                                                                         *)
(* ABSTRACTIONS (full list: .agent/skills/verifying-timeline-identity)    *)
(*  - One conversation; records are a fixed ledger order. Kind "P" = user *)
(*    prompt (run-less, carries an otid), "R" = assistant row of run       *)
(*    RunOf[i], "U" = opaque usage / stop_reason row (no event).           *)
(*    Durable rows carry their run id (as the S1 fixtures store them).     *)
(*  - Record identity = ledger index; serverId/otid/aliases collapse to it.*)
(*  - Paging maxSize (3 pages) is never reached at these bounds, so page   *)
(*    dropping is not modelled. Placeholders are off (enablePlaceholders). *)
(*  - The live run id equals the ledger run id, so live and settled run    *)
(*    keys share one name space (the worst case for collisions).           *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
    Kind,         \* the ledger, oldest first: a sequence over {"P","R","U"}
    RunOf,        \* run id of each record ("" for a prompt), same length as Kind
    PageSize,     \* PagingConfig.pageSize = TimelinePageBudget.maxMetadataRows
    InitDurable,  \* set of possible durable prefixes when the presentation opens
    Live,         \* TRUE: the turn streams through the live overlay
    MaxRev,       \* bound on durable revisions
    Fix,          \* "main" (code as on main) or "D1" (the planned S2 fix)
    Break         \* "none", or a deliberately broken variant that shows a passing
                  \* property can fail (able-to-fail.sh):
                  \*   containsCompleteIgnoresBoundary  claim even across Continues
                  \*   d1PerPageOnly    D1 judges "whole run" by the page alone
                  \*   keyByPageEdge    a claimed run is keyed by the page's first row
                  \*   refreshAtTail    getRefreshKey ignores the reader (Tail)

LedgerLen == Len(Kind)
Recs == 1..LedgerLen
NoKey == ""

VARIABLES
    n,            \* durable prefix: records 1..n are in the ledger
    rev,          \* durable revision (engine publication durableRevision)
    streamed,     \* live frames delivered: records 1..streamed reached the overlay
    retired,      \* the turn's live publication was acknowledged settled
    live,         \* mutableLive: the live projection's render items (a set)
    resident,     \* ResidentState.presented: records reported by onResidentRows
    srcRev,       \* TimelineLedgerPagingSource.revision of the active source
    needRefresh,  \* the active source is invalidated; Paging must refresh
    pages,        \* presented pages, newest first: [lo, hi, m (ledger length
                  \* it was read at), rows (newest first)]
    anchor,       \* reader position: record behind PagingState.anchorPosition (0 = none)
    byId,         \* SettledLiveAdoptions.byIdentity: record -> adopted live key
    holders       \* SettledLiveAdoptions.holders: live key -> records holding it

vars == <<n, rev, streamed, retired, live, resident, srcRev, needRefresh,
          pages, anchor, byId, holders>>

-----------------------------------------------------------------------------
(* Helpers                                                                 *)
Max(S) == CHOOSE x \in S : \A y \in S : y <= x
Min(S) == CHOOSE x \in S : \A y \in S : x <= y
Str(i) == ToString(i)

RunKey(x)      == "run-" \o x                    \* MessageGrouping.runKey
RunKeyAt(x, i) == "run-" \o x \o "-" \o Str(i)   \* runBlockKey split form
SegKey(i)    == "segment-" \o Str(i)            \* per-record projection key
MsgKey(i)    == "msg-" \o Str(i)                \* prompt: msg-<clientMessageId>

\* Records of S sorted newest first, as a sequence.
RECURSIVE Desc(_)
Desc(S) == IF S = {} THEN <<>> ELSE <<Max(S)>> \o Desc(S \ {Max(S)})

\* Rows (records with an .owner field) sorted newest first.
RECURSIVE RowsDesc(_)
RowsDesc(S) ==
    IF S = {} THEN <<>>
    ELSE LET r == CHOOSE x \in S : \A y \in S : y.owner <= x.owner
         IN <<r>> \o RowsDesc(S \ {r})

RECURSIVE Concat(_)
Concat(s) == IF s = <<>> THEN <<>> ELSE Head(s) \o Concat(Tail(s))

Range(s) == {s[i] : i \in DOMAIN s}

-----------------------------------------------------------------------------
(* groupMessagesForRender (MessageGrouping.kt:182) over a set of message   *)
(* records M (P and R only). Contiguous R rows of one run (nothing else of *)
(* M between them) form one block; opaque rows are not messages, so they   *)
(* never split one.                                                        *)
IsR(i) == Kind[i] = "R"
BlockOf(M, i) == {j \in M : IsR(j) /\ RunOf[j] = RunOf[i] /\
                    ~\E k \in M : ~(IsR(k) /\ RunOf[k] = RunOf[i]) /\
                       ((i < k /\ k < j) \/ (j < k /\ k < i))}
Blocks(M) == {BlockOf(M, i) : i \in {j \in M : IsR(j)}}
RunOfBlock(b) == RunOf[Min(b)]
RunCount(M, x) == Cardinality({j \in M : IsR(j) /\ RunOf[j] = x})   \* runIdCounts :194

\* The key the grouper gives block b of snapshot M, or NoKey when it builds
\* a Single without a stable run key (runId not unique, :266).
GroupKey(M, b) ==
    LET x == RunOfBlock(b) IN
    IF Cardinality(b) = 1
    THEN IF RunCount(M, x) = 1 THEN RunKey(x) ELSE NoKey     \* Single, :266-277
    ELSE IF RunCount(M, x) = Cardinality(b) THEN RunKey(x)  \* runBlockKey :549
         ELSE RunKeyAt(x, Max(b))  \* accumulator is newest-first: first() = newest

-----------------------------------------------------------------------------
(* runEnvelope (TimelinePageProjection.kt:222), boundary(...) :229. The    *)
(* value is "Ends", "Unknown", or the run id x of Continues(x):            *)
(* key == null -> Ends; resident edge has no event (opaque) -> Unknown;    *)
(* neighbour not an event -> Unknown; both rows in run x -> Continues(x);  *)
(* otherwise (a prompt on either side, or another run) -> Ends.            *)
Boundary(edge, nb) ==
    IF Kind[edge] = "U" \/ Kind[nb] = "U" THEN "Unknown"
    ELSE IF IsR(edge) /\ IsR(nb) /\ RunOf[edge] = RunOf[nb] THEN RunOf[edge]
    ELSE "Ends"
OlderBoundary(lo) == IF lo = 1 THEN "Ends" ELSE Boundary(lo, lo - 1)
NewerBoundary(hi, m) == IF hi = m THEN "Ends" ELSE Boundary(hi, hi + 1)

\* containsComplete (:209): only Continues(runId) refuses a claim.
ContainsComplete(lo, hi, m, x) ==
    \/ Break = "containsCompleteIgnoresBoundary"
    \/ OlderBoundary(lo) # x /\ NewerBoundary(hi, m) # x

\* Every R record of run x that is durable at ledger length m.
RunRecords(m, x) == {j \in 1..m : IsR(j) /\ RunOf[j] = x}

(* The rows one page [lo, hi] presents at ledger length m, before live-key *)
(* adoption: CanonicalTimelineEngine.preparePage :579 -> project ->        *)
(* aggregatePreparedRuns :118 -> claimRun :163, filtered of Drop rows by   *)
(* settledRows :113. Unclaimed R rows keep segment-<identity> (RunBlock,   *)
(* TimelineLivePublication.kt:117-124); prompts are msg-<clientMessageId>. *)
PromptRow(i) == [key |-> MsgKey(i), members |-> {i}, owner |-> i, run |-> FALSE]
SegRow(i)    == [key |-> SegKey(i), members |-> {i}, owner |-> i, run |-> TRUE]
ClaimRow(b, k) == [key |-> k, members |-> b, owner |-> Min(b), run |-> TRUE]

\* D1 (correction plan section 6): run-<id> only when the page holds every
\* record of the run in this one block, otherwise run-<id>-<firstMessageId>.
\* "Every record" is the ledger's whole run: a page cannot see it through the
\* one-row envelope, so this is the fix as specified, not an implementation.
D1Key(lo, hi, m, M, b) ==
    LET x == RunOfBlock(b)
        whole == IF Break = "d1PerPageOnly" THEN {j \in M : IsR(j) /\ RunOf[j] = x}
                 ELSE RunRecords(m, x)
    IN IF b = whole THEN RunKey(x)
       ELSE IF Break = "keyByPageEdge" THEN RunKeyAt(x, lo) ELSE RunKeyAt(x, Min(b))

BlockRows(lo, hi, m, M, b) ==
    IF Fix = "D1" THEN {ClaimRow(b, D1Key(lo, hi, m, M, b))}
    ELSE LET k == GroupKey(M, b) IN
         IF k # NoKey /\ ContainsComplete(lo, hi, m, RunOfBlock(b))
         THEN {ClaimRow(b, IF Break = "keyByPageEdge" THEN RunKeyAt(RunOfBlock(b), lo) ELSE k)}
                                     \* spansVisibleGap is FALSE: only opaque
                                     \* run metadata can sit between members
         ELSE {SegRow(i) : i \in b}

PageRowSet(lo, hi, m) ==
    LET M == {i \in lo..hi : Kind[i] # "U"}     \* renderableRecords :135
    IN IF Cardinality(M) < 2                    \* :122 early return
       THEN {IF Kind[i] = "P" THEN PromptRow(i) ELSE SegRow(i) : i \in M}
       ELSE {PromptRow(i) : i \in {j \in M : Kind[j] = "P"}}
            \cup UNION {BlockRows(lo, hi, m, M, b) : b \in Blocks(M)}

-----------------------------------------------------------------------------
(* Live projection: projectLiveItems (CanonicalTimelinePresentation:180)   *)
(* groups the unsettled live events in one snapshot.                       *)
LiveItems(E) ==
    {[key |-> MsgKey(i), members |-> {i}, run |-> FALSE] : i \in {j \in E : Kind[j] = "P"}}
    \cup {LET k == GroupKey(E, b) IN
            IF k = NoKey THEN [key |-> MsgKey(Max(b)), members |-> b, run |-> FALSE]
            ELSE [key |-> k, members |-> b, run |-> TRUE]
          : b \in Blocks(E)}

-----------------------------------------------------------------------------
(* Settled row takes over the live row it replaces: withSettledKey :320 -> *)
(* SettledLiveAdoptions.adoptLocked :26 -> livePresentationFor :43.        *)
NoItem == [key |-> NoKey, members |-> {}, run |-> FALSE]
Newest(S) == CHOOSE l \in S : \A o \in S : Max(o.members) <= Max(l.members)
Held(hd, k) == IF k \in DOMAIN hd THEN hd[k] ELSE {}

AdoptOne(row, lv, by, hd) ==
    IF ~row.run THEN [row |-> row, by |-> by, hd |-> hd]  \* prompt: :325 returns first
    ELSE
    LET hits  == {m \in row.members : by[m] # NoKey}
        named == {l \in lv : l.members \cap row.members # {}}
        shaped == {l \in named : l.run}                    \* sharesShapeWith :69
        match == IF shaped # {} THEN Newest(shaped)
                 ELSE IF named # {} THEN Newest(named) ELSE NoItem
        key == IF hits # {} THEN by[Min(hits)]
               ELSE IF match # NoItem /\
                       Held(hd, match.key) = {}
                    THEN match.key ELSE NoKey
    IN IF key = NoKey THEN [row |-> row, by |-> by, hd |-> hd]
       ELSE [row |-> [row EXCEPT !.key = key],
             by  |-> [m \in Recs |-> IF m \in row.members THEN key ELSE by[m]],
             hd  |-> (key :> (Held(hd, key) \cup row.members)) @@ hd]

RECURSIVE AdoptAll(_, _, _, _)
AdoptAll(rows, lv, by, hd) ==
    IF rows = <<>> THEN [rows |-> <<>>, by |-> by, hd |-> hd]
    ELSE LET first == AdoptOne(Head(rows), lv, by, hd)
             rest  == AdoptAll(Tail(rows), lv, first.by, first.hd)
         IN [rows |-> <<first.row>> \o rest.rows, by |-> rest.by, hd |-> rest.hd]

-----------------------------------------------------------------------------
(* What the list shows: TimelineRowAssembly.assemble :34.                  *)
SettledSeq == Concat([p \in DOMAIN pages |-> pages[p].rows])
SettledKeys == {SettledSeq[i].key : i \in DOMAIN SettledSeq}
SettledMembers == UNION {SettledSeq[i].members : i \in DOMAIN SettledSeq}

ShownLive ==                                         \* displayedLive :43
    {l \in live : l.key \notin SettledKeys}
ShownLiveSeq == LET ks == Desc({Max(l.members) : l \in ShownLive})
                IN [i \in DOMAIN ks |-> CHOOSE l \in ShownLive : Max(l.members) = ks[i]]

\* Keys in list order: live rows, then settled pages newest first.
ListKeys == [i \in DOMAIN ShownLiveSeq |-> ShownLiveSeq[i].key]
            \o [i \in DOMAIN SettledSeq |-> SettledSeq[i].key]
ListRows == ShownLiveSeq \o [i \in DOMAIN SettledSeq |-> [key |-> SettledSeq[i].key,
                                 members |-> SettledSeq[i].members, run |-> SettledSeq[i].run]]

\* TimelineRowKeyGuard.duplicateRows :20 blanks every later repeat.
Blanked == {i \in DOMAIN ListKeys : \E j \in 1..(i - 1) : ListKeys[j] = ListKeys[i]}
Drawn == UNION {ListRows[i].members : i \in (DOMAIN ListRows) \ Blanked}

\* The settled key a record is listed under (records sit on one page only).
SettledKeyOf(x) == SettledSeq[CHOOSE i \in DOMAIN SettledSeq : x \in SettledSeq[i].members].key

\* Records the next refresh is certain to load: Room's Around(anchor) page
\* ((PageSize-1) div 2 older rows, the anchor, the rest newer), :184-189.
AroundRange(a, m) ==
    LET olderAvail == a - 1
        older == IF olderAvail < (PageSize - 1) \div 2 THEN olderAvail ELSE (PageSize - 1) \div 2
        newerAvail == m - a
        newer == IF newerAvail < PageSize - older - 1 THEN newerAvail ELSE PageSize - older - 1
    IN (a - older)..(a + newer)

-----------------------------------------------------------------------------
(* Paging loads (TimelineLedgerPagingSource.load :41) at ledger length n.  *)
(* Each is preparePage + project in one step: preparePage holds the engine *)
(* mutex and one store snapshot, load returns Invalid if the revision      *)
(* moved meanwhile, and project reads no durable state, so splitting it    *)
(* from the load adds no behaviour beyond live frames, which can already   *)
(* happen before or after this step.                                       *)
Load(lo, hi) == AdoptAll(RowsDesc(PageRowSet(lo, hi, n)), live, byId, holders)
Page(lo, hi, adopted) == [lo |-> lo, hi |-> hi, m |-> n, rows |-> adopted.rows]
Adopted(adopted) == byId' = adopted.by /\ holders' = adopted.hd

-----------------------------------------------------------------------------
Init ==
    /\ n \in InitDurable
    /\ rev = 1
    /\ streamed = n
    /\ retired = (n = LedgerLen)
    /\ live = {}
    /\ resident = {}
    /\ srcRev = 0
    /\ needRefresh = TRUE
    /\ pages = <<>>
    /\ anchor = 0
    /\ byId = [i \in Recs |-> NoKey]
    /\ holders = <<>>  \* empty function

\* A live frame reaches the overlay (TimelineLivePublication published;
\* projectLiveItems :180 recomputes).
LiveFrame ==
    /\ Live /\ ~retired /\ streamed < LedgerLen
    /\ streamed' = streamed + 1
    /\ live' = LiveItems({i \in 1..streamed' : Kind[i] # "U" /\
                 i \notin resident})
    /\ UNCHANGED <<n, rev, retired, resident, srcRev, needRefresh, pages, anchor, byId, holders>>

\* The canonical writer commits a batch (TurnDone write, reconcile with new
\* rows): one store transaction, one durable revision
\* (CanonicalTimelineEngine.reconcilePageDetailed :502 -> store.transaction).
\* While a turn streams, a plain reconcile is deferred (shouldDeferLiveReconcile
\* :556), but the Paging mediator's catch-up (TimelineHistoryMediator.
\* refreshNewest, catchUp = true) is not, so a write can land mid-turn.
DurableWrite ==
    /\ n < LedgerLen /\ rev < MaxRev
    /\ \E m \in (n + 1)..LedgerLen : n' = m
    /\ rev' = rev + 1
    /\ UNCHANGED <<streamed, retired, live, resident, srcRev, needRefresh, pages, anchor, byId, holders>>

\* observePublications :82-95 -> invalidateIfBehind :25 (generation.replaced).
Invalidate ==
    /\ srcRev # 0 /\ ~needRefresh /\ srcRev < rev
    /\ needRefresh' = TRUE
    /\ UNCHANGED <<n, rev, streamed, retired, live, resident, srcRev, pages, anchor, byId, holders>>

\* observePublications :75-80: a CRITICAL memory trim invalidates the active
\* source with no revision change; Paging reloads around the reader.
Trim ==
    /\ srcRev # 0 /\ ~needRefresh
    /\ needRefresh' = TRUE
    /\ UNCHANGED <<n, rev, streamed, retired, live, resident, srcRev, pages, anchor, byId, holders>>

\* Paging refresh: a new source at the current revision loads
\* getRefreshKey :31 -> Around(anchor), or Tail before any anchor; the
\* presenter swaps the whole list for this one page.
Refresh ==
    /\ needRefresh /\ n > 0
    /\ LET r   == IF anchor = 0 \/ Break = "refreshAtTail" THEN (IF n > PageSize THEN n - PageSize + 1 ELSE 1)..n
                  ELSE AroundRange(anchor, n)
           lo  == Min(r)
           hi  == Max(r)
           a   == Load(lo, hi)
       IN pages' = <<Page(lo, hi, a)>> /\ Adopted(a)
    /\ srcRev' = rev
    /\ needRefresh' = FALSE
    /\ UNCHANGED <<n, rev, streamed, retired, live, resident, anchor>>

\* Paging append: Before(oldest resident row) loads up to PageSize older rows.
LoadOlder ==
    /\ ~needRefresh /\ srcRev = rev /\ pages # <<>>
    /\ LET lo0 == pages[Len(pages)].lo
           lo  == IF lo0 > PageSize THEN lo0 - PageSize ELSE 1
           a   == Load(lo, lo0 - 1)
       IN /\ lo0 > 1
          /\ pages' = Append(pages, Page(lo, lo0 - 1, a))
          /\ Adopted(a)
    /\ UNCHANGED <<n, rev, streamed, retired, live, resident, srcRev, needRefresh, anchor>>

\* Paging prepend: After(newest resident row) loads up to PageSize newer rows.
LoadNewer ==
    /\ ~needRefresh /\ srcRev = rev /\ pages # <<>>
    /\ LET hi0 == pages[1].hi
           hi  == IF hi0 + PageSize < n THEN hi0 + PageSize ELSE n
           a   == Load(hi0 + 1, hi)
       IN /\ hi0 < n
          /\ pages' = <<Page(hi0 + 1, hi, a)>> \o pages
          /\ Adopted(a)
    /\ UNCHANGED <<n, rev, streamed, retired, live, resident, srcRev, needRefresh, anchor>>

\* The reader rests on another settled row; Paging's anchorPosition follows.
Scroll ==
    /\ \E i \in DOMAIN SettledSeq :
         /\ SettledSeq[i].owner # anchor
         /\ anchor' = SettledSeq[i].owner
    /\ UNCHANGED <<n, rev, streamed, retired, live, resident, srcRev, needRefresh, pages, byId, holders>>

\* The list reports what it holds: onResidentRows :245 -> the live
\* projection drops events whose rows are resident (filterUnsettledEvents
\* :210, overlayEvents :245); a turn whose rows are all resident at once is
\* acknowledged settled and its publication retires.
ResidentRows ==
    /\ resident' = SettledMembers
    /\ resident' # resident
    /\ retired' = (retired \/ (streamed = LedgerLen /\ n = LedgerLen /\
                    \A i \in Recs : Kind[i] # "U" => i \in resident'))
    /\ live' = IF retired' \/ ~Live THEN {}
               ELSE LiveItems({i \in 1..streamed : Kind[i] # "U" /\
                      i \notin resident'})
    /\ UNCHANGED <<n, rev, streamed, srcRev, needRefresh, pages, anchor, byId, holders>>

Next ==
    \/ LiveFrame \/ DurableWrite \/ Invalidate \/ Trim \/ Refresh
    \/ LoadOlder \/ LoadNewer \/ Scroll \/ ResidentRows

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* PROPERTIES (names from the correction plan, section 7)                  *)

\* I1: the list (shown live rows + every resident settled page) never holds
\* one key twice, so TimelineRowKeyGuard never fires.
NoDuplicateKey == Blanked = {}

\* I5: a row the list draws stays drawn while the reader stays put. It
\* covers the rows the next refresh is certain to load around the reader
\* and every live row; a guard-blanked row counts as gone.
Protected == (UNION {l.members : l \in ShownLive})
             \cup (IF anchor = 0 THEN {} ELSE AroundRange(anchor, n))
NoVanishedRow ==
    [][anchor' = anchor => \A x \in Drawn \cap Protected : x \in Drawn']_vars

\* The presented page that holds record x: its bounds and the ledger it read.
PageOf(x) == LET p == CHOOSE p \in DOMAIN pages : x \in pages[p].lo..pages[p].hi
             IN <<pages[p].lo, pages[p].hi, pages[p].m>>

\* I7, as the plan words it ("the same record set always produces the same
\* keys"): a record that stays settled on a page with the same bounds, read
\* from the same ledger, keeps its key, in every generation.
StableKeys ==
    [][\A x \in SettledMembers \cap SettledMembers' :
          PageOf(x)' = PageOf(x) => SettledKeyOf(x)' = SettledKeyOf(x)]_vars

\* I7 strengthened ("no remount when nothing changed"): while the ledger is
\* unchanged, a settled record keeps its key even when a refresh around the
\* reader moves the page edges (getRefreshKey -> Around(anchor)).
StableKeysAcrossRecut ==
    [][n' = n => \A x \in SettledMembers \cap SettledMembers' :
                    SettledKeyOf(x)' = SettledKeyOf(x)]_vars

TypeOK ==
    /\ n \in 0..LedgerLen /\ streamed \in 0..LedgerLen /\ rev \in 1..MaxRev
    /\ srcRev \in 0..MaxRev /\ anchor \in 0..LedgerLen
=============================================================================
