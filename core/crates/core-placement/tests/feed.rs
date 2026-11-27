//! The feed v1 placement amendment: feed epochs across moves and failovers (D3), cursor
//! classification, refusals, page additions, and the gate.

mod common;

use common::{
    Members, agent, append_payload, epoch, genesis_payload, key, log, node, open_payload, to_store,
};
use core_log::{AgentLog, Payload, Pos, Reason};
use core_membership::{MembershipView, Preset};
use core_placement::feed::{
    CursorVerdict, FeedCursor, JournalView, PageAdditions, Refusal, ResyncReason, ServedBy,
    classify, continuity_epoch, cursor_line, feed_epoch,
};
use core_placement::{Gate, MembershipJudge, PlacementRecord};
use core_proto::Epoch;
use core_store::{EntryHash, MemoryStore, PlacementState, Store};
use serde_json::json;

fn view() -> MembershipView {
    let mut m = Members::new();
    m.add(2, Preset::DesktopNode).add(3, Preset::AlwaysOnLinux);
    m.view()
}

fn open(reason: Reason, prior: Epoch) -> core_log::EpochOpen {
    let Payload::EpochOpen(open) = open_payload(reason, prior, Vec::new()) else {
        unreachable!()
    };
    open
}

fn continuity(store: &MemoryStore, view: &MembershipView) -> Option<Epoch> {
    let agent = agent();
    let judge = MembershipJudge::new(view, Some(&agent));
    store
        .read(|txn| {
            let line = AgentLog::new(log(), &judge)
                .canonical_line(txn)
                .map_err(to_store)?;
            continuity_epoch(&line, txn)
        })
        .expect("read")
}

#[test]
fn moves_keep_the_feed_epoch_and_failovers_change_it() {
    let view = view();
    let store = MemoryStore::new();
    let agent = agent();
    let judge = MembershipJudge::new(&view, Some(&agent));
    let log_of = || AgentLog::new(log(), &judge);
    let pos = |e: &core_log::SignedEntry| Pos {
        seq: e.header().seq,
        hash: e.hash(),
    };
    let (k1, k2, k3) = (key(1), key(2), key(3));
    let seal = store
        .write(|txn| {
            let Payload::EpochOpen(g) = genesis_payload() else {
                unreachable!()
            };
            log_of().genesis(txn, &k1, g, 1).map_err(to_store)?;
            let a = log_of()
                .append(txn, &k1, &append_payload(0, "a"), 2)
                .map_err(to_store)?;
            let s = log_of()
                .seal(txn, &k1, Some(epoch(2, 2)), pos(&a), 3)
                .map_err(to_store)?;
            Ok::<_, core_store::StoreError>(pos(&s))
        })
        .expect("write");
    let first = continuity(&store, &view).expect("genesis line");
    assert_eq!(first, epoch(1, 1));
    // Move to N2: same feed epoch.
    let moved = store
        .write(|txn| {
            log_of()
                .claim(txn, &k2, &open(Reason::Move, epoch(1, 1)), seal, 4)
                .map_err(to_store)?;
            let b = log_of()
                .append(txn, &k2, &append_payload(1, "b"), 5)
                .map_err(to_store)?;
            Ok::<_, core_store::StoreError>(pos(&b))
        })
        .expect("move");
    assert_eq!(
        continuity(&store, &view),
        Some(first),
        "D3: a move keeps the feed epoch"
    );
    // Failover to N3: a new continuity line.
    store
        .write(|txn| {
            log_of()
                .claim(txn, &k3, &open(Reason::Failover, epoch(2, 2)), moved, 6)
                .map_err(to_store)
        })
        .expect("failover");
    let after = continuity(&store, &view).expect("line");
    assert_eq!(after, epoch(3, 3));
    assert!(
        feed_epoch(&after) > feed_epoch(&first),
        "D3: failover changes it, upward"
    );
}

fn journal(is_holder: bool) -> JournalView {
    JournalView {
        feed_epoch: feed_epoch(&epoch(3, 3)),
        base_seq: 10,
        head_seq: 50,
        line_at_cursor: Some("00112233aabbccdd".into()),
        is_holder,
    }
}

fn cursor(epoch_value: u64, seq: u64, line: Option<&str>) -> FeedCursor {
    FeedCursor {
        epoch: epoch_value,
        seq,
        line: line.map(Into::into),
    }
}

#[test]
fn cursors_are_classified_per_the_amendment() {
    let fe = feed_epoch(&epoch(3, 3));
    let j = journal(false);
    assert_eq!(
        classify(None, &j),
        CursorVerdict::Snapshot(ResyncReason::NoCursor)
    );
    assert_eq!(
        classify(Some(&cursor(fe - 1, 20, None)), &j),
        CursorVerdict::Snapshot(ResyncReason::EpochChanged)
    );
    assert_eq!(
        classify(Some(&cursor(fe, 5, None)), &j),
        CursorVerdict::Snapshot(ResyncReason::BehindHorizon)
    );
    assert_eq!(
        classify(Some(&cursor(fe, 20, None)), &j),
        CursorVerdict::Replay
    );
    assert_eq!(
        classify(Some(&cursor(fe, 20, Some("00112233aabbccdd"))), &j),
        CursorVerdict::Replay
    );
    assert_eq!(
        classify(Some(&cursor(fe, 20, Some("ffffffffffffffff"))), &j),
        CursorVerdict::Snapshot(ResyncReason::LineChanged)
    );
    assert_eq!(
        classify(Some(&cursor(fe, 60, None)), &j),
        CursorVerdict::Lagging,
        "a replica behind the client answers replay + lagging, not ahead_of_head"
    );
    assert_eq!(
        classify(Some(&cursor(fe, 60, None)), &journal(true)),
        CursorVerdict::Snapshot(ResyncReason::AheadOfHead)
    );
}

fn record(state: PlacementState, max_n: u64) -> PlacementRecord {
    PlacementRecord {
        log_id: log(),
        agent_id: agent(),
        epoch: epoch(7, 2),
        state,
        max_n_seen: max_n,
        tip: Pos {
            seq: 9,
            hash: EntryHash::from_bytes([9; 32]),
        },
    }
}

#[test]
fn refusals_carry_the_placement_object() {
    let holder = node(2).to_hex();
    let claimant = holder.clone();
    let r = Refusal::not_holder(&record(PlacementState::Active, 7), false);
    assert_eq!(
        serde_json::to_value(&r).expect("json"),
        json!({"error": format!("not_holder: agent lives on {holder}"), "placement": {
            "agent_id": common::AGENT, "holder_node_id": holder,
            "epoch": {"n": 7, "claimant": claimant}, "state": "active"}})
    );
    let moving = Refusal::not_holder(&record(PlacementState::Active, 7), true);
    assert_eq!(moving.placement.state, "moving");
    assert_eq!(moving.placement.retry_after_ms, Some(2_000));
    let sealed = Refusal::not_holder(&record(PlacementState::SealedTo(epoch(8, 3)), 7), false);
    assert_eq!(sealed.placement.holder_node_id, Some(node(3).to_hex()));
    assert_eq!(sealed.placement.state, "moving");
    let superseded = Refusal::not_holder(&record(PlacementState::Active, 9), false);
    assert_eq!(superseded.error, "not_holder: agent lives on none");
    assert_eq!(superseded.placement.state, "superseded_unknown");
    assert_eq!(superseded.placement.holder_node_id, None);
    let moved = Refusal::moved(&record(PlacementState::Placeless, 7));
    assert_eq!(moved.error, "moved: agent lives on none");
    assert_eq!(moved.placement.state, "placeless");
    let round: Refusal =
        serde_json::from_value(serde_json::to_value(&moving).expect("json")).expect("parse");
    assert_eq!(round, moving);
}

#[test]
fn page_additions_and_cursor_lines_serialize_as_specified() {
    let page = PageAdditions {
        served_by: Some(ServedBy {
            node_id: node(1).to_hex(),
            is_holder: false,
        }),
        placement: None,
        lagging: true,
    };
    let value = serde_json::to_value(&page).expect("json");
    assert_eq!(value["lagging"], json!(true));
    assert_eq!(value["served_by"]["is_holder"], json!(false));
    assert!(value.get("placement").is_none());
    assert_eq!(
        serde_json::to_value(PageAdditions::default()).expect("json"),
        json!({})
    );
    let line = cursor_line(&EntryHash::from_bytes([0xab; 32]));
    assert_eq!(line, "abababababababab");
    let c: FeedCursor =
        serde_json::from_value(json!({"epoch": 5, "seq": 3, "extra": 1})).expect("v1 cursor");
    assert_eq!(c.line, None);
    assert_eq!(
        serde_json::to_value(cursor(5, 3, Some(&line))).expect("json"),
        json!({"epoch": 5, "seq": 3, "line": line})
    );
}

#[test]
fn the_gate_follows_f4() {
    let me = node(2);
    let active = record(PlacementState::Active, 7);
    assert_eq!(active.gate(&me, false), Gate::Active);
    assert_eq!(active.gate(&me, true), Gate::Moving);
    assert_eq!(active.gate(&node(3), false), Gate::NotHolder);
    assert_eq!(
        record(PlacementState::Active, 8).gate(&me, false),
        Gate::SupersededUnknown
    );
    assert_eq!(
        record(PlacementState::Placeless, 7).gate(&me, false),
        Gate::Placeless
    );
    assert_eq!(
        record(PlacementState::SealedTo(epoch(8, 3)), 7).gate(&me, false),
        Gate::NotHolder
    );
    assert_eq!(
        record(PlacementState::SealedTo(epoch(8, 3)), 7).holder(),
        Some(node(3))
    );
    assert!(Gate::Active.accepts() && !Gate::Moving.accepts());
}
