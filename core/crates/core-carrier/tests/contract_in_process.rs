//! The node-carrier v1 contract suite on the in-process carrier, plus the fixture's own round trip.

use core_carrier::contract::{InProcessHarness, load_fixture};
use serde_json::Value;

core_carrier::node_carrier_contract_tests!(InProcessHarness);

/// Collects every frame object the fixture lists (expected frames, replies, responses).
fn frames(value: &Value, found: &mut Vec<Value>) {
    match value {
        Value::Object(map) => {
            for (key, child) in map {
                if matches!(key.as_str(), "expect_frame" | "frame" | "response") {
                    found.push(child.clone());
                }
                frames(child, found);
            }
        }
        Value::Array(items) => items.iter().for_each(|item| frames(item, found)),
        _ => {}
    }
}

#[test]
fn every_fixture_frame_decodes_and_encodes_unchanged() {
    let fixture = load_fixture().expect("fixture");
    assert_eq!(fixture["contract"], "node-carrier");
    assert_eq!(fixture["version"], 1);
    let mut found = Vec::new();
    frames(&fixture, &mut found);
    assert!(found.len() > 40, "found only {} frames", found.len());
    for frame in found {
        assert!(frame.is_object(), "{frame} is not a frame object");
        let text = frame.to_string();
        let decoded: Value = serde_json::from_str(&text).expect("re-decodes");
        assert_eq!(decoded, frame);
        assert_eq!(decoded.to_string(), text, "re-encodes byte for byte");
    }
}

#[test]
fn the_fixture_spells_the_ordered_event_seq_capability_exactly() {
    let text = std::fs::read_to_string(core_carrier::contract::fixture_path()).expect("fixture");
    assert!(text.contains(core_proto::capability::EVENT_SEQ_ORDERED_V1));
}
