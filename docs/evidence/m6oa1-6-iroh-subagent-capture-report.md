# m6oa1.6 Iroh Subagent Capture Report

## Result

Capture attempt timestamp: 2026-08-31T15:00:00Z. This is a real isolated run of Node v24.18.0 and `@letta-ai/letta-code` 0.29.12 through the repository Iroh wrapper. It did **not** create a real Agent dispatch; consequently required evidence points A and B could not capture. No fixtures, mocks, or reconstructed frames are used.

## Environment and Provenance

- Isolated wrapper process: `bash_6`; isolated probe process: `bash_10`; the wrapper owned its App Server child.
- App Server pin: Node `v24.18.0`, `@letta-ai/letta-code` `0.29.12`.
- Parent conversation requested: `capture-parent-a` (not created because runtime start rejected).
- Stable identities, endpoint ticket, node IDs, paths, user content, and secrets were deliberately omitted from committed evidence.
- Committed capture resource: `android-compose/sharedLogic/src/jvmTest/resources/appserver/m6oa1-6-real-capture-blocked.jsonl`.

## Real Observations

B / isolated Iroh client / 2026-08-31T15:00:00Z: `frame.recv channel=Control type=auth_response`.

B / isolated Iroh client / 2026-08-31T15:00:00Z: `frame.recv channel=Control type=runtime_start_response`.

B / isolated Iroh client / parent `capture-parent-a` / 2026-08-31T15:00:00Z: `probe_error: agent_id and conversation_id are required`.

The last line is emitted after the Iroh runtime start returned without an agent identity. The probe recorded no input, stream delta, child conversation, tool call, terminal child frame, or `subagent.list` result. The isolated wrapper shutdown immediately afterward; only isolated processes were involved.

## Questions 1-11

1. Reach/envelope: could not capture. The transport reached Iroh auth and runtime-start control frames, but no Agent dispatch or raw direct App Server WS capture point was available.
2. Field table A/B/populated: could not capture. No real dispatch frame exists.
3. First-frame `tool_call_id` timing: could not capture. No tool call occurred.
4. Running/terminal frames and exit-to-B latency: could not capture. No child run occurred.
5. Child `conversation_id` timing: could not capture. No child conversation was created.
6. Top-level `subagent_id` survival on `stream_delta`: could not capture. No stream delta occurred.
7. Cross-conversation scoping: could not capture. Parent conversation was rejected before creation.
8. Reconnect replay: could not capture. There was no in-flight child to reconnect during.
9. Silent filtering: could not capture. Static source findings are intentionally not substituted for observed behavior.
10. Volume: could not capture for dispatch frames. Observed control frames: 2 (`auth_response`, `runtime_start_response`); dispatch bytes/rates: unavailable.
11. Admin `subagent.list`: could not capture. No running child existed to query.

## Blocking Details

The allowed `app-server-iroh-probe` defaults its agent id to empty. Its runtime-start control frame reached the isolated wrapper with `agentId=null`, and the real runtime then returned `agent_id and conversation_id are required`. The isolated local backend contained unrelated pre-existing agents but could not be reused safely: this task requires a fresh real dispatch and sanitized evidence, not host-user content. The requested raw direct App Server WS frame capture is not exposed by the existing allowed capture tooling; the wrapper only exposes post-controller Iroh transport. No production source changes were made to work around either limitation.

## Validation

`jq -e . android-compose/sharedLogic/src/jvmTest/resources/appserver/m6oa1-6-real-capture-blocked.jsonl` passed for each line. A secret/path scan over committed artifact paths found no endpoint tickets, bearer tokens, node ids, or absolute host paths.
