//! The Baken node's storage SPI.
//!
//! The normative text is `docs/reference/contracts/store/store-v1.md`. A node has one [`Store`]. Every
//! replicated write goes through [`Store::write`], which hands the closure one transaction ([`Txn`])
//! spanning every replicated area at once:
//!
//! - [`LogStore`]: agent log entries, segments per agent per epoch, the placement row (the fencing
//!   mark), recorded divergences and vote promises. Payloads live in the [`BlobStore`].
//! - [`KvStore`]: namespaced bytes, ordered by key, for samod's chunk storage and similar.
//! - [`MembershipStore`]: the signed membership op DAG, stored as received.
//! - [`DocRegistry`]: the catalog documents this node will serve.
//! - [`FeedJournal`]: the conversation journal index over `feed_append` log entries.
//!
//! The closure's changes commit together when it returns `Ok` and are all discarded when it returns
//! `Err`. So an epoch claim, its first entry and the fencing mark land together, and
//! [`Store::commit_epoch_claim`] does exactly that with a value ([`EpochClaim`]) that cannot be built
//! without all three.
//!
//! Node-local, never-replicated data (model profile bindings, workspace paths, the hooks dir) lives
//! behind [`BindingsStore`], reached through [`Store::bindings`]. It is a different trait with typed
//! keys and it is not part of [`Txn`], so replicated records cannot be written into it, and bindings
//! cannot be written inside a replicated transaction.
//!
//! The traits are synchronous: implementations do blocking I/O, and async drivers call them on a
//! blocking pool. Nothing here spawns tasks or reads a clock, so the crate (traits plus
//! [`MemoryStore`]) builds for `wasm32-unknown-unknown`.

pub mod bindings;
pub mod blob;
#[cfg(feature = "contract")]
pub mod contract;
pub mod error;
pub mod feed;
pub mod ids;
pub mod kv;
pub mod log;
pub mod membership;
pub mod memory;
pub mod registry;

pub use bindings::{BindingKey, BindingsStore};
pub use blob::BlobStore;
pub use error::StoreError;
pub use feed::{FeedJournal, FeedRow};
pub use ids::{BlobHash, DivergenceId, DocId, EntryHash, LogId, OpId};
pub use kv::{KvStore, Namespace};
pub use log::{
    DivergenceRecord, EpochClaim, LogStore, PayloadState, Placement, PlacementState, Segment,
    SegmentState, StoredEntry, VotePromise,
};
pub use membership::{MembershipStore, StoredOp};
pub use memory::MemoryStore;
pub use registry::{CatalogDoc, DocRegistry, DocState};

/// The store schema version this build reads and writes. Reported in node.info as the store format.
pub const SCHEMA_VERSION: u32 = 1;

/// Everything one replicated transaction can touch.
///
/// Implemented for every type that implements all the area traits; backends implement those.
pub trait Txn: LogStore + KvStore + MembershipStore + DocRegistry + FeedJournal {}

impl<T: ?Sized + LogStore + KvStore + MembershipStore + DocRegistry + FeedJournal> Txn for T {}

/// A node's store: one per node, opened once and shared.
pub trait Store: Send + Sync {
    /// Runs `f` against a consistent snapshot. Writes are impossible through a shared reference.
    ///
    /// # Errors
    ///
    /// Whatever `f` returns, or a [`StoreError`] from the backend.
    fn read<T, E: From<StoreError>>(
        &self,
        f: impl FnOnce(&dyn Txn) -> Result<T, E>,
    ) -> Result<T, E>;

    /// Runs `f` in one transaction. Its changes commit together when it returns `Ok` and are all
    /// discarded when it returns `Err`. A commit that returns `Ok` is durable.
    ///
    /// # Errors
    ///
    /// Whatever `f` returns, or a [`StoreError`] from the backend (then nothing was committed).
    fn write<T, E: From<StoreError>>(
        &self,
        f: impl FnOnce(&mut dyn Txn) -> Result<T, E>,
    ) -> Result<T, E>;

    /// The node-local bindings. Never replicated, and never part of a [`Txn`].
    fn bindings(&self) -> &dyn BindingsStore;

    /// The schema version of the data this store holds.
    fn schema_version(&self) -> u32;

    /// Commits an epoch claim, its first log entry and the fencing mark in one transaction.
    ///
    /// # Errors
    ///
    /// [`StoreError::Fenced`] when the log already has a placement at this epoch or above; nothing
    /// is written then. Any other [`StoreError`] also leaves the store unchanged.
    fn commit_epoch_claim(&self, claim: EpochClaim) -> Result<(), StoreError> {
        self.write(|txn| txn.adopt_epoch(claim))
    }
}
