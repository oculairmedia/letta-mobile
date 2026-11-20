# service.credential.list: service credentials v1

- Method: `service.credential.list` over the authenticated Iroh `admin_rpc` channel. Capability: `chat.read`.
- Contract: [service credentials v1](../service-credentials-v1.md), section "Status".
- Params: none.
- Result: `contract_version`, `can_manage` (whether this pairing may write, from its granted capabilities) and one status per known service. Never a secret: only `has_secret`.
- Errors: none beyond the transport's own refusals.

Fixture: `service.credential.list/service-credentials-v1.json`, run through the real stream server and service credential routes.
