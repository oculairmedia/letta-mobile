//! The failover policy evaluator (C1 §5.7, §5.8): who may claim automatically, when, and the
//! holder-side self-fence and startup rules. Pure: every clock reading is an argument.
//!
//! Defaults (open questions Q4, Q5, Q12): no quorum; a 5 minute window and a 30 second stagger per
//! candidate rank; phones are manual-only unless `allow_phone_auto` is set; `resume_alone` stays
//! true without a quorum.

use std::collections::BTreeMap;

use core_membership::policy::{FailoverMode, FailoverPolicy};
use core_membership::{Capability, MemberStatus, MembershipView};
use core_proto::{AgentId, NodeId};
use core_store::PlacementState;

use crate::backoff::{Exponential, RetrySchedule};
use crate::record::PlacementRecord;

/// A dial failure must be at most this old for the holder to count as unreachable.
pub const DIAL_FAILURE_WINDOW_MS: u64 = 30_000;

/// Retries of a quorum claim before the candidate waits for the next window.
pub const MAX_CLAIM_ATTEMPTS: u32 = 8;

/// A node's self-reported class (node.info). It only decides the node's own candidacy.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum NodeClass {
    /// An always-on node (a server).
    AlwaysOn,
    /// A desktop.
    Desktop,
    /// A phone.
    Phone,
}

impl NodeClass {
    /// Parses the node.info name.
    #[must_use]
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "always_on" => Some(Self::AlwaysOn),
            "desktop" => Some(Self::Desktop),
            "phone" => Some(Self::Phone),
            _ => None,
        }
    }

    /// The node.info name.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::AlwaysOn => "always_on",
            Self::Desktop => "desktop",
            Self::Phone => "phone",
        }
    }
}

/// The failover mode for `agent`: its override, else the constellation's.
#[must_use]
pub fn effective_mode(policy: &FailoverPolicy, agent: Option<&AgentId>) -> FailoverMode {
    agent
        .and_then(|a| policy.per_agent.get(a))
        .and_then(|o| o.mode)
        .unwrap_or(policy.mode)
}

/// What the evaluator knows about the other nodes.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PeerFacts {
    /// Self-reported classes from node.info.
    pub classes: BTreeMap<NodeId, NodeClass>,
    /// Each node's complete seq on the canonical line (from acks and `have`).
    pub complete_seq: BTreeMap<NodeId, u64>,
}

/// The automatic candidates for `agent`, best first (C1 §5.7 key 3, §5.8 ranking).
///
/// Eligible nodes are active members holding `node.execute`, `node.takeover` and `store`. With an
/// explicit list, its order is the policy order and nodes of unknown class stay eligible. Without
/// one, only `always_on` and `desktop` nodes are, always-on first. Phones are left out unless
/// `allow_phone_auto` is set. Ties go to the highest complete seq, then the lowest node id.
#[must_use]
pub fn candidates(view: &MembershipView, agent: &AgentId, facts: &PeerFacts) -> Vec<NodeId> {
    let policy = view.policy();
    let explicit = policy
        .per_agent
        .get(agent)
        .and_then(|o| o.candidates.as_ref())
        .unwrap_or(&policy.candidates);
    let eligible = |node: &NodeId| {
        view.status(node) == Some(MemberStatus::Active)
            && [
                Capability::NODE_EXECUTE,
                Capability::NODE_TAKEOVER,
                Capability::STORE,
            ]
            .iter()
            .all(|cap| view.has_capability(node, cap))
            && (policy.allow_phone_auto || facts.classes.get(node) != Some(&NodeClass::Phone))
    };
    let mut ranked: Vec<(u64, std::cmp::Reverse<u64>, NodeId)> = if explicit.is_empty() {
        view.members()
            .filter(|node| eligible(node))
            .filter_map(|node| {
                let class = facts.classes.get(node)?;
                let order = match class {
                    NodeClass::AlwaysOn => 0,
                    NodeClass::Desktop => 1,
                    NodeClass::Phone => 2,
                };
                Some((order, *node))
            })
            .map(|(order, node)| (order, complete(facts, &node), node))
            .collect()
    } else {
        explicit
            .iter()
            .zip(0_u64..)
            .filter(|(node, _)| eligible(node))
            .map(|(node, order)| (order, complete(facts, node), *node))
            .collect()
    };
    ranked.sort();
    ranked.into_iter().map(|(_, _, node)| node).collect()
}

fn complete(facts: &PeerFacts, node: &NodeId) -> std::cmp::Reverse<u64> {
    std::cmp::Reverse(facts.complete_seq.get(node).copied().unwrap_or(0))
}

/// What this node has observed of the node it would claim over.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Liveness {
    /// The last frame received from it on any link, or when this node started watching it.
    pub last_heard_ms: u64,
    /// The latest failed dial to it.
    pub last_dial_failure_ms: Option<u64>,
    /// The latest time any reachable peer reports hearing it (`ping.heard`).
    pub peer_heard_ms: Option<u64>,
}

/// The evaluator's inputs for one agent.
#[derive(Clone, Copy, Debug)]
pub struct FailoverInput<'a> {
    /// The membership view.
    pub view: &'a MembershipView,
    /// The placement record.
    pub placement: &'a PlacementRecord,
    /// This node.
    pub me: NodeId,
    /// What this node knows of the others (classes, progress).
    pub facts: &'a PeerFacts,
    /// Observations of the holder (or, for a seal, of its target).
    pub liveness: Liveness,
    /// Whether this node's runtime parity matches the agent's latest checkpoint.
    pub runtime_parity_matches: bool,
    /// This node's clock.
    pub now_ms: u64,
}

/// Why automatic failover does not apply here.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Idle {
    /// This node holds the agent.
    SelfHolder,
    /// The agent is released; only a manual claim places it again.
    Placeless,
    /// A higher invalid epoch exists; only a manual claim places it again.
    Superseded,
    /// The policy mode for the agent is manual.
    ManualMode,
    /// No eligible automatic candidate exists (for example only phones): manual only.
    NoCandidate,
    /// This node is a phone and phones are manual-only.
    PhoneManualOnly,
    /// This node is not an eligible candidate.
    NotCandidate,
    /// This node's runtime parity differs and the policy forbids a mismatched failover.
    RuntimeMismatch,
}

impl Idle {
    /// The stable code fixtures name.
    #[must_use]
    pub const fn code(self) -> &'static str {
        match self {
            Self::SelfHolder => "self_holder",
            Self::Placeless => "placeless",
            Self::Superseded => "superseded",
            Self::ManualMode => "manual_mode",
            Self::NoCandidate => "no_candidate",
            Self::PhoneManualOnly => "phone_manual_only",
            Self::NotCandidate => "not_candidate",
            Self::RuntimeMismatch => "runtime_mismatch",
        }
    }
}

/// The evaluator's verdict.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FailoverDecision {
    /// No automatic claim by this node.
    Idle(Idle),
    /// The holder is not (yet) unreachable for this node's rank: evaluate again at `until_ms`.
    Wait {
        /// When to look again.
        until_ms: u64,
        /// This node's rank.
        rank: u64,
    },
    /// The window has passed but no dial failed recently, or a peer heard the holder: dial it,
    /// then evaluate again.
    Probe {
        /// This node's rank.
        rank: u64,
    },
    /// Claim now (re-check that no higher `epoch_open` arrived first).
    Claim {
        /// This node's rank.
        rank: u64,
    },
}

/// Evaluates automatic failover for one agent from this node's point of view.
#[must_use]
pub fn evaluate(input: &FailoverInput<'_>) -> FailoverDecision {
    use FailoverDecision::Idle as I;
    let record = input.placement;
    let watched = match record.state {
        PlacementState::Placeless => return I(Idle::Placeless),
        PlacementState::SupersededUnknown => return I(Idle::Superseded),
        _ if record.max_n_seen > record.epoch.n => return I(Idle::Superseded),
        PlacementState::Active => record.epoch.claimant,
        PlacementState::SealedTo(next) => next.claimant,
    };
    if watched == input.me {
        return I(Idle::SelfHolder);
    }
    let policy = input.view.policy();
    if effective_mode(policy, Some(&record.agent_id)) == FailoverMode::Manual {
        return I(Idle::ManualMode);
    }
    let ranked = candidates(input.view, &record.agent_id, input.facts);
    let ranked: Vec<NodeId> = ranked.into_iter().filter(|n| *n != watched).collect();
    if ranked.is_empty() {
        return I(Idle::NoCandidate);
    }
    let Some(position) = ranked.iter().position(|n| *n == input.me) else {
        let phone = input.facts.classes.get(&input.me) == Some(&NodeClass::Phone);
        return I(if phone && !policy.allow_phone_auto {
            Idle::PhoneManualOnly
        } else {
            Idle::NotCandidate
        });
    };
    if !input.runtime_parity_matches && !policy.runtime_mismatch_failover {
        return I(Idle::RuntimeMismatch);
    }
    let rank = position as u64;
    let live = input.liveness;
    let mut heard = live.last_heard_ms;
    if policy.indirect_probe {
        heard = heard.max(live.peer_heard_ms.unwrap_or(0));
    }
    let due = heard
        .saturating_add(policy.after_ms)
        .saturating_add(rank.saturating_mul(policy.stagger_ms));
    if input.now_ms < due {
        return FailoverDecision::Wait {
            until_ms: due,
            rank,
        };
    }
    let dial_failed = live
        .last_dial_failure_ms
        .is_some_and(|t| t <= input.now_ms && input.now_ms - t <= DIAL_FAILURE_WINDOW_MS);
    if dial_failed {
        FailoverDecision::Claim { rank }
    } else {
        FailoverDecision::Probe { rank }
    }
}

/// When a candidate whose quorum claim failed `attempt + 1` times may try again:
/// `stagger_ms × 2^attempt` after `now_ms`, or `None` after [`MAX_CLAIM_ATTEMPTS`].
#[must_use]
pub fn claim_retry_at(policy: &FailoverPolicy, attempt: u32, now_ms: u64) -> Option<u64> {
    let schedule = Exponential {
        base_ms: policy.stagger_ms,
        max_attempts: MAX_CLAIM_ATTEMPTS,
        max_delay_ms: u64::MAX / 2,
    };
    schedule
        .delay_ms(attempt)
        .map(|delay| now_ms.saturating_add(delay))
}

/// The latest time at which this node had heard from a majority (`required`) of the voters,
/// counting itself as heard now when it is a voter. `None` without a quorum or when a majority was
/// never heard.
#[must_use]
pub fn majority_heard_at(
    policy: &FailoverPolicy,
    me: &NodeId,
    heard: &BTreeMap<NodeId, u64>,
    now_ms: u64,
) -> Option<u64> {
    let quorum = policy.quorum.as_ref()?;
    let mut times: Vec<u64> = quorum
        .voters
        .iter()
        .filter_map(|v| {
            if v == me {
                Some(now_ms)
            } else {
                heard.get(v).copied()
            }
        })
        .collect();
    times.sort_unstable_by(|a, b| b.cmp(a));
    let index = usize::try_from(quorum.required).ok()?.checked_sub(1)?;
    times.get(index).copied()
}

/// Self-fence (C1 §5.8 step 5): with a quorum, a holder that has not heard a majority of voters
/// for `self_fence_ms` stops accepting new turns. Always `false` without a quorum.
#[must_use]
pub fn self_fenced(
    policy: &FailoverPolicy,
    me: &NodeId,
    heard: &BTreeMap<NodeId, u64>,
    now_ms: u64,
) -> bool {
    if policy.quorum.is_none() {
        return false;
    }
    majority_heard_at(policy, me, heard, now_ms)
        .is_none_or(|t| now_ms.saturating_sub(t) >= policy.self_fence_ms)
}

/// What the startup probe (C1 §5.6) found.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StartupProbe {
    /// A store peer answered and nothing higher was learned.
    PeerAnswered,
    /// A majority of voters answered (quorum policies).
    MajorityAnswered,
    /// No peer answered within the probe window.
    NoAnswer,
}

/// Whether a node whose stored canonical segment is its own and active may resume after its
/// startup probe. A peer revealing a higher epoch fences the node through the log instead.
#[must_use]
pub const fn may_resume(policy: &FailoverPolicy, probe: StartupProbe) -> bool {
    match (policy.quorum.is_some(), probe) {
        (true, StartupProbe::MajorityAnswered)
        | (false, StartupProbe::PeerAnswered | StartupProbe::MajorityAnswered) => true,
        (true, _) => false,
        (false, StartupProbe::NoAnswer) => policy.resume_alone,
    }
}
