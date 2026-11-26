//! Property tests for the placement invariants: the epoch total order, equal-n claims resolving
//! to one fork on every replica (P6), fencing never adopting a lower epoch (P3), automatic
//! failover never picking a phone or firing in manual mode (P9), and vote promises (P7).

mod common;

use std::collections::{BTreeMap, BTreeSet};

use common::{
    Built, Members, Replica, append_payload, build, epoch, genesis_payload, log, node, open_payload,
};
use core_log::{Pos, Reason};
use core_membership::policy::FailoverPolicy;
use core_membership::{MembershipView, Preset};
use core_placement::failover::{self, FailoverDecision, Liveness, NodeClass, PeerFacts};
use core_placement::feed::feed_epoch;
use core_placement::voter::{VoterView, decide};
use core_placement::{ClaimRequest, FailoverInput, PlacementRecord};
use core_proto::bcbor::{self, Value};
use core_proto::{Epoch, NodeId};
use core_store::{EntryHash, PayloadState, PlacementState, SegmentState, StoredEntry, VotePromise};
use proptest::collection::vec;
use proptest::prelude::*;

const CLAIMANTS: [u8; 4] = [2, 3, 6, 7];

fn members() -> MembershipView {
    let mut m = Members::new();
    m.add(2, Preset::DesktopNode)
        .add(3, Preset::AlwaysOnLinux)
        .add(6, Preset::DesktopNode)
        .add(7, Preset::AlwaysOnLinux);
    m.view()
}

fn shuffled<T: Clone>(items: &[T], keys: &[u64]) -> Vec<T> {
    let mut indexed: Vec<(u64, usize)> = (0..items.len())
        .map(|i| (keys.get(i).copied().unwrap_or(0), i))
        .collect();
    indexed.sort_unstable();
    indexed.into_iter().map(|(_, i)| items[i].clone()).collect()
}

fn base() -> (Built, Built) {
    let g = build(1, 1, None, &genesis_payload(), 1);
    let a1 = build(1, 1, Some(&g), &append_payload(0, "a"), 2);
    (g, a1)
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    /// The epoch order is total and lexicographic over (n, claimant bytes); the feed epoch grows
    /// with n and stays JSON-safe.
    #[test]
    fn epochs_are_totally_ordered(
        a in (0_u64..=u64::from(u32::MAX), any::<[u8; 32]>()),
        b in (0_u64..=u64::from(u32::MAX), any::<[u8; 32]>()),
        c in (0_u64..=u64::from(u32::MAX), any::<[u8; 32]>()),
    ) {
        let e = |(n, bytes): (u64, [u8; 32])| Epoch { n, claimant: NodeId::from_bytes(bytes) };
        let (x, y, z) = (e(a), e(b), e(c));
        prop_assert_eq!(x.cmp(&y), (a.0, a.1).cmp(&(b.0, b.1)));
        prop_assert_eq!(x.cmp(&y), y.cmp(&x).reverse());
        if x <= y && y <= z {
            prop_assert!(x <= z);
        }
        prop_assert!(feed_epoch(&x) < 1 << 53);
        if x.n < y.n {
            prop_assert!(feed_epoch(&x) < feed_epoch(&y));
        }
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(48))]

    /// P6: simultaneous claims at equal n from one parent resolve to the highest claimant on every
    /// replica, whatever the delivery order, and every other claim is one divergence with the
    /// same id everywhere.
    #[test]
    fn equal_n_claims_resolve_to_one_fork(
        count in 2_usize..=4,
        order_a in vec(any::<u64>(), 8),
        order_b in vec(any::<u64>(), 8),
    ) {
        let view = members();
        let (g, a1) = base();
        let claims: Vec<Built> = CLAIMANTS[..count]
            .iter()
            .map(|k| build(*k, 2, Some(&a1), &open_payload(Reason::Failover, epoch(1, 1), Vec::new()), 3))
            .collect();
        let mut all = vec![g, a1];
        all.extend(claims.iter().cloned());
        let winner = claims.iter().map(Built::epoch).max().expect("claims");
        let mut results = Vec::new();
        for keys in [&order_a, &order_b] {
            let mut replica = Replica::new();
            for b in shuffled(&all, keys) {
                replica.deliver(&b, &view);
            }
            let evaluation = replica.evaluate(&view);
            prop_assert_eq!(evaluation.canonical, Some(winner));
            let analysis = replica.analysis(&view);
            for claim in &claims {
                let state = analysis.segments.iter().find(|s| s.epoch == claim.epoch()).map(|s| s.state);
                if claim.epoch() == winner {
                    prop_assert_eq!(state, Some(SegmentState::Open));
                } else {
                    prop_assert_eq!(state, Some(SegmentState::Divergent));
                }
            }
            let ids: BTreeSet<_> = evaluation.divergences.iter().map(|d| d.inputs.id()).collect();
            prop_assert_eq!(ids.len(), count - 1);
            results.push(ids);
        }
        prop_assert_eq!(&results[0], &results[1]);
    }

    /// P3 / F2: as entries of competing claims arrive in any order, the adopted placement epoch
    /// never goes down, and the final canonical epoch is the highest claimed one.
    #[test]
    fn fencing_never_adopts_a_lower_epoch(
        claims in vec((0_usize..4, any::<prop::sample::Index>(), 1_u64..4, any::<bool>()), 1..6),
        order in vec(any::<u64>(), 24),
    ) {
        let view = members();
        let (g, a1) = base();
        let mut built = vec![g, a1];
        for (who, parent_index, step, extend) in &claims {
            let parent = built[parent_index.index(built.len())].clone();
            let k = CLAIMANTS[*who];
            let open = build(
                k,
                parent.epoch().n + step,
                Some(&parent),
                &open_payload(Reason::Takeover, parent.epoch(), Vec::new()),
                10,
            );
            built.push(open.clone());
            if *extend {
                built.push(build(k, open.epoch().n, Some(&open), &append_payload(9, "x"), 11));
            }
        }
        let highest = built.iter().filter(|b| b.is_open()).map(Built::epoch).max().expect("opens");
        let mut replica = Replica::new();
        let mut adopted: Option<Epoch> = None;
        for b in shuffled(&built, &order) {
            let evaluation = replica.deliver(&b, &view);
            if let Some(placement) = evaluation.placement {
                if let Some(before) = adopted {
                    prop_assert!(placement.epoch >= before, "{:?} after {:?}", placement.epoch, before);
                }
                adopted = Some(placement.epoch);
            }
        }
        prop_assert_eq!(replica.evaluate(&view).canonical, Some(highest));
    }
}

fn policy_value(manual: bool, allow_phone: bool) -> Value {
    Value::Map(
        [
            (0, Value::Uint(u64::from(!manual))),
            (4, Value::Bool(allow_phone)),
        ]
        .into_iter()
        .collect(),
    )
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(128))]

    /// P9: automatic failover never selects a phone unless `allow_phone_auto`, and never fires in
    /// manual mode.
    #[test]
    fn failover_respects_phones_and_manual_mode(
        manual in any::<bool>(),
        allow_phone in any::<bool>(),
        classes in vec(0_u8..3, 6),
        me in 0_usize..6,
        dial in any::<bool>(),
    ) {
        let mut m = Members::new();
        for k in 2..=7 {
            m.add(k, Preset::DesktopNode);
        }
        m.policy(policy_value(manual, allow_phone));
        let view = m.view();
        let class = |c: u8| match c {
            0 => NodeClass::AlwaysOn,
            1 => NodeClass::Desktop,
            _ => NodeClass::Phone,
        };
        let facts = PeerFacts {
            classes: (2..=7_u8).zip(&classes).map(|(k, c)| (node(k), class(*c))).collect(),
            complete_seq: BTreeMap::new(),
        };
        let ranked = failover::candidates(&view, &common::agent(), &facts);
        if !allow_phone {
            prop_assert!(ranked.iter().all(|n| facts.classes.get(n) != Some(&NodeClass::Phone)));
        }
        let record = PlacementRecord {
            log_id: log(),
            agent_id: common::agent(),
            epoch: epoch(1, 1),
            state: PlacementState::Active,
            max_n_seen: 1,
            tip: Pos { seq: 1, hash: EntryHash::from_bytes([1; 32]) },
        };
        let me_k = u8::try_from(me).expect("small") + 2;
        let decision = failover::evaluate(&FailoverInput {
            view: &view,
            placement: &record,
            me: node(me_k),
            facts: &facts,
            liveness: Liveness {
                last_heard_ms: 0,
                last_dial_failure_ms: dial.then_some(9_999_000),
                peer_heard_ms: None,
            },
            runtime_parity_matches: true,
            now_ms: 10_000_000,
        });
        if matches!(decision, FailoverDecision::Claim { .. } | FailoverDecision::Probe { .. } | FailoverDecision::Wait { .. }) {
            prop_assert!(!manual);
            prop_assert!(allow_phone || facts.classes.get(&node(me_k)) != Some(&NodeClass::Phone));
        }
    }
}

fn line() -> Vec<StoredEntry> {
    let (g, a1) = base();
    let mut built = vec![g, a1];
    for i in 1..6 {
        let next = build(1, 1, built.last(), &append_payload(i, "x"), 3 + i);
        built.push(next);
    }
    built
        .iter()
        .map(|b| b.entry.to_stored(PayloadState::Stored))
        .collect()
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(128))]

    /// P7: with persisted promises, no epoch is certified at two fork points, and each voter's
    /// votes never go down in epoch.
    #[test]
    fn promises_keep_certificates_unique(
        requests in vec((0_usize..3, 1_u64..5, 0_usize..2, 0_usize..2), 1..24),
    ) {
        let mut m = Members::new();
        for k in [2, 3, 6, 7] {
            m.add(k, Preset::AlwaysOnLinux);
        }
        let mut voters = vec![
            Value::Bytes(node(3).as_bytes().to_vec()),
            Value::Bytes(node(6).as_bytes().to_vec()),
            Value::Bytes(node(7).as_bytes().to_vec()),
        ];
        voters.sort_by_key(bcbor::encode);
        m.policy(Value::Map([(5, Value::Map([(0, Value::Array(voters))].into_iter().collect()))].into_iter().collect()));
        let view = m.view();
        let policy: FailoverPolicy = view.policy().clone();
        let line = line();
        let voter_keys = [3_u8, 6, 7];
        let mut promises: BTreeMap<u8, VotePromise> = BTreeMap::new();
        let mut votes: BTreeMap<(Epoch, u64), BTreeSet<u8>> = BTreeMap::new();
        let mut history: BTreeMap<u8, Vec<VotePromise>> = BTreeMap::new();
        for (voter_index, n, claimant_index, parent_index) in requests {
            let voter = voter_keys[voter_index];
            let parent_seq = 5 + u64::try_from(parent_index).expect("small");
            let entry = &line[usize::try_from(parent_seq).expect("seq")];
            let request = ClaimRequest {
                log_id: log(),
                epoch: epoch(n + 1, [2_u8, 6][claimant_index]),
                parent: Pos { seq: entry.seq, hash: entry.entry_hash },
                manual: true,
            };
            let voter_view = VoterView {
                me: node(voter),
                policy: &policy,
                line: &line,
                complete_seq: 0,
                holder_heard_ms: None,
                now_ms: 0,
            };
            if let Ok(promise) = decide(&request, promises.get(&voter), &voter_view) {
                promises.insert(voter, promise.clone());
                history.entry(voter).or_default().push(promise);
                votes.entry((request.epoch, parent_seq)).or_default().insert(voter);
            }
        }
        let certified: Vec<(Epoch, u64)> = votes
            .iter()
            .filter(|(_, v)| v.len() >= 2)
            .map(|(k, _)| *k)
            .collect();
        let epochs: BTreeSet<Epoch> = certified.iter().map(|(e, _)| *e).collect();
        prop_assert_eq!(epochs.len(), certified.len(), "one fork point per certified epoch");
        for promises in history.values() {
            for pair in promises.windows(2) {
                prop_assert!(pair[1].epoch >= pair[0].epoch);
                if pair[1].epoch == pair[0].epoch {
                    prop_assert_eq!(&pair[1], &pair[0]);
                }
            }
        }
    }
}
