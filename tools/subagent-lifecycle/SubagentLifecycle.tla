------------------------- MODULE SubagentLifecycle -------------------------
EXTENDS Naturals, TLC
(***************************************************************************
Bounded executable abstraction of origin/main edb652dae4 plus the lifecycle fixes in this change,
NOT a Kotlin implementation. Recheck this code map when updating the source.

Expected results (main / fixed / fixed-disciplined):
  P1 EventuallyTerminal: PASS / PASS / PASS   (early completion kept, applied on receipt)
  P2 NoResurrection (including eviction): PASS / PASS / PASS   (evicted tombstones)
     RetainedTerminalNeverRuns: PASS / PASS / PASS
  P3 ParentIsolation: FAIL / PASS / PASS
  P4 NoPendingRow: PASS / PASS / PASS   (turn_closed settles unanswered calls)
     NoPendingChildRow: PASS / PASS / PASS (close settles any unanswered row it holds)
Run ./check.sh, ./able-to-fail.sh; trace.py out/<config>.<property>.out
prints one transition per line. Break mutations disable expiry, terminal guards,
foreign-identity suppression, and close settlement (with the disciplined premise).
A fair replay of stale RUNNING facts is allowed, not asserted to have happened
in the production incident: this explains a possible forever-live mechanism,
not a claim to have replayed the exact device trace.
One exact parent/dispatch and one parent tool row suffice for these witnesses;
pressure represents other records filling the ordinary capacity. Evict is a
single transaction inserting another key while this terminal is oldest, NOT an
invented eviction thread. The omitted keys never affect scope/correlation.
Only controller-native observations are selected; weaker source precedence is
outside this model. Dropped tagged/foreign frames have no observable mutation
and are represented by Spec's stuttering steps (not an invented projection).
No claim of unbounded proof, clock accuracy, parsing coverage, or arbitrary delivery.

Code map (paths relative to android-compose/sharedLogic/src):
commonMain/kotlin/com/letta/mobile/data/subagents/DurableSubagentRegistry.kt:
  Dispatch / Receipt / RunningSnapshot: observeLocked, insertLocked; terminal guard
  canAdvanceTo; mergedWith updates lastSeen on accepted sightings EXCEPT while an early
  completion is held (keepingSilenceWhileCompletionPending). Receipt applies a held early
  completion by exact task id (withEarlyTerminalApplied). Evicted keys keep a terminal
  tombstone (rememberEvictedLocked, refuseEvictedLocked), bounded by
  MAX_EVICTED_TOMBSTONES=4096; the model assumes this key is within that bound.
  Restart: retirePreviousProcess:109/116, assuming a known changed process
  identity, uses CANCELLED (not ORPHANED);
  confirmed snapshot absence below uses ORPHANED. Sweep: expireStaleLocked:143 and
  SubagentStaleSweep.kt staleRunningChips:22 (lastSeen TTL, NOT lastActivity);
  ReconcileAbsent: reconcileLocked:476 (snapshot absence confirmation abstracted
  as one already-confirmed absence; reconcileSnapshot:446 has a grace window).
  Dismiss: dismiss; Evict: enforceCapacityLocked, evictionCandidates.
  ParentStop/Close do NOT terminate chips: markParentTurnEnded:537.
commonMain/kotlin/com/letta/mobile/data/subagents/SubagentRegistryStore.kt:20,23:
  mutations below represent the synchronized registry transaction ending in a
  whole-snapshot save (persistLocked:673); Reconnect represents load/rehydrate:172.
  Crash between map mutation and save / I/O failures are outside this model.
jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/
ControllerSubagentRegistrySource.kt:
  Dispatch: ParentStreamProjection.dispatchEntry:150;
  Receipt: receiptEntry:165 (requires existing dispatch);
  Notify: terminalEntry, matchesDispatch; with no match, rememberBeforeReceipt stores a
  task-identified notification on live receipt-less dispatches (rememberEarlyTerminal,
  persisted). Missing task id fails closed; titles never match.
  Lifecycle: ingestSubagentState:228/236 authoritative terminal observation.
  RunningSnapshot: same loop; ReconcileAbsent:251; Sweep: start:272/275.
commonMain/kotlin/com/letta/mobile/data/runtime/AppServerTurnEngine.kt:
  ChildCall/ChildResult: processReceivedFrame:1113/1136 and suppressChildFrame:372
  reject subagent_id, NOT a delta's foreign own identity when that tag is absent.
  Assume generation/envelope/run gates admit this otherwise-valid shared stream.
commonMain/kotlin/com/letta/mobile/data/runtime/TurnDraftProcessor.kt:
  ParentStop: bufferTail:165; Close: publishTerminal:187 and settle window:214;
  ParentCall/ParentResult: process:111/117/135 (track then emit);
  FailTurn: publishTerminal:187/188 (abnormal terminal settles pending tools).
  Close also settles: the host journals turn_closed after every relayed frame of the
  turn (NodeTurnHost.run finally); the shared feed projection
  (FeedRowProjector.settleClosedTurn) settles each still-unanswered call of that turn
  through TimelineMutation.SettleDanglingToolCalls. A later real result still replaces it. WorkerFinish and Tick are environment actions:
  a worker can finish without delivering a matching notification; time advances.
  Child identities abstract a mismatch in ANY of own agent/session/conversation,
  independent of the correctly scoped transport envelope. Tag presence is separate.
  Frame projection is a single observable boundary step; parsing/admission is
  an input predicate, not a second invented state mutation. No frames after Close
  (stampWhileLive:138). Child calls may arrive after stop_reason, before Close.

Fairness: finite local delivery is weakly fair (receipt, notification, finish,
close, result, stop); clock strongly fair; expiry weakly fair. Sweep calls
at non-expired ages are stuttering steps (the periodic scheduler still runs). NO fairness assumption promises an
accurate server snapshot or a terminal lifecycle event. Repeated accepted stale
RUNNING sightings can reset TTL indefinitely, even with fair clock/sweep. Thus
EventuallyTerminal fails in replay environments; quiet proves the TTL fallback.
Fixed changes ONLY the fail-closed foreign-identity delta predicate.
Disciplined is an explicit *environment restriction*, not an implemented fix:
no eviction/replayed RUNNING, and parent result precedes normal close. It gives
positive baselines for mutation tests. Every production gap remains expected
in main/fixed; a new/unexpected failure (or disappearing witness) fails check.sh.

NoResurrection is deliberately global for this exact dispatch, including after
capacity eviction. RetainedTerminalNeverRuns separately tests the actual guard.
Eviction deletes ordinary history but keeps a terminal tombstone, so a later same-key
RUNNING snapshot is refused. Dismissal tombstones, unlike ordinary terminals, cannot be evicted.
***************************************************************************)
CONSTANTS Fixed, Environment, Break
VARIABLES chip, disk, receipt, finished, notified, ended, closed, age,
          terminalSeen, evicted, dismissed, resurrected, leaked,
          childSent, childReturned, parentSent, parentReturned, failed,
          reconnected
vars == <<chip, disk, receipt, finished, notified, ended, closed, age,
          terminalSeen, evicted, dismissed, resurrected, leaked,
          childSent, childReturned, parentSent, parentReturned, failed,
          reconnected>>
Terminal == {"COMPLETED", "FAILED", "CANCELLED", "ORPHANED"}
States == Terminal \cup {"ABSENT", "RUNNING"}
Init == /\ chip = "ABSENT" /\ disk = "ABSENT" /\ receipt = FALSE
        /\ finished = FALSE /\ notified = FALSE /\ ended = FALSE
        /\ closed = FALSE /\ age = 0 /\ terminalSeen = FALSE
        /\ evicted = FALSE /\ dismissed = FALSE /\ resurrected = FALSE
        /\ leaked = FALSE /\ childSent = FALSE /\ childReturned = FALSE
        /\ parentSent = FALSE /\ parentReturned = FALSE /\ failed = FALSE
        /\ reconnected = FALSE
Dispatch == /\ chip = "ABSENT" /\ ~evicted
            /\ chip' = "RUNNING" /\ disk' = chip' /\ age' = 0
            /\ UNCHANGED <<receipt, finished, notified, ended, closed,
                 terminalSeen, evicted, dismissed, resurrected, leaked,
                 childSent, childReturned, parentSent, parentReturned, failed, reconnected>>
\* A held early completion (notified before the receipt) is applied by exact task id.
Receipt == /\ chip # "ABSENT" /\ ~receipt /\ receipt' = TRUE /\ age' = 0
           /\ chip' = IF notified /\ chip = "RUNNING" /\ "forgetEarly" \notin Break
                       THEN "COMPLETED" ELSE chip
           /\ disk' = chip' /\ terminalSeen' = (terminalSeen \/ chip' \in Terminal)
           /\ UNCHANGED <<finished, notified, ended, closed,
                evicted, dismissed, resurrected, leaked,
                childSent, childReturned, parentSent, parentReturned, failed, reconnected>>
WorkerFinish == /\ chip # "ABSENT" /\ ~finished /\ finished' = TRUE
                /\ UNCHANGED <<chip, disk, receipt, notified, ended, closed, age,
                     terminalSeen, evicted, dismissed, resurrected, leaked,
                     childSent, childReturned, parentSent, parentReturned, failed, reconnected>>
Notify == /\ finished /\ ~notified /\ notified' = TRUE
          /\ chip' = IF receipt /\ chip = "RUNNING" THEN "COMPLETED" ELSE chip
          /\ disk' = chip' /\ terminalSeen' = (terminalSeen \/ chip' \in Terminal)
          /\ UNCHANGED <<receipt, finished, ended, closed, age, evicted,
               dismissed, resurrected, leaked, childSent, childReturned,
               parentSent, parentReturned, failed, reconnected>>
Terminalize(s) == /\ chip = "RUNNING" /\ chip' = s /\ disk' = s
                  /\ terminalSeen' = TRUE
                  /\ UNCHANGED <<receipt, finished, notified, ended, closed, age,
                       evicted, dismissed, resurrected, leaked, childSent,
                       childReturned, parentSent, parentReturned, failed, reconnected>>
Lifecycle == \E s \in {"COMPLETED", "FAILED", "CANCELLED"}: Terminalize(s)
Restart == Terminalize("CANCELLED")
ReconcileAbsent == Terminalize("ORPHANED")
Sweep == /\ age = 2 /\ "skipExpiry" \notin Break /\ Terminalize("CANCELLED")
Tick == /\ age < 2 /\ age' = age + 1
        /\ UNCHANGED <<chip, disk, receipt, finished, notified, ended, closed,
             terminalSeen, evicted, dismissed, resurrected, leaked, childSent,
             childReturned, parentSent, parentReturned, failed, reconnected>>
\* An evicted key's tombstone refuses RUNNING; a held early completion stops TTL renewal.
RunningSnapshot ==
    /\ (Environment = "replay" \/ "resurrect" \in Break)
    /\ chip' = IF chip = "RUNNING" \/ (chip = "ABSENT" /\ ~evicted) \/ "resurrect" \in Break
                THEN "RUNNING" ELSE chip
    /\ disk' = chip'
    /\ age' = IF chip' = "RUNNING" /\ ~(notified /\ ~receipt) THEN 0 ELSE age
    /\ resurrected' = (resurrected \/ (terminalSeen /\ chip' = "RUNNING"))
    /\ UNCHANGED <<receipt, finished, notified, ended, closed, terminalSeen,
         evicted, dismissed, leaked, childSent, childReturned, parentSent,
         parentReturned, failed, reconnected>>
Dismiss == /\ chip = "RUNNING" /\ ~dismissed /\ dismissed' = TRUE
           /\ chip' = "ORPHANED" /\ disk' = chip' /\ terminalSeen' = TRUE
           /\ UNCHANGED <<receipt, finished, notified, ended, closed, age,
                evicted, resurrected, leaked, childSent, childReturned,
                parentSent, parentReturned, failed, reconnected>>
Evict == /\ Environment = "replay" /\ chip \in Terminal /\ ~dismissed
         /\ chip' = "ABSENT" /\ disk' = chip' /\ evicted' = TRUE
         /\ UNCHANGED <<receipt, finished, notified, ended, closed, age,
              terminalSeen, dismissed, resurrected, leaked, childSent,
              childReturned, parentSent, parentReturned, failed, reconnected>>
Reconnect == /\ ~reconnected /\ reconnected' = TRUE /\ chip' = disk
             /\ UNCHANGED <<disk, receipt, finished, notified, ended, closed, age,
                  terminalSeen, evicted, dismissed, resurrected, leaked,
                  childSent, childReturned, parentSent, parentReturned, failed>>
ParentStop == /\ ~ended /\ ended' = TRUE
              /\ UNCHANGED <<chip, disk, receipt, finished, notified, closed, age,
                   terminalSeen, evicted, dismissed, resurrected, leaked,
                   childSent, childReturned, parentSent, parentReturned, failed, reconnected>>
ParentCall == /\ ~closed /\ ~parentSent /\ parentSent' = TRUE
              /\ UNCHANGED <<chip, disk, receipt, finished, notified, ended, closed,
                   age, terminalSeen, evicted, dismissed, resurrected, leaked,
                   childSent, childReturned, parentReturned, failed, reconnected>>
ParentResult == /\ ~closed /\ parentSent /\ ~parentReturned /\ parentReturned' = TRUE
                /\ UNCHANGED <<chip, disk, receipt, finished, notified, ended, closed,
                     age, terminalSeen, evicted, dismissed, resurrected, leaked,
                     childSent, childReturned, parentSent, failed, reconnected>>
AdmitChild(tagged) == (~tagged /\ (~Fixed \/ "leak" \in Break))
ChildCall == \E tagged \in BOOLEAN:
    /\ ~closed /\ ~childSent /\ AdmitChild(tagged)
    /\ childSent' = TRUE /\ leaked' = TRUE
    /\ UNCHANGED <<chip, disk, receipt, finished, notified, ended, closed, age,
         terminalSeen, evicted, dismissed, resurrected, childReturned,
         parentSent, parentReturned, failed, reconnected>>
ChildResult == \E tagged \in BOOLEAN:
    /\ ~closed /\ childSent /\ ~childReturned /\ AdmitChild(tagged)
    /\ childReturned' = TRUE
    /\ UNCHANGED <<chip, disk, receipt, finished, notified, ended, closed, age,
         terminalSeen, evicted, dismissed, resurrected, leaked, childSent,
         parentSent, parentReturned, failed, reconnected>>
\* failed here means "every unanswered row visibly settled", exactly what FailTurn shows.
Pending == (parentSent /\ ~parentReturned) \/ (childSent /\ ~childReturned)
Close == /\ ended /\ ~closed
         /\ (Environment # "disciplined" \/ ~parentSent \/ parentReturned \/ "pending" \in Break)
         /\ closed' = TRUE
         /\ failed' = (failed \/ (Pending /\ "pending" \notin Break))
         /\ UNCHANGED <<chip, disk, receipt, finished, notified, ended, age,
              terminalSeen, evicted, dismissed, resurrected, leaked, childSent,
              childReturned, parentSent, parentReturned, reconnected>>
FailTurn == /\ ~closed /\ closed' = TRUE /\ failed' = TRUE
            /\ UNCHANGED <<chip, disk, receipt, finished, notified, ended, age,
                 terminalSeen, evicted, dismissed, resurrected, leaked, childSent,
                 childReturned, parentSent, parentReturned, reconnected>>
Next == Dispatch \/ Receipt \/ WorkerFinish \/ Notify \/ Lifecycle \/ Restart
        \/ ReconcileAbsent \/ Sweep \/ Tick \/ RunningSnapshot \/ Dismiss
        \/ Evict \/ Reconnect \/ ParentStop \/ ParentCall \/ ParentResult
        \/ ChildCall \/ ChildResult \/ Close \/ FailTurn
Spec == Init /\ [][Next]_vars /\ WF_vars(Receipt) /\ WF_vars(WorkerFinish)
        /\ WF_vars(Notify) /\ WF_vars(Close) /\ SF_vars(Tick) /\ WF_vars(Sweep)
        /\ WF_vars(ParentStop) /\ WF_vars(ParentResult) /\ WF_vars(ChildResult)
TypeOK == /\ chip \in States /\ disk \in States /\ age \in 0..2
          /\ \A b \in {receipt, finished, notified, ended, closed, terminalSeen,
               evicted, dismissed, resurrected, leaked, childSent, childReturned,
               parentSent, parentReturned, failed, reconnected}: b \in BOOLEAN
\* History witness survives ordinary capacity eviction; P2 checks later reappearance.
EventuallyTerminal == (finished /\ chip = "RUNNING") ~> terminalSeen
NoResurrection == [](~resurrected)
RetainedTerminalNeverRuns == []((terminalSeen /\ ~evicted) => chip # "RUNNING")
ParentIsolation == [](~leaked)
NoPendingChildRow == childSent ~> (childReturned \/ failed)
NoPendingRow == (parentSent \/ childSent) ~>
               (((~parentSent \/ parentReturned) /\ (~childSent \/ childReturned)) \/ failed)
=============================================================================
