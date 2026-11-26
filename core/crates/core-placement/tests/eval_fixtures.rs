//! Runs `placement/placement-v1-eval.json`: claim verdicts and the canonical segment under a
//! membership view (every case in script order, reversed, and a rotated order, on fresh replicas),
//! and the failover evaluator.

mod common;

use std::collections::BTreeMap;

use common::{
    Members, Replica, Script, agent, build_script, epoch_json, log, named, node, verdict,
};
use core_log::Pos;
use core_placement::failover::{self, FailoverDecision, Liveness, NodeClass, PeerFacts};
use core_placement::{FailoverInput, PlacementRecord};
use core_proto::Epoch;
use core_store::{EntryHash, PlacementState, SegmentState};
use core_testkit::load_value;
use serde_json::{Value as Json, json};

fn fixture() -> Json {
    load_value("placement/placement-v1-eval.json").expect("fixture loads")
}

fn state_name(state: PlacementState) -> &'static str {
    match state {
        PlacementState::Active => "active",
        PlacementState::SealedTo(_) => "sealed_to",
        PlacementState::Placeless => "placeless",
        PlacementState::SupersededUnknown => "superseded_unknown",
    }
}

/// Replaces the placeholders of the equal-n case with the real order of N2 and N3 (the winner,
/// and the loser's open).
fn resolve(expect: &Json) -> Json {
    let (higher, lower) = if node(2).as_bytes() > node(3).as_bytes() {
        ("N2", "N3")
    } else {
        ("N3", "N2")
    };
    let text = expect
        .to_string()
        .replace("OPEN_OF_LOWER_N2_N3", &format!("o{}", &lower[1..]))
        .replace("HIGHER_N2_N3", higher);
    serde_json::from_str(&text).expect("json")
}

fn check(name: &str, members: &Members, script: &Script, order: &[String], expect: &Json) {
    let view = members.view();
    let mut replica = Replica::new();
    for id in order {
        replica.deliver(&script.entries[id], &view);
    }
    let evaluation = replica.evaluate(&view);
    let analysis = replica.analysis(&view);
    let expect = resolve(expect);
    assert_eq!(
        evaluation.canonical.map_or(Json::Null, |e| epoch_json(&e)),
        expect["canonical"],
        "{name}: canonical"
    );
    if let Some(state) = expect.get("state") {
        assert_eq!(
            analysis.placement_state.map(state_name),
            state.as_str(),
            "{name}: state"
        );
    }
    for (id, code) in expect["verdicts"].as_object().expect("verdicts") {
        let open = script
            .entries
            .get(id)
            .unwrap_or_else(|| panic!("{name}: no entry {id}"));
        let parent = script.parents.get(id).map(|p| &script.entries[p]);
        assert_eq!(
            verdict(&view, open, parent).code(),
            code.as_str().expect("code"),
            "{name}: verdict of {id}"
        );
    }
    let segment_state = |id: &str| {
        let e: Epoch = script
            .entries
            .get(id)
            .unwrap_or_else(|| panic!("{name}: no entry {id}"))
            .epoch();
        analysis
            .segments
            .iter()
            .find(|s| s.epoch == e)
            .map(|s| s.state)
    };
    for id in expect
        .get("divergent")
        .and_then(Json::as_array)
        .into_iter()
        .flatten()
    {
        let id = id.as_str().expect("id");
        assert_eq!(
            segment_state(id),
            Some(SegmentState::Divergent),
            "{name}: {id} divergent"
        );
    }
    for id in expect
        .get("ancestors")
        .and_then(Json::as_array)
        .into_iter()
        .flatten()
    {
        let id = id.as_str().expect("id");
        assert_eq!(
            segment_state(id),
            Some(SegmentState::CanonicalAncestor),
            "{name}: {id} canonical ancestor"
        );
    }
}

#[test]
fn judge_cases() {
    let fixture = fixture();
    let cases = fixture["judge"].as_array().expect("judge cases");
    assert!(cases.len() >= 20);
    for case in cases {
        let name = case["name"].as_str().expect("name");
        let script = build_script(case["log"].as_array().expect("log"));
        let mut orders = vec![script.order.clone()];
        let mut reversed = script.order.clone();
        reversed.reverse();
        orders.push(reversed);
        let mut rotated = script.order.clone();
        rotated.rotate_left(script.order.len() / 2);
        orders.push(rotated);
        for order in &orders {
            let mut members = Members::new();
            for op in case["members"].as_array().expect("members") {
                members.apply(op);
            }
            check(name, &members, &script, order, &case["expect"]);
            if let Some(then) = case.get("then") {
                for op in then["members"].as_array().expect("members") {
                    members.apply(op);
                }
                check(
                    &format!("{name} (then)"),
                    &members,
                    &script,
                    order,
                    &then["expect"],
                );
            }
        }
    }
}

fn decision_json(decision: FailoverDecision) -> Json {
    match decision {
        FailoverDecision::Idle(reason) => json!({"decision": "idle", "reason": reason.code()}),
        FailoverDecision::Wait { until_ms, rank } => {
            json!({"decision": "wait", "until_ms": until_ms, "rank": rank})
        }
        FailoverDecision::Probe { rank } => json!({"decision": "probe", "rank": rank}),
        FailoverDecision::Claim { rank } => json!({"decision": "claim", "rank": rank}),
    }
}

fn record_of(placement: &Json) -> PlacementRecord {
    let n = placement["n"].as_u64().expect("n");
    let holder = named(placement["holder"].as_str().expect("holder"));
    let epoch = Epoch {
        n,
        claimant: holder,
    };
    let state = match placement["state"].as_str().expect("state") {
        "active" => PlacementState::Active,
        "sealed_to" => {
            let next = &placement["next"];
            PlacementState::SealedTo(Epoch {
                n: next[0].as_u64().expect("n"),
                claimant: named(next[1].as_str().expect("claimant")),
            })
        }
        "placeless" => PlacementState::Placeless,
        _ => PlacementState::SupersededUnknown,
    };
    PlacementRecord {
        log_id: log(),
        agent_id: agent(),
        epoch,
        state,
        max_n_seen: placement.get("max_n").and_then(Json::as_u64).unwrap_or(n),
        tip: Pos {
            seq: 5,
            hash: EntryHash::from_bytes([5; 32]),
        },
    }
}

#[test]
fn failover_cases() {
    let fixture = fixture();
    let cases = fixture["failover"].as_array().expect("failover cases");
    assert!(cases.len() >= 20);
    for case in cases {
        let name = case["name"].as_str().expect("name");
        let mut members = Members::new();
        for op in case["members"].as_array().expect("members") {
            members.apply(op);
        }
        let view = members.view();
        let facts = PeerFacts {
            classes: case["classes"]
                .as_object()
                .expect("classes")
                .iter()
                .map(|(k, v)| {
                    (
                        named(k),
                        NodeClass::from_name(v.as_str().expect("class")).expect("class"),
                    )
                })
                .collect(),
            complete_seq: case["complete"]
                .as_object()
                .expect("complete")
                .iter()
                .map(|(k, v)| (named(k), v.as_u64().expect("seq")))
                .collect::<BTreeMap<_, _>>(),
        };
        let live = &case["liveness"];
        let record = record_of(&case["placement"]);
        let input = FailoverInput {
            view: &view,
            placement: &record,
            me: named(case["me"].as_str().expect("me")),
            facts: &facts,
            liveness: Liveness {
                last_heard_ms: live["last_heard_ms"].as_u64().expect("heard"),
                last_dial_failure_ms: live.get("dial_failure_ms").and_then(Json::as_u64),
                peer_heard_ms: live.get("peer_heard_ms").and_then(Json::as_u64),
            },
            runtime_parity_matches: case["parity_matches"].as_bool().expect("parity"),
            now_ms: case["now_ms"].as_u64().expect("now"),
        };
        assert_eq!(
            decision_json(failover::evaluate(&input)),
            case["expect"],
            "{name}"
        );
    }
}
