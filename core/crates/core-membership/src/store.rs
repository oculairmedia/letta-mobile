//! Persistence for the membership DAG through the node store ([`core_store::Store`]).
//!
//! The store keeps signed envelopes by op id, verbatim, in its membership area
//! ([`core_store::MembershipStore`]); it does not interpret them. [`PersistentLog`] writes every op
//! that joins the DAG and re-verifies everything it loads.

use core_store::{Store, StoreError, StoredOp};
use thiserror::Error;

use crate::crypto::OpId;
use crate::log::{DEFAULT_PENDING_LIMIT, InsertError, Inserted, MembershipLog};
use crate::op::{OpError, SignedOp};

impl From<OpId> for core_store::OpId {
    fn from(id: OpId) -> Self {
        Self::from_bytes(*id.as_bytes())
    }
}

impl From<core_store::OpId> for OpId {
    fn from(id: core_store::OpId) -> Self {
        Self::from_bytes(*id.as_bytes())
    }
}

/// Why a persistent log could not open or insert.
#[derive(Debug, Error)]
pub enum PersistError {
    /// The store failed.
    #[error("store: {0}")]
    Store(#[from] StoreError),
    /// A stored envelope no longer decodes or verifies.
    #[error("stored op is invalid: {0}")]
    Corrupt(OpError),
    /// A stored envelope's op id is not the id it is stored under.
    #[error("stored op {stored} has id {actual}")]
    IdMismatch {
        /// The id the row is keyed by.
        stored: OpId,
        /// The id of the envelope's body.
        actual: OpId,
    },
    /// The log refused an op.
    #[error("insert: {0}")]
    Insert(#[from] InsertError),
}

/// A [`MembershipLog`] that writes every op that joins the DAG to a node [`Store`]. Ops that wait
/// for parents are not stored; a peer sends them again.
#[derive(Debug)]
pub struct PersistentLog<S> {
    log: MembershipLog,
    store: S,
}

impl<S: Store> PersistentLog<S> {
    /// Opens the log of `constellation` from `store`, re-verifying every stored op.
    ///
    /// The store returns rows in op-id order, which is not parent order, so a stored op can load
    /// before its parents. Loading therefore lifts the pending bound: every stored op was complete
    /// when it was written, so the load always completes. The bound applies again afterwards.
    ///
    /// # Errors
    ///
    /// [`PersistError`] when the store fails, a stored op is invalid, or one belongs elsewhere.
    pub fn open(constellation: OpId, store: S) -> Result<Self, PersistError> {
        let mut log = MembershipLog::new(constellation).with_pending_limit(usize::MAX);
        let rows = store.read(|txn| txn.ops())?;
        for row in rows {
            let signed = SignedOp::decode_envelope(&row.envelope).map_err(PersistError::Corrupt)?;
            let stored = OpId::from(row.op_id);
            if signed.id() != stored {
                return Err(PersistError::IdMismatch {
                    stored,
                    actual: signed.id(),
                });
            }
            log.insert(signed)?;
        }
        let log = log.with_pending_limit(DEFAULT_PENDING_LIMIT);
        Ok(Self { log, store })
    }

    /// Inserts `op`, then stores it and every pending op it completed in one transaction.
    /// `received_at_ms` is the caller's clock reading, kept as information only.
    ///
    /// # Errors
    ///
    /// [`PersistError`] when the log refuses the op or the store fails. When the store fails the
    /// in-memory log is put back as it was (the ops the insert completed wait again), so inserting
    /// the same op again retries the write.
    pub fn insert(&mut self, op: SignedOp, received_at_ms: u64) -> Result<Inserted, PersistError> {
        let inserted = self.log.insert(op)?;
        if let Inserted::Stored(ids) = &inserted {
            let rows: Vec<StoredOp> = ids
                .iter()
                .filter_map(|id| self.log.get(id))
                .map(|signed| stored_op(signed, received_at_ms))
                .collect();
            let written = self.store.write(|txn| {
                for row in &rows {
                    txn.put_op(row)?;
                }
                Ok::<_, StoreError>(())
            });
            if let Err(error) = written {
                self.log.undo_stored(ids);
                return Err(error.into());
            }
        }
        Ok(inserted)
    }

    /// The in-memory log.
    #[must_use]
    pub const fn log(&self) -> &MembershipLog {
        &self.log
    }

    /// The store.
    #[must_use]
    pub const fn store(&self) -> &S {
        &self.store
    }

    /// Gives the store back, dropping the in-memory log.
    #[must_use]
    pub fn into_store(self) -> S {
        self.store
    }
}

fn stored_op(signed: &SignedOp, received_at_ms: u64) -> StoredOp {
    let op = signed.op();
    StoredOp {
        op_id: signed.id().into(),
        envelope: signed.envelope(),
        issuer: op.header.issuer,
        // Kind codes are 0 to 6.
        kind: u8::try_from(op.kind().code()).unwrap_or(u8::MAX),
        parents: op.header.parents.iter().map(|id| (*id).into()).collect(),
        received_at_ms,
    }
}
