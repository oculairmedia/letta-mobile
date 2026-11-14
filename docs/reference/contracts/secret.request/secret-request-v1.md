# secret.request: Secret Request v1

- Method: `secret.request` over the authenticated Iroh `admin_rpc` channel (agent side), registered only on a JVM
  host with a paired-peers-only auth policy and a keystore provider, and advertised as host capability `secret.request.v1`.
  Connection gate capability: `secret.request.agent` (granted to no role; `admin.full` passes).
- Contract version: 1. The full contract (principals, order of checks, every result and refusal code) is
  [secret-request-v1.md](../secret-request-v1.md), sections Methods, Errors and Routes.
- Params: Exactly `purpose`, `vaultItemName`, `fieldName` (strings). Any other field, or a non-string field, is refused with `invalid_params`; nothing
  is echoed.
- Result: `{requestId, status: "pending", expiresAtMs}`.
- Principal: the requesting agent, bound by the host tool `secret_request` to the agent id the host runtime
  stamped on the tool call; a claimed (unverified) identity is refused `forbidden`. No admin RPC connection is an agent: every connection caller, `admin.full`
  included, gets `forbidden` before params are read.
- Refusals are `{"success": false, "error": "<code>"}`; `invalid_request` carries its problem as
  `invalid_request:<problem>`.

This fixture exercises the answer that needs no host records: the request context the fixture runner
can express (authenticated, conversation and agent scope) never establishes the agent principal, so
the route refuses with `forbidden`. Host conformance uses `AdminRpcRegistry.buildRouter` with the real
routes over a test keystore. The principal matrix, denial, lifecycle, race, restart and sentinel cases
run in `com.letta.mobile.data.controller.node.iroh.secrets` against the same router.
