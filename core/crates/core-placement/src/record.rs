//! The placement record (C1 §5.1), derived from the log, and the input gate (§5.6, F4).
//!
//! Nothing here is stored separately: the record is a view over `core_log::AgentLog::analyze`
//! and the store's `max_n_seen`, so it survives restarts with the log.

use core_log::{AgentLog, Analysis, ClaimJudge, LogError, Pos};
use core_proto::{AgentId, Epoch, NodeId};
use core_store::{LogId, LogStore, PlacementState};

/// `Placement {agent_id, holder, epoch, state}` plus what the gate and the feed need.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlacementRecord {
    /// The agent log.
    pub log_id: LogId,
    /// The agent, from the genesis payload.
    pub agent_id: AgentId,
    /// The canonical segment's epoch; its claimant is the holder of record.
    pub epoch: Epoch,
    /// The derived state.
    pub state: PlacementState,
    /// The highest epoch number in any stored segment of the log, any classification.
    pub max_n_seen: u64,
    /// The canonical tip.
    pub tip: Pos,
}

impl PlacementRecord {
    /// Derives the record from a log analysis, or `None` when no segment is valid.
    #[must_use]
    pub fn from_analysis(log_id: LogId, analysis: &Analysis, max_n_seen: u64) -> Option<Self> {
        let tip = analysis.line.last()?;
        Some(Self {
            log_id,
            agent_id: analysis.agent_id.clone()?,
            epoch: analysis.canonical?,
            state: analysis.placement_state?,
            max_n_seen: max_n_seen.max(analysis.canonical?.n),
            tip: Pos {
                seq: tip.seq,
                hash: tip.entry_hash,
            },
        })
    }

    /// Derives the record of `log` from `store`.
    ///
    /// # Errors
    ///
    /// A [`LogError`] from the analysis or the store.
    pub fn derive<J: ClaimJudge + ?Sized, S: LogStore + ?Sized>(
        log: &AgentLog<'_, J>,
        store: &S,
    ) -> Result<Option<Self>, LogError> {
        let analysis = log.analyze(store)?;
        let max_n = store.max_n_seen(&log.log_id())?.unwrap_or(0);
        Ok(Self::from_analysis(log.log_id(), &analysis, max_n))
    }

    /// The node that holds the agent or is receiving it: the claimant while active, the target
    /// while sealed to one, nobody when placeless or superseded.
    #[must_use]
    pub const fn holder(&self) -> Option<NodeId> {
        match self.state {
            PlacementState::Active => Some(self.epoch.claimant),
            PlacementState::SealedTo(next) => Some(next.claimant),
            PlacementState::Placeless | PlacementState::SupersededUnknown => None,
        }
    }

    /// F4: whether `me` may append to the canonical segment now.
    #[must_use]
    pub fn may_append(&self, me: &NodeId) -> bool {
        self.state == PlacementState::Active
            && self.epoch.claimant == *me
            && self.max_n_seen == self.epoch.n
    }

    /// The gate state for `me`, given whether `me` has a move in its quiesce phase.
    #[must_use]
    pub fn gate(&self, me: &NodeId, moving: bool) -> Gate {
        if self.may_append(me) {
            return if moving { Gate::Moving } else { Gate::Active };
        }
        match self.state {
            PlacementState::Placeless => Gate::Placeless,
            PlacementState::SupersededUnknown => Gate::SupersededUnknown,
            _ if self.max_n_seen > self.epoch.n => Gate::SupersededUnknown,
            PlacementState::Active | PlacementState::SealedTo(_) => Gate::NotHolder,
        }
    }
}

/// The input gate of one node for one agent (C1 §5.6).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Gate {
    /// Accept input.
    Active,
    /// This node holds the agent and is moving it: refuse with itself as holder and a retry hint.
    Moving,
    /// Another node holds the agent: refuse and name it.
    NotHolder,
    /// Nobody holds the agent.
    Placeless,
    /// A higher epoch number exists but is not valid: nobody holds the agent.
    SupersededUnknown,
}

impl Gate {
    /// Whether input is accepted.
    #[must_use]
    pub const fn accepts(self) -> bool {
        matches!(self, Self::Active)
    }

    /// The wire name of the state.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Active => "active",
            Self::Moving => "moving",
            Self::NotHolder => "not_holder",
            Self::Placeless => "placeless",
            Self::SupersededUnknown => "superseded_unknown",
        }
    }
}
