//! Property tests for the log invariants (C1 §8): replicas converge to one canonical chain whatever
//! the delivery order (L2, L4, P2), divergence is detected identically on every replica (L9, D5),
//! replication through `baken/log/1` sessions converges with no gap (L4, L5), and a pruned payload
//! never breaks verification (L3, L11).

mod common;

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use common::{
    Built, Replica, agent, append_payload, build, epoch, feed_payload, genesis_payload, key, log,
    node, open_payload,
};
use core_log::fold::StateFold;
use core_log::payload::{Checkpoint, Payload};
use core_log::prune::{FeedRetention, prune, verify_log};
use core_log::wire::Message;
use core_log::{
    AgentLog, Analysis, LogError, Mode, Reason, Session, SessionConfig, SessionEvent,
    StructuralJudge,
};
use core_store::{
    DivergenceRecord, EntryHash, MemoryStore, PayloadState, Placement, Store, StoreError,
};
use proptest::prelude::*;

/// One generated step: extend an existing entry, or claim a new epoch from it.
#[derive(Clone, Debug)]
struct Step {
    claim: bool,
    pick: usize,
    claimant: u8,
    bump: u64,
}

fn steps() -> impl Strategy<Value = Vec<Step>> {
    prop::collection::vec(
        (
            prop::bool::weighted(0.25),
            any::<usize>(),
            1..=3_u8,
            1..=2_u64,
        )
            .prop_map(|(claim, pick, claimant, bump)| Step {
                claim,
                pick,
                claimant,
                bump,
            }),
        1..28,
    )
}

/// Builds a tree of valid signed entries: plain entries extend their parent's epoch (signed by its
/// claimant), claims open a higher epoch from any entry (failover).
fn universe(steps: &[Step]) -> Vec<Built> {
    let mut all = vec![build(1, 1, None, &genesis_payload(), 0)];
    let mut signer: Vec<u8> = vec![1];
    for (index, step) in steps.iter().enumerate() {
        let at = step.pick % all.len();
        let parent = all[at].clone();
        let ts = index as u64 + 1;
        let (k, built) = if step.claim {
            let n = parent.epoch().n + step.bump;
            let payload = open_payload(Reason::Failover, parent.epoch());
            (
                step.claimant,
                build(step.claimant, n, Some(&parent), &payload, ts),
            )
        } else {
            let k = signer[at];
            let payload = feed_payload("c1", ts, "t", "stream_delta");
            (k, build(k, parent.epoch().n, Some(&parent), &payload, ts))
        };
        signer.push(k);
        all.push(built);
    }
    all
}

fn shuffle<T: Clone>(items: &[T], seed: u64) -> Vec<T> {
    let mut state = seed.wrapping_mul(0x9e37_79b9_7f4a_7c15) | 1;
    let mut out = items.to_vec();
    for i in (1..out.len()).rev() {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        let j = usize::try_from(state % (i as u64 + 1)).expect("fits");
        out.swap(i, j);
    }
    out
}

/// Everything a replica concludes about the log.
#[derive(Debug, PartialEq, Eq)]
struct View {
    analysis: Analysis,
    placement: Option<Placement>,
    divergences: Vec<DivergenceRecord>,
    headers: BTreeSet<EntryHash>,
}

fn view(store: &MemoryStore) -> View {
    let judge = StructuralJudge;
    let analysis = store
        .read(|t| {
            AgentLog::new(log(), &judge)
                .analyze(t)
                .map_err(common::to_store)
        })
        .expect("analyze");
    let (placement, divergences, headers) = store
        .read(|t| {
            let mut headers = BTreeSet::new();
            for s in t.segments(&log())? {
                for e in t.segment_entries(&log(), &s.epoch, 0, usize::MAX)? {
                    headers.insert(e.entry_hash);
                }
            }
            Ok::<_, StoreError>((t.placement(&log())?, t.divergences(&log())?, headers))
        })
        .expect("read");
    View {
        analysis,
        placement,
        divergences,
        headers,
    }
}

fn check_line(store: &MemoryStore, view: &View) {
    // L2: contiguous from 0, each prev the hash before it.
    for (index, entry) in view.analysis.line.iter().enumerate() {
        assert_eq!(entry.seq, index as u64);
        if index > 0 {
            assert_eq!(entry.prev, view.analysis.line[index - 1].entry_hash);
        }
    }
    // L9: every off-line entry competing with a line entry at its seq is in a divergence tail.
    let on_line: BTreeSet<EntryHash> = view.analysis.line.iter().map(|e| e.entry_hash).collect();
    let in_tails: BTreeSet<EntryHash> = view
        .analysis
        .divergences
        .iter()
        .flat_map(|d| d.tail.iter().copied())
        .collect();
    for hash in view.headers.difference(&on_line) {
        let entry = store
            .read(|t| t.entry(hash))
            .expect("read")
            .expect("stored");
        let competes = entry.seq > 0
            && on_line.contains(&entry.prev)
            && usize::try_from(entry.seq).is_ok_and(|seq| seq < view.analysis.line.len());
        if competes {
            assert!(
                in_tails.contains(hash),
                "L9: competing entry recorded as a divergence"
            );
        }
    }
    for d in &view.analysis.divergences {
        let i = &d.inputs;
        assert!(i.competing[0] < i.competing[1]);
        assert!(on_line.contains(&i.fork.hash));
        assert!(i.competing.iter().any(|h| on_line.contains(h)));
        assert!(i.competing.iter().any(|h| d.tail.contains(h)));
        assert_eq!(i.equivocation, i.stranded_epoch == i.winning_epoch);
        assert!(
            d.tail.iter().all(|h| !on_line.contains(h)),
            "L10: tails never on the line"
        );
    }
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 32, ..ProptestConfig::default() })]

    /// L4, L9, D5, P2: three replicas receiving the same entries in different orders reach the
    /// same canonical line, placement, segment states and divergence inputs.
    #[test]
    fn replicas_converge_whatever_the_order(steps in steps(), seeds in any::<[u64; 3]>()) {
        let all = universe(&steps);
        let mut views = Vec::new();
        for seed in seeds {
            let mut replica = Replica::new();
            for built in shuffle(&all, seed) {
                replica.deliver(&built);
            }
            prop_assert_eq!(replica.pending_len(), 0);
            let v = view(&replica.store);
            check_line(&replica.store, &v);
            prop_assert_eq!(v.headers.len(), all.len(), "L3: every valid header is stored");
            views.push(v);
        }
        same(&views[0], &views[1])?;
        same(&views[1], &views[2])?;
    }

    /// L4, L5: replicas holding different subsets converge through `baken/log/1` sessions in a
    /// full mesh, with messages delivered in random interleavings and every frame through the codec.
    #[test]
    fn sessions_converge(steps in steps(), assignment in prop::collection::vec(0..8_u8, 30), seed in any::<u64>()) {
        let all = universe(&steps);
        let mut replicas: Vec<Replica> = (0..3).map(|_| Replica::new()).collect();
        for (index, built) in all.iter().enumerate() {
            let mask = assignment.get(index).copied().unwrap_or(7) | u8::from(index == 0);
            for (r, replica) in replicas.iter_mut().enumerate() {
                if mask & (1 << r) != 0 {
                    replica.deliver(built);
                }
            }
        }
        let initial: Vec<BTreeSet<EntryHash>> = replicas.iter().map(|r| view(&r.store).headers).collect();
        let stores: Vec<MemoryStore> = replicas.into_iter().map(|r| r.store).collect();
        run_mesh(&stores, seed);
        let views: Vec<View> = stores.iter().map(view).collect();
        let union: BTreeSet<EntryHash> = initial.iter().flatten().copied().collect();
        prop_assert_eq!(&views[0].headers, &union);
        same(&views[0], &views[1])?;
        same(&views[1], &views[2])?;
        check_line(&stores[0], &views[0]);
        // L6: complete_seq never exceeds what is held (all payloads were inline).
        for store in &stores {
            let segments = store.read(|t| t.segments(&log())).expect("segments");
            for s in segments {
                prop_assert!(s.complete_seq.is_none_or(|c| c <= s.head_seq));
            }
        }
    }

    /// L3, L11: pruning any mix of puts, appends and feed entries never breaks verification, never
    /// changes the materialized manifest, and a fresh replica syncing from the pruned store accepts
    /// the pruned markers and reaches the same tip and complete_seq.
    #[test]
    fn pruning_never_breaks_verification(ops in prop::collection::vec((0..4_u8, 0..3_u8), 1..40), keep in 0..4_usize) {
        let judge = StructuralJudge;
        let agent_log = AgentLog::for_agent(&agent(), &judge);
        let store = MemoryStore::new();
        write(&store, |t| agent_log.genesis(t, &key(1), match genesis_payload() { Payload::EpochOpen(o) => o, _ => unreachable!() }, 0).map(|_| ()));
        let mut fold = StateFold::new();
        let mut lengths: BTreeMap<u8, u64> = BTreeMap::new();
        let mut feed_seq = 0;
        for (index, (op, which)) in ops.iter().enumerate() {
            let payload = match op {
                0 => Payload::file_put(&format!("agents/a{which}.json"), format!("{index}").into_bytes(), false),
                1 => {
                    let offset = *lengths.get(which).unwrap_or(&0);
                    let text = format!("line {index}\n");
                    lengths.insert(*which, offset + text.len() as u64);
                    append_payload(&format!("c{which}"), offset, &text)
                }
                2 => {
                    feed_seq += 1;
                    feed_payload("c0", feed_seq, &format!("t{}", feed_seq / 2), if feed_seq % 2 == 0 { "turn_closed" } else { "turn_open" })
                }
                _ => Payload::Checkpoint(Checkpoint {
                    manifest: fold.manifest(),
                    refs: fold.refs(),
                    feed_heads: Vec::new(),
                    store_format: 1,
                    runtime_parity: "p".into(),
                    h1_violations: Vec::new(),
                }),
            };
            fold.apply(&payload).expect("applies");
            write(&store, |t| agent_log.append(t, &key(1), &payload, index as u64 + 1).map(|_| ()));
        }
        let before = fold.manifest();
        let pruned = store.write(|t| prune(&agent_log, t, FeedRetention { max_entries: keep, max_bytes: 1 << 20 }).map_err(common::to_store)).expect("prune");
        let report = store.read(|t| verify_log(t, &log()).map_err(common::to_store)).expect("verifies");
        prop_assert_eq!(report.pruned, pruned.len());
        let mut after = StateFold::new();
        for entry in store.read(|t| agent_log.canonical_line(t).map_err(common::to_store)).expect("line") {
            if entry.payload_state == PayloadState::Stored {
                let bytes = store.read(|t| t.blob(&entry.payload_hash)).expect("read").expect("blob");
                after.apply(&Payload::decode(entry.kind, &bytes).expect("decodes")).expect("applies after pruning");
            }
        }
        prop_assert_eq!(after.manifest(), before);
        // A fresh replica syncs from the pruned store.
        let fresh = MemoryStore::new();
        run_mesh(&[&store, &fresh], 7);
        let (source_view, fresh_view) = (view(&store), view(&fresh));
        prop_assert_eq!(&source_view.analysis.line.last().map(|e| e.entry_hash), &fresh_view.analysis.line.last().map(|e| e.entry_hash));
        let complete = |s: &MemoryStore| s.read(|t| t.segment(&log(), &epoch(1, 1))).expect("read").and_then(|s| s.complete_seq);
        prop_assert_eq!(complete(&store), complete(&fresh));
        let fresh_report = fresh.read(|t| verify_log(t, &log()).map_err(common::to_store)).expect("fresh verifies");
        prop_assert_eq!(fresh_report.pruned, pruned.len());
    }
}

/// Compares two views part by part, so a failure names the part that differs.
fn same(a: &View, b: &View) -> Result<(), TestCaseError> {
    let line = |v: &View| {
        v.analysis
            .line
            .iter()
            .map(|e| (e.seq, e.entry_hash, e.payload_state))
            .collect::<Vec<_>>()
    };
    prop_assert_eq!(&a.headers, &b.headers, "headers");
    prop_assert_eq!(a.analysis.canonical, b.analysis.canonical, "canonical");
    prop_assert_eq!(line(a), line(b), "line");
    let segs = |v: &View| {
        v.analysis
            .segments
            .iter()
            .map(|s| {
                format!(
                    "{}:{:02x} start {} parent {:?} head {} {:02x} complete {:?} {:?}",
                    s.epoch.n,
                    s.epoch.claimant.as_bytes()[0],
                    s.start_seq,
                    s.parent.map(|p| p.as_bytes()[0]),
                    s.head_seq,
                    s.head_hash.as_bytes()[0],
                    s.complete_seq,
                    s.state
                )
            })
            .collect::<Vec<_>>()
    };
    prop_assert_eq!(segs(a), segs(b), "segments");
    prop_assert_eq!(
        &a.analysis.divergences,
        &b.analysis.divergences,
        "divergences"
    );
    prop_assert_eq!(&a.placement, &b.placement, "placement");
    // Divergence records are an append-only history of what this replica observed (a record made
    // against an intermediate canonical line is kept); every current divergence is recorded with
    // its current tail head on every replica.
    for view in [a, b] {
        for d in &view.analysis.divergences {
            let record = view.divergences.iter().find(|r| r.id == d.inputs.id());
            prop_assert!(
                record.is_some_and(
                    |r| r.tail_head_seq == d.tail_head.seq && r.fork_seq == d.inputs.fork.seq
                ),
                "divergence recorded"
            );
        }
    }
    Ok(())
}

fn write(store: &MemoryStore, f: impl FnOnce(&mut dyn core_store::Txn) -> Result<(), LogError>) {
    store
        .write(|t| f(t).map_err(common::to_store))
        .expect("write");
}

/// Runs a full mesh of sessions over `stores` until no message is in flight. Messages pass
/// through the frame codec; the delivery order is shuffled by `seed`.
#[allow(clippy::needless_range_loop)]
fn run_mesh<S: std::borrow::Borrow<MemoryStore>>(stores: &[S], seed: u64) {
    let judge = StructuralJudge;
    let count = stores.len();
    let mut sessions: BTreeMap<(usize, usize), Session> = BTreeMap::new();
    let mut queues: BTreeMap<(usize, usize), VecDeque<Vec<u8>>> = BTreeMap::new();
    for i in 0..count {
        for j in 0..count {
            if i == j {
                continue;
            }
            let mut session = Session::new(SessionConfig {
                node_id: node(u8::try_from(i + 1).expect("small")),
                constellation_id: [0xc0; 32],
                member_heads: vec![[1; 32]],
                mode: Mode::Live,
                store_format: 1,
            });
            stores[i]
                .borrow()
                .read(|t| session.start(t, 0))
                .expect("start");
            sessions.insert((i, j), session);
            queues.insert((i, j), VecDeque::new());
        }
    }
    let pairs: Vec<(usize, usize)> = sessions.keys().copied().collect();
    let mut round = 0_u64;
    loop {
        round += 1;
        assert!(round < 20_000, "mesh did not quiesce");
        let mut moved = false;
        for &(i, j) in &shuffle(&pairs, seed ^ round) {
            let session = sessions.get_mut(&(i, j)).expect("session");
            let mut sent = 0;
            while let Some(message) = stores[i]
                .borrow()
                .read(|t| session.poll_transmit(t, 0))
                .expect("poll")
            {
                queues
                    .get_mut(&(i, j))
                    .expect("queue")
                    .push_back(message.encode_frame());
                moved = true;
                sent += 1;
                if sent >= 2 {
                    break;
                }
            }
        }
        for &(i, j) in &shuffle(&pairs, seed.rotate_left(7) ^ round) {
            let Some(frame) = queues.get_mut(&(i, j)).expect("queue").pop_front() else {
                continue;
            };
            moved = true;
            let message = Message::decode_body(&frame[4..]).expect("decodes");
            let receiver = sessions.get_mut(&(j, i)).expect("receiver");
            let events = stores[j]
                .borrow()
                .write(|t| receiver.handle(t, &judge, message, 0))
                .expect("handle");
            for event in events {
                if let SessionEvent::Stored { log_id, entries } = event {
                    for k in 0..count {
                        if k == j || k == i {
                            continue;
                        }
                        let relay = sessions.get_mut(&(j, k)).expect("relay");
                        stores[j]
                            .borrow()
                            .read(|t| relay.on_stored(t, log_id, &entries))
                            .expect("relay");
                    }
                }
            }
        }
        if !moved {
            break;
        }
    }
}

#[test]
fn a_holder_append_reaches_live_subscribers() {
    // Two nodes in sync; the holder appends and publishes; the peer receives it on the live tail.
    let judge = StructuralJudge;
    let agent_log = AgentLog::for_agent(&agent(), &judge);
    let a = MemoryStore::new();
    let b = MemoryStore::new();
    write(&a, |t| {
        agent_log
            .genesis(
                t,
                &key(1),
                match genesis_payload() {
                    Payload::EpochOpen(o) => o,
                    _ => unreachable!(),
                },
                0,
            )
            .map(|_| ())
    });
    run_mesh(&[&a, &b], 1);
    assert_eq!(view(&a), view(&b));
    let mut sa = Session::new(SessionConfig {
        node_id: node(1),
        constellation_id: [0xc0; 32],
        member_heads: vec![],
        mode: Mode::Live,
        store_format: 1,
    });
    let mut sb = Session::new(SessionConfig {
        node_id: node(2),
        constellation_id: [0xc0; 32],
        member_heads: vec![],
        mode: Mode::Live,
        store_format: 1,
    });
    a.read(|t| sa.start(t, 0)).expect("start");
    b.read(|t| sb.start(t, 0)).expect("start");
    let pump = |sa: &mut Session, sb: &mut Session| loop {
        let mut moved = false;
        while let Some(m) = a.read(|t| sa.poll_transmit(t, 0)).expect("poll") {
            b.write(|t| sb.handle(t, &judge, m, 0)).expect("handle");
            moved = true;
        }
        while let Some(m) = b.read(|t| sb.poll_transmit(t, 0)).expect("poll") {
            a.write(|t| sa.handle(t, &judge, m, 0)).expect("handle");
            moved = true;
        }
        if !moved {
            break;
        }
    };
    pump(&mut sa, &mut sb);
    let mut appended = Vec::new();
    write(&a, |t| {
        appended.push(
            agent_log
                .append(t, &key(1), &append_payload("c1", 0, "m1\n"), 1)?
                .hash(),
        );
        Ok(())
    });
    a.read(|t| sa.on_stored(t, log(), &appended))
        .expect("publish");
    pump(&mut sa, &mut sb);
    assert_eq!(view(&a), view(&b), "the live tail delivered the append");
    assert!(sa.unacked_bytes() == 0, "every pushed byte was acked");
}
