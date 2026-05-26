# CLI App Parity Matrix

Tracked by `letta-mobile-7h70v`. This matrix maps Android app API surfaces to
headless CLI coverage so future app routes do not silently drift past the CLI.

Status legend:

- `typed`: first-class CLI command maps to the same app route.
- `multipart`: first-class CLI command maps to a multipart app route.
- `setup`: covered by declarative `setup apply` / `setup export`.
- `diagnostic`: covered by transport/timeline diagnostic commands.
- `generic`: covered by `rest <verb>` escape hatch but should get typed coverage
  if it becomes a common workflow.

| App API | App routes | CLI coverage | Status |
| --- | --- | --- | --- |
| `AgentApi` | `GET/POST /v1/agents`, `GET/PATCH/DELETE /v1/agents/{id}`, `GET /context`, `GET /count`, `GET /export`, `PATCH /archives/attach|detach/{id}` | `agents list/get/create/update/delete/context/count/export/attach-archive/detach-archive` | typed |
| `AgentApi.importAgent` | `POST /v1/agents/import` multipart | `agents import --file ...` | multipart |
| `ArchiveApi` | `GET/POST /v1/archives/`, `GET/PATCH/DELETE /v1/archives/{id}`, `GET /agents`, `DELETE /passages/{id}` | `archives list/get/create/update/delete/agents/delete-passage` | typed/setup |
| `BlockApi` | Agent core-memory block routes, `/v1/blocks` CRUD/count/agents, block identity attach/detach | `agents blocks/get-block/update-block/attach-block/detach-block`, `blocks list/get/count/create/update/delete/agents/attach-identity/detach-identity` | typed/setup |
| `ConversationApi` | `/v1/conversations` CRUD, `fork`, `cancel`, `recompile` | `conversations list/get/create/update/delete/fork/cancel/recompile` | typed |
| `MessageApi` | Conversation/agent messages, ambient stream, search, batches | `agents messages/send-message/reset-messages/cancel-messages`, `conversations messages/send-message/stream`, `messages search`, `message-batches list/create/get/messages/cancel` | typed/diagnostic |
| `FolderApi` | `/v1/folders` CRUD/count/metadata, agents/passages/files, file delete | `folders list/get/count/metadata/create/update/delete/agents/passages/files/delete-file` | typed/setup |
| `FolderApi.uploadFileToFolder` | `POST /v1/folders/{id}/upload` multipart | `folders upload <folder-id> --file ...` | multipart |
| `GroupApi` | `/v1/groups` CRUD/count, messages, stream, reset | `groups list/get/count/create/update/delete/send-message/stream-message/update-message/messages/reset-messages` | typed |
| `IdentityApi` | `/v1/identities` CRUD/count/upsert/properties/agents/blocks and agent attach/detach | `identities list/get/count/create/upsert/update/delete/set-properties/agents/blocks/attach-agent/detach-agent` | typed/setup |
| `McpServerApi` | `/v1/mcp-servers` CRUD/tools/refresh/run-tool | `mcp list/get/create/update/delete/tools/refresh/run-tool` | typed/setup |
| `ModelApi` | `/v1/models`, `/v1/models/embedding` | `models list/embedding` | typed |
| `PassageApi` | Agent archival memory list/create/delete | `passages list/create/delete` | typed |
| `ProviderApi` | `/v1/providers` CRUD/check | `providers list/get/create/update/check/check-one/delete` | typed/setup |
| `RunApi` | `/v1/runs` list/get/messages/usage/metrics/steps/delete, agent cancel | `runs list/get/messages/usage/metrics/steps/cancel-agent-messages/delete` | typed |
| `JobApi` | `/v1/jobs` list/get/cancel/delete | `jobs list/get/cancel/delete` | typed |
| `StepApi` | `/v1/steps` list/get/metrics/trace/messages/feedback | `steps list/get/metrics/trace/messages/feedback` | typed |
| `ScheduleApi` | Agent schedule list/get/create/delete | `schedules list/get/create/delete` and setup `resources.schedules` | typed/setup |
| `ToolApi` | `/v1/tools` CRUD/count/upsert/schema, agent attach/detach | `tools list/get/count/create/upsert/update/generate-schema/delete/attach-to-agent/detach-from-agent` | typed/setup |
| `ProjectApi` | Project probe/list/get/beads remote/provision/sync/create/update/archive/delete | `projects probe/list/get/beads-remote/provision-beads-remote/sync-trigger/create/update/archive/delete` | typed/setup |
| `ProjectAgentApi` | `GET /api/agents/lookup` | `project-agents lookup --query repo=...` | typed |
| `ProjectWorkApi` | Ready work, issues, analytics, issue detail, claim/unclaim/status/note/close/reopen | `project-work ready/issues/analytics/issue/claim/unclaim/status/note/close/reopen` | typed |
| `VibesyncDebugApi` | `/health`, `/api/stats` | `debug health/stats` | typed |
| `VibesyncAdminApi` | `POST /api/admin/agents-md/refresh` | `vibesync-admin refresh-agents-md` | typed |
| `CloudConnectionValidator` | `GET /v1/agents?limit=1` probe | `agents list --query limit=1`, `setup export --skip-errors` | typed |
| `WsChatBridge` / timeline reducer | Admin-shim WS, record/replay/timeline paths | `connect/send/record/replay/dump-timeline/disconnect/reconnect` | diagnostic |
| Arbitrary backend route | Any route not listed here | `rest get/post/put/patch/delete` | generic |

## Guardrails

- `ResourceCommandsTest.resource registry includes app admin surfaces` fails if
  a core command group disappears.
- `CliSetupTest` verifies JSON and YAML setup files round-trip through the
  declarative model.
- Smoke commands used for this parity pass:
  - `agents --help`
  - `agents get --help`
  - `folders upload --help`
  - `projects sync-trigger --help`
  - `setup apply --file <json> --dry-run`
  - `setup apply --file <yaml> --dry-run`
  - `setup export --profiles-only`

## Unsupported Surfaces

No app REST repository is intentionally unsupported as of this pass. New app
routes should update this matrix, add a typed CLI command or document why `rest`
is sufficient, and extend focused CLI tests before merging.
