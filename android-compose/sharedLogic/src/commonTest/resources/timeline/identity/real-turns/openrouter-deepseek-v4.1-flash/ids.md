## openrouter-deepseek-v4.1-flash


### Raw wire (stream_delta.delta + envelope), viewer connection

**turn1**: 145 frames, 134 text frames. Types: user_message=1, reasoning_message=11, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=123

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 69000016 | <n> |
| id | approval_classification_end | lifecycle-7dfe0622-0b00-4762-a327-53d99ab2b11c | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-3ab1060f-479b-48b3-b7c5-7d73edef2d17 | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-791 | local-run-<n> |
| event_seq | assistant_message | 123 | <n> |
| id | assistant_message | ui-msg-9184624 | ui-msg-<n> |
| idempotency_key | assistant_message | 123 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-792 | local-run-<n> |
| seq_id | assistant_message | 123 | <n> |
| event_seq | client_tool_end | 69000019 | <n> |
| idempotency_key | client_tool_end | iroh-delta-b921eb8e-9d3a-4589-928d-232444b83d2e | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-791 | local-run-<n> |
| tool_call_id | client_tool_end | call_00_haei2tulk522mdthg7n3bahd | call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| event_seq | client_tool_start | 69000017 | <n> |
| idempotency_key | client_tool_start | iroh-delta-1d427133-8f91-4793-8bf8-bafa09883406 | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-791 | local-run-<n> |
| tool_call_id | client_tool_start | call_00_haei2tulk522mdthg7n3bahd | call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| event_seq | reasoning_message | 11 | <n> |
| id | reasoning_message | ui-msg-9184622:reasoning:0 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | 11 | iroh-delta-<uuid> |
| run_id | reasoning_message | local-run-791 | local-run-<n> |
| seq_id | reasoning_message | 11 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 69000013 | <n> |
| idempotency_key | tool_call_message | iroh-delta-67337ccf-770c-44ba-86db-d090c2c9e5a2 | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-791 | local-run-<n> |
| tool_call_id | tool_call_message | call_00_haei2tulk522mdthg7n3bahd | call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-791 | local-run-<n> |
| tool_call_id | tool_return_message | call_00_haei2tulk522mdthg7n3bahd | call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 69000001 | <n> |
| id | user_message | cm-user-capture-turn1-f7a9a01a-6c03-4c36-8b8e-1852596bcef1 | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-f398e77e-c060-4a1e-9e9e-a3838958036b | iroh-delta-<uuid> |
| otid | user_message | capture-turn1-f7a9a01a-6c03-4c36-8b8e-1852596bcef1 | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184624`: 123 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 122}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184622:reasoning:0`: 11 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 10}

**turn2**: 131 frames, 120 text frames. Types: user_message=1, reasoning_message=3, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=117

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 69000153 | <n> |
| id | approval_classification_end | lifecycle-f9486691-1bbb-48b0-841a-64d0593662ab | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-87903dff-6400-4472-a257-f2c45b718564 | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-793 | local-run-<n> |
| event_seq | assistant_message | 117 | <n> |
| id | assistant_message | ui-msg-9184628 | ui-msg-<n> |
| idempotency_key | assistant_message | 117 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-794 | local-run-<n> |
| seq_id | assistant_message | 117 | <n> |
| event_seq | client_tool_end | 69000156 | <n> |
| idempotency_key | client_tool_end | iroh-delta-d9f6f9f9-829c-4297-afa1-4458d5a168c9 | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-793 | local-run-<n> |
| tool_call_id | client_tool_end | call_00_yu5eq86hht8utbsrd8csy24i | call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| event_seq | client_tool_start | 69000154 | <n> |
| idempotency_key | client_tool_start | iroh-delta-921160a6-8973-417e-a9c0-8f60e55df07d | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-793 | local-run-<n> |
| tool_call_id | client_tool_start | call_00_yu5eq86hht8utbsrd8csy24i | call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| event_seq | reasoning_message | 3 | <n> |
| id | reasoning_message | ui-msg-9184626:reasoning:0 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | 3 | iroh-delta-<uuid> |
| run_id | reasoning_message | local-run-793 | local-run-<n> |
| seq_id | reasoning_message | 3 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 69000150 | <n> |
| idempotency_key | tool_call_message | iroh-delta-5a7fa348-5266-46fc-b0bb-c02a7c85b063 | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-793 | local-run-<n> |
| tool_call_id | tool_call_message | call_00_yu5eq86hht8utbsrd8csy24i | call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-793 | local-run-<n> |
| tool_call_id | tool_return_message | call_00_yu5eq86hht8utbsrd8csy24i | call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 69000146 | <n> |
| id | user_message | cm-user-capture-turn2-ed5f4abb-490b-4ca1-bafb-46fb1c547f88 | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-620c213c-3b37-48a1-a49c-ae46f7763050 | iroh-delta-<uuid> |
| otid | user_message | capture-turn2-ed5f4abb-490b-4ca1-bafb-46fb1c547f88 | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184628`: 117 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 116}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184626:reasoning:0`: 3 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 2}


### Client ServerFrames on A-desktop (after IrohChannelTransport mapping)

**turn1**: 147 frames, 134 text frames. Types: turn_started=2, user_message=1, reasoning_message=11, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=123, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184624 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-792 | local-run-<n> |
| seq | assistant_message | 123 | <n> |
| seq_id | assistant_message | 123 | <n> |
| turn_id | assistant_message | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |
| id | reasoning_message | iroh-reasoning_message-local-run-791-iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | local-run-791 | local-run-<n> |
| seq | reasoning_message | 11 | <n> |
| seq_id | reasoning_message | 11 | <n> |
| turn_id | reasoning_message | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_00_haei2tulk522mdthg7n3bahd | toolcall-call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| run_id | tool_call_message | local-run-791 | local-run-<n> |
| seq | tool_call_message | 67000014 | <n> |
| tool_call_id | tool_call_message | call_00_haei2tulk522mdthg7n3bahd | call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| turn_id | tool_call_message | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd; toolreturn-call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| run_id | tool_return_message | local-run-791 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_00_haei2tulk522mdthg7n3bahd | call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| turn_id | tool_return_message | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |
| id | turn_done | turn_done-4a1b282e-8563-4f15-93e4-c977daf42ae0 | turn_done-<uuid> |
| run_id | turn_done | local-run-791 | local-run-<n> |
| turn_id | turn_done | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn1-f7a9a01a-6c03-4c36-8b8e-1852596bcef1 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-f7a9a01a-6c03-4c36-8b8e-1852596bcef1 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-5b1d9444-47a2-453c-86f6-660c76c12da8 | iroh-run-<uuid> |
| seq | user_message | 67000001 | <n> |
| seq_id | user_message | 67000001 | <n> |
| turn_id | user_message | iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6 | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184624', 'iroh-assistant-iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6')`: 123 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 122}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-791-iroh-turn-8d5046f3-3e23-4b42-b2a5-6a38419049a6', None)`: 11 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 10}

**turn2**: 133 frames, 120 text frames. Types: turn_started=2, user_message=1, reasoning_message=3, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=117, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184628 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-794 | local-run-<n> |
| seq | assistant_message | 117 | <n> |
| seq_id | assistant_message | 117 | <n> |
| turn_id | assistant_message | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |
| id | reasoning_message | iroh-reasoning_message-local-run-793-iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | local-run-793 | local-run-<n> |
| seq | reasoning_message | 3 | <n> |
| seq_id | reasoning_message | 3 | <n> |
| turn_id | reasoning_message | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_00_yu5eq86hht8utbsrd8csy24i | toolcall-call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| run_id | tool_call_message | local-run-793 | local-run-<n> |
| seq | tool_call_message | 67000155 | <n> |
| tool_call_id | tool_call_message | call_00_yu5eq86hht8utbsrd8csy24i | call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| turn_id | tool_call_message | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i; toolreturn-call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| run_id | tool_return_message | local-run-793 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_00_yu5eq86hht8utbsrd8csy24i | call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| turn_id | tool_return_message | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |
| id | turn_done | turn_done-2222dc48-daff-45fa-b3f1-6517b3d43fe1 | turn_done-<uuid> |
| run_id | turn_done | local-run-793 | local-run-<n> |
| turn_id | turn_done | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn2-ed5f4abb-490b-4ca1-bafb-46fb1c547f88 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-ed5f4abb-490b-4ca1-bafb-46fb1c547f88 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-1213db0e-5195-4126-be9c-e3d8003f61b8 | iroh-run-<uuid> |
| seq | user_message | 67000150 | <n> |
| seq_id | user_message | 67000150 | <n> |
| turn_id | user_message | iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77 | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184628', 'iroh-assistant-iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77')`: 117 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 116}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-793-iroh-turn-382be76a-c3ad-4f69-a721-fd567f0b4c77', None)`: 3 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 2}


### Client ServerFrames on B-phone (after IrohChannelTransport mapping)

**turn1**: 145 frames, 134 text frames. Types: user_message=1, reasoning_message=11, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=123, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184624 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-590 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-792 | local-run-<n> |
| seq | assistant_message | 123 | <n> |
| seq_id | assistant_message | 123 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | iroh-reasoning_message-local-run-791-iroh-observer-turn-local-conv-590 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | local-run-791 | local-run-<n> |
| seq | reasoning_message | 11 | <n> |
| seq_id | reasoning_message | 11 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_00_haei2tulk522mdthg7n3bahd | toolcall-call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| run_id | tool_call_message | local-run-791 | local-run-<n> |
| seq | tool_call_message | 68000013 | <n> |
| tool_call_id | tool_call_message | call_00_haei2tulk522mdthg7n3bahd | call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd; toolreturn-call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| run_id | tool_return_message | local-run-791 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_00_haei2tulk522mdthg7n3bahd | call_<n>_haei<n>tulk<n>mdthg<n>n<n>bahd |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-cb213784-9ccd-4460-b048-94b82e5e6923 | turn_done-<uuid> |
| run_id | turn_done | local-run-792 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn1-f7a9a01a-6c03-4c36-8b8e-1852596bcef1 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-f7a9a01a-6c03-4c36-8b8e-1852596bcef1 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-590 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 68000001 | <n> |
| seq_id | user_message | 68000001 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184624', 'iroh-assistant-iroh-observer-turn-local-conv-590')`: 123 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 122}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-791-iroh-observer-turn-local-conv-590', None)`: 11 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 10}

**turn2**: 131 frames, 120 text frames. Types: user_message=1, reasoning_message=3, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=117, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184628 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-590 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-794 | local-run-<n> |
| seq | assistant_message | 117 | <n> |
| seq_id | assistant_message | 117 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | iroh-reasoning_message-local-run-793-iroh-observer-turn-local-conv-590 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | local-run-793 | local-run-<n> |
| seq | reasoning_message | 3 | <n> |
| seq_id | reasoning_message | 3 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_00_yu5eq86hht8utbsrd8csy24i | toolcall-call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| run_id | tool_call_message | local-run-793 | local-run-<n> |
| seq | tool_call_message | 68000150 | <n> |
| tool_call_id | tool_call_message | call_00_yu5eq86hht8utbsrd8csy24i | call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i; toolreturn-call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| run_id | tool_return_message | local-run-793 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_00_yu5eq86hht8utbsrd8csy24i | call_<n>_yu<n>eq<n>hht<n>utbsrd<n>csy<n>i |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-f5ce64c1-ddb0-4130-a2d5-dbcc6a5485d8 | turn_done-<uuid> |
| run_id | turn_done | local-run-794 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn2-ed5f4abb-490b-4ca1-bafb-46fb1c547f88 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-ed5f4abb-490b-4ca1-bafb-46fb1c547f88 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-590 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 68000146 | <n> |
| seq_id | user_message | 68000146 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-590 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184628', 'iroh-assistant-iroh-observer-turn-local-conv-590')`: 117 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 116}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-793-iroh-observer-turn-local-conv-590', None)`: 3 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 2}


### message.list after turn1 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184624 | assistant_message | None | None | None | None | 1425 |
| ui-msg-9184623 | tool_return_message | None | None | None | None |  |
| ui-msg-9184622:tool:call_00_haei2tulk522mdthg7n3bahd:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184622:reasoning:0 | reasoning_message | None | None | None | None | 176 |
| ui-msg-9184621 | user_message | capture-turn1-f7a9a01a-6c03-4c36-8b8e-1852596bcef1 | None | None | None | 1490 |

### message.list after turn2 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184628 | assistant_message | None | None | None | None | 1533 |
| ui-msg-9184627 | tool_return_message | None | None | None | None |  |
| ui-msg-9184626:tool:call_00_yu5eq86hht8utbsrd8csy24i:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184626:reasoning:0 | reasoning_message | None | None | None | None | 18 |
| ui-msg-9184625 | user_message | capture-turn2-ed5f4abb-490b-4ca1-bafb-46fb1c547f88 | None | None | None | 276 |
| ui-msg-9184624 | assistant_message | None | None | None | None | 1425 |
| ui-msg-9184623 | tool_return_message | None | None | None | None |  |
| ui-msg-9184622:tool:call_00_haei2tulk522mdthg7n3bahd:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184622:reasoning:0 | reasoning_message | None | None | None | None | 176 |
| ui-msg-9184621 | user_message | capture-turn1-f7a9a01a-6c03-4c36-8b8e-1852596bcef1 | None | None | None | 1490 |
