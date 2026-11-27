//! The quarantine (catalog-v1 §3.5): changes the gate kept out, stored for diagnosis and never
//! applied. A ring in core-store's KV area under namespace `catalog.quarantine`, capped at 16 MiB:
//! the oldest rows go first.

use core_store::kv::encode_key_path;
use core_store::{KvStore, Namespace, StoreError};
use serde::{Deserialize, Serialize};

use crate::gate::Quarantined;

/// The quarantine's KV namespace.
pub const QUARANTINE: Namespace = Namespace::new("catalog.quarantine");
/// The ring's cap in bytes.
pub const MAX_BYTES: usize = 16 * 1024 * 1024;

/// One stored row.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuarantineRow {
    /// The row id (ascending).
    pub id: u64,
    /// When it was received.
    pub received_at: u64,
    /// The principal that sent it, hex.
    pub principal: String,
    /// The document, hex.
    pub doc_id: String,
    /// `unauthorized_change`, `malformed_change` or `oversized_change`.
    pub reason: String,
    /// The hashes of the changes that parsed.
    pub change_hashes: Vec<String>,
    /// The change bytes, hex.
    pub bytes: String,
}

fn key(id: u64) -> Vec<u8> {
    encode_key_path(&[format!("{id:020}")])
}

/// Every row, oldest first.
///
/// # Errors
///
/// A store error or a corrupt row.
pub fn rows(kv: &dyn KvStore) -> Result<Vec<QuarantineRow>, StoreError> {
    kv.kv_scan_prefix(QUARANTINE, &[])?
        .into_iter()
        .map(|(_, value)| {
            serde_json::from_slice(&value)
                .map_err(|error| StoreError::Corrupt(format!("quarantine row: {error}")))
        })
        .collect()
}

/// Stores one quarantined batch and trims the ring to [`MAX_BYTES`]. Returns the row id.
///
/// # Errors
///
/// A store error.
///
/// # Panics
///
/// Never: rows always serialize.
pub fn record(
    kv: &mut dyn KvStore,
    principal: &core_proto::NodeId,
    quarantined: &Quarantined,
    received_at: u64,
) -> Result<u64, StoreError> {
    let existing = kv.kv_scan_prefix(QUARANTINE, &[])?;
    let next = existing
        .last()
        .and_then(|(_, value)| serde_json::from_slice::<QuarantineRow>(value).ok())
        .map_or(1, |row| row.id + 1);
    let row = QuarantineRow {
        id: next,
        received_at,
        principal: principal.to_hex(),
        doc_id: hex::encode(quarantined.doc_id.as_bytes()),
        reason: quarantined.reason.as_str().to_owned(),
        change_hashes: quarantined.change_hashes.clone(),
        bytes: hex::encode(&quarantined.bytes),
    };
    let value = serde_json::to_vec(&row).expect("rows serialize");
    let mut total: usize = existing.iter().map(|(_, v)| v.len()).sum::<usize>() + value.len();
    kv.kv_put(QUARANTINE, &key(next), &value)?;
    for (stored, old) in existing {
        if total <= MAX_BYTES {
            break;
        }
        kv.kv_delete(QUARANTINE, &stored)?;
        total -= old.len();
    }
    Ok(next)
}
