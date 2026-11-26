//! Runs `membership/membership-eval-v1.json`: each case's ops are signed with the test keys,
//! inserted into a log in several arrival orders (every order for cases marked `permutations: all`),
//! and evaluated; every order must give the expected view and exactly the expected rejections.

// Fixture tables and their checks read top to bottom; splitting them hides the table.
#![allow(clippy::too_many_lines)]

mod common;

use std::collections::{BTreeMap, BTreeSet};

use common::{DagBuilder, caps_from_json, hex, name_of_node, node_named, shuffled};
use core_membership::{Evaluation, MemberStatus, MembershipLog, SignedOp};
use core_testkit::load_value;
use serde_json::{Map, Value as Json};

const FIXTURE: &str = "membership/membership-eval-v1.json";

fn resolve_roots(desc: &Json, roots: &Map<String, Json>) -> Json {
    let mut desc = desc.clone();
    for field in ["catalog_root", "new_root", "prev_root"] {
        if let Some(name) = desc.get(field).and_then(Json::as_str)
            && let Some(hex) = roots.get(name)
        {
            desc[field] = hex.clone();
        }
    }
    desc
}

fn root_name(root: &[u8], roots: &Map<String, Json>) -> String {
    let text = hex(root);
    roots
        .iter()
        .find(|(_, value)| value.as_str() == Some(text.as_str()))
        .map_or(text, |(name, _)| name.clone())
}

/// The observable result of a case, with node ids, op ids and roots replaced by fixture names.
#[derive(Debug, PartialEq, Eq)]
struct Outcome {
    status: BTreeMap<String, String>,
    rotated_to: BTreeMap<String, String>,
    caps: BTreeMap<String, BTreeSet<String>>,
    admins: BTreeSet<String>,
    locked: bool,
    catalog_root: Option<String>,
    superseded: BTreeSet<String>,
    policy_op: Option<String>,
    names: BTreeMap<String, String>,
    label: Option<String>,
    rejections: BTreeMap<String, String>,
}

fn outcome(evaluation: &Evaluation, builder: &DagBuilder, roots: &Map<String, Json>) -> Outcome {
    let view = &evaluation.view;
    let mut status = BTreeMap::new();
    let mut rotated_to = BTreeMap::new();
    for (node, member_status) in view.statuses() {
        status.insert(name_of_node(node), member_status.name().to_owned());
        if let MemberStatus::Rotated { to } = member_status {
            rotated_to.insert(name_of_node(node), name_of_node(to));
        }
    }
    let caps = view
        .members()
        .filter(|node| !view.capabilities_of(node).is_empty())
        .map(|node| {
            (
                name_of_node(node),
                view.capabilities_of(node)
                    .iter()
                    .map(|c| c.as_str().to_owned())
                    .collect(),
            )
        })
        .collect();
    Outcome {
        status,
        rotated_to,
        caps,
        admins: view.admins().iter().map(name_of_node).collect(),
        locked: view.is_locked(),
        catalog_root: view.catalog_root().map(|r| root_name(&r.0, roots)),
        superseded: view
            .superseded_roots()
            .iter()
            .map(|r| root_name(&r.0, roots))
            .collect(),
        policy_op: view.policy_op().map(|id| builder.label_of(&id)),
        names: view
            .statuses()
            .keys()
            .filter_map(|node| {
                view.name_of(node)
                    .map(|name| (name_of_node(node), name.to_owned()))
            })
            .collect(),
        label: view.label().map(str::to_owned),
        rejections: evaluation
            .rejections
            .iter()
            .map(|(id, rejection)| (builder.label_of(id), rejection.code().to_owned()))
            .collect(),
    }
}

fn evaluate_in_order(constellation: core_membership::OpId, ops: &[SignedOp]) -> Evaluation {
    let mut log = MembershipLog::new(constellation);
    for op in ops {
        log.insert(op.clone()).expect("insert");
    }
    assert_eq!(log.pending().count(), 0, "every op joined the DAG");
    log.evaluate()
}

fn string_map(json: Option<&Json>) -> BTreeMap<String, String> {
    json.and_then(Json::as_object)
        .map(|object| {
            object
                .iter()
                .map(|(k, v)| (k.clone(), v.as_str().expect("string").to_owned()))
                .collect()
        })
        .unwrap_or_default()
}

fn string_set(json: Option<&Json>) -> BTreeSet<String> {
    json.and_then(Json::as_array)
        .map(|items| {
            items
                .iter()
                .map(|v| v.as_str().expect("string").to_owned())
                .collect()
        })
        .unwrap_or_default()
}

fn check_case(case: &Json, roots: &Map<String, Json>) -> usize {
    let name = case["name"].as_str().expect("name");
    let mut builder = DagBuilder::default();
    for desc in case["ops"].as_array().expect("ops") {
        builder.add(&resolve_roots(desc, roots));
    }
    let constellation = builder.constellation.expect("genesis first");
    let ops = builder.ops();
    let reference = outcome(&evaluate_in_order(constellation, &ops), &builder, roots);

    let expect = &case["expect"];
    let fill = |field: &str| expect.get(field) == Some(&Json::String("FILL".into()));
    assert!(
        !(fill("policy_op") || fill("catalog_root") || fill("superseded_roots")),
        "{name}: fill expectations from {reference:#?}"
    );
    assert_eq!(
        reference.status,
        string_map(expect.get("status")),
        "{name}: status"
    );
    assert_eq!(
        reference.rotated_to,
        string_map(expect.get("rotated_to")),
        "{name}: rotated_to"
    );
    let expected_caps: BTreeMap<String, BTreeSet<String>> = expect["caps"]
        .as_object()
        .expect("caps")
        .iter()
        .map(|(node, caps)| {
            (
                node.clone(),
                caps_from_json(caps)
                    .iter()
                    .map(|c| c.as_str().to_owned())
                    .collect(),
            )
        })
        .collect();
    assert_eq!(reference.caps, expected_caps, "{name}: caps");
    assert_eq!(
        reference.admins,
        string_set(expect.get("admins")),
        "{name}: admins"
    );
    assert_eq!(
        Some(reference.locked),
        expect["locked"].as_bool(),
        "{name}: locked"
    );
    match expect.get("catalog_root") {
        None => assert_eq!(
            reference.catalog_root.as_deref(),
            Some("R0"),
            "{name}: root"
        ),
        Some(Json::Null) => assert_eq!(reference.catalog_root, None, "{name}: root"),
        Some(root) => assert_eq!(
            reference.catalog_root.as_deref(),
            root.as_str(),
            "{name}: root"
        ),
    }
    assert_eq!(
        reference.superseded,
        string_set(expect.get("superseded_roots")),
        "{name}: superseded"
    );
    if let Some(policy_op) = expect.get("policy_op") {
        assert_eq!(
            reference.policy_op.as_deref(),
            policy_op.as_str(),
            "{name}: policy_op"
        );
    }
    if let Some(names) = expect.get("names") {
        assert_eq!(reference.names, string_map(Some(names)), "{name}: names");
    }
    if let Some(label) = expect.get("label") {
        assert_eq!(reference.label.as_deref(), label.as_str(), "{name}: label");
    }
    assert_eq!(
        reference.rejections,
        string_map(case.get("rejections")),
        "{name}: rejections"
    );

    let orders: Vec<Vec<SignedOp>> =
        if case.get("permutations").and_then(Json::as_str) == Some("all") {
            permutations(&ops)
        } else {
            let mut orders = vec![ops.iter().rev().cloned().collect()];
            orders.extend((1..=8).map(|seed| shuffled(&ops, seed)));
            orders
        };
    for order in &orders {
        let other = outcome(&evaluate_in_order(constellation, order), &builder, roots);
        assert_eq!(other, reference, "{name}: arrival order changed the result");
        // The free function over an unordered input agrees with the log.
        let direct = core_membership::evaluate(constellation, order.iter());
        assert_eq!(
            outcome(&direct, &builder, roots),
            reference,
            "{name}: direct"
        );
    }
    orders.len()
}

fn permutations<T: Clone>(items: &[T]) -> Vec<Vec<T>> {
    if items.len() <= 1 {
        return vec![items.to_vec()];
    }
    let mut out = Vec::new();
    for i in 0..items.len() {
        let mut rest = items.to_vec();
        let first = rest.remove(i);
        for mut tail in permutations(&rest) {
            tail.insert(0, first.clone());
            out.push(tail);
        }
    }
    out
}

#[test]
fn evaluation_fixtures() {
    let fixture = load_value(FIXTURE).expect("fixture loads");
    assert_eq!(fixture["version"], 1);
    let roots = fixture["roots"].as_object().expect("roots").clone();
    let cases = fixture["cases"].as_array().expect("cases");
    assert!(
        cases.len() >= 25,
        "the fixture plan asks for about 25 cases"
    );
    let mut names = BTreeSet::new();
    let mut runs = 0;
    for case in cases {
        assert!(names.insert(case["name"].as_str().expect("name").to_owned()));
        runs += check_case(case, &roots);
    }
    assert!(runs >= 720);
    // Fixture keys resolve to distinct nodes.
    assert_ne!(node_named("N1"), node_named("N2"));
}
