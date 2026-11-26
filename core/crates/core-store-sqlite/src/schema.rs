//! The versioned schema. `PRAGMA user_version` holds the version; migration `i` (0-based) moves a
//! database from version `i` to `i + 1` in one transaction. Migrations are append-only: a shipped
//! migration is never edited, and a database newer than this build is never opened.

/// `PRAGMA application_id` of a Baken node store: `"BKN1"`.
pub(crate) const APPLICATION_ID: i32 = 0x424B_4E31;

/// Every migration, in order. The schema version is `MIGRATIONS.len()`.
pub(crate) const MIGRATIONS: &[&str] = &[V1];

const V1: &str = r"
CREATE TABLE log_entry (
    entry_hash     BLOB    NOT NULL PRIMARY KEY,
    log_id         BLOB    NOT NULL,
    epoch_n        INTEGER NOT NULL,
    epoch_claimant BLOB    NOT NULL,
    seq            INTEGER NOT NULL,
    prev           BLOB    NOT NULL,
    kind           INTEGER NOT NULL,
    flags          INTEGER NOT NULL,
    header         BLOB    NOT NULL,
    sig            BLOB    NOT NULL,
    payload_hash   BLOB    NOT NULL,
    payload_len    INTEGER NOT NULL,
    payload_state  INTEGER NOT NULL CHECK (payload_state IN (0, 1, 2))
) WITHOUT ROWID;
CREATE INDEX log_entry_by_seq ON log_entry (log_id, seq, entry_hash);
CREATE INDEX log_entry_by_segment ON log_entry (log_id, epoch_n, epoch_claimant, seq, entry_hash);
CREATE INDEX log_entry_by_stored_payload ON log_entry (payload_hash) WHERE payload_state = 1;

CREATE TABLE blob (
    hash  BLOB    NOT NULL PRIMARY KEY,
    len   INTEGER NOT NULL,
    bytes BLOB    NOT NULL
);

CREATE TABLE segment (
    log_id         BLOB    NOT NULL,
    epoch_n        INTEGER NOT NULL,
    epoch_claimant BLOB    NOT NULL,
    start_seq      INTEGER NOT NULL,
    parent_hash    BLOB,
    head_seq       INTEGER NOT NULL,
    head_hash      BLOB    NOT NULL,
    complete_seq   INTEGER,
    state          INTEGER NOT NULL CHECK (state BETWEEN 0 AND 5),
    PRIMARY KEY (log_id, epoch_n, epoch_claimant)
) WITHOUT ROWID;

CREATE TABLE placement (
    log_id          BLOB    NOT NULL PRIMARY KEY,
    agent_id        TEXT    NOT NULL,
    epoch_n         INTEGER NOT NULL,
    epoch_claimant  BLOB    NOT NULL,
    state           INTEGER NOT NULL CHECK (state BETWEEN 0 AND 3),
    sealed_n        INTEGER,
    sealed_claimant BLOB,
    open_entry_hash BLOB    NOT NULL
) WITHOUT ROWID;

CREATE TABLE divergence (
    divergence_id       BLOB    NOT NULL PRIMARY KEY,
    log_id              BLOB    NOT NULL,
    losing_epoch_n      INTEGER NOT NULL,
    losing_claimant     BLOB    NOT NULL,
    fork_seq            INTEGER NOT NULL,
    tail_head_seq       INTEGER NOT NULL,
    recorded_entry_hash BLOB
) WITHOUT ROWID;
CREATE INDEX divergence_by_log ON divergence (log_id, divergence_id);

CREATE TABLE vote_promise (
    log_id         BLOB    NOT NULL PRIMARY KEY,
    epoch_n        INTEGER NOT NULL,
    epoch_claimant BLOB    NOT NULL,
    parent_seq     INTEGER NOT NULL,
    parent_hash    BLOB    NOT NULL
) WITHOUT ROWID;

CREATE TABLE membership_op (
    op_id       BLOB    NOT NULL PRIMARY KEY,
    envelope    BLOB    NOT NULL,
    issuer      BLOB    NOT NULL,
    kind        INTEGER NOT NULL,
    received_at INTEGER NOT NULL
) WITHOUT ROWID;
CREATE TABLE membership_parent (
    op_id  BLOB NOT NULL,
    parent BLOB NOT NULL,
    PRIMARY KEY (op_id, parent)
) WITHOUT ROWID;
CREATE INDEX membership_parent_by_parent ON membership_parent (parent);

CREATE TABLE feed_row (
    conversation_id TEXT    NOT NULL,
    feed_seq        INTEGER NOT NULL,
    entry_hash      BLOB    NOT NULL,
    PRIMARY KEY (conversation_id, feed_seq)
) WITHOUT ROWID;

CREATE TABLE catalog_doc (
    doc_id        BLOB    NOT NULL PRIMARY KEY,
    class         TEXT    NOT NULL,
    agent_id      TEXT,
    state         TEXT    NOT NULL CHECK (state IN ('live', 'superseded', 'retired')),
    registered_at INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE kv (
    namespace TEXT NOT NULL,
    key       BLOB NOT NULL,
    value     BLOB NOT NULL,
    PRIMARY KEY (namespace, key)
) WITHOUT ROWID;

-- Node-local, never replicated. Only the bindings connection touches it.
CREATE TABLE binding (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;
";
