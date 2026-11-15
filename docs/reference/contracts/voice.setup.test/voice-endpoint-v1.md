# voice.setup.test: voice endpoint v1

- Method: `voice.setup.test` over the authenticated Iroh `admin_rpc` channel. Capability: `admin.full`.
- Contract: [voice endpoint v1](../voice-endpoint-v1.md), section "Connection test".
- Params: `{}` checks the stored setup with the stored key and records reachability; `base_url` with an
  optional `api_key` checks a candidate and forgets it. Without `api_key` the stored key goes only to a
  candidate on the stored key's origin.
- The host calls `GET {base}/v1/models` only; no audio or text is sent, so no grant is needed.
- Result: `model_count` and `checked_at_ms`.
- Errors: `voice.not_configured`, `voice.invalid_request`, `voice.insecure_transport` and the upstream
  codes (`voice.upstream_unreachable`, `_unauthorized`, `_rejected`, `_bad_response`, `_timeout`).

Fixture: `voice.setup.test/voice-endpoint-v1.json`, run through the real stream server and voice routes
against a fake speech server.
