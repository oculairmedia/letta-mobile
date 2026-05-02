# Letta Code-style Android host tools

**Date:** 2026-05-02  
**Bead:** `letta-mobile-ifd7.1`  
**Parent:** `letta-mobile-ifd7` — Build layered Android system access architecture

## Purpose

Letta Mobile should expose Android-local device actions using the same host-tool split that Letta Code uses for desktop coding tools: the agent may run on a Letta server, but tools that affect local state execute on the user's local host. In Letta Code the host is a Node/Bun CLI process; in Letta Mobile the host should be the Android app process.

This note maps the Letta Code architecture inspected in `/opt/stacks/letta-code` to the existing Kotlin classes in Letta Mobile, recommends a native-first Android implementation, and defines the first tool families and safety tiers.

## Core architecture insight

Letta Code demonstrates the correct split:

```text
Letta agent/server
  -> tool call / approval request
  -> local host process
  -> local tool registry dispatch
  -> platform implementation
  -> structured result returned to Letta
  -> agent continues
```

For Android this becomes:

```text
Letta agent/server
  -> tool call
  -> LocalBotSession on Android
  -> BotToolRegistry.execute()
  -> AndroidExecutionBridge / platform capability layer
  -> Android OS / SAF / Shizuku / root bridge / intents
  -> LettaRuntimeClient.submitToolResult()
  -> Letta agent continues
```

The important invariant is that the server-side agent never directly invokes Android OS APIs. Device-affecting work must be executed by local code that can check Android permissions, app flavor, user settings, and audit policy.

## Letta Code source concepts and Kotlin mapping

| Letta Code concept | Source | Behavior | Letta Mobile mapping | Notes |
|---|---|---|---|---|
| Tool registry | `src/tools/manager.ts` | Loads enabled tools into a singleton registry; resolves internal vs server-facing names; exposes loaded tools as client tools. | `BotToolRegistry` | Keep a single local registry, but model richer metadata than the current `BotToolDefinition`. |
| Schema assets | `src/tools/schemas/*.json` | Model-facing JSON input schemas. | Extend `BotToolDefinition` or add a `BotHostToolDefinition` with `parameters: JsonObject`. | Current local tool sync sends stub source plus description only. Android host tools should also persist canonical parameter schemas so the model receives required fields and types. |
| Markdown descriptions | `src/tools/descriptions/*.md` | Long model-facing instructions imported and trimmed into each tool definition. | Store concise descriptions in Kotlin initially; move long descriptions to assets/resources when they become substantial. | Android tools benefit from explicit user-mediated language: e.g. "opens a draft" vs "sends". |
| Implementation mapping | `src/tools/toolDefinitions.ts` | A definition joins schema + description + implementation function. | `BotToolRegistry.execute()` dispatching to `AndroidExecutionBridge`, storage tools, or future privileged adapters. | Prefer typed Kotlin methods over reflection/stringly APIs. |
| Toolset defaults | `ANTHROPIC_DEFAULT_TOOLS`, `OPENAI_DEFAULT_TOOLS`, `GEMINI_DEFAULT_TOOLS`, Pascal variants | Select model-specific tool names and schemas. | Skill/bot config-controlled tool exposure plus future toolset presets such as `safe_android_actions`, `storage_saf`, `root_shell`. | Android should use stable `android_*` tool names instead of provider-specific aliases unless a provider requires different schema conventions. |
| Name alias mapping | `TOOL_NAME_MAPPINGS`, `getServerToolName()`, `getInternalToolName()` | Maps internal implementations to model/server names. | Add an alias map only when needed for compatibility. | Keep first Android batch one-to-one to avoid confusion. Use aliases later for OpenAI/Gemini schema variants or legacy tool renames. |
| Permission metadata | `TOOL_PERMISSIONS` in `manager.ts` | Simple default `requiresApproval`. | `SystemAccessCapabilityRegistry` plus per-tool approval metadata. | Android needs more than a boolean: capability availability, data sensitivity, flavor, approval policy, and audit fields. |
| Permission checker | `src/permissions/checker.ts` | Deny rules, CLI overrides, permission mode, read-only shell auto-allow, working-directory read allow, session/persisted allow/ask rules, default behavior. | Capability check before exposure/execution, then a future approval engine for ask/deny/remember decisions. | Android equivalent should fail closed and use `SystemAccessApprovalPolicy` (`None`, `AskEveryTime`, `RememberPerSession`, `RememberPerScope`, `Forbidden`). |
| Approval analyzer | `src/permissions/analyzer.ts` | Produces recommended approval rule, scope, button text, and safety level for UI/headless approvals. | Android approval presentation model: operation summary, affected app/URI/path/contact/command, suggested remember scope, risk level. | Especially important for SAF writes, contact writes, shell, Shizuku, and root. |
| Headless protocol approval loop | `src/headless.ts` | Checks permissions for pending approvals, executes approved tools, submits approval results back to Letta. | `LocalBotSession.handleToolCalls()` plus future approval UI/notification queue. | Current Mobile path executes supported tools immediately. Higher-risk tools need an approval state machine before `submitToolResult()`. |
| Shell implementation | `src/tools/impl/Bash.ts` | Uses `node:child_process`, shell launchers, cwd/env, background process manager, timeout/output truncation. | No direct port. Use a Kotlin local-shell adapter only in sideload/root flavors, with command preview, timeout, output caps, and audit. | Keep out of safe Android intent batch. |
| Filesystem implementation | `Read.ts`, `Write.ts`, `Edit.ts`, search tools | Uses `node:fs`, cwd resolution, absolute paths, line limits, patching. | Map to app-private storage, SAF, media, all-files/root filesystem layers. | Android has multiple storage authorities; never collapse them into a single arbitrary path tool. |
| Process/session assumptions | `process.cwd()`, `USER_CWD`, env, background processes | Desktop working directory and process lifecycle. | Not portable to APK. Replace with explicit Android scopes and lifecycle-aware workers. | A Termux/root sidecar may emulate this later, but not as primary app architecture. |

## Native vs sidecar recommendation

Do **not** run the full Bun/Node Letta Code runtime inside the APK as the primary architecture.

Reasons:

- Letta Code tool implementations depend heavily on `node:fs`, `node:child_process`, `process.cwd()`, environment variables, shell availability, and desktop process lifecycle.
- Android storage, permissions, background execution, and app lifecycle do not match a desktop working-directory model.
- A bundled JS runtime would complicate APK size, Play policy posture, security review, and integration with Android permission/UI flows.
- The current Mobile code already has the right host path: `LocalBotSession` receives tool calls, `BotToolRegistry` dispatches, and `LettaRuntimeClient.submitToolResult()` continues the agent.

Use native Kotlin host tools as the primary path. Treat Letta Code as a design blueprint for:

1. explicit schema + description + implementation definitions,
2. model/toolset-specific exposure,
3. permission checks before execution,
4. approval context generation,
5. structured tool results.

A future Termux/root sidecar can be explored as an optional advanced adapter for users who explicitly want a POSIX-like environment. If added, it should sit behind the same `SystemAccessCapabilityRegistry`, approval engine, audit log, and flavor gates as native shell/root tools. It should not bypass native Android capabilities when framework APIs or user-mediated intents are safer.

## Existing Letta Mobile path

The current Mobile host-tool loop already aligns with Letta Code's split:

1. `LocalBotSession.ensureToolsSynced(agentId)` syncs local tool definitions to each target agent through `BotToolSync`.
2. `LocalBotSession` streams a conversation message through `LettaRuntimeClient.streamConversationMessage()`.
3. `LettaRuntimeEvent.ToolCallRequested` is handled locally.
4. `BotToolRegistry.execute(toolName, arguments)` parses arguments and invokes providers or `AndroidExecutionBridge`.
5. Tool results are structured JSON strings and are submitted with `LettaRuntimeClient.submitToolResult()`.

Current tools include device context, app launch, clipboard, notification status, launchable-app listing, and generated UI tools. Current bridge actions include `launchMainApp()`, `launchApp(packageName)`, `writeClipboard(text)`, `readClipboard()`, `notificationStatus()`, and `listLaunchableApps(limit)`.

The missing pieces for a Letta Code-style architecture are richer parameter schemas, explicit capability gating before exposure/execution, approval state for risky tools, and audit records for privileged actions.

## Proposed Android host-tool metadata

Introduce or evolve a definition shape along these lines:

```kotlin
data class AndroidHostToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val tags: Set<String>,
    val capabilityToolId: String?,
    val approvalPolicy: SystemAccessApprovalPolicy,
    val riskLevel: SystemAccessPolicyRiskLevel,
    val resultContract: ToolResultContract,
    val aliases: Set<String> = emptySet(),
)
```

Execution should follow this order:

1. Resolve alias to canonical tool name.
2. Validate JSON arguments against typed parser/schema requirements.
3. If `capabilityToolId != null`, call `SystemAccessCapabilityRegistry.checkToolAccess()`.
4. If approval is required, enqueue/show approval and return/continue only after approval.
5. Execute the typed implementation through `AndroidExecutionBridge` or another platform adapter.
6. Return structured JSON with `status`, `action`, input echo fields, and relevant identifiers.
7. Emit an audit record for capabilities with an audit policy.

## First tool families

### 1. Safe user-mediated Android OS actions (`letta-mobile-ifd7.2`)

These should be implemented first because they rely on Android framework intents or explicit UI and avoid silent mutation:

| Tool | Implementation preference | Safety note |
|---|---|---|
| `android_open_wifi_settings` | `Settings.ACTION_WIFI_SETTINGS` intent | Opens Settings; no silent change. |
| `android_show_location_on_map(location)` | `geo:` / maps intent chooser | User chooses app/action. |
| `android_send_email_draft(to, subject, body)` | `ACTION_SENDTO` `mailto:` or draft intent | Creates a draft; does not send. |
| `android_send_sms_draft(phone_number, body)` | `ACTION_SENDTO` `smsto:` | Creates a draft; does not send. |
| `android_create_calendar_event_draft(title, datetime)` | `CalendarContract.Events.CONTENT_URI` insert intent | User reviews before save. |
| `android_create_contact_draft(first_name, last_name, phone_number?, email?)` | `ContactsContract.Intents.Insert.ACTION` | User reviews before save. |
| `android_set_flashlight(enabled)` | CameraManager torch API if camera/flash is available | Direct device state change; should be capability-gated/audited even though reversible. |

Keep this batch separate from root, shell, arbitrary filesystem, notification listener, accessibility, and Shizuku/Sui.

### 2. Android storage and file tools (`letta-mobile-ifd7.3`, related `wjtr`)

Map Letta Code file tools to explicit Android storage layers:

| Letta Code family | Android-safe mapping | Approval posture |
|---|---|---|
| `Read` / `read_file` | App-private reads; SAF direct-document reads; media metadata/content after permission | App-private none; SAF per granted scope; media ask before remote exposure of sensitive content. |
| `Write` / `write_file` | App-private writes; SAF document/tree writes | App-private overwrite approval when destructive; SAF remember per URI/tree scope. |
| `Edit` / `Replace` / `ApplyPatch` | Existing-file edit in app-private or SAF scopes | Preview diff before write; remember per scope only after user opt-in. |
| `Glob` / `list_directory` | Bounded app-private listing; bounded SAF tree traversal | Avoid unbounded whole-device scans. |
| `Grep` / `search_file_content` | Bounded search inside approved app-private/SAF scopes | Caps and audit; avoid secret previews in logs. |
| `ReadManyFiles` | Batch app-private/SAF reads with count/byte caps | Ask for bulk operations and remote forwarding. |

### 3. Shell and privileged tools (`letta-mobile-ifd7.3`, related `yi6b`)

Shell-like tools should be mapped by privilege layer rather than exposing a single `bash` equivalent:

| Layer | Tool shape | Availability | Approval posture |
|---|---|---|---|
| No-root app shell | `android_run_local_shell_command(command, cwd_scope, timeout)` | Sideload/root only | Ask for mutating/network/long-running commands; allow read-only only if analyzer classifies safely. |
| Shizuku/Sui typed bridge | Specific typed tools, not arbitrary shell first | Sideload/root only | Ask every privileged mutation; audit binder operation. |
| Root shell | `android_run_root_shell_command(command, scope, timeout)` | Root flavor only | Ask every command initially; no broad persistence for dangerous commands. |
| Root filesystem | Read/search/write tools under approved path scopes | Root flavor only | High-friction path approvals, denylisted sensitive paths, diff/preview for writes. |

## Approval and risk tiers

Use `SystemAccessCapabilityRegistry` as the single source of truth for capability availability and coarse safety. Add per-execution approval on top.

| Tier | Examples | Default exposure | Approval | Audit |
|---|---|---|---|---|
| Low / no approval | App-private context, current time, battery, connectivity, open settings screens | Play/sideload/root | None or lightweight notice | Minimal structured event if useful. |
| Low / user-mediated | Draft email/SMS/contact/calendar, show map, SAF picker | Play/sideload/root | Usually none because Android UI is the approval; still disclose tool behavior. | Log action and target fields, redacting message body when needed. |
| Medium | Clipboard write/read, flashlight, contacts read, media import, SAF writes | Play where policy-compatible | Ask or remember per scope depending on sensitivity. | Log tool, scope, operation, redacted preview. |
| High | Notification listener content forwarding, accessibility-derived UI context, local shell | Sideload/root or carefully policy-gated Play | Ask every time or remember per narrow session/scope. | Required; redact content by default. |
| Very high / not Play compatible | Shizuku/Sui privileged mutations, root shell, root filesystem writes | Sideload/root only | Ask every time initially; limited remembered approvals after policy work. | Required with command/path/exit code and redacted output. |
| Forbidden until designed | Silent SMS/email sending, arbitrary root commands without preview, unbounded whole-device file search | Not exposed | Deny | Record denied attempt if surfaced. |

## Tool result contract

Keep results as structured JSON strings, matching current `BotToolRegistry` behavior. Recommended common fields:

```json
{
  "status": "success|unavailable|denied|error",
  "action": "android_send_sms_draft",
  "message": "Human-readable summary",
  "capability_id": "optional registry capability",
  "approval": {
    "required": false,
    "decision": "allowed|denied|user_mediated"
  }
}
```

Tool-specific fields should echo non-sensitive input identifiers (`package_name`, `uri`, `phone_number` if appropriate) and return Android result metadata. Avoid returning sensitive bodies unless needed by the model; prefer counts, booleans, and redacted previews.

## Follow-up work

Existing follow-up beads already cover the next phases:

- `letta-mobile-ifd7.2` — implement the safe Android OS action host tools.
- `letta-mobile-ifd7.3` — map Letta Code file and shell tools to Android storage/shell/root layers.

Recommended additions/refinements to those beads:

1. In `ifd7.2`, include schemas in the local tool definitions for all new `android_*` tools and add unit tests for missing/invalid arguments and JSON result shapes.
2. In `ifd7.2`, gate direct-state actions such as flashlight through a capability/tool id, or create a follow-up if the capability registry does not yet have a matching framework-device-action capability.
3. In `ifd7.3`, produce a table mapping every Letta Code file/shell/search/edit tool family to app-private, SAF, media, local shell, Shizuku/Sui, and root implementations.
4. Create a future implementation bead for a reusable Android tool approval engine once the first high-risk tool is selected. The safe intent batch can proceed first with user-mediated Android UI.

## Open questions

- Should local tool schemas be generated from Kotlin serializable argument classes, stored as JSON assets, or authored directly in code?
- Should `BotToolSync` upsert parameter schemas if the current Letta API path accepts them, or does it need a Letta client/API extension?
- What is the right UX for tool approvals during background chat transports: foreground dialog, notification action, or deferred tool result?
- Should the first approval engine support only session memory, or also persisted per-scope rules from the start?
