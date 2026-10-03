## openrouter-gpt-6.1-sol


### Raw wire (stream_delta.delta + envelope), viewer connection

**turn1**: 217 frames, 206 text frames. Types: user_message=1, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, reasoning_message=98, assistant_message=108

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 63000005 | <n> |
| id | approval_classification_end | lifecycle-a33ef624-b206-4300-ac77-eb3307464511 | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-0aeb7e65-3be8-4060-b82e-805791f8d932 | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-783 | local-run-<n> |
| event_seq | assistant_message | 108 | <n> |
| id | assistant_message | ui-msg-9184608:assistant:1 | ui-msg-<n>:assistant:<n> |
| idempotency_key | assistant_message | 108 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-784 | local-run-<n> |
| seq_id | assistant_message | 108 | <n> |
| event_seq | client_tool_end | 63000008 | <n> |
| idempotency_key | client_tool_end | iroh-delta-9667973f-c47e-4c47-b8bd-77577df43603 | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-783 | local-run-<n> |
| tool_call_id | client_tool_end | call_vjac5mkk2iZZ7szYnWLrF5yA | call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| event_seq | client_tool_start | 63000006 | <n> |
| idempotency_key | client_tool_start | iroh-delta-5eec5312-5125-434c-a4da-b9c43eb80f75 | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-783 | local-run-<n> |
| tool_call_id | client_tool_start | call_vjac5mkk2iZZ7szYnWLrF5yA | call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| event_seq | reasoning_message | 98 | <n> |
| id | reasoning_message | ui-msg-9184608:reasoning:0 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | 98 | iroh-delta-<uuid> |
| run_id | reasoning_message | local-run-784 | local-run-<n> |
| seq_id | reasoning_message | 98 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 63000002 | <n> |
| idempotency_key | tool_call_message | iroh-delta-c14dd010-c1af-46b7-81e7-e4d18ac97baf | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-783 | local-run-<n> |
| tool_call_id | tool_call_message | call_vjac5mkk2iZZ7szYnWLrF5yA | call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-783 | local-run-<n> |
| tool_call_id | tool_return_message | call_vjac5mkk2iZZ7szYnWLrF5yA | call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 63000001 | <n> |
| id | user_message | cm-user-capture-turn1-d0991c18-72da-43b6-8095-89ebed1236e5 | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-7cd7d857-93c9-4baa-8dc7-6b12da112339 | iroh-delta-<uuid> |
| otid | user_message | capture-turn1-d0991c18-72da-43b6-8095-89ebed1236e5 | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184608:assistant:1`: 108 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 107}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184608:reasoning:0`: 98 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 97}

**turn2**: 140 frames, 129 text frames. Types: user_message=1, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=129

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 63000222 | <n> |
| id | approval_classification_end | lifecycle-41b0033a-81fc-445f-b5a7-ee63a2c687f3 | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-86570888-e6f1-4022-bcc3-64c97f89dedf | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-785 | local-run-<n> |
| event_seq | assistant_message | 129 | <n> |
| id | assistant_message | ui-msg-9184612 | ui-msg-<n> |
| idempotency_key | assistant_message | 129 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-786 | local-run-<n> |
| seq_id | assistant_message | 129 | <n> |
| event_seq | client_tool_end | 63000225 | <n> |
| idempotency_key | client_tool_end | iroh-delta-370311fb-dd4f-497e-ad5a-db347eaf1c96 | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-785 | local-run-<n> |
| tool_call_id | client_tool_end | call_YbcrrnSKcOxh9IWMR1nzvJfu | call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| event_seq | client_tool_start | 63000223 | <n> |
| idempotency_key | client_tool_start | iroh-delta-399aeed3-3ef3-47c9-a686-594f090af9fc | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-785 | local-run-<n> |
| tool_call_id | client_tool_start | call_YbcrrnSKcOxh9IWMR1nzvJfu | call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 63000219 | <n> |
| idempotency_key | tool_call_message | iroh-delta-7082025b-7aba-479d-8843-908692f5e44d | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-785 | local-run-<n> |
| tool_call_id | tool_call_message | call_YbcrrnSKcOxh9IWMR1nzvJfu | call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-785 | local-run-<n> |
| tool_call_id | tool_return_message | call_YbcrrnSKcOxh9IWMR1nzvJfu | call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 63000218 | <n> |
| id | user_message | cm-user-capture-turn2-682caec8-2e18-49c9-b57e-5f44f91c2edc | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-25ea4a35-5abc-4144-8ffe-327515f0f3d2 | iroh-delta-<uuid> |
| otid | user_message | capture-turn2-682caec8-2e18-49c9-b57e-5f44f91c2edc | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184612`: 129 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 128}


### Client ServerFrames on A-desktop (after IrohChannelTransport mapping)

**turn1**: 219 frames, 206 text frames. Types: turn_started=2, user_message=1, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, reasoning_message=98, assistant_message=108, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184608:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-784 | local-run-<n> |
| seq | assistant_message | 108 | <n> |
| seq_id | assistant_message | 108 | <n> |
| turn_id | assistant_message | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |
| id | reasoning_message | iroh-reasoning_message-local-run-784-iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | local-run-784 | local-run-<n> |
| seq | reasoning_message | 98 | <n> |
| seq_id | reasoning_message | 98 | <n> |
| turn_id | reasoning_message | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_vjac5mkk2iZZ7szYnWLrF5yA | toolcall-call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| run_id | tool_call_message | local-run-783 | local-run-<n> |
| seq | tool_call_message | 61000003 | <n> |
| tool_call_id | tool_call_message | call_vjac5mkk2iZZ7szYnWLrF5yA | call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| turn_id | tool_call_message | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA; toolreturn-call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| run_id | tool_return_message | local-run-783 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_vjac5mkk2iZZ7szYnWLrF5yA | call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| turn_id | tool_return_message | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |
| id | turn_done | turn_done-2086c9c7-9446-4c70-84d6-4e2e6befa871 | turn_done-<uuid> |
| run_id | turn_done | local-run-783 | local-run-<n> |
| turn_id | turn_done | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn1-d0991c18-72da-43b6-8095-89ebed1236e5 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-d0991c18-72da-43b6-8095-89ebed1236e5 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-9cd0d197-55cd-4597-9707-f13e122ee49f | iroh-run-<uuid> |
| seq | user_message | 61000001 | <n> |
| seq_id | user_message | 61000001 | <n> |
| turn_id | user_message | iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898 | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184608:assistant:1', 'iroh-assistant-iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898')`: 108 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 107}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-784-iroh-turn-0d6caba3-a1ec-4987-9cf7-4f5adea04898', None)`: 98 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 97}

**turn2**: 142 frames, 129 text frames. Types: turn_started=2, user_message=1, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=129, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184612 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-786 | local-run-<n> |
| seq | assistant_message | 129 | <n> |
| seq_id | assistant_message | 129 | <n> |
| turn_id | assistant_message | iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_YbcrrnSKcOxh9IWMR1nzvJfu | toolcall-call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| run_id | tool_call_message | local-run-785 | local-run-<n> |
| seq | tool_call_message | 61000224 | <n> |
| tool_call_id | tool_call_message | call_YbcrrnSKcOxh9IWMR1nzvJfu | call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| turn_id | tool_call_message | iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu; toolreturn-call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| run_id | tool_return_message | local-run-785 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_YbcrrnSKcOxh9IWMR1nzvJfu | call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| turn_id | tool_return_message | iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-turn-<uuid> |
| id | turn_done | turn_done-0bb1193c-9628-43e0-8edb-8a41e4ab0763 | turn_done-<uuid> |
| run_id | turn_done | local-run-785 | local-run-<n> |
| turn_id | turn_done | iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn2-682caec8-2e18-49c9-b57e-5f44f91c2edc | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-682caec8-2e18-49c9-b57e-5f44f91c2edc | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-20129201-502c-482b-bf55-e427b97835b8 | iroh-run-<uuid> |
| seq | user_message | 61000222 | <n> |
| seq_id | user_message | 61000222 | <n> |
| turn_id | user_message | iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184612', 'iroh-assistant-iroh-turn-341f0b9b-7d63-49db-a752-f0a9ff1dbd8e')`: 129 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 128}


### Client ServerFrames on B-phone (after IrohChannelTransport mapping)

**turn1**: 217 frames, 206 text frames. Types: user_message=1, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, reasoning_message=98, assistant_message=108, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184608:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-588 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-784 | local-run-<n> |
| seq | assistant_message | 108 | <n> |
| seq_id | assistant_message | 108 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | iroh-reasoning_message-local-run-784-iroh-observer-turn-local-conv-588 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | local-run-784 | local-run-<n> |
| seq | reasoning_message | 98 | <n> |
| seq_id | reasoning_message | 98 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_vjac5mkk2iZZ7szYnWLrF5yA | toolcall-call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| run_id | tool_call_message | local-run-783 | local-run-<n> |
| seq | tool_call_message | 62000002 | <n> |
| tool_call_id | tool_call_message | call_vjac5mkk2iZZ7szYnWLrF5yA | call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA; toolreturn-call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| run_id | tool_return_message | local-run-783 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_vjac5mkk2iZZ7szYnWLrF5yA | call_vjac<n>mkk<n>iZZ<n>szYnWLrF<n>yA |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-23b20424-5e02-4aa1-84bd-7faea782bc30 | turn_done-<uuid> |
| run_id | turn_done | local-run-784 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn1-d0991c18-72da-43b6-8095-89ebed1236e5 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-d0991c18-72da-43b6-8095-89ebed1236e5 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-588 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 62000001 | <n> |
| seq_id | user_message | 62000001 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184608:assistant:1', 'iroh-assistant-iroh-observer-turn-local-conv-588')`: 108 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 107}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-784-iroh-observer-turn-local-conv-588', None)`: 98 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 97}

**turn2**: 140 frames, 129 text frames. Types: user_message=1, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=129, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184612 | ui-msg-<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-588 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-786 | local-run-<n> |
| seq | assistant_message | 129 | <n> |
| seq_id | assistant_message | 129 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_YbcrrnSKcOxh9IWMR1nzvJfu | toolcall-call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| run_id | tool_call_message | local-run-785 | local-run-<n> |
| seq | tool_call_message | 62000219 | <n> |
| tool_call_id | tool_call_message | call_YbcrrnSKcOxh9IWMR1nzvJfu | call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu; toolreturn-call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| run_id | tool_return_message | local-run-785 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_YbcrrnSKcOxh9IWMR1nzvJfu | call_YbcrrnSKcOxh<n>IWMR<n>nzvJfu |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-891f953d-f4e5-413b-a8c3-8095b1b84ee4 | turn_done-<uuid> |
| run_id | turn_done | local-run-786 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn2-682caec8-2e18-49c9-b57e-5f44f91c2edc | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-682caec8-2e18-49c9-b57e-5f44f91c2edc | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-588 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 62000218 | <n> |
| seq_id | user_message | 62000218 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-588 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184612', 'iroh-assistant-iroh-observer-turn-local-conv-588')`: 129 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 128}


### message.list after turn1 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184608:assistant:1 | assistant_message | None | None | None | None | 503 |
| ui-msg-9184608:reasoning:0 | reasoning_message | None | None | None | None | 659 |
| ui-msg-9184607 | tool_return_message | None | None | None | None |  |
| ui-msg-9184606:tool:call_vjac5mkk2iZZ7szYnWLrF5yA:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184605 | user_message | capture-turn1-d0991c18-72da-43b6-8095-89ebed1236e5 | None | None | None | 1490 |

### message.list after turn2 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184612 | assistant_message | None | None | None | None | 620 |
| ui-msg-9184611 | tool_return_message | None | None | None | None |  |
| ui-msg-9184610:tool:call_YbcrrnSKcOxh9IWMR1nzvJfu:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184609 | user_message | capture-turn2-682caec8-2e18-49c9-b57e-5f44f91c2edc | None | None | None | 276 |
| ui-msg-9184608:assistant:1 | assistant_message | None | None | None | None | 503 |
| ui-msg-9184608:reasoning:0 | reasoning_message | None | None | None | None | 659 |
| ui-msg-9184607 | tool_return_message | None | None | None | None |  |
| ui-msg-9184606:tool:call_vjac5mkk2iZZ7szYnWLrF5yA:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184605 | user_message | capture-turn1-d0991c18-72da-43b6-8095-89ebed1236e5 | None | None | None | 1490 |
