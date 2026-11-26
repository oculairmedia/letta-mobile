//! Bounded push for joiners: a node that is not yet a member on some peer proves its membership by
//! pushing the op that admitted it plus that op's causal past.
//!
//! The joiner builds the bundle with [`proof_bundle`]. The receiver opens a [`PushBudget`] for the
//! connection, which refuses tombstoned diallers and charges every envelope against the per-connection
//! limits before decoding it.

use core_proto::NodeId;
use thiserror::Error;

use crate::log::{InsertError, Inserted, MembershipLog};
use crate::op::{OpError, OpPayload, SignedOp};
use crate::view::{Admission, MembershipView};

/// The per-connection bounds on what a non-member may push.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PushLimits {
    /// Most ops per connection.
    pub max_ops: usize,
    /// Most envelope bytes per connection.
    pub max_bytes: usize,
}

impl Default for PushLimits {
    /// 64 ops and 64 KiB.
    fn default() -> Self {
        Self {
            max_ops: 64,
            max_bytes: 64 * 1024,
        }
    }
}

/// Why a proof bundle cannot be built.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum ProofError {
    /// The node is not an active member in the log's evaluation.
    #[error("not an active member")]
    NotMember,
    /// The proof needs more ops or bytes than the limits allow.
    #[error("proof exceeds the push limits")]
    TooLarge,
}

/// The envelopes that prove `joiner`'s membership, parents before children: the valid op that
/// made it active (the smallest such proof when there are several) and that op's causal past.
///
/// # Errors
///
/// [`ProofError::NotMember`] or [`ProofError::TooLarge`].
pub fn proof_bundle(
    log: &MembershipLog,
    joiner: &NodeId,
    limits: PushLimits,
) -> Result<Vec<Vec<u8>>, ProofError> {
    let evaluation = log.evaluate();
    if !evaluation.view.is_member(joiner) {
        return Err(ProofError::NotMember);
    }
    let admitting = evaluation
        .valid
        .iter()
        .filter_map(|id| log.get(id))
        .filter(|signed| admits(signed, joiner))
        .map(|signed| {
            let mut proof = log.causal_past(&signed.id());
            proof.insert(signed.id());
            proof
        })
        .min_by_key(std::collections::BTreeSet::len)
        .ok_or(ProofError::NotMember)?;
    let envelopes: Vec<Vec<u8>> = evaluation
        .linear_order
        .iter()
        .filter(|id| admitting.contains(id))
        .filter_map(|id| log.get(id).map(SignedOp::envelope))
        .collect();
    let bytes: usize = envelopes.iter().map(Vec::len).sum();
    if envelopes.len() > limits.max_ops || bytes > limits.max_bytes {
        return Err(ProofError::TooLarge);
    }
    Ok(envelopes)
}

fn admits(signed: &SignedOp, joiner: &NodeId) -> bool {
    let op = signed.op();
    match &op.payload {
        OpPayload::Genesis { .. } => op.header.issuer == *joiner,
        OpPayload::Add { subject, .. } => subject == joiner,
        OpPayload::Rotate { new_key, .. } => new_key == joiner,
        _ => false,
    }
}

/// Why a pushed envelope was refused.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum PushError {
    /// The pusher is tombstoned.
    #[error("pusher is revoked")]
    Refused,
    /// The connection used up its op or byte budget.
    #[error("push budget exhausted")]
    Exhausted,
    /// The envelope is not a well-formed signed op.
    #[error("bad op: {0}")]
    Op(#[from] OpError),
    /// The log refused the op.
    #[error("op not accepted: {0}")]
    Insert(#[from] InsertError),
}

/// The receiver's budget for one push-only connection from a non-member.
#[derive(Clone, Debug)]
pub struct PushBudget {
    pusher: NodeId,
    ops_left: usize,
    bytes_left: usize,
}

impl PushBudget {
    /// Opens a budget for `pusher`, checked against the receiver's current `view`.
    ///
    /// # Errors
    ///
    /// [`PushError::Refused`] when the pusher is tombstoned.
    pub fn open(
        view: &MembershipView,
        pusher: NodeId,
        limits: PushLimits,
    ) -> Result<Self, PushError> {
        if view.admission(&pusher) == Admission::Refused {
            return Err(PushError::Refused);
        }
        Ok(Self {
            pusher,
            ops_left: limits.max_ops,
            bytes_left: limits.max_bytes,
        })
    }

    /// The pushing node.
    #[must_use]
    pub const fn pusher(&self) -> NodeId {
        self.pusher
    }

    /// Charges `envelope` against the budget, then decodes, verifies and inserts it.
    ///
    /// # Errors
    ///
    /// [`PushError::Exhausted`] before any decoding when the budget is used up, otherwise the
    /// decode or insert error.
    pub fn accept(
        &mut self,
        log: &mut MembershipLog,
        envelope: &[u8],
    ) -> Result<Inserted, PushError> {
        if self.ops_left == 0 || envelope.len() > self.bytes_left {
            return Err(PushError::Exhausted);
        }
        self.ops_left -= 1;
        self.bytes_left -= envelope.len();
        let signed = SignedOp::decode_envelope(envelope)?;
        Ok(log.insert(signed)?)
    }

    /// Whether the pusher is now an active member of `log`, so its other protocols may admit it.
    #[must_use]
    pub fn admitted(&self, log: &MembershipLog) -> bool {
        log.evaluate().view.is_member(&self.pusher)
    }
}
