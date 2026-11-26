//! The SQLite store runs the full store contract suite, each case on a fresh database file.

use core_store::contract::StoreFixture;
use core_store_sqlite::SqliteStore;
use tempfile::TempDir;

struct SqliteFixture {
    store: SqliteStore,
    _dir: TempDir,
}

impl StoreFixture for SqliteFixture {
    type Store = SqliteStore;
    fn store(&self) -> &SqliteStore {
        &self.store
    }
}

fn fresh() -> SqliteFixture {
    let dir = tempfile::tempdir().expect("temp dir");
    let store = SqliteStore::open(dir.path().join("node.db")).expect("open");
    SqliteFixture { store, _dir: dir }
}

core_store::store_contract_suite!(fresh);
