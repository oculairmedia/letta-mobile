//! The replicated agent-state log (R1) and its replication protocol `baken/log/1`.
//!
//! The normative text is `docs/reference/contracts/log/log-v1.md`; the fixtures beside it are run
//! by this crate's tests. Everything except [`driver`] is a pure state machine: no I/O, no tasks,
//! no clock reads (time is passed in). State lives in a `core_store::LogStore` the caller hands in,
//! inside a transaction it commits.
//!
//! - [`header`]: the fixed 168-byte header and the 232-byte signed entry.
//! - [`hashes`]: BLAKE3 derive-key contexts, payload hashes, SHA-256 and git object ids.
//! - [`payload`]: the payload kinds and their deterministic CBOR.
//! - [`agent_log`]: [`AgentLog`], holder append, replica receive, claims with their fork point,
//!   evaluation of the canonical segment, placement and divergences.
//! - [`judge`]: [`ClaimJudge`], the membership-dependent claim rules (implemented by placement).
//! - [`divergence`]: the canonical divergence inputs C2's catalog node id needs.
//! - [`prune`]: payload pruning that keeps the chain intact, and whole-log verification.
//! - [`fold`]: a pure fold of canonical payloads into a file tree and refs (manifest checks).
//! - [`wire`] and [`replicator`]: `baken/log/1` messages and the session state machine.
//! - [`driver`]: the async driver over a minimal byte-stream link.

pub mod agent_log;
pub mod allowlist;
pub mod divergence;
pub mod driver;
pub mod fold;
pub mod hashes;
pub mod header;
pub mod judge;
pub mod payload;
pub mod prune;
pub mod replicator;
pub mod value;
pub mod wire;

pub use agent_log::{AgentLog, Analysis, Evaluation, Incoming, LogError, Received};
pub use divergence::{Divergence, DivergenceInputs, DivergenceProjection, TouchedConversation};
pub use header::{EntryHeader, HeaderError, SignedEntry};
pub use judge::{ClaimContext, ClaimJudge, StructuralJudge};
pub use payload::{EpochOpen, EpochSeal, Payload, Reason};
pub use replicator::{Session, SessionConfig, SessionEvent};
pub use value::Pos;
pub use wire::{Message, Mode};

/// The node protocol's frame cap.
pub const MAX_FRAME: usize = 1_048_576;
/// Entry bytes per `entries` frame.
pub const MAX_BATCH_BYTES: usize = 768 * 1024;
/// Entries per `entries` frame.
pub const MAX_BATCH_ENTRIES: usize = 512;
/// Payloads above this travel by hash over `baken/blob/1`.
pub const MAX_INLINE_PAYLOAD: usize = 192 * 1024;
/// The largest payload.
pub const MAX_PAYLOAD: u64 = 64 * 1024 * 1024;
/// Unacked bytes a sender keeps per log.
pub const UNACKED_PER_LOG: usize = 512 * 1024;
/// Unacked bytes a sender keeps per connection.
pub const UNACKED_PER_CONN: usize = 4 * 1024 * 1024;
/// Out-of-order entries buffered per log.
pub const PENDING_PER_LOG: usize = 4_096;
/// A ping is sent after this long without sending.
pub const PING_INTERVAL_MS: u64 = 15_000;
/// A live tail over budget this long is stopped with `resync`.
pub const BACKPRESSURE_RESYNC_MS: u64 = 5_000;
/// A claim's `n` may exceed the highest `n` in its ancestry by at most this.
pub const EPOCH_N_MAX_STEP: u64 = 1 << 20;
/// The largest epoch number (the feed epoch mapping needs it).
pub const EPOCH_N_LIMIT: u64 = (1 << 32) - 1;
