# letta.chart: the reference canvas plugin

A complete `process` plugin in about 400 lines of dependency-free Node. It shows each part a canvas
plugin needs: a manifest, a runtime that speaks LCP wire v1 over stdio, assets, element
placement, a live page on the lcp-view/1 bridge, and a deterministic package. If you are writing
a plugin, copy this directory and start from it.

| File | What it is |
|---|---|
| `letta-plugin.json` | The manifest: id, process runtime, capabilities, the `chart` element kind, actions, page |
| `server.mjs` | The runtime: LCP wire v1 over stdin/stdout, one JSON-RPC message per line |
| `png.mjs` | A tiny indexed-PNG writer (`zlib.deflateSync` + CRC-32) and chart rasterizer |
| `pages/chart.html` | The live view: an inline SVG chart and a Bar/Line toggle, through `window.lettaView` |
| `pack.sh` | Builds `dist/letta.chart-1.0.0.lcp` deterministically and prints its sha256 |
| `test/` | `node --test` suites: PNG validity, wire framing, plot/toggle, cancel, pack determinism |

## What it does

- **`plot`**: visible to the agent as the tool `chart_plot` and to the page. Input
  `{kind?: "bar"|"line", title, labels: string[], values: number[]}` (1 to 24 points). The plugin
  renders a 320x200 PNG, uploads it with `host.putAsset.begin/chunk/end`, then calls `host.emit`
  with one `place` entry of kind `chart`. The PNG is the element's `snapshot`, so a client
  without the page still shows a picture, and the fallback card shows the title.
- **`toggle`**: visible to the page only. It flips `kind` between bar and line, re-renders,
  re-uploads, and sends `host.emit {update: [...]}` for the element in `context.elementId`. The
  page sends the element's current props as input, so `toggle` still works after a plugin
  restart has wiped the plugin's memory of the chart.

## Capabilities

`canvas:place` (place and update its own `chart` elements), `assets:write` (snapshot PNGs) and
`ui:pages` (the live view). It declares no network access, no secrets and no `canvas:read`: the
plugin keeps the props of the charts it placed in memory instead of reading the board. The page's
CSP allowlists are empty. Everything in the page is inline and nothing is fetched.

## Element props are flat

A kind's props schema allows only strings, numbers, booleans and enums (`PluginContentRules`), and
must be 4 KiB or less. Arrays are not allowed, so `labels` and `values` are stored as JSON text
(`"[\"Q1\",\"Q2\"]"`, `"[3,5]"`). The `plot` action's input uses real arrays, because that is
what the agent should write. The plugin converts between the two forms.

## The wire, frame by frame

```
host   {"id":1,"method":"plugin.initialize","params":{"contractVersions":[1],...}}
plugin {"id":1,"result":{"ok":true,"contractVersion":1,"pluginInfo":{"build":"1.0.0"}}}
host   {"id":2,"method":"plugin.activate","params":{}}
plugin {"method":"host.log","params":{"level":"info","message":"chart plugin activated"}}
plugin {"id":2,"result":{}}
host   {"id":3,"method":"action.invoke","params":{"action":"plot","input":{...},"context":{...}}}
plugin {"id":"p-1","method":"host.putAsset.begin","params":{"mediaType":"image/png","byteSize":N}}
host   {"id":"p-1","result":{"uploadId":"upload-1"}}
plugin {"id":"p-2","method":"host.putAsset.chunk","params":{"uploadId":"upload-1","index":0,"base64":"..."}}
plugin {"id":"p-3","method":"host.putAsset.end","params":{"uploadId":"upload-1","sha256":"<hex>"}}
host   {"id":"p-3","result":{"ref":"sha256:<hex>"}}
plugin {"id":"p-4","method":"host.emit","params":{"place":[{"kind":"chart","v":1,"props":{...},"snapshot":{"type":"asset","ref":"sha256:<hex>"},...}]}}
host   {"id":"p-4","result":{"receipt":{"placed":["el-1"]}}}
plugin {"id":3,"result":{"text":"Placed a bar chart \"Revenue\" (el-1)","structured":{"elementId":"el-1","kind":"bar"}}}
...    toggle: the same upload, then host.emit {update:[{elementId:"el-1", props:{kind:"line",...}, snapshot}]}
host   {"id":5,"method":"plugin.deactivate","params":{}}
plugin {"id":5,"result":{}}            (then the process exits 0)
```

The full recorded session is the golden transcript
`android-compose/sharedLogic/src/commonTest/resources/canvas/plugin/v1/wire/session-chart.jsonl`.
The JVM test `ChartPluginEndToEndTest` drives this plugin with the real `LcpHostSession` and checks
the session against that file. Values that depend on the PNG bytes are masked, because zlib output
can differ between builds. The protocol reference is `docs/reference/canvas-plugin-wire-v1.md`.

Rules the runtime follows that are easy to get wrong:

- Write only protocol messages to stdout. Diagnostics go to stderr or to `host.log`.
- Before `plugin.initialize`, answer every request with `-32002`. Answer `action.invoke` with
  `-32003` unless the session is active.
- A failure that belongs to the plugin, such as invalid input or an unknown action or element, is
  `-32010` with `data.code`. Use the JSON-RPC codes only for protocol failures.
- `$/cancel {id}`: answer the request with `-32800` at once, and make sure the abandoned work
  sends nothing afterwards.
- Answer `plugin.deactivate`, flush stdout, then exit. Also exit when stdin closes.

## Run, test, pack

```bash
cd plugins/chart
node --test test/*.test.mjs  # 14 tests; UPDATE_CHART_FIXTURE=1 rewrites the golden transcript
./pack.sh                    # dist/letta.chart-1.0.0.lcp + sha256:<hex> (needs zip)
open pages/chart.html        # without a host, the page draws a demo chart
```
