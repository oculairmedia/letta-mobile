//! Checks the on-disk layout against the store-v1 vectors: the table set, the application id and
//! the integer codes the spec fixes for states.

use std::collections::BTreeMap;

use core_store::contract::{claim, epoch, log};
use core_store::{DocState, PlacementState, Store};
use core_store_sqlite::SqliteStore;
use core_testkit::load_json;
use rusqlite::Connection;
use serde::Deserialize;

#[derive(Deserialize)]
struct Vectors {
    schema: Schema,
    codes: Codes,
}

#[derive(Deserialize)]
struct Schema {
    version: u32,
    application_id: i32,
    tables: Vec<String>,
}

#[derive(Deserialize)]
#[allow(
    clippy::struct_field_names,
    reason = "the names mirror the fixture keys"
)]
struct Codes {
    payload_state: BTreeMap<String, i64>,
    segment_state: BTreeMap<String, i64>,
    placement_state: BTreeMap<String, i64>,
    doc_state: BTreeMap<String, String>,
}

#[test]
fn the_file_matches_the_spec_layout_and_codes() {
    let vectors: Vectors = load_json("store/store-v1-vectors.json").expect("vectors");
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("node.db");
    let store = SqliteStore::open(&path).expect("open");
    let log_id = log(1);
    let genesis = claim(&log_id, &epoch(1, 1), None);
    store.commit_epoch_claim(genesis.clone()).expect("claim");
    store
        .write(|txn| {
            txn.set_placement_state(&log_id, &epoch(1, 1), PlacementState::SealedTo(epoch(2, 2)))?;
            txn.register_doc(&core_store::CatalogDoc {
                doc_id: core_store::DocId::from_bytes([1; 16]),
                class: "shell".into(),
                agent_id: None,
                state: DocState::Superseded,
                registered_at_ms: 1,
            })
        })
        .expect("write");

    let raw = Connection::open(&path).expect("raw");
    let version: u32 = raw
        .query_row("PRAGMA user_version", [], |r| r.get(0))
        .expect("v");
    let application_id: i32 = raw
        .query_row("PRAGMA application_id", [], |r| r.get(0))
        .expect("id");
    assert_eq!(version, vectors.schema.version);
    assert_eq!(application_id, vectors.schema.application_id);
    let mut statement = raw
        .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
        .expect("prepare");
    let tables: Vec<String> = statement
        .query_map([], |r| r.get(0))
        .expect("query")
        .map(|row| row.expect("row"))
        .collect();
    assert_eq!(tables, vectors.schema.tables);

    let payload: i64 = raw
        .query_row("SELECT payload_state FROM log_entry", [], |r| r.get(0))
        .expect("entry");
    assert_eq!(payload, vectors.codes.payload_state["stored"]);
    let segment: i64 = raw
        .query_row("SELECT state FROM segment", [], |r| r.get(0))
        .expect("segment");
    assert_eq!(segment, vectors.codes.segment_state["open"]);
    let (placement, sealed_n): (i64, i64) = raw
        .query_row("SELECT state, sealed_n FROM placement", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .expect("placement");
    assert_eq!(placement, vectors.codes.placement_state["sealed_to"]);
    assert_eq!(sealed_n, 2);
    let doc: String = raw
        .query_row("SELECT state FROM catalog_doc", [], |r| r.get(0))
        .expect("doc");
    assert_eq!(doc, vectors.codes.doc_state["superseded"]);
}
