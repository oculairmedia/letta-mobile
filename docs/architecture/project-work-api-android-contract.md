# Android Project Work API Contract

**Status:** Historical external-service contract; production Android Project Work route is absent after the REST hard cut.

The endpoints below describe the intended Vibe Sync / Beads Work service contract, not a currently wired Android HTTP client. [ProjectWorkRepository](../../android-compose/core/android-data/src/main/java/com/letta/mobile/data/repository/ProjectWorkRepository.kt) defaults to `RemoteRoute.Absent(SessionRoute.PROJECT_WORK)` and states that no App Server backend serves this surface yet. Shared [CachedProjectWorkRepository](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/CachedProjectWorkRepository.kt) retains caching/mutation logic over an injected `ProjectWorkRemoteSource`; fake-source tests exercise that seam. Existing DTOs and tests do not establish live endpoint availability.

The Iroh wrapper's `project.*` catalog is a separate bounded VibeSync integration; it does not wire the absent Project Work issue-mutation source. The ETag/idempotency/service guarantees below are the original external contract and were not live-verified in this audit.

## Canonical endpoints

- `GET /api/projects` — mobile project catalog with repo metadata, tracker capabilities, tracker summary, freshness, version, and ETag.
- `GET /api/projects/{projectId}` — compact project detail.
- `GET /api/projects/{projectId}/ready-work` — Android equivalent of `bd ready`; use this for "what should I work on?".
- `GET /api/projects/{projectId}/issues` — compact issue summaries with status/filter/search/pagination query parameters.
- `GET /api/issues/{issueId}` — full issue detail by stable Beads issue ID.
- `POST /api/issues/{issueId}/claim`
- `POST /api/issues/{issueId}/unclaim`
- `PATCH /api/issues/{issueId}/status`
- `POST /api/issues/{issueId}/notes`
- `POST /api/issues/{issueId}/close`
- `POST /api/issues/{issueId}/reopen`

`/api/registry/projects` remains a legacy/admin registration surface. Android workspace listing should use `/api/projects`.

## Identity and schema rules

- Issue IDs are opaque stable Beads IDs such as `letta-mobile-qmbg`; Android must not parse semantic meaning from them.
- Work payloads are deterministic JSON with `schema_version = 1`.
- Android logic should use normalized `status`; `statusLabel` is display/debug information from the backing tracker.
- Project freshness is represented by `tracker.data_freshness`; issue list freshness is represented by response-level `data_freshness`.

## Mutation rules

All issue mutations must send:

- `If-Match: {issue.etag}`
- `Idempotency-Key: {stable client-generated key}`

Idempotency keys are durable and scoped by `(issue id, action, idempotency key)`. Replaying the same queued mutation with the same key returns the cached mutation result and does not invoke `bd` again. Replayed responses use `applied: false` and `idempotent_replay: true`.

On stale ETags, the API returns `409` with a structured conflict payload. Android should refetch `GET /api/issues/{issueId}`, rebase or discard the queued mutation, then retry only if the user action is still valid.

## Delete/tombstone behavior

Explicit tombstone records are not part of v1 incremental sync. Android must not rely on `updatedSince` to discover deleted issues yet. Treat missing or closed issues conservatively and use a full refresh when reconciling deletes.

## Android implementation files

The current Android data contract lives in:

- `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/model/Project.kt`
- `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/model/ProjectWork.kt`
- ~~`ProjectApi.kt`~~, ~~`ProjectWorkApi.kt`~~ — removed with the Letta REST backend in the hard cut (letta-mobile-ckmub); no REST client remains
- `android-compose/core/android-data/src/main/java/com/letta/mobile/data/repository/ProjectWorkRepository.kt`

Current focused coverage includes `ProjectWorkRepositoryTest` and `SessionScopedProjectWorkRepositoryTest` under [Android data tests](../../android-compose/core/android-data/src/test/java/com/letta/mobile/data/). The former REST API tests/classes named by the original design are removed.
