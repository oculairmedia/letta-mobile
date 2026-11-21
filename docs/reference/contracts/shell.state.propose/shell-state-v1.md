# shell.state.propose: shell state v1

- Method: `shell.state.propose` over the authenticated Iroh `admin_rpc` channel (or the on-device
  runtime's in-process router). Capability: **`conversation.manage`**.
- Contract: [shell state v1](../shell-state-v1.md). One document for the host's single owner.
- Params: `{"changes": [{"key", "value", "expected_revision"}, ...]}`: entry changes, each with the
  revision of that entry in the caller's copy (0 for none); no `value` removes the entry.
- Result: the document after the proposal. A change bumps `document_revision` by one, stamps every written
  entry with it and the serving connection as `updated_by`, and pushes `shell_state_updated` (`v`, `type`,
  `id`, `ts`, then the document) to every authenticated, agent-reading, unscoped connection; a proposal
  that changes nothing keeps the revision and pushes nothing.
- Keys: only under an allowed namespace (`appearance.` today); any other key is `invalid_request` for the whole
  proposal and nothing changes. No secret is ever a shell state entry.
- Errors: `revision_conflict` when any `expected_revision` is stale (nothing changes, all or nothing);
  `invalid_request` for a malformed proposal; `too_large` over a size limit; `state_unreadable`;
  agent-scoped peers are refused (`forbidden: agent not in this device's scope`); the transport refuses a
  pairing without `conversation.manage` (`forbidden`).

Fixture: `shell.state.propose/shell-state-v1.json`, run through the real shell state routes and the full host router.
