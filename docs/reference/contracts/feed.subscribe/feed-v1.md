# feed.subscribe: conversation feed v1

- Method: `feed.subscribe` over the authenticated Iroh `admin_rpc` channel. Capability: `chat.read`.
- Contract: [conversation feed v1](../conversation-feed-v1.md). The host advertises `conversation_feed_v1`.
- Params: `{"conversation_id", "agent_id"?, "from"?: {"epoch", "seq"}, "limit"?: 1..500}`.
- Result: a `FeedPage`: `{"mode": "replay" | "snapshot", "resync_reason", "cursor": {"epoch", "seq"}, "base_seq", "head_seq",
  "has_more", "entries": [{"epoch", "seq", "kind", "turn", "payload" | "pointer"}]}`.
  `replay` continues the caller's cursor; `snapshot` starts the retained journal over (`no_cursor`, `epoch_changed`,
  `behind_horizon`, `ahead_of_head`).
- Errors: `invalid_request` (including a bare `default` with no `agent_id`); `unavailable: ...` when the host could not persist its epoch; `forbidden: agent not in this device's scope` (an out-of-scope, unknown or mismatched owner);
  `forbidden: conversation out of authorized scope`; `forbidden` from the transport's capability gate;
  `Unknown method: feed.subscribe` from a host that predates the contract.

Fixture: `feed.subscribe/feed-v1.json`, replayed through the real route over a seeded journal and decoded by the client
types. The cases name their own host state (`host_state.feed.turns`): each turn is written through the journal's writer.
