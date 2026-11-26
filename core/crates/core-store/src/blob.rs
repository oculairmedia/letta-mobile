//! Content-addressed bytes: log payloads and `baken/blob/1` blobs.

use crate::error::StoreError;
use crate::ids::BlobHash;

/// The blob area of a transaction. Blobs are keyed by `blake3(bytes)`, which the caller computes;
/// the store does not re-hash.
pub trait BlobStore {
    /// The bytes stored under `hash`.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn blob(&self, hash: &BlobHash) -> Result<Option<Vec<u8>>, StoreError>;

    /// The length of the blob, without reading it.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn blob_len(&self, hash: &BlobHash) -> Result<Option<u64>, StoreError>;

    /// Stores `bytes` under `hash`. Returns `true` when it was new; storing the same blob again is
    /// a no-op.
    ///
    /// # Errors
    ///
    /// [`StoreError::Conflict`] when a blob of a different length is stored under `hash`.
    fn put_blob(&mut self, hash: &BlobHash, bytes: &[u8]) -> Result<bool, StoreError>;

    /// Deletes the blob. Returns `true` when it existed. Log payloads are released through
    /// [`crate::LogStore::prune_payload`], which checks references first.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn delete_blob(&mut self, hash: &BlobHash) -> Result<bool, StoreError>;
}
