//! The move pipeline (C1 §5.5) as two pure state machines, one per side, plus crash recovery.
//!
//! The source walks prepare, quiesce, checkpoint, top-up, verify and seal. Writing the seal is the
//! commit point: before it every failure aborts and the source keeps the agent; after it nothing
//! aborts and the source keeps sending `commit` until the target's `epoch_open` is seen. The target
//! checks the prepare (version, model profiles, H1, replica), verifies the materialized checkpoint
//! and writes its `epoch_open` on commit. Machines take events and the caller's clock and return
//! actions; the agent actor performs them (log writes go through `core_log::AgentLog`).

use core_log::Pos;
use core_membership::{Capability, MemberStatus, MembershipView};
use core_proto::{Epoch, NodeId};
use core_store::{LogId, PlacementState};

use crate::record::PlacementRecord;

/// `prepare` → `prepared` timeout.
pub const PREPARE_TIMEOUT_MS: u64 = 10_000;
/// Top-up timeout.
pub const TOP_UP_TIMEOUT_MS: u64 = 600_000;
/// Verify timeout.
pub const VERIFY_TIMEOUT_MS: u64 = 300_000;
/// `commit` is resent this often until the target's `epoch_open` is seen.
pub const COMMIT_RESEND_MS: u64 = 5_000;

/// Why a move request is refused before anything starts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RequestRefusal {
    /// This node does not hold the agent (or may not append).
    NotHolder,
    /// The target is this node.
    SameNode,
    /// The target is not an active member with `node.execute` and `store`.
    TargetIneligible,
}

/// Step 0: whether `me` accepts a move of `record`'s agent to `target`.
///
/// # Errors
///
/// The [`RequestRefusal`].
pub fn check_request(
    record: &PlacementRecord,
    view: &MembershipView,
    me: &NodeId,
    target: &NodeId,
) -> Result<(), RequestRefusal> {
    if !record.may_append(me) {
        return Err(RequestRefusal::NotHolder);
    }
    if target == me {
        return Err(RequestRefusal::SameNode);
    }
    if !executes_and_stores(view, target) {
        return Err(RequestRefusal::TargetIneligible);
    }
    Ok(())
}

fn executes_and_stores(view: &MembershipView, node: &NodeId) -> bool {
    view.status(node) == Some(MemberStatus::Active)
        && view.has_capability(node, Capability::NODE_EXECUTE)
        && view.has_capability(node, Capability::STORE)
}

/// `prepare {log_id, from, proposed, store_format, runtime_parity}`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Prepare {
    /// The agent log.
    pub log_id: LogId,
    /// The source's epoch.
    pub from: Epoch,
    /// The target's epoch: `(max_n_seen + 1, target)`.
    pub proposed: Epoch,
    /// The source's store format.
    pub store_format: u64,
    /// The source's runtime parity (I6).
    pub runtime_parity: String,
}

/// What the target knows when it checks a prepare.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TargetFacts {
    /// The target's store format.
    pub store_format: u64,
    /// The target's runtime parity.
    pub runtime_parity: String,
    /// Model profiles (agent default and conversation overrides) the target has no binding for.
    pub missing_profiles: Vec<String>,
    /// The latest checkpoint's H1 findings.
    pub h1_violations: Vec<String>,
    /// The target's replica of the log: its complete position, or `None` without a replica.
    pub replica: Option<Pos>,
}

/// Why the target refuses a prepare (`refused {reason}`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PrepareRefusal {
    /// The target is not an active member with `node.execute` and `store`.
    NotEligible,
    /// The proposed epoch does not name the target, or is not above the source's.
    BadEpoch,
    /// Store formats differ (I6).
    StoreFormat,
    /// Runtime parities differ (I6).
    RuntimeParity,
    /// No binding for these model profiles (I2).
    MissingProfiles(Vec<String>),
    /// The latest checkpoint has H1 violations.
    H1Violations,
    /// The target has no replica of the log.
    NoReplica,
}

impl PrepareRefusal {
    /// The wire reason.
    #[must_use]
    pub const fn code(&self) -> &'static str {
        match self {
            Self::NotEligible => "not_eligible",
            Self::BadEpoch => "bad_epoch",
            Self::StoreFormat => "store_format",
            Self::RuntimeParity => "runtime_parity",
            Self::MissingProfiles(_) => "missing_profiles",
            Self::H1Violations => "h1_violations",
            Self::NoReplica => "no_replica",
        }
    }
}

/// Step 1, target side: checks a prepare. On success the reply is `prepared {complete}`.
///
/// # Errors
///
/// The first [`PrepareRefusal`], in the order of C1 §5.5 step 1.
pub fn check_prepare(
    prepare: &Prepare,
    view: &MembershipView,
    me: &NodeId,
    facts: &TargetFacts,
) -> Result<Pos, PrepareRefusal> {
    if !executes_and_stores(view, me) {
        return Err(PrepareRefusal::NotEligible);
    }
    if prepare.proposed.claimant != *me || prepare.proposed <= prepare.from {
        return Err(PrepareRefusal::BadEpoch);
    }
    if prepare.store_format != facts.store_format {
        return Err(PrepareRefusal::StoreFormat);
    }
    if prepare.runtime_parity != facts.runtime_parity {
        return Err(PrepareRefusal::RuntimeParity);
    }
    if !facts.missing_profiles.is_empty() {
        return Err(PrepareRefusal::MissingProfiles(
            facts.missing_profiles.clone(),
        ));
    }
    if !facts.h1_violations.is_empty() {
        return Err(PrepareRefusal::H1Violations);
    }
    facts.replica.ok_or(PrepareRefusal::NoReplica)
}

/// Why a move ended without committing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AbortReason {
    /// The target refused the prepare.
    Refused(String),
    /// No `prepared` in time.
    PrepareTimeout,
    /// The in-flight turn did not finish within `quiesce_timeout`.
    QuiesceTimeout,
    /// The top-up did not finish in time.
    TopUpTimeout,
    /// No verify result in time.
    VerifyTimeout,
    /// The target's materialization did not match the checkpoint.
    VerifyFailed,
    /// The other side aborted.
    PeerAborted(String),
    /// A higher epoch was seen: the source is fenced (F4).
    Fenced,
}

impl AbortReason {
    /// The wire reason sent in `abort`.
    #[must_use]
    pub fn code(&self) -> &str {
        match self {
            Self::Refused(_) => "refused",
            Self::PrepareTimeout => "prepare_timeout",
            Self::QuiesceTimeout => "quiesce_timeout",
            Self::TopUpTimeout => "top_up_timeout",
            Self::VerifyTimeout => "verify_timeout",
            Self::VerifyFailed => "verify_failed",
            Self::PeerAborted(_) => "peer_aborted",
            Self::Fenced => "fenced",
        }
    }
}

/// Where the source is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SourcePhase {
    /// `prepare` sent.
    Preparing {
        /// Abort after this.
        deadline_ms: u64,
    },
    /// The gate is `moving`; waiting for the in-flight turn to finish.
    Quiescing {
        /// Abort (or stop the turn) after this; `None` once the turn was told to stop.
        deadline_ms: Option<u64>,
    },
    /// The checkpoint is being written.
    Checkpointing,
    /// Streaming the tail to the target.
    ToppingUp {
        /// The checkpoint.
        checkpoint: Pos,
        /// Abort after this.
        deadline_ms: u64,
    },
    /// Waiting for `verified`.
    Verifying {
        /// The checkpoint.
        checkpoint: Pos,
        /// Abort after this.
        deadline_ms: u64,
    },
    /// The seal is being written.
    Sealing {
        /// The checkpoint the seal names.
        checkpoint: Pos,
    },
    /// The seal is written (the commit point); `commit` is resent until the target opens.
    Committed {
        /// The seal.
        seal: Pos,
        /// When to resend `commit`.
        resend_at_ms: u64,
    },
    /// The target opened its epoch.
    Done,
    /// The move ended before the seal; the source keeps the agent unless fenced.
    Aborted(AbortReason),
}

/// An event the source's actor feeds in.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SourceEvent {
    /// `prepared {complete}`.
    Prepared {
        /// The target's complete position.
        complete: Pos,
    },
    /// `refused {reason}`.
    Refused(String),
    /// `abort {reason}` from the target.
    PeerAborted(String),
    /// The in-flight turn finished (or none was running) and memory is committed.
    TurnIdle,
    /// The checkpoint entry was appended.
    CheckpointWritten(Pos),
    /// The target acked this position of the source's segment.
    Acked(Pos),
    /// `verified {checkpoint}`.
    Verified(Pos),
    /// `verify_failed {...}`.
    VerifyFailed,
    /// The seal entry was appended.
    SealWritten(Pos),
    /// The target's `epoch_open` was seen.
    TargetOpened,
    /// A higher epoch than the source's was seen.
    HigherEpochSeen,
    /// Time passed.
    Tick,
}

/// What the source's actor must do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SourceAction {
    /// Send `prepare`.
    SendPrepare(Prepare),
    /// Set the gate to `moving` (refuse new input with a retry hint).
    GateMoving,
    /// Reopen the gate (the move is over and the source still holds the agent).
    GateReopen,
    /// Stop the in-flight turn through the runtime host (the user chose "stop the turn and move").
    StopTurn,
    /// Let letta-code commit memory, flush the exporter, then append a `checkpoint`.
    WriteCheckpoint,
    /// Stream the log tail to the target on `baken/log/1`.
    TopUp,
    /// Append `epoch_seal {next_epoch, checkpoint}`: the commit point.
    WriteSeal {
        /// The target's epoch.
        next_epoch: Epoch,
        /// The verified checkpoint.
        checkpoint: Pos,
    },
    /// Send `commit {seal}` to the target and push the seal to every peer.
    SendCommit {
        /// The seal.
        seal: Pos,
    },
    /// Send `abort {reason}`.
    SendAbort(AbortReason),
}

/// Options of one move.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MoveOptions {
    /// The policy's `quiesce_timeout_ms`.
    pub quiesce_timeout_ms: u64,
    /// "Stop the turn and move": on quiesce timeout, stop the turn instead of aborting.
    pub stop_turn: bool,
}

/// The source side of one move.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MoveSource {
    prepare: Prepare,
    options: MoveOptions,
    phase: SourcePhase,
}

impl MoveSource {
    /// Starts a move: sends `prepare` for `(max_n_seen + 1, target)`. The caller has checked the
    /// request ([`check_request`]) and serializes moves per agent.
    #[must_use]
    pub fn start(
        record: &PlacementRecord,
        target: NodeId,
        store_format: u64,
        runtime_parity: String,
        options: MoveOptions,
        now_ms: u64,
    ) -> (Self, Vec<SourceAction>) {
        let prepare = Prepare {
            log_id: record.log_id,
            from: record.epoch,
            proposed: Epoch {
                n: record.max_n_seen + 1,
                claimant: target,
            },
            store_format,
            runtime_parity,
        };
        let source = Self {
            prepare: prepare.clone(),
            options,
            phase: SourcePhase::Preparing {
                deadline_ms: now_ms.saturating_add(PREPARE_TIMEOUT_MS),
            },
        };
        (source, vec![SourceAction::SendPrepare(prepare)])
    }

    /// The current phase.
    #[must_use]
    pub const fn phase(&self) -> &SourcePhase {
        &self.phase
    }

    /// The prepare this move sent.
    #[must_use]
    pub const fn prepare(&self) -> &Prepare {
        &self.prepare
    }

    /// Whether the seal is written (the move can no longer abort).
    #[must_use]
    pub const fn committed(&self) -> bool {
        matches!(
            self.phase,
            SourcePhase::Committed { .. } | SourcePhase::Done
        )
    }

    /// Feeds one event at `now_ms`.
    pub fn handle(&mut self, event: &SourceEvent, now_ms: u64) -> Vec<SourceAction> {
        use SourceAction as A;
        use SourcePhase as P;
        if matches!(self.phase, P::Done | P::Aborted(_)) {
            return Vec::new();
        }
        if self.committed() {
            return self.after_commit(event, now_ms);
        }
        let gate_set = !matches!(self.phase, P::Preparing { .. });
        match (&self.phase, event) {
            (_, SourceEvent::HigherEpochSeen) => {
                // F4 fences the source; the gate stays shut through the placement record.
                self.phase = P::Aborted(AbortReason::Fenced);
                vec![A::SendAbort(AbortReason::Fenced)]
            }
            (_, SourceEvent::Refused(reason)) => {
                self.abort(AbortReason::Refused(reason.clone()), gate_set)
            }
            (_, SourceEvent::PeerAborted(reason)) => {
                self.abort(AbortReason::PeerAborted(reason.clone()), gate_set)
            }
            (_, SourceEvent::VerifyFailed) => self.abort(AbortReason::VerifyFailed, gate_set),
            (P::Preparing { .. }, SourceEvent::Prepared { .. }) => {
                self.phase = P::Quiescing {
                    deadline_ms: Some(now_ms.saturating_add(self.options.quiesce_timeout_ms)),
                };
                vec![A::GateMoving]
            }
            (P::Quiescing { .. }, SourceEvent::TurnIdle) => {
                self.phase = P::Checkpointing;
                vec![A::WriteCheckpoint]
            }
            (P::Checkpointing, SourceEvent::CheckpointWritten(checkpoint)) => {
                self.phase = P::ToppingUp {
                    checkpoint: *checkpoint,
                    deadline_ms: now_ms.saturating_add(TOP_UP_TIMEOUT_MS),
                };
                vec![A::TopUp]
            }
            (P::ToppingUp { checkpoint, .. }, SourceEvent::Acked(pos))
                if pos.seq >= checkpoint.seq =>
            {
                self.phase = P::Verifying {
                    checkpoint: *checkpoint,
                    deadline_ms: now_ms.saturating_add(VERIFY_TIMEOUT_MS),
                };
                Vec::new()
            }
            (
                P::ToppingUp { checkpoint, .. } | P::Verifying { checkpoint, .. },
                SourceEvent::Verified(pos),
            ) if pos == checkpoint => {
                let checkpoint = *checkpoint;
                self.phase = P::Sealing { checkpoint };
                vec![A::WriteSeal {
                    next_epoch: self.prepare.proposed,
                    checkpoint,
                }]
            }
            (P::Sealing { .. }, SourceEvent::SealWritten(seal)) => {
                self.phase = P::Committed {
                    seal: *seal,
                    resend_at_ms: now_ms.saturating_add(COMMIT_RESEND_MS),
                };
                vec![A::SendCommit { seal: *seal }]
            }
            (_, SourceEvent::Tick) => self.tick(now_ms),
            _ => Vec::new(),
        }
    }

    fn tick(&mut self, now_ms: u64) -> Vec<SourceAction> {
        use SourcePhase as P;
        match self.phase {
            P::Preparing { deadline_ms } if now_ms >= deadline_ms => {
                self.abort(AbortReason::PrepareTimeout, false)
            }
            P::Quiescing {
                deadline_ms: Some(deadline_ms),
            } if now_ms >= deadline_ms => {
                if self.options.stop_turn {
                    self.phase = P::Quiescing { deadline_ms: None };
                    vec![SourceAction::StopTurn]
                } else {
                    self.abort(AbortReason::QuiesceTimeout, true)
                }
            }
            P::ToppingUp { deadline_ms, .. } if now_ms >= deadline_ms => {
                self.abort(AbortReason::TopUpTimeout, true)
            }
            P::Verifying { deadline_ms, .. } if now_ms >= deadline_ms => {
                self.abort(AbortReason::VerifyTimeout, true)
            }
            _ => Vec::new(),
        }
    }

    fn abort(&mut self, reason: AbortReason, reopen: bool) -> Vec<SourceAction> {
        let mut actions = vec![SourceAction::SendAbort(reason.clone())];
        if reopen {
            actions.push(SourceAction::GateReopen);
        }
        self.phase = SourcePhase::Aborted(reason);
        actions
    }

    fn after_commit(&mut self, event: &SourceEvent, now_ms: u64) -> Vec<SourceAction> {
        match (&self.phase, event) {
            (
                SourcePhase::Committed { .. },
                SourceEvent::TargetOpened | SourceEvent::HigherEpochSeen,
            ) => {
                // Any higher epoch ends the source's part: the seal is the last entry it writes.
                self.phase = SourcePhase::Done;
                Vec::new()
            }
            (SourcePhase::Committed { seal, resend_at_ms }, SourceEvent::Tick)
                if now_ms >= *resend_at_ms =>
            {
                let seal = *seal;
                self.phase = SourcePhase::Committed {
                    seal,
                    resend_at_ms: now_ms.saturating_add(COMMIT_RESEND_MS),
                };
                vec![SourceAction::SendCommit { seal }]
            }
            _ => Vec::new(),
        }
    }
}

/// Where the target is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TargetPhase {
    /// `prepared` sent; waiting for the checkpoint to arrive complete.
    AwaitingCheckpoint {
        /// Abort after this.
        deadline_ms: u64,
    },
    /// Materializing into staging and comparing with the checkpoint.
    Verifying {
        /// The checkpoint.
        checkpoint: Pos,
        /// Abort after this.
        deadline_ms: u64,
    },
    /// `verified` sent; waiting for `commit`.
    AwaitingCommit {
        /// The verified checkpoint.
        checkpoint: Pos,
    },
    /// The `epoch_open` is being written.
    Opening {
        /// Its parent, the seal.
        seal: Pos,
    },
    /// Opened: the target holds the agent.
    Opened,
    /// The move ended; staging is discarded.
    Aborted(AbortReason),
}

/// An event the target's actor feeds in.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TargetEvent {
    /// The source's checkpoint entry arrived and the replica is complete up to it.
    CheckpointComplete(Pos),
    /// Materialization finished; whether manifest and refs match the checkpoint exactly.
    Materialized {
        /// Exact match.
        matches: bool,
    },
    /// `commit {seal}`, with the seal entry already stored and naming this target's epoch.
    Commit {
        /// The seal.
        seal: Pos,
    },
    /// The `epoch_open` was appended.
    OpenWritten,
    /// `abort {reason}` from the source.
    PeerAborted(String),
    /// Time passed.
    Tick,
}

/// What the target's actor must do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TargetAction {
    /// Materialize the canonical line up to the checkpoint into staging with this node's bindings.
    Materialize {
        /// The checkpoint.
        checkpoint: Pos,
    },
    /// Send `verified {checkpoint}`.
    SendVerified(Pos),
    /// Send `verify_failed`.
    SendVerifyFailed,
    /// Append `epoch_open {reason: move, prior_epoch}` with the seal as parent.
    WriteOpen {
        /// The source's epoch.
        prior_epoch: Epoch,
        /// The parent (the seal).
        seal: Pos,
    },
    /// Swap staging into place, start the runtime, write the post-open checkpoint, open the gate,
    /// re-authorize grants and update the catalog placement copy.
    TakeOver,
    /// Discard staging.
    DiscardStaging,
    /// Send `abort {reason}`.
    SendAbort(AbortReason),
}

/// The target side of one move, created after [`check_prepare`] accepted.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MoveTarget {
    prepare: Prepare,
    phase: TargetPhase,
}

impl MoveTarget {
    /// The target after replying `prepared`.
    #[must_use]
    pub const fn new(prepare: Prepare, now_ms: u64) -> Self {
        Self {
            prepare,
            phase: TargetPhase::AwaitingCheckpoint {
                deadline_ms: now_ms.saturating_add(TOP_UP_TIMEOUT_MS),
            },
        }
    }

    /// The current phase.
    #[must_use]
    pub const fn phase(&self) -> &TargetPhase {
        &self.phase
    }

    /// Feeds one event at `now_ms`.
    pub fn handle(&mut self, event: &TargetEvent, now_ms: u64) -> Vec<TargetAction> {
        use TargetAction as A;
        use TargetPhase as P;
        match (&self.phase, event) {
            // A commit is authoritative whenever it arrives (the seal is the source's commit);
            // one already being acted on is not acted on twice.
            (P::Opened | P::Aborted(_), _) | (P::Opening { .. }, TargetEvent::Commit { .. }) => {
                Vec::new()
            }
            (P::Opening { .. }, TargetEvent::OpenWritten) => {
                self.phase = P::Opened;
                vec![A::TakeOver]
            }
            (_, TargetEvent::Commit { seal }) => {
                self.phase = P::Opening { seal: *seal };
                vec![A::WriteOpen {
                    prior_epoch: self.prepare.from,
                    seal: *seal,
                }]
            }
            (_, TargetEvent::PeerAborted(reason)) => {
                self.phase = P::Aborted(AbortReason::PeerAborted(reason.clone()));
                vec![A::DiscardStaging]
            }
            (P::AwaitingCheckpoint { .. }, TargetEvent::CheckpointComplete(checkpoint)) => {
                self.phase = P::Verifying {
                    checkpoint: *checkpoint,
                    deadline_ms: now_ms.saturating_add(VERIFY_TIMEOUT_MS),
                };
                vec![A::Materialize {
                    checkpoint: *checkpoint,
                }]
            }
            (P::Verifying { checkpoint, .. }, TargetEvent::Materialized { matches }) => {
                let checkpoint = *checkpoint;
                if *matches {
                    self.phase = P::AwaitingCommit { checkpoint };
                    vec![A::SendVerified(checkpoint)]
                } else {
                    self.phase = P::Aborted(AbortReason::VerifyFailed);
                    vec![A::SendVerifyFailed, A::DiscardStaging]
                }
            }
            (
                P::AwaitingCheckpoint { deadline_ms } | P::Verifying { deadline_ms, .. },
                TargetEvent::Tick,
            ) if now_ms >= *deadline_ms => {
                let reason = if matches!(self.phase, P::AwaitingCheckpoint { .. }) {
                    AbortReason::TopUpTimeout
                } else {
                    AbortReason::VerifyTimeout
                };
                self.phase = P::Aborted(reason.clone());
                vec![A::SendAbort(reason), A::DiscardStaging]
            }
            _ => Vec::new(),
        }
    }
}

/// What a source does when it restarts mid-move (C1 §5.5 crash table).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SourceRecovery {
    /// Crashed before the seal: no move happened. Resume as holder after the startup check.
    ResumeAfterStartupCheck,
    /// Crashed after the seal: stay fenced and keep sending `commit` to the target.
    ResendCommit {
        /// The target's epoch.
        to: Epoch,
        /// The seal.
        seal: Pos,
    },
    /// This node does not hold the agent (any more).
    NotHolder,
}

/// Source recovery from the log alone.
#[must_use]
pub fn recover_source(record: &PlacementRecord, me: &NodeId) -> SourceRecovery {
    if record.epoch.claimant != *me || record.max_n_seen > record.epoch.n {
        return SourceRecovery::NotHolder;
    }
    match record.state {
        PlacementState::Active => SourceRecovery::ResumeAfterStartupCheck,
        PlacementState::SealedTo(to) => SourceRecovery::ResendCommit {
            to,
            seal: record.tip,
        },
        PlacementState::Placeless | PlacementState::SupersededUnknown => SourceRecovery::NotHolder,
    }
}

/// What a target does when it restarts (C1 §5.5 crash table).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TargetRecovery {
    /// A seal names this node's epoch and no open followed: write the `epoch_open` now (the epoch
    /// and seq are fixed by the seal, so this is idempotent).
    WriteOpen {
        /// The epoch to open.
        epoch: Epoch,
        /// The source's epoch.
        prior_epoch: Epoch,
        /// The parent (the seal).
        seal: Pos,
    },
    /// Already the holder: resume after the startup check.
    ResumeAfterStartupCheck,
    /// Nothing to do: any staging from an unfinished move is discarded.
    Nothing,
}

/// Target recovery from the log alone.
#[must_use]
pub fn recover_target(record: &PlacementRecord, me: &NodeId) -> TargetRecovery {
    match record.state {
        PlacementState::SealedTo(next) if next.claimant == *me && record.max_n_seen < next.n => {
            TargetRecovery::WriteOpen {
                epoch: next,
                prior_epoch: record.epoch,
                seal: record.tip,
            }
        }
        PlacementState::Active if record.may_append(me) => TargetRecovery::ResumeAfterStartupCheck,
        _ => TargetRecovery::Nothing,
    }
}
