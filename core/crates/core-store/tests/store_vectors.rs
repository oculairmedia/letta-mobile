//! Runs the store-v1 vectors: key-path encoding and binding keys, decoded and encoded both ways.

use core_store::kv::{decode_key_path, encode_key_path};
use core_store::{BindingKey, SCHEMA_VERSION, StoreError};
use core_testkit::{decode_hex, encode_hex, load_json};
use serde::Deserialize;

#[derive(Deserialize)]
struct Vectors {
    schema: Schema,
    key_paths: Vec<KeyPath>,
    invalid_key_paths: Vec<Invalid>,
    binding_keys: Vec<Binding>,
    invalid_binding_keys: Vec<InvalidBinding>,
}

#[derive(Deserialize)]
struct Schema {
    version: u32,
}

#[derive(Deserialize)]
struct KeyPath {
    name: String,
    components: Vec<String>,
    hex: String,
}

#[derive(Deserialize)]
struct Invalid {
    name: String,
    hex: String,
}

#[derive(Deserialize)]
struct Binding {
    kind: String,
    name: Option<String>,
    encoded: String,
}

#[derive(Deserialize)]
struct InvalidBinding {
    name: String,
    encoded: String,
}

fn vectors() -> Vectors {
    load_json("store/store-v1-vectors.json").expect("store vectors")
}

#[test]
fn the_schema_version_matches_the_spec() {
    assert_eq!(vectors().schema.version, SCHEMA_VERSION);
}

#[test]
fn key_paths_encode_and_decode() {
    for vector in vectors().key_paths {
        let encoded = encode_key_path(&vector.components);
        assert_eq!(encode_hex(&encoded), vector.hex, "{}", vector.name);
        let bytes = decode_hex(&vector.hex).expect("hex");
        assert_eq!(
            decode_key_path(&bytes).expect("decodes"),
            vector.components,
            "{}",
            vector.name
        );
    }
}

#[test]
fn malformed_key_paths_are_refused() {
    for vector in vectors().invalid_key_paths {
        let bytes = decode_hex(&vector.hex).expect("hex");
        assert!(
            matches!(decode_key_path(&bytes), Err(StoreError::Corrupt(_))),
            "{}",
            vector.name
        );
    }
}

#[test]
fn binding_keys_encode_and_decode() {
    for vector in vectors().binding_keys {
        let key = match (vector.kind.as_str(), vector.name) {
            ("model_profile", Some(name)) => BindingKey::ModelProfile(name),
            ("workspace_path", Some(id)) => BindingKey::WorkspacePath(id),
            ("hooks_dir", None) => BindingKey::HooksDir,
            (kind, name) => panic!("unknown vector kind {kind} {name:?}"),
        };
        assert_eq!(key.encode().expect("encodes"), vector.encoded);
        assert_eq!(BindingKey::decode(&vector.encoded).expect("decodes"), key);
    }
}

#[test]
fn unknown_binding_keys_are_refused() {
    for vector in vectors().invalid_binding_keys {
        assert!(
            matches!(
                BindingKey::decode(&vector.encoded),
                Err(StoreError::Corrupt(_))
            ),
            "{}",
            vector.name
        );
    }
}
