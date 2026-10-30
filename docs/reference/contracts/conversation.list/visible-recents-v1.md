# conversation.list: Visible Recents v1

- Method: `conversation.list` over authenticated Iroh `admin_rpc`.
- Opt-in: JSON boolean `visible_only: true`, with none of `agent_id`, `summary_search`,
  `archive_status`, `order`, or `order_by`. A string `"true"` is ignored, not rejected.
- Optional `limit` defaults to 30 when absent or invalid. Integer negative limits retain
  the App Server prefix semantics. Optional `after` names a visible conversation; an
  unknown cursor restarts at the top. Ordering is newest first by
  the first parseable timestamp among `last_message_at`, `updated_at`, and `created_at`
  (zero if none parse), then conversation id ascending.
- Response: ordinary `admin_rpc_response`, `success: true`, and a JSON array `result`.
  Before cursor and limit, exclude agent-free rows, hidden agents (explicit hidden flag,
  otherwise role:subagent tag), hidden conversations, archived conversations, and rows
  outside authorized agent or conversation scopes. A short page means end of list.
- Errors fail closed with `capability_unavailable`: no local visibility index, visibility
  never read successfully, or more than 20000 conversations. A failed index reload keeps
  its last successful hidden-agent set. No truncated raw window substitutes for recents.
- Requests that do not opt in retain the legacy per-agent/search/unfiltered semantics.
  Older hosts ignore the flag. Clients detect agent-free raw rows before decoding and
  fall back to per-agent lists; capability-unavailable also enables this fallback.
- The fixture exercises real-handler filtering before limit, scoped filtering, visible
  and unknown cursors, an exact 30-row window with an older owned conversation outside it,
  ignored string opt-in, and missing-index failure. The native
  adapter supplies only declared conversation data and rejects other native calls.
  Client fakes return the same `WireContractFixture.response` envelopes.
