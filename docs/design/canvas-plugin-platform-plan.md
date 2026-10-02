# Canvas Plugin Platform: Architecture Plan (revision 2)

Epic: letta-mobile-s416w. Date: 2026-10-02. Status: **plan, validated against origin/main f1c96bb0e; revised after Emmanuel's decisions of 2026-10-02 (recorded on the epic). Group A (beads .1–.5) is unchanged from revision 1 and is in progress.**

Emmanuel's decisions that drive this revision:

1. **No MCP as the foundation.** The platform defines **its own plugin contract** in two forms: a **Kotlin SPI + manifest for in-process JVM jars hot-loaded by the host**, and an **out-of-process protocol in any language** (a small JSON protocol we define, over stdio or WebSocket). Clients only ever receive declarative content (elements, fallback cards) and sandboxed web views; no plugin code runs on Android. An MCP adapter may exist later as one optional plugin, never the foundation.
2. **Install from a settings UI from the start**: hot-loading on the host (add/remove/enable/disable/update without a restart), secret entry in the UI, consent for capabilities and the network allowlist, a client↔host plugin-management API over the existing Iroh channel.
3. **Desktop live views: JCEF via jcefmaven** (accepted).
4. **Generic foundation, no specific consumer.** ComfyUI and Penpot are illustrations only; one trivial reference plugin exercises every contract feature for tests and docs.

Inputs and verified facts are those of revision 1 (section 2) plus the prior-art report `docs/design/canvas-plugin-platform-prior-art.md`.

---

## 1. Summary

A **plugin** is a package (`<id>-<version>.lcp`, a zip) holding a **manifest** (`letta-plugin.json`) and one of three runtimes:

| Runtime | What is in the package | Who runs it | For |
|---|---|---|---|
| `jvm` | a jar implementing the Kotlin SPI from `:plugin-api` | the host, in-process, in an isolated class loader | first-party and consented plugins written in Kotlin/Java |
| `process` | a command (any language) speaking the **Letta Canvas Plugin wire protocol** (LCP wire v1, JSON-RPC 2.0 over newline-delimited stdio) | the host, as a supervised subprocess | plugins in Node/Python/Rust/…; untrusted code |
| `service` | nothing — the manifest names a WebSocket URL speaking the same wire protocol | wherever the service runs (LAN, same box) | long-lived integrations, containers |

The manifest declares the plugin's **element kinds** (versioned props schemas), **actions** (become agent tools beside `canvas_*`), **settings and secrets**, **capabilities**, a **network allowlist**, and optional **pages** (HTML live views). All three runtimes present the same interface to the host: a `PluginDriver`. The SPI is the canonical interface; the wire protocol is its serialisation.

On the canvas, plugin content is a **plugin element** in the `_pluginElements` collection (revision 1, unchanged: `ext:<pluginId>/<kind>`, split `_frame`/`_state` provenance, snapshot asset, host-renderable fallback card). Plugins never write the op log; they **emit** placements and state updates that the host turns into `set_plugin_element` ops through the one validator and the one op log.

The host (Iroh wrapper; the desktop app in direct App-Server mode) holds a **live plugin registry**: installs, updates, enables, disables and removes plugins at runtime, re-advertises agent tools, and exposes a **management API** over the existing Iroh admin channel that the clients' **settings UI** uses (install from a file, consent, settings, secrets, health). Live views are HTML pages from the package rendered in a sandbox (Android WebView, desktop JCEF, wasm iframe later) through our own **view bridge** (`lcp-view/1`, JSON-RPC over postMessage, a small fixed method set, CSP from the manifest).

```
Agent (letta-code) --external_tool_call_request--> Host (iroh-wrapper | desktop direct mode)
                                                     |- ExternalToolRegistry (LIVE): canvas_* + <plugin>_<action> tools
                                                     |- PluginRegistry: packages, manifests, consent, settings, secrets, health   <--admin_rpc plugins.*-- Settings UI
                                                     |- PluginDrivers: InProcessJar | Process(stdio) | Service(ws)
                                                     |- emits -> set_plugin_element ops -> CanvasBatchValidator -> relay op log
                                                     '- AssetStore (snapshots), page files (served to clients)
Clients (Android / Desktop / wasm) <--Iroh--> Host
   CanvasWorkspace: DrawBox layer | Notes layer | Plugin layer (fallback card always; live page optional via PluginViewHost + ViewBridge)
```

The core modules gain only generic mechanisms (an element collection, a renderer registry, a plugin registry and drivers, a management API, a view bridge); no plugin and no integration is named in the core. The reference plugin lives in `plugins/example/` outside the core modules and is consumed only by tests and docs.

---

## 2. What exists today and what it dictates

Unchanged from revision 1 (the table there is still accurate). The rows that matter most for this revision:

- `ExternalToolRegistry` is immutable after construction and advertised on `runtime_start`; `reRegisterAll` is a no-op seam kept for a future protocol; the reconnect coordinator re-issues `runtime_start` per active runtime. Hot-loading needs a live registry and a re-advertisement path (section 6.3, decision R2).
- The Iroh App Server connection already carries a generic `admin_rpc` (method, path, body) on the control stream, routed by `AdminRpcRouter`/`AdminRpcRegistry` on the host, gated by `IrohAuthPolicy` (token, allowed peers, pairing). The management API rides on it.
- The canvas relay already moves content-addressed blobs (`AssetPut/AssetGet`, 1 MiB chunks, 50 MiB cap, sha256-verified). Plugin packages are uploaded the same way.
- Production host: Linux/systemd, JVM 21, Clikt CLI, spawns a child process already. The JVM has no `SecurityManager` since JDK 24 (deprecated for removal in 17): in-process isolation is class loaders, threads and resource caps, not a sandbox (decision R1).
- Desktop already depends on `me.friwi:jcefmaven` 146.0.10; Android has a sandboxed offline WebView precedent (`MathBlock`); `:sharedUI` may depend only on `:sharedLogic`; KMP modules may depend only on KMP modules.

---

## 3. The plugin contract (LCP v1)

### 3.1 Package and manifest

`<id>-<version>.lcp` = zip with `letta-plugin.json` at the root plus the runtime's files (`plugin.jar`, or the process files, or nothing for a service) and `pages/*.html`. Max 50 MiB (the relay cap). Strict manifest schema (JSON-pointer refusals, `additionalProperties: false`, the data/schema checker from bead .2):

```json
{
  "manifestVersion": 1,
  "id": "letta.example",
  "name": "Example",
  "version": "1.2.0",
  "publisher": "oculairmedia",
  "contract": { "version": 1 },
  "runtime": { "kind": "jvm", "jar": "plugin.jar", "entry": "com.example.ExamplePlugin" },
  "settings": {
    "baseUrl": { "type": "string", "format": "uri", "required": true, "label": "Service URL" },
    "quality": { "type": "integer", "minimum": 1, "maximum": 100, "default": 80 }
  },
  "secrets": [ { "name": "apiToken", "label": "API token", "required": false } ],
  "capabilities": ["canvas:place", "canvas:read", "assets:write", "net:connect", "ui:pages"],
  "net": { "connect": ["https://api.example.test", "ws://127.0.0.1:8188"] },
  "elements": {
    "widget": {
      "schemaVersion": 2,
      "props": { "type": "object", "additionalProperties": false, "properties": {
        "status": { "enum": ["idle", "running", "done", "failed"] },
        "progress": { "type": "number", "minimum": 0, "maximum": 1 },
        "label": { "type": "string", "maxLength": 128 } } },
      "defaultSize": { "width": 320, "height": 240 },
      "page": "widget",
      "migrations": { "1": [ { "rename": ["pct", "progress"] }, { "default": ["status", "idle"] } ] }
    }
  },
  "actions": {
    "start": { "description": "Start a job and place a widget", "visibility": ["agent", "view"],
               "input": { "type": "object", "additionalProperties": false, "required": ["label"],
                          "properties": { "label": { "type": "string", "maxLength": 128 } } } },
    "cancel": { "description": "Cancel a job", "visibility": ["agent", "view"],
                "input": { "type": "object", "additionalProperties": false, "properties": { "elementId": { "type": "string" } } } },
    "refresh": { "visibility": ["view"], "input": { "type": "object", "additionalProperties": false, "properties": {} } }
  },
  "pages": {
    "widget": { "html": "pages/widget.html",
                "csp": { "connectDomains": [], "resourceDomains": [], "frameDomains": [] },
                "permissions": [], "displayModes": ["inline", "fullscreen"] }
  }
}
```

Alternative runtimes:
```json
"runtime": { "kind": "process", "command": "node", "args": ["server.js"], "cwd": ".", "env": { "EXAMPLE_URL": "${settings.baseUrl}", "EXAMPLE_TOKEN": "${secrets.apiToken}" } }
"runtime": { "kind": "service", "url": "ws://127.0.0.1:7788/lcp", "headers": { "Authorization": "Bearer ${secrets.apiToken}" } }
```

Rules: `id` is `<publisher>.<name>` lowercase (`[a-z0-9]+(\.[a-z0-9]+)+`); element types are `ext:<id>/<kind>`; agent tool names are `<idShort>_<action>` where `idShort` is the last id segment (the host refuses collisions at install); `${settings.*}` and `${secrets.*}` are the only substitutions and are resolved by the host only; `props` schemas are closed objects of scalars/short strings (≤ 4 KiB serialised); `capabilities` is a closed enum (3.3); `migrations` are declarative only (`rename`, `default`, `drop`) keyed by the *from* version; `contract.version` must be one the host supports; every page's `html` is inside the package.

### 3.2 Kotlin SPI (`:plugin-api`, KMP module with a jvm target, no project dependencies, published as an artifact)

```kotlin
interface CanvasPlugin {
    fun activate(host: PluginHost)                         // once per load; may start work
    suspend fun invoke(call: ActionCall): ActionResult     // actions from agents and views
    suspend fun onElementEvent(event: ElementEvent) {}     // moved | removed | focused | viewOpened | viewClosed
    fun health(): PluginHealth                             // Ok | Degraded(reason) | Failed(reason)
    fun deactivate()                                       // stop work; the loader unloads the class loader after
}
interface PluginHost {
    val pluginId: String; val contractVersion: Int
    val settings: JsonObject                               // resolved, validated against the manifest
    fun secret(name: String): String?                      // resolved on the host; never logged by the host
    suspend fun emit(emit: PluginEmit): EmitReceipt        // placements/updates/removals -> validator -> op log
    suspend fun putAsset(mediaType: String, bytes: ByteArray): String   // sha256 ref, needs assets:write
    suspend fun readElements(query: ElementQuery): List<PluginElementView>  // needs canvas:read; own namespace + ids/frames of others
    fun log(level: LogLevel, message: String, fields: Map<String, String> = emptyMap())
    val scope: CoroutineScope                              // cancelled on deactivate; bounded dispatcher
}
data class ActionCall(val action: String, val input: JsonObject, val context: ActionContext)
data class ActionContext(val origin: Origin /* Agent(agentId, conversationId, toolCallId) | View(elementId, peerId) | Host(reason) */, val canvasId: String?, val elementId: String?)
sealed interface ActionResult { data class Ok(val text: String, val structured: JsonObject? = null, val emit: PluginEmit? = null) ; data class Error(val code: String, val message: String) }
data class PluginEmit(val place: List<PlaceElement> = emptyList(), val update: List<UpdateElement> = emptyList(), val remove: List<String> = emptyList(), val placeImages: List<PlaceImage> = emptyList())
```

`PlaceElement(kind, v, ref, props, fallback, frame?, snapshot: SnapshotSource?)` where `SnapshotSource` is `Asset(ref)` or `Bytes(mediaType, bytes)` (the host stores it). `UpdateElement(elementId, props?, fallback?, snapshot?, ref?)` touches only the `_state` group. `PlaceImage(bytes/ref, frame?, provenance)` places a **core `Image` element** with `_plugin` provenance (needs `assets:write`), so plugin outputs survive without the plugin.

Versioning: `:plugin-api` is semver'd; `contract.version` in the manifest = its major. The host supports a range and refuses a package outside it at install with a clear message.

### 3.3 Capabilities and consent

| Capability | Grants | Consent |
|---|---|---|
| `canvas:place` | `emit` may create/update/remove elements of the plugin's own kinds | install |
| `canvas:read` | `readElements` (own elements in full; ids/types/frames of others) | install |
| `assets:write` | `putAsset`, `SnapshotSource.Bytes`, `placeImages` | install |
| `net:connect` | the declared `net.connect` origins (enforced for `jvm` through the host-provided HTTP client in `PluginHost` — a jar that brings its own client is a policy violation surfaced by review; recorded and shown for `process`/`service`, decision R1) | install, list shown |
| `ui:pages` | the package ships pages | install |
| `ui:openLink` | a page may ask the host to open an external link | first use (client prompt) |
| `ui:clipboardWrite`, `ui:camera`, `ui:microphone`, `ui:geolocation` | page permissions | first use (client prompt), never without the OS permission |

Consent for install-time capabilities and the allowlist is recorded on the host (per plugin version; an update that adds capabilities or origins asks again). Page permissions are per device.

### 3.4 Secrets and settings

- Settings: validated against the manifest schema, stored on the host in `~/.letta/plugins/<id>/settings.json`, editable from the UI (`plugins.settings.set`), delivered to the plugin at activation and on change (`host.settingsChanged`).
- Secrets: entered in the settings UI, sent over the Iroh channel (QUIC/TLS) with `plugins.secrets.set`, stored on the host in `~/.letta/plugins/secrets/<id>.json` (mode 600; the same discipline as the Iroh secret-key file), never returned (the API answers only "set"/"unset"), injected through `PluginHost.secret`, `process` env or `service` headers; the host scrubs every plugin output (results, logs, emits) for secret values and fails closed. Never in URLs. Never to clients. Pages get no secrets; a page acts through actions.

---

## 4. Canvas element model

**Unchanged from revision 1 (beads .1–.5).** For reference: `_pluginElements` root collection; `set_plugin_element`/`remove_plugin_element`; `type: ext:<pluginId>/<kind>`, `v`, `frame`+`owner`, `ref`, flat `props`, `snapshot{assetRef…}`, `fallback{title, subtitle, icon, openUrl}`, `meta{pluginId, createdBy, toolCallId}`; split `_frame`/`_state` provenance; envelope validation in `CanvasSceneValidator`, kind props validated through the lifted JSON-Schema checker and `PluginKindCatalog`, unknown kinds accepted with a WARN; `CanvasPluginLayer`, `PluginElementRenderers`, `PluginFallbackCard`; snapshots as assets; fixtures under `commonTest/resources/canvas/plugin/v1/`.

Two notes for the new contract (no change to .1's scope):
- Plugin-originated state writes (freshness emits with no agent in the loop) are stamped `actorId = "plugin:<pluginId>"`; the projector treats actor ids as opaque strings. The canvas ACL gains a `writerPluginIds` entry in bead .26 (host integration), added when an agent that may write first places an element of that plugin.
- `PluginKindCatalog` on the host is backed by the live registry (section 6); clients get kinds from `plugins.list`.

---

## 5. Out-of-process wire protocol (LCP wire v1)

JSON-RPC 2.0, one message per line (NDJSON) on stdin/stdout for `process`, text frames on a WebSocket for `service`. UTF-8, ≤ 4 MiB per message (assets go through `host.putAsset` in ≤ 1 MiB base64 chunks). The method set mirrors the SPI one-to-one:

Host → plugin (requests unless noted):
- `plugin.initialize { contractVersion, pluginId, settings, hostInfo }` → `{ ok, pluginInfo }`
- `plugin.activate {}` → `{}`; `plugin.deactivate {}` → `{}`
- `plugin.health {}` → `{ status: ok|degraded|failed, reason? }`
- `action.invoke { action, input, context }` → `{ text, structured?, emit? }` or JSON-RPC error `{ code, message, data.code }`
- `element.event { kind, elementId, event, frame? }` (notification)
- `host.settingsChanged { settings }` (notification)

Plugin → host:
- `host.emit { place, update, remove, placeImages }` → `{ receipt: { placed: [ids], refused: [{index, reason}] } }`
- `host.putAsset.begin { mediaType, byteSize }` → `{ uploadId }`; `host.putAsset.chunk { uploadId, index, base64 }` → `{}`; `host.putAsset.end { uploadId, sha256 }` → `{ ref }`
- `host.readElements { query }` → `{ elements }`
- `host.log { level, message, fields }` (notification)

Process hygiene: the host writes nothing but JSON to stdin; a plugin must write nothing but JSON to stdout (stderr is captured to telemetry at WARN, scrubbed); deadlines: `initialize` 10 s, `activate` 30 s, `action.invoke` 100 s (inside the dispatcher's 120 s), `health` 5 s. Secrets reach a `process` plugin only through env (never through the protocol) and a `service` plugin only through connection headers.

The protocol is documented for third parties in `docs/reference/canvas-plugin-wire-v1.md` with the fixtures in `commonTest/resources/canvas/plugin/v1/wire/*.jsonl` (recorded sessions: initialize/activate/invoke/emit/putAsset/health/deactivate, plus malformed-message cases) that the host's codec and the reference plugin both replay.

---

## 6. Host: registry, drivers, hot-loading, tools

### 6.1 Registry (`sharedLogic/commonMain/data/plugin/`)

`PluginRegistry` is a live model: `StateFlow<List<InstalledPlugin>>` where `InstalledPlugin(manifest, version, state: Installed|Enabled|Active|Faulted(reason)|Updating, consent, settingsSummary, secretsSet, health, installedAt, packageSha256)`. Persistence `~/.letta/plugins/plugins.json` + `~/.letta/plugins/<id>/<version>/` (package contents) + settings + secrets as in 3.4. Pure transitions (install, update with rollback on failure, enable, disable, remove) are commonMain functions with tests; file I/O is jvmMain.

### 6.2 Drivers (`sharedLogic/jvmMain/data/plugin/runtime/`)

`interface PluginDriver { suspend fun start(); suspend fun invoke(call): ActionResult; suspend fun event(e); suspend fun health(); suspend fun stop() }` with three implementations:

- `InProcessJarDriver`: a child-first `URLClassLoader` over the package jar whose parent exposes only `:plugin-api`, the Kotlin stdlib, coroutines and serialization (an allowlisting parent loader); the entry class is instantiated reflectively and must implement `CanvasPlugin`; every call runs on the plugin's own bounded dispatcher (`Dispatchers.IO.limitedParallelism(4)`) under a deadline; uncaught throwables mark the plugin `Faulted`; `deactivate` cancels the scope, closes the loader and clears references so the jar can be replaced (hot update); `PluginHost.httpClient` is the host's Ktor client wrapped with the allowlist.
- `ProcessDriver`: `ProcessBuilder` with env from substitution, cwd = package dir, NDJSON codec over stdin/stdout, stderr to telemetry, kill tree on stop, restart with backoff.
- `ServiceDriver`: Ktor WebSocket client to the URL with headers, same codec, reconnect with backoff.

Lifecycle for all: `Installed → Enabled (advertised) → Active (on first action/event, or at enable for jvm) → idle stop after 10 min for process/service (jvm stays loaded) → crash: restart 1 s/5 s/30 s then `Faulted` (actions answer with a clear error, elements stay as fallback cards) → `Disabled`/`Removed` (tools withdrawn, elements stay).

Isolation levels (decision R1): `jvm` = class loader + thread/deadline caps + consent (it shares the host process; a hostile jar is not contained); `process` = OS process (recommended for untrusted code; the host shows which runtime a package uses in the consent dialog); `service` = not on the host at all.

### 6.3 Actions as agent tools, and emits as ops (`PluginHostIntegration`)

- Every enabled plugin's actions with `visibility` containing `"agent"` are `HostExternalTool`s named `<idShort>_<action>`, description prefixed with the plugin name, `inputSchema` from the manifest. `invoke` → `driver.invoke(ActionCall(origin = Agent(...)))` → text for the agent (+ a receipt line for any emit) → scrubbed.
- `ExternalToolRegistry` becomes **live**: it takes a `ToolSource` (`StateFlow<List<ExternalTool>>`) beside the fixed list; `advertisedToolsCommandGroups()` reads the current set; the dispatcher answers calls for a tool that vanished mid-flight with an error. **Re-advertisement**: the registry raises `toolsChanged`; the controller re-issues `runtime_start` for each active runtime (the same frame the reconnect coordinator already re-issues). Whether letta-code accepts a repeated `runtime_start` for a live runtime is **unverified** → bead .27 starts with a spike; the fallback is that changed tools appear at the next runtime start (new conversation/turn boundary), which the settings UI states (decision R2).
- `emit` (from an action result, `host.emit`, or `PluginHost.emit`) → `PlacementCompiler` (commonMain, pure): capability check, namespace check (`ext:<pluginId>/*` only; `placeImages` → core `Image` elements with `_plugin` provenance), snapshot bytes → `AssetStore`, missing frame → `CanvasComposePlacement` origin/grid with `defaultSize` (owner EXPLICIT), throttle (≤ 1 state write/s per element, coalesced), → one atomic `BatchOp` through the **same publisher as `canvas_apply_ops`** (`HostCanvasBackend.publish` on the Iroh host; `CanvasSession.applyAgentBatch`/store-at-revision on the desktop) with actor `agent:<agentId>` when an agent's call caused it, else `plugin:<pluginId>`. Target canvas: the calling agent's conversation canvas, or for host-origin emits the canvas the element lives on (an emit must name `elementId` or `canvasId` then). A refused emit is reported in the receipt, never fails the action.
- Element events: the host forwards `moved/removed/focused/viewOpened/viewClosed` for a plugin's own elements (from the relay op stream and from view lifecycle) so a plugin can stop refreshing a removed element.

### 6.4 Management API (over the existing Iroh `admin_rpc`; desktop direct mode calls the same functions in-process)

Methods (`plugins.*`), authorised for peers the `IrohAuthPolicy` admits as owners (paired/authenticated; the same gate as the other admin RPCs):

| Method | Params → result |
|---|---|
| `plugins.list` | → `[InstalledPlugin summary]` (no secrets, no settings values marked `secret`) |
| `plugins.inspect` | `{packageRef}` → validated manifest summary, requested capabilities, origins, runtime kind, contract compatibility, warnings (used before install for the consent screen) |
| `plugins.install` | `{packageRef, consent: {capabilities, origins}}` → `{pluginId, version}` (the package was uploaded first as a relay asset: `AssetPut` → `sha256:` ref) |
| `plugins.update` | `{pluginId, packageRef, consent?}` → `{version}` (new version staged, old kept for rollback; drivers restarted; tools re-advertised) |
| `plugins.enable` / `plugins.disable` / `plugins.remove` | `{pluginId}` → `{}` (remove keeps elements; settings/secrets deleted on remove) |
| `plugins.settings.get` / `plugins.settings.set` | `{pluginId}` / `{pluginId, settings}` → validated settings (pointer-path refusals) |
| `plugins.secrets.set` / `plugins.secrets.clear` / `plugins.secrets.status` | `{pluginId, name, value}` → `{}`; status → `{name → set|unset}` |
| `plugins.health` | `{pluginId?}` → health per plugin |
| `plugins.events.subscribe` | → a stream of registry changes (install/update/state/health) on the existing App Server event fan-out (`RuntimeEventFanout`) so the settings UI is live |

Package upload uses the relay's asset path (chunked, sha256-verified, 50 MiB cap) on a management topic (`plugins:<hostNodeId>`), so no new transport is needed. Audit: every management call is logged with the peer id.

---

## 7. Client: settings UI, view bridge, hosting

### 7.1 Settings UI (`sharedUI/commonMain/ui/settings/plugins/`)

A Plugins pane in the settings route (Android and desktop bind it; desktop direct mode uses the in-process host): list with state/health badges; **Install** = pick a `.lcp` file (FileKit, already a sharedUI dependency) → upload → `plugins.inspect` → consent screen (capabilities, origins, runtime kind with its isolation note, contract version) → `plugins.install`; per plugin: enable/disable/remove/update (pick a file), settings form generated from the manifest schema (string/integer/number/boolean/enum, required, defaults, labels), secrets fields (write-only, show set/unset), health and last fault, "elements on boards" count. Everything through a `PluginManagementClient` interface (commonMain) with the Iroh `admin_rpc` implementation and a loopback implementation.

### 7.2 View bridge (`lcp-view/1`, `sharedLogic/commonMain/data/plugin/view/ViewBridge.kt`)

Our own JSON-RPC 2.0 over postMessage, fixed method set:

View → host: `view.ready { pageId, viewVersion }` → `host.context`; `view.action { action, input }` → `{ text, structured }` (only actions with `visibility` containing `"view"`, of the view's own plugin, with `ActionContext.origin = View(elementId, peerId)`); `view.resize { width, height }` (notification; host may grow the frame while owner ≠ USER); `view.displayMode { mode }` → `{ mode }`; `view.openLink { url }` → `{}` (needs consent); `view.log { level, message }`.

Host → view: `host.context { theme, cssVariables, element: {id, type, v, frame, props, snapshot, fallback}, settingsPublic, displayMode, locale, platform, safeArea }` (sent after `view.ready` and on change), `host.element.changed { element }`, `host.teardown { reason }` → `{}` (awaited, 2 s).

The bridge validates every message (shape, known method, params via the schema checker, id correlation, 256 KiB cap, 20 req/s), audits to telemetry, builds the CSP string from the page's manifest `csp` over a default-deny base (`default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: <resourceDomains>; connect-src <connectDomains>; frame-src <frameDomains>; base-uri 'none'`) and gates permissions by consent. `interface PostMessagePort` and `interface PluginViewTransport { suspend fun readPage(pluginId, version, pageId): ByteArray; suspend fun action(...) }` abstract the platform and the transport. The page HTML is served by the host over a new ALPN `meridian/plugin-view/1` (same framing and auth gate as the canvas relay; authorises `readPage` for enabled plugins and `view.action` against the peer's readable canvases) and cached on the client by `(pluginId, version, pageId)`.

A 1 KB shim injected before the page gives it `window.lettaView.send(json)` / `window.addEventListener("message", …)` on every platform, so a page author sees one API.

### 7.3 Hosting (`:plugin-view`, KMP android + jvm; wasmJs later)

Unchanged in substance from revision 1 with the bridge swapped: Android `WebView` (JS on, no file/content access, no DOM storage, request interception enforcing the allowlists, CSP meta injected, opaque origin via `loadDataWithBaseURL(null, …)`, `addJavascriptInterface` + `evaluateJavascript`); desktop **JCEF via jcefmaven** (one `CefApp`, one `CefClient` per plugin with an isolated request context, custom scheme `letta-plugin://<id>/<page>` with real CSP headers, `CefMessageRouter`, request handler enforcing the allowlists, popups/downloads refused; spike first); wasm double iframe later. `PluginViewHost` interface in sharedUI; shells register.

### 7.4 Live-view integration

As revision 1: card → page upgrade when the renderer is registered, the plugin is enabled on the host, the transport is available and the element is focused/expanded (or always-live preference); display modes inline/fullscreen; `view.resize` grows the frame only while owner ≠ USER; offline badge; teardown on collapse/scroll-out/close; ≤ 4 live views per board; consent prompts per device.

---

## 8. Lifecycle, versioning, degrade, offline, security

- **Versioning**: `contract.version` (host range), plugin `version` (semver; update = install a newer version with rollback on failed activation), per-kind `schemaVersion` with declarative migrations applied on read by the host (into actions) and by clients (for rendering) — the stored element keeps its `v`.
- **Missing/disabled plugin**: fallback card with "not installed"/"disabled" subtitle; elements movable and deletable; agents lack the tools; `canvas_apply_ops` still accepts the ops (envelope).
- **Offline**: card from local scene + local assets; no live page; emits made on the host arrive by relay catch-up.
- **Security**: no plugin code on clients; pages sandboxed with CSP, allowlists, opaque/per-plugin origins, no cookies/storage, no secrets; actions from a view limited to the view's own plugin and canvas; management API owner-only and audited; secrets host-only, scrubbed; `jvm` plugins are consented code with class-loader/thread isolation only (R1); `process` for untrusted; resource caps (message sizes, rates, 8 MiB snapshots, 4 KiB props, 1 write/s per element, 4 live views).

---

## 9. What changed vs revision 1, and why

| Revision 1 | Revision 2 | Why |
|---|---|---|
| Plugins = MCP servers; host = MCP client (Kotlin MCP SDK) | **Own contract**: Kotlin SPI (`:plugin-api`) for in-process jars + LCP wire v1 (JSON-RPC NDJSON/WebSocket) for processes and services, one `PluginDriver` abstraction | Emmanuel: no MCP as the foundation; MCP may become one optional adapter plugin later |
| MCP Apps verbatim for views (`ui://`, `_meta.ui`, `ui/*`) | **`lcp-view/1`**: our own small JSON-RPC method set; pages in the package; CSP from the manifest (shape borrowed from MCP Apps/Figma) | same |
| Install by CLI + config files; host restart; dynamic registry optional/later | **Settings UI install from day one**: live registry, hot add/update/enable/disable/remove, management API over `admin_rpc`, package upload via the relay asset path, secrets and consent in the UI | Emmanuel decision 2 |
| ComfyUI and Penpot reference plugins in their own repos | **One generic reference plugin** (`plugins/example/`, both `jvm` and `process` forms) exercising every contract feature; ComfyUI/Penpot are illustrations only | Emmanuel decision 4 |
| Group A element model | **Unchanged** | protocol-independent |
| Desktop JCEF via jcefmaven | Unchanged, accepted | decision 3 |

---

## 10. Remaining decisions for Emmanuel (minimal)

| # | Decision | Recommendation |
|---|---|---|
| R1 | **In-process jar isolation level.** The JVM has no sandbox any more (no `SecurityManager`); a `jvm` plugin is isolated by class loader, bounded threads and deadlines, and its network goes through the host's allowlisted client only by convention. Treat `jvm` as *consented code* (first-party/self-written), and route untrusted code to the `process` runtime (OS isolation), shown in the consent dialog. | **Accept.** A real sandbox would mean WASM or a separate JVM per plugin; both are later options behind the same `PluginDriver`. |
| R2 | **Tool re-advertisement latency.** If letta-code does not accept a repeated `runtime_start` for a live runtime (spike in bead .27), newly installed/removed plugin tools take effect at the next runtime start (next conversation turn boundary), and the settings UI says so. | **Accept** the fallback if the spike fails; no protocol change on the letta-code side. |
| R3 | **Package format and upload path**: `.lcp` zip (manifest + jar/files + pages, ≤ 50 MiB) uploaded through the existing relay asset path, installed by `sha256` ref. | **Accept.** Reuses verified, content-addressed transport; no new ALPN for management. |
| R4 | **`service` runtime in v1?** (WebSocket URL, plugin runs elsewhere.) It is small on top of `process` (same codec) and is the natural home for container-hosted integrations. | **Include** (bead .25), behind the same consent dialog. |

Signing (previous D7) stays deferred (bead .23); wasm live views (previous D10) stay deferred (bead .16).

---

## 11. Bead plan (children of letta-mobile-s416w)

Each bead is one Opus session. Standing rules apply (feature logic in `sharedLogic/commonMain`, UI in `sharedUI`, platform modules bind; no plugin names in the core; tests for everything; PR fully green incl. CodeScene; world units; one op log, one validator; storage failures loud and non-fatal).

**Group A — element model and fallback (unchanged; .1 in progress)**: `.1` model/ops/projector/envelope → [`.2` kind schemas + `PluginKindCatalog`, `.3` storage/session/undo, `.4` `CanvasPluginLayer` + registry + `PluginFallbackCard`, `.5` tool contract parity + relay/asset E2E] parallel after .1.

**Group B — contract and host runtime**
- `.24` Plugin contract and manifest model (commonMain): package layout, strict manifest schema, capabilities, settings/secrets substitution + scrubber, migrations, registry state transitions (install/update/rollback/enable/disable/remove), kind catalog over the registry, fixtures. *(parallel with A2–A5; needs .2's checker — define the dependency, or vendor the call behind an interface until .2 lands)*
- `.25` LCP wire protocol v1 (commonMain): JSON-RPC method set, NDJSON + WebSocket codecs, limits, chunked asset upload, recorded fixtures, `docs/reference/canvas-plugin-wire-v1.md`. *(after .24; parallel with .26/.28)*
- `.26` `:plugin-api` module + Kotlin SPI + conformance kit (a test harness any jar plugin can run). *(after .24; parallel with .25)*
- `.27` Live `ExternalToolRegistry` and re-advertisement (spike: repeated `runtime_start`), dispatcher behaviour for vanished tools. *(parallel with .24–.26; depends on nothing in this epic; R2)*
- `.28` Plugin drivers and runtime (jvmMain): `PluginDriver`, `InProcessJarDriver` (isolated loader, bounded dispatcher, hot unload), `ProcessDriver`, `ServiceDriver`, lifecycle/health/backoff/faulted, secrets store and injection. *(after .25, .26; R1, R4)*
- `.29` Host integration: actions → live `HostExternalTool`s, `PlacementCompiler` (emits → ops through the one validator/publisher on both hosts), assets, element events, throttle, ACL `writerPluginIds`, Iroh wrapper + desktop direct-mode binding. *(after .1, .5, .27, .28)*
- `.30` Management API over `admin_rpc` + host install pipeline: `plugins.*` methods, package upload via relay assets, inspect/consent/install/update/rollback/enable/disable/remove/settings/secrets/health/events, owner-only authorisation, audit. *(after .24, .28; parallel with .29)*

**Group C — views**
- `.31` `ViewBridge` (`lcp-view/1`, commonMain): method set, validation/rate/audit, CSP builder, `PostMessagePort`/`PluginViewTransport` interfaces, handshake fixtures. *(after .24; parallel with B)*
- `.32` `meridian/plugin-view/1` ALPN: page serving + view actions with auth, client transport following the App Server connection, loopback for desktop direct mode. *(after .28, .31)*
- `.13` `:plugin-view` module + `PluginViewHost` interface in sharedUI + Android WebView host *(rewritten: uses `ViewBridge`; after .4, .31)*
- `.14` Desktop JCEF host *(rewritten: uses `ViewBridge`; after .4, .31; starts with the spike)*
- `.15` Live-view integration on the canvas *(rewritten; after .4, .32, and .13 or .14)*
- `.16` wasm live views *(rewritten; after .31, o4ygk.4; deferred)*

**Group D — settings UI and reference plugin (parallel with C)**
- `.33` Plugin settings UI: list/install (file pick + upload + inspect + consent)/update/enable/disable/remove/settings form/secrets/health, `PluginManagementClient` (Iroh + loopback), Android and desktop binding. *(after .30)*
- `.34` Reference plugin `letta.example` in `plugins/example/` (outside the core): a `jvm` form and a `process` form (Node) exercising every contract feature; host E2E tests on both hosts; `docs/reference/canvas-plugin-authoring.md`. *(after .29, .32 for pages)*

**Group E — later**
- `.21` compose v2 `PLUGIN` kinds *(after .1, .4, .29)*
- `.23` signing / trust model *(deferred)*
- `.35` MCP adapter as an optional plugin (one `process` plugin that bridges an MCP server's tools to actions) *(after .34; only if wanted)*

Closed as superseded: `.6`–`.12` (MCP manifest/runtime/bridge/CLI/rpc/dynamic-registry/MCP Apps bridge), `.17`–`.20` (ComfyUI/Penpot), `.22` (replaced by .33).

---

## 12. Illustrations (not designs to build): how ComfyUI and Penpot would use the contract

- A **ComfyUI** plugin would be a `process` plugin (Node) with settings `baseUrl`, secret `authHeader`, actions `run_workflow({workflowId, params})`/`get_job`/`cancel`, a `job` kind (`status, progress, workflowId, seed`), a `job` page, and it would `host.emit` state updates from ComfyUI's WebSocket and `placeImages` for outputs. Everything it needs exists in the contract: `emit`, `putAsset`, `placeImages`, `net.connect`, pages.
- A **Penpot** plugin would be a `jvm` or `process` plugin with settings `baseUrl`, secret `accessToken`, actions `list_files/list_pages/list_frames/place_frame/refresh`, a `frame` kind (`name, revn, format`), snapshots exported through Penpot's exporter, and a periodic `host.emit` update when `revn` changes.

Neither is a bead. The reference plugin (`.34`) covers the same contract surface with a trivial "widget" that counts, places images from generated bytes, and serves a page.
