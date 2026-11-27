# Node v1: the composition root (core-node)

Status: normative for `core-node`. Builds on node-protocol v1, log-v1, placement-v1, catalog-v1, membership-v1 and
projection-v1. The runtime model is core-architecture's: one tokio runtime per node, one actor per agent log, a
`RuntimeHost` implemented outside the core.

## 1. Runtime

- N-1 A node owns one multi-thread tokio runtime (2 workers on phones, 4 on desktop). There is no global runtime.
  Exported async calls run on it and can be awaited from any executor.
- N-2 Store calls are synchronous and run on the node's runtime (the SQLite store is fast enough for v1; a blocking
  pool is a later optimisation).

## 2. What a node holds

The node store, membership (the signed op log, the view evaluated from it, a claim judge over the current view), the
catalog node (samod), the carrier with the protocols below, the log hub (stored entries relayed to every session), the
feed journal index, and one actor per agent log it stores.

## 3. Protocols

| ALPN | Kind | Served by |
|---|---|---|
| `/letta/appserver/0` | node-protocol lanes | the node session (§4) |
| `baken/log/1` | byte stream | core-log's replicator driver, one session per link |
| `baken/place/1` | byte stream | one message per stream (§3.1) |
| `baken/member/1` | byte stream | one push per stream (§3.2) |
| `meridian/automerge-repo/1` | byte stream | the catalog (catalog-v1) |

- N-3 One access hook: the catalog's gate for `meridian/automerge-repo/1`; for every other ALPN, members of the current
  view (and the in-process shell) are admitted and others closed `4403 peer_not_allowed` before any handler runs;
  `baken/member/1` is open to all (a joining device needs the ops).
- N-4 A node keeps a `baken/log/1` session with every peer it was told to connect to, redialling with exponential backoff
  (200 ms to 30 s). Every frame received marks the peer heard; every failed dial is recorded (failover liveness).

### 3.1 `baken/place/1` exchanges

- N-5 The dialler writes one `u32 BE` length-prefixed BCBOR body (placement-v1 §wire) and closes; replies are exchanges of
  their own (C1 §5.10, one stream per exchange). A message naming a log (`move_request`, `prepare`, `claim_request`,
  `fenced`, and `commit` through its seal header) goes to that log's actor; a reply (`prepared`, `refused`, `verified`,
  `verify_failed`, `abort`) goes to the log of the move in progress with its sender.
- N-6 This node answers `claim_request` with `claim_refused {not_voter}`: quorum voting is not served yet.

### 3.2 `baken/member/1`

- N-7 Body `{0: 1, 1: [head bstr32…], 2: [op envelope bstr…]}`. The receiver inserts the ops (each verified on insert);
  if the sender's heads miss any of its own heads, it pushes all its ops back. A node pushes all its ops when a log
  session reports `MembershipDiffers`. New ops re-evaluate the view, republish the catalog policy and re-evaluate every
  agent actor.

## 4. The node session (subset)

- N-8 `auth` with a `request_id` from a member NodeId, or from the in-process shell, is accepted (`Pairing::NotReported`);
  anything else is refused `pairing_required`; a missing `request_id` is refused `request_id is required`.
- N-9 Before `auth`, every other frame is answered `error {message: unauthorized}`. After it, `admin_rpc` `node.info`
  returns `admin_rpc_response {success: true, result: <node.info>}`; another method fails with
  `Unknown admin method: <method>`; another frame type is `Unknown command type: <type>`. Turn commands stay with the
  App Server at the runtime host's endpoint.

## 5. The agent actor

One actor per agent log, the only writer of this node's entries for that agent. Commands are serialized, so input
refused during a move's quiesce is refused by construction.

- N-10 **Create** writes the genesis `epoch_open` (store format, runtime parity) and becomes holder.
- N-11 **Becoming holder** (genesis, move, failover, restart as holder): seed the exporter with the canonical line, start
  the backend watcher and the runtime (`RuntimeHost::start`), export anything newer, and write a checkpoint (E4: after
  every `epoch_open`).
- N-12 **Export.** A watcher burst or an explicit flush scans and appends one batch in one transaction, then publishes it to
  the hub. A checkpoint follows every 500 entries or 10 minutes after the first unchecked entry, at quiesce, and after
  every `epoch_open`. `feed_heads` are the highest feed seq per conversation on the line.
- N-13 **Feed journal.** The holder appends `feed_append` with `feed_seq = journal head + 1` and indexes the row in the same
  transaction. Replicas index every canonical `feed_append` when their log changes.
- N-14 **Move, source.** `check_request`, then placement's `MoveSource`: `prepare` to the target; on `prepared`, quiesce
  through the runtime host (then `RuntimeHost::checkpoint` so letta-code commits memory), flush and write the checkpoint;
  the tail reaches the target over the live `baken/log/1` session; on `verified`, write the seal and send `commit` (resent
  every 5 s until the target's `epoch_open` is canonical); then stop the runtime and the exporter.
- N-15 **Move, target.** `check_prepare` with the target's facts: store format, runtime parity, missing model-profile
  bindings (every `profile/…` model in the agent and conversation records of the line must have a `ModelProfile`
  binding), the latest checkpoint's `h1_violations`, and the replica's tip. On a checkpoint at the tip of the source's
  epoch above the prepared position: materialize into `data/staging/<log id>` and verify; on `commit`, once the seal is
  stored with its payload, claim `epoch_open {reason: move}` over the seal, install staging into the backend, and become
  holder.
- N-16 **Fencing (F4).** A holder whose log shows a higher epoch (other than its own committed move) aborts the turn, stops
  the runtime and the exporter, and sends `fenced {log_id, epoch, head}` to the new holder.
- N-17 **Fenced report.** The holder assesses it (placement-v1 §7) and, for each divergence of that epoch not yet recorded,
  appends a `divergence` entry from projection-v1 §5.
- N-18 **Failover.** Each tick a replica that is not in a move runs placement's evaluator with its liveness view (last heard,
  last failed dial) and the configured node classes. On `claim` it claims `epoch_open {reason: failover}` over its canonical
  tip (`prior_head_seen` = that tip), materializes the whole line, installs it and becomes holder.

## 6. The runtime host and node.info

- N-19 `RuntimeHost`: `info()` (letta-code version, patch ids, boot-line counts, models), `start(agent, backend dir, hooks
  dir, env)`, `stop`, `quiesce(timeout) -> idle | busy`, `checkpoint` (let letta-code commit memory and flush files),
  `abort_turn`, `endpoint()`. Kotlin implements it on phones and the desktop; a Rust process supervisor may implement it
  on headless nodes.
- N-20 node.info is the N7 shape (`docs/reference/contracts/node.info/info-v1.md` on the N7 work): `schema 1`, `os`,
  `app_version` and `letta_code_version` reduced to a version token (`unknown` when absent), `store_format {major: 1,
  minor: 0}`, capabilities mapped from the node's membership capabilities (`ui`, `catalog`, `approve`, `node.execute` as
  `execute`, `store`) plus `always-on` for the always-on class, `models`, and `runtime_patches {source, applied, skipped,
  patch_ids_sha256}`.
- N-21 I6 parity is `letta-code@<version>+lcp:<sha256 hex of the sorted patch ids joined by "\n">`, with `unknown` for
  either part the host cannot see.

## 7. Not in v1 (open)

Quorum votes (`claim_request` answered `not_voter`), the startup probe before a restarted holder resumes, a source or
target restarting mid-move (placement's recovery functions exist; the actor does not act on them yet), writing the
catalog placement copy, `materialize_branch`, payloads by hash over `baken/blob/1`, and pushes on the session's push lane.
