# App Server protocol fixtures

These fixtures document the Letta Code App Server v2 frame contract used by the
shared client tests.

Live capture attempted on 2026-06-24 with:

```text
pnpm dlx @letta-ai/letta-code@0.27.10 app-server --listen ws://127.0.0.1:4510
```

The server started and accepted `runtime_start`, `sync`, `input`, and
`abort_message` commands on `/ws?channel=control`, but the local Letta auth
refresh token was revoked, so the run could not produce a complete successful
`stream_delta -> stop_reason` capture. The committed protocol tests therefore
use JSON frames derived from `dist/types/types/protocol_v2.d.ts` in the same
0.27.10 package and should be replaced or augmented with raw JSONL once a valid
host App Server profile is available.
