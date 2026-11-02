# beads.context v1

Unary admin_rpc route for the bd 1.3.2 `getContext` operation. Requires `beads.read`;
agent-scoped peers are denied. The wrapper owns the bearer credential.

Parameters use `id` or `key` for path parameters, `query` as an object mapping
query keys to arrays of strings, and `body` for the operation's JSON body.
The result is the bd response JSON. Invalid `query` returns
`validation.invalid_params: query`; unsupported capabilities return
`capability_unavailable: beads operation unsupported`; backend failures return
`beads.unauthorized`, `beads.upstream_error`, `beads.unavailable`, or
`beads.invalid_response`.
The route is absent when no beads base URL is configured.

Upstream: `GET /v0/beads/context`. Required: none.

The wrapper is configured with `--beads-base-url` / `LETTA_IROH_BEADS_BASE_URL`
and `--beads-token-file` / `LETTA_IROH_BEADS_TOKEN_FILE`. The named admin role
contains both Beads capabilities; `admin.full` also authorizes them through the
existing override. The desktop, chat and VibeSync roles do not grant them.

The 20 pinned operations outside this route are `health`, `queryIssues`,
`countIssues`, `listRelatedIssues`, `addComment`, `reopenIssue`,
`compareAndSetMetadata`, `sweepIssues`, `deleteIssues`, `batchCreateIssues`,
`applyBatch`, `batchCloseIssues`, `listSettings`, `unsetSetting`,
`listDependencies`, `countDependencyEdges`, `listBlockingAnnotations`,
`getMemory`, `forgetMemory`, and `watchEvents`. The streaming `watchEvents`
protocol is tracked separately by letta-mobile-s33jq; `beads.events.list` polls.
