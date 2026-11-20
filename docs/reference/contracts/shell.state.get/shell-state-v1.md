# shell.state.get: shell state v1

- Method: `shell.state.get` over the authenticated Iroh `admin_rpc` channel (or the on-device runtime's
  in-process router). Capability: `chat.read`.
- Contract: [shell state v1](../shell-state-v1.md). The host advertises `shell_state_v1`. One document for
  the host's single owner.
- Params: none (`{}`).
- Result: `{"schema_version", "document_revision", "entries"}`, each entry with `value`, `revision`,
  `updated_by` and `updated_at_ms`. Nothing stored: revision 0, no entries.
- Errors: an agent-scoped peer is refused (`forbidden: agent not in this device's scope`); an unreadable
  or newer-schema stored document answers `state_unreadable`; a pairing without `chat.read` is refused by
  the transport (`forbidden`); a host that predates the contract answers `Unknown method: shell.state.get`.

Fixture: `shell.state.get/shell-state-v1.json`, run through the real shell state routes and the full host router.
