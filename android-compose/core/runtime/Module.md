# Module :core:runtime

Portable contracts for executing agent turns and recording/replaying their events.
This module depends on typed domain identities from `:core:ids`; it does not bind
an Android service, socket, model provider, filesystem or Compose renderer.

Use `TurnCommand` and `TurnEngine` at the execution boundary. Engine drafts become
`RuntimeEventEnvelope` records through a `RuntimeEventOutbox`, and
`RuntimeEventProjector` folds those records into replay state. The included
in-memory outbox retains all events for its instance lifetime; durable hosts must
choose an appropriate persistent implementation instead of assuming this module
provides restart storage.

Memory-file revisions and imported/exported file descriptors are virtual runtime
contracts. They are separate from host filesystem permissions and the App Server
MemFS browser's Git-backed wire models. Tool capability flags, permission decisions
and tools requiring a real human answer also remain distinct contracts.

# Package com.letta.mobile.runtime

Portable turn inputs, approvals, runtime event envelopes, replay projection and virtual file/memory
descriptors define the execution boundary here. Outbox offsets must not be treated as upstream
transport sequence numbers, and data-layer and runtime `ConversationId`/`RunId` wrappers require
explicit conversion. Hosts extend execution and durability through `TurnEngine` and
`RuntimeEventOutbox`; implementations must preserve event identity and replay ordering rather
than introduce UI or platform dependencies into these contracts.
