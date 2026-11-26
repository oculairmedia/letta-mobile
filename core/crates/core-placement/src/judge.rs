//! The membership-aware [`ClaimJudge`]: claim validity rules 2 and 3 (C1 §5.3) over a
//! [`MembershipView`].
//!
//! The judge is a pure function of the claim and the view, so every replica holding the same
//! membership ops reaches the same verdict. It never reads node class: class is self-reported in
//! node.info, differs between replicas, and only decides a node's own candidacy (the failover
//! evaluator), never validity.

use core_log::hashes::log_id;
use core_log::judge::structural_reason_rule;
use core_log::{ClaimContext, ClaimJudge, Pos, Reason};
use core_membership::policy::FailoverMode;
use core_membership::{Capability, MemberStatus, MembershipView};
use core_proto::NodeId;
use core_proto::{AgentId, Epoch};
use core_store::LogId;

use crate::failover::effective_mode;
use crate::vote::check_certificate;

/// Why a claim is not valid. Each variant has a stable code ([`ClaimVerdict::code`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClaimVerdict {
    /// The claim is valid.
    Valid,
    /// The structural reason rule failed (genesis off seq 0, a move without a matching seal).
    Structure,
    /// The claimant is not an active member.
    NotActive,
    /// The claimant lacks `node.execute`.
    NoExecute,
    /// A failover or takeover by a node without `node.takeover`.
    NoTakeover,
    /// A forced takeover by a node without `admin.full`.
    NoAdmin,
    /// The policy has a quorum and the certificate is missing or invalid.
    BadCertificate,
    /// An automatic failover while the agent's policy mode is manual.
    ManualMode,
    /// An automatic failover by a node outside the policy's explicit candidate list.
    NotCandidate,
    /// A rekey whose parent segment's claimant did not rotate to this claimant.
    NotRotated,
}

impl ClaimVerdict {
    /// The stable code fixtures name.
    #[must_use]
    pub const fn code(self) -> &'static str {
        match self {
            Self::Valid => "valid",
            Self::Structure => "structure",
            Self::NotActive => "not_active",
            Self::NoExecute => "no_execute",
            Self::NoTakeover => "no_takeover",
            Self::NoAdmin => "no_admin",
            Self::BadCertificate => "bad_certificate",
            Self::ManualMode => "manual_mode",
            Self::NotCandidate => "not_candidate",
            Self::NotRotated => "not_rotated",
        }
    }
}

/// The claim judge over one membership view.
///
/// Built for one agent, so the policy's per-agent overrides apply to that agent's log; a judge
/// built without an agent applies the constellation-wide policy to every log.
#[derive(Clone, Copy, Debug)]
pub struct MembershipJudge<'a> {
    view: &'a MembershipView,
    agent: Option<&'a AgentId>,
}

impl<'a> MembershipJudge<'a> {
    /// A judge over `view` for the log of `agent` (per-agent overrides apply), or for any log
    /// with the constellation-wide policy when `agent` is `None`.
    #[must_use]
    pub const fn new(view: &'a MembershipView, agent: Option<&'a AgentId>) -> Self {
        Self { view, agent }
    }

    /// The verdict for one claim, with its reason.
    #[must_use]
    pub fn verdict(&self, claim: &ClaimContext<'_>) -> ClaimVerdict {
        if !structural_reason_rule(claim) {
            return ClaimVerdict::Structure;
        }
        let header = claim.open.header();
        let epoch = header.epoch;
        let claimant = epoch.claimant;
        if self.view.status(&claimant) != Some(MemberStatus::Active) {
            return ClaimVerdict::NotActive;
        }
        if !self.has(&claimant, Capability::NODE_EXECUTE) {
            return ClaimVerdict::NoExecute;
        }
        let parent = claim.parent.map(|p| Pos {
            seq: p.seq,
            hash: p.entry_hash,
        });
        match claim.payload.reason {
            Reason::Genesis | Reason::Move => ClaimVerdict::Valid,
            // The parent's segment claimant rotated to this claimant. Whether the parent is that
            // segment's head needs the store; the log's fork-point rules already confine a rekey
            // to one segment boundary, and a rekey off a non-head forks like any other claim.
            Reason::Rekey => match claim.parent.map(|p| p.epoch.claimant) {
                Some(old)
                    if self.view.status(&old) == Some(MemberStatus::Rotated { to: claimant }) =>
                {
                    ClaimVerdict::Valid
                }
                _ => ClaimVerdict::NotRotated,
            },
            Reason::TakeoverForced => {
                if !self.has(&claimant, Capability::NODE_TAKEOVER) {
                    ClaimVerdict::NoTakeover
                } else if !self.has(&claimant, Capability::ADMIN_FULL) {
                    ClaimVerdict::NoAdmin
                } else {
                    ClaimVerdict::Valid
                }
            }
            reason @ (Reason::Failover | Reason::Takeover) => {
                if !self.has(&claimant, Capability::NODE_TAKEOVER) {
                    return ClaimVerdict::NoTakeover;
                }
                if reason == Reason::Failover
                    && let Some(verdict) = self.failover_policy(&claim.log_id, &claimant)
                {
                    return verdict;
                }
                self.certificate(claim, &epoch, parent)
            }
        }
    }

    fn has(&self, node: &NodeId, capability: &str) -> bool {
        self.view.has_capability(node, capability)
    }

    fn agent_for(&self, log: &LogId) -> Option<&AgentId> {
        self.agent.filter(|agent| log_id(agent) == *log)
    }

    fn failover_policy(&self, log: &LogId, claimant: &NodeId) -> Option<ClaimVerdict> {
        let policy = self.view.policy();
        let agent = self.agent_for(log);
        if effective_mode(policy, agent) == FailoverMode::Manual {
            return Some(ClaimVerdict::ManualMode);
        }
        let candidates = agent
            .and_then(|a| policy.per_agent.get(a))
            .and_then(|o| o.candidates.as_ref())
            .unwrap_or(&policy.candidates);
        if !candidates.is_empty() && !candidates.contains(claimant) {
            return Some(ClaimVerdict::NotCandidate);
        }
        None
    }

    fn certificate(
        &self,
        claim: &ClaimContext<'_>,
        epoch: &Epoch,
        parent: Option<Pos>,
    ) -> ClaimVerdict {
        let Some(quorum) = &self.view.policy().quorum else {
            return ClaimVerdict::Valid;
        };
        let Some(parent) = parent else {
            return ClaimVerdict::BadCertificate;
        };
        match check_certificate(&claim.payload.votes, &claim.log_id, epoch, &parent, quorum) {
            Ok(()) => ClaimVerdict::Valid,
            Err(_) => ClaimVerdict::BadCertificate,
        }
    }
}

impl ClaimJudge for MembershipJudge<'_> {
    fn claim_valid(&self, claim: &ClaimContext<'_>) -> bool {
        self.verdict(claim) == ClaimVerdict::Valid
    }
}
