# beads.issues.close v1

Unary admin_rpc route for the bd 1.3.2 `closeIssue` operation. Requires `beads.write`;
agent-scoped peers are denied. The wrapper owns the bearer credential.

Parameters use `id` or `key` for path parameters, `query` as an object mapping
query keys to arrays of strings, and `body` for the operation's JSON body.
The result is the bd response JSON. Invalid `query` returns
`validation.invalid_params: query`; unsupported capabilities return
`capability_unavailable: beads operation unsupported`; backend failures return
`beads.unauthorized`, `beads.upstream_error`, `beads.unavailable`, or
`beads.invalid_response`.
The route is absent when no beads base URL is configured.

Upstream: `POST /v0/beads/issues/{id}:close`. Required: top-level `id` string, `body`.
