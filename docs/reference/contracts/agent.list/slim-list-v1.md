# agent.list: Slim List v1

- Method: `agent.list` over authenticated Iroh `admin_rpc`. Owner: App Server v2 (`agent_list`).
- Params: optional `limit` (default 20, at least 1) and `offset` (default 0, at least 0), each a
  string or number. The host emulates `offset` because the App Server pages only by `after` cursor.
- Response: ordinary `admin_rpc_response`, `success: true`, and a JSON array `result` in App Server
  order (most recent run first), hidden agents already removed by the App Server. A short page means
  the end of the roster.
- Every row carries only the list fields, each present only when the App Server row has it, with the
  App Server's value unchanged: `id`, `name`, `description`, `tags`, `hidden`, `metadata` (the
  Meridian sidecar's when stored), `model`, `model_settings`, `llm_config`, `context_window_limit`,
  `last_run_completion`, `created_at`, `updated_at`. That is identity, the visibility inputs, the mascot
  identity, the model label and context window, and last activity.
- Detail fields never appear in a list row: `system`, `tools`, `message_ids`,
  `in_context_message_ids`, `compaction_settings`, memory blocks and anything else the App Server adds.
  A client that needs them (opening the editor, checkpointing config, the memory page) reads one agent
  with `agent.get`, which still returns the whole agent.
- Size: measured against the live host on 2026-11-22, one 136-agent `agent_list` was 2,061,282 bytes,
  1,907,318 of them system prompts; the list fields of the same roster were 94,596 bytes. A list row
  stays under 1 KB.
- An agent-scoped peer sees only its agents' rows. A refused App Server read answers
  `app_server_error: agent.list native command unsuccessful`.
- Compatibility: older hosts send full rows; clients decode either shape because every detail field
  of `Agent` is optional.
- The fixture runs the real handler over a native adapter that applies letta-code 0.30.5 `listAgents`
  (hidden filtering, numeric `limit`); it rejects every other native call.
