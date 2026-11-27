# Membership v1 (M1): the constellation's signed op DAG

Status: normative, 2026-11-26. Implemented by `core/crates/core-membership`. Derived from the M1 section of the C1 design
spec (membership, log and placement), with the C2 catalog requests (`catalog_root`, `catalog_root_move`, `catalog.write`)
and the C1 open-question defaults Q1, Q2, Q3, Q11 and Q16. Where this document is tighter than C1, this document wins;
section 10 lists every such point.

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

Fixtures beside this file:

| File | Contents |
|---|---|
| `bcbor-v1.json` | Valid BCBOR encodings with their values, and invalid inputs with the error code a decoder MUST report. Shared by every signed area; run by `core-proto`, which implements the profile (`core_proto::bcbor`) |
| `membership-ops-v1.json` | One signed op of every kind under fixed test keys, hash-context vectors, and invalid envelopes with the stage and code at which they MUST be rejected. Generated; see its `generator` field |
| `membership-eval-v1.json` | DAG cases: ops by label, the expected view and exactly the expected rejections. Every case MUST give the same result in every arrival order |

Test keys: `N<k>` is the Ed25519 key whose RFC 8032 seed is the byte `k` repeated 32 times.

## 1. Model

- Membership is an append-only DAG of signed **ops**. Every node holds the ops it has verified and **evaluates** them
  into a **view**: who is a member, with which capabilities, who the admins are, the failover policy and the catalog root.
- The **constellation id** is the op id of the genesis op. A node accepts exactly one genesis: the one whose op id equals
  its trust anchor (from its invite, or created by itself).
- The view is a pure function of the set of ops held (section 6). Arrival order, the evaluating node and wall clocks MUST
  NOT affect it.
- The catalog holds only copies. Nothing read from a catalog document grants anything, and the catalog root is taken only
  from the view.

## 2. BCBOR profile v1

Every op body and envelope is BCBOR, a deterministic CBOR subset, implemented once in `core_proto::bcbor`: the CBOR
reading and writing are the `minicbor` crate's, and `core_proto::bcbor` adds only the profile checks below (allowed
types, shortest heads, ascending uint keys, depth and item limits). A decoder MUST reject any input that breaks a rule,
with the code in brackets:

1. Allowed: major type 0 (unsigned int), 2 (byte string), 3 (text string, valid UTF-8 [`invalid_utf8`]), 4 (array),
   5 (map), and from major type 7 only `false` (0xf4), `true` (0xf5) and `null` (0xf6). Rejected: negative ints
   [`negative_int`], tags [`tag`], floats and every other simple value [`forbidden_simple`], indefinite lengths and the
   break byte [`indefinite_length`], additional info 28-30 [`reserved_info`].
2. Ints and lengths MUST use the shortest form [`non_shortest`].
3. Map keys MUST be unsigned ints [`non_uint_key`], strictly ascending [`unsorted_keys`], without duplicates
   [`duplicate_key`].
4. Exactly one top-level item [`trailing_bytes`]; an item cut short is [`truncated`].
5. Nesting depth at most 16, counting the top-level item as 1 [`too_deep`]. At most 65,536 items per array or map
   [`too_many_items`].
6. A **sorted set** (`sset`) is an array whose elements are strictly ascending by the bytes of their encodings. A field
   declared `sset` that is not sorted, or has a duplicate, makes the op malformed.

Consequences: one value has exactly one encoding, so whatever decodes re-encodes to the same bytes. A verifier hashes and
verifies the bytes as received and never re-encodes them.

## 3. Ops

### 3.1 Body

An op body is a BCBOR map:

| Key | Field | Type | Present in |
|---|---|---|---|
| 0 | `v` | uint, = 1 | all |
| 1 | `kind` | uint: 0 genesis, 1 add, 2 revoke, 3 grant, 4 rotate, 5 policy_set, 6 catalog_root_move | all |
| 2 | `constellation` | bstr32: the genesis op id | all except genesis |
| 3 | `issuer` | bstr32: the signing node id (Ed25519 public key) | all |
| 4 | `parents` | sset of bstr32 op ids; empty exactly in genesis | all |
| 5 | `ts_ms` | uint, informational only | all |
| 6 | `subject` | bstr32 node id | genesis (= issuer), add, revoke, grant, rotate (= issuer) |
| 7 | `caps_add` | sset of capability strings | genesis, add, grant |
| 8 | `caps_remove` | sset of capability strings | grant |
| 9 | `name` | tstr, at most 64 bytes | genesis, add (optional) |
| 10 | `new_key` | bstr32 | rotate |
| 11 | `new_key_sig` | bstr64: the proof of possession (3.4) | rotate |
| 12 | `reason` | tstr, at most 256 bytes | revoke (optional) |
| 13 | `policy` | map (section 8) | policy_set |
| 14 | `label` | tstr, at most 64 bytes: the constellation's display name | genesis (optional) |
| 15 | `catalog_root` | bstr16: the constellation catalog document id | genesis |
| 16 | `new_root` | bstr16 | catalog_root_move |
| 17 | `prev_root` | bstr16 | catalog_root_move |

An op is **well-formed** when all of these hold, else it is rejected with the code in brackets:

- The body is at most 8,192 bytes [`too_large`] and canonical BCBOR [`bad_encoding`], and is a map [`bad_shape`].
- `v` is 1 [`bad_version`, or `missing_field`]; `kind` is known [`unknown_kind`].
- Every field the kind requires is present [`missing_field`]. No key below 64 that the kind does not allow is present
  [`unexpected_field`]. Keys 64 and up are extensions: allowed, kept in the body, and without meaning in v1.
- Every field has its type, size and content: 32-byte ids, 16-byte roots, a 64-byte proof, texts within their limits,
  capability strings matching `^[a-z][a-z0-9._*-]{0,63}$`, sorted sets sorted, and `policy` a map [`bad_field`].
- `parents` is empty exactly for genesis [`bad_parents`]. For genesis and rotate, `subject == issuer`
  [`subject_not_issuer`].
- Grant carries both `caps_add` and `caps_remove` (either MAY be empty). Add carries `caps_add` (it MAY be empty).

### 3.2 Op id and signature

- `op_id = BLAKE3.derive_key("baken 2026-11-26 membership op v1", body)`, 32 bytes.
- `sig = Ed25519(issuer_sk, op_id)`. What is signed is always the 32-byte id, never the body.
- Verification MUST be strict (`verify_strict`): reject a non-canonical `S`, a small-order public key and a small-order
  `R` [`bad_signature`].
- Ed25519 signing is deterministic and the id covers the body only, so re-signing a body yields the same op.

### 3.3 Envelope

`[body bstr, sig bstr64]`, as BCBOR. Anything else is [`bad_shape`]. A decoder MUST check the envelope size before
decoding (a body at the limit plus 70 bytes of framing).

### 3.4 Rotate proof of possession

`pop_msg = BLAKE3.derive_key("baken 2026-11-26 rotate pop v1", subject ‖ new_key ‖ constellation_id)` and
`new_key_sig = Ed25519(new_sk, pop_msg)`, verified strictly under `new_key`. A rotate with a bad proof is well-formed;
it is unauthorized (section 5, `bad_proof_of_possession`).

## 4. The held DAG

- A node inserts an op only after it is well-formed and its signature verifies.
- A genesis whose op id is not the trust anchor is refused [`foreign_genesis`]. A non-genesis op whose `constellation`
  is not the trust anchor is refused [`constellation_mismatch`].
- An op whose parents are not all held waits in a **pending** buffer (at most 1,024 ops per log by default; a driver MAY
  additionally bound it per peer). When its parents arrive it joins the DAG, parents before children. A full buffer
  refuses new pending ops [`pending_full`].
- The held set is therefore always down-closed and descends from the one genesis. Its **heads** (held ops that no held
  op names as a parent) are the parents of the next op the node writes. A node never writes a subset of its heads: that
  would change the op's causal past. Each parent costs 34 bytes of the 8,192-byte body, so a node holding more than about
  230 concurrent heads cannot write until v2 defines a merge op; signing then fails with [`too_large`]. Heads are ops
  written concurrently by members that have not synced, and every op a node writes merges all of its heads into one, so a
  constellation of one user's devices stays far below that bound.
- `past(X)`, the causal past of X, is the transitive closure of its parents. X and Y are **concurrent** when neither is
  in the other's past.

## 5. Capabilities and authorization

### 5.1 Capabilities

| Capability | Grants |
|---|---|
| `admin.full` | Issue any membership op. Implies `devices.approve` |
| `devices.approve` | Issue `add` with `caps_add ⊆ DEVICE_CEILING = {ui, catalog, catalog.write, approve, store, node.replicate}` |
| `node.execute` | Claim placement with reason genesis, move or rekey; run turns |
| `node.takeover` | Claim by failover or takeover; also needs `node.execute` |
| `node.replicate` | Open the log and blob protocols; pull and push agent logs |
| `store` | Keep a full replica of every agent log; counts for replication, votes and failover candidacy |
| `ui` | Run the shell |
| `catalog` | Hold the catalog replica |
| `catalog.write` | Write catalog documents. Reading needs only active membership |
| `approve` | Answer approvals and app-tool requests |

The protocol grants exactly what `caps_add` names. `node.execute`, `node.replicate`, `node.takeover` and
`devices.approve` are in no default role: only an explicit op grants them.

UI presets (not part of the protocol; the fixtures name them `preset:<name>`):

| Preset | Capabilities |
|---|---|
| `desktop_node` | ui, catalog, catalog.write, approve, store, node.replicate, node.execute, node.takeover |
| `always_on_linux` | catalog, catalog.write, store, node.replicate, node.execute, node.takeover |
| `read_only` | ui, catalog |
| `phone` | ui, catalog, catalog.write, approve, store, node.replicate. The phone is a full store node |
| `phone_execution` | `phone` plus node.execute, once the user opts the phone into execution |
| `first_device` | all ten (the genesis) |

`catalog.write` is in every writing preset and in `DEVICE_CEILING`; an add without it makes a read-only member (Q16).
Node class (desktop, always-on, phone) is self-reported through node.info and affects only the node's own candidacy
(Q11); it is not a membership field.

### 5.2 Authorization

Let `S(D)` be the state evaluated from a down-closed set D (section 6). Each op X is checked against the state of its
causal past, written `Sp(X)`; section 6 says exactly which state that is. "Active" and "holds" refer to that state.
Rejection codes are in brackets.

- **Locked.** If `Sp(X)` has no active member holding `admin.full`, every non-genesis op is unauthorized [`locked`].
- **genesis**: `caps_add` contains `admin.full` [`genesis_without_admin`].
- **add**: the issuer is active [`issuer_not_active`] and holds `admin.full`, or holds `devices.approve`
  [`issuer_lacks_capability`] with `caps_add ⊆ DEVICE_CEILING` [`ceiling_exceeded`]; `subject ≠ issuer` [`self_add`];
  the subject is not tombstoned [`subject_tombstoned`] and has never been a member, active or rotated away
  [`subject_already_member`].
- **grant**: the issuer is active with `admin.full` [`issuer_not_active`, `issuer_lacks_capability`]; the subject is
  active [`subject_not_active`]; `caps_add ∪ caps_remove` is non-empty [`empty_grant`]; the two are disjoint
  [`overlapping_grant`]; last-admin rule [`last_admin`].
- **revoke**: the issuer is active with `admin.full`; the subject is not already tombstoned [`already_tombstoned`] (a
  revoke of a never-added id is allowed and tombstones it in advance); last-admin rule [`last_admin`].
- **rotate**: the issuer is active [`issuer_not_active`] (any capabilities); `new_key` is not and never was a member and
  is not tombstoned [`rotate_key_reused`]; the proof of possession verifies [`bad_proof_of_possession`].
- **policy_set**: the issuer is active with `admin.full`; the policy decodes (section 8) and every quorum voter is
  active holding `store` and `node.replicate` [`policy_invalid`].
- **catalog_root_move**: the issuer is active with `admin.full`; `prev_root` is the current root [`prev_root_mismatch`];
  `new_root ≠ prev_root` and `new_root` was never a root, current, earlier or superseded [`root_reused`].

**Last-admin rule:** a revoke, or a grant whose `caps_remove` contains `admin.full`, is unauthorized when no active
member other than its subject holds `admin.full` in `Sp(X)`.

## 6. Evaluation

For a down-closed set E:

1. A **revoke** R is valid in E iff it is well-formed and authorized in `S(past(R))`. Its validity depends only on its
   causal past, never on E.
2. Any other op X is valid in E iff
   - (**revoke wins**) there is no revoke R valid in E with `R.subject == X.issuer` and `X ∉ past(R)`
     [`excluded_by_revoke`], and
   - X is authorized in `S_E(past(X))`: the state of the ops of `past(X)` that are valid **in E**.

   Because the past is evaluated against E's revokes, exclusion cascades: an op whose issuer was admitted by an excluded
   op is unauthorized as well [`issuer_not_active`].
3. The recursion is well-founded: `S(past(R))` is over a strictly smaller set, and the validity of ops in `past(X)` is
   decided before X (they precede it in the linear order).

`S(E)` applies the valid ops of E:

- **Status.** A node id is `revoked` if any valid revoke names it; else `rotated(to)` if a valid rotate by it exists
  (`to` is the new key of the first such rotate in the linear order); else `active` if a valid genesis (its issuer), add
  (its subject) or rotate (its `new_key`) names it. Tombstones are sticky: a revoked id is never active again, whatever
  adds exist, earlier, concurrent or later (Q2: a returning device rotates to a fresh key and is added again). A rotated
  key is retired, not revoked: what it signed before the rotate stays valid.
- **Capabilities** are a remove-wins observed-remove set per (subject, capability). Capability c is held by an active s
  iff some valid op `a` adds c to s (genesis, add, a grant's `caps_add`, or a rotate passing c to its new key) such that
  every valid op `r` that removes c from s (a grant's `caps_remove`, or any revoke of s) is in `past(a)`. A removal
  concurrent with an addition wins.
- **Rotate.** `new_key` receives the capabilities and the name the subject had in the rotate's `Sp`.
- **Linear order.** Kahn's topological order over E, choosing among ready ops the smallest
  `(kind_class, op_id)`, where kind_class is genesis 0, add 1, grant 2, rotate 3, policy_set 4, catalog_root_move 5,
  revoke 6 (op ids compare as bytes). The order restricted to a down-closed subset is that subset's own linear order.
- **Names** and the **policy** are last-writer-wins in the linear order (a later `name` on an add of the same subject, or
  a later valid policy_set, replaces an earlier one). The constellation label comes from genesis.
- **Catalog root.** Start at the genesis `catalog_root`. At each root r, the successor is the `new_root` of the valid
  catalog_root_move with `prev_root == r` and the lowest op id, among those whose `new_root` is not already on the chain.
  The current root is the end of the chain. Every other root named by genesis or a valid move is **superseded** (C2
  reads it once and retires it).
- **Locked.** With a genesis held but no active member holding `admin.full`, the constellation is locked: no further op
  is authorized. It arises only from two concurrent ops that each take an admin away (a revoke, a rotate retiring the old key, or a grant removing `admin.full`; Q1: mutual concurrent revokes both apply; a revoked node never
  gains authority; the recovery is a new genesis that re-adds the same node ids, and agent logs stay verifiable because
  node keys sign them).

An op that is held but not valid has no effect; evaluation reports why (the codes above).

## 7. Joining and bounded push

1. The joiner redeems an invite carrying the constellation id, which becomes its trust anchor.
2. An admin (or a `devices.approve` holder) writes `add {subject: joiner}`.
3. The joiner syncs the DAG from the inviter, verifies every op, evaluates, and is a member once its view says so.
4. **Bounded push.** A peer that has not yet received the add would refuse the joiner. The membership protocol therefore
   admits non-members in push-only mode. The joiner pushes its **proof bundle**: the valid op that made it active (the
   genesis, its add, or the rotate to its key; the one with the smallest causal past if several) plus that op's causal
   past, in linear order. A receiver:
   - refuses a tombstoned pusher outright;
   - charges each envelope against a per-connection budget of 64 ops and 64 KiB **before** decoding it, and stops
     reading when the budget is used up;
   - inserts each op like any other (sections 3-4), then evaluates; when the pusher is active, its other protocols admit
     it.

   A bundle larger than the budget cannot be pushed; the joiner falls back to syncing through a node that already
   admits it.
5. New ops flood to every connected member, deduplicated by op id. A node that applies a revoke closes the revoked
   node's connections at once and re-checks every connection whose peer's status changed.

Connection admission on the membership protocol: active members get full sync; ids with no status and rotated-away keys
get bounded push; tombstoned ids are refused.

## 8. Failover policy (`policy_set`)

`policy` is a map; absent keys take the defaults. Keys 12-63 are rejected; 64 and up are allowed and ignored.

| Key | Knob | Type | Default |
|---|---|---|---|
| 0 | `mode` | 0 manual, 1 auto | 1 |
| 1 | `after_ms` | uint | 300,000 |
| 2 | `stagger_ms` | uint | 30,000 |
| 3 | `candidates` | array of distinct bstr32 node ids, in rank order | empty (derived by placement) |
| 4 | `allow_phone_auto` | bool | false |
| 5 | `quorum` | null, or `{0: voters sset of bstr32, 1: required uint}` | null |
| 6 | `indirect_probe` | bool | true |
| 7 | `self_fence_ms` | uint | `after_ms − 60,000`, saturating at 0 |
| 8 | `quiesce_timeout_ms` | uint | 120,000 |
| 9 | `resume_alone` | bool | true without a quorum, false with one |
| 10 | `runtime_mismatch_failover` | bool | false |
| 11 | `per_agent` | array of `[agent_id tstr, {0: mode, 3: candidates}]`, strictly ascending by agent id bytes | empty |

A quorum needs at least 3 voters; `required` defaults to `⌊voters/2⌋ + 1`, MUST NOT be lower, and MUST NOT exceed the
voter count. Placement interprets the policy; membership only decodes and authorizes it.

## 9. Persistence

Ops persist in the membership area of the node store (`../store/store-v1.md`, `MembershipStore`), which keeps each
envelope verbatim by op id with its issuer, kind and parents, and does not interpret it. Storing the same op again is a
no-op. A node stores an op when it joins the DAG (pending ops are not stored), stores every op one insert completes in one
transaction, and re-verifies every op it loads, including that the envelope's op id is the id it is stored under. Storing
an op and storing the evaluation that follows from it SHOULD happen in one transaction.

## 10. Differences from the C1 design spec

| # | Point | C1 | Here, and why |
|---|---|---|---|
| 1 | Exclusion cascade | `Sp(X)` is `S(past(X))`, so an op whose issuer was admitted by an excluded op still counts | Non-revoke ops are authorized against `S_E(past(X))`, which applies E's revokes. C1's fixture plan lists the cascade case; only this rule makes the cascade exclusion hold |
| 2 | Re-adding a rotated key | add requires "not active and not tombstoned" | add requires that the subject was never a member. A rotated key is retired for good, like a revoked one |
| 3 | Locked | "no further membership op is authorized" | Made explicit for every kind, including rotate |
| 4 | `per_agent` | a map keyed by agent id | BCBOR map keys are uints, so it is an array of `[agent_id, override]` pairs |
| 5 | Quorum voters | "each holding store + node.replicate" | Checked at authorization against `Sp(policy_set)` |
| 6 | Root chain | successor by lowest op id | Also skips moves back to a root already on the chain, so the walk always ends; superseded = every named root other than the current one |
| 7 | Concurrent rotates of one key | unspecified | Both new keys become active; `rotated(to)` names the first in the linear order |
| 8 | Pending limit | 1,024 per peer | 1,024 per log in the state machine; per-peer accounting is the driver's |
| 9 | Fixture location and generator | `docs/reference/contracts/c1/`, a core-testkit binary | Beside this spec; the ops vectors are generated by a test of the implementing crate (`BAKEN_REGEN_FIXTURES=1`), which fails when the checked-in file is stale |
