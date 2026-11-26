//! Holder and replica flows through `AgentLog`: genesis, append behind the F4 gate, seal and move,
//! failover with a fenced old holder, the holder's divergence entry, and pruning.

#![allow(clippy::too_many_lines)]

mod common;

use common::{
    Built, Replica, agent, append_payload, feed_payload, genesis_payload, key, log, node,
    open_payload,
};
use core_log::fold::StateFold;
use core_log::payload::{Checkpoint, Payload, kind};
use core_log::prune::{FeedRetention, prune, verify_log};
use core_log::{
    AgentLog, DivergenceProjection, Incoming, LogError, Pos, Reason, Received, SignedEntry,
    StructuralJudge, TouchedConversation,
};
use core_proto::{ConversationId, Epoch};
use core_store::{MemoryStore, PayloadState, PlacementState, Store, StoreError, StoredEntry};

fn w<T>(
    store: &MemoryStore,
    f: impl FnOnce(&mut dyn core_store::Txn) -> Result<T, LogError>,
) -> Result<T, LogError> {
    let mut out = None;
    let result = store.write(|txn| match f(txn) {
        Ok(value) => {
            out = Some(Ok(value));
            Ok(())
        }
        Err(error) => {
            out = Some(Err(error));
            Err(StoreError::Invalid("rolled back".into()))
        }
    });
    match (result, out) {
        (_, Some(value)) => value,
        (Err(error), None) => Err(LogError::Store(error)),
        (Ok(()), None) => unreachable!(),
    }
}

fn copy_to(from: &MemoryStore, to: &mut Replica) {
    let judge = StructuralJudge;
    let line = from
        .read(|txn| {
            let mut all: Vec<StoredEntry> = Vec::new();
            for segment in txn.segments(&log())? {
                all.extend(txn.segment_entries(&log(), &segment.epoch, 0, usize::MAX)?);
            }
            Ok::<_, StoreError>(all)
        })
        .expect("read");
    let _ = &judge;
    for entry in line {
        let payload = from
            .read(|txn| txn.blob(&entry.payload_hash))
            .expect("blob")
            .expect("payload");
        let signed = SignedEntry::from_stored(&entry).expect("valid");
        to.deliver(&Built {
            entry: signed,
            payload,
        });
    }
}

fn open_genesis() -> core_log::EpochOpen {
    match genesis_payload() {
        Payload::EpochOpen(open) => open,
        _ => unreachable!(),
    }
}

fn open_of(reason: Reason, prior: Epoch) -> core_log::EpochOpen {
    match open_payload(reason, prior) {
        Payload::EpochOpen(open) => open,
        _ => unreachable!(),
    }
}

#[test]
fn holder_appends_behind_the_gate_and_moves_with_a_seal() {
    let judge = StructuralJudge;
    let log_n1 = AgentLog::for_agent(&agent(), &judge);
    let source = MemoryStore::new();
    w(&source, |t| log_n1.genesis(t, &key(1), open_genesis(), 1)).expect("genesis");
    assert!(matches!(
        w(&source, |t| log_n1.genesis(t, &key(1), open_genesis(), 1)),
        Err(LogError::NotHolder(_))
    ));
    let a1 = w(&source, |t| {
        log_n1.append(t, &key(1), &append_payload("c1", 0, "m1\n"), 2)
    })
    .expect("append");
    assert_eq!(a1.header().seq, 1);
    // Another key is not the holder.
    assert!(matches!(
        w(&source, |t| log_n1.append(
            t,
            &key(2),
            &append_payload("c1", 3, "x\n"),
            3
        )),
        Err(LogError::NotHolder(_))
    ));
    let cp = w(&source, |t| {
        log_n1.append(
            t,
            &key(1),
            &Payload::Checkpoint(Checkpoint {
                manifest: Vec::new(),
                refs: Vec::new(),
                feed_heads: Vec::new(),
                store_format: 1,
                runtime_parity: "p".into(),
                h1_violations: Vec::new(),
            }),
            3,
        )
    })
    .expect("checkpoint");
    let target = Epoch {
        n: 2,
        claimant: node(2),
    };
    let cp_pos = Pos {
        seq: cp.header().seq,
        hash: cp.hash(),
    };
    let seal = w(&source, |t| {
        log_n1.seal(t, &key(1), Some(target), cp_pos, 4)
    })
    .expect("seal");
    let placement = source
        .read(|t| t.placement(&log()))
        .expect("read")
        .expect("placement");
    assert_eq!(placement.state, PlacementState::SealedTo(target));
    // After its seal, the claimant never writes again in that epoch.
    assert!(matches!(
        w(&source, |t| log_n1.append(
            t,
            &key(1),
            &append_payload("c1", 3, "late\n"),
            5
        )),
        Err(LogError::NotHolder(_))
    ));

    // The target replicates, then claims with reason move from the seal.
    let mut target_replica = Replica::new();
    copy_to(&source, &mut target_replica);
    let seal_pos = Pos {
        seq: seal.header().seq,
        hash: seal.hash(),
    };
    let (open, evaluation) = w(&target_replica.store, |t| {
        log_n1.claim(
            t,
            &key(2),
            &open_of(
                Reason::Move,
                Epoch {
                    n: 1,
                    claimant: node(1),
                },
            ),
            seal_pos,
            6,
        )
    })
    .expect("move claim");
    assert_eq!(open.header().epoch, target);
    assert_eq!(evaluation.canonical, Some(target));
    assert!(evaluation.divergences.is_empty());
    let b1 = w(&target_replica.store, |t| {
        log_n1.append(t, &key(2), &append_payload("c1", 3, "m2\n"), 7)
    })
    .expect("append at target");
    assert_eq!(b1.header().seq, seal.header().seq + 2);

    // The source learns of the new epoch and is fenced.
    let mut source_replica = Replica::new();
    copy_to(&source, &mut source_replica);
    copy_to(&target_replica.store, &mut source_replica);
    let placement = source_replica
        .store
        .read(|t| t.placement(&log()))
        .expect("read")
        .expect("placement");
    assert_eq!(placement.epoch, target);
    assert!(
        w(&source_replica.store, |t| log_n1.append(
            t,
            &key(1),
            &append_payload("c1", 6, "no\n"),
            8
        ))
        .is_err()
    );
}

#[test]
fn a_move_without_a_seal_is_refused() {
    let judge = StructuralJudge;
    let log_n1 = AgentLog::for_agent(&agent(), &judge);
    let store = MemoryStore::new();
    let g = w(&store, |t| log_n1.genesis(t, &key(1), open_genesis(), 1)).expect("genesis");
    let parent = Pos {
        seq: 0,
        hash: g.hash(),
    };
    let refused = w(&store, |t| {
        log_n1.claim(
            t,
            &key(2),
            &open_of(
                Reason::Move,
                Epoch {
                    n: 1,
                    claimant: node(1),
                },
            ),
            parent,
            2,
        )
    });
    assert!(matches!(refused, Err(LogError::ClaimRejected(_))));
}

#[test]
fn failover_fences_the_old_holder_and_the_new_holder_records_the_divergence() {
    let judge = StructuralJudge;
    let log = AgentLog::for_agent(&agent(), &judge);
    let a = MemoryStore::new();
    w(&a, |t| log.genesis(t, &key(1), open_genesis(), 1)).expect("genesis");
    let a1 = w(&a, |t| {
        log.append(t, &key(1), &append_payload("c1", 0, "m1\n"), 2)
    })
    .expect("a1");
    let mut b = Replica::new();
    copy_to(&a, &mut b);
    // Partition: A keeps writing, B fails over from a1.
    w(&a, |t| {
        log.append(t, &key(1), &append_payload("c1", 3, "a2\n"), 3)
    })
    .expect("a2");
    w(&a, |t| {
        log.append(t, &key(1), &feed_payload("c1", 1, "t1", "turn_open"), 4)
    })
    .expect("a3");
    let parent = Pos {
        seq: a1.header().seq,
        hash: a1.hash(),
    };
    let (open, _) = w(&b.store, |t| {
        log.claim(
            t,
            &key(2),
            &open_of(
                Reason::Failover,
                Epoch {
                    n: 1,
                    claimant: node(1),
                },
            ),
            parent,
            10,
        )
    })
    .expect("failover");
    w(&b.store, |t| {
        log.append(t, &key(2), &append_payload("c1", 3, "b1\n"), 11)
    })
    .expect("b1");
    // Heal: both sides exchange everything.
    let mut a_view = Replica::new();
    copy_to(&a, &mut a_view);
    copy_to(&b.store, &mut a_view);
    copy_to(&a, &mut b);
    let ea = a_view
        .store
        .read(|t| log.analyze(t).map_err(common::to_store))
        .expect("a");
    let eb = b
        .store
        .read(|t| log.analyze(t).map_err(common::to_store))
        .expect("b");
    assert_eq!(ea, eb, "both sides agree");
    assert_eq!(ea.canonical, Some(open.header().epoch));
    assert_eq!(ea.divergences.len(), 1);
    let divergence = &ea.divergences[0];
    assert_eq!(
        divergence.inputs.stranded_epoch,
        Epoch {
            n: 1,
            claimant: node(1)
        }
    );
    assert_eq!(divergence.inputs.fork.seq, 1);
    assert_eq!(divergence.tail.len(), 2);
    assert!(!divergence.inputs.equivocation);
    // The tail is never part of the canonical line (L10).
    let line: Vec<_> = ea.line.iter().map(|e| e.entry_hash).collect();
    assert!(divergence.tail.iter().all(|h| !line.contains(h)));
    // The old holder is fenced.
    assert!(matches!(
        w(&a_view.store, |t| log.append(
            t,
            &key(1),
            &append_payload("c1", 9, "x\n"),
            20
        )),
        Err(LogError::NotHolder(_))
    ));
    // The new holder records the divergence; its payload carries the canonical inputs.
    let projection = DivergenceProjection {
        conversations: vec![TouchedConversation {
            conversation_id: ConversationId::new("c1"),
            last_common_message_id: Some("m1".into()),
            owner: agent(),
        }],
        ..DivergenceProjection::default()
    };
    let entry = w(&b.store, |t| {
        log.record_divergence(t, &key(2), divergence, &projection, 30)
    })
    .expect("record");
    let payload = b
        .store
        .read(|t| t.blob(&entry.header().payload_hash))
        .expect("read")
        .expect("payload");
    let Payload::Divergence(decoded) =
        Payload::decode(kind::DIVERGENCE, &payload).expect("decodes")
    else {
        panic!("divergence payload")
    };
    assert_eq!(decoded.divergence_id, divergence.inputs.id());
    assert_eq!(decoded.competing, divergence.inputs.competing);
    assert_eq!(
        decoded.conversations[0].catalog_node_id,
        divergence
            .inputs
            .catalog_node_id(&ConversationId::new("c1"))
    );
    assert!(
        decoded.conversations[0]
            .branch_conversation_id
            .as_str()
            .starts_with("local-conv-")
    );
    let record = b
        .store
        .read(|t| t.divergence(&divergence.inputs.id()))
        .expect("read")
        .expect("record");
    assert_eq!(record.recorded_entry, Some(entry.hash()));
}

#[test]
fn pending_entries_wait_for_their_parent_and_payloads_attach_later() {
    let judge = StructuralJudge;
    let log = AgentLog::for_agent(&agent(), &judge);
    let g = common::build(1, 1, None, &genesis_payload(), 1);
    let a1 = common::build(1, 1, Some(&g), &append_payload("c1", 0, "m1\n"), 2);
    let store = MemoryStore::new();
    let r = w(&store, |t| {
        log.receive(t, &a1.entry, &Incoming::Inline(a1.payload.clone()))
    })
    .expect("receive");
    assert_eq!(
        r,
        Received::Pending {
            missing: g.entry.hash()
        }
    );
    w(&store, |t| {
        log.receive(t, &g.entry, &Incoming::Inline(g.payload.clone()))
    })
    .expect("g");
    let r = w(&store, |t| log.receive(t, &a1.entry, &Incoming::ByHash)).expect("a1");
    assert!(matches!(r, Received::Stored { .. }));
    let seg = store
        .read(|t| t.segment(&common::log(), &a1.epoch()))
        .expect("read")
        .expect("segment");
    assert_eq!((seg.head_seq, seg.complete_seq), (1, Some(0)));
    let r = w(&store, |t| {
        log.receive(t, &a1.entry, &Incoming::Inline(a1.payload.clone()))
    })
    .expect("attach");
    assert_eq!(r, Received::PayloadAttached);
    let seg = store
        .read(|t| t.segment(&common::log(), &a1.epoch()))
        .expect("read")
        .expect("segment");
    assert_eq!(seg.complete_seq, Some(1));
    // A payload that does not match its header is refused.
    let mut wrong = a1.payload.clone();
    wrong[0] ^= 1;
    assert_eq!(
        w(&store, |t| log.receive(
            t,
            &a1.entry,
            &Incoming::Inline(wrong)
        )),
        Err(LogError::PayloadMismatch)
    );
    // A pruned marker for a non-compactable entry leaves the payload missing.
    let a2 = common::build(1, 1, Some(&a1), &append_payload("c1", 3, "m2\n"), 3);
    w(&store, |t| log.receive(t, &a2.entry, &Incoming::Pruned)).expect("a2");
    let stored = store
        .read(|t| t.entry(&a2.entry.hash()))
        .expect("read")
        .expect("a2");
    assert_eq!(stored.payload_state, PayloadState::Missing);
}

#[test]
fn pruning_keeps_the_chain_and_the_materialized_manifest() {
    let judge = StructuralJudge;
    let log = AgentLog::for_agent(&agent(), &judge);
    let store = MemoryStore::new();
    w(&store, |t| log.genesis(t, &key(1), open_genesis(), 1)).expect("genesis");
    let mut fold = StateFold::new();
    let mut appended = 0;
    let write = |payload: Payload, fold: &mut StateFold| {
        fold.apply(&payload).expect("applies");
        w(&store, |t| log.append(t, &key(1), &payload, 10)).expect("append");
    };
    for round in 0..4_u64 {
        write(
            Payload::file_put(
                "agents/YQ.json",
                format!("{{\"v\":{round}}}").into_bytes(),
                false,
            ),
            &mut fold,
        );
        let line = format!("m{round}\n");
        write(append_payload("c1", appended, &line), &mut fold);
        appended += line.len() as u64;
        write(
            feed_payload("c1", round * 2, &format!("t{round}"), "turn_open"),
            &mut fold,
        );
        write(
            feed_payload("c1", round * 2 + 1, &format!("t{round}"), "turn_closed"),
            &mut fold,
        );
        write(
            Payload::file_put(
                "agents/YQ.json",
                format!("{{\"v\":{round},\"w\":1}}").into_bytes(),
                false,
            ),
            &mut fold,
        );
    }
    write(
        Payload::Checkpoint(Checkpoint {
            manifest: fold.manifest(),
            refs: fold.refs(),
            feed_heads: Vec::new(),
            store_format: 1,
            runtime_parity: "p".into(),
            h1_violations: Vec::new(),
        }),
        &mut fold,
    );
    let before = fold.manifest();
    let pruned = w(&store, |t| {
        prune(
            &log,
            t,
            FeedRetention {
                max_entries: 2,
                max_bytes: 1 << 20,
            },
        )
    })
    .expect("prune");
    // One superseded put per round (the first of each adjacent pair) plus the old feed entries.
    assert!(pruned.len() >= 4 + 6, "pruned {}", pruned.len());
    let report = store
        .read(|t| verify_log(t, &common::log()).map_err(common::to_store))
        .expect("verifies");
    assert_eq!(report.pruned, pruned.len());
    let mut after = StateFold::new();
    for entry in store
        .read(|t| log.canonical_line(t).map_err(common::to_store))
        .expect("line")
    {
        if entry.payload_state == PayloadState::Stored {
            let bytes = store
                .read(|t| t.blob(&entry.payload_hash))
                .expect("read")
                .expect("blob");
            after
                .apply(&Payload::decode(entry.kind, &bytes).expect("decodes"))
                .expect("applies");
        }
    }
    assert_eq!(
        after.manifest(),
        before,
        "L11: the manifest survives pruning"
    );
    // Pruning twice prunes nothing more.
    let again = w(&store, |t| {
        prune(
            &log,
            t,
            FeedRetention {
                max_entries: 2,
                max_bytes: 1 << 20,
            },
        )
    })
    .expect("prune");
    assert!(again.is_empty());
}
