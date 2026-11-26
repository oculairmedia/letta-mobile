//! samod's storage on the node store: chunks in core-store's KV area under namespace `samod`, keyed
//! by core-store's key-path encoding, so a component prefix of a samod key is a byte prefix of the
//! stored key (what `load_range` needs) and a catalog write can share a transaction with a C1 op.

use std::collections::HashMap;
use std::sync::Arc;

use core_store::kv::{decode_key_path, encode_key_path};
use core_store::{Namespace, Store, StoreError};
use samod::storage::{Storage, StorageKey};

/// samod's [`Storage`] over a node [`Store`]. Store calls are synchronous, so each runs on tokio's
/// blocking pool.
pub struct KvSamodStorage<S> {
    store: Arc<S>,
}

impl<S> Clone for KvSamodStorage<S> {
    fn clone(&self) -> Self {
        Self {
            store: Arc::clone(&self.store),
        }
    }
}

impl<S: Store + 'static> KvSamodStorage<S> {
    /// Storage over `store`.
    #[must_use]
    pub const fn new(store: Arc<S>) -> Self {
        Self { store }
    }

    async fn blocking<T: Send + 'static>(
        &self,
        f: impl FnOnce(&S) -> Result<T, StoreError> + Send + 'static,
    ) -> Option<T> {
        let store = Arc::clone(&self.store);
        tokio::task::spawn_blocking(move || f(&store))
            .await
            .ok()
            .and_then(Result::ok)
    }
}

/// The stored key of a samod key.
#[must_use]
pub fn stored_key(key: &StorageKey) -> Vec<u8> {
    let parts: Vec<&str> = key.into_iter().map(String::as_str).collect();
    encode_key_path(&parts)
}

impl<S: Store + 'static> Storage for KvSamodStorage<S> {
    async fn load(&self, key: StorageKey) -> Option<Vec<u8>> {
        let stored = stored_key(&key);
        self.blocking(move |store| store.read(|txn| txn.kv_get(Namespace::SAMOD, &stored)))
            .await
            .flatten()
    }

    async fn load_range(&self, prefix: StorageKey) -> HashMap<StorageKey, Vec<u8>> {
        let stored = stored_key(&prefix);
        let rows = self
            .blocking(move |store| store.read(|txn| txn.kv_scan_prefix(Namespace::SAMOD, &stored)))
            .await
            .unwrap_or_default();
        rows.into_iter()
            .filter_map(|(key, value)| {
                let parts = decode_key_path(&key).ok()?;
                Some((StorageKey::from_parts(parts).ok()?, value))
            })
            .collect()
    }

    async fn put(&self, key: StorageKey, data: Vec<u8>) {
        let stored = stored_key(&key);
        let _ = self
            .blocking(move |store| store.write(|txn| txn.kv_put(Namespace::SAMOD, &stored, &data)))
            .await;
    }

    async fn delete(&self, key: StorageKey) {
        let stored = stored_key(&key);
        let _ = self
            .blocking(move |store| store.write(|txn| txn.kv_delete(Namespace::SAMOD, &stored)))
            .await;
    }
}
