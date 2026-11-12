# Project registry v1: host-owned project record, project file and read methods

Status: **PROPOSED for review** (bead letta-mobile-m0xhd, stage S0). This stage is the contract and
its fixtures only. No host or client code exists, no admin RPC route is registered, no capability is
added and no peer grant changes. Freezing needs the owner's approval; after freezing, a change needs
a v2 file, not an edit here. The questions still open are in §8.

Today the project record lives in vibesync: `project.list`, `project.get`, `project.create` and the
rest of `project.*` are a proxy from the host to vibesync's HTTP API. This contract describes the
record once the host owns it, the file that gives a project its identity, and the two read methods
an engine such as vibesync uses in place of its own registry. Writes are a later stage (§7).

- Fixtures: [`android-compose/sharedLogic/src/commonTest/resources/project-registry-v1/`](../../../android-compose/sharedLogic/src/commonTest/resources/project-registry-v1/),
  parsed and checked against this document by `ProjectRegistryV1FixturesTest` (`:sharedLogic:jvmTest`).
- Field names are camelCase. A missing optional field is absent: never `null`, never an empty string.
- Readers ignore fields they do not know.

## 0. Terms

- **Host:** the Iroh wrapper process that serves admin RPC for one machine's Letta backend. It owns
  the registry and validates everything below.
- **Project:** one unit of work with one identity (`projectId`) and at most one PM agent on a host.
- **Checkout:** one folder on the host holding a project's repository. A project may have several.
- **Workspace root:** a folder the host is told to look in, declared per machine by name (for
  example `stacks`). A root's name is stable; its path is the machine's own business.
- **Engine:** a peer that runs work for projects (vibesync). It reads this registry; it does not
  write it.
- **Project file:** `.meridian/project.json`, committed at the root of the project's repository.

## 1. Project file

Path: `.meridian/project.json` at the repository root. It is committed, so a clone on another
machine carries the same identity.

| Field | Type | Required | Rule |
| --- | --- | --- | --- |
| `schemaVersion` | integer | yes | `1` |
| `projectId` | string | yes | lower-case UUID, 36 characters; generated once, never reused |
| `name` | string | yes | 1 to 120 characters of plain text |
| `engine` | object | no | engine settings, below |

`engine` fields, all optional:

| Field | Type | Rule |
| --- | --- | --- |
| `formula` | string | identifier: `[a-z0-9][a-z0-9-]{0,63}` |
| `readyLabel` | string | label: `[a-z0-9][a-z0-9:_-]{0,63}` |
| `poolSize` | integer | 1 to 8 |
| `ciChecks` | array of string | at most 16 entries, each 1 to 100 characters |

Rules:

- **F1** The file holds no path and no agent id. Paths differ per machine. Agent ids belong to one
  Letta backend, so a clone elsewhere must not inherit them. The agent mapping lives in the host
  registry (§2).
- **F2** The file is strict JSON of at most 16 KiB. A file that is larger, does not parse, repeats a
  key, lacks a required field or breaks a rule above makes the project `invalid` (§2) with a reason;
  the host never repairs it, half-applies it or stops because of it.
- **F3** A `schemaVersion` above `1` makes the project `invalid` with `unsupported_schema_version`.
- **F4** The host keeps keys it does not know when it rewrites the file, and ignores them when it
  reads.
- **F5** The host reads `engine` from the default branch of a confirmed project only. A branch under
  review, or a repository the host has not confirmed, changes nothing.
- **F6** The host checks `formula` against the grammar only. Whether a formula of that name exists is
  the engine's check.
- **F7** The file is data. Nothing in it is executed.

## 2. Project record

The host holds one record per project. This is what the read methods return.

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `projectId` | string | yes | as in the project file |
| `name` | string | yes | as in the project file; for an unconfirmed project, the folder name |
| `state` | string | yes | `active`, `unconfirmed`, `archived` or `invalid` |
| `invalidReason` | string | only when `invalid` | one of the reasons below |
| `repository` | object | no | `{ "slug": "owner/name" }`, present when the origin remote names one |
| `checkouts` | array | yes | this host's checkouts of the project; may be empty |
| `pmAgentId` | string | no | the project's PM agent on this host |
| `roleAgents` | object | yes | role name to agent id; may be empty |
| `providerKind` | string | no | how an engine reaches the agents, for example `letta-code-iroh` |
| `engine` | object | yes | below |
| `revision` | string | yes | opaque; changes whenever this record changes |

A checkout:

| Field | Type | Meaning |
| --- | --- | --- |
| `root` | string | the workspace root's name |
| `relativePath` | string | the folder below that root |
| `path` | string | the absolute path on the answering host |
| `primary` | boolean | the checkout an engine works in |

`engine` in a record:

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `enabled` | boolean | yes | whether an engine may run work for this project |
| `formula`, `readyLabel`, `poolSize` | as in §1 | no | the validated values from the project file |
| `ciChecks` | array of string | yes | the validated value from the project file, or empty |

States:

- `active`: confirmed, with a valid project file.
- `unconfirmed`: a folder the host found whose repository is not ours, or which nobody has confirmed.
  The host has written nothing into it.
- `archived`: kept for history; no engine work.
- `invalid`: the project file breaks §1. `invalidReason` is one of `file_too_large`, `not_json`,
  `duplicate_key`, `missing_field`, `field_out_of_range`, `unsupported_schema_version`.

Rules:

- **R1** `path` is the only place a path appears, and it is meaningful only on the answering host.
- **R2** When `checkouts` is not empty, exactly one checkout has `primary: true`. An engine uses that
  one. The host gives `primary` to the first checkout it registers and moves it only on an explicit
  update, or to the oldest remaining checkout when the primary one is removed.
- **R3** The same `projectId` in two folders is one project with two checkouts and one PM.
- **R4** An `unconfirmed` record has no `pmAgentId`, an empty `roleAgents`, `engine.enabled: false`
  and no engine settings.
- **R5** `engine.enabled` is `true` only for an `active` project.
- **R6** Role names are lower-case identifiers (`coder`, `reviewer`, `tester`, `mayor`). A role the
  project has no agent for is absent.
- **R7** `repository.slug` is `owner/name`. It is absent when the origin remote does not name one.

## 3. Read methods

Both are admin RPC methods. Neither changes anything on the host, so a caller may retry them.

### `project.registry.list`

Params, all optional:

| Field | Type | Rule |
| --- | --- | --- |
| `engineEnabled` | boolean | return only records whose `engine.enabled` equals it |
| `limit` | integer | 1 to 100; default 50 |
| `cursor` | string | from a previous page's `nextCursor` |
| `ifRevision` | string | the registry revision the caller already holds |

Result:

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `revision` | string | yes | the registry revision this answer was read at |
| `notModified` | boolean | no | `true` when `ifRevision` equals `revision`; then `items` is absent |
| `items` | array of record | unless `notModified` | at most `limit` records, ordered by `projectId` |
| `nextCursor` | string | no | present when more records follow |

- **L1** Records are ordered by `projectId`, ascending. The host never returns the whole registry in
  one answer.
- **L2** A cursor belongs to the revision it was issued at. When the registry has changed since, the
  host answers `cursor_stale` and the caller starts again without a cursor.
- **L3** `ifRevision` and `cursor` together are refused (`invalid_request`, problem
  `if_revision_with_cursor`).

### `project.registry.get`

Params: `{ "projectId": <id> }`. Result: one record (§2).

### Errors

A refusal keeps the admin RPC envelope: `{"success": false, "error": <code>}`, plus
`"problem": <problem>` for `invalid_request`.

| Code | Meaning | Methods |
| --- | --- | --- |
| `invalid_request` | the params break a rule; `problem` says which | both |
| `cursor_stale` | the registry changed since the cursor was issued | `project.registry.list` |
| `unknown_project` | no record has that `projectId` | `project.registry.get` |

Problems for `invalid_request`: `limit_out_of_range`, `cursor_malformed`, `if_revision_with_cursor`,
`project_id_malformed`.

A caller without the capability gets the router's usual authorization refusal, which this document
does not define.

## 4. Capability

- **C1** Both methods need `projects.read`, a new capability. The stage that registers the methods
  adds them to an explicit read set that is matched before the existing `project.*` rule, which maps
  every other project method to `projects.manage`.
- **C2** `admin.full` satisfies `projects.read`, as it satisfies every capability today.
- **C3** `projects.manage` satisfies `projects.read`: a peer that may change projects may read them.
- **C4** `projects.read` allows nothing else. A peer holding only `projects.read` cannot create,
  update, archive or delete a project.
- **C5** The methods take no agent id, so a peer's agent scope does not filter the answer. A record
  names agents; it grants no access to them.

## 5. Change detection

- **P1** The registry revision changes whenever a record is added, removed or changed.
- **P2** A reader polls `project.registry.list` with `ifRevision`. An unchanged registry answers
  `notModified` and nothing else.
- **P3** A reader should poll no more often than once every 15 seconds. There is no push channel in
  v1.

## 6. Reader rules

- **X1** Take engine settings from the record, not from the project file. The host is the one
  validator.
- **X2** Work in the `primary` checkout. A record with no checkout cannot be worked on.
- **X3** Treat `unconfirmed`, `archived` and `invalid` records, and any record with
  `engine.enabled: false`, as not runnable.
- **X4** Ignore unknown fields and unknown `state` values; an unknown state is not runnable.

## 7. Not in v1

- Writes. `project.create`, `project.update`, `project.archive` and `project.delete` stay as they are
  until the stage that moves them to the host.
- The create operation, the folder watcher and the workspace-root declaration format.
- Creating role agents, and deriving a peer's agent scope from the registry.
- A push channel, and listing another host's projects.

## 8. Open for review

1. **C3**: should `projects.manage` satisfy `projects.read`? Without it, a paired desktop that can
   change projects today could not read the registry.
2. **C5**: is it acceptable that a peer with a narrow agent scope sees every project's agent ids?
   The alternative is to drop records whose agents are outside the caller's scope.
3. **poolSize**: is 8 the right upper bound?
4. **F6**: the host cannot know an engine's formula names, so it checks the grammar only. An earlier
   draft had the host check the name itself.
5. **P3**: is 15 seconds the right polling floor?

## 9. Fixtures

| File | Shows |
| --- | --- |
| `project-file.json` | a project file with every field (§1) |
| `project-file-minimal.json` | a project file with only the required fields (§1) |
| `record-active.json` | an active record with two checkouts and three role agents (§2) |
| `record-unconfirmed.json` | an unconfirmed record (§2, R4) |
| `record-invalid.json` | an invalid record with its reason (§2) |
| `project.registry.list.json` | list cases: first page, next page, filter, not modified and refusals (§3) |
| `project.registry.get.json` | get cases: found, unknown and malformed (§3) |
