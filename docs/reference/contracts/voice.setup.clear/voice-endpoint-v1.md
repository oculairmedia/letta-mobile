# voice.setup.clear: voice endpoint v1

- Method: `voice.setup.clear` over the authenticated Iroh `admin_rpc` channel. Capability: `admin.full`.
- Contract: [voice endpoint v1](../voice-endpoint-v1.md), section "Clear".
- Params: optional `expected_revision`.
- Result: the empty setup status. The URL, names, key and grants are deleted.
- Errors: `voice.revision_conflict` when `expected_revision` is stale.

Fixture: `voice.setup.clear/voice-endpoint-v1.json`, run through the real stream server and voice routes.
