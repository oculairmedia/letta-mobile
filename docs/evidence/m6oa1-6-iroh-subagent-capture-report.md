# m6oa1.6 Iroh Subagent Capture Report

## Result

A real isolated synchronous `Agent` dispatch was captured on 2026-08-31 using Node v24.18.0, `@letta-ai/letta-code` 0.29.12, the repository Iroh wrapper, and a fresh local backend. The child ran successfully and returned `AGENT_TOOL_CAPTURED`; the controller persisted the lifecycle as `CONTROLLER_NATIVE`/`COMPLETED`. The client-facing stream nevertheless failed after the populated native snapshot reached the controller because a `JsonArray` was read as a `JsonPrimitive`. This is a production-shape finding, not a fixture result.

Committed evidence:

- `android-compose/sharedLogic/src/jvmTest/resources/appserver/m6oa1-6-app-server-source.jsonl`
- `android-compose/sharedLogic/src/jvmTest/resources/appserver/m6oa1-6-iroh-client-arrivals.jsonl`

Point A combines two direct producer artifacts from the same run: persisted App Server conversation frames and the exact 0.29.12 `buildSubagentSnapshot` producer shape correlated with controller-native registry timestamps. The wrapper had no independent raw-WS tap, so the populated state frames are labelled as reconstructed field-for-field rather than falsely described as packet captures. Point B is the raw arrival order dumped by the real Iroh client.

## Environment and Provenance

- Parent: `parent-agent-1` / `parent-conversation-1` after sanitization.
- Child: synchronous `general-purpose`; fresh child backend agent; child conversation `default`.
- Trigger: Iroh control `runtime_start` then `input`, permission mode `unrestricted`; the controller auto-allowed the real Agent approval.
- Model: `openai/gpt-5.6-terra`; prompt/user payload redacted from committed evidence.
- Source timestamps are UTC. Controller registry millisecond epochs are retained only where needed for latency calculations.
- No endpoint ticket, node ID, provider response ID, bearer token, absolute path, credential, or original UUID remains in committed resources.

## Questions 1-11

1. **Reach and envelope.** The dispatch reached both sides. A emitted `stream_delta/tool_call_message`, populated `update_subagent_state`, and `stream_delta/tool_return_message`. B received the tool call but the populated state path caused a stream error before a usable child-state arrival. Quote A: `"type":"update_subagent_state" ... "status":"running"`. Quote B: `"message":"Element class kotlinx.serialization.json.JsonArray ... is not a JsonPrimitive"`.
2. **Fields at A, B, and populated state.** A tool call has `tool_call_id`, `name`, and arguments. A populated state has `subagent_id`, `subagent_type`, description, status, child `conversation_id`, `tool_call_id`, parent agent/conversation, start time, `tool_calls` array, duration/error when terminal. B preserves tool-call ID/name/arguments, then emits a synthetic error return; it exposes no usable populated state fields because projection fails.
3. **First `tool_call_id`.** `tool_call_id` first appears on A's parent `tool_call_message`, before the running snapshot. It survives intact to B's first Agent tool-call arrival. It is therefore available before child identity.
4. **Running, terminal, and latency.** Running registry observation: 1788191657629; terminal: 1788191661575. Observed child execution window: 3,946 ms. The persisted App Server tool return followed at 1788191661583, 8 ms after the controller's terminal timestamp. B did not receive a successful terminal; the wrapper synthesized an error settlement after projection failure.
5. **Child `conversation_id`.** Running A snapshot has null child conversation. The terminal snapshot and tool return expose `conversation_id:"default"`. Child conversation identity is therefore terminal/return-time for this synchronous run, not launch-time.
6. **Top-level `subagent_id` on stream delta.** No. Parent `stream_delta` tool-call/return envelopes contain no top-level `subagent_id`; identity lives in `update_subagent_state` and inside the textual Agent result. The current client dump therefore cannot correlate child deltas by top-level subagent ID.
7. **Cross-conversation scoping.** All native observations and fanout logs were scoped to the parent conversation; registry rows preserve parent agent/conversation. No evidence of cross-conversation leakage was observed. Only one parent conversation was active, so this is a positive scope observation, not a concurrent isolation proof.
8. **Reconnect replay.** The probe reconnects between its two turns. On the second connection, the first completed child remained in the controller registry while a second child ran, proving controller persistence/reconciliation across reconnect. A usable state replay did not reach B because each populated snapshot hit the same array-to-primitive failure.
9. **Silent filtering.** The synchronous child had `silent` absent/false and was included. The 0.29.12 producer filters only `silent && !is_background`; this run does not empirically test a silent foreground child, so that branch remains unmeasured.
10. **Volume.** For one selected dispatch, A evidence has four lifecycle frames: tool call, running snapshot, tool return, terminal snapshot. B has five arrivals totaling 1,301 bytes before newline separators: user echo, two tool-call updates, synthetic error return, error message. The child ran ~3.95 s, yielding about 1.27 B arrivals/s and 330 bytes/s. The duplicate B tool-call row is an argument-update frame, not a second invocation.
11. **Admin `subagent.list`.** The durable controller registry equivalent contains one terminal row per invocation with `CONTROLLER_NATIVE`, parent scope, canonical tool-call ID, description/type, start/terminal timestamps, and `COMPLETED`. Child agent ID and task ID remain null in that registry row despite being present in the textual Agent return; child conversation is `default`.

## Findings

1. **Populated native state currently breaks the parent stream.** The first real `update_subagent_state` includes an array-valued `tool_calls` field. The controller successfully ingests and persists the row, but another raw-object accessor calls `jsonPrimitive` on an array and raises `Element class ... JsonArray ... is not a JsonPrimitive`. The parent receives an error settlement even though the child succeeded.
2. **The empty-state fixture was a false pass.** Existing tests with `subagents:[]` cannot exercise snapshot entry decoding, array-valued additive fields, terminal identity timing, or the error settlement.
3. **Registry and parent return diverge.** The Agent return contains child agent identity, but the controller-native terminal registry row does not populate it. Consumers cannot reliably navigate to the child from the registry alone.
4. **Synchronous lifecycle is bounded.** One meaningful running state and one terminal state are sufficient; generation churn (17-20, then 28) shows coalescing/reconciliation activity around the same child rather than distinct child states.

## Reproduction and Validation

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
android-compose/gradlew -p android-compose --no-daemon \
  :sharedLogic:jvmJar :iroh-wrapper-cli:installDist :cli:compileDebugKotlin

# The isolated wrapper used --own-app-server, pinned letta-code 0.29.12,
# --allow-insecure-anonymous-iroh, --a2a-port=-1, and a temporary backend.
# The probe used a fresh parent agent/conversation, unrestricted permission,
# client_tool_allowlist=[Agent], and --dump-frames.

while IFS= read -r line; do jq -e . >/dev/null <<<"$line"; done < \
  android-compose/sharedLogic/src/jvmTest/resources/appserver/m6oa1-6-app-server-source.jsonl
while IFS= read -r line; do jq -e . >/dev/null <<<"$line"; done < \
  android-compose/sharedLogic/src/jvmTest/resources/appserver/m6oa1-6-iroh-client-arrivals.jsonl
```

The production source experiments used to expose `Agent` deterministically were not intended as part of this evidence-only change and are not included with the report.
