# Keystore v1: provider contract

Status: **PROPOSED for review; contract-and-fixture stage of letta-mobile-09ulk.** This stage
supplies the provider SPI (`:plugin-api`, packages `com.letta.mobile.plugin.keystore` and
`com.letta.mobile.plugin.keystore.wire`), the wire DTOs, shared wire fixtures and a conformance
kit (`:plugin-api-testkit`, package `com.letta.mobile.plugin.testkit.keystore`). It supplies no
host keystore service, routing, grant enforcement, audit writer, process launcher, real provider,
agent integration or UI. Sections marked *host* state what a later stage must enforce; nothing in
this stage enforces them.

A keystore provider is a module that stores secrets in a backend of its author's choice (an OS
keychain, a password manager, a vault server, an encrypted file). The host talks to every provider
through one SPI, in process (Kotlin) or out of process (JSON-RPC over stdio, any language).

## Invariants

These hold for every provider, host and transport. Each is a test target.

1. **I1 No value in a sink.** A secret value never enters a conversation, prompt, tool result,
   log line, telemetry event, crash report, exception message, `toString`, process argument list
   or environment variable. It travels only inside `SecretValue` (in process) and inside the
   `value`/`material` fields of the wire frames named below (out of process).
2. **I2 Names are not values.** A `SecretRef`, a listing, an error, a health report and an audit
   record carry names, codes and outcomes only. A provider never derives a name from a value.
3. **I3 Fail closed.** A missing, locked, unhealthy or unreachable provider answers a named error
   (below). No operation ever answers success with an empty, default or placeholder value.
   An empty value is not a valid secret: `put` refuses it and `get` never returns it.
4. **I4 No credentials in configuration.** Provider settings hold no plaintext credential.
   Unlock material is host-owned and reaches the provider only through `unlock`.
5. **I5 Grants live on the host.** *Host:* which principal may touch which refs is decided and
   enforced by the host before a provider is called. A client or agent cannot assert a grant.

## SecretRef

A `SecretRef` names one secret slot; it is never the value.

| Field | Grammar | Meaning |
| --- | --- | --- |
| `provider` | `[a-z0-9]+([.-][a-z0-9]+)*`, at most 64 characters | The provider id (`KeystoreDescriptor.id`) |
| `path` | 1 to 16 segments joined by `/`; a segment is `[A-Za-z0-9_.-]+` and is not `.` or `..`; at most 256 characters | The item inside the provider; no leading, trailing or doubled `/` |
| `field` | `[A-Za-z0-9_.-]{1,64}`, not `.` or `..`; optional | A named slot of the item; absent means the item's default slot |

- **R1** Two refs are equal exactly when all three components are equal as strings, with an absent
  field equal only to an absent field. No case folding, Unicode normalization, trimming or
  percent-decoding is applied, by either side.
- **R2** The text form is `<provider>:<path>` plus `#<field>` when a field is present
  (`example.vault:team/api/openai#token`); a scope's is `<provider>:` plus `<prefix>/` when a
  prefix is present. Text forms are for messages and audit only; the wire carries the object form.
- **R3** A ref whose components break the grammar is *malformed*. A provider answers
  `invalid_ref` (`malformed`) for it on every operation; it never normalizes it into a valid one.
- **R4** A ref naming another provider is answered `invalid_ref` (`provider_mismatch`).
- **R5** How a provider maps `path` and `field` to its backend (vault item, folder, keychain
  service/account) is the provider's own and must be documented by its author. The default slot
  (absent field) and each named field are independent slots.

A `SecretScope` is `{provider, prefix?}`. A ref is in scope when its provider matches and the
prefix is absent, equals its path, or is a whole-segment prefix of it (`team/api` covers
`team/api/openai`, not `team/apis`).

## SecretValue

`SecretValue` is the only in-process carrier of secret bytes.

- **V1** Its `toString()` is the fixed text `SecretValue(redacted)` whatever it holds, including
  its length.
- **V2** It is not a data class, not `java.io.Serializable` and has no kotlinx serializer of its
  own. Only the wire package encodes it, and only in the value-carrying fields named below.
- **V3** Equality and hash are identity. `contentEqualsConstantTime` compares contents in time
  independent of where they differ.
- **V4** The bytes are readable only through `revealSecretBytes { bytes -> ... }`, which lends a
  copy that is zeroed when the block returns or throws. Callers must not keep or copy the array.
- **V5** `wipe()` zeroes the bytes; later reveals throw `IllegalStateException` (a programming
  error, not an expected failure). Factories copy their input; the caller wipes its own array.
- **V6** A value holds 1 to 65 536 bytes (`KeystoreSpi.MAX_VALUE_BYTES`). Text secrets are UTF-8.

## Provider SPI

`KeystoreProvider` (Kotlin, in process) and the `keystore.*` wire methods (out of process) are the
same contract. Expected failures are results (`KeystoreResult.Failure`), never exceptions.

| Operation | Needs capability | Success | Semantics |
| --- | --- | --- | --- |
| `describe()` | - | `KeystoreDescriptor` | Static: same answer every call, no I/O, works locked or unhealthy |
| `health()` | - | `KeystoreHealth` | `ready`, `locked` or `unavailable`, with optional diagnostic |
| `unlock(material)` | `needsUnlock` | nothing | Opens the backend with host-owned material; idempotent when already open |
| `lock()` | `needsUnlock` | nothing | Drops any unlocked state; idempotent |
| `get(ref)` | `read` | `SecretValue` | The slot's current value; a fresh `SecretValue` per call |
| `put(ref, value, options)` | `write` | nothing | Writes the slot; `options` is `{mode, metadata?}` |
| `list(scope)` | `list` | `SecretListing` | Item names in scope, never values |
| `delete(ref)` | `delete` | nothing | Removes the item and all its slots; `ref.field` must be absent |

- **S1** `KeystoreDescriptor` is `{id, capabilities, needsUnlock}`. `capabilities` is a subset of
  `read`, `write`, `list`, `delete`. A host ignores capability names it does not know.
- **S2** An operation whose capability is not declared answers `unsupported` (with the
  operation). `unlock` and `lock` answer `unsupported` when `needsUnlock` is false.
- **S3** Check order, first match wins: `unsupported`; `invalid_ref`; `invalid_value`;
  `unavailable`; `locked`; then backend outcomes (`denied`, `not_found`, `conflict`, `internal`).
  Validation never needs the backend.
- **S4** `put` modes: `create` answers `conflict` when the slot exists; `replace` answers
  `not_found` when it does not; `upsert` (default) writes either way. `put` with an empty value
  answers `invalid_value` (`empty`); over 65 536 bytes, `invalid_value` (`too_large`).
- **S5** `metadata` is `{description?}`, at most 256 characters, not secret. A provider may store
  or ignore it; it is never returned by `get` in v1. It must never hold a value.
- **S6** `get` of a missing slot answers `not_found` naming the same ref. A provider never returns
  an empty value (I3).
- **S7** `delete` with a field answers `invalid_ref` (`field_not_allowed`). Deleting a missing item
  answers `not_found`; a successful delete makes every slot of the item `not_found`.
- **S8** `list` answers `{entries, truncated}`: distinct item refs with no field, all in scope,
  sorted by path in code-point order, at most 1 000 (`KeystoreSpi.MAX_LIST_ENTRIES`);
  `truncated` is true when more exist. `list` with a ref-level field is impossible by type.
- **S9** A provider with `needsUnlock` starts `locked`. While locked, `get`/`put`/`list`/`delete`
  answer `locked`. `unlock` with wrong material answers `denied` (operation `unlock`).
- **S10** While `health()` would not answer `ready`, data operations answer `unavailable` or
  `locked`, never success (I3). Provider ids, capability sets and descriptors never change while a
  provider instance lives.
- **S11** Diagnostics (`health.detail`, `unavailable.detail`, `internal.detail`) are at most 200
  characters of operator text and never contain a value. *Host:* it drops a diagnostic that
  contains any value it knows.

## Errors

Every failure is one of these codes. Codes are stable wire strings; new codes need a new major.
Messages are fixed templates built from the code and the structured fields (names only), so a
provider cannot put free text, let alone a value, into one.

| Code | Fields | Meaning |
| --- | --- | --- |
| `not_found` | `ref` | The slot or item does not exist |
| `denied` | `operation` | The backend refused (its own ACL), or `unlock` material was wrong. *Host:* also a grant refusal |
| `unavailable` | `detail?` | The provider or backend is unreachable, unhealthy, not running or timed out |
| `locked` | - | The provider needs `unlock` first |
| `unsupported` | `operation` | The capability is not declared |
| `invalid_ref` | `refProblem` | `malformed`, `provider_mismatch` or `field_not_allowed` |
| `invalid_value` | `valueProblem` | `empty` or `too_large` |
| `conflict` | `ref` | `put` with mode `create` found an existing slot |
| `internal` | `detail?` | The provider failed unexpectedly |

- **E1** A failure never carries a value, a fragment of one or a backend response body.
- **E2** *Host:* a missing provider, a crashed process, a missed deadline and an unparseable answer
  all surface as `unavailable`. A host never substitutes an empty string or a cached value.
- **E3** *Host:* a grant refusal is `denied` and is indistinguishable from a refusal of a ref that
  does not exist (no existence oracle).

## Grant model (host)

Specified here so providers and hosts agree on terms; enforced by the host service stage.

- **G1** A grant is `{principal, provider, prefix?, operations}`, where `operations` is a subset of
  `read`, `write`, `list`, `delete` and the prefix follows `SecretScope` rules. Grants are stored
  on the host and changed only by the owner.
- **G2** Default deny: an operation is allowed only when one grant names the principal, the
  provider, an operation covering it and a scope containing the ref. `list` needs a grant whose
  scope contains the requested scope.
- **G3** Principals are `owner` (the human, through host UI), `agent:<agentId>` and `host`
  (internal maintenance). The principal comes from the host's authenticated session, never from
  request parameters or tool arguments.
- **G4** `describe`, `health`, `unlock` and `lock` are host operations: never granted to an agent.
- **G5** An agent never receives a value through a tool result. Delivery to a process that needs
  one (for example as an HTTP header injected by the host) is a later stage's decision.

## Audit record (host)

One record per operation attempt, written by the host after the outcome is known:

| Field | Value |
| --- | --- |
| `at` | Epoch milliseconds |
| `principal` | `owner`, `agent:<agentId>` or `host` |
| `operation` | `get`, `put`, `list`, `delete`, `unlock`, `lock` or `health` |
| `provider` | Provider id |
| `target` | Ref text form (R2), scope text form, or absent |
| `outcome` | `ok` or an error code |
| `entries` | For `list` only: the entry count |

- **A1** An audit record never holds a value, unlock material, metadata description or diagnostic.
- **A2** Denied attempts are recorded like any other outcome.

## Wire form (out of process)

A process provider speaks the LCP process framing of
[canvas-plugin-wire-v1.md](../canvas-plugin-wire-v1.md): JSON-RPC 2.0, one UTF-8 message per
`\n`-terminated line, host to provider on stdin and provider to host on stdout, nothing else on
stdout, params always an object, no batches, at most 4 MiB per message, `$/cancel` and its codes.
All `keystore.*` methods are host-to-provider requests; v1 has no provider-to-host methods.

| Method | Params | Result | Deadline |
| --- | --- | --- | --- |
| `keystore.initialize` | `{contractVersions, host: {name, version}, settings}` | `{contractVersion}` | 10 s |
| `keystore.describe` | `{}` | `{id, capabilities, needsUnlock}` | 5 s |
| `keystore.health` | `{}` | `{status, detail?}` | 5 s |
| `keystore.unlock` | `{material}` | `{}` | 30 s |
| `keystore.lock` | `{}` | `{}` | 10 s |
| `keystore.get` | `{ref}` | `{value}` | 30 s |
| `keystore.put` | `{ref, value, mode, metadata?}` | `{}` | 30 s |
| `keystore.list` | `{scope: {provider, prefix?}}` | `{entries, truncated}` | 30 s |
| `keystore.delete` | `{ref}` | `{}` | 30 s |
| `keystore.shutdown` | `{}` | `{}`, then the provider exits | 10 s |

- **W1** A ref is `{provider, path, field?}`; optional fields are omitted when absent, never `null`.
  Enum values are the lowercase codes in this document.
- **W2** `value` and `material` are standard padded base64 of the bytes. They appear only in
  `keystore.get` results, `keystore.put` params and `keystore.unlock` params.
- **W3** A keystore failure is the JSON-RPC error
  `{"code": -32020, "message": <template>, "data": {"code": <error code>, ...fields}}` with the
  fields of the Errors table. Receivers act on `data.code`, never on `message`. Protocol failures
  keep the LCP codes (`-32700`, `-32600`, `-32601`, `-32602`, `-32603`, `-32800`, `-32001`,
  `-32002`, `-32003`, `-32004`, `-32005`, `-32008`, `-32009`).
- **W4** Handshake: `keystore.initialize` must come first (others answer `-32002`). The host offers
  every major it speaks in `contractVersions`; the provider answers the one it chose, or `-32004`
  with `data.supported`. `settings` is the provider's non-secret configuration object (I4).
- **W5** Receivers ignore unknown object keys (minor, additive changes); unknown enum values and
  missing required keys answer `-32602`.
- **W6** *Host:* the process is started with a fixed argument list and an environment that holds
  no value or material. The host never logs the params or result of `keystore.get`,
  `keystore.put` or `keystore.unlock`; it may log method, id, outcome code and duration. stderr is
  captured as scrubbed telemetry; a provider must never write a value to stderr.
- **W7** *Host:* a provider exit, a closed pipe or a missed deadline fails every pending call with
  `unavailable` (E2).

The normative examples are the transcripts in
[`android-compose/plugin-api/src/jvmTest/resources/contracts/keystore/v1/`](../../../android-compose/plugin-api/src/jvmTest/resources/contracts/keystore/v1/),
one per method plus `errors.jsonl`, in the LCP transcript form
`{"dir": "host->provider" | "provider->host", "message": {...}}`. Their values are sentinels.
`KeystoreWireFixturesTest` (`:plugin-api:jvmTest`) decodes every line into the typed DTOs,
re-encodes it to an equal JSON value, and checks no decoded DTO prints a value.

## Versioning

`KeystoreSpi.CONTRACT_VERSION` is the contract major (1). A minor adds optional fields, methods a
provider may answer `-32601`, or SPI members with defaults; a major changes codes, required
fields or semantics. In process, a provider is built against a `:plugin-api` whose
`KeystoreSpi.CONTRACT_VERSION` the host supports.

## Conformance

`KeystoreConformance.run(fixture)` in `:plugin-api-testkit` checks every statement above that is
observable through the SPI: descriptor stability, round trip, slot independence, fresh values,
write modes, not found, delete, list shape and scope, capability refusals and check order, invalid
refs and values, locking, fail closed (when the fixture can take its backend down), no throws,
and a sentinel scan of every string the kit can capture (results, errors, messages, descriptors,
diagnostics, listings, exceptions and the fixture's captured provider output). Host statements
(*host*, G, A, W6, W7) and leaks to sinks the kit cannot observe (a provider's own files or
network) are out of its reach.

## Out of scope for v1

Rotation (no `rotate` capability or method), versions and history of a value, metadata reads,
field enumeration, list pagination beyond `truncated`, sync between providers, sharing between
owners, provider-to-host calls, service (WebSocket) providers, and how unlock material is
obtained or stored by the host.

## Open decisions

Each was settled conservatively for v1 and is listed for the owner:

1. **Rotation deferred.** `rotate` is not a v1 capability; a provider that rotates does so out of
   band. Adding it is a minor (new capability and method).
2. **Refs are provider-scoped paths**, not host-assigned names. A host alias layer (stable names
   that map to refs) can be added above the SPI without changing providers.
3. **Unlock is in the SPI** (`unlock`/`lock` with host-owned material) so locked backends work out
   of process without env or argv; where the host keeps material is not decided.
4. **Delete is item-level only**; deleting a single field is not in v1.
5. **List is bounded, not paged** (1 000 entries plus `truncated`); a cursor is a later minor.
6. **Values are bytes** (UTF-8 for text) with a 64 KiB cap, and an empty value is invalid.
7. **No provider-to-host channel**, so no provider logging over the wire; stderr only, scrubbed.
8. **`:plugin-api` version** is not bumped by this stage; the artifact's minor bump belongs to the
   first published release that carries this package.
