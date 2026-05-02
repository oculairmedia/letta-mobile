# Android System Access Capability Matrix

**Date:** 2026-05-02  
**Bead:** `letta-mobile-n3x7`  
**Parent:** `letta-mobile-ifd7` — Build layered Android system access architecture

## Purpose

Letta Mobile should expose Android and Linux-device capabilities through a layered, user-controlled architecture rather than a single monolithic "root mode." This matrix defines the initial product and engineering contract for storage, contacts, overlay, notifications, accessibility, no-root shell, Shizuku/Sui, and root-backed access.

The design goals are:

1. Keep the Play build policy-safe by default.
2. Make every privileged capability discoverable, explainable, revocable, and auditable.
3. Prefer Android framework APIs before shell bridges.
4. Prefer no-root and user-consented delegated access before root.
5. Keep root integration compatible with standard `su` providers such as KernelSU, SukiSU-Ultra, and Magisk without embedding any root manager.

## Proposed build flavor model

| Flavor | Distribution | Capability posture | Intended use |
|---|---|---|---|
| `play` | Google Play | Framework-only capabilities that can pass Play policy review. No bundled root, no Shizuku/Sui dependency, no root command tools. | Main public app. |
| `sideload` | GitHub releases / direct APK | Framework capabilities plus opt-in experimental local shell and optional Shizuku/Sui bridge. No root filesystem tools by default. | Power users who want local automation while staying mostly non-root. |
| `root` | GitHub releases / direct APK only | All sideload capabilities plus root shell bridge, root filesystem tools, root approval UI, and audit log. | Explicitly-rooted devices running KernelSU, SukiSU-Ultra, Magisk, or compatible `su`. |

Implementation note for `letta-mobile-74bn`: use Gradle product flavors to keep policy-sensitive code and manifest entries out of the `play` artifact where possible. Runtime feature flags are not enough for Play-sensitive root tooling.

## Capability matrix

| Capability | Play | Sideload | Root | Android permission / setting | User consent flow | Tool exposure | Approval requirement | Play policy risk |
|---|---:|---:|---:|---|---|---|---|---|
| App-private storage | ✅ | ✅ | ✅ | None beyond app sandbox | Normal app install and in-app explanation | Read/write app-created files, exports, cached artifacts | No per-operation approval; respect in-app data controls | Low |
| User-selected files and folders | ✅ | ✅ | ✅ | Storage Access Framework (`ACTION_OPEN_DOCUMENT`, `ACTION_OPEN_DOCUMENT_TREE`), persisted URI grants | Android picker; show selected scope in System Access dashboard | File read/write/search inside granted document/tree URIs | Approval for destructive writes and bulk export/import | Low if scoped and user-initiated |
| Media library | ✅ | ✅ | ✅ | Android 13+: `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`; older Android: scoped storage or `READ_EXTERNAL_STORAGE` where applicable | Runtime permission plus purpose-specific disclosure | Media search/import/attachment tools | Approval for bulk scans, uploads, or deletes | Medium; must justify media access and avoid broad background collection |
| All-files storage | ❌ | ⚠️ | ✅ | `MANAGE_EXTERNAL_STORAGE` special app access or root filesystem bridge | Special-access settings screen plus high-friction disclosure | Whole-device file search/read/write where authorized | Required for every destructive write and sensitive-path read; audit all operations | High; generally not suitable for Play unless core file-manager use case |
| Contacts read | ✅ | ✅ | ✅ | `READ_CONTACTS` runtime permission | Android runtime permission; explain exact Letta tools enabled | Contact lookup, disambiguation, user-approved insertion into prompts/tools | Approval before sending contact data to model/server unless already user-selected | Medium; sensitive personal data requires clear disclosure and minimization |
| Contacts write | ⚠️ | ✅ | ✅ | `WRITE_CONTACTS` runtime permission | Separate opt-in from read; preview diff before write | Create/update contact tools | Required for every write | Medium/high; avoid in Play unless product need is explicit |
| Overlay / floating assistant | ✅ | ✅ | ✅ | `SYSTEM_ALERT_WINDOW` special app access (`Settings.canDrawOverlays`) | Explain persistent surface behavior; deep link to Android special access settings | Floating assistant bubble/panel, contextual quick actions | No approval for showing surface after permission; approval for actions launched from it | Medium; must not be deceptive, interfere with other apps, or collect data silently |
| Notification listener | ✅ | ✅ | ✅ | `NotificationListenerService` enabled in Android settings | Deep link to notification access settings; show examples of captured fields | Notification triage, summarization, action suggestions, optional local-only indexing | Approval before forwarding notification content to a remote model/server by default | High sensitivity; Play disclosure and limited-use handling required |
| Post notifications | ✅ | ✅ | ✅ | Android 13+: `POST_NOTIFICATIONS` runtime permission | Standard runtime permission | Letta status alerts, long-running task completion, approval prompts | No per-notification approval | Low/medium depending on notification volume and content |
| Accessibility service | ⚠️ | ✅ | ✅ | `BIND_ACCESSIBILITY_SERVICE` service declaration and user enablement in Accessibility settings | Dedicated education screen; Android settings enablement; explicit statement that this is not for disability assistance unless that becomes true | UI context inspection, optional user-driven UI automation, screen element summaries | Required for automation actions; audit captured UI events and text | Very high; Play policy is strict. Avoid in Play unless accessibility is core, truthful, and compliant |
| No-root local shell sandbox | ❌ | ✅ | ✅ | No Android permission for app UID process; uses app sandbox and bundled/available binaries | In-app opt-in with command preview and working-directory scope | Run local commands as app UID only; app files, limited network, no cross-app data | Required for network, long-running, or file-mutating commands; audit commands and outputs | Medium/high for Play due to executable-code and abuse concerns; keep out of Play initially |
| Shizuku bridge | ❌ | ✅ | ✅ | User-installed Shizuku app/service; binder permission such as `moe.shizuku.manager.permission.API_V23`; optional pairing/start flow | Detect Shizuku, explain delegated privilege, request Shizuku permission | Privileged Android API adapters where Shizuku grants access; package/appops/settings operations after evaluation | Required for privileged mutations; audit binder calls at capability layer | High for Play if privileged behavior appears to bypass Android permission model; sideload only initially |
| Sui bridge | ❌ | ✅ | ✅ | Sui module on rooted devices exposing Shizuku-compatible API | Same as Shizuku plus root-module disclosure | Same bridge shape as Shizuku; root-backed delegated privileged APIs | Required for mutations; audit calls | High; sideload/root only |
| Root shell | ❌ | ❌ | ✅ | External `su` provider prompt/profile: KernelSU, SukiSU-Ultra, Magisk, or compatible `su`; no embedded root manager | In-app root capability opt-in, external root prompt, persistent dashboard state | Root command execution through a narrow bridge; environment detection; capability probes | Required for every command until user grants a narrowly-scoped session/policy; audit all commands, cwd, exit code, and redacted output | Not Play-compatible |
| Root filesystem tools | ❌ | ❌ | ✅ | Root shell plus tool-specific path allowlist/denylist | High-friction enablement; explain irreversible risk; path preview | Read/search/copy/write under approved filesystem scopes | Required for every write, delete, chmod/chown, mount, or sensitive read; audit and support dry-run | Not Play-compatible |
| KernelSU / SukiSU profile guidance | ❌ | ✅ docs | ✅ docs | External manager app App Profile / superuser policy | Documentation only; no embedding or direct manager control | Setup checklist, troubleshooting, recommended least-privilege profile | N/A in app, but root commands still require Letta approval | Docs are low risk outside Play; root guidance should not ship in Play listing copy |

Legend: ✅ supported target, ⚠️ possible but policy/product gated, ❌ excluded from flavor, `docs` documentation-only.

## Capability design notes

### 1. Storage access

Storage should be split into separate capabilities rather than represented as a single "files" switch:

- **App-private storage:** always available and safe for tool scratch space, exports, and local artifacts.
- **SAF user-selected scopes:** preferred user-file integration. Persist URI grants and show each grant in the dashboard with revoke instructions.
- **Media library:** only request specific media permissions needed for the active task.
- **All-files/root filesystem:** sideload/root-only and guarded by approvals, denylisted sensitive paths, and audit records.

The app should avoid `MANAGE_EXTERNAL_STORAGE` in the Play flavor unless Letta Mobile becomes a genuine file-manager-style product, which is not the current core use case.

Implementation status (`letta-mobile-wjtr`): the first storage layer now exposes app-private storage helpers for bounded list/read/write operations under `filesDir`, `cacheDir`, and `noBackupFilesDir`. Every app-private path is resolved through canonical containment checks before IO, absolute paths and `..` escapes fail closed, directory listings and reads are capped, and writes refuse to overwrite unless explicitly requested. SAF foundation code persists and lists Android picker grants and supports bounded direct-document read/write only when a matching persisted grant exists; broad tree search remains intentionally unsupported until bounded `DocumentFile` traversal and destructive-write approvals are added.

### 2. Contacts

Contacts are sensitive personal data and should be exposed as read-only first. Tool design should separate:

- local lookup/autocomplete,
- model-visible contact snippets,
- writes/updates.

Default behavior should keep contact data local until the user selects a contact or approves sharing a specific result with an agent/server.

### 3. Overlay and floating assistant

Overlay is a UI surface capability, not a general automation capability. The floating assistant can provide quick access and context entry points, but it should not imply notification, accessibility, or screen-reading access. Each additional data source remains separately consented.

### 4. Notification listener

Notification access can reveal cross-app private content. The safe default is local processing with explicit consent before remote/model forwarding. The dashboard should show:

- whether notification listener is enabled,
- which notification-derived tools are exposed,
- whether remote forwarding is disabled, ask-every-time, or allowed for selected agents.

### 5. Accessibility

Accessibility is the highest-risk framework capability. It must not be used as a stealth replacement for missing APIs. If implemented, gate it behind:

- a truthful capability description,
- a separate settings page,
- action previews for UI automation,
- clear collection limits,
- an audit stream for captured app/package names and automation actions.

The Play flavor should treat accessibility as disabled by default and only include it if a future compliance pass confirms the exact user-facing use case is acceptable.

### 6. No-root shell sandbox

A no-root shell runs as Letta Mobile's app UID. It cannot read other apps' private data, change system settings, or bypass Android permissions. It is still powerful enough to mutate app files, consume resources, make network requests, and execute bundled binaries, so it needs a command approval model even without root.

Initial sideload scope should be:

- explicit working directory,
- command preview,
- environment variable redaction,
- timeout and output limits,
- no background persistence without an active user-visible task.

### 7. Shizuku and Sui

Shizuku and Sui should be evaluated as delegated privileged API bridges, not as root replacements. They may provide safer typed access to package, appops, settings, and content APIs than parsing shell output, but each API still needs a capability entry, consent state, and audit category.

Default stance:

- Sideload/root flavors may include bridge detection and optional integration.
- Play flavor excludes bridge code until policy risk is separately reviewed.
- Tool implementations should call typed adapters through the same capability registry used by framework and root access.

### 8. Root, KernelSU, SukiSU-Ultra, and Magisk

KernelSU is a kernel-backed root manager, not an SDK that Letta Mobile should embed. KernelSU's manager app uses native KernelSU JNI calls for KernelSU status and app-profile management, and `libksud.so` / `su` behavior for root shells. It can configure App Profiles controlling `allowSu`, UID/GID, supplemental groups, Linux capabilities, SELinux domain, mount namespace, and module unmount behavior.

SukiSU-Ultra is a KernelSU fork focused on broader kernel/device compatibility and features such as KPM, susfs, and manager changes. From Letta Mobile's perspective, SukiSU should be treated as another compatible external `su` provider unless a future bead proves a specific app-facing API is needed.

Magisk remains another common `su` provider. The Letta Mobile root bridge should target standard `su`/libsu-compatible behavior rather than any one manager's private APIs.

Root integration rules:

- Do not embed KernelSU, SukiSU, Magisk, or their manager functionality.
- Detect root availability by probing `su` through a small bridge and recording provider hints only for diagnostics.
- Require a separate Letta in-app root enablement switch even if the external root manager grants `su`.
- Require command approvals and audit logging for root operations.
- Provide documentation for recommended KernelSU/SukiSU App Profile settings instead of attempting to manage those profiles from Letta Mobile.

## Kai pattern carry-forward

The Android system-access UX should carry forward the Kai-style pattern of making capabilities explicit and reversible:

1. **Capability cards before tools:** users grant a named capability first, then individual tools become available.
2. **Consent is scoped:** selected folders, selected agents, selected command classes, or selected data types are better than global toggles.
3. **Preview before mutation:** file writes, contact writes, notification actions, UI automation, Shizuku mutations, and root commands all show a preview/diff/command before execution.
4. **Audit by default:** privileged actions create an audit event even when the user has granted a remembered approval.
5. **Local-first processing:** sensitive data sources are summarized or filtered locally unless the user explicitly allows remote/model exposure.
6. **No silent escalation:** a root-capable build must still prefer framework/SAF/Shizuku paths when those are sufficient and safer.

## Capability registry implications

For `letta-mobile-14cm`, model each capability with at least:

| Field | Purpose |
|---|---|
| `id` | Stable capability identifier, e.g. `storage.saf`, `contacts.read`, `shell.root`. |
| `flavorAvailability` | Compile/runtime availability for `play`, `sideload`, and `root`. |
| `status` | `Unavailable`, `AvailableNeedsSetup`, `Denied`, `Granted`, `GrantedLimited`, `Revoked`, `Error`. |
| `permissionIntents` | Android runtime permission requests and settings deep links. |
| `dataSensitivity` | Public/app-private/personal/cross-app/system/root. |
| `toolIds` | Tools unlocked by the capability. |
| `approvalPolicy` | None, ask every time, remember per session, remember per scope, forbidden. |
| `auditPolicy` | What gets logged and how outputs are redacted. |
| `policyRisk` | Play/distribution risk flag and rationale. |

The registry should be the single source of truth for the System Access dashboard and for tool availability checks. Tools should fail closed if the registry says a capability is unavailable or unapproved.

## Initial implementation order

1. Build flavors and capability registry foundation.
2. System Access settings dashboard that can display granted/denied/unavailable states before all backends exist.
3. Low-risk framework capabilities: SAF storage, contacts read, post notifications.
4. Medium/high-risk framework capabilities: overlay, notification listener, accessibility only after policy review.
5. Sideload no-root shell sandbox.
6. Shizuku/Sui evaluation and typed bridge prototype.
7. Root shell bridge, approval UI, audit log, and root filesystem tools.
8. KernelSU/SukiSU profile guidance documentation.

## Open policy questions

- Should the Play build include notification listener if remote forwarding is disabled by default, or should it be sideload-only until a Play disclosure review is complete?
- Is accessibility a genuine user-facing accessibility use case, or only automation? If automation only, keep it out of Play.
- Do contacts write tools provide enough user value to justify Play-sensitive permissions, or should the first version be read-only?
- Should `sideload` include Shizuku/Sui code by default, or should those integrations live only in a separate `privileged` source set shared with `root`?
