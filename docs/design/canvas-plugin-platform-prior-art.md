# Canvas Plugin Platform: Prior Art Research

Epic: letta-mobile-s416w. Date of research: 2026-10-02. Status: research input for the architect, not a decision.

Conventions: every factual claim carries a URL. **[V]** = verified by fetching the cited page this session. **[S]** = taken from a search-result snippet only. **[U]** = unverified (general knowledge or inference; check before relying on it). Section 5 and 6 are our own design proposals, not facts about third parties.

Note on method: page fetches were summarised by a small model, so quoted API names are reliable but nuance may be lost. Several pages (Figma missing-plugin behaviour, Excalidraw libraries/embeddables detail, JetBrains, Heptabase, Muse, Freeform, ComfyUI payload details, Penpot RPC schema) could not be verified and are marked.

---

## 1. Executive summary: top 10 takeaways

1. **MCP Apps is the standard for "tool provider ships UI without the host shipping their code."** A tool declares `_meta.ui.resourceUri` pointing at a `ui://` resource with MIME `text/html;profile=mcp-app`; the host renders it in a sandboxed iframe and talks JSON-RPC over `postMessage`. Supported by Claude, Claude Desktop, VS Code Copilot, Goose, Postman and others. [V] https://modelcontextprotocol.io/extensions/apps/overview , spec: https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx
   **Recommendation:** adopt the MCP Apps wire format verbatim for plugin UI; do not invent a Letta-specific plugin UI protocol.

2. **The spec mandates graceful degradation, which is exactly our "plugin absent" requirement.** Servers must provide text-only fallbacks for every UI tool; the host negotiates via `capabilities.extensions["io.modelcontextprotocol/ui"].mimeTypes`. [V] (ext-apps spec above).
   **Recommendation:** every plugin element must carry a host-renderable fallback (title, snapshot asset, link) independent of any plugin code.

3. **Plugins as MCP servers on the host side fits our topology.** The Iroh host already sits next to the App Server; MCP servers are the agent-tool standard, give us the agent-drives-plugin story for free, and keep third-party code off Android. MCP also defines resources, `resources/subscribe` and `notifications/resources/updated`, which map to "keep it fresh." [V] https://modelcontextprotocol.io/specification/2025-11-25/server/resources
   **Recommendation:** a plugin = an MCP server (run by the host) + a manifest + optional `ui://` resources.

4. **Android cannot run third-party native/JVM plugin code, but JS in a WebView is explicitly exempt.** Play policy bans downloading dex/JAR/.so, but excludes code that "runs in a virtual machine or an interpreter" with indirect API access, e.g. JavaScript in a WebView; interpreted code still must not violate Play policy. [V] https://support.google.com/googleplay/android-developer/answer/9888379
   **Recommendation:** client-side plugin UI = sandboxed WebView (Android), CEF/WebView2 (desktop), iframe (wasm); never dex.

5. **Everyone who has a sandboxed-UI plugin model splits "document access" from "UI/network".** Figma: sandbox with scene access and no `fetch`/DOM; iframe with browser APIs and no scene; message passing between. [V] https://developers.figma.com/docs/plugins/how-plugins-run/ . MCP Apps: iframe with no host DOM, host-brokered `tools/call`. Penpot: iframe plugins with permission-gated API. [V] https://help.penpot.app/plugins/getting-started/
   **Recommendation:** plugin UI never touches the CRDT directly; it only gets data via host-brokered calls, and writes only via validated canvas ops.

6. **Declare permissions and network access in a manifest and show them at install.** Figma `networkAccess.allowedDomains` + required `reasoning` for wildcards [V] https://developers.figma.com/docs/plugins/manifest/ ; Penpot `permissions` (`content:read/write`, `allow:downloads`, ...) [V] https://help.penpot.app/plugins/getting-started/ ; MCP Apps `_meta.ui.csp` (`connectDomains`, `resourceDomains`, `frameDomains`) default-deny [V].
   **Recommendation:** manifest declares CSP-style domain lists + capability scopes; host enforces and user consents.

7. **Tldraw's model is the best "custom shape" blueprint, but its licence is not open source.** Shapes are typed records with validated `props`, a `ShapeUtil` registry, `meta`, migrations; each shape renders in its own error boundary. [V] https://tldraw.dev/docs/shapes , https://tldraw.dev/sdk-features/errors [S]. The SDK is "source available", needs a license key in production and is not OSI-open. [V] https://tldraw.dev/community/license
   **Recommendation:** copy the architecture (namespaced type + versioned props + renderer registry + error boundary), do not take the dependency.

8. **Store a reference plus a cached snapshot, never the payload.** Miro app cards: preview fields + an iframe detail view hosted by the app, with a `status` sync property. [V] https://developers.miro.com/docs/app-card . JSON Canvas `file`/`link` nodes are reference-by-path/URL. [V] https://jsoncanvas.org/spec/1.0/
   **Recommendation:** plugin element = `{type:"x-<ns>/<kind>", v, ref, snapshotAssetId, small props}`; blobs live in the content-addressed asset store.

9. **Secrets stay on the host.** MCP authorization forbids token passthrough and puts tokens in an Authorization header, never a query string [V] https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization . Penpot's remote MCP puts a key in a URL query (`?userToken=`), a pattern we should not copy [V] https://help.penpot.app/mcp/ . ComfyUI has no first-party per-user auth documented for self-hosted [U].
   **Recommendation:** ComfyUI/Penpot credentials live in the host's secret store; UI iframes get only short-lived, scoped, host-proxied handles.

10. **Prefer a closed core vocabulary + one open escape hatch, A2UI-style.** A2UI: agents emit declarative JSON constrained to a pre-approved component catalog, "no UI injection". [V] https://a2ui.org/ . This is already our `canvas.compose` model and our shared A2UI renderer.
    **Recommendation:** the declarative fallback card is just A2UI/`canvas.compose` CARD content, so absent-plugin rendering reuses code we already own.

---

## 2. Per-area findings

### 2.1 Canvas and whiteboard extensibility

**tldraw**
- Extension model: custom shape = `ShapeUtil` subclass implementing `getDefaultProps`, `getGeometry`, `component`, `getIndicatorPath`; registered via `shapeUtils` prop. Setting `static props` validates the record; without it props is arbitrary JSON. `BaseBoxShapeUtil`, bindings (connections) and migrations exist. [V] https://tldraw.dev/docs/shapes
- Data model: shape record with `props` (validated) and `meta` (free JSON, validated via `createTLSchema`). [V] same URL.
- Rendering: React component in `HTMLContainer`; so any HTML (including an iframe) renders as a shape.
- Missing plugin / failure: each shape renders inside its own React error boundary; a broken shape shows a fallback and the editor keeps working; fallback customisable via `ShapeErrorFallback`. There is a `TLUnknownShape` type for shapes of unknown type. [S] https://tldraw.dev/sdk-features/errors , https://tldraw.dev/reference/tlschema/TLUnknownShape . What the editor does for a record whose type has no registered ShapeUtil: [U] (confirm in source).
- Agent/LLM: official agent starter kit; agent sees screenshots plus structured shape data and acts via Zod-schema actions, each with `_type`, title and description metadata, plus utilities for execution, validation, sanitisation. [V] https://tldraw.dev/starter-kits/agent . "Make real" specifics: [U].
- Licence: "source available", not OSI; production requires a license key (trial 100 days, commercial annual, hobby with watermark); commercial keys send no analytics. [V] https://tldraw.dev/community/license , https://tldraw.dev/sdk-features/license-key
- Relevance: strongest blueprint for typed custom elements; disqualified as a dependency for a KMP/Compose app anyway (web-React only) and for licensing.

**Excalidraw**
- `renderEmbeddable` prop replaces the renderer of embeddable elements (which render as `<iframe>`). [V] https://docs.excalidraw.com/docs/@excalidraw/excalidraw/api/props/render-props . `validateEmbeddable` (allowlist of URLs), libraries (`.excalidrawlib`), element `customData`: [U], not confirmed this session. Excalidraw is MIT [U].
- MCP: Excalidraw ships an official MCP App server (remote `https://mcp.excalidraw.com`) that renders diagrams in the chat via MCP Apps; also the MCP Apps docs use it as the flagship demo. [V] https://modelcontextprotocol.io/extensions/apps/overview , [S] https://mcp.excalidraw.com/manifest.json
- Relevance: our scene format is Excalidraw-shaped, so `embeddable` (an element type that points at a URL and renders in an iframe) is the nearest existing element for third-party content; its limits (URL only, no state channel) argue for MCP Apps framing instead.

**Miro**
- Web SDK apps run as iframes hosted by the app developer; REST API + OAuth for server-side. App cards: preview with up to 20 fields, `status` sync indicator, `owned` flag, and a detailed view that is "an iframe you've implemented and hosted." [V] https://developers.miro.com/docs/app-card , [S] https://developers.miro.com/docs/app-card-use-cases
- Agent: official MCP server using OAuth 2.1 with dynamic client registration, rate limited, respects board permissions, available on all plans. [V] https://developers.miro.com/docs/miro-mcp
- Missing app behaviour: [U].
- Relevance: app card = exactly our "declarative preview card + optional live iframe" split.

**FigJam / Figma**
- Plugins: main-thread sandbox (ES2020+, no `fetch`, `XMLHttpRequest`, `setTimeout`, DOM) with scene access; UI via `figma.showUI()` iframe with browser APIs and no scene access; message passing. [V] https://developers.figma.com/docs/plugins/how-plugins-run/ . (QuickJS realm detail: [U]; the page summarised did not name QuickJS.)
- Manifest: `editorType` (figma, figjam, dev, slides, buzz), `networkAccess.allowedDomains` / `reasoning` / `devAllowedDomains`, `permissions` (currentuser, activeusers, fileusers, payments, teamlibrary), `capabilities`, `api` version, `id`, `documentAccess: "dynamic-page"`. [V] https://developers.figma.com/docs/plugins/manifest/ . CSP blocks unauthorised domains for non-iframed requests; sites embedded in an iframe aren't restricted by the allowlist. [V] how-plugins-run page.
- Widgets: collaborative objects written in JSX, rendered as widget nodes on the canvas, visible to everyone, with `useSyncedState`; distributed via Community. [V] https://developers.figma.com/docs/widgets/ . Behaviour when a viewer lacks the widget: [U].
- Distribution: Community review [U].
- Relevance: the two-realm split and the manifest are the model to copy. Widgets show the "plugin-owned element with synced state" idea; their state sync is separate from document content, a point worth noting for CRDT size.

**Penpot**
- Plugins: independent modules in isolated iframes; message passing; `manifest.json` with `name`, `description`, `code`, `icon`, `version`, `permissions`; installed by manifest URL via plugin manager; TypeScript typings in `@penpot/plugin-types`. [V] https://help.penpot.app/plugins/getting-started/ . Permissions: `content:read/write`, `library:read/write`, `user:read`, `comment:read/write`, `allow:downloads`, `allow:localstorage`; write includes read. [V] same.
- External integration: webhooks (team level, JSON or Transit; every backend RPC labelled WEBHOOK emits one), personal access tokens (`Authorization: Token <token>`; expiry never/30/60/90/180 days). [V] https://help.penpot.app/technical-guide/integration/
- MCP: official Penpot MCP = MCP server + a Penpot-side plugin that connects the open file; remote (`https://<domain>/mcp/stream?userToken=<MCP key>`) or local (`npx @penpot/mcp@stable`); one MCP key per user; tools `execute_code`, `high_level_overview`, `penpot_api_info`, `export_shape` (PNG/SVG), `import_image` (local mode only). [V] https://help.penpot.app/mcp/ , [S] https://dev.to/creeta/penpots-mcp-server-runs-on-five-tools-figmas-uses-dozens-354e
- Licence: MPL-2.0 (Kaleidos). [S] https://ark.sudovanilla.org/Penpot/penpot/raw/commit/1e642bba8f18155b950babc5abe068bbdf5e5f83/README.md (mirror of the README; confirm on github.com/penpot/penpot).
- Observation: the Penpot MCP plugin runs inside the open editor, so a headless host cannot export without a browser session unless it uses the backend RPC/exporter. [inference]

**Obsidian Canvas / plugins / JSON Canvas**
- JSON Canvas 1.0: `nodes` + `edges`; node types text (markdown), file (attachment, optional subpath), link (URL), group; every node has `id,type,x,y,width,height`; colours preset-numbered. [V] https://jsoncanvas.org/spec/1.0/ . The spec text fetched did not state unknown-node-type handling or licence. [U]
- Obsidian plugins: `manifest.json` + `main.ts`, loaded from `.obsidian/plugins`, enabled from Community Plugins. [V] https://docs.obsidian.md/Plugins/Getting+started/Build+a+plugin . Not sandboxed (plugins have full process access) [U]; restricted mode toggling [U].
- Relevance: file/link nodes are the minimal reference model; no plugin-UI story. Useful lesson: an unsandboxed in-process plugin model is a security pitfall.

**AFFiNE / BlockSuite**
- Custom block definitions and inline embeds; CRDT data layer (`@blocksuite/store`, Yjs); web components; Edgeless (canvas) editor; MPL 2.0; actively maintained. [V] https://github.com/toeverything/blocksuite
- Relevance: a CRDT-native custom block model with schema-per-block; Yjs not Automerge.

**Heptabase, Muse, Apple Freeform**: a search found no documented third-party plugin APIs for them. [S] (negative result, weak). Treat as no prior art.

### 2.2 General plugin architectures

- **VS Code**: extensions run in a separate extension host (local Node, web WebWorker, or remote); manifest `main`/`browser`; `extensionKind` chooses where it runs. [V] https://code.visualstudio.com/api/advanced-topics/extension-host . Contribution points and `activationEvents` are declarative JSON in `package.json`; extension must export `activate()` called once on first event; ~25 event types such as `onCommand`, `onView`, `onLanguageModelTool`. [V] https://code.visualstudio.com/api/references/activation-events . Webviews: iframe-like, recommended CSP e.g. `default-src 'none'; img-src ${webview.cspSource} https:; script-src ${webview.cspSource};`, `localResourceRoots`, `postMessage` both ways, `getState/setState`, serializer for restore. [V] https://code.visualstudio.com/api/extension-guides/webview . Extensions are not sandboxed from the user's machine [U].
  Copy: lazy activation, declarative contributions, host-process isolation, CSP-by-default webviews, state restore.
- **JetBrains plugins**: not researched (no fetch). [U] General knowledge: in-process JVM with `plugin.xml` extension points and dynamic plugin loading; not suitable for Android.
- **Grafana**: frontend plugins (panel/data source/app) plus backend plugins as separate subprocesses over gRPC via HashiCorp go-plugin, so a plugin cannot crash the server; backend needs a frontend component; instances configured per request so the plugin stays stateless. [V] https://grafana.com/developers/plugin-tools/key-concepts/backend-plugins/ . Signing: Grafana "requires all plugins to be signed before it loads them"; Community/Commercial levels; private plugins signed with `--rootUrls`; `MANIFEST.txt` holds checksums and a digital signature verified with a built-in public key. [V] https://grafana.com/developers/plugin-tools/publish-a-plugin/sign-a-plugin
  Copy: separate backend process, stateless per-request instance config, mandatory signed manifest with checksum list, private signing bound to a root URL.
- **Backstage**: frontend plugins composed in an extension tree; backend plugins communicate over the wire; extension points registered by plugins; plugins are NPM packages installed at compile time. [V] https://backstage.io/docs/overview/architecture-overview/ . Lesson: compile-time install = no runtime plugin story; useful only for its "typed extension point" idea.
- **Home Assistant**: integration manifest (domain, config_flow, requirements, iot_class, ...) and `custom_components`. The fetched manifest page 404'd; [U]. General knowledge only: config flow gives UI-driven credential entry, which is a good pattern for ComfyUI/Penpot setup.
- **WASM component model / Extism**: component model = binary format with imports/exports typed in WIT; cross-language. [V] https://component-model.bytecodealliance.org/design/why-component-model.html (WASI, capability security and JVM/Android maturity: [U] from this page). Extism = "plug-in system for everyone" with host SDKs [V] https://extism.org/docs/overview ; for Android the Extism suggestion is the pure-Java Chicory runtime (Chicory 1.0 released; experimental compile-to-Dalvik backend) [S] https://docs.mcp.run/blog/2025/07/11/android-support-mcpx4j , https://github.com/extism/extism . Chicory is a pure-Java runtime, "safety, simplicity, portability over maximum speed", core spec + WASI preview 1. [S] https://docsearch.algolia.com/mcp/docs/repo/dylibso/chicory . Kotlin/WASM-native host SDK, wasm client story, Play policy treatment of WASM: [U]. (Play policy exempts "a virtual machine or an interpreter" with only indirect API access, which plausibly covers a WASM interpreter but this is not stated; needs policy confirmation.)

### 2.3 Agent/AI tool-and-UI standards (central pattern)

**MCP Apps (SEP-1865), extension id `io.modelcontextprotocol/ui`** [V] https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx and https://modelcontextprotocol.io/extensions/apps/overview
- UI resource: URI scheme `ui://`, MIME `text/html;profile=mcp-app`, delivered via `resources/read` as `text` or base64 `blob`. Other content types (external URL, remote DOM) are reserved for later.
- Tool linkage: tool `_meta.ui.resourceUri`; `_meta.ui.visibility` = `["model","app"]` (default), `["model"]`, or `["app"]` (hidden from `tools/list` for the agent; app-only tools cannot be called cross-server). Host may prefetch/cache the template before the tool runs.
- Resource `_meta.ui`: `csp` {`connectDomains`, `resourceDomains`, `frameDomains`, `baseUriDomains`}, default-deny (`default-src 'none'`, `script-src 'self' 'unsafe-inline'`); `permissions` {camera, microphone, geolocation, clipboardWrite}; `domain` (dedicated sandbox origin); `prefersBorder`.
- Messaging (JSON-RPC 2.0 over `postMessage`): View to Host: `ui/initialize` (declares `appCapabilities`; host returns `hostCapabilities`, `hostContext`, `hostInfo`, protocol version), `ui/notifications/initialized`, `tools/call`, `resources/read`, `ui/message` (add to chat), `ui/update-model-context`, `ui/open-link`, `ui/request-display-mode`, `ui/notifications/size-changed`, `notifications/message` (logs). Host to View: `ui/notifications/tool-input-partial`, `tool-input`, `tool-result` (`content` for the model, `structuredContent` for rendering, `_meta`), `tool-cancelled`, `host-context-changed`, `ui/resource-teardown`.
- HostContext: `toolInfo`, `theme`, `styles` (CSS variables, fonts), `displayMode` (`inline`/`fullscreen`/`pip`), `availableDisplayModes`, `containerDimensions` (fixed/flexible/unbounded), `locale`, `timeZone`, `userAgent`, `platform` (`web`/`desktop`/`mobile`), `deviceCapabilities`, `safeAreaInsets`. Standard CSS variable theming with `light-dark()`.
- Security: web hosts use a double-iframe: outer sandbox proxy on a different origin than the host, inner view iframe, proxy forwards messages (except reserved `ui/notifications/sandbox-*`) and applies CSP/Permission Policy; native/desktop hosts may use a single iframe. Predeclared reviewable templates, auditable RPC logging, host validates message types. Remaining risks named in the spec: social engineering via UI content, resource exhaustion.
- Capability negotiation: client advertises `mimeTypes` in `initialize`; servers should check before registering UI tools and "provide text-only fallbacks for all UI tools."
- Not yet in MVP: external URL / remote DOM content types, state persistence, custom sandbox policies, view-to-view comms, screenshot/preview API, multiple UI resources per tool.
- Host support: Claude, Claude Desktop, VS Code GitHub Copilot, Microsoft 365 Copilot, Goose, Postman, MCPJam, Archestra.AI. [V] overview page. SDK: `@modelcontextprotocol/ext-apps` (`App` class, AppBridge for hosts, `basic-host` example). Client renderer package `@mcp-ui/client` (`AppRenderer`, `AppFrame`). [V] https://mcpui.dev/guide/introduction
- No Kotlin/Compose host exists that we found. We must implement the host side (the double-iframe/`AppBridge` equivalent) per platform. [U] (absence not exhaustively searched).

**MCP-UI**: the precursor that influenced MCP Apps; `createUIResource()`; now implements the same standard, backward compatible. Its original types were rawHtml / externalUrl / remoteDom. [V] https://mcpui.dev/guide/introduction (the type list is partly [U]).

**OpenAI Apps SDK**: components run in iframes in ChatGPT, JSON-RPC over `postMessage` following MCP Apps; uses `_meta.ui.resourceUri` (alias `_meta["openai/outputTemplate"]`), `_meta.ui.csp`, MIME `text/html;profile=mcp-app`; `window.openai` offers optional extras (`requestCheckout`, `uploadFile`, `selectFiles`, `getFileDownloadUrl`, `requestModal`, `widgetState`/`setWidgetState`). State guidance: authoritative data server-owned, ephemeral UI state local. [V] https://developers.openai.com/apps-sdk/build/chatgpt-ui . Convergence: both OpenAI and Anthropic ecosystems now use the same MCP Apps shape, so adopting it buys cross-compatibility.

**A2UI (Google, Apache-2.0)**: agents send declarative, flat, streaming JSON describing components from a pre-approved catalog; renderers native per framework (Angular, Flutter, Lit, ...); v0.9.1 current, v1.0 candidate with client-to-server RPC and action IDs. [V] https://a2ui.org/ . Our repo already contains a shared A2UI renderer (per CLAUDE.md), so A2UI is our declarative tier.

**Claude artifacts**: not fetched. [U]. Conceptually the same sandboxed-HTML idea; MCP Apps is the reusable spec form.

### 2.4 Integration targets

**ComfyUI** (server routes verified at https://docs.comfy.org/development/comfyui-server/comms_routes [V])
- HTTP: `POST /prompt` submit; `GET /prompt` queue status; `GET /queue`; `POST /queue`; `GET /history`, `GET /history/{prompt_id}`, `POST /history`; `GET /view` (image fetch, "lots of options"); `POST /upload/image`, `/upload/mask`; `GET /object_info[/{node_class}]`; `POST /interrupt`; `POST /free`; `GET /system_stats`; `GET /models[/{folder}]`.
- WebSocket `/ws`: `status`, `execution_start`, `executing`, `execution_cached`, `progress`, `executed` messages. [V] same page.
- Workflow: client submits the whole workflow when queueing; later edits are not seen by the server. [V] https://docs.comfy.org/development/comfyui-server/comms_overview . API-format vs UI-format JSON, `client_id` in the prompt body, `/view?filename=&subfolder=&type=` params: general knowledge, [U] (the example script fetch returned 503).
- Auth: documentation found describes a Comfy platform API key (for Partner Nodes via `extra_data.api_key_comfy_org`, Cloud API headers). No built-in per-user auth for a self-hosted server was found; treat self-hosted ComfyUI as unauthenticated and put it behind the host / reverse proxy. [S] https://docs.comfy.org/development/api-development/getting-an-api-key ; the no-auth claim is [U].
- Existing MCP servers: several community ones (generate_image, generate_with_workflow, get_queue_status, get_job, cancel_job, view_image, regenerate), HTTP at e.g. `127.0.0.1:9000/mcp` pointing to a ComfyUI on 8188. [S] https://glama.ai/mcp/servers/jaewilson07/comfy_mcp , https://glama.ai/mcp/servers/miller-joe/comfyui-mcp . Quality/licence of each: [U].

**Penpot**
- Backend RPC under `/api/rpc/command/<name>` (example `get-profile`), authenticated by `Authorization: Token <access-token>` or session cookie. [V] https://help.penpot.app/technical-guide/integration/ , [S] get-file via POST with `file_id`: https://glama.ai/mcp/servers/montevive/penpot-mcp/tools/get_file
- Export: Penpot has a built-in exporter producing SVG/PNG [S] (search snippet only). Exact export RPC / exporter endpoint and frame-id addressing: [U].
- Webhooks: team-level, JSON or Transit, no dedicated event documentation; infer payloads by observation. [V] integration guide.
- Plugin API and MCP: see 2.1. Community MCP servers exist (e.g. `montevive/penpot-mcp`, `ancrz/penpot-mcp-server`) using the RPC API. [S] links above.

**Minimal "show external resource, keep it fresh, agent drives it"** (generic): (1) host-side connector authenticates to the tool; (2) host exports a snapshot and stores it in the content-addressed asset store; (3) a canvas element holds `{ref, snapshotAssetId, rev}`; (4) a change source (webhook, WebSocket, or poll) triggers re-snapshot and a small CRDT write; (5) agent tools call the connector via MCP. Concrete designs in section 6.

### 2.5 Canvas element model for third-party content (synthesis)

Evidence and patterns:
- tldraw: type-keyed registry + validated `props` + `meta` + migrations + per-shape error boundary. [V]/[S] above.
- Miro app card: `fields[]`, `status`, owned flag, external-sync semantics. [V]
- JSON Canvas: file/link reference nodes. [V]
- Excalidraw embeddable: URL-only iframe element. [V]/[U]
- BlockSuite: per-block schema, Yjs CRDT. [V]
- Figma widgets: separate synced state per widget. [V]

Proposed shape (ours): `type: "ext:<publisher>.<plugin>/<kind>"` (namespaced), `schemaVersion: int`, `ref` (opaque string id or URL, never a secret), `snapshot: {assetId, mime, w, h, rev, takenAt}`, `props` (<= a few hundred bytes, flat scalars so Automerge merges per-field), `fallback: {title, subtitle, icon, openUrl}`. Rules: unknown type or newer schema renders the fallback card from `fallback` + `snapshot`; plugin never rewrites the whole element, only fields it owns; live view is opt-in on focus/expand.

CRDT friendliness: Automerge (and Yjs) merge field-level, so keep props flat and small, avoid arrays that two writers will edit, put refresh timestamps in the snapshot sub-object with last-writer-wins semantics, and store blobs outside. (Automerge semantics: [U] here; verify against project's Automerge version.)

### 2.6 Security and trust (synthesis of sources)

- Capabilities/permissions: Figma `permissions`/`capabilities` [V]; Penpot permission list [V]; MCP Apps `permissions` and `visibility` [V]; MCP OAuth scope step-up with `insufficient_scope` [V].
- Network allowlists: Figma `allowedDomains` [V]; MCP Apps CSP lists, host "no loosening" [V]. Gap: Figma's allowlist doesn't govern nested iframes [V]; MCP Apps lists `frameDomains` explicitly.
- Secrets: MCP spec: tokens only in `Authorization` header, never URI query; servers must not accept or pass through other tokens (confused deputy); stdio servers take credentials from environment. [V] authorization page. Penpot remote MCP puts the key in the query string [V] https://help.penpot.app/mcp/ . Penpot `allow:localstorage` is the sanctioned place for plugin-side tokens [V] but that's client-side; we keep ours on the host.
- Signing/distribution: Grafana signed manifest with checksums [V]; Figma/Penpot/Obsidian community review or URL install [V]/[U]; Penpot install by manifest URL means the trust anchor is the URL [V].
- Consent: MCP Apps says host controls which tools an app can call and whether `sendOpenLink` is allowed [V]; MCP Apps requires user consent for delegated actions per overview [V].
- Residual risks the spec admits: social engineering through plugin UI, resource exhaustion [V].

---

## 3. Comparison table of plugin models

| Model | Where code runs | UI model | Data model | Sandbox | Distribution | Missing-plugin behaviour |
|---|---|---|---|---|---|---|
| MCP Apps (spec) | Server: any MCP host-side process. View: HTML in host iframe | `ui://` HTML (`text/html;profile=mcp-app`), themed via CSS vars | Tool args + `structuredContent`; resources | Iframe, double-iframe proxy on web, CSP default-deny, host-brokered calls | Whatever the MCP server is (remote URL, npx) | Mandated text-only fallback if host lacks UI support [V] |
| OpenAI Apps SDK | Server + iframe in ChatGPT | Same MIME / `_meta.ui` + `window.openai` extras | Server-owned snapshots; `widgetState` | Iframe, CSP lists | ChatGPT app directory [U] | [U] |
| tldraw custom shape | In-app JS (React) | React `ShapeUtil.component` | Typed record: validated `props` + `meta` | None (same page); error boundary only | npm, compile-time | Error-boundary fallback; unknown-type handling [S]/[U] |
| Excalidraw embeddable | Remote page in iframe | `renderEmbeddable` iframe | URL on element | Iframe, validate-URL allowlist [U] | n/a | Unknown element type: [U] |
| Miro app / app card | App's own web server + iframe | Preview fields + hosted iframe detail | `fields[]`, `status`, external id | Iframe, OAuth | Miro Marketplace [U] | [U] |
| Figma plugin | Main-thread sandbox + UI iframe | iframe HTML | Scene nodes; plugin data | JS sandbox without browser APIs; CSP + `allowedDomains` | Community review [U] | N/A (not persisted as element) |
| Figma widget | Widget runtime | JSX to canvas nodes | `useSyncedState` | Sandbox [U] | Community | [U] |
| Penpot plugin | Iframe + plugin code | Iframe modal | Penpot API objects | Isolated iframe, permission scopes | Install by manifest URL | N/A |
| Obsidian plugin | In-process JS | Native DOM | Vault files / JSON Canvas | None [U] | Community review [U] | Plain file nodes still render |
| VS Code extension | Extension host process | Contributions + CSP webviews | Extension state | Process isolation, webview CSP | Marketplace | Contribution ignored |
| Grafana plugin | Frontend bundle + backend subprocess (gRPC) | React panel | Data frames | Process isolation; signed | Catalog, signed | Unsigned refused by default [V] |
| Extism/WASM | Host-embedded wasm runtime | None (headless) | Bytes via host functions | WASM linear memory, host-granted capabilities [U] | Any | N/A |
| A2UI | Agent emits JSON; client renders natively | Declarative catalog components | Flat JSON | No code execution | n/a | Unknown component: [U] |

---

## 4. Standards to adopt verbatim / patterns to borrow / pitfalls

**Adopt verbatim**
- MCP (core tools/resources/subscriptions/OAuth when HTTP) [V].
- MCP Apps: `ui://` URIs, MIME `text/html;profile=mcp-app`, `_meta.ui.{resourceUri,visibility,csp,permissions,prefersBorder}`, `ui/*` JSON-RPC methods, capability negotiation under `io.modelcontextprotocol/ui`, display modes, host context & CSS variables [V].
- A2UI for the declarative tier (already in repo).
- JSON Canvas semantics for file/link style references where useful [V].
- Semantic-version `api` field in manifest (Figma-style) [V].

**Borrow**
- Figma/Penpot: manifest with `networkAccess`/`permissions`, `reasoning` for broad requests, `devAllowedDomains` for dev builds.
- VS Code: lazy activation events; declarative contribution points; `extensionKind` placement (ui vs workspace maps to client vs host); webview state restore.
- Grafana: separate backend process; signed manifest with file checksums; private signing bound to rootUrls.
- tldraw: validated props, `meta`, migrations, per-element error boundary.
- Miro: preview + `status` + detail view split.
- Penpot MCP: tiny tool surface (5 tools) plus a `high_level_overview` bootstrap tool for the agent.
- tldraw agent kit: Zod/JSON-schema actions with title and description, plus sanitisation before applying.
- Home Assistant config-flow style guided credential setup [U].

**Pitfalls**
- tldraw SDK licence as a dependency [V].
- Putting credentials in URLs (Penpot remote MCP key) [V].
- Unsandboxed in-process plugins (Obsidian-style) [U].
- Figma: an iframe URL embedded in a plugin escapes the domain allowlist [V]; so treat `frameDomains` as a separate permission.
- Storing rendered output or large JSON in the CRDT (size and merge churn) [inference].
- Relying on Penpot's in-editor MCP plugin for headless snapshots [inference from 2.1].
- Token passthrough from client to the upstream tool (MCP confused-deputy rule) [V].
- Assuming MCP Apps host libraries exist for Compose: none found [U]; the bridge is our work.
- WASM on Android: Chicory Android support is described as experimental in one snippet [S]; do not commit to it for v1.

---

## 5. Candidate architecture sketch

Our constraints: Compose clients on Android, Desktop (Windows JVM), wasm; Iroh host process beside the Letta App Server; Automerge canvas; agents already call `canvas_*` tools via the host.

### Recommended (A): host-side MCP plugin servers + MCP-Apps-style sandboxed UI + declarative fallback card

```
Agent --(canvas_* / plugin tools)--> Host (Iroh wrapper)
                                      |- Plugin registry: manifests, permissions, signatures, secrets
                                      |- Plugin MCP servers (stdio/HTTP subprocesses)  <--> ComfyUI / Penpot
                                      |- Asset store (content-addressed)
                                      '- Canvas ops validator -> Automerge doc
Clients (Android/Desktop/wasm) <--Iroh--> Host
   Element renderer registry:
     core types (NOTE/CHECKLIST/CARD/TEXT/GROUP)
     ext:* element -> 1) fallback card (A2UI/CARD from snapshot + fallback fields)  [always]
                      2) optional live view: ui:// HTML in WebView/iframe via an AppBridge-compatible host [if plugin + user opt-in]
```
- Manifest: id, version, api range, MCP server launch/endpoint, tools, `ui://` resources, CSP lists, permissions (`canvas:read`, `canvas:write:own-elements`, `assets:write`, net domains), element kinds with schemaVersion, signature.
- Plugin tools register as MCP tools; the host exposes them to agents and also wraps them as validated canvas ops (the plugin returns ops or element specs, never writes the CRDT).
- Live view: the client implements an `AppBridge` equivalent. Android: WebView (JS interpreted, Play-compliant [V]); Desktop: CEF/WebView2 (KCEF-style wrappers exist [S] https://github.com/kpgalligan/compose-webview-multiplatform ); wasm: sandboxed iframe + proxy origin. `tools/call` from the view goes client -> host over Iroh -> plugin server, with `visibility:["app"]` honoured.
- Pros: no third-party code in the client binaries; industry standard UI protocol; agents get tools "for free"; ComfyUI/Penpot already have MCP servers to start from; graceful degradation designed in. Cons: needs a plugin runner on the host (process lifecycle, signing, resource limits); live view requires host to be reachable (offline = snapshot only); we must build the MCP-Apps host bridge on three platforms; UI hosting on a different origin on wasm needs infrastructure.

### Alternative B: in-app Kotlin plugin modules (compile-time)
- Plugins are Gradle modules registering `ElementRenderer`s via DI (Backstage-style compile-time install [V] https://backstage.io/docs/overview/architecture-overview/ ).
- Pros: native look, smallest runtime complexity, type-safe. Cons: violates "must not bloat core" unless separate flavours; third parties cannot ship without us (Play bans dynamic dex [V]); every plugin ships to all users; no web distribution. Acceptable only for first-party reference plugins.

### Alternative C: WASM plugins (Extism/component model)
- Plugin logic in WASM run by Chicory (JVM/Android) or browser engine (wasm).
- Pros: capability-sandboxed, portable, one binary for host and client. Cons: no UI story (still need declarative UI or WebView); Android/Chicory maturity and Play stance uncertain [S]/[U]; I/O (HTTP to ComfyUI) must be host functions anyway; no agent-tool ecosystem (agents speak MCP); small wins vs A since the host already can run subprocesses. Consider later as a sandbox for the host-side runner [inference].

Hybrid recommendation: A for third-party plugins; B only for first-party "core-adjacent" renderers; C deferred.

---

## 6. Concrete minimal designs under architecture A

### 6.1 ComfyUI plugin (`ext:letta.comfyui`)
Host connector (MCP server, config: base URL; optional proxy auth header held in host secret store):
- Tools (model+app): `comfy_list_workflows` (saved templates in plugin storage), `comfy_run_workflow({workflowId, params})`, `comfy_get_job({promptId})`, `comfy_cancel({promptId})`, `comfy_object_info()` (to validate node names). App-only: `comfy_view_output({promptId,index})`.
- Flow: `POST /prompt` with API-format workflow JSON (plus a client id); subscribe `/ws` for `execution_start`/`progress`/`executed`; on finish `GET /history/{prompt_id}` then `GET /view?...` to fetch outputs; store images in the asset store; emit canvas ops. [routes V; payload details U]
- Elements: `ext:letta.comfyui/job` {`ref`: promptId, `props`: {status, progress%, workflowId, seed}, `snapshot`: last output assetId}. Placeholder while running with progress; final replaces snapshot (or adds `ext:letta.comfyui/image` elements, which are just core image elements plus provenance `meta`; recommended so images work without the plugin).
- Freshness: WS events update the single job element's `status/progress` fields (throttled, e.g. <= 1 write/s); no polling on clients.
- Live view (optional `ui://letta/comfy/job`): shows queue, re-run with edited params button (calls `comfy_run_workflow`), CSP `connectDomains: []` since all traffic goes through the host.
- Missing plugin: fallback card shows prompt, thumbnail snapshot, status text. Agent without the plugin simply lacks the tools.
- Security: ComfyUI URL allowlisted on host, workflow JSON validated (node class allowlist via `/object_info`; custom nodes can execute code on the ComfyUI machine, so restrict agent-supplied workflows to templates with a parameter schema) [custom-node risk: U]; cap concurrent jobs and image size.

### 6.2 Penpot plugin (`ext:letta.penpot`)
Host connector (MCP server, config: Penpot base URL + personal access token in host secret store; `Authorization: Token ...` [V]).
- Tools: `penpot_list_files/pages/frames`, `penpot_get_frame_snapshot({fileId, frameId, format})`, `penpot_place_frame_on_board({fileId, frameId, x, y})`, `penpot_refresh({elementId})`; optional write path later: `penpot_import_image` via official MCP/plugin (requires an open editor session) [V for official MCP; round-trip design inference].
- Elements: `ext:letta.penpot/frame` {`ref`: `{fileId, pageId, frameId}`, `props`: {name, revn}, `snapshot`: SVG or PNG assetId, `fallback.openUrl`: Penpot deep link}.
- Freshness: team webhook to the host (events labelled WEBHOOK, JSON payload; inspect once to learn format [V/U]) or poll `get-file` `revn`; on change re-export and bump `snapshot.rev` only. Export endpoint details are [U]; spike needed (RPC/exporter vs official MCP `export_shape` which needs an open browser session [V]).
- Agent driving: read via the tools above; for authoring in Penpot, delegate to Penpot's official MCP (`execute_code`, `export_shape`) mounted as a second MCP server, not re-implemented. Note this session's tooling already exposes a Penpot MCP, a practical dev reference.
- Live view: Penpot embed or exported SVG viewer in `ui://letta/penpot/frame`; no token in the iframe.
- Licence: MPL-2.0 (file-level copyleft) only matters if we vendor Penpot code; calling its API does not [S][U].

---

## 7. Open questions for the architect

1. Does Play policy accept a WebView that renders third-party remote HTML/JS as "interpreted code" for our app category? (The exemption exists [V] but "must not enable policy violations"; confirm with Play review.)
2. Who hosts the plugin UI origin on wasm (the double-iframe proxy needs a distinct origin)? Is a per-host sub-origin acceptable?
3. Plugin runner on the host: subprocess per plugin (Grafana-style) vs in-process; who pays for resource limits, and what OS do hosts run on (Windows host process)?
4. Trust model: sign plugins (Grafana-style manifest checksums) with our key only, or allow user-installed unsigned plugins in a dev mode with explicit consent?
5. Can the live-view bridge be the same Kotlin code on three platforms (WebView/CEF/iframe all speak postMessage; Compose wasm uses browser DOM), or do we need per-platform shims? No Kotlin AppBridge exists [U].
6. Element ownership in the CRDT: may a plugin edit only elements of its namespace? Who validates `props` against the plugin's schema when peers run different plugin versions (schemaVersion skew)?
7. Snapshot policy: who generates the thumbnail (plugin server vs host), size cap, retention, and sync of the asset store over Iroh to peers lacking the plugin?
8. Agent exposure: should every plugin tool be visible to every agent, or per-agent/per-board grants (MCP `visibility` + our own ACL)?
9. Do we require MCP Apps `ui/update-model-context` and `ui/message` (feeding board interactions back to the agent) in v1, or only tool-result push and `tools/call`?
10. ComfyUI trust: is arbitrary agent-authored workflow JSON allowed, or only templates with parameter schemas? (Custom-node code execution risk [U].)
11. Penpot: confirm export endpoint and webhook payload via spike; confirm whether headless snapshot is possible without an open editor (official MCP needs the plugin in an open file [V]).
12. Licensing diligence: confirm Penpot MPL-2.0 on github.com, MCP SDK licences, any ComfyUI MCP server licence we might fork, and that A2UI Apache-2.0 [V] terms fit.
13. Verify unconfirmed items before commit: Figma/Miro missing-plugin behaviour, tldraw unknown-shape handling, Excalidraw `validateEmbeddable`/libraries, Home Assistant manifest, JetBrains, Claude artifacts model.
