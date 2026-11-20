# Service credentials v1: non-LLM service secrets held on the host

Status: **proposed for review; host routes, the Settings > Providers > Services section and the
TypeSafe Jev service are built.** Route and capability registration needs human review.

Fixtures: one per method, `android-compose/sharedLogic/src/commonTest/resources/contracts/<method>/service-credentials-v1.json`,
each paired with a short spec in `docs/reference/contracts/<method>/service-credentials-v1.md`.
Wire types and the client: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/servicecredentials/`.
Host routes, store, adapters: `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/servicecredentials/`.
Contract test: `WireContractsTest` (jvmTest) runs every case through the real stream server, capability
gate and routes against a fake TypeSafe that fails the test unless it receives only the synthetic test
sentence with the placeholder key.

## Decision this implements

Credentials for the non-LLM APIs the system calls (TypeSafe Jev first, the speech server next) are
stored **on the host**, in its secret storage, never on a client. Devices can set or replace a
secret but never read it back. They are managed from one generic "Services" section of the Providers
settings. Adding a service is one host descriptor (data: name, address rules, secret label, settings)
plus one host adapter (its connection test); no new screen and no new contract.

This reuses the provider and voice-setup patterns: `SecretWriteValue` inside a write-only command
whose serializer cannot decode; the owner-only secret file of the voice setup
(`IrohSecretKeyStores.writeOwnerOnly`, secrets bound to the origin they were entered for); revisions
for optimistic concurrency; the capability matrix in `IrohPeerCapabilities.forAdminMethod`.

## Transport, discovery and versioning

- Every method is one `admin_rpc` request over the authenticated Iroh channel; no streaming.
- A host serving every v1 method advertises the feature capability `service_credentials_v1`. A
  client without it shows no Services page. Every list, status and write carries `contract_version: 1`.

## Methods and authorisation

| Method | Capability | Purpose |
| --- | --- | --- |
| `service.credential.list` | `chat.read` | Every known service with its status, never a secret |
| `service.credential.set` | `admin.full` | Store or replace one service's address, settings and secret |
| `service.credential.test` | `admin.full` | Test the stored credential with the adapter's fixed synthetic request |
| `service.credential.remove` | `admin.full` | Delete one service's settings, secret and last test |

The list answer carries `can_manage`: whether the caller's granted capabilities allow the writes,
computed on the host, so a client never probes.

## Status

```json
{"service_id": "typesafe-jev", "display_name": "TypeSafe Jev", "summary": "...", "configured": true,
 "default_base_url": "https://api.typesafe.ai", "base_url_editable": false, "has_secret": true,
 "secret_label": "API key", "config_fields": [{"key": "model", "label": "Model", "help": "...", "default": "jev-latest"}],
 "config": {"model": "jev-latest"}, "revision": "rev-3",
 "last_test": {"state": "passed", "checked_at_ms": 1767225600000}}
```

- `service_id` grammar: `[a-z0-9]+(-[a-z0-9]+)*`, at most 64 characters.
- `base_url` is present only when an override is stored; `default_base_url` only when the service has one.
- `revision` is present only when something is stored; it keeps counting across a removal.
- `last_test.state` is `unknown`, `passed` or `failed` (with `error`). Every write resets it to `unknown`.

## Write (`service.credential.set`)

Params: `service_id` (required), `base_url` (only where `base_url_editable`), at most one of `secret`
(store it, at most 4096 characters) and `remove_secret: true`, `config` (only declared keys, non-empty
strings of at most 256 characters; replaces the stored settings), `expected_revision`.

- A write without `secret` keeps the stored secret only while the address's origin is unchanged; a
  write to a new origin drops it.
- Moving a service: where `base_url_editable` is true, an admin may point the service at a different
  https address and enter a key there in the same write (or a later one). The old key is never carried
  to the new address: it is bound to the origin it was entered for, a write to a new origin deletes it,
  and the status shows `has_secret: false` until a key is entered for the new address.
- A secret is kept only for https, or plain http to a loopback IP literal (`127.0.0.0/8` or `::1`;
  the name `localhost` does not count, since a name can resolve elsewhere), else
  `service.insecure_transport`.
- The write does not contact the service.

## Test (`service.credential.test`)

Params: `service_id`. Tests the **stored** credential only; v1 has no candidate test, so a stored
secret can never be steered to a new address. The adapter sends only a fixed synthetic request
compiled into it. An upstream failure is a test outcome (`last_test.state: failed` with one of
`service.unauthorized`, `service.rate_limited`, `service.unreachable`, `service.upstream_timeout`,
`service.upstream_error`), not an RPC error. The outcome is recorded against the revision tested.

TypeSafe Jev: `POST {base}/v1/systemone` with `Authorization: Bearer <secret>` and one Score question
about a fixed made-up sentence. `200` with an answer to the probe question passes; `401`/`403` is
unauthorized; `429` rate limited; other statuses upstream error.

## Remove (`service.credential.remove`)

Params: `service_id`, `expected_revision`. Deletes settings, secret and last test; answers the empty
status.

## Errors (closed set)

`service.invalid_request`, `service.unknown_service`, `service.not_configured`,
`service.insecure_transport`, `service.revision_conflict`; a pairing without `admin.full` is refused
by the transport (`forbidden`); a host without the contract answers an unknown method.

## Host storage, privacy and logging

- Settings without secrets: `service-credentials.json` beside the host's other state.
- Secrets: `service-credential-secrets`, one owner-only file (POSIX `rw-------` or an owner-only ACL,
  restricted before the secret is written, replaced by a rename, no best-effort fallback), each
  secret bound to the origin it was entered for. Behind `ServiceSecretStore`, so a keystore provider
  can replace the file.
- Telemetry: method, outcome code, duration. Never a secret, an address, a response body or any text.
- No conversation text goes to any service through this contract. A later host-side TypeSafe mood
  gate stays behind an off-by-default host flag until the person sets a budget.

## Clients

Clients persist nothing of this: the Services page holds the list and any typed secret in memory,
sends a typed secret once inside the write command, and clears it after the write.
