//! The Baken node: the composition root of the core.
//!
//! The normative text is `docs/reference/contracts/node/node-v1.md`. A [`Node`] owns one tokio
//! runtime (no global runtime) and on it:
//!
//! - the node store (`core_store::Store`), membership ([`membership::Membership`]) and the catalog
//!   node (`core_catalog::CatalogNode`);
//! - the carrier and the protocols the node serves ([`Node::protocols`]): the node session's
//!   control lane (`auth`, `node.info`), `baken/log/1` (core-log's replicator driver over a
//!   carrier byte stream), `baken/place/1` and `baken/member/1` ([`wire`]), and the catalog's
//!   `meridian/automerge-repo/1`;
//! - the log hub that relays stored entries to every session;
//! - one actor per agent log ([`actor`]): the holder's exporter and checkpoints, the feed
//!   journal, the move pipeline on both sides, failover through placement's evaluator, fencing
//!   (F4) and the fenced report, and the projection's materialize-and-install on takeover;
//! - the [`RuntimeHost`] that supervises letta-code, implemented outside the core.
//!
//! [`info`] builds node.info and the I6 runtime parity string.

pub mod actor;
pub mod clock;
pub mod host;
pub mod info;
pub mod membership;
mod node;
pub mod wire;

pub use actor::{AgentHandle, FeedEntry};
pub use core_placement::pipeline::MoveOptions;
pub use host::{AgentRuntime, AppServerEndpoint, Quiesce, RuntimeError, RuntimeHost, RuntimeInfo};
pub use info::{NodeInfo, node_info, runtime_parity};
pub use node::{Node, NodeConfig, NodeError};
