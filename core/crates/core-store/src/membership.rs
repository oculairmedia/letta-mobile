//! The membership op DAG as received: signed envelopes, stored verbatim and never re-encoded.

use core_proto::NodeId;

use crate::error::StoreError;
use crate::ids::OpId;

/// One signed membership op.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoredOp {
    /// `derive_key("membership op", body)`.
    pub op_id: OpId,
    /// The `[body, sig]` envelope bytes, verbatim.
    pub envelope: Vec<u8>,
    /// The issuer's node id.
    pub issuer: NodeId,
    /// The op kind (genesis, add, revoke, ...), as its wire number.
    pub kind: u8,
    /// The parent op ids, sorted ascending without duplicates.
    pub parents: Vec<OpId>,
    /// When this node received it (informational).
    pub received_at_ms: u64,
}

/// The membership area of a transaction. A membership op and the evaluation results the caller
/// stores elsewhere in the same transaction commit together.
pub trait MembershipStore {
    /// The op with this id.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn op(&self, op_id: &OpId) -> Result<Option<StoredOp>, StoreError>;

    /// Every stored op, ordered by op id. Evaluation does not depend on the order.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn ops(&self) -> Result<Vec<StoredOp>, StoreError>;

    /// The DAG heads: stored ops that no stored op names as a parent, ordered by op id.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn op_heads(&self) -> Result<Vec<OpId>, StoreError>;

    /// Stores an op. Returns `true` when it was new; the same op again is a no-op.
    ///
    /// # Errors
    ///
    /// [`StoreError::Invalid`] when the parents are not sorted and unique;
    /// [`StoreError::Conflict`] when a different envelope is stored under the same id.
    fn put_op(&mut self, op: &StoredOp) -> Result<bool, StoreError>;
}

/// Checks the parent list rule shared by every backend.
///
/// # Errors
///
/// [`StoreError::Invalid`] when the parents are not strictly ascending.
pub fn check_parents(op: &StoredOp) -> Result<(), StoreError> {
    if op.parents.windows(2).all(|pair| pair[0] < pair[1]) {
        Ok(())
    } else {
        Err(StoreError::Invalid(
            "op parents must be sorted ascending without duplicates".into(),
        ))
    }
}
