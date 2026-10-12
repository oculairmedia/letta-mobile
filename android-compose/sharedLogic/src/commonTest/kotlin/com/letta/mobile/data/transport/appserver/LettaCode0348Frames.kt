package com.letta.mobile.data.transport.appserver

/**
 * letta-mobile-zxk2n.1: server frames in the shapes letta-code 0.34.8 emits, written from its
 * open-source protocol types at tag v0.34.8 (`turn-finished-protocol.ts`, `client-preferences.ts`,
 * `teleport-protocol.ts`, `background-process-protocol.ts`, `protocol_v2.ts`). 0.34.8 is the
 * desktop bundled runtime and the wire contract baseline; the Android embedded runtime (0.26.1)
 * never emits these.
 */
internal object LettaCode0348Frames {
    /** `TurnFinishedMessage.terminal_consumer_ids`: the terminal is replayed until acked. */
    const val TURN_FINISHED_WITH_CONSUMERS = """
        {"type":"turn_finished","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":30,"emitted_at":"2026-10-11T10:00:00.000Z","idempotency_key":"turn_finished:30:a",
         "turn_id":"turn-30","stop_reason":"end_turn","run_id":"run-30",
         "terminal_consumer_ids":["letta-mobile:device-a","desktop:b"]}
    """

    const val AGENT_FREE_TURN_FINISHED_WITH_CONSUMER = """
        {"type":"turn_finished","runtime":{"agent_id":null,"conversation_id":"conv-free"},
         "event_seq":31,"emitted_at":"2026-10-11T10:00:01.000Z","idempotency_key":"turn_finished:31:a",
         "turn_id":"turn-31","stop_reason":"end_turn","terminal_consumer_ids":["letta-mobile:device-a"]}
    """

    /** A `workflow` background process with the 0.34 `progress` summary. */
    const val DEVICE_STATUS_WORKFLOW_PROGRESS = """
        {"type":"update_device_status","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "event_seq":32,"emitted_at":"2026-10-11T10:00:02.000Z","idempotency_key":"update_device_status:32:a",
         "device_status":{"current_toolset":"codex",
           "background_processes":[
             {"process_id":"wf-1","kind":"workflow","description":"Review the repo","started_at_ms":1760176800000,
              "status":"running",
              "progress":{"agents_total":5,"agents_done":2,"agents_failed":1,"agents_running":2,"total_tokens":48000,
                          "phases":[{"title":"Explore","agents_total":3,"agents_done":2,"agents_failed":1,
                                     "agents_running":0,"total_tokens":30000},
                                    {"title":"Write","agents_total":2,"agents_done":0,"agents_failed":0,
                                     "agents_running":2,"total_tokens":18000}]}},
             {"process_id":"wf-2","kind":"workflow","description":"No progress yet","started_at_ms":1760176801000,
              "status":"running"}],
           "experiments":[{"id":"memory_palace","label":"Memory palace","description":"","enabled":false}]}}
    """

    /** `ExperimentId` gained `memory_palace`; the response stays an untyped frame. */
    const val GET_EXPERIMENTS_RESPONSE = """
        {"type":"get_experiments_response","request_id":"req-exp","success":true,
         "experiments":[{"id":"memory_palace","label":"Memory palace","description":"","enabled":true,
                         "source":"override"}]}
    """

    /** `TeleportReadyMessage.client_preferences` is required from 0.34. */
    const val TELEPORT_READY = """
        {"type":"teleport_ready","client_preferences":{"toolset":{"include":["Read"]}},
         "teleport_id":"tp-1","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},
         "success":true,"active_turn":false}
    """
}
