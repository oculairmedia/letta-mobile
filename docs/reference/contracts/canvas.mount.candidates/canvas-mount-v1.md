# canvas.mount.candidates: Canvas Mount v1

- Method: `canvas.mount.candidates` over the authenticated Iroh `admin_rpc` channel, registered only with a secure
  auth policy and advertised as host capability `canvas.mount.v1`.
- Contract version: 1. The full contract (authorization order, every result, refusal texts,
  deadline and durability) is [canvas-mount-v1.md](../canvas-mount-v1.md).
- Params: exactly `agent_id`, `conversation_id`; any other field is refused with
  `canvas.mount.invalid_input:params:unknown_field`.
- Principal: only the endpoint id the serving connection verified. A connection without one gets
  `{"status": "unavailable", "reason": "identity"}` before params are read.
- The router's agent scope guard refuses an `agent_id` outside a paired peer's scope before the
  handler runs.

This fixture exercises the two answers that need no host records: the missing verified identity
and the router's agent scope guard. Host conformance uses `AdminRpcRegistry.buildRouter` with the
real routes. The authorization matrix, denial, storage, deadline and concurrency cases run in
`com.letta.mobile.data.controller.node.iroh.mounts` against the same router, with fixtures under
`android-compose/sharedLogic/src/jvmTest/resources/canvas-mount/v1/`.
