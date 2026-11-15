# voice.setup.get: voice endpoint v1

- Method: `voice.setup.get` over the authenticated Iroh `admin_rpc` channel. Capability: `chat.read`.
- Contract: [voice endpoint v1](../voice-endpoint-v1.md), section "Setup status". The host advertises
  `voice_endpoint_v1` when it serves every voice method.
- Params: none (`{}`).
- Result: the shared setup status. It names the speech server, its models and voice, the shared grant
  record and the last reachability check. It never carries the key: `has_api_key` says only whether one
  is stored. Nothing stored: `configured: false`, both kinds unavailable, empty grants, reachability
  `unknown`.
- Errors: a pairing without `chat.read` is refused by the transport (`forbidden`); a host that predates
  the contract answers `Unknown method: voice.setup.get`.

Fixture: `voice.setup.get/voice-endpoint-v1.json`, run through the real stream server and voice routes.
