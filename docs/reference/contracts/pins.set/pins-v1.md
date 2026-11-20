# pins.set: pins v1

- Method: `pins.set` over the authenticated Iroh `admin_rpc` channel (or the on-device runtime's
  in-process router). Capability: **`conversation.manage`**.
- Contract: [pins v1](../pins-v1.md). One global list for the host's single owner.
- Params: `{"agent_ids": [...], "expected_revision": n}`: the whole new list in order and the revision
  it was based on.
- Result: the list after the write. A change bumps the revision by one and pushes `pins_updated`
  (`v`, `type`, `id`, `ts`, `revision`, `agent_ids`) to every authenticated, agent-reading, unscoped
  connection; an unchanged list keeps the revision and pushes nothing.
- Errors: `revision_conflict` when `expected_revision` is not the stored revision (nothing changes);
  `invalid_request` for a malformed list or revision; `state_unreadable`; agent-scoped peers are
  refused (`forbidden: agent not in this device's scope`); the transport refuses a pairing without
  `conversation.manage` (`forbidden`).

Fixture: `pins.set/pins-v1.json`, run through the real pins routes and the full host router.
