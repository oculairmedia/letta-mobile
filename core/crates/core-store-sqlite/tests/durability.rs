//! What only a file-backed store can show: WAL mode, migrations, durability across reopen, and
//! that a transaction cut off before commit is absent from the file.

use core_store::contract::{claim, epoch, log, next_entry, payload_for};
use core_store::{BindingKey, Namespace, PayloadState, Store, StoreError};
use core_store_sqlite::{SqliteStore, latest_schema_version};
use rusqlite::Connection;

fn raw(path: &std::path::Path) -> Connection {
    Connection::open(path).expect("raw connection")
}

#[test]
fn a_fresh_file_is_wal_at_the_latest_schema() {
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("node.db");
    let store = SqliteStore::open(&path).expect("open");
    assert_eq!(store.schema_version(), latest_schema_version());
    assert_eq!(store.path(), path);
    let connection = raw(&path);
    let mode: String = connection
        .query_row("PRAGMA journal_mode", [], |row| row.get(0))
        .expect("pragma");
    assert_eq!(mode, "wal");
    let version: u32 = connection
        .query_row("PRAGMA user_version", [], |row| row.get(0))
        .expect("pragma");
    assert_eq!(version, latest_schema_version());
    let tables: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'",
            [],
            |row| row.get(0),
        )
        .expect("count");
    assert_eq!(tables, 12, "the v1 schema has 12 tables");
}

#[test]
fn committed_state_survives_reopen() {
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("node.db");
    let log_id = log(1);
    let genesis = claim(&log_id, &epoch(1, 1), None);
    let first = next_entry(genesis.entry(), 0);
    {
        let store = SqliteStore::open(&path).expect("open");
        store.commit_epoch_claim(genesis.clone()).expect("claim");
        let payload = payload_for(&log_id, &epoch(1, 1), 1, 0);
        store
            .write(|txn| txn.append_canonical(&first, Some(&payload)))
            .expect("append");
        store
            .bindings()
            .set_binding(&BindingKey::HooksDir, "/hooks")
            .expect("binding");
    }
    let store = SqliteStore::open(&path).expect("reopen without migrating again");
    store
        .read(|txn| {
            assert_eq!(txn.placement(&log_id)?.map(|p| p.epoch), Some(epoch(1, 1)));
            let segment = txn.segment(&log_id, &epoch(1, 1))?.expect("segment");
            assert_eq!(segment.head_seq, 1);
            assert_eq!(segment.complete_seq, Some(1));
            assert_eq!(
                txn.entry(&first.entry_hash)?.map(|e| e.payload_state),
                Some(PayloadState::Stored)
            );
            Ok::<_, StoreError>(())
        })
        .expect("read");
    assert_eq!(
        store
            .bindings()
            .binding(&BindingKey::HooksDir)
            .expect("binding"),
        Some("/hooks".into())
    );
}

#[test]
fn a_transaction_cut_off_before_commit_is_not_in_the_file() {
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("node.db");
    let snapshot = dir.path().join("snapshot");
    std::fs::create_dir(&snapshot).expect("snapshot dir");
    let store = SqliteStore::open(&path).expect("open");
    store
        .write(|txn| txn.kv_put(Namespace::SAMOD, b"committed", b"v"))
        .expect("seed");
    let log_id = log(2);
    store
        .write(|txn| {
            txn.adopt_epoch(claim(&log_id, &epoch(1, 1), None))?;
            txn.kv_put(Namespace::SAMOD, b"torn", &vec![7_u8; 200_000])?;
            // Copy the database and its WAL as they are on disk mid-transaction: what a power cut
            // would leave behind. (The -shm index is rebuilt from the WAL on open.)
            for suffix in ["", "-wal"] {
                let from = dir.path().join(format!("node.db{suffix}"));
                if from.exists() {
                    std::fs::copy(&from, snapshot.join(format!("node.db{suffix}"))).expect("copy");
                }
            }
            Ok::<_, StoreError>(())
        })
        .expect("write");
    drop(store);
    let survivor = SqliteStore::open(snapshot.join("node.db")).expect("open the cut-off copy");
    survivor
        .read(|txn| {
            assert!(txn.kv_get(Namespace::SAMOD, b"committed")?.is_some());
            assert_eq!(txn.kv_get(Namespace::SAMOD, b"torn")?, None);
            assert_eq!(
                txn.placement(&log_id)?,
                None,
                "claim, entry and mark: all or nothing"
            );
            assert!(txn.logs()?.is_empty());
            Ok::<_, StoreError>(())
        })
        .expect("read");
}

#[test]
fn a_newer_schema_is_refused() {
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("node.db");
    drop(SqliteStore::open(&path).expect("open"));
    raw(&path)
        .execute_batch("PRAGMA user_version = 99;")
        .expect("bump");
    assert!(matches!(
        SqliteStore::open(&path),
        Err(StoreError::SchemaTooNew { found: 99, .. })
    ));
}

#[test]
fn a_foreign_database_is_refused() {
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("other.db");
    raw(&path)
        .execute_batch("CREATE TABLE t (x); PRAGMA user_version = 1;")
        .expect("foreign db");
    assert!(matches!(
        SqliteStore::open(&path),
        Err(StoreError::Corrupt(_))
    ));
}
