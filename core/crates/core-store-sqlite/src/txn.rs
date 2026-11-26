//! One transaction over the node database: the [`core_store`] area traits as SQL.

use core_proto::{AgentId, ConversationId, Epoch, NodeId};
use core_store::kv::KvEntry;
use core_store::log::check_promise;
use core_store::membership::check_parents;
use core_store::{
    BlobHash, BlobStore, CatalogDoc, DivergenceId, DivergenceRecord, DocId, DocRegistry, DocState,
    EntryHash, FeedJournal, FeedRow, KvStore, LogId, LogStore, MembershipStore, Namespace, OpId,
    PayloadState, Placement, PlacementState, Segment, SegmentState, StoreError, StoredEntry,
    StoredOp, VotePromise,
};
use rusqlite::types::Value;
use rusqlite::{Connection, OptionalExtension, Row, params};

/// Maps rusqlite errors into [`StoreError::Backend`].
pub(crate) trait SqlResult<T> {
    /// The value, or the error as a [`StoreError`].
    fn sql(self) -> Result<T, StoreError>;
}

impl<T> SqlResult<T> for rusqlite::Result<T> {
    fn sql(self) -> Result<T, StoreError> {
        self.map_err(|error| StoreError::Backend(error.to_string()))
    }
}

/// The area traits over a connection that has a transaction open.
pub(crate) struct SqliteTxn<'c> {
    connection: &'c Connection,
}

impl<'c> SqliteTxn<'c> {
    pub(crate) const fn new(connection: &'c Connection) -> Self {
        Self { connection }
    }

    /// Runs a query and decodes every row with `decode`.
    fn rows<T>(
        &self,
        sql: &str,
        params: impl rusqlite::Params,
        decode: impl Fn(&Row<'_>) -> Result<T, StoreError>,
    ) -> Result<Vec<T>, StoreError> {
        let mut statement = self.connection.prepare_cached(sql).sql()?;
        let mut rows = statement.query(params).sql()?;
        let mut out = Vec::new();
        while let Some(row) = rows.next().sql()? {
            out.push(decode(row)?);
        }
        Ok(out)
    }

    /// Runs a query expected to return at most one row.
    fn row<T>(
        &self,
        sql: &str,
        params: impl rusqlite::Params,
        decode: impl Fn(&Row<'_>) -> Result<T, StoreError>,
    ) -> Result<Option<T>, StoreError> {
        Ok(self.rows(sql, params, decode)?.into_iter().next())
    }

    fn execute(&self, sql: &str, params: impl rusqlite::Params) -> Result<usize, StoreError> {
        self.connection
            .prepare_cached(sql)
            .sql()?
            .execute(params)
            .sql()
    }
}

// ---------------------------------------------------------------------------------------------
// Column codecs

fn int(value: u64) -> Result<i64, StoreError> {
    i64::try_from(value).map_err(|_| StoreError::Invalid(format!("{value} exceeds i64::MAX")))
}

fn uint(row: &Row<'_>, index: usize) -> Result<u64, StoreError> {
    let value: i64 = row.get(index).sql()?;
    u64::try_from(value).map_err(|_| StoreError::Corrupt(format!("negative integer {value}")))
}

fn opt_uint(row: &Row<'_>, index: usize) -> Result<Option<u64>, StoreError> {
    let value: Option<i64> = row.get(index).sql()?;
    value
        .map(|value| {
            u64::try_from(value)
                .map_err(|_| StoreError::Corrupt(format!("negative integer {value}")))
        })
        .transpose()
}

fn bytes<const N: usize>(row: &Row<'_>, index: usize) -> Result<[u8; N], StoreError> {
    let value: Vec<u8> = row.get(index).sql()?;
    <[u8; N]>::try_from(value.as_slice())
        .map_err(|_| StoreError::Corrupt(format!("expected {N} bytes, found {}", value.len())))
}

fn opt_bytes<const N: usize>(row: &Row<'_>, index: usize) -> Result<Option<[u8; N]>, StoreError> {
    let value: Option<Vec<u8>> = row.get(index).sql()?;
    value
        .map(|value| {
            <[u8; N]>::try_from(value.as_slice()).map_err(|_| {
                StoreError::Corrupt(format!("expected {N} bytes, found {}", value.len()))
            })
        })
        .transpose()
}

fn epoch_at(row: &Row<'_>, n: usize, claimant: usize) -> Result<Epoch, StoreError> {
    Ok(Epoch {
        n: uint(row, n)?,
        claimant: NodeId::from_bytes(bytes(row, claimant)?),
    })
}

fn small<T: TryFrom<i64>>(row: &Row<'_>, index: usize) -> Result<T, StoreError> {
    let value: i64 = row.get(index).sql()?;
    T::try_from(value).map_err(|_| StoreError::Corrupt(format!("integer {value} out of range")))
}

const fn payload_code(state: PayloadState) -> i64 {
    match state {
        PayloadState::Missing => 0,
        PayloadState::Stored => 1,
        PayloadState::Pruned => 2,
    }
}

fn payload_state(code: i64) -> Result<PayloadState, StoreError> {
    match code {
        0 => Ok(PayloadState::Missing),
        1 => Ok(PayloadState::Stored),
        2 => Ok(PayloadState::Pruned),
        other => Err(StoreError::Corrupt(format!("payload state {other}"))),
    }
}

const fn segment_code(state: SegmentState) -> i64 {
    match state {
        SegmentState::Open => 0,
        SegmentState::Sealed => 1,
        SegmentState::CanonicalAncestor => 2,
        SegmentState::Divergent => 3,
        SegmentState::Invalid => 4,
        SegmentState::Quarantined => 5,
    }
}

fn segment_state(code: i64) -> Result<SegmentState, StoreError> {
    Ok(match code {
        0 => SegmentState::Open,
        1 => SegmentState::Sealed,
        2 => SegmentState::CanonicalAncestor,
        3 => SegmentState::Divergent,
        4 => SegmentState::Invalid,
        5 => SegmentState::Quarantined,
        other => return Err(StoreError::Corrupt(format!("segment state {other}"))),
    })
}

const ENTRY_COLUMNS: &str = "entry_hash, log_id, epoch_n, epoch_claimant, seq, prev, kind, flags, \
     header, sig, payload_hash, payload_len, payload_state";

fn decode_entry(row: &Row<'_>) -> Result<StoredEntry, StoreError> {
    Ok(StoredEntry {
        entry_hash: EntryHash::from_bytes(bytes(row, 0)?),
        log_id: LogId::from_bytes(bytes(row, 1)?),
        epoch: epoch_at(row, 2, 3)?,
        seq: uint(row, 4)?,
        prev: EntryHash::from_bytes(bytes(row, 5)?),
        kind: small(row, 6)?,
        flags: small(row, 7)?,
        header: row.get(8).sql()?,
        signature: bytes(row, 9)?,
        payload_hash: BlobHash::from_bytes(bytes(row, 10)?),
        payload_len: uint(row, 11)?,
        payload_state: payload_state(row.get(12).sql()?)?,
    })
}

const SEGMENT_COLUMNS: &str = "log_id, epoch_n, epoch_claimant, start_seq, parent_hash, head_seq, \
     head_hash, complete_seq, state";

fn decode_segment(row: &Row<'_>) -> Result<Segment, StoreError> {
    Ok(Segment {
        log_id: LogId::from_bytes(bytes(row, 0)?),
        epoch: epoch_at(row, 1, 2)?,
        start_seq: uint(row, 3)?,
        parent: opt_bytes(row, 4)?.map(EntryHash::from_bytes),
        head_seq: uint(row, 5)?,
        head_hash: EntryHash::from_bytes(bytes(row, 6)?),
        complete_seq: opt_uint(row, 7)?,
        state: segment_state(row.get(8).sql()?)?,
    })
}

const PLACEMENT_COLUMNS: &str =
    "log_id, agent_id, epoch_n, epoch_claimant, state, sealed_n, sealed_claimant, open_entry_hash";

fn decode_placement(row: &Row<'_>) -> Result<Placement, StoreError> {
    let code: i64 = row.get(4).sql()?;
    let state =
        match code {
            0 => PlacementState::Active,
            1 => PlacementState::SealedTo(Epoch {
                n: opt_uint(row, 5)?
                    .ok_or_else(|| StoreError::Corrupt("sealed placement without n".into()))?,
                claimant: NodeId::from_bytes(opt_bytes(row, 6)?.ok_or_else(|| {
                    StoreError::Corrupt("sealed placement without claimant".into())
                })?),
            }),
            2 => PlacementState::Placeless,
            3 => PlacementState::SupersededUnknown,
            other => return Err(StoreError::Corrupt(format!("placement state {other}"))),
        };
    Ok(Placement {
        log_id: LogId::from_bytes(bytes(row, 0)?),
        agent_id: AgentId::new(row.get::<_, String>(1).sql()?),
        epoch: epoch_at(row, 2, 3)?,
        state,
        open_entry_hash: EntryHash::from_bytes(bytes(row, 7)?),
    })
}

const DIVERGENCE_COLUMNS: &str = "divergence_id, log_id, losing_epoch_n, losing_claimant, fork_seq, \
     tail_head_seq, recorded_entry_hash";

fn decode_divergence(row: &Row<'_>) -> Result<DivergenceRecord, StoreError> {
    Ok(DivergenceRecord {
        id: DivergenceId::from_bytes(bytes(row, 0)?),
        log_id: LogId::from_bytes(bytes(row, 1)?),
        losing_epoch: epoch_at(row, 2, 3)?,
        fork_seq: uint(row, 4)?,
        tail_head_seq: uint(row, 5)?,
        recorded_entry: opt_bytes(row, 6)?.map(EntryHash::from_bytes),
    })
}

fn decode_doc(row: &Row<'_>) -> Result<CatalogDoc, StoreError> {
    let state: String = row.get(3).sql()?;
    Ok(CatalogDoc {
        doc_id: DocId::from_bytes(bytes(row, 0)?),
        class: row.get(1).sql()?,
        agent_id: row.get::<_, Option<String>>(2).sql()?.map(AgentId::new),
        state: DocState::parse(&state)
            .ok_or_else(|| StoreError::Corrupt(format!("doc state {state}")))?,
        registered_at_ms: uint(row, 4)?,
    })
}

/// The smallest byte string greater than every string that starts with `prefix`, or `None` when
/// there is none (the prefix is empty or all `0xff`).
fn prefix_successor(prefix: &[u8]) -> Option<Vec<u8>> {
    let mut upper = prefix.to_vec();
    while let Some(last) = upper.pop() {
        if last < u8::MAX {
            upper.push(last + 1);
            return Some(upper);
        }
    }
    None
}

// ---------------------------------------------------------------------------------------------
// Areas

impl BlobStore for SqliteTxn<'_> {
    fn blob(&self, hash: &BlobHash) -> Result<Option<Vec<u8>>, StoreError> {
        self.row(
            "SELECT bytes FROM blob WHERE hash = ?1",
            params![hash.as_bytes()],
            |row| row.get(0).sql(),
        )
    }

    fn blob_len(&self, hash: &BlobHash) -> Result<Option<u64>, StoreError> {
        self.row(
            "SELECT len FROM blob WHERE hash = ?1",
            params![hash.as_bytes()],
            |row| uint(row, 0),
        )
    }

    fn put_blob(&mut self, hash: &BlobHash, data: &[u8]) -> Result<bool, StoreError> {
        let len = data.len() as u64;
        if let Some(existing) = self.blob_len(hash)? {
            return if existing == len {
                Ok(false)
            } else {
                Err(StoreError::Conflict(format!(
                    "blob {hash:?} is stored with another length"
                )))
            };
        }
        self.execute(
            "INSERT INTO blob (hash, len, bytes) VALUES (?1, ?2, ?3)",
            params![hash.as_bytes(), int(len)?, data],
        )?;
        Ok(true)
    }

    fn delete_blob(&mut self, hash: &BlobHash) -> Result<bool, StoreError> {
        Ok(self.execute("DELETE FROM blob WHERE hash = ?1", params![hash.as_bytes()])? > 0)
    }
}

impl LogStore for SqliteTxn<'_> {
    fn entry(&self, hash: &EntryHash) -> Result<Option<StoredEntry>, StoreError> {
        self.row(
            &format!("SELECT {ENTRY_COLUMNS} FROM log_entry WHERE entry_hash = ?1"),
            params![hash.as_bytes()],
            decode_entry,
        )
    }

    fn entries_at(&self, log_id: &LogId, seq: u64) -> Result<Vec<StoredEntry>, StoreError> {
        self.rows(
            &format!(
                "SELECT {ENTRY_COLUMNS} FROM log_entry WHERE log_id = ?1 AND seq = ?2 \
                 ORDER BY entry_hash"
            ),
            params![log_id.as_bytes(), int(seq)?],
            decode_entry,
        )
    }

    fn segment_entries(
        &self,
        log_id: &LogId,
        epoch: &Epoch,
        from_seq: u64,
        limit: usize,
    ) -> Result<Vec<StoredEntry>, StoreError> {
        let limit = i64::try_from(limit).unwrap_or(i64::MAX);
        self.rows(
            &format!(
                "SELECT {ENTRY_COLUMNS} FROM log_entry \
                 WHERE log_id = ?1 AND epoch_n = ?2 AND epoch_claimant = ?3 AND seq >= ?4 \
                 ORDER BY seq, entry_hash LIMIT ?5"
            ),
            params![
                log_id.as_bytes(),
                int(epoch.n)?,
                epoch.claimant.as_bytes(),
                int(from_seq.min(i64::MAX as u64))?,
                limit
            ],
            decode_entry,
        )
    }

    fn payload_refs(&self, hash: &BlobHash) -> Result<u64, StoreError> {
        self.row(
            "SELECT COUNT(*) FROM log_entry WHERE payload_hash = ?1 AND payload_state = 1",
            params![hash.as_bytes()],
            |row| uint(row, 0),
        )
        .map(Option::unwrap_or_default)
    }

    fn max_n_seen(&self, log_id: &LogId) -> Result<Option<u64>, StoreError> {
        self.row(
            "SELECT MAX(epoch_n) FROM log_entry WHERE log_id = ?1",
            params![log_id.as_bytes()],
            |row| opt_uint(row, 0),
        )
        .map(Option::flatten)
    }

    fn logs(&self) -> Result<Vec<LogId>, StoreError> {
        self.rows(
            "SELECT DISTINCT log_id FROM log_entry ORDER BY log_id",
            [],
            |row| Ok(LogId::from_bytes(bytes(row, 0)?)),
        )
    }

    fn segment(&self, log_id: &LogId, epoch: &Epoch) -> Result<Option<Segment>, StoreError> {
        self.row(
            &format!(
                "SELECT {SEGMENT_COLUMNS} FROM segment \
                 WHERE log_id = ?1 AND epoch_n = ?2 AND epoch_claimant = ?3"
            ),
            params![log_id.as_bytes(), int(epoch.n)?, epoch.claimant.as_bytes()],
            decode_segment,
        )
    }

    fn segments(&self, log_id: &LogId) -> Result<Vec<Segment>, StoreError> {
        self.rows(
            &format!(
                "SELECT {SEGMENT_COLUMNS} FROM segment WHERE log_id = ?1 \
                 ORDER BY epoch_n, epoch_claimant"
            ),
            params![log_id.as_bytes()],
            decode_segment,
        )
    }

    fn placement(&self, log_id: &LogId) -> Result<Option<Placement>, StoreError> {
        self.row(
            &format!("SELECT {PLACEMENT_COLUMNS} FROM placement WHERE log_id = ?1"),
            params![log_id.as_bytes()],
            decode_placement,
        )
    }

    fn placements(&self) -> Result<Vec<Placement>, StoreError> {
        self.rows(
            &format!("SELECT {PLACEMENT_COLUMNS} FROM placement ORDER BY log_id"),
            [],
            decode_placement,
        )
    }

    fn divergence(&self, id: &DivergenceId) -> Result<Option<DivergenceRecord>, StoreError> {
        self.row(
            &format!("SELECT {DIVERGENCE_COLUMNS} FROM divergence WHERE divergence_id = ?1"),
            params![id.as_bytes()],
            decode_divergence,
        )
    }

    fn divergences(&self, log_id: &LogId) -> Result<Vec<DivergenceRecord>, StoreError> {
        self.rows(
            &format!(
                "SELECT {DIVERGENCE_COLUMNS} FROM divergence WHERE log_id = ?1 \
                 ORDER BY divergence_id"
            ),
            params![log_id.as_bytes()],
            decode_divergence,
        )
    }

    fn vote_promise(&self, log_id: &LogId) -> Result<Option<VotePromise>, StoreError> {
        self.row(
            "SELECT log_id, epoch_n, epoch_claimant, parent_seq, parent_hash \
             FROM vote_promise WHERE log_id = ?1",
            params![log_id.as_bytes()],
            |row| {
                Ok(VotePromise {
                    log_id: LogId::from_bytes(bytes(row, 0)?),
                    epoch: epoch_at(row, 1, 2)?,
                    parent_seq: uint(row, 3)?,
                    parent_hash: EntryHash::from_bytes(bytes(row, 4)?),
                })
            },
        )
    }

    fn put_entry(&mut self, entry: &StoredEntry) -> Result<bool, StoreError> {
        entry.check_shape()?;
        let existing = self.row(
            "SELECT header, sig FROM log_entry WHERE entry_hash = ?1",
            params![entry.entry_hash.as_bytes()],
            |row| {
                Ok((
                    row.get::<_, Vec<u8>>(0).sql()?,
                    row.get::<_, Vec<u8>>(1).sql()?,
                ))
            },
        )?;
        if let Some((header, signature)) = existing {
            return if header == entry.header && signature == entry.signature {
                Ok(false)
            } else {
                Err(StoreError::Conflict(format!(
                    "entry {:?} is stored with another header or signature",
                    entry.entry_hash
                )))
            };
        }
        self.execute(
            &format!(
                "INSERT INTO log_entry ({ENTRY_COLUMNS}) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)"
            ),
            params![
                entry.entry_hash.as_bytes(),
                entry.log_id.as_bytes(),
                int(entry.epoch.n)?,
                entry.epoch.claimant.as_bytes(),
                int(entry.seq)?,
                entry.prev.as_bytes(),
                i64::from(entry.kind),
                i64::from(entry.flags),
                entry.header,
                &entry.signature[..],
                entry.payload_hash.as_bytes(),
                int(entry.payload_len)?,
                payload_code(entry.payload_state),
            ],
        )?;
        Ok(true)
    }

    fn set_payload_state(
        &mut self,
        hash: &EntryHash,
        state: PayloadState,
    ) -> Result<(), StoreError> {
        let flags: Option<i64> = self.row(
            "SELECT flags FROM log_entry WHERE entry_hash = ?1",
            params![hash.as_bytes()],
            |row| row.get(0).sql(),
        )?;
        let Some(flags) = flags else {
            return Err(StoreError::NotFound(format!("entry {hash:?}")));
        };
        if state == PayloadState::Pruned
            && flags & i64::from(core_store::log::FLAG_COMPACTABLE) == 0
        {
            return Err(StoreError::Invalid(
                "only a compactable payload may be pruned".into(),
            ));
        }
        self.execute(
            "UPDATE log_entry SET payload_state = ?2 WHERE entry_hash = ?1",
            params![hash.as_bytes(), payload_code(state)],
        )?;
        Ok(())
    }

    fn put_segment(&mut self, segment: &Segment) -> Result<(), StoreError> {
        self.execute(
            &format!(
                "INSERT OR REPLACE INTO segment ({SEGMENT_COLUMNS}) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)"
            ),
            params![
                segment.log_id.as_bytes(),
                int(segment.epoch.n)?,
                segment.epoch.claimant.as_bytes(),
                int(segment.start_seq)?,
                segment.parent.as_ref().map(|hash| hash.as_bytes().to_vec()),
                int(segment.head_seq)?,
                segment.head_hash.as_bytes(),
                segment.complete_seq.map(int).transpose()?,
                segment_code(segment.state),
            ],
        )?;
        Ok(())
    }

    fn put_divergence(&mut self, record: &DivergenceRecord) -> Result<(), StoreError> {
        self.execute(
            &format!(
                "INSERT OR REPLACE INTO divergence ({DIVERGENCE_COLUMNS}) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)"
            ),
            params![
                record.id.as_bytes(),
                record.log_id.as_bytes(),
                int(record.losing_epoch.n)?,
                record.losing_epoch.claimant.as_bytes(),
                int(record.fork_seq)?,
                int(record.tail_head_seq)?,
                record
                    .recorded_entry
                    .as_ref()
                    .map(|hash| hash.as_bytes().to_vec()),
            ],
        )?;
        Ok(())
    }

    fn set_vote_promise(&mut self, promise: &VotePromise) -> Result<(), StoreError> {
        if let Some(existing) = self.vote_promise(&promise.log_id)? {
            check_promise(&existing, promise)?;
        }
        self.execute(
            "INSERT OR REPLACE INTO vote_promise \
             (log_id, epoch_n, epoch_claimant, parent_seq, parent_hash) \
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                promise.log_id.as_bytes(),
                int(promise.epoch.n)?,
                promise.epoch.claimant.as_bytes(),
                int(promise.parent_seq)?,
                promise.parent_hash.as_bytes(),
            ],
        )?;
        Ok(())
    }

    fn write_placement_row(&mut self, placement: &Placement) -> Result<(), StoreError> {
        let (code, sealed_n, sealed_claimant) = match placement.state {
            PlacementState::Active => (0, None, None),
            PlacementState::SealedTo(next) => (
                1,
                Some(int(next.n)?),
                Some(next.claimant.as_bytes().to_vec()),
            ),
            PlacementState::Placeless => (2, None, None),
            PlacementState::SupersededUnknown => (3, None, None),
        };
        self.execute(
            &format!(
                "INSERT OR REPLACE INTO placement ({PLACEMENT_COLUMNS}) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"
            ),
            params![
                placement.log_id.as_bytes(),
                placement.agent_id.as_str(),
                int(placement.epoch.n)?,
                placement.epoch.claimant.as_bytes(),
                code,
                sealed_n,
                sealed_claimant,
                placement.open_entry_hash.as_bytes(),
            ],
        )?;
        Ok(())
    }
}

impl KvStore for SqliteTxn<'_> {
    fn kv_get(&self, namespace: Namespace, key: &[u8]) -> Result<Option<Vec<u8>>, StoreError> {
        self.row(
            "SELECT value FROM kv WHERE namespace = ?1 AND key = ?2",
            params![namespace.as_str(), key],
            |row| row.get(0).sql(),
        )
    }

    fn kv_scan_prefix(
        &self,
        namespace: Namespace,
        prefix: &[u8],
    ) -> Result<Vec<KvEntry>, StoreError> {
        let upper = prefix_successor(prefix).map_or(Value::Null, Value::Blob);
        self.rows(
            "SELECT key, value FROM kv \
             WHERE namespace = ?1 AND key >= ?2 AND (?3 IS NULL OR key < ?3) ORDER BY key",
            params![namespace.as_str(), prefix, upper],
            |row| Ok((row.get(0).sql()?, row.get(1).sql()?)),
        )
    }

    fn kv_put(&mut self, namespace: Namespace, key: &[u8], value: &[u8]) -> Result<(), StoreError> {
        self.execute(
            "INSERT INTO kv (namespace, key, value) VALUES (?1, ?2, ?3) \
             ON CONFLICT (namespace, key) DO UPDATE SET value = excluded.value",
            params![namespace.as_str(), key, value],
        )?;
        Ok(())
    }

    fn kv_delete(&mut self, namespace: Namespace, key: &[u8]) -> Result<bool, StoreError> {
        Ok(self.execute(
            "DELETE FROM kv WHERE namespace = ?1 AND key = ?2",
            params![namespace.as_str(), key],
        )? > 0)
    }
}

impl SqliteTxn<'_> {
    fn parents_of(&self, op_id: &OpId) -> Result<Vec<OpId>, StoreError> {
        self.rows(
            "SELECT parent FROM membership_parent WHERE op_id = ?1 ORDER BY parent",
            params![op_id.as_bytes()],
            |row| Ok(OpId::from_bytes(bytes(row, 0)?)),
        )
    }

    fn decode_op(&self, row: &Row<'_>) -> Result<StoredOp, StoreError> {
        let op_id = OpId::from_bytes(bytes(row, 0)?);
        Ok(StoredOp {
            op_id,
            envelope: row.get(1).sql()?,
            issuer: NodeId::from_bytes(bytes(row, 2)?),
            kind: small(row, 3)?,
            parents: self.parents_of(&op_id)?,
            received_at_ms: uint(row, 4)?,
        })
    }
}

impl MembershipStore for SqliteTxn<'_> {
    fn op(&self, op_id: &OpId) -> Result<Option<StoredOp>, StoreError> {
        self.row(
            "SELECT op_id, envelope, issuer, kind, received_at FROM membership_op WHERE op_id = ?1",
            params![op_id.as_bytes()],
            |row| self.decode_op(row),
        )
    }

    fn ops(&self) -> Result<Vec<StoredOp>, StoreError> {
        self.rows(
            "SELECT op_id, envelope, issuer, kind, received_at FROM membership_op ORDER BY op_id",
            [],
            |row| self.decode_op(row),
        )
    }

    fn op_heads(&self) -> Result<Vec<OpId>, StoreError> {
        self.rows(
            "SELECT op_id FROM membership_op o \
             WHERE NOT EXISTS (SELECT 1 FROM membership_parent p WHERE p.parent = o.op_id) \
             ORDER BY op_id",
            [],
            |row| Ok(OpId::from_bytes(bytes(row, 0)?)),
        )
    }

    fn put_op(&mut self, op: &StoredOp) -> Result<bool, StoreError> {
        check_parents(op)?;
        let existing: Option<Vec<u8>> = self.row(
            "SELECT envelope FROM membership_op WHERE op_id = ?1",
            params![op.op_id.as_bytes()],
            |row| row.get(0).sql(),
        )?;
        if let Some(envelope) = existing {
            return if envelope == op.envelope {
                Ok(false)
            } else {
                Err(StoreError::Conflict(format!(
                    "op {:?} is stored with another envelope",
                    op.op_id
                )))
            };
        }
        self.execute(
            "INSERT INTO membership_op (op_id, envelope, issuer, kind, received_at) \
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                op.op_id.as_bytes(),
                op.envelope,
                op.issuer.as_bytes(),
                i64::from(op.kind),
                int(op.received_at_ms)?,
            ],
        )?;
        for parent in &op.parents {
            self.execute(
                "INSERT INTO membership_parent (op_id, parent) VALUES (?1, ?2)",
                params![op.op_id.as_bytes(), parent.as_bytes()],
            )?;
        }
        Ok(true)
    }
}

impl DocRegistry for SqliteTxn<'_> {
    fn catalog_doc(&self, doc_id: &DocId) -> Result<Option<CatalogDoc>, StoreError> {
        self.row(
            "SELECT doc_id, class, agent_id, state, registered_at FROM catalog_doc WHERE doc_id = ?1",
            params![doc_id.as_bytes()],
            decode_doc,
        )
    }

    fn catalog_docs(&self) -> Result<Vec<CatalogDoc>, StoreError> {
        self.rows(
            "SELECT doc_id, class, agent_id, state, registered_at FROM catalog_doc ORDER BY doc_id",
            [],
            decode_doc,
        )
    }

    fn register_doc(&mut self, doc: &CatalogDoc) -> Result<(), StoreError> {
        self.execute(
            "INSERT OR REPLACE INTO catalog_doc (doc_id, class, agent_id, state, registered_at) \
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                doc.doc_id.as_bytes(),
                doc.class,
                doc.agent_id.as_ref().map(AgentId::as_str),
                doc.state.as_str(),
                int(doc.registered_at_ms)?,
            ],
        )?;
        Ok(())
    }

    fn set_doc_state(&mut self, doc_id: &DocId, state: DocState) -> Result<(), StoreError> {
        let changed = self.execute(
            "UPDATE catalog_doc SET state = ?2 WHERE doc_id = ?1",
            params![doc_id.as_bytes(), state.as_str()],
        )?;
        if changed == 0 {
            return Err(StoreError::NotFound(format!("catalog doc {doc_id:?}")));
        }
        Ok(())
    }

    fn remove_doc(&mut self, doc_id: &DocId) -> Result<bool, StoreError> {
        Ok(self.execute(
            "DELETE FROM catalog_doc WHERE doc_id = ?1",
            params![doc_id.as_bytes()],
        )? > 0)
    }
}

impl FeedJournal for SqliteTxn<'_> {
    fn feed_rows(
        &self,
        conversation_id: &ConversationId,
        after: Option<u64>,
        limit: usize,
    ) -> Result<Vec<FeedRow>, StoreError> {
        let after = after.map(|seq| int(seq.min(i64::MAX as u64))).transpose()?;
        let limit = i64::try_from(limit).unwrap_or(i64::MAX);
        self.rows(
            "SELECT conversation_id, feed_seq, entry_hash FROM feed_row \
             WHERE conversation_id = ?1 AND (?2 IS NULL OR feed_seq > ?2) \
             ORDER BY feed_seq LIMIT ?3",
            params![conversation_id.as_str(), after, limit],
            |row| {
                Ok(FeedRow {
                    conversation_id: ConversationId::new(row.get::<_, String>(0).sql()?),
                    feed_seq: uint(row, 1)?,
                    entry_hash: EntryHash::from_bytes(bytes(row, 2)?),
                })
            },
        )
    }

    fn feed_head(&self, conversation_id: &ConversationId) -> Result<Option<u64>, StoreError> {
        self.connection
            .query_row(
                "SELECT MAX(feed_seq) FROM feed_row WHERE conversation_id = ?1",
                params![conversation_id.as_str()],
                |row| row.get::<_, Option<i64>>(0),
            )
            .optional()
            .sql()?
            .flatten()
            .map(|value| {
                u64::try_from(value)
                    .map_err(|_| StoreError::Corrupt(format!("negative feed seq {value}")))
            })
            .transpose()
    }

    fn put_feed_row(&mut self, row: &FeedRow) -> Result<(), StoreError> {
        self.execute(
            "INSERT OR REPLACE INTO feed_row (conversation_id, feed_seq, entry_hash) \
             VALUES (?1, ?2, ?3)",
            params![
                row.conversation_id.as_str(),
                int(row.feed_seq)?,
                row.entry_hash.as_bytes()
            ],
        )?;
        Ok(())
    }

    fn delete_feed_row(
        &mut self,
        conversation_id: &ConversationId,
        feed_seq: u64,
    ) -> Result<bool, StoreError> {
        let Ok(feed_seq) = i64::try_from(feed_seq) else {
            return Ok(false);
        };
        Ok(self.execute(
            "DELETE FROM feed_row WHERE conversation_id = ?1 AND feed_seq = ?2",
            params![conversation_id.as_str(), feed_seq],
        )? > 0)
    }

    fn truncate_feed_after(
        &mut self,
        conversation_id: &ConversationId,
        feed_seq: u64,
    ) -> Result<u64, StoreError> {
        let Ok(feed_seq) = i64::try_from(feed_seq) else {
            return Ok(0);
        };
        let deleted = self.execute(
            "DELETE FROM feed_row WHERE conversation_id = ?1 AND feed_seq > ?2",
            params![conversation_id.as_str(), feed_seq],
        )?;
        Ok(deleted as u64)
    }
}

#[cfg(test)]
mod tests {
    use super::prefix_successor;

    #[test]
    fn prefix_successor_bounds_the_scan() {
        assert_eq!(prefix_successor(b""), None);
        assert_eq!(prefix_successor(&[0xff, 0xff]), None);
        assert_eq!(prefix_successor(b"ab"), Some(b"ac".to_vec()));
        assert_eq!(prefix_successor(&[0x61, 0xff]), Some(vec![0x62]));
    }
}
