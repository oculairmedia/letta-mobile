//! Node protocol v1 for the Baken core, as plain data and pure functions.
//!
//! The normative text is `docs/reference/contracts/node-protocol/node-protocol-v1.md`; the fixtures
//! beside it are run by this crate's tests. Nothing here does I/O, spawns tasks or reads a clock,
//! so the crate builds for `wasm32-unknown-unknown` and every platform the core targets.
//!
//! - [`codec`]: length-prefixed frames and `frame_part` chunking, with explicit [`codec::Limits`].
//! - [`ids`]: typed ids ([`NodeId`], [`AgentId`], [`ConversationId`], [`RequestId`], ...).
//! - [`epoch`]: the placement [`Epoch`], a total order over `(n, claimant)`.
//! - [`alpn`] and [`closure`]: the protocols a node accepts and the codes it closes connections with.
//! - [`auth`]: the auth error vocabulary, limits and the per-NodeId failure limiter.
//! - [`frames`]: control-lane frames, error frames and denial frames as serde types.
//! - [`capability`]: transport capability strings exchanged in `auth` / `auth_response`.
//! - [`bcbor`]: the deterministic CBOR profile every signed body uses; decoding rejects every
//!   non-canonical input.
//! - [`crypto`]: BLAKE3 derive-key with a caller's context, plain BLAKE3, and strict Ed25519 over a
//!   32-byte id.

pub mod alpn;
pub mod auth;
pub mod bcbor;
pub mod capability;
pub mod closure;
pub mod codec;
pub mod crypto;
pub mod epoch;
pub mod frames;
pub mod ids;

pub use epoch::Epoch;
pub use ids::{AgentId, ClientMessageId, ConversationId, NodeId, RequestId};
