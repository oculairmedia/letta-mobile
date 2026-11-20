# service.credential.set: service credentials v1

- Method: `service.credential.set` over the authenticated Iroh `admin_rpc` channel. Capability: `admin.full`.
- Contract: [service credentials v1](../service-credentials-v1.md), section "Write".
- Params: `service_id`, optional `base_url` (only where `base_url_editable`), at most one of `secret` and `remove_secret: true`, `config` (declared keys only) and `expected_revision`.
- Result: the service's new status with a new revision and `last_test.state: unknown`. The write does not contact the service. Without `secret` the stored secret is kept only while the origin is unchanged.
- Errors: `service.invalid_request`, `service.unknown_service`, `service.insecure_transport`, `service.revision_conflict`; a pairing without `admin.full` is refused by the transport (`forbidden`).

Fixture: `service.credential.set/service-credentials-v1.json`, run through the real stream server and service credential routes.
