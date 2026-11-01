# reflection.set: Reflection Settings v2

- Method: `reflection.set` over the authenticated Iroh `admin_rpc` channel.
- Contract version: 2. This adds partial writes for reflection model and focus instructions without
  changing the existing trigger, step-count, merge, merge-instructions, or persistence scope.
- Params: a JSON object with required strings `agent_id` and `trigger`, required positive integer
  `step_count`, and optional string `conversation_id`, `merge`, `merge_instructions`, and `scope`.
  Optional `model` is either a non-blank model-handle string or JSON null; null clears an override
  and restores agent-model inheritance. Optional `focus_instructions` is a string. Omitted `model`
  or `focus_instructions` leaves the stored value unchanged. Clients never send `last_outcome`.
- Response: an `admin_rpc_response` object with string `request_id` copied from the request,
  boolean `success: true`, and object `result`. `result.reflection_settings` is the complete stored
  settings object returned by the App Server. A patched host includes nullable string `model`,
  string `focus_instructions`, and nullable object `last_outcome` in that object. Optional string
  `result.scope` reports the persistence scope selected by the host.
- Errors: invalid trigger, step count, merge mode, scope, model type/blank handle, or focus type is
  invalid params and no App Server mutation occurs. A missing native App Server client is
  `capability_unavailable`; an unsuccessful App Server response is returned as a method error.
- Scope: the router applies the authenticated peer's agent and conversation authorization before
  dispatch. `scope` accepts `local_project`, `global`, or `both`; omission retains the host's
  conversation/default persistence behavior.
- Old clients: omission is deliberately distinct from JSON null. An old client changing only
  trigger settings cannot erase a model override or focus instructions stored by a newer client.
- Old hosts: an older host can omit all v2 response fields and may reject v2 request fields. The
  client hides v2 controls until a get response proves support, and sends v2 fields only after the
  user changes them.

The fixture exercises the real reflection handler and an App Server double that applies the legacy
trigger change over an existing v2 settings object. It proves an old-client-shaped set preserves
model, focus instructions, and last outcome.
