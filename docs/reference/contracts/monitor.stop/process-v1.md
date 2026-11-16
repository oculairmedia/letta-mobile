# monitor.stop: Background process stop v1

Authenticated Iroh `admin_rpc` mutation forwarding the existing App Server `monitor_stop` command.
The pinned host accepts Monitor processes only. Unknown IDs, other process kinds, mismatched runtime
ownership, inactive runtimes and kill failures are refused; a refusal remains visible in the composer.
Required nonblank strings: `agent_id`, `conversation_id`, `process_id`. The router enforces agent
scope and the handler enforces conversation scope before dispatch. The peer needs `chat.send`.
The wrapper generates a native request ID and forwards the process ID and runtime unchanged.
A successful response returns `{"accepted":true}`; missing parameters, unavailable native client,
and unsuccessful App Server responses return an RPC error. Acceptance never removes a process:
only a subsequent `update_device_status` snapshot, forwarded through the shared `toolset_status` relay, changes the shelf. Wrapper and clients ship together.
The JSON fixture is exercised against the real router by MonitorStopContractTest.
