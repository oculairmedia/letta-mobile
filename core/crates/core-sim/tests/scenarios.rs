//! The eighteen C1 §9.3 simulator scenarios, one test each (the move crash matrix runs its nine
//! points inside one test). Every step is followed by the safety check; `quiesce` lets the network
//! settle and checks the convergence invariants per component.

use std::collections::BTreeSet;

use core_log::payload::kind;
use core_log::prune::FeedRetention;
use core_log::{Payload, Reason};
use core_membership::{CapSet, Preset};
use core_placement::feed::{
    CursorVerdict, FeedCursor, JournalView, ResyncReason, classify, continuity_epoch, cursor_line,
    feed_epoch,
};
use core_proto::Epoch;
use core_sim::model::{LOG_ALPN, PLACE_ALPN};
use core_sim::{MovePoint, NodeSpec, Policy, Refusal, Sim, run};
use core_store::{EntryHash, PayloadState, PlacementState, Store};

const AGENT: &str = "agent-alpha";
const CONV: &str = "conv-1";
const MIN: u64 = 60_000;
const HOUR: u64 = 60 * MIN;

fn desktops(n: u8) -> Vec<NodeSpec> {
    (1..=n).map(NodeSpec::desktop).collect()
}

fn desktop_caps() -> CapSet {
    Preset::DesktopNode.capabilities()
}

/// Founder 1 adds every other node as a desktop node and sets `policy`.
fn members(sim: &Sim, others: &[u8], policy: &Policy) {
    for n in others {
        sim.add(1, *n, desktop_caps()).expect("add");
    }
    sim.policy(1, policy).expect("policy");
}

fn manual() -> Policy {
    Policy {
        manual: true,
        ..Policy::default()
    }
}

fn fast() -> Policy {
    Policy::auto(MIN, 10_000)
}

fn line_hashes(sim: &Sim, node: u8) -> Vec<EntryHash> {
    sim.analysis(node, AGENT)
        .line
        .iter()
        .map(|e| e.entry_hash)
        .collect()
}

fn canonical_epoch(sim: &Sim, node: u8) -> Option<Epoch> {
    sim.record(node, AGENT).map(|r| r.epoch)
}

/// The epoch whose feed epoch `node` serves (C1 §7.3).
fn continuity(sim: &Sim, node: u8) -> Epoch {
    let line = sim.analysis(node, AGENT).line;
    sim.with_store(node, |store| store.read(|txn| continuity_epoch(&line, txn)))
        .expect("read")
        .expect("a continuity epoch")
}

fn opens(sim: &Sim, node: u8, reason: Reason) -> Vec<core_log::EpochOpen> {
    let analysis = sim.analysis(node, AGENT);
    let mut out = Vec::new();
    for segment in &analysis.segments {
        let entries = sim.with_store(node, |s| {
            s.read(|t| t.segment_entries(&Sim::log_id(AGENT), &segment.epoch, 0, usize::MAX))
                .expect("read")
        });
        for entry in entries.iter().filter(|e| e.kind == kind::EPOCH_OPEN) {
            if let Some(Payload::EpochOpen(open)) = sim.payload_of(node, entry)
                && open.reason == reason
            {
                out.push(open);
            }
        }
    }
    out
}

fn divergence_payloads(sim: &Sim, node: u8) -> Vec<core_log::payload::DivergencePayload> {
    sim.canonical_payloads(node, AGENT)
        .into_iter()
        .filter_map(|(_, p)| match p {
            Some(Payload::Divergence(d)) => Some(*d),
            _ => None,
        })
        .collect()
}

#[test]
fn s01_join_and_replicate() {
    run(1, &desktops(2), 1, |sim| async move {
        // Before it is added, node 2 is refused on the log and placement ALPNs (M8).
        sim.advance(2_000).await;
        assert!(!sim.admitted(2, 1, LOG_ALPN).await);
        assert!(!sim.admitted(2, 1, PLACE_ALPN).await);
        members(&sim, &[2], &Policy::default());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 5).expect("turn");
        sim.advance(5_000).await;
        assert!(sim.admitted(2, 1, LOG_ALPN).await);
        assert_eq!(sim.entry_count(2, AGENT), sim.entry_count(1, AGENT));
        // The live tail.
        for _ in 0..5 {
            sim.turn(1, AGENT, CONV, 3).expect("turn");
            sim.advance(500).await;
        }
        sim.quiesce().await;
        assert_eq!(sim.entry_count(2, AGENT), 1 + 5 * 2 + 5 * 3 * 2);
        assert_eq!(sim.view(1), sim.view(2));
    });
}

#[test]
fn s02_catch_up_after_a_day() {
    run(2, &desktops(2), 1, |sim| async move {
        members(&sim, &[2], &manual());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.advance(2_000).await;
        sim.crash(2);
        // 2,000 entries over 24 virtual hours while node 2 is offline.
        for _ in 0..20 {
            sim.turn(1, AGENT, CONV, 50).expect("turn");
            sim.advance(HOUR + 12 * MIN).await;
        }
        assert_eq!(sim.entry_count(1, AGENT), 1 + 2_000);
        sim.restart(2);
        sim.quiesce().await;
        assert_eq!(
            line_hashes(&sim, 2),
            line_hashes(&sim, 1),
            "no gap, no duplicate"
        );
        assert!(sim.rejected(2).is_empty());
    });
}

#[test]
fn s03_kill_holder_mid_turn() {
    run(3, &desktops(3), 1, |sim| async move {
        members(&sim, &[2, 3], &fast());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 4).expect("turn");
        sim.tool_call(1, AGENT, CONV, "tool-1", false)
            .expect("tool call");
        sim.advance(3_000).await;
        let acked = line_hashes(&sim, 1);
        sim.crash(1);
        sim.advance(3 * MIN).await;
        let holders = sim.holders(AGENT);
        assert_eq!(holders.len(), 1, "one holder after failover: {holders:?}");
        let holder = holders[0];
        assert_ne!(holder, 1);
        // L7: every entry the old holder had acked is on every replica's canonical line.
        for node in [2, 3] {
            let line = line_hashes(&sim, node);
            assert_eq!(&line[..acked.len()], &acked[..]);
        }
        // D2: the failover epoch_open lists the tool call that started and never finished.
        let failovers = opens(&sim, holder, Reason::Failover);
        assert_eq!(failovers.len(), 1);
        assert!(
            failovers[0]
                .uncertain
                .iter()
                .any(|a| a.idempotency_key == "tool-1")
        );
        sim.restart(1);
        sim.quiesce().await;
        assert_eq!(sim.holders(AGENT), vec![holder]);
    });
}

#[test]
fn s04_move_clean() {
    run(4, &desktops(2), 1, |sim| async move {
        members(&sim, &[2], &manual());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 3).expect("turn");
        // A memory edit made just before the move.
        let edit: &[u8] = b"{\"memory\":\"uncommitted edit\"}";
        sim.file_put(1, AGENT, &[("agents/agent-alpha.json", edit)])
            .expect("edit");
        sim.advance(2_000).await;
        let before = line_hashes(&sim, 1);
        sim.move_agent(1, AGENT, 2).expect("move");
        sim.advance(10_000).await;
        assert_eq!(sim.holders(AGENT), vec![2]);
        // The edit is in the target's store on its first turn.
        assert_eq!(
            sim.materialize(2, AGENT).file("agents/agent-alpha.json"),
            Some(edit)
        );
        sim.turn(2, AGENT, CONV, 2)
            .expect("first turn at the target");
        assert_eq!(
            sim.turn(1, AGENT, CONV, 1),
            Err(Refusal::Gate("not_holder"))
        );
        // D3: a move keeps the feed epoch.
        assert_eq!(continuity(&sim, 2).n, 1);
        sim.quiesce().await;
        let line = line_hashes(&sim, 1);
        assert_eq!(&line[..before.len()], &before[..]);
    });
}

#[test]
fn s05_move_crash_matrix() {
    for (i, point) in MovePoint::ALL.into_iter().enumerate() {
        run(50 + i as u64, &desktops(2), 1, |sim| async move {
            members(&sim, &[2], &manual());
            sim.create_agent(1, AGENT).expect("genesis");
            sim.turn(1, AGENT, CONV, 5).expect("turn");
            sim.advance(2_000).await;
            let before = line_hashes(&sim, 1);
            let crashed = if point.on_source() { 1 } else { 2 };
            sim.crash_at(crashed, point);
            sim.move_agent(1, AGENT, 2).expect("move");
            sim.advance(20_000).await;
            assert!(!sim.is_up(crashed), "{point:?} fired");
            sim.restart(crashed);
            // Past the top-up timeout, so a source stuck waiting for `verified` aborts.
            sim.advance(11 * MIN).await;
            sim.quiesce().await;
            let expected = if point < MovePoint::SourceAfterSeal {
                1
            } else {
                2
            };
            assert_eq!(sim.holders(AGENT), vec![expected], "{point:?}");
            // P4: the post-move canonical line holds every entry from before the seal.
            for node in [1, 2] {
                let line: BTreeSet<EntryHash> = line_hashes(&sim, node).into_iter().collect();
                assert!(before.iter().all(|h| line.contains(h)), "{point:?}");
            }
            // One holder, and it takes turns; nothing is doubled.
            sim.turn(expected, AGENT, CONV, 2)
                .expect("turn after the move");
            sim.quiesce().await;
        });
    }
}

#[test]
fn s06_move_verify_mismatch() {
    run(6, &desktops(2), 1, |sim| async move {
        members(&sim, &[2], &manual());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 4).expect("turn");
        sim.advance(2_000).await;
        sim.corrupt_materialize(2, true);
        let record = sim.record(1, AGENT).expect("record");
        let count = sim.entry_count(1, AGENT);
        sim.move_agent(1, AGENT, 2).expect("move");
        sim.advance(10_000).await;
        // P5: the source still holds at the same epoch; only the checkpoint was added.
        let after = sim.record(1, AGENT).expect("record");
        assert_eq!(after.epoch, record.epoch);
        assert_eq!(after.state, PlacementState::Active);
        assert_eq!(sim.holders(AGENT), vec![1]);
        assert_eq!(sim.entry_count(1, AGENT), count + 1);
        let last = sim.analysis(1, AGENT).line.last().cloned().expect("tip");
        assert_eq!(last.kind, kind::CHECKPOINT);
        sim.turn(1, AGENT, CONV, 1)
            .expect("the source takes turns again");
        sim.quiesce().await;
    });
}

#[test]
fn s07_partition_both_run_unequal_n() {
    run(7, &desktops(2), 1, |sim| async move {
        members(&sim, &[2], &fast());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 2).expect("turn");
        sim.advance(2_000).await;
        sim.partition(&[&[1], &[2]]);
        sim.turn(1, AGENT, CONV, 3)
            .expect("the old holder keeps going");
        sim.advance(3 * MIN).await;
        // P1: both pass the gate, at different epochs, only while partitioned.
        assert_eq!(sim.holders(AGENT), vec![1, 2]);
        sim.turn(2, AGENT, CONV, 2).expect("turn by failover");
        sim.turn(1, AGENT, CONV, 1).expect("turn in the old epoch");
        sim.heal();
        sim.quiesce().await;
        assert_eq!(sim.holders(AGENT), vec![2]);
        // D1: both continuations exist: the canonical one, and node 1's tail as a divergence.
        for node in [1, 2] {
            let analysis = sim.analysis(node, AGENT);
            assert_eq!(analysis.divergences.len(), 1);
            assert_eq!(analysis.divergences[0].tail.len(), 3 * 2 + 2);
        }
        assert_eq!(divergence_payloads(&sim, 1).len(), 1);
    });
}

#[test]
fn s07_partition_both_run_equal_n() {
    run(71, &desktops(3), 1, |sim| async move {
        members(&sim, &[2, 3], &fast());
        sim.advance(2_000).await;
        sim.create_agent(3, AGENT).expect("genesis");
        sim.turn(3, AGENT, CONV, 2).expect("turn");
        sim.advance(2_000).await;
        sim.crash(3);
        sim.partition(&[&[1], &[2]]);
        sim.advance(3 * MIN).await;
        assert_eq!(sim.holders(AGENT), vec![1, 2]);
        sim.turn(1, AGENT, CONV, 2).expect("turn");
        sim.turn(2, AGENT, CONV, 2).expect("turn");
        sim.heal();
        sim.quiesce().await;
        // P6: equal n resolves to the higher claimant id on every node.
        let winner = if sim.id(1) > sim.id(2) { 1 } else { 2 };
        assert_eq!(sim.holders(AGENT), vec![winner]);
        for node in [1, 2] {
            assert_eq!(
                canonical_epoch(&sim, node),
                Some(Epoch {
                    n: 2,
                    claimant: sim.id(winner)
                })
            );
            let analysis = sim.analysis(node, AGENT);
            assert_eq!(analysis.divergences.len(), 1);
            let inputs = &analysis.divergences[0].inputs;
            assert_eq!(inputs.stranded_epoch.n, inputs.winning_epoch.n);
        }
    });
}

#[test]
fn s08_simultaneous_claims() {
    run(8, &desktops(4), 1, |sim| async move {
        members(&sim, &[2, 3, 4], &Policy::auto(MIN, 0));
        sim.advance(2_000).await;
        sim.create_agent(4, AGENT).expect("genesis");
        sim.turn(4, AGENT, CONV, 2).expect("turn");
        sim.advance(2_000).await;
        sim.crash(4);
        sim.advance(3 * MIN).await;
        sim.quiesce().await;
        // P6: three claims at one n; the highest claimant id wins everywhere.
        let winner = [1, 2, 3]
            .into_iter()
            .max_by_key(|n| sim.id(*n))
            .expect("a winner");
        assert_eq!(sim.holders(AGENT), vec![winner]);
        let claimed: usize = [1, 2, 3]
            .iter()
            .map(|n| opens(&sim, *n, Reason::Failover).len())
            .max()
            .unwrap_or(0);
        assert_eq!(claimed, 3, "every candidate claimed at once");
        for node in [1, 2, 3] {
            assert_eq!(sim.analysis(node, AGENT).divergences.len(), 2);
        }
    });
}

#[test]
fn s09_quorum_3_voters() {
    let specs = [
        NodeSpec::desktop(1),
        NodeSpec::always_on(2),
        NodeSpec::always_on(3),
        NodeSpec::always_on(4),
    ];
    run(9, &specs, 1, |sim| async move {
        for n in [2, 3, 4] {
            sim.add(1, n, Preset::AlwaysOnLinux.capabilities())
                .expect("add");
        }
        sim.policy(
            1,
            &Policy {
                quorum: Some((vec![2, 3, 4], Some(2))),
                self_fence_ms: Some(30_000),
                ..fast()
            },
        )
        .expect("policy");
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 2).expect("turn");
        sim.advance(2_000).await;
        // The holder is isolated with one voter.
        sim.partition(&[&[1, 2], &[3, 4]]);
        sim.advance(40_000).await;
        assert_eq!(
            sim.turn(1, AGENT, CONV, 1),
            Err(Refusal::SelfFenced),
            "the isolated holder self-fences"
        );
        sim.advance(3 * MIN).await;
        let majority: Vec<u8> = sim
            .holders(AGENT)
            .into_iter()
            .filter(|n| [3, 4].contains(n))
            .collect();
        assert_eq!(majority.len(), 1, "the majority side failed over");
        let holder = majority[0];
        sim.heal();
        sim.quiesce().await;
        assert_eq!(sim.holders(AGENT), vec![holder]);
        // P7: one certified failover claim.
        let certified: Vec<_> = opens(&sim, holder, Reason::Failover)
            .into_iter()
            .filter(|o| o.votes.len() >= 2)
            .collect();
        assert_eq!(certified.len(), 1);
    });
}

#[test]
fn s10_revoke_stolen_holder() {
    run(10, &desktops(3), 1, |sim| async move {
        members(&sim, &[2, 3], &fast());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 2).expect("turn");
        sim.advance(2_000).await;
        sim.move_agent(1, AGENT, 2).expect("move");
        sim.advance(10_000).await;
        assert_eq!(sim.holders(AGENT), vec![2]);
        sim.turn(2, AGENT, CONV, 2).expect("turn at the new holder");
        sim.advance(2_000).await;
        let e2 = canonical_epoch(&sim, 1).expect("epoch");
        sim.partition(&[&[1, 3], &[2]]);
        sim.revoke(1, 2).expect("revoke");
        sim.advance(2_000).await;
        // P8: the revoked claimant's tip loses validity; the canonical segment falls back to the
        // highest valid one below it. A higher n exists but is invalid, so nobody holds the agent
        // (superseded_unknown, C1 §5.3) and no automatic failover fires: an admin takes over.
        for node in [1, 3] {
            let record = sim.record(node, AGENT).expect("record");
            assert_eq!(record.epoch.n, 1);
            assert_eq!(record.max_n_seen, e2.n);
            assert_eq!(record.state, PlacementState::SupersededUnknown);
        }
        sim.turn(2, AGENT, CONV, 3)
            .expect("the stolen holder does not know yet");
        sim.advance(3 * MIN).await;
        assert!(sim.holders(AGENT).iter().all(|n| *n == 2));
        sim.claim(3, AGENT, Reason::Takeover).expect("takeover");
        sim.advance(5_000).await;
        let holders: Vec<u8> = sim.holders(AGENT).into_iter().filter(|n| *n != 2).collect();
        assert_eq!(holders.len(), 1);
        let holder = holders[0];
        assert_eq!(canonical_epoch(&sim, holder).map(|e| e.n), Some(3));
        let recorded = divergence_payloads(&sim, holder);
        assert!(recorded.iter().any(|d| d.from_revoked));
        sim.heal();
        sim.advance(10_000).await;
        assert!(
            !sim.admitted(2, 1, LOG_ALPN).await,
            "M8: refused after the revoke"
        );
        assert!(sim.holders(AGENT).iter().all(|n| *n != 2));
        sim.quiesce().await;
    });
}

#[test]
fn s11_restore_from_backup() {
    run(11, &desktops(2), 1, |sim| async move {
        members(&sim, &[2], &manual());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 25).expect("turn");
        sim.advance(2_000).await;
        let backup = sim.backup(1);
        sim.turn(1, AGENT, CONV, 25).expect("turn");
        sim.advance(3_000).await;
        assert_eq!(sim.entry_count(2, AGENT), 101);
        sim.crash(1);
        sim.restore(1, backup);
        sim.partition(&[&[1], &[2]]);
        sim.restart(1);
        sim.advance(12_000).await;
        // No peer answered the startup probe; resume_alone lets it write at its old head.
        sim.turn(1, AGENT, CONV, 5).expect("turn after the restore");
        sim.heal();
        sim.quiesce().await;
        // L9: the equivocation is recorded with one id on both nodes.
        let ids = |node: u8| -> BTreeSet<_> {
            sim.analysis(node, AGENT)
                .divergences
                .iter()
                .filter(|d| d.inputs.equivocation)
                .map(|d| d.inputs.id())
                .collect()
        };
        assert_eq!(ids(1).len(), 1);
        assert_eq!(ids(1), ids(2));
        assert_eq!(sim.holders(AGENT), vec![1]);
        sim.turn(1, AGENT, CONV, 1)
            .expect("the holder keeps writing");
        sim.quiesce().await;
    });
}

#[test]
fn s12_phone_only_candidate() {
    let specs = [NodeSpec::desktop(1), NodeSpec::phone(2)];
    // P9: a phone is never an automatic candidate unless allowed.
    run(12, &specs, 1, |sim| async move {
        members(&sim, &[2], &fast());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.advance(2_000).await;
        sim.crash(1);
        sim.advance(10 * MIN).await;
        let record = sim.record(2, AGENT).expect("record");
        assert_eq!(record.epoch.n, 1);
        assert_eq!(record.holder(), Some(sim.id(1)));
        assert!(opens(&sim, 2, Reason::Failover).is_empty());
    });
    // ... and never in manual mode.
    run(121, &desktops(2), 1, |sim| async move {
        members(&sim, &[2], &manual());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.advance(2_000).await;
        sim.crash(1);
        sim.advance(10 * MIN).await;
        assert_eq!(sim.record(2, AGENT).expect("record").epoch.n, 1);
    });
    // With allow_phone_auto the phone fails over.
    run(122, &specs, 1, |sim| async move {
        members(
            &sim,
            &[2],
            &Policy {
                allow_phone_auto: true,
                ..fast()
            },
        );
        sim.create_agent(1, AGENT).expect("genesis");
        sim.advance(2_000).await;
        sim.crash(1);
        sim.advance(3 * MIN).await;
        assert_eq!(sim.holders(AGENT), vec![2]);
    });
}

/// A feed v1 client following one conversation (C1 §7).
#[derive(Default)]
struct Client {
    cursor: Option<FeedCursor>,
    seen: Vec<u64>,
    resyncs: Vec<ResyncReason>,
    lagging: u32,
}

impl Client {
    fn read(&mut self, sim: &Sim, node: u8) {
        let analysis = sim.analysis(node, AGENT);
        let epoch = continuity(sim, node);
        let feed: Vec<(u64, EntryHash)> = analysis
            .line
            .iter()
            .filter(|e| e.kind == kind::FEED_APPEND)
            .filter_map(|e| match sim.payload_of(node, e) {
                Some(Payload::FeedAppend {
                    conversation_id,
                    feed_seq,
                    ..
                }) if conversation_id.as_str() == CONV => Some((feed_seq, e.entry_hash)),
                _ => None,
            })
            .collect();
        let head = feed.last().map_or(0, |(s, _)| *s);
        let line_at_cursor = self.cursor.as_ref().and_then(|c| {
            feed.iter()
                .find(|(s, _)| *s == c.seq)
                .map(|(_, h)| cursor_line(h))
        });
        let journal = JournalView {
            feed_epoch: feed_epoch(&epoch),
            base_seq: 0,
            head_seq: head,
            line_at_cursor,
            is_holder: sim.holders(AGENT).contains(&node),
        };
        let verdict = classify(self.cursor.as_ref(), &journal);
        let jump = |seq: u64| FeedCursor {
            epoch: journal.feed_epoch,
            seq,
            line: feed
                .iter()
                .find(|(s, _)| *s == seq)
                .map(|(_, h)| cursor_line(h)),
        };
        match verdict {
            CursorVerdict::Replay => {
                let from = self.cursor.as_ref().map_or(0, |c| c.seq);
                for (seq, _) in feed.iter().filter(|(s, _)| *s > from) {
                    let expected = self.seen.last().map_or(1, |s| s + 1);
                    assert_eq!(*seq, expected, "no gap, no duplicate");
                    self.seen.push(*seq);
                }
                self.cursor = Some(jump(head));
            }
            CursorVerdict::Lagging => self.lagging += 1,
            CursorVerdict::Snapshot(reason) => {
                if reason != ResyncReason::NoCursor {
                    self.resyncs.push(reason);
                }
                self.seen = feed.iter().map(|(s, _)| *s).collect();
                self.cursor = Some(jump(head));
            }
        }
    }
}

#[test]
fn s13_feed_continuity() {
    run(13, &desktops(3), 1, |sim| async move {
        members(&sim, &[2, 3], &fast());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 3).expect("turn");
        sim.advance(2_000).await;
        let mut client = Client::default();
        client.read(&sim, 3);
        // A move A to B: no resync.
        sim.move_agent(1, AGENT, 2).expect("move");
        sim.advance(10_000).await;
        sim.turn(2, AGENT, CONV, 3).expect("turn at B");
        sim.advance(2_000).await;
        client.read(&sim, 3);
        assert!(client.resyncs.is_empty(), "a move never resyncs");
        // A failover B to A: one epoch_changed resync.
        sim.crash(2);
        sim.advance(3 * MIN).await;
        assert_eq!(sim.holders(AGENT), vec![1]);
        sim.turn(1, AGENT, CONV, 3).expect("turn after failover");
        sim.advance(2_000).await;
        client.read(&sim, 3);
        assert_eq!(client.resyncs, vec![ResyncReason::EpochChanged]);
        // B comes back, catches up, then lags behind while the client switches C to B.
        sim.restart(2);
        sim.advance(15_000).await;
        sim.partition(&[&[1, 3], &[2]]);
        sim.turn(1, AGENT, CONV, 2).expect("turn");
        sim.advance(2_000).await;
        client.read(&sim, 3);
        client.read(&sim, 2);
        assert_eq!(client.lagging, 1, "the lagging replica answers lagging");
        sim.heal();
        sim.quiesce().await;
        client.read(&sim, 2);
        assert_eq!(client.resyncs, vec![ResyncReason::EpochChanged]);
        assert_eq!(client.seen.last().copied(), Some(3 + 3 + 3 + 2));
    });
}

#[test]
fn s14_backpressure() {
    let specs = [NodeSpec::desktop(1), NodeSpec::phone(2)];
    run(14, &specs, 1, |sim| async move {
        members(&sim, &[2], &manual());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.advance(2_000).await;
        // A slow phone: every frame takes 3 s; the burst is larger than the unacked budget.
        sim.delay(1, 2, 3_000);
        for _ in 0..10 {
            sim.turn(1, AGENT, CONV, 100).expect("turn");
        }
        sim.advance(2 * MIN).await;
        sim.delay(1, 2, 0);
        sim.quiesce().await;
        assert_eq!(
            line_hashes(&sim, 2),
            line_hashes(&sim, 1),
            "L5: no gap, no duplicate"
        );
        assert!(sim.rejected(2).is_empty());
    });
}

#[test]
fn s15_compaction() {
    run(15, &desktops(3), 1, |sim| async move {
        members(&sim, &[2], &manual());
        sim.create_agent(1, AGENT).expect("genesis");
        for _ in 0..50 {
            sim.turn(1, AGENT, CONV, 100).expect("turn");
        }
        for round in 0..20 {
            let records: Vec<String> = (0..10)
                .map(|i| format!("{{\"agent\":\"alpha\",\"rev\":{}}}", round * 10 + i))
                .collect();
            let files: Vec<(&str, &[u8])> = records
                .iter()
                .map(|r| ("agents/agent-alpha.json", r.as_bytes()))
                .collect();
            sim.file_put(1, AGENT, &files).expect("record rewrite");
        }
        sim.checkpoint(1, AGENT).expect("checkpoint");
        sim.advance(10_000).await;
        sim.quiesce().await;
        let manifests = |sim: &Sim| -> Vec<_> {
            [1, 2]
                .iter()
                .map(|n| sim.materialize(*n, AGENT).manifest())
                .collect()
        };
        let before = manifests(&sim);
        let retention = FeedRetention {
            max_entries: 1_000,
            max_bytes: 4 * 1024 * 1024,
        };
        for node in [1, 2] {
            let pruned = sim.compact(node, AGENT, retention).expect("prune");
            assert!(pruned.len() > 4_000, "node {node} pruned {}", pruned.len());
        }
        // L11: compaction prunes only compactable payloads and keeps the materialized manifest.
        assert_eq!(manifests(&sim), before);
        for node in [1, 2] {
            for entry in sim.analysis(node, AGENT).line {
                if entry.payload_state == PayloadState::Pruned {
                    assert!(entry.is_compactable());
                }
            }
        }
        // A node joining after compaction catches up from the pruned log.
        sim.add(1, 3, desktop_caps()).expect("add");
        sim.turn(1, AGENT, CONV, 5).expect("turn after compaction");
        sim.quiesce().await;
        assert_eq!(
            sim.materialize(3, AGENT).manifest(),
            sim.materialize(1, AGENT).manifest()
        );
    });
}

#[test]
fn s16_idle() {
    run(16, &desktops(3), 1, |sim| async move {
        members(&sim, &[2, 3], &fast());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 2).expect("turn");
        sim.quiesce().await;
        let counts: Vec<usize> = (1..=3).map(|n| sim.entry_count(n, AGENT)).collect();
        let frames: Vec<_> = (1..=3).map(|n| sim.frames(n)).collect();
        sim.advance(24 * HOUR).await;
        // D4: zero entries, and no frames other than pings.
        assert_eq!(
            (1..=3)
                .map(|n| sim.entry_count(n, AGENT))
                .collect::<Vec<_>>(),
            counts
        );
        for n in 1..=3 {
            let now = sim.frames(n);
            let then = frames[usize::from(n - 1)];
            assert_eq!(
                now.other, then.other,
                "node {n} sent non-ping frames while idle"
            );
            assert!(now.pings > then.pings, "the sessions stay alive with pings");
        }
        assert_eq!(sim.holders(AGENT), vec![1]);
    });
}

#[test]
fn s17_subagent_moves_with_dispatcher() {
    run(17, &desktops(3), 1, |sim| async move {
        members(&sim, &[2, 3], &fast());
        sim.create_agent(1, AGENT).expect("genesis");
        // The dispatcher runs a subagent: its conversation is written to the dispatcher's log.
        sim.turn(1, AGENT, "sub-conv-1", 2).expect("subagent turn");
        sim.advance(2_000).await;
        sim.move_agent(1, AGENT, 2).expect("move");
        sim.advance(10_000).await;
        sim.turn(2, AGENT, "sub-conv-1", 2)
            .expect("the subagent runs on the new holder");
        assert_eq!(
            sim.turn(1, AGENT, "sub-conv-1", 1),
            Err(Refusal::Gate("not_holder"))
        );
        sim.advance(2_000).await;
        sim.crash(2);
        sim.advance(3 * MIN).await;
        let holder = sim.holders(AGENT)[0];
        sim.turn(holder, AGENT, "sub-conv-1", 2)
            .expect("and on the failover holder");
        sim.restart(2);
        sim.quiesce().await;
        // D6: one log per node, the dispatcher's; the subagent's file has every line in order.
        for node in 1..=3 {
            let logs = sim.with_store(node, |s| s.read(|t| t.logs()).expect("logs"));
            assert_eq!(logs, vec![Sim::log_id(AGENT)]);
            let file = sim
                .materialize(node, AGENT)
                .file("conversations/sub-conv-1/messages.jsonl")
                .map(<[u8]>::to_vec)
                .expect("the subagent's conversation");
            let text = String::from_utf8(file).expect("utf-8");
            assert_eq!(text.lines().count(), 6);
        }
    });
}

#[test]
fn s18_divergence_both_sides_write_catalog() {
    run(18, &desktops(3), 1, |sim| async move {
        members(&sim, &[2, 3], &fast());
        sim.create_agent(1, AGENT).expect("genesis");
        sim.turn(1, AGENT, CONV, 2).expect("turn");
        sim.advance(2_000).await;
        sim.partition(&[&[1], &[2, 3]]);
        sim.turn(1, AGENT, CONV, 2).expect("old side");
        sim.advance(3 * MIN).await;
        let holder = sim
            .holders(AGENT)
            .into_iter()
            .find(|n| *n != 1)
            .expect("a failover holder");
        sim.turn(holder, AGENT, CONV, 2).expect("new side");
        sim.heal();
        // Both sides detect the divergence from their own computation before the holder's
        // divergence entry is required: every node computes the same catalog node id and inputs.
        sim.advance(3_000).await;
        let computed: Vec<_> = (1..=3)
            .map(|n| {
                let analysis = sim.analysis(n, AGENT);
                assert_eq!(analysis.divergences.len(), 1, "node {n} detected it");
                let inputs = analysis.divergences[0].inputs.clone();
                let node_id = inputs.catalog_node_id(&core_proto::ConversationId::new(CONV));
                (inputs, node_id)
            })
            .collect();
        assert!(computed.windows(2).all(|w| w[0] == w[1]), "D5");
        sim.quiesce().await;
        let recorded = divergence_payloads(&sim, holder);
        assert_eq!(recorded.len(), 1);
        assert_eq!(recorded[0].conversations.len(), 1);
        assert_eq!(recorded[0].conversations[0].catalog_node_id, computed[0].1);
        // The losing tail is kept on every node, never discarded.
        for node in 1..=3 {
            let analysis = sim.analysis(node, AGENT);
            assert_eq!(analysis.divergences[0].tail.len(), 2 * 2);
            for hash in &analysis.divergences[0].tail {
                let stored = sim.with_store(node, |s| s.read(|t| t.entry(hash)).expect("read"));
                assert!(stored.is_some());
            }
        }
    });
}

#[test]
fn the_same_seed_and_steps_give_the_same_run() {
    let digest = || {
        let out = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let sink = std::rc::Rc::clone(&out);
        run(99, &desktops(3), 1, |sim| async move {
            members(&sim, &[2, 3], &fast());
            sim.create_agent(1, AGENT).expect("genesis");
            sim.drop_rate(20_000);
            for i in 0..6 {
                let _ = sim.turn(1, AGENT, CONV, 3);
                sim.advance(1_500 + i * 700).await;
            }
            sim.partition(&[&[1], &[2, 3]]);
            sim.advance(3 * MIN).await;
            sim.heal();
            sim.drop_rate(0);
            sim.quiesce().await;
            *sink.borrow_mut() = sim.digest();
        });
        out.take()
    };
    assert_eq!(digest(), digest());
}
