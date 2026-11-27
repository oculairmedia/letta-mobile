//! Property tests for the encoding invariants (E1, E2) and the membership invariants (M1-M6, M9)
//! over randomly generated DAGs with concurrency, unauthorized ops, rotations and root moves.

mod common;

use std::collections::{BTreeMap, BTreeSet};

use common::{DagBuilder, key, shuffled};
use core_membership::capability::{self, CapSet, Capability, Preset};
use core_membership::op::{CatalogRoot, Op, OpHeader, OpKind, OpPayload, rotate_proof};
use core_membership::{
    CapSet as Caps, Evaluation, MemberStatus, MembershipLog, MembershipView, OpId, SignedOp,
};
use core_proto::NodeId;
use core_proto::bcbor::{self, Value};
use proptest::collection::{btree_map, vec};
use proptest::prelude::*;

/// One step of the DAG generator. Indices are taken modulo whatever they select from.
#[derive(Clone, Debug)]
struct Step {
    actor: u8,
    kind: u8,
    target: u8,
    choice: u8,
    parent: u16,
    second_parent: Option<u16>,
}

fn step() -> impl Strategy<Value = Step> {
    (
        any::<u8>(),
        0..10_u8,
        any::<u8>(),
        any::<u8>(),
        any::<u16>(),
        proptest::option::of(any::<u16>()),
    )
        .prop_map(
            |(actor, kind, target, choice, parent, second_parent)| Step {
                actor,
                kind,
                target,
                choice,
                parent,
                second_parent,
            },
        )
}

const POOL: std::ops::RangeInclusive<u8> = 21..=26;
const OUTSIDER: u8 = 40;

fn add_caps(choice: u8) -> CapSet {
    match choice % 5 {
        0 => Preset::FirstDevice.capabilities(),
        1 => Preset::Phone { execution: false }.capabilities(),
        2 => Preset::DesktopNode.capabilities(),
        3 => Preset::ReadOnly.capabilities(),
        _ => capability::cap_set(&[Capability::DEVICES_APPROVE, Capability::UI]),
    }
}

fn one_cap(choice: u8) -> CapSet {
    capability::cap_set(&[capability::ALL[usize::from(choice) % capability::ALL.len()]])
}

/// Builds a DAG from `steps`. Ops may be unauthorized; that is the point. Returns the
/// constellation and the ops in creation order (parents before children).
fn build(steps: &[Step]) -> (OpId, Vec<SignedOp>) {
    let first = key(*POOL.start());
    let genesis = Op {
        header: OpHeader {
            constellation: None,
            issuer: first.node_id(),
            parents: BTreeSet::new(),
            ts_ms: 0,
        },
        payload: OpPayload::Genesis {
            caps: Preset::FirstDevice.capabilities(),
            name: None,
            label: None,
            catalog_root: CatalogRoot([0; 16]),
        },
        extensions: BTreeMap::new(),
    };
    let genesis = SignedOp::sign(&genesis, &first).expect("signs");
    let constellation = genesis.id();
    let mut ops = vec![genesis];
    let mut keys: Vec<u8> = POOL.collect();
    let mut next_key = 27_u8;
    let mut roots = vec![CatalogRoot([0; 16])];
    for (index, step) in steps.iter().enumerate() {
        let ts_ms = u64::try_from(index).expect("small") + 1;
        let actor = keys[usize::from(step.actor) % keys.len()];
        let target = keys[usize::from(step.target) % keys.len()];
        let mut parents = BTreeSet::from([ops[usize::from(step.parent) % ops.len()].id()]);
        if let Some(second) = step.second_parent {
            parents.insert(ops[usize::from(second) % ops.len()].id());
        }
        let header = OpHeader {
            constellation: Some(constellation),
            issuer: key(actor).node_id(),
            parents,
            ts_ms,
        };
        let payload = match step.kind {
            0..=2 => OpPayload::Add {
                subject: key(target).node_id(),
                caps: add_caps(step.choice),
                name: None,
            },
            3 | 4 => OpPayload::Revoke {
                subject: key(target).node_id(),
                reason: None,
            },
            5 => OpPayload::Grant {
                subject: key(target).node_id(),
                caps_add: one_cap(step.choice),
                caps_remove: CapSet::new(),
            },
            6 => OpPayload::Grant {
                subject: key(target).node_id(),
                caps_add: CapSet::new(),
                caps_remove: one_cap(step.choice),
            },
            7 => {
                let new_key = key(next_key);
                keys.push(next_key);
                next_key += 1;
                OpPayload::Rotate {
                    new_key: new_key.node_id(),
                    new_key_sig: rotate_proof(&new_key, &key(actor).node_id(), &constellation),
                }
            }
            8 => OpPayload::PolicySet {
                policy: Value::Map(BTreeMap::from([(
                    0,
                    Value::Uint(u64::from(step.choice % 2)),
                )])),
            },
            _ => {
                let prev_root = roots[usize::from(step.choice) % roots.len()];
                let new_root = CatalogRoot([u8::try_from(roots.len()).unwrap_or(255); 16]);
                roots.push(new_root);
                OpPayload::CatalogRootMove {
                    new_root,
                    prev_root,
                }
            }
        };
        let op = Op {
            header,
            payload,
            extensions: BTreeMap::new(),
        };
        ops.push(SignedOp::sign(&op, &key(actor)).expect("signs"));
    }
    (constellation, ops)
}

fn log_of(constellation: OpId, ops: &[SignedOp]) -> MembershipLog {
    let mut log = MembershipLog::new(constellation).with_pending_limit(usize::MAX);
    for op in ops {
        log.insert(op.clone()).expect("insert");
    }
    log
}

type Semantic = (
    BTreeMap<NodeId, MemberStatus>,
    Vec<(NodeId, Caps)>,
    BTreeSet<NodeId>,
    Option<OpId>,
    Option<CatalogRoot>,
    BTreeSet<CatalogRoot>,
);

/// Everything a view says except the heads it was evaluated at.
fn semantic(view: &MembershipView) -> Semantic {
    (
        view.statuses().clone(),
        view.members()
            .map(|node| (*node, view.capabilities_of(node).clone()))
            .collect(),
        view.admins(),
        view.policy_op(),
        view.catalog_root(),
        view.superseded_roots().clone(),
    )
}

/// Whether an op can remove an admin: a revoke, a rotate, or a grant dropping `admin.full`.
fn takes_admin_away(op: &Op) -> bool {
    match &op.payload {
        OpPayload::Revoke { .. } | OpPayload::Rotate { .. } => true,
        OpPayload::Grant { caps_remove, .. } => caps_remove
            .iter()
            .any(|cap| cap.as_str() == Capability::ADMIN_FULL),
        _ => false,
    }
}

fn revoked(evaluation: &Evaluation) -> BTreeSet<NodeId> {
    evaluation
        .view
        .statuses()
        .iter()
        .filter(|(_, status)| **status == MemberStatus::Revoked)
        .map(|(node, _)| *node)
        .collect()
}

fn valid_revokes<'a>(log: &'a MembershipLog, evaluation: &Evaluation) -> Vec<&'a SignedOp> {
    evaluation
        .valid
        .iter()
        .filter_map(|id| log.get(id))
        .filter(|op| op.op().kind() == OpKind::Revoke)
        .collect()
}

/// Whether neither op is in the other's causal past.
fn concurrent(log: &MembershipLog, a: &OpId, b: &OpId) -> bool {
    a != b && !log.causal_past(a).contains(b) && !log.causal_past(b).contains(a)
}

fn value() -> impl Strategy<Value = Value> {
    let leaf = prop_oneof![
        any::<u64>().prop_map(Value::Uint),
        vec(any::<u8>(), 0..40).prop_map(Value::Bytes),
        "\\PC{0,12}".prop_map(Value::Text),
        any::<bool>().prop_map(Value::Bool),
        Just(Value::Null),
    ];
    leaf.prop_recursive(4, 64, 8, |inner| {
        prop_oneof![
            vec(inner.clone(), 0..8).prop_map(Value::Array),
            btree_map(any::<u64>(), inner, 0..8).prop_map(Value::Map),
        ]
    })
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    /// E1: every encoding decodes to its value and re-encodes identically.
    #[test]
    fn encodings_round_trip(value in value()) {
        let bytes = bcbor::encode(&value);
        prop_assert_eq!(bcbor::decode(&bytes), Ok(value));
    }

    /// E1: whatever decodes is canonical: re-encoding gives back exactly the input. Inputs are
    /// canonical encodings with one byte changed, which reach every decoder branch.
    #[test]
    fn only_canonical_bytes_decode(value in value(), position in any::<usize>(), byte in any::<u8>()) {
        let mut bytes = bcbor::encode(&value);
        let index = position % bytes.len();
        bytes[index] = byte;
        if let Ok(decoded) = bcbor::decode(&bytes) {
            prop_assert_eq!(bcbor::encode(&decoded), bytes);
        }
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(64))]

    /// E2: changing any bit of a signed envelope makes it fail to decode or verify.
    #[test]
    fn any_bit_flip_is_rejected(steps in vec(step(), 1..6), pick in any::<usize>(), bit in any::<usize>()) {
        let (_, ops) = build(&steps);
        let op = &ops[pick % ops.len()];
        let mut envelope = op.envelope();
        let bit = bit % (envelope.len() * 8);
        envelope[bit / 8] ^= 1 << (bit % 8);
        prop_assert!(SignedOp::decode_envelope(&envelope).is_err());
    }

    /// M1 (and M7, M9): the evaluation is a pure function of the op set, whatever the arrival
    /// order, and the free function over any input order agrees with the log.
    #[test]
    fn evaluation_is_order_independent(steps in vec(step(), 1..18), seed in any::<u64>()) {
        let (constellation, ops) = build(&steps);
        let reference = log_of(constellation, &ops).evaluate();
        let order = shuffled(&ops, seed);
        let other = log_of(constellation, &order).evaluate();
        prop_assert_eq!(&other, &reference);
        prop_assert_eq!(&core_membership::evaluate(constellation, order.iter().rev()), &reference);
    }

    /// Tombstones are monotone: every id revoked in a down-closed subset stays revoked in the
    /// whole set, and every revoke valid in a subset stays valid.
    #[test]
    fn tombstones_are_monotone(steps in vec(step(), 1..18)) {
        let (constellation, ops) = build(&steps);
        let log = log_of(constellation, &ops);
        let full = log.evaluate();
        let full_revoked = revoked(&full);
        for op in &ops {
            let sub = log.restricted_to(&BTreeSet::from([op.id()])).evaluate();
            prop_assert!(revoked(&sub).is_subset(&full_revoked));
            for revoke in valid_revokes(&log, &sub) {
                prop_assert!(full.valid.contains(&revoke.id()));
            }
        }
    }

    /// M3 and M4: a validly revoked id is not active, and none of its non-revoke ops outside the
    /// revoke's causal past took effect.
    #[test]
    fn revoke_wins(steps in vec(step(), 1..18)) {
        let (constellation, ops) = build(&steps);
        let log = log_of(constellation, &ops);
        let evaluation = log.evaluate();
        for revoke in valid_revokes(&log, &evaluation) {
            let subject = *revoke.op().subject().expect("revoke has a subject");
            prop_assert!(!evaluation.view.is_member(&subject));
            let past = log.causal_past(&revoke.id());
            for op in log.ops() {
                if op.op().header.issuer == subject
                    && op.op().kind() != OpKind::Revoke
                    && !past.contains(&op.id())
                {
                    prop_assert!(!evaluation.valid.contains(&op.id()));
                }
            }
        }
    }

    /// M6: no single op leaves zero admins. Every valid op had an admin in its past's state, and a
    /// locked constellation needs two concurrent valid ops that each take an admin away: a revoke,
    /// a rotate (which retires the old key) or a grant removing `admin.full`.
    ///
    /// A valid op X is authorized in `S_E(past(X))`, which uses the whole set's revokes. Evaluating
    /// `past(X)` on its own gives the same state exactly when it finds the same ops of `past(X)`
    /// valid; otherwise a valid revoke concurrent with X excluded one of them.
    #[test]
    fn no_single_op_removes_the_last_admin(steps in vec(step(), 1..14)) {
        let (constellation, ops) = build(&steps);
        let log = log_of(constellation, &ops);
        let evaluation = log.evaluate();
        for id in &evaluation.valid {
            if *id == constellation {
                continue;
            }
            let past_ids = log.causal_past(id);
            let past = log.restricted_to(&past_ids).evaluate();
            let valid_in_whole: BTreeSet<OpId> =
                evaluation.valid.intersection(&past_ids).copied().collect();
            if past.valid == valid_in_whole {
                prop_assert!(!past.view.admins().is_empty());
            } else {
                prop_assert!(
                    valid_revokes(&log, &evaluation).iter().any(|revoke| concurrent(&log, &revoke.id(), id)),
                    "the past's state differs only under a concurrent revoke"
                );
            }
        }
        if evaluation.view.is_locked() {
            let removers: Vec<&SignedOp> = log
                .ops()
                .filter(|op| evaluation.valid.contains(&op.id()) && takes_admin_away(op.op()))
                .collect();
            let any_concurrent = removers
                .iter()
                .any(|a| removers.iter().any(|b| concurrent(&log, &a.id(), &b.id())));
            prop_assert!(any_concurrent);
        }
    }

    /// M2: ops signed by a key that was never a member change nothing.
    #[test]
    fn outsider_ops_change_nothing(steps in vec(step(), 1..14), extra in vec(step(), 1..6)) {
        let (constellation, ops) = build(&steps);
        let reference = log_of(constellation, &ops).evaluate();
        let outsider = key(OUTSIDER);
        let mut with_outsider = ops.clone();
        for (index, step) in extra.iter().enumerate() {
            let parent = ops[usize::from(step.parent) % ops.len()].id();
            let payload = match step.kind % 4 {
                0 => OpPayload::Add {
                    subject: key(41).node_id(),
                    caps: Preset::FirstDevice.capabilities(),
                    name: None,
                },
                1 => OpPayload::Revoke { subject: key(*POOL.start()).node_id(), reason: None },
                2 => OpPayload::Grant {
                    subject: outsider.node_id(),
                    caps_add: one_cap(step.choice),
                    caps_remove: CapSet::new(),
                },
                _ => OpPayload::CatalogRootMove {
                    new_root: CatalogRoot([0xee; 16]),
                    prev_root: CatalogRoot([0; 16]),
                },
            };
            let op = Op {
                header: OpHeader {
                    constellation: Some(constellation),
                    issuer: outsider.node_id(),
                    parents: BTreeSet::from([parent]),
                    ts_ms: 1_000 + u64::try_from(index).expect("small"),
                },
                payload,
                extensions: BTreeMap::new(),
            };
            with_outsider.push(SignedOp::sign(&op, &outsider).expect("signs"));
        }
        let evaluation = log_of(constellation, &with_outsider).evaluate();
        prop_assert_eq!(semantic(&evaluation.view), semantic(&reference.view));
        let original: BTreeSet<OpId> = ops.iter().map(SignedOp::id).collect();
        prop_assert_eq!(
            evaluation.valid.intersection(&original).copied().collect::<BTreeSet<_>>(),
            reference.valid.clone()
        );
    }
}

#[test]
fn the_generator_produces_interesting_dags() {
    // A fixed run of the generator exercises revokes, rotations and rejections, so the properties
    // above are not vacuous.
    let steps: Vec<Step> = (0..40_u16)
        .map(|i| Step {
            actor: u8::try_from(i % 7).expect("small"),
            kind: u8::try_from(i % 10).expect("small"),
            target: u8::try_from((i * 3) % 11).expect("small"),
            choice: u8::try_from(i % 13).expect("small"),
            parent: i,
            second_parent: (i % 3 == 0).then_some(i / 2),
        })
        .collect();
    let (constellation, ops) = build(&steps);
    let evaluation = log_of(constellation, &ops).evaluate();
    assert!(!evaluation.rejections.is_empty());
    assert!(evaluation.valid.len() > 3);
}

#[test]
fn a_concurrent_revoke_can_restore_the_admin_a_valid_op_relies_on() {
    // N21 and N22 each drop their own admin.full concurrently; N21 then adds N23 with its
    // devices.approve. Concurrently N21 revokes N22, which excludes N22's grant, so in the whole
    // set N22 is still an admin in the add's past and the add is valid. The past alone has no
    // admin left: only the whole set's revokes give the add's authorizing state.
    let mut b = DagBuilder::default();
    for desc in [
        serde_json::json!({"op": "g", "kind": "genesis", "issuer": "N21", "caps_add": "preset:first_device", "catalog_root": "00000000000000000000000000000000"}),
        serde_json::json!({"op": "a22", "kind": "add", "issuer": "N21", "parents": ["g"], "subject": "N22", "caps_add": "preset:first_device"}),
        serde_json::json!({"op": "drop21", "kind": "grant", "issuer": "N21", "parents": ["a22"], "subject": "N21", "caps_add": [], "caps_remove": ["admin.full"]}),
        serde_json::json!({"op": "drop22", "kind": "grant", "issuer": "N22", "parents": ["a22"], "subject": "N22", "caps_add": [], "caps_remove": ["admin.full"]}),
        serde_json::json!({"op": "x", "kind": "add", "issuer": "N21", "parents": ["drop21", "drop22"], "subject": "N23", "caps_add": "preset:phone"}),
        serde_json::json!({"op": "r22", "kind": "revoke", "issuer": "N21", "parents": ["a22"], "subject": "N22"}),
    ] {
        b.add(&desc);
    }
    let constellation = b.constellation.expect("genesis");
    let log = log_of(constellation, &b.ops());
    let evaluation = log.evaluate();
    let x = b.id("x");
    assert!(evaluation.valid.contains(&x), "{:?}", evaluation.rejections);
    assert!(!evaluation.valid.contains(&b.id("drop22")));
    let past_ids = log.causal_past(&x);
    let past = log.restricted_to(&past_ids).evaluate();
    assert!(
        past.view.admins().is_empty(),
        "the past alone lost both admins"
    );
    assert_ne!(
        past.valid,
        evaluation
            .valid
            .intersection(&past_ids)
            .copied()
            .collect::<BTreeSet<_>>()
    );
    assert!(concurrent(&log, &b.id("r22"), &x));
}
