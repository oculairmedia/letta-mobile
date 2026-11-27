//! The Baken core's foreign-function boundary (`baken_core`).
//!
//! The normative text is `docs/reference/contracts/ffi/ffi-v1.md`. This crate is UniFFI glue and
//! nothing else: every exported call converts its arguments, hops onto the node's tokio runtime,
//! calls core-node (or the crate core-node composes), and converts the result back. Behaviour lives
//! in the crates below; if a call here needs a decision, that decision belongs in one of them.
//!
//! - [`CoreNode`]: create, start, stop, connect, node.info, the membership view, catalog read
//!   views and placement status.
//! - Streams: `subscribe_*` takes a listener (a foreign trait) and returns a [`TaskHandle`] whose
//!   `cancel()` ends the stream (the matrix-rust-sdk pattern). Dropping the handle cancels too.
//! - Foreign traits the platform implements: [`RuntimeHost`] (supervising letta-code) and
//!   [`PlatformHooks`] (the node key, foreground/background, network changes).
//! - Errors cross as [`CoreError`]; a foreign implementation fails with [`HostError`].
//!
//! The crate builds as one cdylib that links every core crate (the combined library), so std, the
//! allocator, tokio, iroh, automerge and SQLite exist once per process.

#![allow(
    clippy::needless_pass_by_value,
    reason = "UniFFI hands every exported argument over by value"
)]

#[cfg(test)]
mod bridge_tests;
mod error;
mod hooks;
mod node;
mod ops;
mod records;
mod stream;
mod transport;

pub use error::{CoreError, HostError};
pub use hooks::{PlatformEvents, PlatformHooks, RuntimeHost};
pub use node::CoreNode;
pub use ops::{
    AddMemberRequest, GenesisRequest, SignedMembershipOp, node_id_of_secret_key,
    sign_add_member_op, sign_genesis_op,
};
pub use records::{
    AgentRuntimeSpec, CatalogDocument, Member, MemberPreset, MemberStatus, MembershipView,
    NodeClass, NodeEvent, NodeInfo, NodeOptions, PeerAddress, PlacementStatus, QuiesceOutcome,
    RuntimeInfo, RuntimePatches, StoreFormat,
};
pub use stream::{
    CatalogEvent, CatalogEventListener, MembershipListener, NodeEventListener, TaskHandle,
};
pub use transport::{InProcessNetwork, Transport};

uniffi::setup_scaffolding!("baken_core");
