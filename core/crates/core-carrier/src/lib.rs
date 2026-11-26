//! The carrier SPI of the Baken core: how a node's protocols move frames between nodes and
//! shells, independent of any transport library.
//!
//! The normative text is node-carrier v1 (`docs/reference/contracts/node-carrier/node-carrier-v1.md`)
//! with the Rust notes beside it (`rust-carrier-notes-v1.md`). A carrier moves UTF-8 JSON frames on
//! three kinds of lane and reports the verified identity of the other end. It never authenticates,
//! authorizes, parses or rewrites a frame.
//!
//! - [`Carrier`] dials a node; the dialler gets a [`Link`] and an [`InboundSource`].
//! - A node serves [`Protocols`]: an [`AccessHook`] admits or rejects a peer before any
//!   [`ProtocolHandler`] runs, then the handler registered for the connection's ALPN gets a
//!   [`PeerLink`] with its control and call lanes.
//! - [`VerifiedPeer`] is who the transport proved the other end to be, never a value from a frame.
//! - [`LinkClosure`] carries close codes and reasons unchanged; [`core_proto::closure::Closure`]
//!   names the ones the node protocol defines.
//! - A byte-stream ALPN ([`ProtocolsBuilder::accept_stream`], [`Carrier::dial_stream`]) gets one raw
//!   [`StreamLink`] per link instead of lanes, for protocols with their own framing.
//! - [`in_process`] is the carrier for tests and the simulator, with loss, delay and partition hooks.
//! - `contract` (feature `contract-suite`) runs the node-carrier v1 scenarios against any carrier.
//!
//! This crate names no transport library; `core-carrier-iroh` is the Iroh adapter.

mod access;
mod closure;
mod error;
mod frame;
mod identity;
pub mod in_process;
mod link;
mod router;
mod stream;

#[cfg(feature = "contract-suite")]
pub mod contract;

pub use access::{AccessDecision, AccessHook, AdmitAll, MembershipAccess, MembershipQuery};
pub use closure::{AppClose, LinkClosure};
pub use error::CarrierError;
pub use frame::{Frame, Lane};
pub use identity::{NodeAddr, VerifiedPeer};
pub use link::{
    AcceptedLink, CallLane, CallSource, Carrier, DialedLink, FrameSink, FrameSource, InboundSource,
    Link, LinkControl, PeerLink,
};
pub use router::{ProtocolHandler, Protocols, ProtocolsBuilder};
pub use stream::{ByteStream, StreamHandler, StreamLink};
