# Slash command execution v1

`slash_command.execute` forwards to the App Server's `execute_command` command. It requires `agent_id`, `conversation_id` and `command_id`; optional `args` is passed unchanged. The wrapper generates the native request id and constructs the runtime from the top-level ids, after agent and conversation scope checks.

A confirmed response contains `output` (string or null) and `result` (object or null). A native refusal becomes an admin RPC error; a missing native client reports capability unavailable. Commands are not retried automatically because their effects can be ambiguous after disconnect.

The route requires `admin.full`: the general command catalog includes mutations to tools, secrets and the host installation, so a chat-send grant alone cannot authorize arbitrary commands. Existing client-local commands keep their dedicated client routes.

This ships as a matched set: wrapper + clients. The fixture exercises native success, native refusal, missing command id and conversation scope denial against the real registered handler.
