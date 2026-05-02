# Letta Code file/shell tools on Android access layers

**Date:** 2026-05-02

**Bead:** `letta-mobile-ifd7.3`

**Parent:** `letta-mobile-ifd7` — Build layered Android system access architecture

**References:** `docs/architecture/letta-code-android-host-tools.md`, `docs/architecture/android-system-access-capability-matrix.md`

## Purpose

Letta Code exposes desktop-style filesystem and shell tools against a process working directory. Letta Mobile must not mirror that as one arbitrary `path` or `bash` abstraction. Android has multiple storage authorities and privilege layers with different consent, policy, and safety properties.

This map converts the Letta Code file/search/edit/shell families into explicit Android host-tool layers:

1. app-private storage,
2. SAF-granted documents and trees,
3. media-library reads/imports,
4. no-root local shell sandbox,
5. Shizuku/Sui typed privileged bridges,
6. root shell,
7. root filesystem.

This bead is a design/mapping deliverable only. It does **not** implement raw shell, root shell, broad filesystem mutation, or new storage traversal.

## Tool naming principles

Android model-facing tools should encode the authority they operate against. Letta Code names may be accepted as aliases only after a selected scope is explicit.

| Principle | Rule |
|---|---|
| Scope in the name | Prefer `android_app_file_read`, `android_saf_file_read`, `android_media_search`, `android_root_file_read`, `android_local_shell_run`, and `android_root_shell_run` over `read_file` or `bash`. |
| Typed scope argument | Use `root + relative_path` for app-private storage, `grant_uri + document_id/path` for SAF, `media_id` for media, `scope_id + root_path` for root filesystem, and `cwd_scope` for shells. |
| Fail closed | A Letta Code alias with no explicit Android scope returns `unsupported` or `denied_scope_required`, not a best-effort device scan. |
| Split reads from mutation | Listing/reading/searching can have lighter policies than writes, edits, deletes, chmod/chown, package changes, or root commands. |
| Prefer Android APIs | Use app-private IO, SAF, MediaStore/photo picker, framework APIs, and typed Shizuku adapters before shelling out. |
| Keep aliases secondary | If compatibility requires `ReadFile`/`Bash` aliases, they should resolve through an alias analyzer that asks which Android layer and scope to use. |

## Capability ids and approval tiers

Existing capability ids from `SystemAccessCapabilityIds` are the coarse gates. Some granular tool ids below are proposed additions where the current registry only lists broad ids.

| Layer | Capability id | Flavor availability | Default approval | Owner bead |
|---|---|---:|---|---|
| App-private storage | `storage.app_private` | play/sideload/root | none for bounded list/read/write-create; ask for overwrite/delete/export if added | completed `letta-mobile-wjtr`; granular follow-up in implementation bead if needed |
| SAF files/trees | `storage.saf` | play/sideload/root | Android picker grant; remember per URI/tree; ask for destructive writes and bulk reads | completed `letta-mobile-wjtr`; tree/destructive follow-up `letta-mobile-3hse` |
| Media library | `storage.media_library` | play/sideload/root | ask before broad search/import or model/server exposure | `letta-mobile-i215` |
| All-files storage | `storage.all_files` | sideload policy-gated/root | ask every time; high-friction setup | future policy-specific bead; prefer `filesystem.root` for root-only paths |
| No-root local shell | `shell.local` | sideload/root | ask every command initially; possible analyzer-based session memory later | `letta-mobile-lyzj`; approval design `letta-mobile-1g4k` |
| Shizuku bridge | `bridge.shizuku` | sideload/root | ask every privileged mutation; typed operations only | `letta-mobile-j53z`; approval design `letta-mobile-1g4k` |
| Sui bridge | `bridge.sui` | sideload/root | ask every privileged mutation; typed operations only | `letta-mobile-j53z`; approval design `letta-mobile-1g4k` |
| Root shell | `shell.root` | root | ask every command; no broad persistence until audit policy lands | `letta-mobile-yi6b`, `letta-mobile-xz06` |
| Root filesystem | `filesystem.root` | root | ask for every write/destructive/sensitive read; remember only narrow path scopes | `letta-mobile-b3z3`, blocked by `letta-mobile-yi6b` and `letta-mobile-xz06` |

## Mapping tables

### Read, list, and search tools

| Letta Code names | Android model-facing tool | Capability / tool id | Scope arguments | Result contract | Unsupported/forbidden states | Owner |
|---|---|---|---|---|---|---|
| `Read`, `ReadFile`, `read_file` | `android_app_file_read` | `storage.app_private` / existing `storage.app_private.read` | `root=files|cache|no_backup`, `relative_path`, optional `max_bytes` | `status`, `root`, `path`, `bytes_read`, `truncated`, `content` or encoded content metadata | absolute paths, `..` escapes, reads above byte cap | completed foundation in `letta-mobile-wjtr` |
| `Read`, `ReadFile`, `read_file` | `android_saf_file_read` | `storage.saf` / existing `storage.saf.read` | `grant_uri` or `document_uri`, optional `display_name`, optional `max_bytes` | `status`, `uri`, `display_name`, `mime_type`, `bytes_read`, `truncated`, `content` | no persisted grant, write-only grant, unsupported provider, above cap | completed direct-document foundation in `letta-mobile-wjtr`; tree paths in `letta-mobile-3hse` |
| `Read`, `ReadFile`, `read_file` | `android_media_read` or `android_media_import` | `storage.media_library` / existing `media.import`, `media.attach`; proposed `media.read` | `media_id` or user picker result, `media_type`, optional `max_bytes` | `status`, `media_id`, `mime_type`, `bytes_read`, `local_attachment_uri`; content only if explicitly approved | background broad media reads, unapproved remote/model forwarding, unsupported media permission | `letta-mobile-i215` |
| `Read`, `ReadFile`, `read_file` | `android_root_file_read` | `filesystem.root` / existing `filesystem.root.read` | `scope_id`, `root_path`, optional `max_bytes`, optional `redaction_mode` | `status`, `path`, `bytes_read`, `truncated`, `redacted`, `content` or preview | Play/sideload flavor, no root approval, denylisted sensitive path without explicit approval | `letta-mobile-b3z3` |
| `Glob`, `list_directory` | `android_app_directory_list` | `storage.app_private` / proposed `storage.app_private.list` | `root`, `relative_path`, `max_entries` | `status`, `root`, `path`, `entries[]`, `truncated` | recursive whole-sandbox scans without cap | completed foundation in `letta-mobile-wjtr`; registry should add granular id |
| `Glob`, `list_directory` | `android_saf_directory_list` | `storage.saf` / proposed `storage.saf.list` | `tree_uri`, `relative_path` or provider document id, `max_entries` | `status`, `tree_uri`, `path`, `entries[]`, `truncated` | no tree grant, unbounded recursion, provider denies children | `letta-mobile-3hse` |
| `Glob`, `list_directory` | `android_root_directory_list` | `filesystem.root` / proposed `filesystem.root.list` | `scope_id`, `root_path`, `max_entries` | `status`, `path`, `entries[]`, `truncated`, `redacted_names_count` | no root, no path approval, sensitive path without approval | `letta-mobile-b3z3` |
| `Grep`, `search_file_content` | `android_app_file_search` | `storage.app_private` / proposed `storage.app_private.search` | `root`, `relative_path`, `query` or safe regex subset, `max_files`, `max_matches` | `status`, `matches[]` with path/line/range/redacted preview, `truncated` | unsafe regex, binary content by default, unbounded search | `letta-mobile-euk5` |
| `Grep`, `search_file_content` | `android_saf_file_search` | `storage.saf` / existing `storage.saf.search` | `tree_uri`, `relative_path`, `query`, `max_files`, `max_matches` | same as app search plus `uri` fields | no tree grant, unbounded whole-tree scan, secret previews in audit logs | `letta-mobile-3hse` |
| `Grep`, `search_file_content` | `android_media_search` | `storage.media_library` / existing `media.search` | `media_type`, metadata query, date/type filters, `max_results` | metadata-only results by default: `media_id`, `display_name`, `mime_type`, `size`, `date` | full content OCR/transcription without separate approval; background all-library scans | `letta-mobile-i215` |
| `Grep`, `search_file_content` | `android_root_file_search` | `filesystem.root` / existing `filesystem.root.search` | `scope_id`, `root_path`, query, caps, redaction mode | `status`, redacted `matches[]`, `truncated`, `approval_id` | denylisted paths, no root approval, no caps | `letta-mobile-b3z3` |
| `ReadManyFiles` / batch reads | `android_*_file_read_batch` variants | same capability as selected authority; proposed batch tool ids | list of scoped file handles and aggregate byte/file caps | `status`, `files[]`, `total_bytes`, `truncated`, per-file errors | mixed authorities in one call unless represented as separate scoped handles; remote forwarding without approval | same owner as selected layer |

### Write, edit, replace, and patch tools

| Letta Code names | Android model-facing tool | Capability / tool id | Scope arguments | Approval/audit | Result contract | Unsupported/forbidden states | Owner |
|---|---|---|---|---|---|---|---|
| `Write`, `WriteFile`, `write_file` | `android_app_file_write` | `storage.app_private` / existing `storage.app_private.write` | `root`, `relative_path`, `content`, `overwrite=false` | no approval for create under caps; ask/audit for overwrite if destructive policy is added | `status`, `root`, `path`, `bytes_written`, `created`, `overwritten` | absolute paths, parent escape, overwrite without flag/approval, above cap | completed foundation in `letta-mobile-wjtr` |
| `Write`, `WriteFile`, `write_file` | `android_saf_file_write` | `storage.saf` / existing `storage.saf.write` | `document_uri` or `tree_uri + relative_path`, `content`, `overwrite=false` | remember per grant for non-destructive writes; ask for overwrite/delete/bulk | `status`, `uri`, `display_name`, `bytes_written`, `created` | no persisted write grant, provider denies write, destructive write without approval | direct document foundation in `letta-mobile-wjtr`; tree/destructive approval in `letta-mobile-3hse` |
| `Write`, `WriteFile`, `write_file` | `android_root_file_write` | `filesystem.root` / existing `filesystem.root.write` | `scope_id`, `root_path`, `content`, `dry_run=true` default | ask every time initially; audit command/path/diff/approval id; diff/preview required | `status`, `path`, `bytes_written`, `created`, `dry_run`, `approval_id` | Play/sideload, no approval, denylisted path, mount/system mutation without special bead | `letta-mobile-b3z3` |
| `Edit`, `Replace` | `android_app_file_replace` | `storage.app_private` / proposed `storage.app_private.edit` | `root`, `relative_path`, `old_text`, `new_text`, `expected_replacements` | ask if overwrite/destructive policy says so; audit path and replacement counts, not content | `status`, `path`, `replacements`, `bytes_written`, `preview` if dry-run | ambiguous/multiple replacements without explicit count, binary file, above cap | `letta-mobile-euk5` |
| `Edit`, `Replace` | `android_saf_file_replace` | `storage.saf` / proposed `storage.saf.edit` | `document_uri` or tree scope, `old_text`, `new_text`, count guard | ask before write; audit URI, operation, redacted diff preview | `status`, `uri`, `replacements`, `bytes_written`, `approval_id` | no write grant, provider streaming constraints, binary/large files | `letta-mobile-3hse` |
| `Edit`, `Replace` | `android_root_file_replace` | `filesystem.root` / proposed `filesystem.root.edit` | `scope_id`, `root_path`, replacement request, `dry_run=true` default | ask every time; audit path, redacted diff, approval id | `status`, `path`, `replacements`, `dry_run`, `approval_id` | denylisted path, no root, no dry-run/preview for first execution | `letta-mobile-b3z3` |
| `ApplyPatch` | `android_app_file_apply_patch` | `storage.app_private` / proposed `storage.app_private.patch` | `root`, patch bundle, expected file hashes | ask when patch mutates existing files; audit paths/counts | `status`, `files_changed`, `hunks_applied`, per-file result | patch outside app-private root, missing hashes for destructive patch, symlink/escape | `letta-mobile-euk5` |
| `ApplyPatch` | `android_saf_file_apply_patch` | `storage.saf` / proposed `storage.saf.patch` | tree grant, patch bundle, expected hashes | ask with diff summary; remember only per tree/scope if user opts in | same as app patch plus URI mapping | no tree grant, provider cannot map paths, unsupported binary patches | `letta-mobile-3hse` |
| `ApplyPatch` | `android_root_file_apply_patch` | `filesystem.root` / proposed `filesystem.root.patch` | approved root path scope, patch, expected hashes, `dry_run=true` default | ask every time initially; audit full path list and redacted diff | same as app patch plus `approval_id` and `dry_run` | system partition/remount/package manager changes without dedicated root policy | `letta-mobile-b3z3` |
| Delete/move/chmod/chown implied by shell or patch | No generic Play tool; use future explicit tools only | `storage.saf` / `filesystem.root` proposed destructive ids | explicit operation-specific scopes | ask every time; audit before/after | operation-specific result | unsupported until approval engine exists; forbidden from generic `write_file` | `letta-mobile-1g4k`, `letta-mobile-3hse`, `letta-mobile-b3z3` |

### Shell and process tools

| Letta Code names | Android model-facing tool | Capability / tool id | Scope arguments | Approval/audit | Result contract | Unsupported/forbidden states | Owner |
|---|---|---|---|---|---|---|---|
| `Bash`, `shell_command`, `run_shell_command` | `android_local_shell_run` | `shell.local` / existing `shell.local.run` | `command`, `cwd_scope`, `timeout_ms`, optional sanitized env | ask every command initially; audit command/cwd/exit/duration; redact env/stdout/stderr by default | `status`, `exit_code`, `stdout_truncated`, `stderr_truncated`, `duration_ms`, `timed_out` | Play flavor, no user setup, no cwd scope, background persistence, daemonization, commands over cap | `letta-mobile-lyzj`, approval design `letta-mobile-1g4k` |
| `Bash`, `shell_command`, `run_shell_command` | `android_root_shell_run` | `shell.root` / existing `shell.root.run` | `command`, `cwd_scope` or approved root path scope, `timeout_ms`, provider hints | ask every command; classify read/write/destructive/persistence; audit approval id, provider, exit, redacted output | same as local shell plus `approval_id`, `provider_hint` | play/sideload flavor, no external su approval, no Letta root opt-in, interactive prompts, background services | `letta-mobile-yi6b`, `letta-mobile-xz06` |
| `Bash` for Android framework/system actions | Prefer typed tools, not shell | `bridge.shizuku`, `bridge.sui`, existing typed ids (`shizuku.package_ops`, `shizuku.appops`, `shizuku.settings`) | operation-specific structured args | ask for privileged mutations; audit binder API and target package/setting | operation-specific JSON | arbitrary Shizuku/Sui shell text is unsupported until a separate risk review | `letta-mobile-j53z`, `letta-mobile-1g4k` |
| Shell background process manager | unsupported initially | none | N/A | N/A | `status=unsupported`, `reason=background_processes_not_supported` | long-running daemons, persistent reverse shells, unbounded log streaming | future long-running task bead if product need appears |
| Desktop cwd/env assumptions | unsupported alias behavior | none | N/A | N/A | `status=unsupported`, `reason=explicit_android_scope_required` | `process.cwd()`, arbitrary environment inheritance, host `$HOME` assumptions | this design bead |

### Explicit unsupported or forbidden mappings

| Letta Code-style request | Android result | Reason |
|---|---|---|
| `ReadFile` with an arbitrary absolute Android path in play/sideload | `unsupported` / `explicit_scope_required` | Android app cannot and should not translate arbitrary paths without SAF, app-private, media, all-files, or root scope. |
| `Grep` across `/sdcard` or the whole device | `denied` / `scope_and_caps_required` | Unbounded personal-data scan; use SAF tree/media search with caps or root filesystem with approval. |
| `Bash` in Play flavor | `unavailable` | Local shell is sideload/root only by capability matrix. |
| `Bash` as root in non-root flavor | `unavailable` | Root bridge is root flavor only and requires external `su`. |
| Silent email/SMS sending via shell or intents | `forbidden` | Only user-mediated drafts are allowed; sending requires explicit user action outside the tool. |
| System setting/package/appops mutation via arbitrary shell | `unsupported` | Use typed Shizuku/Sui/root tools with operation-specific approval and audit. |
| File delete/chmod/chown/mount/remount from generic write/patch tools | `forbidden_until_designed` | Needs explicit operation tool, approval text, audit schema, and root policy. |
| Returning full file/media/shell output into audit logs | `forbidden` | Audit logs must redact content/output by default and store only bounded previews when approved. |

## Result contracts

All tools should return structured JSON strings through the existing local tool result path. Common fields:

```json
{
  "status": "success|unavailable|denied|unsupported|error",
  "action": "android_saf_file_read",
  "capability_id": "storage.saf",
  "tool_id": "storage.saf.read",
  "message": "Human-readable summary",
  "approval": {
    "required": true,
    "decision": "allowed|denied|user_mediated|not_required",
    "approval_id": "optional"
  },
  "limits": {
    "truncated": false,
    "max_bytes": 65536,
    "max_entries": 200
  }
}
```

Layer-specific fields:

| Layer | Required fields | Redacted/default-hidden fields |
|---|---|---|
| App-private storage | `root`, `path`, `bytes_read|bytes_written`, `created`, `truncated` | file content in audit; large content in tool result above cap |
| SAF | `uri`, `display_name`, `mime_type`, `grant_uri`, `bytes_read|bytes_written`, `truncated` | content preview, user file names in broad telemetry if not needed |
| Media | `media_id`, `media_type`, `mime_type`, `size_bytes`, `date_modified`, attachment URI when imported | media content, EXIF/location unless explicitly approved |
| Local shell | `command_hash` or command preview, `cwd_scope`, `exit_code`, `duration_ms`, `timed_out`, output truncation flags | env, stdout/stderr in audit by default |
| Shizuku/Sui | `api`, `operation`, `target_package|setting_key`, `mutation=false|true` | payload content and sensitive setting values |
| Root shell | local shell fields plus `provider_hint`, `approval_id` | env, stdout/stderr, secrets in command arguments when detectable |
| Root filesystem | `scope_id`, `path`, `operation`, `bytes`, `dry_run`, `approval_id`, truncation flags | file content, secret-looking previews |

## Audit fields and approval rules

| Operation class | Approval policy | Minimum audit fields | Redacted fields |
|---|---|---|---|
| Bounded app-private read/list | `None` | `toolId`, `root`, `path`, `operation`, `bytes/entries` | `content` |
| App-private create write | `None` under caps; ask if remote/export-sensitive | `toolId`, `root`, `path`, `bytesWritten`, `created` | `content` |
| App-private overwrite/edit/patch | `AskEveryTime` or `RememberPerScope` after `letta-mobile-1g4k` | `toolId`, `path`, `operation`, `replacementCount/filesChanged`, `approvalId` | `oldText`, `newText`, `diff` unless explicitly local preview |
| SAF read/list | picker grant is base consent; ask for bulk/model-forwarding | `toolId`, `grantUri`, `uri/path`, `operation`, `bytes/entries` | `contentPreview` |
| SAF write/edit/patch/delete | `RememberPerScope` only after explicit user opt-in; destructive ask every time initially | `toolId`, `grantUri`, `uri/path`, `operation`, `approvalId`, `bytes/filesChanged` | `content`, redacted diff |
| Media metadata search | `AskEveryTime` for broad search or remote forwarding | `toolId`, `mediaType`, `filters`, `resultCount` | media content, EXIF/location by default |
| Local shell | `AskEveryTime` initially | `toolId`, `command`, `cwd`, `exitCode`, `durationMs`, `approvalId` | `environment`, `stdout`, `stderr` |
| Shizuku/Sui typed mutation | `AskEveryTime` | `toolId`, `api`, `operation`, `targetPackage/setting`, `mutation`, `approvalId` | payload/sensitive values |
| Root shell | `AskEveryTime`; maybe session-scope only after `letta-mobile-xz06` classification | `toolId`, `command`, `cwd`, `exitCode`, `durationMs`, `providerHint`, `approvalId` | `environment`, `stdout`, `stderr` |
| Root filesystem write/destructive/sensitive read | `AskEveryTime` with dry-run/diff first | `toolId`, `scopeId`, `path`, `operation`, `dryRun`, `approvalId`, `bytes/filesChanged` | file content, secret previews |

## Implementation bead breakdown

| Area | Existing/open bead | Notes |
|---|---|---|
| Safe Android OS action tools | completed `letta-mobile-ifd7.2` | Already added user-mediated intents and flashlight host action batch. |
| App-private storage + SAF direct-document foundation | completed `letta-mobile-wjtr`; follow-up `letta-mobile-euk5` | Existing registry ids are broad; add granular list/search/edit/patch ids when implementing model-facing aliases. |
| SAF tree traversal and destructive write approvals | `letta-mobile-3hse` | Owns bounded DocumentFile traversal, search, destructive-write approval, revoke guidance. |
| MediaStore/photo picker tools | `letta-mobile-i215` | Owns `android_media_search`, import/attach/read contracts, metadata minimization. |
| Approval/audit engine | `letta-mobile-1g4k` | Needed before exposing risky writes, shell, Shizuku/Sui, root filesystem mutation. |
| No-root local shell sandbox | `letta-mobile-lyzj` | Owns `android_local_shell_run` and app-UID cwd scope model. |
| Root shell bridge | `letta-mobile-yi6b` | Owns root provider detection/execution; no model exposure without `letta-mobile-xz06`. |
| Root command approval and audit | `letta-mobile-xz06` | Owns command classification, approval prompts, audit schema for root shell. |
| Root filesystem tools | `letta-mobile-b3z3` | Owns `android_root_file_*` tools after root shell and approval/audit exist. |
| Shizuku/Sui typed bridge | `letta-mobile-j53z` | Should expose typed package/appops/settings adapters, not a generic shell first. |
| Letta Code alias resolver | `letta-mobile-0zyo` | Maps `ReadFile`/`Bash`-style names to explicit Android scopes; otherwise keep canonical Android names only. |

## Open questions

1. Should the first model-facing storage tools use the existing registry tool ids directly, or should `SystemAccessCapabilityDefinitions` gain granular ids such as `storage.app_private.list`, `storage.app_private.search`, `storage.saf.list`, and `filesystem.root.patch` before exposure?
2. Should `ReadFile`/`WriteFile` aliases ever be synced to agents, or should Letta Mobile expose only explicit `android_*` names to avoid desktop assumptions?
3. What is the maximum safe content size to return to the model for SAF/media/root reads, and should it vary by agent trust or connection mode?
4. Should Shizuku and Sui share one typed bridge capability in code, or remain separate capabilities with identical adapter implementations but different disclosure text?
5. Should app-private edit/patch support be implemented as standalone low-risk tools before SAF/root edit support, or wait for the common approval/audit engine?
