# toolset.update v1

Forwards an active conversation's toolset preference to App Server `update_toolset`.
Requires `agent_id`, `conversation_id`, and nonempty `toolset_preference` parameters.
The peer needs conversation management permission and access to the named agent and conversation.

The wrapper generates a request id and calls `AppServerClient.updateToolset` once.
A successful result contains `current_toolset` when supplied by the host. This is the resolved
preset, which can differ from the preference `auto`. Clients keep their preference selected
from `update_device_status.current_toolset_preference`, never from the requested value or
resolved preset. An upstream rejection returns `success: false` and the host error.

Producer and shared client fixtures: `toolset-v1.json`. Real-handler conformance runs in
`WireContractsTest`; forwarding and parameter failures run in `ToolsetHandlersTest`.
This route ships as a matched set: wrapper + clients.

The wrapper forwards the complete device status to authorized viewers as a `toolset_status` notification.
The toolset picker and background-process shelf read the same connection-owned cache; `background_processes`
is preserved unchanged, replayed on viewer attach and cleared with the connection. The envelope is unchanged:
`{"type":"toolset_status","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"device_status":{...}}`.
`device_status` is the host snapshot, including `available_toolsets` and
`current_toolset_preference`. It replays the latest snapshot when a viewer joins, and drops the
cache on host disconnect. The notification has no event sequence because the native App Server
and the wrapper's relayed turns have independent sequence spaces. Scope and chat-read permission
are checked at delivery, including replay. `ToolsetDeviceStatusRelayTest` exercises this relay.
