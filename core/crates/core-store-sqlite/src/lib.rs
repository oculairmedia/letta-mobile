//! The SQLite [`Store`] for a Baken node.
//!
//! One database file per node (SQLite adds its `-wal` and `-shm` sidecars next to it), opened in
//! WAL mode with `synchronous = FULL`, so a committed transaction survives power loss. The schema
//! is versioned by `PRAGMA user_version` and migrated forward on open; a database written by a
//! newer build is refused, never downgraded. The normative text is
//! `docs/reference/contracts/store/store-v1.md`.
//!
//! Three connections share the file: a writer (every [`Store::write`] is one `BEGIN IMMEDIATE`
//! transaction), a reader (every [`Store::read`] is one snapshot), and a bindings connection for
//! the node-local table, which no replicated transaction can reach.
//!
//! This is the only crate that names rusqlite. It is native only; the wasm client uses
//! [`core_store::MemoryStore`].

mod schema;
mod txn;

use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use core_store::{BindingKey, BindingsStore, Store, StoreError, Txn};
use rusqlite::{Connection, OpenFlags, OptionalExtension, TransactionBehavior, params};

use crate::txn::{SqlResult, SqliteTxn};

/// How long a connection waits for another connection's lock before failing.
const BUSY_TIMEOUT: Duration = Duration::from_secs(5);

/// The schema version this build migrates to.
#[must_use]
pub fn latest_schema_version() -> u32 {
    u32::try_from(schema::MIGRATIONS.len()).unwrap_or(u32::MAX)
}

/// A node's store in one SQLite database file.
pub struct SqliteStore {
    path: PathBuf,
    writer: Mutex<Connection>,
    reader: Mutex<Connection>,
    bindings: SqliteBindings,
    schema_version: u32,
}

impl SqliteStore {
    /// Opens (creating if needed) the node store at `path` and migrates it to the latest schema.
    ///
    /// # Errors
    ///
    /// [`StoreError::SchemaTooNew`] for a database from a newer build; [`StoreError::Corrupt`] for a
    /// file that is not a Baken node store; [`StoreError::Backend`] when SQLite fails (including
    /// when WAL mode cannot be enabled).
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StoreError> {
        let path = path.as_ref().to_path_buf();
        let mut writer = connect(&path, false)?;
        let schema_version = migrate(&mut writer)?;
        let reader = connect(&path, true)?;
        let bindings = SqliteBindings {
            connection: Mutex::new(connect(&path, false)?),
        };
        Ok(Self {
            path,
            writer: Mutex::new(writer),
            reader: Mutex::new(reader),
            bindings,
            schema_version,
        })
    }

    /// The database file.
    #[must_use]
    pub fn path(&self) -> &Path {
        &self.path
    }
}

/// Opens and configures one connection.
fn connect(path: &Path, read_only: bool) -> Result<Connection, StoreError> {
    let flags = if read_only {
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX
    } else {
        OpenFlags::SQLITE_OPEN_READ_WRITE
            | OpenFlags::SQLITE_OPEN_CREATE
            | OpenFlags::SQLITE_OPEN_NO_MUTEX
    };
    let connection = Connection::open_with_flags(path, flags).sql()?;
    connection.busy_timeout(BUSY_TIMEOUT).sql()?;
    if !read_only {
        let mode: String = connection
            .query_row("PRAGMA journal_mode = WAL", [], |row| row.get(0))
            .sql()?;
        if !mode.eq_ignore_ascii_case("wal") {
            return Err(StoreError::Backend(format!(
                "journal_mode is {mode}, WAL is required"
            )));
        }
    }
    connection
        .execute_batch("PRAGMA synchronous = FULL; PRAGMA foreign_keys = ON;")
        .sql()?;
    Ok(connection)
}

/// Brings the schema to the latest version, one transaction per migration.
fn migrate(connection: &mut Connection) -> Result<u32, StoreError> {
    let application_id: i32 = connection
        .query_row("PRAGMA application_id", [], |row| row.get(0))
        .sql()?;
    let found: u32 = connection
        .query_row("PRAGMA user_version", [], |row| row.get(0))
        .sql()?;
    let latest = latest_schema_version();
    if application_id != schema::APPLICATION_ID && (application_id != 0 || found != 0) {
        return Err(StoreError::Corrupt(format!(
            "not a Baken node store (application_id {application_id:#x}, user_version {found})"
        )));
    }
    if found > latest {
        return Err(StoreError::SchemaTooNew {
            found,
            supported: latest,
        });
    }
    for version in found..latest {
        let index = usize::try_from(version).unwrap_or(usize::MAX);
        let tx = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .sql()?;
        tx.execute_batch(schema::MIGRATIONS[index]).sql()?;
        tx.execute_batch(&format!(
            "PRAGMA application_id = {}; PRAGMA user_version = {};",
            schema::APPLICATION_ID,
            version + 1
        ))
        .sql()?;
        tx.commit().sql()?;
    }
    Ok(latest)
}

fn lock(connection: &Mutex<Connection>) -> MutexGuard<'_, Connection> {
    // A panic inside a closure drops (and so rolls back) its transaction before the guard is
    // released, so the connection behind a poisoned lock is clean.
    connection.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Store for SqliteStore {
    fn read<T, E: From<StoreError>>(
        &self,
        f: impl FnOnce(&dyn Txn) -> Result<T, E>,
    ) -> Result<T, E> {
        let mut connection = lock(&self.reader);
        let tx = connection
            .transaction_with_behavior(TransactionBehavior::Deferred)
            .sql()?;
        let value = f(&SqliteTxn::new(&tx));
        tx.rollback().sql()?;
        value
    }

    fn write<T, E: From<StoreError>>(
        &self,
        f: impl FnOnce(&mut dyn Txn) -> Result<T, E>,
    ) -> Result<T, E> {
        let mut connection = lock(&self.writer);
        let tx = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .sql()?;
        let value = f(&mut SqliteTxn::new(&tx));
        match value {
            Ok(value) => {
                tx.commit().sql()?;
                Ok(value)
            }
            Err(error) => {
                tx.rollback().sql()?;
                Err(error)
            }
        }
    }

    fn bindings(&self) -> &dyn BindingsStore {
        &self.bindings
    }

    fn schema_version(&self) -> u32 {
        self.schema_version
    }
}

/// The node-local `binding` table, on its own connection. Each call is one autocommit statement.
/// A binding written while a replicated write transaction is open waits for it (up to the busy
/// timeout), so callers do not write bindings from inside a write closure.
struct SqliteBindings {
    connection: Mutex<Connection>,
}

impl BindingsStore for SqliteBindings {
    fn binding(&self, key: &BindingKey) -> Result<Option<String>, StoreError> {
        lock(&self.connection)
            .query_row(
                "SELECT value FROM binding WHERE key = ?1",
                params![key.encode()?],
                |row| row.get(0),
            )
            .optional()
            .sql()
    }

    fn set_binding(&self, key: &BindingKey, value: &str) -> Result<(), StoreError> {
        lock(&self.connection)
            .execute(
                "INSERT INTO binding (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                params![key.encode()?, value],
            )
            .sql()?;
        Ok(())
    }

    fn remove_binding(&self, key: &BindingKey) -> Result<bool, StoreError> {
        let removed = lock(&self.connection)
            .execute("DELETE FROM binding WHERE key = ?1", params![key.encode()?])
            .sql()?;
        Ok(removed > 0)
    }

    fn bindings(&self) -> Result<Vec<(BindingKey, String)>, StoreError> {
        let connection = lock(&self.connection);
        let mut statement = connection
            .prepare("SELECT key, value FROM binding ORDER BY key")
            .sql()?;
        let rows = statement
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .sql()?;
        let mut out = Vec::new();
        for row in rows {
            let (key, value) = row.sql()?;
            out.push((BindingKey::decode(&key)?, value));
        }
        Ok(out)
    }
}
