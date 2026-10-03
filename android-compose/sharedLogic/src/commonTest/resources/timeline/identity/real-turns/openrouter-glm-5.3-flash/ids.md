## openrouter-glm-5.3-flash


### Raw wire (stream_delta.delta + envelope), viewer connection

**turn1**: 188 frames, 177 text frames. Types: user_message=1, reasoning_message=37, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=140

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 75000024 | <n> |
| id | approval_classification_end | lifecycle-b50a8d84-db35-49f5-a4e0-f5c6a4106cbe | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-804a02d7-7d93-4268-9e7f-fd503b0653f8 | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-799 | local-run-<n> |
| event_seq | assistant_message | 140 | <n> |
| id | assistant_message | ui-msg-9184640:assistant:1 | ui-msg-<n>:assistant:<n> |
| idempotency_key | assistant_message | 140 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-800 | local-run-<n> |
| seq_id | assistant_message | 140 | <n> |
| event_seq | client_tool_end | 75000027 | <n> |
| idempotency_key | client_tool_end | iroh-delta-59a486fc-b55b-4642-ae2f-d69742e5da8e | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-799 | local-run-<n> |
| tool_call_id | client_tool_end | call_a556f448d7d14817b1db38cc | call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| event_seq | client_tool_start | 75000025 | <n> |
| idempotency_key | client_tool_start | iroh-delta-b18e656a-e74c-4dc6-8346-61e56f87e8c1 | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-799 | local-run-<n> |
| tool_call_id | client_tool_start | call_a556f448d7d14817b1db38cc | call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| event_seq | reasoning_message | 37 | <n> |
| id | reasoning_message | 2 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | 37 | iroh-delta-<uuid> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq_id | reasoning_message | 19 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 75000021 | <n> |
| idempotency_key | tool_call_message | iroh-delta-54ac72bb-5f5f-48f9-a08b-9465add37511 | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-799 | local-run-<n> |
| tool_call_id | tool_call_message | call_a556f448d7d14817b1db38cc | call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-799 | local-run-<n> |
| tool_call_id | tool_return_message | call_a556f448d7d14817b1db38cc | call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 75000001 | <n> |
| id | user_message | cm-user-capture-turn1-d7b5c1b9-07a6-4a12-93e9-de5e58905473 | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-7e697095-0779-40c0-8ded-51778eefdb5b | iroh-delta-<uuid> |
| otid | user_message | capture-turn1-d7b5c1b9-07a6-4a12-93e9-de5e58905473 | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184640:assistant:1`: 140 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 139}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184638:reasoning:0`: 19 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 18}
- key `ui-msg-9184640:reasoning:0`: 18 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 17}

**turn2**: 223 frames, 212 text frames. Types: user_message=1, reasoning_message=37, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=175

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 75000208 | <n> |
| id | approval_classification_end | lifecycle-1ae1b9f3-f811-4579-b0c4-9d6365dbc0f7 | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-7ef78e62-ea78-4217-b20e-9a947160b576 | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-801 | local-run-<n> |
| event_seq | assistant_message | 175 | <n> |
| id | assistant_message | ui-msg-9184644:assistant:1 | ui-msg-<n>:assistant:<n> |
| idempotency_key | assistant_message | 175 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-802 | local-run-<n> |
| seq_id | assistant_message | 175 | <n> |
| event_seq | client_tool_end | 75000211 | <n> |
| idempotency_key | client_tool_end | iroh-delta-e211debb-0917-4625-b86f-923384b0bc61 | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-801 | local-run-<n> |
| tool_call_id | client_tool_end | call_19b91781d2224a0c97d84483 | call_<n>b<n>d<n>a<n>c<n>d<n> |
| event_seq | client_tool_start | 75000209 | <n> |
| idempotency_key | client_tool_start | iroh-delta-6b30ed02-c9a5-491a-b34b-91208b4e3743 | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-801 | local-run-<n> |
| tool_call_id | client_tool_start | call_19b91781d2224a0c97d84483 | call_<n>b<n>d<n>a<n>c<n>d<n> |
| event_seq | reasoning_message | 37 | <n> |
| id | reasoning_message | 2 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | 37 | iroh-delta-<uuid> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq_id | reasoning_message | 22 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 75000205 | <n> |
| idempotency_key | tool_call_message | iroh-delta-d0bbb167-67f2-4c23-865c-55dbd70c657b | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-801 | local-run-<n> |
| tool_call_id | tool_call_message | call_19b91781d2224a0c97d84483 | call_<n>b<n>d<n>a<n>c<n>d<n> |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>b<n>d<n>a<n>c<n>d<n> |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-801 | local-run-<n> |
| tool_call_id | tool_return_message | call_19b91781d2224a0c97d84483 | call_<n>b<n>d<n>a<n>c<n>d<n> |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 75000189 | <n> |
| id | user_message | cm-user-capture-turn2-cabdb2a4-64c7-47ed-99bd-da637d78f0dc | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-658e0fa3-d6fa-42c6-b5e4-8066413ae903 | iroh-delta-<uuid> |
| otid | user_message | capture-turn2-cabdb2a4-64c7-47ed-99bd-da637d78f0dc | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184644:assistant:1`: 175 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 174}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184642:reasoning:0`: 15 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 14}
- key `ui-msg-9184644:reasoning:0`: 22 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 21}


### Client ServerFrames on A-desktop (after IrohChannelTransport mapping)

**turn1**: 190 frames, 177 text frames. Types: turn_started=2, user_message=1, reasoning_message=37, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=140, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184640:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-800 | local-run-<n> |
| seq | assistant_message | 140 | <n> |
| seq_id | assistant_message | 140 | <n> |
| turn_id | assistant_message | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |
| id | reasoning_message | 2 | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq | reasoning_message | 37 | <n> |
| seq_id | reasoning_message | 37 | <n> |
| turn_id | reasoning_message | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_a556f448d7d14817b1db38cc | toolcall-call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| run_id | tool_call_message | local-run-799 | local-run-<n> |
| seq | tool_call_message | 73000022 | <n> |
| tool_call_id | tool_call_message | call_a556f448d7d14817b1db38cc | call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| turn_id | tool_call_message | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_a<n>f<n>d<n>d<n>b<n>db<n>cc; toolreturn-call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| run_id | tool_return_message | local-run-799 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_a556f448d7d14817b1db38cc | call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| turn_id | tool_return_message | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |
| id | turn_done | turn_done-06130c23-886f-4741-aefd-28a567782358 | turn_done-<uuid> |
| run_id | turn_done | local-run-799 | local-run-<n> |
| turn_id | turn_done | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn1-d7b5c1b9-07a6-4a12-93e9-de5e58905473 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-d7b5c1b9-07a6-4a12-93e9-de5e58905473 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-24fc5081-d1ab-4e9e-bfa0-c130b35bab54 | iroh-run-<uuid> |
| seq | user_message | 73000001 | <n> |
| seq_id | user_message | 73000001 | <n> |
| turn_id | user_message | iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84 | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184640:assistant:1', 'iroh-assistant-iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84')`: 140 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 139}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-799-iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84', None)`: 19 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 18}
- key `('iroh-reasoning_message-local-run-800-iroh-turn-5c8a8c33-8385-44d7-8f79-7d0e6b51bb84', None)`: 18 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 17}

**turn2**: 225 frames, 212 text frames. Types: turn_started=2, user_message=1, reasoning_message=37, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=175, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184644:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-802 | local-run-<n> |
| seq | assistant_message | 175 | <n> |
| seq_id | assistant_message | 175 | <n> |
| turn_id | assistant_message | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |
| id | reasoning_message | 2 | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq | reasoning_message | 37 | <n> |
| seq_id | reasoning_message | 37 | <n> |
| turn_id | reasoning_message | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_19b91781d2224a0c97d84483 | toolcall-call_<n>b<n>d<n>a<n>c<n>d<n> |
| run_id | tool_call_message | local-run-801 | local-run-<n> |
| seq | tool_call_message | 73000210 | <n> |
| tool_call_id | tool_call_message | call_19b91781d2224a0c97d84483 | call_<n>b<n>d<n>a<n>c<n>d<n> |
| turn_id | tool_call_message | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>b<n>d<n>a<n>c<n>d<n>; toolreturn-call_<n>b<n>d<n>a<n>c<n>d<n> |
| run_id | tool_return_message | local-run-801 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_19b91781d2224a0c97d84483 | call_<n>b<n>d<n>a<n>c<n>d<n> |
| turn_id | tool_return_message | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |
| id | turn_done | turn_done-f360b63b-9978-49dc-8558-1ce102230a09 | turn_done-<uuid> |
| run_id | turn_done | local-run-801 | local-run-<n> |
| turn_id | turn_done | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn2-cabdb2a4-64c7-47ed-99bd-da637d78f0dc | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-cabdb2a4-64c7-47ed-99bd-da637d78f0dc | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-a528fbd0-4eaf-4438-94ce-d3335d6eaf90 | iroh-run-<uuid> |
| seq | user_message | 73000193 | <n> |
| seq_id | user_message | 73000193 | <n> |
| turn_id | user_message | iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184644:assistant:1', 'iroh-assistant-iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd')`: 175 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 174}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-801-iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd', None)`: 15 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 14}
- key `('iroh-reasoning_message-local-run-802-iroh-turn-312a7c4f-78be-4faa-81f7-6e39a389a3bd', None)`: 22 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 21}


### Client ServerFrames on B-phone (after IrohChannelTransport mapping)

**turn1**: 188 frames, 177 text frames. Types: user_message=1, reasoning_message=37, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=140, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184640:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-592 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-800 | local-run-<n> |
| seq | assistant_message | 140 | <n> |
| seq_id | assistant_message | 140 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | 2 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq | reasoning_message | 37 | <n> |
| seq_id | reasoning_message | 37 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_a556f448d7d14817b1db38cc | toolcall-call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| run_id | tool_call_message | local-run-799 | local-run-<n> |
| seq | tool_call_message | 74000021 | <n> |
| tool_call_id | tool_call_message | call_a556f448d7d14817b1db38cc | call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_a<n>f<n>d<n>d<n>b<n>db<n>cc; toolreturn-call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| run_id | tool_return_message | local-run-799 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_a556f448d7d14817b1db38cc | call_a<n>f<n>d<n>d<n>b<n>db<n>cc |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-66ca79e2-922d-494f-957f-07fe005bb5e0 | turn_done-<uuid> |
| run_id | turn_done | local-run-800 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn1-d7b5c1b9-07a6-4a12-93e9-de5e58905473 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-d7b5c1b9-07a6-4a12-93e9-de5e58905473 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-592 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 74000001 | <n> |
| seq_id | user_message | 74000001 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184640:assistant:1', 'iroh-assistant-iroh-observer-turn-local-conv-592')`: 140 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 139}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-799-iroh-observer-turn-local-conv-592', None)`: 19 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 18}
- key `('iroh-reasoning_message-local-run-800-iroh-observer-turn-local-conv-592', None)`: 18 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 17}

**turn2**: 223 frames, 212 text frames. Types: user_message=1, reasoning_message=37, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=175, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184644:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-592 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-802 | local-run-<n> |
| seq | assistant_message | 175 | <n> |
| seq_id | assistant_message | 175 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | 2 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq | reasoning_message | 37 | <n> |
| seq_id | reasoning_message | 37 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_19b91781d2224a0c97d84483 | toolcall-call_<n>b<n>d<n>a<n>c<n>d<n> |
| run_id | tool_call_message | local-run-801 | local-run-<n> |
| seq | tool_call_message | 74000205 | <n> |
| tool_call_id | tool_call_message | call_19b91781d2224a0c97d84483 | call_<n>b<n>d<n>a<n>c<n>d<n> |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>b<n>d<n>a<n>c<n>d<n>; toolreturn-call_<n>b<n>d<n>a<n>c<n>d<n> |
| run_id | tool_return_message | local-run-801 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_19b91781d2224a0c97d84483 | call_<n>b<n>d<n>a<n>c<n>d<n> |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-0c3ec341-abe6-4e2b-9218-ceffe136fe83 | turn_done-<uuid> |
| run_id | turn_done | local-run-802 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn2-cabdb2a4-64c7-47ed-99bd-da637d78f0dc | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-cabdb2a4-64c7-47ed-99bd-da637d78f0dc | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-592 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 74000189 | <n> |
| seq_id | user_message | 74000189 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-592 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184644:assistant:1', 'iroh-assistant-iroh-observer-turn-local-conv-592')`: 175 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 174}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-801-iroh-observer-turn-local-conv-592', None)`: 15 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 14}
- key `('iroh-reasoning_message-local-run-802-iroh-observer-turn-local-conv-592', None)`: 22 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 21}


### message.list after turn1 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184640:assistant:1 | assistant_message | None | None | None | None | 1492 |
| ui-msg-9184640:reasoning:0 | reasoning_message | None | None | None | None | 227 |
| ui-msg-9184639 | tool_return_message | None | None | None | None |  |
| ui-msg-9184638:tool:call_a556f448d7d14817b1db38cc:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184638:reasoning:0 | reasoning_message | None | None | None | None | 274 |
| ui-msg-9184637 | user_message | capture-turn1-d7b5c1b9-07a6-4a12-93e9-de5e58905473 | None | None | None | 1490 |

### message.list after turn2 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184644:assistant:1 | assistant_message | None | None | None | None | 1732 |
| ui-msg-9184644:reasoning:0 | reasoning_message | None | None | None | None | 228 |
| ui-msg-9184643 | tool_return_message | None | None | None | None |  |
| ui-msg-9184642:tool:call_19b91781d2224a0c97d84483:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184642:reasoning:0 | reasoning_message | None | None | None | None | 243 |
| ui-msg-9184641 | user_message | capture-turn2-cabdb2a4-64c7-47ed-99bd-da637d78f0dc | None | None | None | 276 |
| ui-msg-9184640:assistant:1 | assistant_message | None | None | None | None | 1492 |
| ui-msg-9184640:reasoning:0 | reasoning_message | None | None | None | None | 227 |
| ui-msg-9184639 | tool_return_message | None | None | None | None |  |
| ui-msg-9184638:tool:call_a556f448d7d14817b1db38cc:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184638:reasoning:0 | reasoning_message | None | None | None | None | 274 |
| ui-msg-9184637 | user_message | capture-turn1-d7b5c1b9-07a6-4a12-93e9-de5e58905473 | None | None | None | 1490 |
