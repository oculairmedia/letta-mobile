## openrouter-gemini-3.8-flash


### Raw wire (stream_delta.delta + envelope), viewer connection

**turn1**: 19 frames, 8 text frames. Types: user_message=1, reasoning_message=1, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=7

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 66000006 | <n> |
| id | approval_classification_end | lifecycle-fb3a325e-46dc-481c-9a9f-5fc8c10b8557 | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-2678ae6f-3396-41aa-86c1-f82f9086e6fb | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-787 | local-run-<n> |
| event_seq | assistant_message | 7 | <n> |
| id | assistant_message | ui-msg-9184616 | ui-msg-<n> |
| idempotency_key | assistant_message | 7 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-788 | local-run-<n> |
| seq_id | assistant_message | 7 | <n> |
| event_seq | client_tool_end | 66000009 | <n> |
| idempotency_key | client_tool_end | iroh-delta-f1f84074-e108-414b-8a82-103d8c686282 | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-787 | local-run-<n> |
| tool_call_id | client_tool_end | call_2926519 | call_<n> |
| event_seq | client_tool_start | 66000007 | <n> |
| idempotency_key | client_tool_start | iroh-delta-1380570b-0e46-41bc-9f13-e8f8a23fb2a5 | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-787 | local-run-<n> |
| tool_call_id | client_tool_start | call_2926519 | call_<n> |
| event_seq | reasoning_message | 66000002 | <n> |
| id | reasoning_message | ui-msg-9184614:reasoning:0 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | iroh-delta-3eba99bc-563b-45e6-a6af-7f7356f67b27 | iroh-delta-<uuid> |
| run_id | reasoning_message | local-run-787 | local-run-<n> |
| seq_id | reasoning_message | 1 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 66000003 | <n> |
| idempotency_key | tool_call_message | iroh-delta-a5216c47-a1bd-4a79-801b-384d967a1f8f | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-787 | local-run-<n> |
| tool_call_id | tool_call_message | call_2926519 | call_<n> |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n> |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-787 | local-run-<n> |
| tool_call_id | tool_return_message | call_2926519 | call_<n> |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 66000001 | <n> |
| id | user_message | cm-user-capture-turn1-23719a42-ac62-42ac-a739-b6d8fcf97ab5 | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-d635a8f5-239c-493a-a4df-b4b3d7e8ffc1 | iroh-delta-<uuid> |
| otid | user_message | capture-turn1-23719a42-ac62-42ac-a739-b6d8fcf97ab5 | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184616`: 7 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 6}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184614:reasoning:0`: 1 frames -> **pure-increment** {'first': 1}

**turn2**: 21 frames, 10 text frames. Types: user_message=1, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=10

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 66000024 | <n> |
| id | approval_classification_end | lifecycle-390bbcc1-787c-44c5-8aff-9d8cd5166292 | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-60e8530b-4b85-408d-81fd-050e935c9066 | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-789 | local-run-<n> |
| event_seq | assistant_message | 10 | <n> |
| id | assistant_message | ui-msg-9184620 | ui-msg-<n> |
| idempotency_key | assistant_message | 10 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-790 | local-run-<n> |
| seq_id | assistant_message | 10 | <n> |
| event_seq | client_tool_end | 66000027 | <n> |
| idempotency_key | client_tool_end | iroh-delta-6f50b72e-d881-4230-ad52-779df28f0162 | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-789 | local-run-<n> |
| tool_call_id | client_tool_end | call_2762453 | call_<n> |
| event_seq | client_tool_start | 66000025 | <n> |
| idempotency_key | client_tool_start | iroh-delta-5e262363-af56-46eb-8db7-3590c54234b6 | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-789 | local-run-<n> |
| tool_call_id | client_tool_start | call_2762453 | call_<n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 66000021 | <n> |
| idempotency_key | tool_call_message | iroh-delta-527d9d81-a292-432b-adcb-af129c2ee4a4 | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-789 | local-run-<n> |
| tool_call_id | tool_call_message | call_2762453 | call_<n> |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n> |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-789 | local-run-<n> |
| tool_call_id | tool_return_message | call_2762453 | call_<n> |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 66000020 | <n> |
| id | user_message | cm-user-capture-turn2-a78dcb91-899b-4580-a38a-4b5897be446e | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-01fb0971-9888-4643-9022-9edccccb0a43 | iroh-delta-<uuid> |
| otid | user_message | capture-turn2-a78dcb91-899b-4580-a38a-4b5897be446e | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184620`: 10 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 9}


### Client ServerFrames on A-desktop (after IrohChannelTransport mapping)

**turn1**: 21 frames, 8 text frames. Types: turn_started=2, user_message=1, reasoning_message=1, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=7, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184616 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-788 | local-run-<n> |
| seq | assistant_message | 7 | <n> |
| seq_id | assistant_message | 7 | <n> |
| turn_id | assistant_message | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |
| id | reasoning_message | iroh-reasoning_message-local-run-787-iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | local-run-787 | local-run-<n> |
| seq | reasoning_message | 64000003 | <n> |
| seq_id | reasoning_message | 64000003 | <n> |
| turn_id | reasoning_message | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_2926519 | toolcall-call_<n> |
| run_id | tool_call_message | local-run-787 | local-run-<n> |
| seq | tool_call_message | 64000004 | <n> |
| tool_call_id | tool_call_message | call_2926519 | call_<n> |
| turn_id | tool_call_message | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>; toolreturn-call_<n> |
| run_id | tool_return_message | local-run-787 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_2926519 | call_<n> |
| turn_id | tool_return_message | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |
| id | turn_done | turn_done-8b3955d8-e12e-4900-8a35-8fd2800570d1 | turn_done-<uuid> |
| run_id | turn_done | local-run-787 | local-run-<n> |
| turn_id | turn_done | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn1-23719a42-ac62-42ac-a739-b6d8fcf97ab5 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-23719a42-ac62-42ac-a739-b6d8fcf97ab5 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-29c08e47-7a54-4e54-96dc-ab89a1a44167 | iroh-run-<uuid> |
| seq | user_message | 64000001 | <n> |
| seq_id | user_message | 64000001 | <n> |
| turn_id | user_message | iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184616', 'iroh-assistant-iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d')`: 7 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 6}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-787-iroh-turn-96ed6664-412a-438d-9daf-8f05d0f5a24d', None)`: 1 frames -> **pure-increment** {'first': 1}

**turn2**: 23 frames, 10 text frames. Types: turn_started=2, user_message=1, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=10, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184620 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-790 | local-run-<n> |
| seq | assistant_message | 10 | <n> |
| seq_id | assistant_message | 10 | <n> |
| turn_id | assistant_message | iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_2762453 | toolcall-call_<n> |
| run_id | tool_call_message | local-run-789 | local-run-<n> |
| seq | tool_call_message | 64000026 | <n> |
| tool_call_id | tool_call_message | call_2762453 | call_<n> |
| turn_id | tool_call_message | iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>; toolreturn-call_<n> |
| run_id | tool_return_message | local-run-789 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_2762453 | call_<n> |
| turn_id | tool_return_message | iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-turn-<uuid> |
| id | turn_done | turn_done-466e0f5d-c3dc-4d40-9753-736cc45a9de9 | turn_done-<uuid> |
| run_id | turn_done | local-run-789 | local-run-<n> |
| turn_id | turn_done | iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn2-a78dcb91-899b-4580-a38a-4b5897be446e | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-a78dcb91-899b-4580-a38a-4b5897be446e | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-ca8b37a0-1e71-4867-bfc3-a112c05a3a2c | iroh-run-<uuid> |
| seq | user_message | 64000024 | <n> |
| seq_id | user_message | 64000024 | <n> |
| turn_id | user_message | iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184620', 'iroh-assistant-iroh-turn-7e127cc7-4391-4be4-8aec-e1a55a3073fd')`: 10 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 9}


### Client ServerFrames on B-phone (after IrohChannelTransport mapping)

**turn1**: 19 frames, 8 text frames. Types: user_message=1, reasoning_message=1, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=7, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184616 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-589 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-788 | local-run-<n> |
| seq | assistant_message | 7 | <n> |
| seq_id | assistant_message | 7 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | iroh-reasoning_message-local-run-787-iroh-observer-turn-local-conv-589 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | local-run-787 | local-run-<n> |
| seq | reasoning_message | 65000002 | <n> |
| seq_id | reasoning_message | 65000002 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_2926519 | toolcall-call_<n> |
| run_id | tool_call_message | local-run-787 | local-run-<n> |
| seq | tool_call_message | 65000003 | <n> |
| tool_call_id | tool_call_message | call_2926519 | call_<n> |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>; toolreturn-call_<n> |
| run_id | tool_return_message | local-run-787 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_2926519 | call_<n> |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-c2e9091a-e633-4856-bde3-18f5a5a32db6 | turn_done-<uuid> |
| run_id | turn_done | local-run-788 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn1-23719a42-ac62-42ac-a739-b6d8fcf97ab5 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-23719a42-ac62-42ac-a739-b6d8fcf97ab5 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-589 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 65000001 | <n> |
| seq_id | user_message | 65000001 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184616', 'iroh-assistant-iroh-observer-turn-local-conv-589')`: 7 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 6}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-787-iroh-observer-turn-local-conv-589', None)`: 1 frames -> **pure-increment** {'first': 1}

**turn2**: 21 frames, 10 text frames. Types: user_message=1, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=10, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184620 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-589 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-790 | local-run-<n> |
| seq | assistant_message | 10 | <n> |
| seq_id | assistant_message | 10 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_2762453 | toolcall-call_<n> |
| run_id | tool_call_message | local-run-789 | local-run-<n> |
| seq | tool_call_message | 65000021 | <n> |
| tool_call_id | tool_call_message | call_2762453 | call_<n> |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>; toolreturn-call_<n> |
| run_id | tool_return_message | local-run-789 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_2762453 | call_<n> |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-161cf317-2558-40a2-b9d4-85a84c90eef2 | turn_done-<uuid> |
| run_id | turn_done | local-run-790 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn2-a78dcb91-899b-4580-a38a-4b5897be446e | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-a78dcb91-899b-4580-a38a-4b5897be446e | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-589 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 65000020 | <n> |
| seq_id | user_message | 65000020 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-589 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184620', 'iroh-assistant-iroh-observer-turn-local-conv-589')`: 10 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 9}


### message.list after turn1 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184616 | assistant_message | None | None | None | None | 858 |
| ui-msg-9184615 | tool_return_message | None | None | None | None |  |
| ui-msg-9184614:tool:call_2926519:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184614:reasoning:0 | reasoning_message | None | None | None | None | 392 |
| ui-msg-9184613 | user_message | capture-turn1-23719a42-ac62-42ac-a739-b6d8fcf97ab5 | None | None | None | 1490 |

### message.list after turn2 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184620 | assistant_message | None | None | None | None | 1035 |
| ui-msg-9184619 | tool_return_message | None | None | None | None |  |
| ui-msg-9184618:tool:call_2762453:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184617 | user_message | capture-turn2-a78dcb91-899b-4580-a38a-4b5897be446e | None | None | None | 276 |
| ui-msg-9184616 | assistant_message | None | None | None | None | 858 |
| ui-msg-9184615 | tool_return_message | None | None | None | None |  |
| ui-msg-9184614:tool:call_2926519:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184614:reasoning:0 | reasoning_message | None | None | None | None | 392 |
| ui-msg-9184613 | user_message | capture-turn1-23719a42-ac62-42ac-a739-b6d8fcf97ab5 | None | None | None | 1490 |
