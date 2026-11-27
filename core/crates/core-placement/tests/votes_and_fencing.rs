//! Voters with persisted promises (V1-V4), the claimant's tally, and the fenced-holder report
//! against real logs.

mod common;

use common::{
    Members, append_payload, build, epoch, genesis_payload, key, log, node, open_payload, to_store,
    verdict,
};
use core_log::{AgentLog, Incoming, Payload, Pos, Reason};
use core_membership::policy::FailoverPolicy;
use core_membership::{MembershipView, Preset};
use core_placement::fenced::{assess, report_if_fenced};
use core_placement::vote::check_certificate;
use core_placement::voter::{CastError, VoterView, cast_vote, decide};
use core_placement::{
    ClaimRequest, ClaimVerdict, FencedAssessment, MembershipJudge, PlacementRecord, Refusal, Tally,
};
use core_proto::bcbor::{self, Value};
use core_store::{
    EntryHash, MemoryStore, PayloadState, Store, StoreError, StoredEntry, VotePromise,
};

fn quorum_members() -> Members {
    let mut m = Members::new();
    m.add(2, Preset::DesktopNode)
        .add(3, Preset::AlwaysOnLinux)
        .add(6, Preset::AlwaysOnLinux)
        .add(7, Preset::AlwaysOnLinux);
    let mut voters = vec![
        Value::Bytes(node(3).as_bytes().to_vec()),
        Value::Bytes(node(6).as_bytes().to_vec()),
        Value::Bytes(node(7).as_bytes().to_vec()),
    ];
    voters.sort_by_key(bcbor::encode);
    m.policy(Value::Map(
        [(
            5,
            Value::Map([(0, Value::Array(voters))].into_iter().collect()),
        )]
        .into_iter()
        .collect(),
    ));
    m
}

/// A canonical line of `len` entries (only seq and hash matter to the voter).
fn line(len: u64) -> Vec<StoredEntry> {
    let g = build(1, 1, None, &genesis_payload(), 1);
    let mut built = vec![g];
    for i in 1..len {
        let next = build(1, 1, built.last(), &append_payload(i - 1, "x"), 1 + i);
        built.push(next);
    }
    built
        .iter()
        .map(|b| b.entry.to_stored(PayloadState::Stored))
        .collect()
}

fn at(line: &[StoredEntry], seq: u64) -> Pos {
    let e = &line[usize::try_from(seq).expect("seq")];
    Pos {
        seq: e.seq,
        hash: e.entry_hash,
    }
}

fn view_for<'a>(policy: &'a FailoverPolicy, line: &'a [StoredEntry], me: u8) -> VoterView<'a> {
    VoterView {
        me: node(me),
        policy,
        line,
        complete_seq: 4,
        holder_heard_ms: Some(0),
        now_ms: 400_000,
    }
}

#[test]
fn voters_apply_v1_to_v3() {
    let membership = quorum_members().view();
    let policy = membership.policy().clone();
    let line = line(6);
    let request = ClaimRequest {
        log_id: log(),
        epoch: epoch(2, 2),
        parent: at(&line, 5),
        manual: false,
    };
    let view = view_for(&policy, &line, 3);
    let promise = decide(&request, None, &view).expect("votes");
    assert_eq!(promise.epoch, epoch(2, 2));
    // Not a voter.
    assert_eq!(
        decide(&request, None, &view_for(&policy, &line, 2)),
        Err(Refusal::NotVoter)
    );
    // V1: promised higher, or the same epoch at another fork point; a re-vote is fine.
    let higher = VotePromise {
        epoch: epoch(3, 6),
        ..promise.clone()
    };
    assert_eq!(
        decide(&request, Some(&higher), &view),
        Err(Refusal::Promised)
    );
    let other_fork = VotePromise {
        parent_seq: 4,
        parent_hash: at(&line, 4).hash,
        ..promise.clone()
    };
    assert_eq!(
        decide(&request, Some(&other_fork), &view),
        Err(Refusal::Promised)
    );
    assert_eq!(decide(&request, Some(&promise), &view), Ok(promise.clone()));
    // V2: off the line, or below the voter's complete tip.
    let off = ClaimRequest {
        parent: Pos {
            seq: 5,
            hash: EntryHash::from_bytes([9; 32]),
        },
        ..request
    };
    assert_eq!(decide(&off, None, &view), Err(Refusal::Behind));
    let stale = ClaimRequest {
        parent: at(&line, 3),
        ..request
    };
    assert_eq!(decide(&stale, None, &view), Err(Refusal::Behind));
    // V3: the voter heard the holder within the window; waived for a manual takeover.
    let alive = VoterView {
        holder_heard_ms: Some(200_000),
        ..view
    };
    assert_eq!(decide(&request, None, &alive), Err(Refusal::HolderAlive));
    let manual = ClaimRequest {
        manual: true,
        ..request
    };
    assert!(decide(&manual, None, &alive).is_ok());
}

#[test]
fn promises_persist_before_votes_and_certificates_are_judged() {
    let membership = quorum_members().view();
    let policy = membership.policy().clone();
    let line = line(6);
    let parent = at(&line, 5);
    let request = ClaimRequest {
        log_id: log(),
        epoch: epoch(3, 2),
        parent,
        manual: false,
    };
    let quorum = policy.quorum.clone().expect("quorum");
    let mut tally = Tally::new(request, quorum.clone());
    for voter in [3_u8, 6] {
        let store = MemoryStore::new();
        let view = view_for(&policy, &line, voter);
        let vote = store
            .write(|txn| {
                cast_vote(txn, &key(voter), &request, &view).map_err(|e| match e {
                    CastError::Store(s) => s,
                    other => StoreError::Invalid(other.to_string()),
                })
            })
            .expect("vote");
        assert!(tally.add(vote.clone()));
        assert!(!tally.add(vote), "a duplicate does not count twice");
        let promised = store
            .read(|txn| txn.vote_promise(&log()))
            .expect("read")
            .expect("persisted");
        assert_eq!(promised.epoch, epoch(3, 2));
        // A lower claim is now refused, and the store itself never lowers a promise.
        let lower = ClaimRequest {
            epoch: epoch(2, 3),
            ..request
        };
        let refused = store.write(|txn| {
            cast_vote(txn, &key(voter), &lower, &view)
                .map_err(|e| StoreError::Invalid(e.to_string()))
        });
        assert!(refused.is_err());
    }
    let certificate = tally.certificate().expect("two of three");
    assert_eq!(
        check_certificate(&certificate, &log(), &epoch(3, 2), &parent, &quorum),
        Ok(())
    );
    // The certificate in an epoch_open makes the claim valid under the quorum policy.
    let g = build(1, 1, None, &genesis_payload(), 1);
    let a1 = build(1, 1, Some(&g), &append_payload(0, "a"), 2);
    let parent = a1.pos();
    let mut tally = Tally::new(
        ClaimRequest {
            parent,
            epoch: epoch(2, 2),
            ..request
        },
        quorum,
    );
    for voter in [6_u8, 7] {
        tally.add(
            core_placement::SignedVote::from_value(&common::vote(voter, epoch(2, 2), parent))
                .expect("vote"),
        );
    }
    let open = build(
        2,
        2,
        Some(&a1),
        &open_payload(
            Reason::Failover,
            epoch(1, 1),
            tally.certificate().expect("cert"),
        ),
        3,
    );
    assert_eq!(verdict(&membership, &open, Some(&a1)), ClaimVerdict::Valid);
}

// ----- fenced holder -----

fn desktop_members() -> MembershipView {
    let mut m = Members::new();
    m.add(2, Preset::DesktopNode).add(3, Preset::AlwaysOnLinux);
    m.view()
}

fn write<T>(
    store: &MemoryStore,
    view: &MembershipView,
    f: impl FnOnce(
        &AgentLog<'_, MembershipJudge<'_>>,
        &mut dyn core_store::Txn,
    ) -> Result<T, core_log::LogError>,
) -> T {
    let agent = common::agent();
    let judge = MembershipJudge::new(view, Some(&agent));
    store
        .write(|txn| f(&AgentLog::new(log(), &judge), txn).map_err(to_store))
        .expect("write")
}

fn deliver_all(from: &MemoryStore, to: &MemoryStore, view: &MembershipView) {
    let mut entries: Vec<(StoredEntry, Vec<u8>)> = from
        .read(|txn| {
            let mut out = Vec::new();
            for s in txn.segments(&log())? {
                for e in txn.segment_entries(&log(), &s.epoch, 0, usize::MAX)? {
                    let p = txn.blob(&e.payload_hash)?.unwrap_or_default();
                    out.push((e, p));
                }
            }
            Ok::<_, StoreError>(out)
        })
        .expect("read");
    entries.sort_by_key(|(e, _)| e.seq);
    write(to, view, |log, txn| {
        for (e, p) in &entries {
            let signed = core_log::SignedEntry::from_stored(e)?;
            log.receive(txn, &signed, &Incoming::Inline(p.clone()))?;
        }
        log.evaluate(txn)
    });
}

fn record(store: &MemoryStore, view: &MembershipView) -> PlacementRecord {
    let agent = common::agent();
    let judge = MembershipJudge::new(view, Some(&agent));
    store
        .read(|txn| PlacementRecord::derive(&AgentLog::new(log(), &judge), txn).map_err(to_store))
        .expect("read")
        .expect("record")
}

fn analysis(store: &MemoryStore, view: &MembershipView) -> core_log::Analysis {
    let agent = common::agent();
    let judge = MembershipJudge::new(view, Some(&agent));
    store
        .read(|txn| AgentLog::new(log(), &judge).analyze(txn).map_err(to_store))
        .expect("read")
}

fn genesis_open() -> core_log::EpochOpen {
    let Payload::EpochOpen(open) = genesis_payload() else {
        unreachable!()
    };
    open
}

fn failover_open() -> core_log::EpochOpen {
    let Payload::EpochOpen(open) = open_payload(Reason::Failover, epoch(1, 1), Vec::new()) else {
        unreachable!()
    };
    open
}

#[test]
fn a_returning_holder_reports_and_its_tail_is_recorded_once() {
    let view = desktop_members();
    let old = MemoryStore::new();
    let new = MemoryStore::new();
    let (k1, k2) = (key(1), key(2));
    let a1 = write(&old, &view, |log, txn| {
        log.genesis(txn, &k1, genesis_open(), 1)?;
        let e = log.append(txn, &k1, &append_payload(0, "a"), 2)?;
        Ok(Pos {
            seq: e.header().seq,
            hash: e.hash(),
        })
    });
    deliver_all(&old, &new, &view);
    // Partition: N2 fails over from a1; N1 keeps writing.
    write(&new, &view, |log, txn| {
        log.claim(txn, &k2, &failover_open(), a1, 3)
    });
    let head = write(&old, &view, |log, txn| {
        log.append(txn, &k1, &append_payload(1, "b"), 4)?;
        let e = log.append(txn, &k1, &append_payload(2, "c"), 5)?;
        Ok(Pos {
            seq: e.header().seq,
            hash: e.hash(),
        })
    });
    assert_eq!(
        report_if_fenced(&record(&old, &view), epoch(1, 1), head),
        None
    );
    // N2's report before it has N1's tail asks for the entries.
    let report = core_placement::FencedReport {
        log_id: log(),
        epoch: epoch(1, 1),
        head,
    };
    let early = new
        .read(|txn| assess(&report, &node(1), &analysis(&new, &view), txn))
        .expect("assess");
    assert_eq!(early, FencedAssessment::NeedEntries { epoch: epoch(1, 1) });
    // Heal: N1 learns the higher epoch, stops, and reports.
    deliver_all(&new, &old, &view);
    let fenced = record(&old, &view);
    assert!(!fenced.may_append(&node(1)));
    assert_eq!(report_if_fenced(&fenced, epoch(1, 1), head), Some(report));
    deliver_all(&old, &new, &view);
    let a = analysis(&new, &view);
    assert_eq!(
        new.read(|txn| assess(&report, &node(3), &a, txn))
            .expect("assess"),
        FencedAssessment::NotClaimant
    );
    let not_lower = core_placement::FencedReport {
        epoch: epoch(2, 2),
        ..report
    };
    assert_eq!(
        new.read(|txn| assess(&not_lower, &node(2), &a, txn))
            .expect("assess"),
        FencedAssessment::NotLower
    );
    let FencedAssessment::Divergent {
        to_record,
        recorded,
    } = new
        .read(|txn| assess(&report, &node(1), &a, txn))
        .expect("assess")
    else {
        panic!("a divergence");
    };
    assert_eq!((to_record.len(), recorded), (1, 0));
    assert_eq!(to_record[0].inputs.stranded_epoch, epoch(1, 1));
    assert_eq!(to_record[0].tail_head, head);
    write(&new, &view, |log, txn| {
        log.record_divergence(
            txn,
            &k2,
            &to_record[0],
            &core_log::DivergenceProjection::default(),
            6,
        )
    });
    let a = analysis(&new, &view);
    assert_eq!(
        new.read(|txn| assess(&report, &node(1), &a, txn))
            .expect("assess"),
        FencedAssessment::Divergent {
            to_record: Vec::new(),
            recorded: 1
        }
    );
}

#[test]
fn a_holder_that_sealed_reports_a_clean_stop() {
    let view = desktop_members();
    let store = MemoryStore::new();
    let k1 = key(1);
    let seal = write(&store, &view, |log, txn| {
        log.genesis(txn, &k1, genesis_open(), 1)?;
        let a = log.append(txn, &k1, &append_payload(0, "a"), 2)?;
        let s = log.seal(
            txn,
            &k1,
            Some(epoch(2, 2)),
            Pos {
                seq: a.header().seq,
                hash: a.hash(),
            },
            3,
        )?;
        Ok(Pos {
            seq: s.header().seq,
            hash: s.hash(),
        })
    });
    let k2 = key(2);
    let Payload::EpochOpen(open) = open_payload(Reason::Move, epoch(1, 1), Vec::new()) else {
        unreachable!()
    };
    write(&store, &view, |log, txn| {
        log.claim(txn, &k2, &open, seal, 4)
    });
    let report = report_if_fenced(&record(&store, &view), epoch(1, 1), seal).expect("fenced");
    let a = analysis(&store, &view);
    assert_eq!(
        store
            .read(|txn| assess(&report, &node(1), &a, txn))
            .expect("assess"),
        FencedAssessment::Clean
    );
}
