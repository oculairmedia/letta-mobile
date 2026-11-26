# Node store v1: storage SPI, on-disk layout, transactional guarantees and crash semantics

Status: **v1, implemented by `core/crates/core-store` (traits, in-memory store, contract suite) and
`core/crates/core-store-sqlite` (the node database).** Both implementations run the same contract suite. A change to
the rules or the layout is made here first, with its vector or contract case.

Sources: C1 (membership, log, placement; its Appendix A schema sketch), C2 (doc registry, samod storage), the core
architecture (one SQLite file per node, WAL, behind a `Store` trait) and H1 (node-local bindings are never replicated).

Fixtures, beside this file:

- [`store-v1-vectors.json`](store-v1-vectors.json): the schema version, application id and table set; the integer
  codes stored for states; key-path encoding vectors; binding key encodings, valid and invalid.

Rules marked **S-x** are normative for every `Store` implementation. Rules marked **D-x** bind the SQLite layout.

## 1. Shape

A node has exactly one store. It holds the node's copy of everything replicated (agent logs, membership, the catalog
registry and samod chunks, the feed index) and, apart from that, the node-local bindings.

| Area | Trait | Holds |
| --- | --- | --- |
| Log | `LogStore` | entries (header and signature verbatim, plus indexed fields), segments, the placement row, divergences, vote promises |
| Blobs | `BlobStore` | content-addressed bytes: log payloads and `baken/blob/1` blobs, keyed by `blake3(bytes)` |
| KV | `KvStore` | namespaced, bytewise-ordered keys; samod chunks live in namespace `samod` |
| Membership | `MembershipStore` | signed op envelopes as received, with issuer, kind and parents |
| Registry | `DocRegistry` | catalog documents this node serves (`live`, `superseded`, `retired`) |
| Feed index | `FeedJournal` | `(conversation, feed_seq) -> entry_hash` of the `feed_append` holding it |
| Bindings | `BindingsStore` | node-local values: model profile to provider handle, workspace id to path, hooks dir |

`Txn` is every area except bindings. `Store::read` hands a closure `&dyn Txn` (reads only); `Store::write` hands it
`&mut dyn Txn`.

The store does not hash, verify signatures, decode payloads or decide which segment is canonical. `core-log`,
`core-membership` and `core-placement` do, and give the store records whose indexed fields they took from verified
bytes. The store enforces what must hold whatever its caller does (section 3).

The traits are synchronous. Async drivers call them on a blocking pool.

## 2. Transactional guarantees

- **S-1 Atomicity.** Everything one `write` closure does commits together when it returns `Ok`, and is discarded
  entirely when it returns `Err` or panics. There is no partial commit across areas.
- **S-2 Durability.** When `write` returns `Ok`, the transaction survives process death and power loss.
- **S-3 Isolation.** Writers are serialized. A `read` sees one committed state for its whole closure. A `read` made
  from inside a `write` closure sees the last committed state, not the open transaction, and never deadlocks. A
  transaction reads its own writes.
- **S-4 Epoch claims.** `Store::commit_epoch_claim(EpochClaim)` commits an epoch claim's `epoch_open` entry, its
  payload, its segment row and the placement row (the fencing mark) in one transaction. `EpochClaim` can only be built
  from the `epoch_open` entry and payload, and derives the segment and placement rows from it, so the three cannot be
  committed apart through it. The same operation inside a larger transaction is `LogStore::adopt_epoch`, for example
  to reclassify the old line in the same commit (C1 F2).
- **S-5 Bindings are outside.** `BindingsStore` is reached through `Store::bindings`, is not part of `Txn`, and takes
  only typed `BindingKey`s. A replicated record cannot be written to it, and a binding cannot be written inside a
  replicated transaction. Each binding call is its own durable write. Callers do not write bindings from inside a
  `write` closure (on SQLite it waits for the writer, up to the busy timeout, then fails).

## 3. Rules the store enforces

These are carried by provided trait methods, so every backend enforces them identically.

- **S-10 Entries are immutable.** `put_entry` of a known hash with the same header and signature is a no-op that keeps
  the stored payload state; with a different header or signature it fails with `Conflict`. Nothing ever deletes or
  rewrites a stored header (C1 L3, F5). The header is exactly 168 bytes and the signature 64; `seq == 0` exactly when
  `prev` is zero.
- **S-11 Placement only rises with its entry.** `adopt_epoch` fails with `Fenced` unless the claim's epoch is above the
  current placement's (total order: `n`, then claimant bytes). A claim for a log owned by another agent is a
  `Conflict`. A fenced claim writes nothing; the caller may still store its entry with `put_entry` as divergent
  history.
- **S-12 Appends extend the open segment only** (C1 F1, F4). `append_canonical` requires: a placement exists; the
  entry's epoch is the placement's; the placement is `Active`; no stored entry of the log has a higher `n`
  (`max_n_seen`); the entry is not an `epoch_open`; `seq == head_seq + 1` and `prev == head_hash`. It stores the entry
  and payload and advances `head`, and `complete_seq` when it was at the old head and the new entry is complete.
- **S-13 `max_n_seen` is derived** from stored entries, so it never decreases, even when the placement falls back.
- **S-14 Placement changes without a new epoch** go through `set_placement_state` (only at the current epoch: seal,
  release, superseded) or `fall_back_placement` (only to a lower epoch whose segment and `epoch_open` are stored: the
  canonical segment fell back after its tip lost validity, C1 P8).
- **S-15 Pruning.** Only a compactable entry's payload may be `Pruned` (`set_payload_state`, `prune_payload`).
  `prune_payload` deletes the blob only when no entry still references it as `Stored` (C1 L11).
- **S-16 Vote promises never regress.** A promise at a lower epoch is `Fenced`; a second promise at the same epoch
  naming another fork point is a `Conflict` (C1 P7 depends on this surviving restarts).
- **S-17 Membership ops are immutable.** Same id and envelope: no-op. Same id, other envelope: `Conflict`. Parents are
  strictly ascending. Heads are the stored ops no stored op names as a parent.
- **S-18 Blobs.** A second put of a hash is a no-op; another length under the same hash is a `Conflict`. The store
  trusts the caller's hash.
- **S-19 Integers.** Every stored integer fits in `i64`; a larger `seq`, `n`, length or timestamp is `Invalid`.

## 4. KV and key paths

- Namespaces match `^[a-z][a-z0-9_.-]{0,63}$`. Keys order bytewise within a namespace. `kv_scan_prefix` returns every
  key starting with the prefix, ordered; an empty prefix scans the namespace and never another.
- **Key paths** (samod's storage keys are lists of strings, and `load_range` takes a component prefix):
  each component is a byte string in the `storekey` crate's format: its UTF-8 bytes with `0x00` written `01 00` and
  `0x01` written `01 01`, then a `0x00` terminator. The encoding of a component prefix is a byte prefix of every path
  extending it and of no other path, so `load_range(prefix)` is `kv_scan_prefix(SAMOD, encode(prefix))`. A decoder
  refuses a missing terminator, an escape of any byte other than `00` or `01` (it re-encodes and compares, so one path
  has one key) and non-UTF-8 components. Vectors: `key_paths`, `invalid_key_paths`.

The samod storage adapter itself lands with `core-catalog`; it is a thin mapping onto these calls, so a catalog write
and a membership op can share one transaction where needed (C2 §5.4).

## 5. Bindings (H1)

| Key | Stored as | Value |
| --- | --- | --- |
| `ModelProfile(name)` | `model_profile:<name>` | the provider handle serving that profile on this node |
| `WorkspacePath(id)` | `workspace_path:<id>` | the workspace's path on this disk |
| `HooksDir` | `hooks_dir` | the local hooks directory |

Names are 1 to 256 bytes with no control characters. A stored key no build writes is `Corrupt`. Secrets are never
bindings; credentials stay in platform secure storage. Vectors: `binding_keys`, `invalid_binding_keys`.

## 6. On-disk layout (SQLite)

- **D-1 One file per node.** `SqliteStore::open(path)` opens or creates one database file. SQLite keeps `<path>-wal`
  and `<path>-shm` beside it; the three are one database and are copied or deleted together. Large payloads are stored
  inline (SQLite's blob limit is far above `MAX_PAYLOAD`, 64 MiB).
- **D-2 Pragmas.** `journal_mode = WAL` (open fails if WAL cannot be enabled), `synchronous = FULL`,
  `foreign_keys = ON`, `busy_timeout = 5 s`. `application_id = 0x424B4E31` ("BKN1").
- **D-3 Connections.** A writer (every `write` is one `BEGIN IMMEDIATE` transaction), a read-only reader (every `read`
  is one deferred transaction, a WAL snapshot), and a bindings connection. Only the bindings connection touches the
  `binding` table.
- **D-4 Schema version** is `PRAGMA user_version`, managed by `rusqlite_migration`: opening runs every pending
  migration and sets `user_version` in one transaction. Migration 1 also stamps `application_id`. Migrations are append-only. A file whose version is above the
  build's is refused with `SchemaTooNew` and never touched. A file with a foreign `application_id`, or a non-zero
  `user_version` and no application id, is refused with `Corrupt`. The schema version is the store format node.info
  reports.

Schema v1 (`core-store-sqlite/src/schema.rs` is the exact DDL):

| Table | Key | Columns |
| --- | --- | --- |
| `log_entry` | `entry_hash` | `log_id, epoch_n, epoch_claimant, seq, prev, kind, flags, header(168), sig(64), payload_hash, payload_len, payload_state`; indexes `(log_id, seq, entry_hash)`, `(log_id, epoch_n, epoch_claimant, seq, entry_hash)`, `(payload_hash) WHERE payload_state = 1` |
| `blob` | `hash` | `len, bytes` |
| `segment` | `(log_id, epoch_n, epoch_claimant)` | `start_seq, parent_hash NULL, head_seq, head_hash, complete_seq NULL, state` |
| `placement` | `log_id` | `agent_id, epoch_n, epoch_claimant, state, sealed_n NULL, sealed_claimant NULL, open_entry_hash` |
| `divergence` | `divergence_id` | `log_id, losing_epoch_n, losing_claimant, fork_seq, tail_head_seq, recorded_entry_hash NULL` |
| `vote_promise` | `log_id` | `epoch_n, epoch_claimant, parent_seq, parent_hash` |
| `membership_op` | `op_id` | `envelope, issuer, kind, received_at` |
| `membership_parent` | `(op_id, parent)` | index on `parent` |
| `feed_row` | `(conversation_id, feed_seq)` | `entry_hash` |
| `catalog_doc` | `doc_id` (16 bytes) | `class, agent_id NULL, state ('live','superseded','retired'), registered_at` |
| `kv` | `(namespace, key)` | `value` |
| `binding` | `key` | `value` (node-local) |

Ids and hashes are BLOBs of their fixed length; a BLOB compares bytewise, so `ORDER BY epoch_n, epoch_claimant` is the
epoch total order. State codes are fixed by the vectors (`codes`): payload `0 missing, 1 stored, 2 pruned`; segment
`0 open, 1 sealed, 2 canonical_ancestor, 3 divergent, 4 invalid, 5 quarantined`; placement `0 active, 1 sealed_to
(sealed_n, sealed_claimant set), 2 placeless, 3 superseded_unknown`.

## 7. Crash semantics

- **Power loss or process death between transactions** loses nothing that was committed (S-2: WAL with
  `synchronous = FULL` syncs the WAL on every commit).
- **During a transaction** nothing of it is visible after recovery: WAL frames without a commit record are ignored when
  the database is next opened. So an epoch claim is either wholly present (entry, payload, segment, placement) or
  wholly absent, and a holder can never come back with a placement and no `epoch_open`, or an entry past a fencing mark
  it did not record. The `durability` tests copy the database and WAL mid-transaction and open the copy.
- **A panic inside a closure** drops the transaction, which rolls back; the store keeps working (the lock is taken
  over, never left poisoned).
- **Seq reuse** after a crash is impossible: the segment head commits with each entry (C1 §3.7). Restoring an older
  database file can reuse a seq; that is detected as equivocation by `core-log` (C1 §3.8), not prevented here.
- **Migrations** run in one transaction: a crash mid-migration leaves the previous version, which migrates again on
  the next open.

## 8. In-memory store and wasm

`MemoryStore` implements the same traits and passes the same contract suite. A write works on a copy of the state and
swaps it in on `Ok`; blobs are shared between copies. It holds nothing across restarts. `core-store` (traits plus
`MemoryStore`) builds for `wasm32-unknown-unknown`; the browser is not a store node and never links SQLite.

## 9. Contract suite

`core_store::contract` (feature `contract`) holds every case; `store_contract_suite!(factory)` expands one test per
case on a fresh fixture. It covers S-1 to S-19 (including a panicking closure and a read from inside a write) and two
property tests: KV equals a model after any mix of committed and aborted transactions, and for any order of claims
the placement ends at the highest epoch offered with no trace of fenced claims.

## 10. Differences from the C1 Appendix A sketch

- `max_n_seen` is derived from `log_entry` (S-13) instead of a stored placement column, so it cannot regress or drift.
- `placement.holder` is not stored: it is always the epoch's claimant. `sealed_n`/`sealed_claimant` hold `sealed_to`.
- `payload.refcount` and `payload.path` are dropped: references are counted from `log_entry` when pruning, and v1
  stores every blob inline, which keeps the database one file. External blob files can come in a later schema version
  if payload sizes call for it.
- `log_entry.payload_len` is added (the materializer and blob fetch need the length without the header decoded).
- `feed_row` replaces the prior-art `feed_entry(conv, epoch, seq, body)`: the body lives once, in the log payload.
- `catalog_doc` (C2) and `kv` (samod chunks, C2's `samod_chunk`) are added.
