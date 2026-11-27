//! The move pipeline: both state machines, prepare checks, and C1's crash table against real logs
//! (P4, P5).

mod common;

use common::{
    Members, agent, append_payload, epoch, genesis_payload, key, log, node, open_payload, to_store,
};
use core_log::{AgentLog, EpochOpen, Payload, Pos, Reason};
use core_membership::{MembershipView, Preset};
use core_placement::failover::{self, FailoverDecision, Liveness, NodeClass, PeerFacts};
use core_placement::pipeline::{
    AbortReason, MoveOptions, Prepare, PrepareRefusal, RequestRefusal, SourceAction, SourceEvent,
    SourcePhase, TargetAction, TargetEvent, TargetFacts, TargetPhase, check_prepare, check_request,
    recover_source, recover_target,
};
use core_placement::{
    FailoverInput, MembershipJudge, MoveSource, MoveTarget, PlacementRecord, SourceRecovery,
    TargetRecovery,
};
use core_proto::{Epoch, NodeId};
use core_store::{EntryHash, MemoryStore, PlacementState, SegmentState, Store};

fn members() -> Members {
    let mut m = Members::new();
    m.add(2, Preset::DesktopNode)
        .add(3, Preset::AlwaysOnLinux)
        .add(4, Preset::Phone { execution: false });
    m
}

fn options() -> MoveOptions {
    MoveOptions {
        quiesce_timeout_ms: 120_000,
        stop_turn: false,
    }
}

fn pos(seq: u64, fill: u8) -> Pos {
    Pos {
        seq,
        hash: EntryHash::from_bytes([fill; 32]),
    }
}

fn record(state: PlacementState) -> PlacementRecord {
    PlacementRecord {
        log_id: log(),
        agent_id: agent(),
        epoch: epoch(1, 1),
        state,
        max_n_seen: 1,
        tip: pos(3, 3),
    }
}

/// One node's view of the log: its store, written as holder through `AgentLog`.
struct Node {
    k: u8,
    store: MemoryStore,
}

impl Node {
    fn new(k: u8) -> Self {
        Self {
            k,
            store: MemoryStore::new(),
        }
    }

    fn with<T>(
        &self,
        view: &MembershipView,
        f: impl FnOnce(
            &AgentLog<'_, MembershipJudge<'_>>,
            &mut dyn core_store::Txn,
        ) -> Result<T, core_log::LogError>,
    ) -> T {
        let agent = agent();
        let judge = MembershipJudge::new(view, Some(&agent));
        self.store
            .write(|txn| f(&AgentLog::new(log(), &judge), txn).map_err(to_store))
            .expect("write")
    }

    fn record(&self, view: &MembershipView) -> PlacementRecord {
        let agent = agent();
        let judge = MembershipJudge::new(view, Some(&agent));
        self.store
            .read(|txn| {
                PlacementRecord::derive(&AgentLog::new(log(), &judge), txn).map_err(to_store)
            })
            .expect("read")
            .expect("a record")
    }

    /// Copies every entry this node holds into `other` (a full top-up).
    fn push_to(&self, other: &Self, view: &MembershipView) {
        let agent = agent();
        let judge = MembershipJudge::new(view, Some(&agent));
        let entries = self
            .store
            .read(|txn| {
                let mut out = Vec::new();
                for segment in txn.segments(&log())? {
                    for entry in txn.segment_entries(&log(), &segment.epoch, 0, usize::MAX)? {
                        let payload = txn.blob(&entry.payload_hash)?.unwrap_or_default();
                        out.push((entry, payload));
                    }
                }
                Ok::<_, core_store::StoreError>(out)
            })
            .expect("read");
        let mut sorted = entries;
        sorted.sort_by_key(|(e, _)| e.seq);
        other
            .store
            .write(|txn| {
                let log = AgentLog::new(log(), &judge);
                for (entry, payload) in &sorted {
                    let signed = core_log::SignedEntry::from_stored(entry).expect("signed");
                    log.receive(txn, &signed, &core_log::Incoming::Inline(payload.clone()))
                        .map_err(to_store)?;
                }
                log.evaluate(txn).map_err(to_store)
            })
            .expect("push");
    }

    fn key(&self) -> core_proto::crypto::NodeKey {
        key(self.k)
    }

    fn id(&self) -> NodeId {
        node(self.k)
    }
}

fn genesis_open() -> EpochOpen {
    let Payload::EpochOpen(open) = genesis_payload() else {
        unreachable!()
    };
    open
}

fn open(reason: Reason, prior: Epoch) -> EpochOpen {
    let Payload::EpochOpen(open) = open_payload(reason, prior, Vec::new()) else {
        unreachable!()
    };
    open
}

/// N1 creates the agent and writes two entries; returns the last position.
fn source_with_history(view: &MembershipView) -> (Node, Pos) {
    let source = Node::new(1);
    let k = source.key();
    let last = source.with(view, |log, txn| {
        log.genesis(txn, &k, genesis_open(), 10)?;
        log.append(txn, &k, &append_payload(0, "a"), 11)?;
        let e = log.append(txn, &k, &append_payload(1, "b"), 12)?;
        Ok(Pos {
            seq: e.header().seq,
            hash: e.hash(),
        })
    });
    (source, last)
}

// ----- state machines -----

#[test]
fn a_clean_move_walks_every_step_and_commits_at_the_seal() {
    let (mut source, actions) = MoveSource::start(
        &record(PlacementState::Active),
        node(2),
        1,
        common::PARITY.into(),
        options(),
        0,
    );
    let prepare = source.prepare().clone();
    assert_eq!(prepare.proposed, epoch(2, 2));
    assert_eq!(actions, vec![SourceAction::SendPrepare(prepare.clone())]);
    let mut target = MoveTarget::new(prepare.clone(), 0);

    assert_eq!(
        source.handle(
            &SourceEvent::Prepared {
                complete: pos(2, 2)
            },
            100
        ),
        vec![SourceAction::GateMoving]
    );
    assert_eq!(
        source.handle(&SourceEvent::TurnIdle, 200),
        vec![SourceAction::WriteCheckpoint]
    );
    let k = pos(4, 4);
    assert_eq!(
        source.handle(&SourceEvent::CheckpointWritten(k), 300),
        vec![SourceAction::TopUp]
    );
    assert!(!source.committed());
    assert_eq!(source.handle(&SourceEvent::Acked(k), 400), vec![]);
    assert_eq!(
        target.handle(&TargetEvent::CheckpointComplete(k), 400),
        vec![TargetAction::Materialize { checkpoint: k }]
    );
    assert_eq!(
        target.handle(&TargetEvent::Materialized { matches: true }, 500),
        vec![TargetAction::SendVerified(k)]
    );
    assert_eq!(
        source.handle(&SourceEvent::Verified(k), 500),
        vec![SourceAction::WriteSeal {
            next_epoch: epoch(2, 2),
            checkpoint: k
        }]
    );
    let seal = pos(5, 5);
    assert_eq!(
        source.handle(&SourceEvent::SealWritten(seal), 600),
        vec![SourceAction::SendCommit { seal }]
    );
    assert!(source.committed(), "the seal is the commit point");
    // After the commit point nothing aborts, timeouts included.
    assert_eq!(source.handle(&SourceEvent::VerifyFailed, 700), vec![]);
    assert_eq!(
        source.handle(&SourceEvent::Tick, 5_600),
        vec![SourceAction::SendCommit { seal }],
        "commit is resent every 5 s"
    );
    assert_eq!(
        target.handle(&TargetEvent::Commit { seal }, 5_600),
        vec![TargetAction::WriteOpen {
            prior_epoch: epoch(1, 1),
            seal
        }]
    );
    assert_eq!(target.handle(&TargetEvent::Commit { seal }, 5_700), vec![]);
    assert_eq!(
        target.handle(&TargetEvent::OpenWritten, 5_800),
        vec![TargetAction::TakeOver]
    );
    assert_eq!(source.handle(&SourceEvent::TargetOpened, 5_900), vec![]);
    assert_eq!(source.phase(), &SourcePhase::Done);
    assert_eq!(target.phase(), &TargetPhase::Opened);
}

#[test]
// One scenario per failure point, each a few lines; kept together as the table they form.
#[allow(clippy::too_many_lines)]
fn failures_before_the_seal_abort_and_reopen_the_gate() {
    let start = || {
        MoveSource::start(
            &record(PlacementState::Active),
            node(2),
            1,
            common::PARITY.into(),
            options(),
            0,
        )
        .0
    };
    // Refused at prepare: the gate was never closed.
    let mut s = start();
    assert_eq!(
        s.handle(&SourceEvent::Refused("missing_profiles".into()), 1),
        vec![SourceAction::SendAbort(AbortReason::Refused(
            "missing_profiles".into()
        ))]
    );
    // Prepare timeout.
    let mut s = start();
    assert_eq!(s.handle(&SourceEvent::Tick, 9_999), vec![]);
    assert_eq!(
        s.handle(&SourceEvent::Tick, 10_000),
        vec![SourceAction::SendAbort(AbortReason::PrepareTimeout)]
    );
    // Quiesce timeout aborts and reopens.
    let mut s = start();
    s.handle(
        &SourceEvent::Prepared {
            complete: pos(1, 1),
        },
        0,
    );
    assert_eq!(
        s.handle(&SourceEvent::Tick, 120_000),
        vec![
            SourceAction::SendAbort(AbortReason::QuiesceTimeout),
            SourceAction::GateReopen
        ]
    );
    // "Stop the turn and move" stops the turn instead.
    let (mut s, _) = MoveSource::start(
        &record(PlacementState::Active),
        node(2),
        1,
        common::PARITY.into(),
        MoveOptions {
            quiesce_timeout_ms: 120_000,
            stop_turn: true,
        },
        0,
    );
    s.handle(
        &SourceEvent::Prepared {
            complete: pos(1, 1),
        },
        0,
    );
    assert_eq!(
        s.handle(&SourceEvent::Tick, 120_000),
        vec![SourceAction::StopTurn]
    );
    assert_eq!(
        s.handle(&SourceEvent::TurnIdle, 121_000),
        vec![SourceAction::WriteCheckpoint]
    );
    // Top-up and verify timeouts.
    let mut s = start();
    s.handle(
        &SourceEvent::Prepared {
            complete: pos(1, 1),
        },
        0,
    );
    s.handle(&SourceEvent::TurnIdle, 0);
    s.handle(&SourceEvent::CheckpointWritten(pos(4, 4)), 0);
    assert_eq!(
        s.handle(&SourceEvent::Tick, 600_000),
        vec![
            SourceAction::SendAbort(AbortReason::TopUpTimeout),
            SourceAction::GateReopen
        ]
    );
    let mut s = start();
    s.handle(
        &SourceEvent::Prepared {
            complete: pos(1, 1),
        },
        0,
    );
    s.handle(&SourceEvent::TurnIdle, 0);
    s.handle(&SourceEvent::CheckpointWritten(pos(4, 4)), 0);
    s.handle(&SourceEvent::Acked(pos(4, 4)), 10);
    assert_eq!(
        s.handle(&SourceEvent::Tick, 300_010),
        vec![
            SourceAction::SendAbort(AbortReason::VerifyTimeout),
            SourceAction::GateReopen
        ]
    );
    // A higher epoch fences the source mid-move; the record keeps the gate shut.
    let mut s = start();
    s.handle(
        &SourceEvent::Prepared {
            complete: pos(1, 1),
        },
        0,
    );
    assert_eq!(
        s.handle(&SourceEvent::HigherEpochSeen, 5),
        vec![SourceAction::SendAbort(AbortReason::Fenced)]
    );
    assert_eq!(s.handle(&SourceEvent::TurnIdle, 6), vec![]);
}

#[test]
fn a_failed_verify_leaves_the_source_holding_with_only_its_checkpoint() {
    // P5, on a real log: the source writes its checkpoint, the target's verify fails, and the
    // source keeps its epoch with no other new entry.
    let view = members().view();
    let (source, last) = source_with_history(&view);
    let before = source.record(&view);
    let (mut machine, _) =
        MoveSource::start(&before, node(2), 1, common::PARITY.into(), options(), 0);
    machine.handle(&SourceEvent::Prepared { complete: last }, 1);
    assert_eq!(
        machine.handle(&SourceEvent::TurnIdle, 2),
        vec![SourceAction::WriteCheckpoint]
    );
    let k = source.key();
    let checkpoint = source.with(&view, |log, txn| {
        let e = log.append(txn, &k, &append_payload(2, "checkpoint"), 13)?;
        Ok(Pos {
            seq: e.header().seq,
            hash: e.hash(),
        })
    });
    machine.handle(&SourceEvent::CheckpointWritten(checkpoint), 3);
    let actions = machine.handle(&SourceEvent::VerifyFailed, 4);
    assert_eq!(
        actions,
        vec![
            SourceAction::SendAbort(AbortReason::VerifyFailed),
            SourceAction::GateReopen
        ]
    );
    assert!(
        actions
            .iter()
            .all(|a| !matches!(a, SourceAction::WriteSeal { .. }))
    );
    let after = source.record(&view);
    assert_eq!(after.epoch, before.epoch);
    assert_eq!(after.state, PlacementState::Active);
    assert_eq!(
        after.tip, checkpoint,
        "the checkpoint is the only new entry"
    );
    assert_eq!(after.tip.seq, before.tip.seq + 1);
    assert!(after.gate(&node(1), false).accepts());
}

#[test]
fn the_target_aborts_on_mismatch_and_timeouts() {
    let prepare = Prepare {
        log_id: log(),
        from: epoch(1, 1),
        proposed: epoch(2, 2),
        store_format: 1,
        runtime_parity: common::PARITY.into(),
    };
    let mut t = MoveTarget::new(prepare.clone(), 0);
    t.handle(&TargetEvent::CheckpointComplete(pos(4, 4)), 1);
    assert_eq!(
        t.handle(&TargetEvent::Materialized { matches: false }, 2),
        vec![TargetAction::SendVerifyFailed, TargetAction::DiscardStaging]
    );
    let mut t = MoveTarget::new(prepare.clone(), 0);
    assert_eq!(
        t.handle(&TargetEvent::Tick, 600_000),
        vec![
            TargetAction::SendAbort(AbortReason::TopUpTimeout),
            TargetAction::DiscardStaging
        ]
    );
    let mut t = MoveTarget::new(prepare, 0);
    assert_eq!(
        t.handle(&TargetEvent::PeerAborted("quiesce_timeout".into()), 1),
        vec![TargetAction::DiscardStaging]
    );
}

#[test]
fn prepare_checks_version_profiles_h1_and_replica() {
    let view = members().view();
    let prepare = Prepare {
        log_id: log(),
        from: epoch(1, 1),
        proposed: epoch(2, 2),
        store_format: 1,
        runtime_parity: common::PARITY.into(),
    };
    let good = TargetFacts {
        store_format: 1,
        runtime_parity: common::PARITY.into(),
        missing_profiles: Vec::new(),
        h1_violations: Vec::new(),
        replica: Some(pos(9, 9)),
    };
    assert_eq!(
        check_prepare(&prepare, &view, &node(2), &good),
        Ok(pos(9, 9))
    );
    let refuse = |facts: TargetFacts, me: u8| check_prepare(&prepare, &view, &node(me), &facts);
    assert_eq!(refuse(good.clone(), 4), Err(PrepareRefusal::NotEligible));
    assert_eq!(refuse(good.clone(), 3), Err(PrepareRefusal::BadEpoch));
    assert_eq!(
        refuse(
            TargetFacts {
                store_format: 2,
                ..good.clone()
            },
            2
        ),
        Err(PrepareRefusal::StoreFormat)
    );
    assert_eq!(
        refuse(
            TargetFacts {
                runtime_parity: "other".into(),
                ..good.clone()
            },
            2
        ),
        Err(PrepareRefusal::RuntimeParity)
    );
    assert_eq!(
        refuse(
            TargetFacts {
                missing_profiles: vec!["opus".into()],
                ..good.clone()
            },
            2
        ),
        Err(PrepareRefusal::MissingProfiles(vec!["opus".into()]))
    );
    assert_eq!(
        refuse(
            TargetFacts {
                h1_violations: vec!["x".into()],
                ..good.clone()
            },
            2
        ),
        Err(PrepareRefusal::H1Violations)
    );
    assert_eq!(
        refuse(
            TargetFacts {
                replica: None,
                ..good
            },
            2
        ),
        Err(PrepareRefusal::NoReplica)
    );
}

#[test]
fn move_requests_need_the_holder_and_an_eligible_target() {
    let view = members().view();
    let r = record(PlacementState::Active);
    assert_eq!(check_request(&r, &view, &node(1), &node(2)), Ok(()));
    assert_eq!(
        check_request(&r, &view, &node(2), &node(3)),
        Err(RequestRefusal::NotHolder)
    );
    assert_eq!(
        check_request(&r, &view, &node(1), &node(1)),
        Err(RequestRefusal::SameNode)
    );
    assert_eq!(
        check_request(&r, &view, &node(1), &node(4)),
        Err(RequestRefusal::TargetIneligible),
        "a phone without execution cannot receive an agent"
    );
}

// ----- the crash table (C1 §5.5), P4 -----

/// Seals N1's segment to `(2, N2)` and returns the seal's position.
fn seal_to_n2(source: &Node, view: &MembershipView, checkpoint: Pos) -> Pos {
    let k = source.key();
    source.with(view, |log, txn| {
        let e = log.seal(txn, &k, Some(epoch(2, 2)), checkpoint, 20)?;
        Ok(Pos {
            seq: e.header().seq,
            hash: e.hash(),
        })
    })
}

#[test]
fn crash_of_the_source_before_the_seal_is_no_move() {
    let view = members().view();
    let (source, _) = source_with_history(&view);
    let target = Node::new(2);
    source.push_to(&target, &view);
    let record = source.record(&view);
    assert_eq!(
        recover_source(&record, &source.id()),
        SourceRecovery::ResumeAfterStartupCheck
    );
    assert_eq!(
        recover_target(&target.record(&view), &target.id()),
        TargetRecovery::Nothing
    );
    assert!(record.gate(&source.id(), false).accepts());
    assert!(!target.record(&view).gate(&target.id(), false).accepts());
}

#[test]
fn crash_of_the_source_after_the_seal_keeps_it_fenced_and_resending() {
    let view = members().view();
    let (source, last) = source_with_history(&view);
    let seal = seal_to_n2(&source, &view, last);
    let record = source.record(&view);
    assert_eq!(record.state, PlacementState::SealedTo(epoch(2, 2)));
    assert_eq!(
        recover_source(&record, &source.id()),
        SourceRecovery::ResendCommit {
            to: epoch(2, 2),
            seal
        }
    );
    assert!(
        !record.may_append(&source.id()),
        "a sealed source never writes again"
    );
    let k = source.key();
    let refused = source
        .store
        .write(|txn| {
            let agent = agent();
            let judge = MembershipJudge::new(&view, Some(&agent));
            Ok::<_, core_store::StoreError>(
                AgentLog::new(log(), &judge)
                    .append(txn, &k, &append_payload(2, "late"), 30)
                    .is_err(),
            )
        })
        .expect("write");
    assert!(refused);
}

#[test]
fn crash_of_the_target_after_the_seal_opens_on_restart_idempotently() {
    let view = members().view();
    let (source, last) = source_with_history(&view);
    let seal = seal_to_n2(&source, &view, last);
    let target = Node::new(2);
    source.push_to(&target, &view);
    let recovery = recover_target(&target.record(&view), &target.id());
    assert_eq!(
        recovery,
        TargetRecovery::WriteOpen {
            epoch: epoch(2, 2),
            prior_epoch: epoch(1, 1),
            seal
        }
    );
    let k = target.key();
    let (entry, evaluation) = target.with(&view, |log, txn| {
        log.claim(txn, &k, &open(Reason::Move, epoch(1, 1)), seal, 40)
    });
    assert_eq!(
        entry.header().epoch,
        epoch(2, 2),
        "the seal fixes the epoch"
    );
    assert_eq!(entry.header().seq, seal.seq + 1, "and the seq");
    assert_eq!(evaluation.canonical, Some(epoch(2, 2)));
    assert_eq!(
        recover_target(&target.record(&view), &target.id()),
        TargetRecovery::ResumeAfterStartupCheck
    );
    // The source learns of the open and is done; it is not the holder any more.
    target.push_to(&source, &view);
    assert_eq!(
        recover_source(&source.record(&view), &source.id()),
        SourceRecovery::NotHolder
    );
}

#[test]
fn an_unreachable_target_after_a_seal_is_claimed_by_failover_above_its_epoch() {
    let view = members().view();
    let (source, last) = source_with_history(&view);
    let seal = seal_to_n2(&source, &view, last);
    let candidate = Node::new(3);
    source.push_to(&candidate, &view);
    let record = candidate.record(&view);
    let facts = PeerFacts {
        classes: [
            (node(2), NodeClass::Desktop),
            (node(3), NodeClass::AlwaysOn),
        ]
        .into_iter()
        .collect(),
        ..PeerFacts::default()
    };
    let decision = failover::evaluate(&FailoverInput {
        view: &view,
        placement: &record,
        me: node(3),
        facts: &facts,
        liveness: Liveness {
            last_heard_ms: 0,
            last_dial_failure_ms: Some(299_000),
            peer_heard_ms: None,
        },
        runtime_parity_matches: true,
        now_ms: 300_000,
    });
    assert_eq!(decision, FailoverDecision::Claim { rank: 0 });
    let k = candidate.key();
    let (entry, _) = candidate.with(&view, |log, txn| {
        log.claim(txn, &k, &open(Reason::Failover, epoch(1, 1)), seal, 50)
    });
    assert_eq!(
        entry.header().epoch,
        epoch(3, 3),
        "above the sealed-to epoch"
    );
    // The target's late open forks off the seal and stays divergent; the seal is on the line.
    let target = Node::new(2);
    source.push_to(&target, &view);
    let k2 = target.key();
    target.with(&view, |log, txn| {
        log.claim(txn, &k2, &open(Reason::Move, epoch(1, 1)), seal, 60)
    });
    target.push_to(&candidate, &view);
    candidate.push_to(&target, &view);
    for node in [&candidate, &target] {
        let r = node.record(&view);
        assert_eq!(r.epoch, epoch(3, 3));
        assert_eq!(r.state, PlacementState::Active);
        let segments = node
            .store
            .read(|txn| txn.segments(&log()))
            .expect("segments");
        let late = segments
            .iter()
            .find(|s| s.epoch == epoch(2, 2))
            .expect("late segment");
        assert_eq!(late.state, SegmentState::Divergent);
    }
}

#[test]
fn a_move_racing_a_failover_resolves_by_epoch_order_everywhere() {
    let view = members().view();
    let (source, last) = source_with_history(&view);
    let seal = seal_to_n2(&source, &view, last);
    let target = Node::new(2);
    let candidate = Node::new(3);
    source.push_to(&target, &view);
    source.push_to(&candidate, &view);
    // The target opens its move; meanwhile the candidate, partitioned, claims from before the
    // seal at the same n.
    let k2 = target.key();
    target.with(&view, |log, txn| {
        log.claim(txn, &k2, &open(Reason::Move, epoch(1, 1)), seal, 70)
    });
    let k3 = candidate.key();
    candidate.with(&view, |log, txn| {
        log.claim(txn, &k3, &open(Reason::Takeover, epoch(1, 1)), last, 70)
    });
    target.push_to(&candidate, &view);
    candidate.push_to(&target, &view);
    let winner = if node(2) > node(3) {
        epoch(2, 2)
    } else {
        epoch(2, 3)
    };
    let a = target.record(&view);
    let b = candidate.record(&view);
    assert_eq!(a.epoch, winner);
    assert_eq!(b.epoch, winner);
    assert_eq!(a.tip, b.tip);
    let holders: Vec<bool> = [(&target, a), (&candidate, b)]
        .iter()
        .map(|(n, r)| r.may_append(&n.id()))
        .collect();
    assert_eq!(
        holders.iter().filter(|h| **h).count(),
        1,
        "exactly one holder"
    );
}
