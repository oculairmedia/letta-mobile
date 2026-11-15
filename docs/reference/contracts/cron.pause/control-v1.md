# `cron.pause` v1

Forwards the existing App Server `cron_pause` command. Ships as a matched set: wrapper + clients.

Params: required string `task_id`. The wrapper generates the native request ID.

Success: `{"task": <host-confirmed task or null>}`. A native failure returns an admin RPC error, preserving the host's error text; missing `task_id` fails before calling the host. No native client returns a clear unavailable error.

Scope: requires the peer's `schedule.manage` capability, like sibling cron mutations. The router applies agent scope to any supplied `agent_id`. Uses the same task-id addressing as `cron.trigger`.

Old peers without this route return unknown-method errors; clients retain the prior state and show the error. A successful mutation is followed by a repository refresh, so rows and the paused filter follow the host's list.
