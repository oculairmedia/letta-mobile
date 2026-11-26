//! The one error type every store operation returns.

use core_proto::Epoch;
use thiserror::Error;

use crate::ids::LogId;

/// Why a store operation failed. A failed operation inside [`crate::Store::write`] aborts the whole
/// transaction when the closure propagates it.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum StoreError {
    /// A placement or append was refused by the fencing rules: the log already has a placement at
    /// or above the epoch in question, or has seen a higher epoch number.
    #[error("fenced on {log_id:?}: canonical {canonical:?}, highest n seen {max_n_seen:?}")]
    Fenced {
        /// The log.
        log_id: LogId,
        /// The placement's epoch, if the log has one.
        canonical: Option<Epoch>,
        /// The highest epoch number stored for the log, if any.
        max_n_seen: Option<u64>,
    },
    /// The write contradicts what is stored under the same key (for example a different header
    /// under one entry hash).
    #[error("conflict: {0}")]
    Conflict(String),
    /// The input breaks a structural rule (a header of the wrong length, a gap in a segment, a
    /// prune of a payload that is not compactable).
    #[error("invalid: {0}")]
    Invalid(String),
    /// A row the operation needs is not stored.
    #[error("not found: {0}")]
    NotFound(String),
    /// The database was written by a newer build. It is never opened, so it is never downgraded.
    #[error("store schema {found} is newer than this build supports ({supported})")]
    SchemaTooNew {
        /// The version on disk.
        found: u32,
        /// The newest version this build knows.
        supported: u32,
    },
    /// Stored data cannot be decoded.
    #[error("corrupt store: {0}")]
    Corrupt(String),
    /// The backend failed (I/O, locking, the database engine).
    #[error("backend: {0}")]
    Backend(String),
}
