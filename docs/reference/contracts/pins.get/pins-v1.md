# pins.get: pins v1

- Method: `pins.get` over the authenticated Iroh `admin_rpc` channel (or the on-device runtime's
  in-process router). Capability: `chat.read`.
- Contract: [pins v1](../pins-v1.md). The host advertises `pinned_agents_v1`. One global list for
  the host's single owner.
- Params: none (`{}`).
- Result: `{"revision", "agent_ids"}`, the ordered pinned agents. Nothing stored: revision 0, no ids.
- Errors: an agent-scoped peer is refused (`forbidden: agent not in this device's scope`); an unreadable
  stored list answers `state_unreadable`; a pairing without `chat.read` is refused by the transport
  (`forbidden`); a host that predates the contract answers `Unknown method: pins.get`.

Fixture: `pins.get/pins-v1.json`, run through the real pins routes and the full host router.
