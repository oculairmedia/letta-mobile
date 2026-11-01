# reflection.get: Reflection Settings v2

- Method: `reflection.get` over the authenticated Iroh `admin_rpc` channel.
- Contract version: 2. This extends `reflection_settings` with optional model, focus, and
  last-outcome fields while retaining the existing trigger, step-count, merge, and scope fields.
- Params: a JSON object with required string `agent_id` and optional string `conversation_id`.
  A missing or blank conversation id selects the agent's `default` conversation. Unknown fields
  are ignored.
- Response: an `admin_rpc_response` object with string `request_id` copied from the request,
  boolean `success: true`, and object `result`. `result.reflection_settings` is the App Server's
  settings object. A patched host includes all three v2 fields: nullable string `model` (`null`
  means inherit the agent model), string `focus_instructions`, and nullable object `last_outcome`.
  A last outcome has string `status` (`launched`, `skipped`, `failed`, or `completed`), optional
  strings `reason`, `trigger_source`, and `model`, and required ISO-8601 string `at`.
- Errors: a missing or non-string `agent_id` is invalid params. A missing native App Server client
  is `capability_unavailable`; an unsuccessful App Server response is returned as a method error.
- Scope: the router applies the authenticated peer's agent and conversation authorization before
  dispatch. The handler reads only the selected runtime's persisted reflection settings.
- Old peers: an older host can omit `model`, `focus_instructions`, and `last_outcome`. Their absence
  means the v2 controls are unsupported, not that the model is inherited or the instructions are
  empty. Unknown last-outcome status strings remain displayable as an unknown outcome.

The fixture exercises the real reflection handler with both a complete patched-host object and a
legacy object that omits all v2 fields. Its native App Server double returns the fixture's exact
settings object; the handler must preserve JSON nulls and unknown-field omission without inventing
defaults.
