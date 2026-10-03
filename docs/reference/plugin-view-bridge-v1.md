# Plugin view bridge `lcp-view/1` — for plugin page authors

A plugin page (an `.html` file under `pages/` in your `.lcp` package, declared in the manifest's
`pages`) runs in a sandbox: an Android WebView, desktop JCEF, or (later) a wasm iframe. It talks to
the host through **one** API on every platform, `window.lettaView`, which a small shim installs
before your page's own scripts. Underneath it is JSON-RPC 2.0 over postMessage with a fixed method
set. Nothing outside that set is answered.

Source of truth: `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/plugin/view/`
(`LcpView.kt`, `ViewBridge.kt`, `LcpViewShim.kt`, `PluginViewCsp.kt`). Plan: section 7.2 of
`docs/design/canvas-plugin-platform-plan.md`.

## Lifecycle

1. Your page loads and calls `await lettaView.ready()`. The answer is the **host context**.
   Every other call before `ready()` fails with `NOT_READY` (-32000).
2. While the view is live, you call actions and the host sends you `message` events.
3. When the view collapses, scrolls out or closes, the host sends `host.teardown { reason }`. Finish
   your cleanup inside the `message` listener. The shim acknowledges for you once the listeners
   have run. The host waits at most 2 s, and after that every call fails with `CLOSED` (-32007).

```html
<script>
  addEventListener("message", (e) => {
    const m = e.data;                       // the JSON-RPC object
    if (m.method === "host.context") render(m.params);
    if (m.method === "host.element.changed") update(m.params.element);
    if (m.method === "host.teardown") stopTimers();
  });
  lettaView.ready().then(render);
</script>
```

## `window.lettaView`

| Member | What it does |
|---|---|
| `version` | `"1"`, the `viewVersion` that `ready()` announces |
| `ready()` | `view.ready { pageId, viewVersion }` → host context |
| `action(name, input)` | `view.action { action, input }` → `{ text, structured? }` |
| `resize(width, height)` | `view.resize` notification (CSS px, 1–10000). The host may grow the frame while the person has not sized it |
| `displayMode(mode)` | `view.displayMode { mode }` → `{ mode }`, the mode the host settled on |
| `openLink(url)` | `view.openLink { url }` → `{}` |
| `log(level, message)` | `view.log` notification. `level` is `debug`, `info`, `warn` or `error`; `message` is at most 2000 characters |
| `request(method, params)` | A raw request. It returns a Promise of `result` and rejects with `error` |
| `notify(method, params)` | A raw notification |
| `send(message)` | A raw JSON-RPC message (an object or its text) |

### Host context (`ready()` answer and `host.context` events)

```json
{ "theme": "dark", "cssVariables": { "--md-sys-color-primary": "#6750a4" },
  "element": { "id": "el-1", "type": "ext:letta.example/widget", "v": 2,
               "frame": { "x": 10.0, "y": 20.0, "width": 320.0, "height": 240.0 },
               "props": { "status": "idle" }, "snapshot": { "assetRef": "sha256:…" },
               "fallback": { "title": "Widget" } },
  "settingsPublic": { "quality": 80 }, "displayMode": "inline", "locale": "en-CA",
  "platform": "desktop", "safeArea": { "top": 0.0, "right": 0.0, "bottom": 0.0, "left": 0.0 } }
```

`settingsPublic` holds only fields declared under the manifest's `settings`. Secrets never reach a
page. `host.element.changed { element }` carries the same element shape.

## What the manifest lets a page do

- **Actions**: only your own plugin's actions whose `visibility` includes `"view"`. The input must
  hold to the action's `input` schema, or the call fails with `INVALID_PARAMS` and lists problems
  at JSON pointers (`/params/input/label`). The action runs for your view's own canvas and element.
- **Display modes**: only those listed in the page's `displayModes`. Any other mode fails with
  `FORBIDDEN`.
- **Links**: the page must list `ui:openLink` in `permissions`. The person must allow it on that
  device (first use), and the host's link policy must accept the URL (an allowlist and/or a
  confirmation). Only absolute `http`/`https` URLs without userinfo are accepted.
- **Device permissions** (`ui:camera`, `ui:microphone`, `ui:geolocation`, `ui:clipboardWrite`): only
  the ones the page declares **and** the person consented to are granted. The host sends the result
  as a `Permissions-Policy`, with everything else off.

## Content-Security-Policy

Pages run under a default-deny policy, widened only by the page's `csp` allowlists:

```
default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline';
img-src 'self' data: blob: <resourceDomains>; font-src 'self' <resourceDomains>;
media-src 'self' blob: <resourceDomains>; connect-src <connectDomains or 'none'>;
frame-src <frameDomains or 'none'>; object-src 'none'; form-action 'none'; base-uri 'none'
```

Each domain must be an origin (`https://host[:port]`, or a leading `*.` wildcard under two or more
labels). Anything else is dropped. Scripts and styles must come from your package or be inline,
because no allowlist widens them. Desktop sends the policy as a header. Android injects a `<meta>`.

## Limits and errors

- Each message is at most **256 KiB** (UTF-8), and each view may send at most **20 messages per
  second** (a sliding window).
- Requests need an id (a string of 1–64 characters, or an integer). Notifications (`view.resize`,
  `view.log`) must not have one. Unknown members, methods and params fields are refused.
- Every message is audited (`Plugin view.rpc` telemetry: plugin, page, element, method, outcome).
  Params and results are never logged.

| Code | Name | When |
|---|---|---|
| -32700 | PARSE_ERROR | not JSON |
| -32600 | INVALID_REQUEST | not a JSON-RPC 2.0 message, id rules broken, a second `ready()` |
| -32601 | METHOD_NOT_FOUND | outside the method set |
| -32602 | INVALID_PARAMS | params or action input do not hold, a URL that is not http(s) |
| -32000 | NOT_READY | a call before `ready()` |
| -32001 | RATE_LIMITED | over 20 messages/s |
| -32002 | TOO_LARGE | over 256 KiB |
| -32003 | FORBIDDEN | something the manifest does not declare for this page |
| -32004 | DENIED | the person or the link policy said no |
| -32005 | ACTION_FAILED | the plugin failed the action |
| -32006 | UNAVAILABLE | the host cannot be reached |
| -32007 | CLOSED | the view is tearing down |

Notifications are never answered, not even with an error.

## For platform hosts

The host defines `window.__lettaViewPost(text)` (its native channel), injects
`LcpViewShim.inject(pageId)` before the page, and delivers host messages with
`window.__lettaViewReceive(text)`. It wraps both directions as a `PostMessagePort`, builds a
`ViewBridge(spec, port, ViewBridgeServices(host, transport, links, consent))`, runs `run()` while the
page lives, and calls `teardown(reason)` to end it. `PluginViewTransport` (pages and actions) is
implemented by the `meridian/plugin-view/1` ALPN (s416w.32). `PluginViewTransport.Unavailable` is
the offline case.

### Desktop (JCEF)

The desktop host (`desktop/src/main/kotlin/com/letta/mobile/desktop/plugin/view/`,
letta-mobile-s416w.14) serves each page at `letta-plugin://<pluginId>/<pageId>?v=<version>`, a
standard, secure custom scheme, so every plugin is its own origin. The response carries the CSP and
`Permissions-Policy` above as real headers, plus `nosniff`, `no-store` and `no-referrer`. The shim
is an inline script placed first in the page's `<head>`, and `__lettaViewPost` goes through JCEF's
message router under the name `__lettaCefQuery`. Only the main frame showing the page may post.
Each view gets its own `CefClient` and an in-memory request context. A request handler holds the
main frame to the page, subframes to `frameDomains`, and resources to the page's allowlists plus
`data:`/`blob:`. It refuses popups, downloads and the context menu.

The JCEF native bundle (about 360 MB on disk for Windows x64) is downloaded by jcefmaven the first
time a live view shows. It goes to `~/.letta-mobile/jcef/bundle` (override with
`-Dletta.pluginViews.jcefDir=<dir>`), and later runs reuse it. While it downloads, the card shows the
progress. If the download fails (offline on first run), the platform is unsupported, there is no
display, or `-Dletta.pluginViews.jcef=false` is set, every element stays its fallback card with the
reason, and the app carries on.
