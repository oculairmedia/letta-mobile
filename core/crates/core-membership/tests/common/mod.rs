//! Shared helpers for the membership tests: fixed test keys, the fixture value notation, and a
//! builder that turns fixture op descriptions into signed ops.

#![allow(dead_code, unreachable_pub)]

use std::collections::{BTreeMap, BTreeSet};

use core_membership::capability::{CapSet, Capability, Preset};
use core_membership::op::{CatalogRoot, Op, OpHeader, OpPayload};
use core_membership::{NodeKey, OpId, SignedOp};
use core_proto::NodeId;
use core_proto::bcbor::{self, Value};
use core_testkit::{decode_hex, encode_hex};
use serde_json::Value as Json;

/// Test key `Nk`: the Ed25519 key with seed `k` repeated 32 times.
pub fn key(k: u8) -> NodeKey {
    NodeKey::from_seed(&[k; 32])
}

/// The key named `N<k>`.
pub fn key_named(name: &str) -> NodeKey {
    let k: u8 = name
        .strip_prefix('N')
        .and_then(|digits| digits.parse().ok())
        .unwrap_or_else(|| panic!("key names are N<k>: {name}"));
    key(k)
}

/// The node id of `N<k>`.
pub fn node_named(name: &str) -> NodeId {
    key_named(name).node_id()
}

/// The name `N<k>` of a test node id, for k in 1..=32.
pub fn name_of_node(node: &NodeId) -> String {
    (1..=32_u8)
        .find(|k| key(*k).node_id() == *node)
        .map_or_else(|| node.to_hex(), |k| format!("N{k}"))
}

pub fn unhex(text: &str) -> Vec<u8> {
    decode_hex(text).expect("valid hex")
}

pub fn hex(bytes: &[u8]) -> String {
    encode_hex(bytes)
}

pub fn root(text: &str) -> CatalogRoot {
    CatalogRoot(unhex(text).try_into().expect("a root is 16 bytes"))
}

/// Parses the fixture value notation.
pub fn value_from_json(json: &Json) -> Value {
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

/// A capability list, or `"preset:<name>"`.
pub fn caps_from_json(json: &Json) -> CapSet {
    match json {
        Json::String(text) => {
            let name = text.strip_prefix("preset:").expect("preset:<name>");
            Preset::from_name(name)
                .unwrap_or_else(|| panic!("unknown preset {name}"))
                .capabilities()
        }
        Json::Array(items) => items
            .iter()
            .map(|item| Capability::new(item.as_str().expect("cap text")).expect("valid cap"))
            .collect(),
        other => panic!("bad caps {other}"),
    }
}

/// Builds signed ops from fixture descriptions, resolving parent labels to op ids.
#[derive(Default)]
pub struct DagBuilder {
    pub constellation: Option<OpId>,
    pub by_label: BTreeMap<String, SignedOp>,
    pub order: Vec<String>,
}

impl DagBuilder {
    pub fn id(&self, label: &str) -> OpId {
        self.by_label
            .get(label)
            .unwrap_or_else(|| panic!("unknown op label {label}"))
            .id()
    }

    pub fn label_of(&self, id: &OpId) -> String {
        self.by_label
            .iter()
            .find(|(_, op)| op.id() == *id)
            .map_or_else(|| id.to_string(), |(label, _)| label.clone())
    }

    pub fn ops(&self) -> Vec<SignedOp> {
        self.order
            .iter()
            .map(|label| self.by_label[label].clone())
            .collect()
    }

    /// Adds the op described by `desc` and returns it.
    pub fn add(&mut self, desc: &Json) -> SignedOp {
        let field = |name: &str| desc.get(name);
        let text = |name: &str| field(name).and_then(Json::as_str).map(str::to_owned);
        let label = text("op").expect("op label");
        let kind = text("kind").expect("kind");
        let issuer_key = key_named(&text("issuer").expect("issuer"));
        let parents: BTreeSet<OpId> = field("parents")
            .and_then(Json::as_array)
            .map(|labels| {
                labels
                    .iter()
                    .map(|l| self.id(l.as_str().expect("label")))
                    .collect()
            })
            .unwrap_or_default();
        let header = OpHeader {
            constellation: if kind == "genesis" {
                None
            } else {
                Some(self.constellation.expect("genesis first"))
            },
            issuer: issuer_key.node_id(),
            parents,
            ts_ms: field("ts_ms").and_then(Json::as_u64).unwrap_or(0),
        };
        let subject = || node_named(&text("subject").expect("subject"));
        let caps = |name: &str| field(name).map(caps_from_json).unwrap_or_default();
        let payload = match kind.as_str() {
            "genesis" => OpPayload::Genesis {
                caps: caps("caps_add"),
                name: text("name"),
                label: text("label"),
                catalog_root: root(&text("catalog_root").expect("catalog_root")),
            },
            "add" => OpPayload::Add {
                subject: subject(),
                caps: caps("caps_add"),
                name: text("name"),
            },
            "revoke" => OpPayload::Revoke {
                subject: subject(),
                reason: text("reason"),
            },
            "grant" => OpPayload::Grant {
                subject: subject(),
                caps_add: caps("caps_add"),
                caps_remove: caps("caps_remove"),
            },
            "rotate" => {
                let new_key = key_named(&text("new_key").expect("new_key"));
                let pop_signer = text("pop_signer").map_or_else(
                    || key_named(&text("new_key").expect("new_key")),
                    |name| key_named(&name),
                );
                let constellation = self.constellation.expect("genesis first");
                // A proof signed by another key is the "bad PoP" case.
                let sig = pop_signer.sign(&core_membership::crypto::rotate_pop_message(
                    &issuer_key.node_id(),
                    &new_key.node_id(),
                    &constellation,
                ));
                OpPayload::Rotate {
                    new_key: new_key.node_id(),
                    new_key_sig: sig,
                }
            }
            "policy_set" => OpPayload::PolicySet {
                policy: value_from_json(field("policy").expect("policy")),
            },
            "catalog_root_move" => OpPayload::CatalogRootMove {
                new_root: root(&text("new_root").expect("new_root")),
                prev_root: root(&text("prev_root").expect("prev_root")),
            },
            other => panic!("unknown kind {other}"),
        };
        let op = Op {
            header,
            payload,
            extensions: BTreeMap::new(),
        };
        let signed = SignedOp::sign(&op, &issuer_key).expect("signs");
        if kind == "genesis" {
            self.constellation = Some(signed.id());
        }
        self.by_label.insert(label.clone(), signed.clone());
        self.order.push(label);
        signed
    }
}

/// A deterministic shuffle (xorshift), so fixture runs are reproducible.
pub fn shuffled<T: Clone>(items: &[T], seed: u64) -> Vec<T> {
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

/// Encodes a map whose entries are already-encoded values, in the given order, so tests can build
/// non-canonical bodies.
pub fn raw_map(entries: &[(u64, Vec<u8>)]) -> Vec<u8> {
    assert!(entries.len() < 24);
    let mut out = vec![0xa0 | u8::try_from(entries.len()).expect("small")];
    for (key, value) in entries {
        out.extend(bcbor::encode(&Value::Uint(*key)));
        out.extend_from_slice(value);
    }
    out
}

/// The entries of a canonical op body, each value encoded.
pub fn body_entries(op: &Op) -> Vec<(u64, Vec<u8>)> {
    let Value::Map(map) = op.to_value() else {
        panic!("op is a map")
    };
    map.iter().map(|(k, v)| (*k, bcbor::encode(v))).collect()
}

/// Signs arbitrary body bytes with `key` and wraps them in an envelope, valid or not.
pub fn sign_raw(body: &[u8], key: &NodeKey) -> Vec<u8> {
    let id = OpId::of_body(body);
    let sig = key.sign(id.as_bytes());
    bcbor::encode(&Value::Array(vec![
        Value::Bytes(body.to_vec()),
        Value::Bytes(sig.as_bytes().to_vec()),
    ]))
}
