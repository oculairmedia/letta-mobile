# voice.models.list: voice endpoint v1

- Method: `voice.models.list` over the authenticated Iroh `admin_rpc` channel. Capability: `chat.read`.
- Contract: [voice endpoint v1](../voice-endpoint-v1.md), section "Models".
- Params: none.
- Result: `models` from `GET {base}/v1/models`; `voices` from `GET {base}/v1/audio/voices` when the
  server lists them (`voices_source: "upstream"`), else only the stored voice (`"configured"`);
  `checked_at_ms`. The models call refreshes the stored reachability.
- Errors: `voice.not_configured` and the upstream codes.

Fixture: `voice.models.list/voice-endpoint-v1.json`, run through the real stream server and voice routes
against a fake speech server.
