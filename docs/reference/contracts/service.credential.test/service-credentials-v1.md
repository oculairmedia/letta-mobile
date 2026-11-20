# service.credential.test: service credentials v1

- Method: `service.credential.test` over the authenticated Iroh `admin_rpc` channel. Capability: `admin.full`.
- Contract: [service credentials v1](../service-credentials-v1.md), section "Test".
- Params: `service_id`. Tests the stored credential only.
- Result: the status with `last_test` recorded (`passed`, or `failed` with an outcome code). The adapter sends only its fixed synthetic request; an upstream failure is an outcome, not an error.
- Errors: `service.invalid_request`, `service.unknown_service`, `service.not_configured`; `forbidden` without `admin.full`.

Fixture: `service.credential.test/service-credentials-v1.json`, run through the real stream server and service credential routes.
