//! A deterministic N-node simulator of the Baken core, and the checker of C1's invariants.
//!
//! Each simulated node is the real core: a [`core_store::MemoryStore`], a
//! [`core_log::AgentLog`] per agent, a [`core_membership::MembershipLog`], core-placement's judge,
//! move pipeline and failover evaluator. Nodes reach each other only through core-carrier's
//! [`InProcessNetwork`](core_carrier::in_process::InProcessNetwork): `baken/log/1` sessions run
//! core-log's driver between every pair of members over a call lane ([`stream`] binds the two),
//! and placement messages and membership ops cross the same network. So partitions, severed links,
//! crashes, loss and delay hit every protocol the way a real network would.
//!
//! Determinism: [`run`] drives everything on a tokio current-thread runtime with the clock paused
//! (time moves only when every task waits on a timer), every task is local, every select is
//! biased, and the network's loss draws come from a seed. The same seed and steps give the same
//! run.
//!
//! - [`Sim`] and [`Step`]: the scenario DSL (membership ops, agents, turns, tool calls, moves,
//!   manual claims, partition and heal, sever, crash and restart, crashes at move points, loss,
//!   delay, backups and restores).
//! - [`check`]: the invariant groups E, M, L, P and D, after every step and at quiescence.
//! - [`stream`]: a byte stream over a carrier call lane, for core-log's session driver.
//! - [`judge`]: a claim judge over a node's changing membership view.

mod actor;
pub mod check;
pub mod judge;
pub mod model;
mod sim;
pub mod stream;
mod world;

pub use actor::{MoveRefusal, Refusal};
pub use check::Violation;
pub use model::{NodeSpec, PARITY, Policy};
pub use sim::{Sim, Step, run};
pub use world::MovePoint;

/// A log error as the store error a store transaction closure returns.
pub(crate) fn to_store(error: core_log::LogError) -> core_store::StoreError {
    match error {
        core_log::LogError::Store(error) => error,
        other => core_store::StoreError::Invalid(other.to_string()),
    }
}
