# Iroh Admin RPC Ownership Matrix (lgns8.13)

Date: 2026-07-22

Beads: `letta-mobile-lgns8.13`

## What this decides

Every admin_rpc method the Iroh node registers today (73 methods, all but three
pure proxies to the admin-shim on :8291) gets an explicit post-shim owner,
public contract, authorization class, data store, fallback, and migration
slice. The machine-readable decision is
`android-compose/sharedLogic/src/jvmTest/resources/appserver/iroh-admin-ownership-matrix.json`,
enforced by `IrohAdminOwnershipMatrixTest`, which:

- diffs the matrix against the real `AdminRpcRegistry.buildRouter(...)` method
  set, so registering a new admin_rpc method without an ownership decision
  fails CI;
- requires every `app_server_v2` row to cite only command/message discriminants
  present in the pinned `installed-protocol-v2-inventory.json` (lgns8.1);
- keeps unsupported native operations (`conversation_delete`,
  `skill_list_command`) and the epic-scoped unrouted domains (crons, search,
  channels, secrets, filesystem/terminal, MCP catalog) explicit;
- forbids any direct-Letta-storage data store.

## Ownership summary

| Owner | Count | Methods |
|---|---|---|
| `app_server_v2` | 21 | agent CRUD (5), conversation list/get/create/update/archive/restore (6), message.list/get + tool_return.get (3), model.list, skill.* (4), approval.submit |
| `admin_rest_service` (bounded injected adapters, lgns8.9) | 39 | agent.context, runs/steps (3), archives/folders/passages/groups (6), identities (2), model.list.embedding, provider.list, schedules/jobs (6), tools (7), blocks (8), mcp.list, goals (2), slash commands (2) |
| `vibesync_service` | 9 | project.* |
| `controller_native` | 3 | health.check, subagent.list, subagent.todos |
| `capability_gated_unsupported` | 1 | conversation.delete (native `conversation_delete` absent; typed denial after cutover, prefer archive) |

Slice mapping: `lgns8.7` runtime-native agent/conversation/message adoption
(18 methods); `lgns8.8` policy-gated control capabilities — model.list,
skills, health, the conversation.delete capability gate, plus native `cron_*`
exposure; `lgns8.9` bounded admin/VibeSync adapters (48 methods); `lgns8.11`
approval.submit shim-fallback removal at cutover.

## Load-bearing decisions

- **Native availability does not transfer ownership.** `app_server_v2` rows
  are explicit adoptions; everything else stays behind bounded adapters even
  where a similarly named native command exists (e.g. `provider.list` vs the
  app-server connect-provider domain; schedule CRUD vs native `cron_*`).
- **The controller never becomes admin-shim v2.** `admin_rest_service` and
  `vibesync_service` are injected services with their own contracts, not REST
  mirrors grown inside the fanout controller (per the lgns8.1 audit's
  must-not-own list).
- **Fail-closed gaps.** `conversation.delete` and the secrets domain deny with
  typed capability errors after cutover. Secrets (`secret_list`/`secret_apply`
  are `exposed_sensitive` upstream — plaintext values) are never reachable via
  generic admin_rpc without an explicit lgns8.12 policy.
- **Skill listings are projections.** `skill_list_command` is absent upstream;
  the controller derives `skill.list`/`skill.list_agent` from the `sync`
  snapshot and `skills_updated` events.
- **Crons leave the legacy WS path.** The Iroh transport currently stubs cron
  methods; lgns8.8 exposes native `cron_*` behind policy, and the legacy
  mobile-WS cron/subagent frames retire with the shim in lgns8.11.

## Scope revisions for .7/.8/.9

- **lgns8.7** implements exactly the 18 `lgns8.7`-tagged runtime-native rows
  (agent CRUD, conversation CRUD-minus-delete, message hydration projections),
  keeping controller-owned wire projection (`MessageListPageGuard`) and
  runtime-binding eviction on `agent.update`.
- **lgns8.8** gains: model.list via `list_models`, skill enable/disable plus
  sync-derived listings (with install-vs-enable semantic verification),
  controller-native health, the typed `conversation.delete` denial, and
  policy-gated native `cron_*` exposure.
- **lgns8.9** covers the 39 bounded admin adapter rows plus the 9 VibeSync
  project rows, an explicit decision on message search over Iroh, and either
  adapters or deliberate deprecation for the shim-era goal/slash-command
  routes. No adapter may bypass its public contract to reach Letta storage.
