## openrouter-qwen3.8-flash


### Raw wire (stream_delta.delta + envelope), viewer connection

**turn1**: 142 frames, 131 text frames. Types: user_message=1, reasoning_message=30, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=101

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 72000014 | <n> |
| id | approval_classification_end | lifecycle-a34f4e4e-9c4d-49c6-986e-2250364055a5 | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-8282cf67-a886-43d3-b8a0-bb131166d4b1 | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-795 | local-run-<n> |
| event_seq | assistant_message | 101 | <n> |
| id | assistant_message | ui-msg-9184632:assistant:1 | ui-msg-<n>:assistant:<n> |
| idempotency_key | assistant_message | 101 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-796 | local-run-<n> |
| seq_id | assistant_message | 101 | <n> |
| event_seq | client_tool_end | 72000017 | <n> |
| idempotency_key | client_tool_end | iroh-delta-116d1cf7-c9bb-4ebe-a14b-6878c2dfe9ed | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-795 | local-run-<n> |
| tool_call_id | client_tool_end | call_d8c82b623a014a3bb002c8e6 | call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| event_seq | client_tool_start | 72000015 | <n> |
| idempotency_key | client_tool_start | iroh-delta-f400d1a7-2c52-4416-93ff-a46f4c998c01 | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-795 | local-run-<n> |
| tool_call_id | client_tool_start | call_d8c82b623a014a3bb002c8e6 | call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| event_seq | reasoning_message | 30 | <n> |
| id | reasoning_message | 2 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | 30 | iroh-delta-<uuid> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq_id | reasoning_message | 21 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 72000011 | <n> |
| idempotency_key | tool_call_message | iroh-delta-a912af3a-3a61-4c0c-851e-9b5af016a7d1 | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-795 | local-run-<n> |
| tool_call_id | tool_call_message | call_d8c82b623a014a3bb002c8e6 | call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-795 | local-run-<n> |
| tool_call_id | tool_return_message | call_d8c82b623a014a3bb002c8e6 | call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 72000001 | <n> |
| id | user_message | cm-user-capture-turn1-66e18903-585b-4875-bd0b-1bc3eaf3d15c | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-fcd96d09-8d8c-43a7-a57a-b2caf081d05f | iroh-delta-<uuid> |
| otid | user_message | capture-turn1-66e18903-585b-4875-bd0b-1bc3eaf3d15c | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184632:assistant:1`: 101 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 100}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184630:reasoning:0`: 9 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 8}
- key `ui-msg-9184632:reasoning:0`: 21 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 20}

**turn2**: 158 frames, 147 text frames. Types: user_message=1, reasoning_message=34, tool_call_message=1, usage_statistics=2, stop_reason=2, approval_classification_end=1, client_tool_start=1, tool_return_message=2, client_tool_end=1, assistant_message=113

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| event_seq | approval_classification_end | 72000156 | <n> |
| id | approval_classification_end | lifecycle-20042066-aef9-40fd-b807-7c04cf4b1efd | lifecycle-<uuid> |
| idempotency_key | approval_classification_end | iroh-delta-801633fe-e35e-46c1-8f04-600ffbeb07a1 | iroh-delta-<uuid> |
| run_id | approval_classification_end | local-run-797 | local-run-<n> |
| event_seq | assistant_message | 113 | <n> |
| id | assistant_message | ui-msg-9184636:assistant:1 | ui-msg-<n>:assistant:<n> |
| idempotency_key | assistant_message | 113 | iroh-delta-<uuid> |
| run_id | assistant_message | local-run-798 | local-run-<n> |
| seq_id | assistant_message | 113 | <n> |
| event_seq | client_tool_end | 72000159 | <n> |
| idempotency_key | client_tool_end | iroh-delta-44ca656e-1eb0-4b0e-a236-635cee7da0fb | iroh-delta-<uuid> |
| run_id | client_tool_end | local-run-797 | local-run-<n> |
| tool_call_id | client_tool_end | call_22db3562249945838bd85431 | call_<n>db<n>bd<n> |
| event_seq | client_tool_start | 72000157 | <n> |
| idempotency_key | client_tool_start | iroh-delta-b3cd6129-b40f-4c6d-a48e-a60694170794 | iroh-delta-<uuid> |
| run_id | client_tool_start | local-run-797 | local-run-<n> |
| tool_call_id | client_tool_start | call_22db3562249945838bd85431 | call_<n>db<n>bd<n> |
| event_seq | reasoning_message | 34 | <n> |
| id | reasoning_message | 2 | ui-msg-<n>:reasoning:<n> |
| idempotency_key | reasoning_message | 34 | iroh-delta-<uuid> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq_id | reasoning_message | 25 | <n> |
| event_seq | stop_reason | 2 | <n> |
| idempotency_key | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq_id | stop_reason | 2 | <n> |
| event_seq | tool_call_message | 72000153 | <n> |
| idempotency_key | tool_call_message | iroh-delta-8a46e0db-4e95-4590-8836-1e8bdd6bd441 | iroh-delta-<uuid> |
| run_id | tool_call_message | local-run-797 | local-run-<n> |
| tool_call_id | tool_call_message | call_22db3562249945838bd85431 | call_<n>db<n>bd<n> |
| event_seq | tool_return_message | 2 | <n> |
| id | tool_return_message | 2 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>db<n>bd<n> |
| idempotency_key | tool_return_message | 2 | iroh-delta-<uuid> |
| run_id | tool_return_message | local-run-797 | local-run-<n> |
| tool_call_id | tool_return_message | call_22db3562249945838bd85431 | call_<n>db<n>bd<n> |
| event_seq | usage_statistics | 2 | <n> |
| id | usage_statistics | 2 | letta-msg-<n> |
| idempotency_key | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq_id | usage_statistics | 2 | <n> |
| event_seq | user_message | 72000143 | <n> |
| id | user_message | cm-user-capture-turn2-216fd2d8-5c5f-4e51-8c65-52441e92e987 | cm-user-capture-turn<n>-<uuid> |
| idempotency_key | user_message | iroh-delta-a2942737-a119-48eb-a16f-c7f555d5146d | iroh-delta-<uuid> |
| otid | user_message | capture-turn2-216fd2d8-5c5f-4e51-8c65-52441e92e987 | capture-turn<n>-<uuid> |
| seq_id | user_message | 0 | <n> |

Text classification for `assistant_message` keyed by delta.id:
- key `ui-msg-9184636:assistant:1`: 113 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 112}

Text classification for `reasoning_message` keyed by delta.id:
- key `ui-msg-9184634:reasoning:0`: 9 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 8}
- key `ui-msg-9184636:reasoning:0`: 25 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 24}


### Client ServerFrames on A-desktop (after IrohChannelTransport mapping)

**turn1**: 144 frames, 131 text frames. Types: turn_started=2, user_message=1, reasoning_message=30, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=101, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184632:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-796 | local-run-<n> |
| seq | assistant_message | 101 | <n> |
| seq_id | assistant_message | 101 | <n> |
| turn_id | assistant_message | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |
| id | reasoning_message | 2 | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq | reasoning_message | 30 | <n> |
| seq_id | reasoning_message | 30 | <n> |
| turn_id | reasoning_message | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_d8c82b623a014a3bb002c8e6 | toolcall-call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| run_id | tool_call_message | local-run-795 | local-run-<n> |
| seq | tool_call_message | 70000012 | <n> |
| tool_call_id | tool_call_message | call_d8c82b623a014a3bb002c8e6 | call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| turn_id | tool_call_message | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n>; toolreturn-call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| run_id | tool_return_message | local-run-795 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_d8c82b623a014a3bb002c8e6 | call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| turn_id | tool_return_message | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |
| id | turn_done | turn_done-8d41d10b-c88b-4f70-b531-aca690558eb0 | turn_done-<uuid> |
| run_id | turn_done | local-run-795 | local-run-<n> |
| turn_id | turn_done | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn1-66e18903-585b-4875-bd0b-1bc3eaf3d15c | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-66e18903-585b-4875-bd0b-1bc3eaf3d15c | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-9f171c11-c206-4499-94d9-0595b9ab9e54 | iroh-run-<uuid> |
| seq | user_message | 70000001 | <n> |
| seq_id | user_message | 70000001 | <n> |
| turn_id | user_message | iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35 | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184632:assistant:1', 'iroh-assistant-iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35')`: 101 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 100}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-795-iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35', None)`: 9 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 8}
- key `('iroh-reasoning_message-local-run-796-iroh-turn-dcd429ff-0b7e-4313-8eb2-74f611df7a35', None)`: 21 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 20}

**turn2**: 160 frames, 147 text frames. Types: turn_started=2, user_message=1, reasoning_message=34, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=113, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184636:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-assistant-iroh-turn-<uuid> |
| run_id | assistant_message | local-run-798 | local-run-<n> |
| seq | assistant_message | 113 | <n> |
| seq_id | assistant_message | 113 | <n> |
| turn_id | assistant_message | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |
| id | reasoning_message | 2 | iroh-reasoning_message-local-run-<n>-iroh-turn-<uuid> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq | reasoning_message | 34 | <n> |
| seq_id | reasoning_message | 34 | <n> |
| turn_id | reasoning_message | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |
| id | tool_call_message | toolcall-call_22db3562249945838bd85431 | toolcall-call_<n>db<n>bd<n> |
| run_id | tool_call_message | local-run-797 | local-run-<n> |
| seq | tool_call_message | 70000158 | <n> |
| tool_call_id | tool_call_message | call_22db3562249945838bd85431 | call_<n>db<n>bd<n> |
| turn_id | tool_call_message | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>db<n>bd<n>; toolreturn-call_<n>db<n>bd<n> |
| run_id | tool_return_message | local-run-797 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_22db3562249945838bd85431 | call_<n>db<n>bd<n> |
| turn_id | tool_return_message | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |
| id | turn_done | turn_done-60dc7c9e-5dba-4e1b-b342-71af9b978dc4 | turn_done-<uuid> |
| run_id | turn_done | local-run-797 | local-run-<n> |
| turn_id | turn_done | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |
| id | turn_started | 2 | turn_started-<uuid> |
| run_id | turn_started | 2 | iroh-run-<uuid>; local-run-<n> |
| turn_id | turn_started | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |
| id | user_message | cm-user-capture-turn2-216fd2d8-5c5f-4e51-8c65-52441e92e987 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-216fd2d8-5c5f-4e51-8c65-52441e92e987 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-run-5dd2ecc8-c12c-4765-9ed8-6c41e7bb3910 | iroh-run-<uuid> |
| seq | user_message | 70000147 | <n> |
| seq_id | user_message | 70000147 | <n> |
| turn_id | user_message | iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4 | iroh-turn-<uuid> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184636:assistant:1', 'iroh-assistant-iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4')`: 113 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 112}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-797-iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4', None)`: 9 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 8}
- key `('iroh-reasoning_message-local-run-798-iroh-turn-d4076799-3bdf-484b-b8ec-70de36b1a3b4', None)`: 25 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 24}


### Client ServerFrames on B-phone (after IrohChannelTransport mapping)

**turn1**: 142 frames, 131 text frames. Types: user_message=1, reasoning_message=30, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=101, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184632:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-591 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-796 | local-run-<n> |
| seq | assistant_message | 101 | <n> |
| seq_id | assistant_message | 101 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | 2 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq | reasoning_message | 30 | <n> |
| seq_id | reasoning_message | 30 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_d8c82b623a014a3bb002c8e6 | toolcall-call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| run_id | tool_call_message | local-run-795 | local-run-<n> |
| seq | tool_call_message | 71000011 | <n> |
| tool_call_id | tool_call_message | call_d8c82b623a014a3bb002c8e6 | call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n>; toolreturn-call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| run_id | tool_return_message | local-run-795 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_d8c82b623a014a3bb002c8e6 | call_d<n>c<n>b<n>a<n>a<n>bb<n>c<n>e<n> |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-c65e30e8-756b-43ce-8d9e-e9f107b00b9c | turn_done-<uuid> |
| run_id | turn_done | local-run-796 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn1-66e18903-585b-4875-bd0b-1bc3eaf3d15c | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn1-66e18903-585b-4875-bd0b-1bc3eaf3d15c | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-591 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 71000001 | <n> |
| seq_id | user_message | 71000001 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184632:assistant:1', 'iroh-assistant-iroh-observer-turn-local-conv-591')`: 101 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 100}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-795-iroh-observer-turn-local-conv-591', None)`: 9 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 8}
- key `('iroh-reasoning_message-local-run-796-iroh-observer-turn-local-conv-591', None)`: 21 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 20}

**turn2**: 158 frames, 147 text frames. Types: user_message=1, reasoning_message=34, tool_call_message=2, usage_statistics=2, stop_reason=2, tool_return_message=3, assistant_message=113, turn_done=1

| field | message_type | distinct values | shape(s) |
|---|---|---|---|
| id | assistant_message | ui-msg-9184636:assistant:1 | ui-msg-<n>:assistant:<n> |
| otid | assistant_message | iroh-assistant-iroh-observer-turn-local-conv-591 | iroh-assistant-iroh-observer-turn-local-conv-<n> |
| run_id | assistant_message | local-run-798 | local-run-<n> |
| seq | assistant_message | 113 | <n> |
| seq_id | assistant_message | 113 | <n> |
| turn_id | assistant_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | reasoning_message | 2 | iroh-reasoning_message-local-run-<n>-iroh-observer-turn-local-conv-<n> |
| run_id | reasoning_message | 2 | local-run-<n> |
| seq | reasoning_message | 34 | <n> |
| seq_id | reasoning_message | 34 | <n> |
| turn_id | reasoning_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | stop_reason | 2 | iroh-delta-<uuid> |
| run_id | stop_reason | 2 | local-run-<n> |
| seq | stop_reason | 2 | <n> |
| turn_id | stop_reason | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | tool_call_message | toolcall-call_22db3562249945838bd85431 | toolcall-call_<n>db<n>bd<n> |
| run_id | tool_call_message | local-run-797 | local-run-<n> |
| seq | tool_call_message | 71000153 | <n> |
| tool_call_id | tool_call_message | call_22db3562249945838bd85431 | call_<n>db<n>bd<n> |
| turn_id | tool_call_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | tool_return_message | 3 | synthetic-tool-return-<uuid>; synthetic-tool-return-stream-call_<n>db<n>bd<n>; toolreturn-call_<n>db<n>bd<n> |
| run_id | tool_return_message | local-run-797 | local-run-<n> |
| seq | tool_return_message | 2 | <n> |
| tool_call_id | tool_return_message | call_22db3562249945838bd85431 | call_<n>db<n>bd<n> |
| turn_id | tool_return_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | turn_done | turn_done-00b2bf11-f794-4fb0-b794-dae70ea5db40 | turn_done-<uuid> |
| run_id | turn_done | local-run-798 | local-run-<n> |
| turn_id | turn_done | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | usage_statistics | 2 | iroh-delta-<uuid> |
| run_id | usage_statistics | 2 | local-run-<n> |
| seq | usage_statistics | 2 | <n> |
| turn_id | usage_statistics | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |
| id | user_message | cm-user-capture-turn2-216fd2d8-5c5f-4e51-8c65-52441e92e987 | cm-user-capture-turn<n>-<uuid> |
| otid | user_message | capture-turn2-216fd2d8-5c5f-4e51-8c65-52441e92e987 | capture-turn<n>-<uuid> |
| run_id | user_message | iroh-observer-run-local-conv-591 | iroh-observer-run-local-conv-<n> |
| seq | user_message | 71000143 | <n> |
| seq_id | user_message | 71000143 | <n> |
| turn_id | user_message | iroh-observer-turn-local-conv-591 | iroh-observer-turn-local-conv-<n> |

Text classification for `assistant_message` keyed by (id, otid):
- key `('ui-msg-9184636:assistant:1', 'iroh-assistant-iroh-observer-turn-local-conv-591')`: 113 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 112}

Text classification for `reasoning_message` keyed by (id, otid):
- key `('iroh-reasoning_message-local-run-797-iroh-observer-turn-local-conv-591', None)`: 9 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 8}
- key `('iroh-reasoning_message-local-run-798-iroh-observer-turn-local-conv-591', None)`: 25 frames -> **cumulative-snapshot** {'first': 1, 'cumulative': 24}


### message.list after turn1 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184632:assistant:1 | assistant_message | None | None | None | None | 1488 |
| ui-msg-9184632:reasoning:0 | reasoning_message | None | None | None | None | 265 |
| ui-msg-9184631 | tool_return_message | None | None | None | None |  |
| ui-msg-9184630:tool:call_d8c82b623a014a3bb002c8e6:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184630:reasoning:0 | reasoning_message | None | None | None | None | 123 |
| ui-msg-9184629 | user_message | capture-turn1-66e18903-585b-4875-bd0b-1bc3eaf3d15c | None | None | None | 1490 |

### message.list after turn2 (newest first)

| id | message_type | otid | run_id | step_id | seq_id | text len |
|---|---|---|---|---|---|---|
| ui-msg-9184636:assistant:1 | assistant_message | None | None | None | None | 1731 |
| ui-msg-9184636:reasoning:0 | reasoning_message | None | None | None | None | 305 |
| ui-msg-9184635 | tool_return_message | None | None | None | None |  |
| ui-msg-9184634:tool:call_22db3562249945838bd85431:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184634:reasoning:0 | reasoning_message | None | None | None | None | 138 |
| ui-msg-9184633 | user_message | capture-turn2-216fd2d8-5c5f-4e51-8c65-52441e92e987 | None | None | None | 276 |
| ui-msg-9184632:assistant:1 | assistant_message | None | None | None | None | 1488 |
| ui-msg-9184632:reasoning:0 | reasoning_message | None | None | None | None | 265 |
| ui-msg-9184631 | tool_return_message | None | None | None | None |  |
| ui-msg-9184630:tool:call_d8c82b623a014a3bb002c8e6:request | approval_request_message | None | None | None | None |  |
| ui-msg-9184630:reasoning:0 | reasoning_message | None | None | None | None | 123 |
| ui-msg-9184629 | user_message | capture-turn1-66e18903-585b-4875-bd0b-1bc3eaf3d15c | None | None | None | 1490 |
