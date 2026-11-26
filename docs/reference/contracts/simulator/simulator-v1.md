# Baken core simulator v1

Status: normative for `core/crates/core-sim`. Source: C1 §8 (invariants) and §9.3 (scenarios).

The simulator runs N in-process nodes of the Rust core and checks C1's invariant groups after
every step and at quiescence. It is the acceptance harness for membership, the agent log,
`baken/log/1`, placement, handoff and failover working together.

## 1. What a simulated node is

Each node is the real core, not a model of it:

| Part | Crate | Notes |
|---|---|---|
| Store | `core-store` `MemoryStore` | Durable: survives a crash. A backup is a copy of the committed state. |
| Agent logs | `core-log` `AgentLog` | One per agent; holder appends pass the F4 gate inside `AgentLog`. |
| Replication | `core-log` `driver::run_session` | One `baken/log/1` session per connected pair, over a carrier call lane. |
| Membership | `core-membership` `MembershipLog` | Ops are gossiped on `baken/member/1`; every change re-evaluates every log. |
| Judge | `core-placement` `MembershipJudge` | Built over the node's current view for each verdict. |
| Moves | `core-placement` `MoveSource`, `MoveTarget` | Messages on `baken/place/1`. |
| Failover | `core-placement` `failover::evaluate`, `voter::cast_vote`, `Tally` | Evaluated every tick (1 s). |
| Network | `core-carrier` `InProcessNetwork` | Partition, sever, stop, delay and seeded loss. Every exchange crosses it. |

A node's in-memory state (a move in progress, a vote tally, liveness observations) is lost on a
crash; its store is kept. A restarted node runs the startup probe (C1 §5.6) and recovery from its
log (C1 §5.5 crash table).

## 2. Determinism

- One tokio current-thread runtime with the clock paused; every task is local to one `LocalSet`.
- Every select in the simulator, the carrier and the log driver is biased.
- The network's loss draws come from the run's seed; generated schedules come from a seeded
  proptest runner.
- The same seed and steps give the same stored entries on every node (tested).

## 3. Binding `baken/log/1` to a carrier

The log driver reads and writes bytes (`AsyncRead`, `AsyncWrite`); carriers move UTF-8 frames. The
dialler opens a call lane with the request frame `log`; each side binds its lane to one half of a
tokio duplex pipe. Each chunk the driver writes crosses as one frame holding the chunk's lowercase
hex; frames received are decoded back into the pipe. The end of the lane is the end of the stream,
both ways. This binding is the simulator's; a QUIC transport carries the bytes on a raw stream.

## 4. Scenario steps

`add`, `revoke`, `policy`, `create_agent`, `turn(agent, conv, n)`, `tool_call(agent, key, finish)`,
`move(agent, to)`, `claim(node, agent, reason)`, `crash(node)`, `crash(node, at: move point)`,
`restart(node)`, `partition([[...], [...]])`, `sever(a, b)`, `heal`, `advance(ms)`, `drop_rate(p)`,
plus `backup`/`restore`, `delay`, `checkpoint`, `compact` and `corrupt_materialize` on the handle.

A turn of n messages appends, per message, a `file_append` to the conversation's
`messages.jsonl` and a `feed_append`; the last closes the turn. Turns are atomic.

The nine move crash points, in pipeline order: source after `prepare`; target after `prepared`;
source after quiesce; source after the checkpoint; target after `verified`; source after the seal;
source after `commit`; target after `commit`, before its `epoch_open`; target after its
`epoch_open`.

## 5. Invariants checked

After every step, on every node: L1, L2, L3, L5 (except segments with an equivocation, whose line
legitimately moves to the lower-hash branch), L6, and P1 with a 30 s grace after a topology change.

At quiescence, per component of up nodes that are reachable and admit each other: M7 and M9 (same
op set and view), L4 (same headers and canonical tip), P2 (same placement record), P1 (at most one
gate passer), L9 and D5 (same divergences; every entry that competes with the canonical line is in
a divergence tail), L8, L10 and L12 (the canonical line folds, paths are allowlisted, every node
folds the same manifest).

Scenario-specific: M8 (s01, s10), L7 and D2 (s03), X2 and D3 for moves (s04, s13), P4 (s05),
P5 (s06), D1 and P6 (s07, s08), P7 (s09), P8 and `from_revoked` (s10), L9 for equivocation (s11),
P9 (s12), D3 (s13), backpressure resync with L5 (s14), L11 (s15), D4 (s16), D6 (s17), D5 (s18).

E1-E3, M1-M6 are covered by the owning crates' fixture and property tests and are not repeated.

## 6. Modelled, not built

- `baken/member/1` and `baken/place/1` have no session drivers yet. Ops cross as one call per
  push (`heads|ops`, hex); a receiver that sees the pusher lacks ops pushes its whole set back.
  Placement messages cross as one call each, carrying `PlaceMessage::encode_frame` in hex.
- `heard(X)` is the last session event, place message or open session with X; `ping.heard` gossip
  is read from the connected peers' observations.
- Materialization is `core-log`'s `StateFold` of the canonical line; a verify mismatch is injected.
- Catalog nodes are not written; D5 compares the divergence inputs and C2 node ids every node
  computes.
