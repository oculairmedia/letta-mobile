# service.credential.remove: service credentials v1

- Method: `service.credential.remove` over the authenticated Iroh `admin_rpc` channel. Capability: `admin.full`.
- Contract: [service credentials v1](../service-credentials-v1.md), section "Remove".
- Params: `service_id`, optional `expected_revision`.
- Result: the empty status. Settings, secret and last test are deleted.
- Errors: `service.invalid_request`, `service.unknown_service`, `service.revision_conflict`; `forbidden` without `admin.full`.

Fixture: `service.credential.remove/service-credentials-v1.json`, run through the real stream server and service credential routes.
