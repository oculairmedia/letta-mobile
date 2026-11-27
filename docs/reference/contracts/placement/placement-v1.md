# Placement v1: placement, handoff and failover (X1/X6)

Status: normative for `core/crates/core-placement`. Source: C1 §5 and §7 (`core-research/c1-membership-log-placement.md`),
tightened while implementing. Builds on `../log/log-v1.md` (entries, segments, evaluation, fencing F1-F5) and
`../membership/membership-v1.md` (members, capabilities, the failover policy carried by `policy_set`). Section 12 lists
every difference from C1.

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

Everything here is a pure function or a pure state machine: no I/O, no clock reads (every time is an argument), no
tasks. An agent actor and the async drivers perform the actions the machines return.

Fixtures beside this file:

| File | Contents |
|---|---|
| `placement-v1-eval.json` | `judge`: membership ops plus a log script, giving each claim's verdict, the canonical epoch, the placement state and segment states; some cases change membership afterwards (`then`). Every case MUST give the same result in script order, reversed and rotated. `failover`: evaluator inputs and the expected decision |
| `placement-v1-vectors.json` | Epoch order pairs, feed epochs (including n = 2^32 − 1), a claim vote (body, `vote_id`, envelope), certificates under a 3-voter policy with their expected result, one framed example of every `baken/place/1` message, and frames that MUST NOT decode. Generated; see its `generator` field |

Test keys: `N<k>` is the Ed25519 key whose RFC 8032 seed is the byte `k` repeated 32 times. N1 founds the constellation
with every capability.

## 1. The placement record

- **P-1** `PlacementRecord {log_id, agent_id, epoch, state, max_n_seen, tip}` is derived from the log
  (`AgentLog::analyze` plus the store's `max_n_seen`), never stored on its own. `epoch` is the canonical segment's epoch,
  `state` is log v1 R-42 (`active`, `sealed_to(T)`, `placeless`, `superseded_unknown`), `tip` the canonical tip. A log
  with no valid segment has no record.
- **P-2** The holder of record is `epoch.claimant` while `active`, and the seal's target while `sealed_to(T)`; there is
  none while `placeless` or `superseded_unknown`. A record whose `max_n_seen` exceeds `epoch.n` is treated as
  `superseded_unknown` everywhere.
- **P-3 Gate (F4).** A node MAY append (and accept input) only when the state is `active`, it is the claimant, and
  `max_n_seen == epoch.n`. The gate states are `active`, `moving` (the holder is quiescing a move), `not_holder`,
  `placeless` and `superseded_unknown`; only `active` accepts.

## 2. The epoch order and the claimed n

- **P-10** `Epoch = (n, claimant)` is ordered by `n`, then by the claimant's 32 bytes as an unsigned big-endian number
  (`core_proto::Epoch`). Equal-n claims are therefore ordered deterministically and accepted as forks.
- **P-11** A claim takes `n = max(max_n_seen, parent.n, s) + 1`, where `s` is the sealed-to `n` when the parent is an
  `epoch_seal` naming a next epoch and the claim is not that move, else 0 (log v1 R-52). So a failover over a seal goes
  above the target's epoch, and the target's late open becomes a divergent tail instead of a tie.
- **P-12** A move's epoch is fixed by the seal: the target opens exactly `next_epoch` at `seal.seq + 1`.

## 3. The claim judge (claim rules 2 and 3)

`MembershipJudge` implements core-log's `ClaimJudge` over a `MembershipView`, built for one agent so per-agent policy
overrides apply. It MUST be a pure function of the claim context and the view, and it MUST NOT read node class
(self-reported, differs between replicas). It returns a verdict; the first failing rule names it:

| Order | Rule | Verdict |
|---|---|---|
| 1 | The structural reason rule (log v1 §6.2): genesis only at seq 0 and n 1; a move only from a seal naming its epoch | `structure` |
| 2 | The claimant is an active member | `not_active` |
| 3 | The claimant holds `node.execute` | `no_execute` |
| 4 | **genesis, move:** nothing more | `valid` |
| 4 | **rekey:** the parent's claimant is `rotated` to this claimant | `not_rotated` |
| 4 | **takeover_forced:** `node.takeover`, then `admin.full`; no certificate | `no_takeover`, `no_admin` |
| 4 | **failover, takeover:** `node.takeover` | `no_takeover` |
| 5 | **failover only:** the agent's effective mode is `auto` | `manual_mode` |
| 6 | **failover only:** with an explicit candidate list (the agent's override, else the policy's), the claimant is in it | `not_candidate` |
| 7 | **failover, takeover:** with a quorum, `votes` is a valid certificate for exactly `(log_id, epoch, parent.seq, parent.hash)` (section 4) | `bad_certificate` |

Ratification (log v1 R-40) means these rules apply to tips only: revoking a device or changing the policy never reverts a
segment that a valid later segment descends from.

## 4. Votes and certificates

- **P-20** Vote body: `{0: 1, 1: log_id bstr32, 2: epoch [n, claimant], 3: parent_seq, 4: parent_hash bstr32, 5: voter
  bstr32, 6: ts_ms}`. Keys 7-63 are rejected; 64 and up are kept. `vote_id = derive_key("baken 2026-11-26 claim vote v1",
  body)`. The envelope is `[body bstr, sig bstr64]` with `sig = Ed25519(voter, vote_id)`, verified strictly.
- **P-21** An `epoch_open` carries envelopes (as BCBOR values) in `votes`. A certificate is valid when **every** envelope
  is a valid vote for the claim, by a voter of the policy's quorum, with no voter twice, and there are at least
  `required` of them. One bad vote invalidates the whole certificate (codes: `bad_vote`, `bad_signature`, `wrong_claim`,
  `not_voter`, `duplicate_voter`, `too_few`).
- **P-22 Voter rules.** A voter answers a `claim_request` with a vote iff:
  - **V1** it has not promised this log to an epoch at or above the request's, except an identical `(epoch, parent)`;
  - **V2** the parent is on its canonical line, at or beyond its complete seq (a voter that lacks the parent refuses
    `behind` and votes after catching up);
  - **V3** it has not heard the holder within `after_ms` (waived for a manual `takeover`);
  - **V4** it persists the promise (`LogStore::set_vote_promise`, inside the transaction that must commit before the
    vote is sent). The store never lowers a promise and never names two fork points at one epoch.
  Refusals: `promised`, `behind`, `holder_alive`, `not_voter`.
- **P-23** The claimant tallies distinct valid votes for its exact claim and writes the `epoch_open` once `required` are in
  (envelopes ordered by voter). Otherwise it retries after `stagger_ms × 2^attempt` (at most 8 attempts, section 6).

## 5. The move pipeline

Two machines. Source events: `prepared`, `refused`, peer `abort`, turn idle, checkpoint written, ack, `verified`,
`verify_failed`, seal written, target opened, higher epoch seen, tick. Target events: checkpoint complete, materialized
(match or not), `commit`, open written, peer `abort`, tick.

- **P-30 Request.** The holder accepts `move_request` only when it may append (P-3), the target is another node, and the
  target is an active member with `node.execute` and `store`. Moves of one agent are serialized by the actor.
- **P-31 Prepare.** The source sends `prepare {log_id, from, proposed = (max_n_seen + 1, target), store_format,
  runtime_parity}`. The target refuses, in this order: `not_eligible` (not active with `node.execute` and `store`),
  `bad_epoch` (the proposed epoch does not name it or is not above `from`), `store_format`, `runtime_parity` (I6),
  `missing_profiles` (no binding for the agent's model profile or a conversation override's, I2), `h1_violations` (in the
  latest checkpoint), `no_replica`. Otherwise it replies `prepared {complete}`.
- **P-32 Quiesce.** On `prepared` the source sets its gate to `moving`. When the in-flight turn finishes (memory committed)
  it writes a checkpoint. On `quiesce_timeout_ms` it aborts, or, if the user chose "stop the turn and move", stops the turn
  and keeps waiting.
- **P-33 Top-up and verify.** The source streams the tail on `baken/log/1`. The target, once complete up to the
  checkpoint, materializes into staging and compares manifest and refs exactly; it replies `verified {checkpoint}` or
  `verify_failed {diffs, refs_mismatch}`.
- **P-34 Commit point.** On `verified` for its checkpoint the source appends `epoch_seal {next_epoch: proposed,
  checkpoint}`. **Writing the seal is the commit point.** Before it, every failure (refusal, peer abort, `verify_failed`,
  a timeout) aborts: the source sends `abort` and reopens its gate, and has written nothing but the checkpoint (P5).
  After it nothing aborts: the source sends `commit {seal}` and resends it every 5 s until the target's open is seen
  (or any higher epoch).
- **P-35** On `commit` (in any phase: the seal is authoritative) the target appends `epoch_open {reason: move,
  prior_epoch}` with the seal as parent, then takes over (swap staging, start the runtime, post-open checkpoint, open
  the gate, re-authorize grants, update the catalog copy).
- **P-36** A higher epoch seen by the source before the seal aborts with `fenced`; its gate stays shut through the record.

Timeouts: `prepare` 10 s, top-up 10 min, verify 5 min, `commit` resend 5 s, quiesce from the policy (default 120 s).

**Crash table** (recovery reads the log alone):

| Crash | Recovery |
|---|---|
| Source before the seal | `ResumeAfterStartupCheck`: no move happened; the source resumes after §5.6's startup probe |
| Source after the seal, before the target has it | `ResendCommit {to, seal}`: the source never writes again and keeps sending `commit`; any peer relays the seal |
| Target unreachable after a seal for `after_ms` | The record is `sealed_to(T)`; candidates watch T (section 6) and claim with the seal as parent, above T's epoch (P-11); T's late open is a divergent tail, and nothing is lost because the seal is the last entry |
| Target after receiving the seal, before its open | `WriteOpen {epoch, prior_epoch, seal}`: the epoch and seq are fixed by the seal, so this is idempotent |
| A move racing a failover | The epoch order decides on every replica; the loser's segment is a divergent tail, and exactly one node passes the gate |

## 6. The failover evaluator

- **P-40 Candidates.** Eligible: active members holding `node.execute`, `node.takeover` and `store`, and not a phone
  (self-reported class) unless `allow_phone_auto`. With an explicit list (the agent's override, else the policy's), its
  order is the policy order and nodes of unknown class stay eligible. Without one, only `always_on` and `desktop` nodes
  are candidates, always-on first. Ties go to the highest complete seq on the canonical line, then the lowest node id.
  The watched node is never its own candidate.
- **P-41 Watched node.** The claimant while `active`; the seal's target while `sealed_to(T)`. `placeless` and
  `superseded_unknown` never fail over automatically (only a manual claim places the agent).
- **P-42 Decision** for node C at time `now`:
  - `idle(self_holder | placeless | superseded | manual_mode | no_candidate | phone_manual_only | not_candidate |
    runtime_mismatch)`;
  - otherwise with `heard = max(last_heard, peer_heard if indirect_probe)` and `due = heard + after_ms + rank ×
    stagger_ms`: `wait {until: due}` while `now < due`; then `claim` when a dial to the watched node failed within the
    last 30 s, else `probe` (dial, then evaluate again).
  A candidate re-checks that no higher `epoch_open` arrived before claiming.
- **P-43 Self-fence.** With a quorum, a holder that has not heard `required` voters (itself counting when a voter) for
  `self_fence_ms` stops accepting new turns. Never without a quorum.
- **P-44 Startup.** A node whose canonical segment is its own and active resumes after its probe when a store peer
  answered (no quorum) or a majority of voters answered (quorum); with no answer, only when `resume_alone` (default true
  without a quorum).

Defaults (open questions Q4, Q5, Q12): no quorum; `after_ms` 5 min and `stagger_ms` 30 s; phones are manual-only;
`resume_alone` true without a quorum.

## 7. The fenced-holder report

- **P-50** A node that held epoch E and whose record now shows a higher epoch (or `max_n_seen > E.n`) stops (F4), sends
  `fenced {log_id, epoch: E, head}` to the holder, and pushes its segment to every peer.
- **P-51** The holder assesses a report: `not_claimant` (the sender is not E's claimant) and `not_lower` (E is not below
  the canonical epoch) are ignored; `need_entries` asks for E's segment when the head is not stored; `clean` when the
  head is on the canonical line (a seal, or a claim parented at the head); otherwise `divergent` with the divergences of
  stranded epoch E it has not recorded yet, for which it writes `divergence` entries (log v1 R-53). The tail becomes
  divergent by evaluation alone: fencing changes classification, never content.

## 8. Feed v1 amendment

Hosts advertise `conversation_feed_placement_v1` beside `conversation_feed_v1`.

- **P-60** `feed_epoch = (n << 21) | (u32_be(blake3(claimant)[0..4]) >> 11)` for the epoch of the most recent `epoch_open`
  on the canonical line whose reason is genesis, failover, takeover or takeover_forced. Moves and rekeys keep it; it
  stays below 2^53 and grows with n.
- **P-61** `cursor.line` = hex of the first 8 bytes of the entry hash of the log entry holding the cursor's feed entry.
- **P-62 Classification** of a cursor against a node's journal: no cursor → `snapshot(no_cursor)`; another feed epoch →
  `snapshot(epoch_changed)`; below `base_seq` → `snapshot(behind_horizon)`; above `head_seq` → `replay` with no
  entries, the client's own cursor and `"lagging": true` on a replica, `snapshot(ahead_of_head)` on the holder (it lost
  data); within range with an echoed `line` that differs from the entry's → `snapshot(line_changed)`; else `replay`.
- **P-63 Refusals.** A turn command for an agent this node does not hold (or holds while moving):
  `{"error": "not_holder: agent lives on <hex or none>", "placement": {"agent_id", "holder_node_id": hex or null,
  "epoch": {"n", "claimant"}, "state", "retry_after_ms"}}`. `state` is `active`, `moving` (the holder is quiescing, or
  a seal names a target that has not opened; the holder is then the target), `placeless` or `superseded_unknown`.
  `retry_after_ms` (2000) only with `moving`. `feed.subscribe` on a node with no replica answers `moved: agent lives on
  ...` with the same object.
- **P-64 Page additions** (optional): `served_by {node_id, is_holder}`, `placement`, `lagging` (omitted when false),
  `cursor.line`, the resync reason `line_changed`. Unknown fields are ignored on both sides.

## 9. `baken/place/1`

Framing as log v1: a `u32 BE` length, then a BCBOR map whose key 0 is the type, at most 1 MiB. Keys above a type's own
and below 64 are rejected.

| Type | Message | Fields |
|---|---|---|
| 1 | `move_request` | `1: log_id, 2: target` |
| 2 | `prepare` | `1: log_id, 2: from Epoch, 3: proposed Epoch, 4: store_format, 5: runtime_parity` |
| 3 | `prepared` | `1: complete Pos` |
| 4 | `refused` | `1: reason` |
| 5 | `verified` | `1: checkpoint Pos` |
| 6 | `verify_failed` | `1: [[path, expected sha256, actual sha256 or null]…], 2: refs_mismatch` |
| 7 | `commit` | `1: seal (232-byte signed entry)` |
| 8 | `abort` | `1: reason` |
| 9 | `claim_request` | `1: log_id, 2: epoch, 3: parent Pos, 4: reason (2 failover, 3 takeover)` |
| 10 | `claim_vote` | `1: vote envelope [body, sig]` |
| 11 | `claim_refused` | `1: reason` |
| 12 | `fenced` | `1: log_id, 2: epoch, 3: head Pos` |

## 10. Invariants covered here

| Invariant | Where |
|---|---|
| Epoch total order | property `epochs_are_totally_ordered`; vectors `epoch_order` |
| P1 one gate per epoch | P-3; `a_move_racing_a_failover_resolves_by_epoch_order_everywhere` (exactly one holder) |
| P2 same record everywhere | eval cases in three orders; property `equal_n_claims_resolve_to_one_fork` |
| P3 no append below the canonical epoch | P-3 gate; property `fencing_never_adopts_a_lower_epoch`; `crash_of_the_source_after_the_seal...` |
| P4 move under crashes | the crash-table tests in `move_pipeline.rs` |
| P5 failed verify | `a_failed_verify_leaves_the_source_holding_with_only_its_checkpoint` |
| P6 equal-n claims | property `equal_n_claims_resolve_to_one_fork`; eval `simultaneous_equal_n` |
| P7 certificates | property `promises_keep_certificates_unique`; `promises_persist_before_votes...`; vectors `certificates` |
| P8 revoked tip, ratification | eval `revoked_tip_claimant`, `ratified_after_revoke`, `rekey` |
| P9 phones and manual mode | property `failover_respects_phones_and_manual_mode`; eval `phone_is_manual_only`, `manual_mode` |
| D1 both continuations kept | `a_returning_holder_reports_and_its_tail_is_recorded_once` |
| D3 feed epoch | `moves_keep_the_feed_epoch_and_failovers_change_it` |

P1 and P4 across real partitions, crashes and message loss, and D2's uncertain actions, need the simulator (core-testkit)
and the agent actor (C10); this crate supplies their decisions.

## 11. Libraries

- BCBOR and hashing: `core_proto::bcbor` and `core_proto::crypto` (swappable underneath without changing this crate).
- Retry backoff: the `backon` crate behind `RetrySchedule` (only `backoff.rs` names it; default features off, so no
  async runtime).
- JSON shapes of the feed amendment: `serde`.
- Hand-written, because they are protocol rules no crate provides: the judge, the voter rules and tally (a set of
  distinct voters against a threshold), the move machines, the failover evaluator, and the `baken/place/1` message
  schema.

## 12. Differences from C1

| # | Point | C1 | Here, and why |
|---|---|---|---|
| 1 | Claim n over a seal | `max_n_seen + 1` | P-11 adds the sealed-to n: otherwise a failover over an unopened seal ties with the target's late open on n and may lose by node id, contradicting C1's own crash table |
| 2 | Failover and the policy | Policy only gates who tries | The judge also refuses an automatic failover in manual mode or by a node outside an explicit candidate list (both are in the membership log, so every replica agrees); a manual claim uses `takeover` |
| 3 | Node class | Decides candidacy | Never part of validity (self-reported, differs between replicas); only the evaluator reads it |
| 4 | Rekey | Parent is the rotated segment's head | Only the rotation is checked; the head part needs the store, and a rekey off a non-head forks like any other claim |
| 5 | `claim_request` | `{log_id, epoch, parent}` | Adds key 4 `reason`, because V3 is waived for a takeover and the voter cannot otherwise tell |
| 6 | Certificate with one bad vote | Unspecified | Invalid as a whole, so every replica reaches the same verdict from the same bytes |
| 7 | V2 when the voter lacks the parent | Unspecified | Refuse `behind`; vote after catching up |
| 8 | `moving` in refusals | Only while the holder quiesces | Also for a seal whose target has not opened, naming the target |
| 9 | Lagging vs `ahead_of_head` | "A cursor the line cannot explain" | A replica above-head cursor is `lagging`; the holder's is `ahead_of_head` (it lost data) |
| 10 | Commit resend end | Until T's open is seen | Also ends on any higher epoch: the seal is the source's last entry either way |
| 11 | Fixture location | `c1/` | Beside this spec, generated by this crate's tests |
