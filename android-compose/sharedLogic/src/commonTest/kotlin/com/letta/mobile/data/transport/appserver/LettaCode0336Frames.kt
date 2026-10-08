package com.letta.mobile.data.transport.appserver

/**
 * letta-mobile-bzvro.10 (F10): serialized server frames in the shapes letta-code 0.33.6 emits,
 * written from its open-source protocol types (`src/types/runtime-scope.ts`,
 * `loop-status-protocol.ts`, `queue-update-protocol.ts`, `approval-classification-protocol.ts`,
 * `protocol_v2.ts`, tag v0.33.6). The client's contract baseline stays 0.32.10 and the desktop
 * runtime stays 0.29.12; these fixtures pin that the decoders tolerate the newer server.
 */
internal object LettaCode0336Frames {
    /** `ConversationRuntimeScope`: `agent_id` may be null for an agent-free conversation. */
    const val AGENT_FREE_STREAM_DELTA = """
        {"type":"stream_delta","runtime":{"agent_id":null,"conversation_id":"conv-free"},
         "event_seq":7,"emitted_at":"2026-10-08T10:00:00.000Z","idempotency_key":"stream_delta:7:a",
         "delta":{"id":"m-1","date":"2026-10-08T10:00:00.000Z","message_type":"assistant_message",
                  "content":"hi","run_id":"run-1"}}
    """

    const val AGENT_FREE_TURN_FINISHED = """
        {"type":"turn_finished","runtime":{"conversation_id":"conv-free"},
         "event_seq":8,"emitted_at":"2026-10-08T10:00:01.000Z","idempotency_key":"turn_finished:8:a",
         "turn_id":"turn-1","stop_reason":"end_turn","run_id":"run-1"}
    """

    const val LOOP_STATUS_RETRYING = """
        {"type":"update_loop_status","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":9,"emitted_at":"2026-10-08T10:00:02.000Z","idempotency_key":"update_loop_status:9:a",
         "loop_status":{"status":"RETRYING_API_REQUEST","active_run_ids":["run-2"],
                        "client_message_ids_by_run_id":{"run-2":["cm-1"]},"executing_tool_call_ids":[]}}
    """

    /** 0.33 `RetryMessage`: `retry_kind`, `provider`, `error_code`, transports. */
    const val RETRY_DELTA = """
        {"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":10,"emitted_at":"2026-10-08T10:00:03.000Z","idempotency_key":"stream_delta:10:a",
         "delta":{"id":"r-1","date":"2026-10-08T10:00:03.000Z","message_type":"retry","run_id":"run-2",
                  "message":"Provider overloaded, retrying","reason":"llm_api_error","attempt":2,
                  "max_attempts":5,"delay_ms":4000,"retry_kind":"provider_retry","provider":"anthropic",
                  "from_transport":null,"to_transport":null,"error_code":"overloaded_error","step_id":null}}
    """

    const val STATUS_DELTA = """
        {"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":11,"emitted_at":"2026-10-08T10:00:04.000Z","idempotency_key":"stream_delta:11:a",
         "delta":{"id":"s-1","date":"2026-10-08T10:00:04.000Z","message_type":"status",
                  "message":"Compacting conversation","level":"warning"}}
    """

    const val SLASH_COMMAND_START = """
        {"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":12,"emitted_at":"2026-10-08T10:00:05.000Z","idempotency_key":"stream_delta:12:a",
         "delta":{"id":"c-1","date":"2026-10-08T10:00:05.000Z","message_type":"slash_command_start",
                  "command_id":"cmd-1","input":"/compact"}}
    """

    const val SLASH_COMMAND_END = """
        {"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":13,"emitted_at":"2026-10-08T10:00:06.000Z","idempotency_key":"stream_delta:13:a",
         "delta":{"id":"c-2","date":"2026-10-08T10:00:06.000Z","message_type":"slash_command_end",
                  "command_id":"cmd-1","input":"/compact","output":"Compacted 42 messages","success":true}}
    """

    const val COMMAND_END_DIM = """
        {"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":14,"emitted_at":"2026-10-08T10:00:07.000Z","idempotency_key":"stream_delta:14:a",
         "delta":{"id":"c-3","date":"2026-10-08T10:00:07.000Z","message_type":"command_end",
                  "command_id":"cmd-2","input":"git status","output":"clean","success":false,
                  "dim_output":true,"preformatted":true}}
    """

    const val APPROVAL_CLASSIFICATION_END = """
        {"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":15,"emitted_at":"2026-10-08T10:00:08.000Z","idempotency_key":"stream_delta:15:a",
         "delta":{"id":"a-1","date":"2026-10-08T10:00:08.000Z","message_type":"approval_classification_end",
                  "run_id":"run-2","auto_allowed_tool_call_ids":["tc-1","tc-2"],
                  "auto_denied_tool_call_ids":["tc-3"],"user_input_tool_call_ids":[]}}
    """

    /** `update_queue` with 0.32+ `removed[]` transitions and a parked (`paused`) item. */
    const val QUEUE_WITH_REMOVALS = """
        {"type":"update_queue","runtime":{"agent_id":null,"conversation_id":"conv-free"},
         "event_seq":16,"emitted_at":"2026-10-08T10:00:09.000Z","idempotency_key":"update_queue:16:a",
         "queue":[{"id":"q-1","client_message_id":"cm-2","kind":"message","source":"user",
                   "enqueued_at":"2026-10-08T10:00:09.000Z","paused":true,"content":"next"}],
         "removed":[{"client_message_id":"cm-1","disposition":"dequeued"},
                    {"client_message_id":"cm-0","disposition":"cancelled"}]}
    """

    /** `DeviceStatus` with the 0.33 toolset names, `available_toolsets` and new process kinds. */
    const val DEVICE_STATUS_TOOLSETS = """
        {"type":"update_device_status","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":17,"emitted_at":"2026-10-08T10:00:10.000Z","idempotency_key":"update_device_status:17:a",
         "device_status":{"current_toolset":"codex","toolset_preference":"auto",
                          "available_toolsets":[{"id":"codex","display_name":"Codex","description":"",
                                                 "is_featured":true}],
                          "background_processes":[{"process_id":"p-1","kind":"monitor"},
                                                  {"process_id":"p-2","kind":"workflow"}]}}
    """

    /** A 0.33-only frame type the client does not model yet. */
    const val EXECUTE_COMMAND_RESPONSE = """
        {"type":"execute_command_response","request_id":"req-9","command_id":"compact","success":true}
    """
}
