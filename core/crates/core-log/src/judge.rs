//! Claim validity rules 2 and 3 (C1 §5.3), behind a trait.
//!
//! The log applies entry validity and the structural part of rule 1 itself (`n` above its
//! ancestry, at most `EPOCH_N_MAX_STEP` above, at most `EPOCH_N_LIMIT`). Whether the claimant was
//! an active member with the right capabilities, and whether a quorum certificate is valid, depends
//! on membership: `core-placement` implements [`ClaimJudge`] over a membership view.
//! [`StructuralJudge`] checks only what the log can see on its own.

use core_store::{LogId, StoredEntry};

use crate::header::SignedEntry;
use crate::payload::{EpochOpen, Payload, Reason};

/// What a judge sees of one claim.
#[derive(Clone, Copy, Debug)]
pub struct ClaimContext<'a> {
    /// The log.
    pub log_id: LogId,
    /// The `epoch_open` entry.
    pub open: &'a SignedEntry,
    /// Its decoded payload.
    pub payload: &'a EpochOpen,
    /// The parent (fork point) entry; `None` at genesis.
    pub parent: Option<&'a StoredEntry>,
    /// The parent's decoded payload, when stored.
    pub parent_payload: Option<&'a Payload>,
}

/// Decides claim validity rules 2 and 3 for one `epoch_open`. Must be a pure function of the
/// context and the judge's own (membership) state, so every replica with the same membership
/// reaches the same verdict.
pub trait ClaimJudge {
    /// Whether the claim is valid.
    fn claim_valid(&self, claim: &ClaimContext<'_>) -> bool;
}

/// The membership-free rules: genesis only at seq 0, a move only from a seal naming its epoch;
/// every other reason is accepted.
#[derive(Clone, Copy, Debug, Default)]
pub struct StructuralJudge;

impl ClaimJudge for StructuralJudge {
    fn claim_valid(&self, claim: &ClaimContext<'_>) -> bool {
        structural_reason_rule(claim)
    }
}

/// The reason rules the log can check without membership. A membership-aware judge calls this
/// and adds its own rules.
#[must_use]
pub fn structural_reason_rule(claim: &ClaimContext<'_>) -> bool {
    let header = claim.open.header();
    match claim.payload.reason {
        Reason::Genesis => header.seq == 0 && header.epoch.n == 1,
        _ if header.seq == 0 => false,
        Reason::Move => matches!(
            claim.parent_payload,
            Some(Payload::EpochSeal(seal)) if seal.next_epoch == Some(header.epoch)
        ),
        Reason::Failover | Reason::Takeover | Reason::TakeoverForced | Reason::Rekey => true,
    }
}
