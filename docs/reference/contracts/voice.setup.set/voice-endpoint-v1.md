# voice.setup.set: voice endpoint v1

- Method: `voice.setup.set` over the authenticated Iroh `admin_rpc` channel. Capability: `admin.full`.
- Contract: [voice endpoint v1](../voice-endpoint-v1.md), section "Setup write".
- Params: `base_url` (required, plain http(s)), optional `transcription_model`, `speech_model`, `voice`,
  `language`, at most one of `api_key` (store it) and `remove_api_key: true` (delete it), `grants` (the
  whole grant record for the new origin) and `expected_revision`.
- Result: the new setup status with a new revision and reachability `unknown`. The write does not
  contact the speech server.
- Key rules: the key is write-only and kept in the host's key store, bound to the origin it was entered
  for. Keeping it applies only while the origin is unchanged; a write to a new origin drops the key and
  any grant it does not restate. A key over plain http to a host other than loopback is refused.
- Errors: `voice.invalid_request`, `voice.insecure_transport`, `voice.revision_conflict`; a pairing
  without `admin.full` is refused by the transport (`forbidden`).

Fixture: `voice.setup.set/voice-endpoint-v1.json`, run through the real stream server and voice routes.
