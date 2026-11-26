//! Runs `membership/bcbor-v1.json`: every valid vector decodes to its value and encodes back to its
//! bytes; every invalid vector is rejected with its code.

use core_proto::bcbor::{self, Value};
use core_testkit::{decode_hex, load_value};
use serde_json::Value as Json;

fn unhex(text: &str) -> Vec<u8> {
    decode_hex(text).expect("valid hex")
}

/// Parses the fixture value notation.
fn value_from_json(json: &Json) -> Value {
    let object = json.as_object().expect("a value is an object");
    let (tag, inner) = object.iter().next().expect("one tag");
    match tag.as_str() {
        "uint" => Value::Uint(match inner {
            Json::String(text) => text.parse().expect("decimal uint"),
            other => other.as_u64().expect("uint"),
        }),
        "bytes" => Value::Bytes(unhex(inner.as_str().expect("hex"))),
        "text" => Value::Text(inner.as_str().expect("text").to_owned()),
        "array" => Value::Array(
            inner
                .as_array()
                .expect("array")
                .iter()
                .map(value_from_json)
                .collect(),
        ),
        "map" => Value::Map(
            inner
                .as_array()
                .expect("map entries")
                .iter()
                .map(|pair| {
                    let pair = pair.as_array().expect("[key, value]");
                    (
                        pair[0].as_u64().expect("uint key"),
                        value_from_json(&pair[1]),
                    )
                })
                .collect(),
        ),
        "bool" => Value::Bool(inner.as_bool().expect("bool")),
        "null" => Value::Null,
        "depth" => {
            let mut value = Value::Array(vec![]);
            for _ in 1..inner.as_u64().expect("depth") {
                value = Value::Array(vec![value]);
            }
            value
        }
        other => panic!("unknown value tag {other}"),
    }
}

#[test]
fn bcbor_vectors() {
    let fixture = load_value("membership/bcbor-v1.json").expect("fixture loads");
    assert_eq!(fixture["version"], 1);
    let valid = fixture["valid"].as_array().expect("valid");
    let invalid = fixture["invalid"].as_array().expect("invalid");
    assert!(valid.len() >= 25 && invalid.len() >= 25);
    for vector in valid {
        let name = vector["name"].as_str().expect("name");
        let bytes = unhex(vector["hex"].as_str().expect("hex"));
        let value = value_from_json(&vector["value"]);
        assert_eq!(bcbor::decode(&bytes).as_ref(), Ok(&value), "{name} decodes");
        assert_eq!(bcbor::encode(&value), bytes, "{name} encodes");
    }
    for vector in invalid {
        let name = vector["name"].as_str().expect("name");
        let bytes = unhex(vector["hex"].as_str().expect("hex"));
        let error = bcbor::decode(&bytes).expect_err(name);
        assert_eq!(error.code(), vector["error"], "{name}");
    }
}
