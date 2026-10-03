## openrouter-grok-4.7


### Raw wire (stream_delta.delta + envelope), viewer connection

**turn1**: 260 frames, 249 text frames. Types: user_message=1, assistant_message=244, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, reasoning_message=5

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 78000022 | <n> |
| id | approval_classification_end | lifecycle-51c95a89-715e-428b-af26-d65aa6c1a501 | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-7d88f85d-873a-4232-8ef4-f78600755f6a | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-803 | local-run-<n> |
| event_seq | assistant_message | 244 | <n> |
| id | assistant_message | 2 | ui-msg-<n>; ui-msg-<n>:assistant:<n> |
| idempotency_key | assistant_message | 244 | iroh-delta-<uuid> |
| run_id | assistant_message | 2 | local-run-<n> |
| seq_id | assistant_message | 232 | <n> |
| event_seq | client_tool_end | 78000025 | <n> |
| idempotency_key | client_tool_end | iroh-delta-bdb44a13-4e9e-419b-990b-665d909e81be | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-803 | local-run-<n> |
| tool_call_id | client_tool_end | call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | call-<uuid>-<n> |
| event_seq | client_tool_start | 78000023 | <n> |
| idempotency_key | client_tool_start | iroh-delta-cc9a44fb-88c7-4056-be5f-a942171ed66d | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-803 | local-run-<n> |
| tool_call_id | client_tool_start | call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | call-<uuid>-<n> |
| event_seq | reasoning_message | 5 | <n> |
| id | reasoning_message | ui-msg-9184648:reasoning:0 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | 5 | iroh-delta-<uuid> |
| run_id | reasoning_message | local-run-804 | local-run-<n> |
| seq_id | reasoning_message | 5 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 78000019 | <n> |
| idempotency_key | tool_call_message | iroh-delta-94503adb-c182-4f95-98bb-ca705ecb7bd7 | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-803 | local-run-<n> |
| tool_call_id | tool_call_message | call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | call-<uuid>-<n> |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call-<uuid>-<n> |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-803 | local-run-<n> |
| tool_call_id | tool_return_message | call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | call-<uuid>-<n> |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 78000001 | <n> |
| id | user_message | cm-user-capture-turn1-dbedc2d8-1a17-40d7-9cce-594e607a4c2e | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-902523ca-bda6-43c2-a4c6-eedaf5027e9a | iroh-delta-<uuid> |
| otid | user_message | capture-turn1-dbedc2d8-1a17-40d7-9cce-594e607a4c2e | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184646`: 17 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 16}
- key `ui-msg-9184648:assistant:1`: 227 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 226}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184648:reasoning:0`: 5 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 4}

**turn2**: 292 frames, 281 text frames. Types: user_message=1, assistant_message=281, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 78000288 | <n> |
| id | approval_classification_end | lifecycle-6e4033f2-0a5e-4481-a8b5-2ed73d4553aa | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-69ce5c34-6554-4ca8-a681-40ca0f1c41ac | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-805 | local-run-<n> |
| event_seq | assistant_message | 281 | <n> |
| id | assistant_message | 2 | ui-msg-<n> |
| idempotency_key | assistant_message | 281 | iroh-delta-<uuid> |
| run_id | assistant_message | 2 | local-run-<n> |
| seq_id | assistant_message | 258 | <n> |
| event_seq | client_tool_end | 78000291 | <n> |
| idempotency_key | client_tool_end | iroh-delta-da54d528-3992-4b95-9d9c-7337459bd16d | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-805 | local-run-<n> |
| tool_call_id | client_tool_end | call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | call-<uuid>-<n> |
| event_seq | client_tool_start | 78000289 | <n> |
| idempotency_key | client_tool_start | iroh-delta-e5984731-38a5-4286-8da7-6cc109743e1b | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-805 | local-run-<n> |
| tool_call_id | client_tool_start | call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | call-<uuid>-<n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 78000285 | <n> |
| idempotency_key | tool_call_message | iroh-delta-523b127f-5cbe-4b47-893b-4739e7d3bc1a | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-805 | local-run-<n> |
| tool_call_id | tool_call_message | call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | call-<uuid>-<n> |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call-<uuid>-<n> |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-805 | local-run-<n> |
| tool_call_id | tool_return_message | call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | call-<uuid>-<n> |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 78000261 | <n> |
| id | user_message | cm-user-capture-turn2-ef789a06-10ff-4e35-b397-5edea9a99023 | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-9f88d69c-f6c9-4513-9864-647b81857b55 | iroh-delta-<uuid> |
| otid | user_message | capture-turn2-ef789a06-10ff-4e35-b397-5edea9a99023 | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184650`: 23 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 22}
- key `ui-msg-9184652`: 258 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 257}


### Client ServerFrames on A-desktop (after IrohChannelTransport mapping)

**turn1**: 262 frames, 249 text frames. Types: turn_started=2, user_message=1, assistant_message=244, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, reasoning_message=5, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | 2 | ui-msg-<n>; ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | 2 | local-run-<n> |
| seq | assistant_message | 244 | <n> |
| seq_id | assistant_message | 244 | <n> |
| turn_id | assistant_message | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |
| id | reasoning_message | iroh-reasoning_message-local-run-804-iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | local-run-804 | local-run-<n> |
| seq | reasoning_message | 5 | <n> |
| seq_id | reasoning_message | 5 | <n> |
| turn_id | reasoning_message | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | toolcall-call-<uuid>-<n> |
| run_id | tool_call_message | local-run-803 | local-run-<n> |
| seq | tool_call_message | 76000020 | <n> |
| tool_call_id | tool_call_message | call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | call-<uuid>-<n> |
| turn_id | tool_call_message | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call-<uuid>-<n>; toolreturn-call-<uuid>-<n> |
| run_id | tool_return_message | local-run-803 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | call-<uuid>-<n> |
| turn_id | tool_return_message | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |
| id | turn_done | turn_done-a8c93d50-a4d5-4b5d-abd0-c09898d75268 | turn_done-<uuid> |
| run_id | turn_done | local-run-803 | local-run-<n> |
| turn_id | turn_done | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn1-dbedc2d8-1a17-40d7-9cce-594e607a4c2e | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-dbedc2d8-1a17-40d7-9cce-594e607a4c2e | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-80dd167b-658a-4eab-9e1d-954891592933 | iroh-run-<uuid> |
| seq | user_message | 76000001 | <n> |
| seq_id | user_message | 76000001 | <n> |
| turn_id | user_message | iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10 | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184646', 'iroh-assistant-iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10')`: 17 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 16}
- key `('ui-msg-9184648:assistant:1', 'iroh-assistant-iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10')`: 227 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 226}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-804-iroh-turn-18aee15c-262b-4202-8cfa-5d2767ba3c10', None)`: 5 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 4}

**turn2**: 294 frames, 281 text frames. Types: turn_started=2, user_message=1, assistant_message=281, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | 2 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | 2 | local-run-<n> |
| seq | assistant_message | 281 | <n> |
| seq_id | assistant_message | 281 | <n> |
| turn_id | assistant_message | iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | toolcall-call-<uuid>-<n> |
| run_id | tool_call_message | local-run-805 | local-run-<n> |
| seq | tool_call_message | 76000290 | <n> |
| tool_call_id | tool_call_message | call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | call-<uuid>-<n> |
| turn_id | tool_call_message | iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call-<uuid>-<n>; toolreturn-call-<uuid>-<n> |
| run_id | tool_return_message | local-run-805 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | call-<uuid>-<n> |
| turn_id | tool_return_message | iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-turn-<uuid> |
| id | turn_done | turn_done-32debed3-4fca-4f4f-a85c-66c4f08e77b0 | turn_done-<uuid> |
| run_id | turn_done | local-run-805 | local-run-<n> |
| turn_id | turn_done | iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn2-ef789a06-10ff-4e35-b397-5edea9a99023 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-ef789a06-10ff-4e35-b397-5edea9a99023 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-e25680d7-b268-4648-93c3-013a03c68d4b | iroh-run-<uuid> |
| seq | user_message | 76000265 | <n> |
| seq_id | user_message | 76000265 | <n> |
| turn_id | user_message | iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694 | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184650', 'iroh-assistant-iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694')`: 23 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 22}
- key `('ui-msg-9184652', 'iroh-assistant-iroh-turn-22f7bcb4-f765-4b8b-bf03-a1dd173bb694')`: 258 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 257}


### Client ServerFrames on B-phone (after IrohChannelTransport mapping)

**turn1**: 260 frames, 249 text frames. Types: user_message=1, assistant_message=244, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, reasoning_message=5, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | 2 | ui-msg-<n>; ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-593 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | 2 | local-run-<n> |
| seq | assistant_message | 244 | <n> |
| seq_id | assistant_message | 244 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | iroh-reasoning_message-local-run-804-iroh-observer-turn-local-conv-593 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | local-run-804 | local-run-<n> |
| seq | reasoning_message | 5 | <n> |
| seq_id | reasoning_message | 5 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | toolcall-call-<uuid>-<n> |
| run_id | tool_call_message | local-run-803 | local-run-<n> |
| seq | tool_call_message | 77000019 | <n> |
| tool_call_id | tool_call_message | call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | call-<uuid>-<n> |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call-<uuid>-<n>; toolreturn-call-<uuid>-<n> |
| run_id | tool_return_message | local-run-803 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call-affb8ba6-8372-499d-9f81-2823f83cc115-0 | call-<uuid>-<n> |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-249a9479-3a88-4301-a790-e4a9b1c1dec7 | turn_done-<uuid> |
| run_id | turn_done | local-run-804 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn1-dbedc2d8-1a17-40d7-9cce-594e607a4c2e | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-dbedc2d8-1a17-40d7-9cce-594e607a4c2e | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-593 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 77000001 | <n> |
| seq_id | user_message | 77000001 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184646', 'iroh-assistant-iroh-observer-turn-local-conv-593')`: 17 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 16}
- key `('ui-msg-9184648:assistant:1', 'iroh-assistant-iroh-observer-turn-local-conv-593')`: 227 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 226}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-804-iroh-observer-turn-local-conv-593', None)`: 5 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 4}

**turn2**: 292 frames, 281 text frames. Types: user_message=1, assistant_message=281, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | 2 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-593 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | 2 | local-run-<n> |
| seq | assistant_message | 281 | <n> |
| seq_id | assistant_message | 281 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | toolcall-call-<uuid>-<n> |
| run_id | tool_call_message | local-run-805 | local-run-<n> |
| seq | tool_call_message | 77000285 | <n> |
| tool_call_id | tool_call_message | call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | call-<uuid>-<n> |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call-<uuid>-<n>; toolreturn-call-<uuid>-<n> |
| run_id | tool_return_message | local-run-805 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1 | call-<uuid>-<n> |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-f764580b-bbc0-4fb7-bfa1-d1eef30f6387 | turn_done-<uuid> |
| run_id | turn_done | local-run-806 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn2-ef789a06-10ff-4e35-b397-5edea9a99023 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-ef789a06-10ff-4e35-b397-5edea9a99023 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-593 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 77000261 | <n> |
| seq_id | user_message | 77000261 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-593 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184650', 'iroh-assistant-iroh-observer-turn-local-conv-593')`: 23 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 22}
- key `('ui-msg-9184652', 'iroh-assistant-iroh-observer-turn-local-conv-593')`: 258 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 257}


### message.list after turn1 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184648:assistant:1 | assistant_message | None | None | None | None | 1117 |
| ui-msg-9184648:reasoning:0 | reasoning_message | None | None | None | None | 91 |
| ui-msg-9184647 | tool_return_message | None | None | None | None |  |
| ui-msg-9184646:tool:call-affb8ba6-8372-499d-9f81-2823f83cc115-0:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184646 | assistant_message | None | None | None | None | 70 |
| ui-msg-9184645 | user_message | capture-turn1-dbedc2d8-1a17-40d7-9cce-594e607a4c2e | None | None | None | 1490 |

### message.list after turn2 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184652 | assistant_message | None | None | None | None | 1137 |
| ui-msg-9184651 | tool_return_message | None | None | None | None |  |
| ui-msg-9184650:tool:call-3f184f84-a643-44ef-a6bb-02b4dded96d0-1:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184650 | assistant_message | None | None | None | None | 101 |
| ui-msg-9184649 | user_message | capture-turn2-ef789a06-10ff-4e35-b397-5edea9a99023 | None | None | None | 276 |
| ui-msg-9184648:assistant:1 | assistant_message | None | None | None | None | 1117 |
| ui-msg-9184648:reasoning:0 | reasoning_message | None | None | None | None | 91 |
| ui-msg-9184647 | tool_return_message | None | None | None | None |  |
| ui-msg-9184646:tool:call-affb8ba6-8372-499d-9f81-2823f83cc115-0:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184646 | assistant_message | None | None | None | None | 70 |
| ui-msg-9184645 | user_message | capture-turn1-dbedc2d8-1a17-40d7-9cce-594e607a4c2e | None | None | None | 1490 |
