# catalog-v1: catalog documents and per-document authorization

Status: normative. Implemented by `core/crates/core-catalog`. Fixtures beside this file are run by that
crate's tests (`docs/`, `gate/`, `sync/`).

Rule: **the catalog holds nothing authoritative.** Membership, placement and security settings are C1
signed records (membership v1, log v1). The catalog carries verifiable copies; readers verify them.

## 1. Engine and wire

- Documents are Automerge documents synced by samod (the automerge-repo protocol).
- Versions are pinned to what the JVM binding `org.automerge:automerge` 0.0.9 bundles:
  `automerge = "=0.8.0"`, `samod-core = "=0.9.0"`. The async repository is `samod = "=0.9.0"`, whose
  `samod-core` dependency is 0.9.0 (crates.io), so no hand-written driver is needed.
- ALPN `meridian/automerge-repo/1`. One bidirectional stream per connection (the dialler's first stream).
  Each frame is a u32 big-endian length, then one samod CBOR message. Frames over 4 MiB close the link
  `4413 frame_too_large`; the length prefix is checked before the payload is read.
- samod's peer id is the node's endpoint id, as 64 lowercase hex characters. The samod connection URL is
  `iroh://<endpoint id hex>`.
- On core-carrier the ALPN is a byte-stream protocol: `ProtocolsBuilder::accept_stream` serves it and
  `Carrier::dial_stream` dials it. The carrier never parses what crosses the stream.

## 2. Documents, ids, discovery

| Class | Count | Created by |
|---|---|---|
| `constellation` | 1 per constellation | the genesis node |
| `shell` | 1 per constellation | the genesis node |
| `agent` | 1 per standard agent (subagents live in their dispatcher's doc) | the agent's holder |
| `notebook` | existing `notebook/1` docs | unchanged |

- Document ids are random 16-byte samod `DocumentId`s, referenced as `automerge:<bs58check>`.
- Discovery: C1's genesis op carries `catalog_root` (the constellation doc id); `catalog_root_move`
  replaces it on rollover. The constellation doc lists `docs.shell` and `agents[a].docUrl`.
- **Registry.** Every node keeps the documents it knows in core-store's `DocRegistry`
  (`doc_id, class, agent_id, state ∈ {live, superseded, retired}`). The gate serves only registered,
  non-retired documents. A node registers a document when the root pointer, the constellation doc or the
  notebook index lists it, then `find`s it.
- **samod storage** is core-store's `KvStore`, namespace `samod`. A samod key (a list of components) is
  stored under core-store's key-path encoding, so a component prefix is a byte prefix (`load_range`).

## 3. Schemas

### 3.1 Conventions

- Root keys of every class: `schema` (`"baken.catalog.<class>/<major>"`), `v` (int, additive minor,
  starts at 1), `constellationId` (str), `createdAtMs` (int), `supersededBy` (str URL or null), `ext` (map).
- Times are int milliseconds, named `…AtMs`. Signatures and record bytes are `bytes`. Titles and names
  are `str` (the `text` type is not used). Counters only where stated.
- `ActorRef = {t: "user"|"agent"|"node", nodeId: str, clientId: str|null, agentId: str|null}`, display
  only.
- *Atomic* objects are written by putting a new map at their key, never edited field by field.
- Containers are created only in the genesis change, by repair (§6.3) or by rollover (§7).
- A key holding conflicting map containers is read as the union of their entries, the Automerge winner
  first on duplicate keys.

### 3.2 constellation (`baken.catalog.constellation/1`)

```
meta           { name: str, iconToken: str|null }
docs           { shell: str (automerge URL) }
membershipOps  <opIdHex> -> atomic { body: bytes, sig: bytes, issuer: str }
members        <nodeIdHex> -> atomic { name, caps: [str], state: "active"|"revoked", addedBy, addedAtMs,
                                       revokedAtMs: int|null, derivedFromHead }
nodes          <nodeIdHex> -> atomic { name, platform, roles: [str], appVersion, lettaCodeVersion: str|null,
                                       storeFormat: int, infoUpdatedAtMs }
agents         <agentId> -> { agentId, kind: "standard"|"subagent", name, description, avatarToken,
                              docUrl: str|null, treeAgentId: str|null, createdAtMs, createdBy, deletedAtMs }
settings       { signed: <kind> -> atomic { body, sig, issuer }, display: <key> -> atomic { valueJson,
                 updatedAtMs, updatedBy } }
```

A `membershipOps` entry is valid when `body` + `sig` verify as a C1 signed op and the key is its op id.

### 3.3 shell (`baken.catalog.shell/1`)

```
docRev    counter                       shell-state v1 document_revision
entries   <key> -> atomic Entry
pinsRev   counter                       pins v1 revision
pins      <agentId> -> atomic { pos: str, pinnedAtMs: int, by: ActorRef }
archived  <conversationId> -> atomic { agentId, atMs, by }
devices   <nodeIdHex> -> { entries: <key> -> atomic Entry }
Entry = { valueJson: str (≤ 8192 bytes), rev: int, updatedBy: ActorRef, updatedAtMs: int }
```

Every accepted entry write increments `docRev` by 1 and stores the value this replica then sees as `rev`.
Pins are ordered by `(pos, agentId)`, bytewise; at most 200 are shown.

### 3.4 agent (`baken.catalog.agent/1`)

```
agentId            str
placement          atomic PlacementCopy | null
conversations      <conversationId> -> Node
deleted            <conversationId> -> int (deletedAtMs)
subagentLinks      <subagentAgentId> -> atomic { dispatchedFrom: str, atMs: int }
memoryDivergences  <id> -> MemoryDivergence
```

**PlacementCopy** `{holder, epochN, epochClaimant, state, prevHolder, record: bytes, sig: bytes, signer,
copiedAtMs, copiedBy}`. `record` is the 168-byte header of the claimant's `epoch_open` log entry (log v1)
and `sig` its 64-byte signature. A copy is verified when the header decodes, its kind is `epoch_open`, its
`log_id` is the agent's log id, `signer` is the epoch claimant and the signature verifies. Readers use the
decoded epoch, never the plain fields.

**Node**: immutable `id` (= key), `agentId`, `parentId` (null exactly for `root`), `kind`
(`root|fork|edit|rerun|aside|subagent|divergence`), `forkPoint` (atomic `{lastSharedMessageId, logEpochN,
logEpochClaimant, logSeq}`, null for root), `createdBy` (atomic), `createdAtMs`; mutable `title`,
`titleSource`, `lastActivityAtMs`, `deletedAtMs`, `ext`; and `detail`:

- `edit {replacedMessageId}`; `rerun {rerunMessageId}`;
- `aside {anchorMessageId, returnedAtMs, summaryMessageId}`;
- `subagent {toolCallId, dispatchMessageId, subagentType, status: running|done|failed|cancelled,
  startedAtMs, endedAtMs}` (only `status` and `endedAtMs` change);
- `divergence {strandedHolder, strandedEpochN, strandedEpochClaimant, winningEpochN, winningEpochClaimant,
  lastCommonMessageId, lastCommonLogSeq, detectedAtMs, detectedBy, resolution: {state:
  open|kept|dismissed, atMs, by}}`. Everything but `resolution` is written once with the node.

**Divergence node id**: the bare UUIDv8 (no prefix) of the first 16 bytes of
`blake3("baken.divergence.v1" ‖ agentId ‖ 0x00 ‖ conversationId ‖ 0x00 ‖ u64be(n) ‖ claimant)` for the
stranded epoch; it is core-log's `DivergenceInputs::catalog_node_id`. The branch conversation is
`local-conv-<same uuid>`. `parentId` is the conversation that continued on both sides.

**MemoryDivergence** `{id, strandedEpochN, strandedEpochClaimant, memfsBranch, detectedAtMs, detectedBy,
resolution}`. `id` is the UUIDv8 of `blake3("baken.memdiv.v1" ‖ agentId ‖ 0x00 ‖ u64be(n) ‖ claimant)`.
`memfsBranch` is C1's ref without `refs/heads/`: `divergence/<n>-<first 8 hex of claimant>/<leaf>`.

### 3.5 Versioning

Additive changes bump `v`; unknown keys are kept; unknown enum values read as `unknown`. A node whose
known minor is below `v` reads but does not write (`header.writable = false`). A breaking change is a new
major, made only by rollover; an unknown major is unavailable and never written.

## 4. Authorization

`may(principal, doc) → none | read | write` (the `DocPolicy` trait) is a pure function of C1's evaluation,
the registry and the notebook grants:

| Class | read | write |
|---|---|---|
| constellation, shell, agent | active member | active member with `catalog.write` |
| notebook | active member, or a guest with `mayRead` for that doc | member with `catalog.write`, or a guest with `mayEdit` |
| unregistered or retired | nobody | nobody |

**Layer 0 (connection hook).** A carrier `AccessHook` admits active members and notebook-grant principals
on this ALPN; others close `4403 peer_not_allowed` before any handler runs.

**Layer 1 (link).** The principal is the carrier's verified peer. Its standing (refused, guest with its
grants, member with or without `catalog.write`) is taken at admission. When a policy update moves it, the
node closes the link: `4403 peer_not_allowed` when it became refused, `4409 caps_changed` otherwise. The
peer re-dials with fresh sync state.

**Layer 2 (per-message gate).** Inbound:

| Message | Rule |
|---|---|
| over 4 MiB | close `4413 frame_too_large` |
| bad CBOR, not a map, no text `type` | close `4400 malformed_frame` |
| `join` | `senderId` ≠ principal: close `4403 peer_id_mismatch`; else pass |
| `peer` | as `join`, and `targetId` ≠ us: close `4400` |
| any other message with a sender | `senderId` ≠ principal or `targetId` ≠ us: close `4400` |
| `request`/`sync`, unregistered doc | drop, no reply |
| `request`/`sync`, `none` | reply `doc-unavailable` (senderId us, targetId principal); count a denial |
| `request`/`sync`, `read` | no changes: pass the original bytes. Changes: strip them, pass the rewritten frame, quarantine `unauthorized_change`; the 3rd violation on the link closes `4403 write_not_permitted` |
| `request`/`sync`, `write` | the change chunks must load; a single change over 1 MiB is `oversized_change`, a chunk that does not load is `malformed_change`: quarantine and close `4400`. Else pass the original bytes |
| `doc-unavailable`, `error` | pass; if it names a doc, the doc must be registered, else drop |
| `ephemeral` | pass only for a notebook the principal may read; drop for catalog classes |
| `remote-heads-changed` | pass when the doc is readable, else drop |
| `remote-subscription-change` | filter `add` and `remove` to readable docs; pass (rewritten if changed), drop when empty |
| `leave` | pass |
| unknown `type` | drop |

More than 32 denials in 60 s close `4429 too_many_denials`.

Outbound: a message about a doc the principal cannot read is dropped, `ephemeral` for catalog classes is
dropped, and when this node may only read a doc its own `request`/`sync` messages are sent with the changes
stripped. The local Catalog API refuses writes the node may not make (`NotPermitted`).

**Quarantine.** Core-store KV namespace `catalog.quarantine`, a ring of JSON rows `{id, receivedAt,
principal, docId, reason, changeHashes, bytes}` capped at 16 MiB. Rows are never applied. Each raises a
`catalog_violation` event.

**Stated limits (v1).** Authorship is unauthenticated (trusted-relay model); actor ids are not bound to
node ids; changes a revoked node hands to an uninformed member are relayed. Signed copies stay verifiable,
so authority never depends on any of this.

**Kotlin nodes** until they run the core: layer 0, layer 1 and the doc-id part of layer 2 (the gate vectors
with `needsSyncDecode: false`); read-only principals are refused on the ALPN with `4403
write_not_permitted`.

## 5. Typed views and conflict rules (layer 3)

Views are computed from the canonical JSON (§8.1), so every conflicting value is visible. Invalid entries
are hidden and listed as `violations: [{path, reason}]`, never deleted. Reasons: `missing_container`,
`container_conflict_unioned`, `bad_shape`, `id_mismatch`, `unknown_enum`, `parent_rule`, `cycle`,
`immutable_changed`, `id_reused_after_tombstone`, `unverified_op`, `unverified_placement`,
`stale_member_row`, `over_pin_cap`, `bad_url`.

| Field | Rule |
|---|---|
| atomic objects, entries, pins, archive flags, titles, names | Automerge's winner |
| container keys | union of entries |
| `agents[a].docUrl` | the lowest id bytes win; the others are reported as `supersededDocUrls` (merge and supersede them) |
| `deletedAtMs` (directory and nodes) | non-null wins (the largest) |
| `members[n]` | when it disagrees with the local evaluation, the row is replaced and flagged |
| `placement` | the highest verified epoch among conflicting copies; `effective` = max with the local high-water mark |
| node `lastActivityAtMs` | max |
| subagent `status` | terminal beats `running`; between terminals the later `endedAtMs`, then `cancelled` > `failed` > `done` |
| divergence `resolution` | `kept` > `dismissed` > `open` |
| node immutable fields | the earliest value (lowest op id) is used and the field is flagged |
| `deleted[c]` vs a live node | the tombstone wins unless the node's `createdAtMs` is later (flagged) |
| cycles | a node whose parent chain returns to itself is shown as a root and flagged |
| orphans | a node whose parent is absent is shown with `orphan: true` |

## 6. Writes

### 6.1 Writers

The holder writes node creation, titles, activity, subagent status, placement, subagent links and
divergence records. Any catalog writer writes `deletedAtMs` and `resolution`. Writers refuse a document
whose `supersededBy` is set.

### 6.2 Secrets and paths

Every value is checked before it reaches Automerge, and the fixture test walks every string of every
fixture:

- structural strings: no absolute path (`^[A-Za-z]:[\\/]`, `^\\\\`,
  `^/(home|Users|root|var|tmp|opt|etc|mnt|data)/`, `file://`), no secret shape (`sk-…`, `ghp_…`,
  `xox[abp]-`, `Bearer `, `-----BEGIN`, a JWT), and no run of 32 or more characters above 4.5 bits per
  character, except in fields holding ids, URLs, refs or positions (`id`, `…Id`, `…Url`, `…Claimant`,
  `holder`, `signer`, `pos`, `memfsBranch`, …);
- prose fields (`title`, `name`, `description`, `valueJson`): the secret checks only;
- map keys are checked as ids; `bytes` values are skipped.

### 6.3 Repair

When a root container is missing or not a map, a writer finds the newest change at which it was a map,
creates a fresh map at the key and copies the entries it held then.

## 7. Rollover

- Triggers: saved size over 2 MiB; more than half an agent doc's nodes deleted; a breaking change.
- Hard cap 3.5 MiB: writers refuse non-essential writes until rollover.
- The successor's genesis is the materialised view: conflicts resolved (containers unioned), unknown keys
  kept, `supersededBy` null, deleted leaf nodes folded into `deleted`, tombstones older than 180 days
  dropped. Deleted nodes with live children stay.
- The old document gets `supersededBy`. For 7 days the rolling node sweeps entries that still arrive there
  into the successor (keyed, so idempotent; tombstones keep deleted nodes out). After 30 days the old
  document is retired.
- A constellation rollover also issues C1's `catalog_root_move {new_root, prev_root}`.

## 8. Fixtures

### 8.1 `docs/`

For each case: `<case>.automerge` (`save()` output), `<case>.json` (canonical JSON) and `<case>.meta.json`
(`class`, `documentId`, sorted `heads`, `schemaMajor`, `v`, `context`, `expectedView`,
`expectedViolations`). `index.json` lists the cases; `size_benchmark.json` records a 2,000-node agent doc.

Canonical JSON: keys sorted; `str`, `bool`, `null` as themselves; `int` as a number within ±(2^53 − 1),
else `{"$int": "…"}`; `{"$uint": "…"}`, `{"$f64": n}`, `{"$bytes": "<base64url, no padding>"}`,
`{"$counter": n}`, `{"$timestamp": n}`, `{"$text": "…"}`; a conflicted key is
`{"$conflict": [winner, …others in op id order]}`.

Fixtures are generated with actor `0x00…0n` for simulated node n and commit time 0, so they are
byte-reproducible. A binding loads each binary and must produce the same canonical JSON and typed view,
and a re-save must keep the heads. When `context.membershipFromDocOps` is true, the view is computed with
the membership evaluated from the document's verified `membershipOps`.

Regenerate: `BAKEN_REGEN_FIXTURES=1 cargo test -p core-catalog --test doc_fixtures --test gate_vectors`.

### 8.2 `gate/vectors.json`

`{case, frameHex, authenticatedId, ourId, principalAccess: {docId: {class, access}}, needsSyncDecode,
expect: {verdict: pass_original|pass_rewritten|drop|reply|close, rewrittenHex?, replyHex?, closeCode?,
closeReason?, quarantine?}}`. Each vector runs on a fresh gate.

### 8.3 `sync/<case>.json`

Step scripts run over the in-process carrier: `node`, `create`, `create_notebook`, `grant`, `register`,
`connect`, `find`, `edit`, `settle`, `membership`, `raw_join`, `dial_refused`, and the assertions
`expect_heads_equal`, `expect_nodes`, `expect_field`, `expect_no_copy`, `expect_not_found`,
`expect_closed`, `expect_quarantine`. Frames are asserted by effect, never by bytes.

## 9. Running the JVM interop test

`core/crates/core-catalog/tests/jvm_interop.rs` dials a Kotlin node's `AutomergeIrohRepoProtocol` over
Iroh through the full gate and syncs one notebook. It is skipped unless the environment names the node.

1. Pick a Rust secret key: `BAKEN_RUST_SECRET_HEX=<64 hex>`. Its endpoint id is printed by the test.
2. Start the Kotlin host with notebook sync, allowlisting that endpoint id:
   `./gradlew :iroh-wrapper-cli:run --args="app-server serve-iroh --iroh-port 4433
   --iroh-secret-key-file <file> --notebook-dir <dir> --notebook-peer-ids <rust endpoint id> …"`
   and create a notebook in it (its samod peer id is already its endpoint id).
3. Run:
   `BAKEN_KOTLIN_AUTOMERGE_NODE=<kotlin endpoint id>@127.0.0.1:4433 BAKEN_KOTLIN_NOTEBOOK_URL=automerge:<id>
   BAKEN_RUST_SECRET_HEX=<hex> cargo test -p core-catalog --test jvm_interop -- --nocapture`.

The test passes when the Rust node receives the notebook and reads `schema = "notebook/1"`.
