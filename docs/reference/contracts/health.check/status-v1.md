# health.check: Controller Status v1

- Method: `health.check` over the authenticated Iroh `admin_rpc` channel.
- Contract version: 1. This documents existing host behaviour; it adds no wire fields.
- Params: a JSON object; no required fields. Unknown fields are ignored.
- Response: an `admin_rpc_response` object with string `request_id` copied from the request,
  boolean `success: true`, and object `result`. `result.native` is boolean `true`.
  A connected controller returns string `status: "ok"`, `controller_state: "connected"`.
  A disconnected controller returns `status: "degraded"`, `controller_state: "disconnected"`.
  A controller error returns `status: "degraded"`, `controller_state: "error"`.
  Without a StateFlow-backed controller the result is degraded with `controller_state: "unavailable"`.
- Errors: no method-specific errors for a controller state. Transport authentication and
  capability checks happen before router dispatch; this contract does not bypass them.
- Scope: no conversation or agent is selected or read. No native command, backend read,
  plugin call, or HTTP fallback is permitted to obtain health.
- Old peers: no negotiation is required; the response keeps its existing fields. A peer
  on a host without this method receives the router's unknown-method error, not a fabricated healthy result.

The fixture exercises connected and disconnected StateFlow snapshots. Host conformance
uses `AdminRpcRegistry.buildRouter` and the real health handler; its native client rejects
all calls. Client doubles can load this same data through `WireContractFixture.response`.

The `conversation.list visible_only` contract is versioned separately under
`conversation.list/visible-recents-v1.md`. The `message.list` default-alias contract
remains uncovered until its real-handler fixture lands.
