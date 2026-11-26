//! A pure fold of canonical payloads into a file tree and memfs refs, with the checkpoint manifest
//! (C1 §6.2 step 1 and 4). It writes nothing to disk: `core-projection` materializes, and uses this
//! fold's rules. Tests use it to check that pruning never changes what materializes (L11).
//!
//! A pruned payload is skipped: the pruning rule (see `prune`) only drops a `file_put` that a later
//! `file_put` of the same path overwrites with no `file_append` to that path in between, and a
//! `feed_append`, which is not part of the file tree.

use std::collections::{BTreeMap, BTreeSet};

use thiserror::Error;

use crate::allowlist::is_replicated;
use crate::hashes::sha256;
use crate::payload::{Checkpoint, ManifestEntry, Payload, RefEntry};

/// Why a payload cannot be applied.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum FoldError {
    /// A `file_append` offset is not the current file length.
    #[error("append to {path} at offset {offset}, file is {len} bytes")]
    OffsetMismatch {
        /// The path.
        path: String,
        /// The entry's offset.
        offset: u64,
        /// The current length.
        len: u64,
    },
    /// A `git_ref` names an old oid that is not current.
    #[error("ref {0} does not hold the expected old oid")]
    RefMismatch(String),
    /// A `git_ref` points at an object not yet written.
    #[error("ref {0} points at a missing object")]
    MissingObject(String),
}

/// The folded state.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct StateFold {
    files: BTreeMap<String, Vec<u8>>,
    objects: BTreeSet<(String, Vec<u8>)>,
    refs: BTreeMap<(String, String), Vec<u8>>,
    symrefs: BTreeMap<(String, String), String>,
    alarms: Vec<String>,
}

impl StateFold {
    /// An empty store.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Applies one canonical payload. Paths off the allowlist are skipped and recorded as alarms.
    ///
    /// # Errors
    ///
    /// [`FoldError`] when the payload does not apply.
    pub fn apply(&mut self, payload: &Payload) -> Result<(), FoldError> {
        match payload {
            Payload::FilePut { path, content, .. } => {
                if self.allowed(path) {
                    self.files.insert(path.clone(), content.clone());
                }
            }
            Payload::FileAppend {
                path,
                offset,
                bytes,
            } => {
                if !self.allowed(path) {
                    return Ok(());
                }
                let content = self.files.entry(path.clone()).or_default();
                let len = content.len() as u64;
                if *offset != len {
                    return Err(FoldError::OffsetMismatch {
                        path: path.clone(),
                        offset: *offset,
                        len,
                    });
                }
                content.extend_from_slice(bytes);
            }
            Payload::FileDelete { path } => {
                self.files.remove(path);
            }
            Payload::GitObjects { repo, objects, .. } => {
                for object in objects {
                    self.objects.insert((repo.clone(), object.oid.clone()));
                }
            }
            Payload::GitRef {
                repo,
                name,
                old_oid,
                new_oid,
                symref,
            } => {
                let key = (repo.clone(), name.clone());
                if let Some(target) = symref {
                    self.symrefs.insert(key, target.clone());
                    return Ok(());
                }
                if self.refs.get(&key) != old_oid.as_ref() {
                    return Err(FoldError::RefMismatch(name.clone()));
                }
                match new_oid {
                    Some(oid) => {
                        if !self.objects.contains(&(repo.clone(), oid.clone())) {
                            return Err(FoldError::MissingObject(name.clone()));
                        }
                        self.refs.insert(key, oid.clone());
                    }
                    None => {
                        self.refs.remove(&key);
                    }
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn allowed(&mut self, path: &str) -> bool {
        let allowed = is_replicated(path);
        if !allowed {
            self.alarms.push(path.to_owned());
        }
        allowed
    }

    /// Paths that were skipped because they are off the allowlist.
    #[must_use]
    pub fn alarms(&self) -> &[String] {
        &self.alarms
    }

    /// The manifest: SHA-256 and length per file, sorted by path.
    #[must_use]
    pub fn manifest(&self) -> Vec<ManifestEntry> {
        self.files
            .iter()
            .map(|(path, content)| ManifestEntry {
                path: path.clone(),
                sha256: sha256(content),
                len: content.len() as u64,
            })
            .collect()
    }

    /// The refs, sorted.
    #[must_use]
    pub fn refs(&self) -> Vec<RefEntry> {
        self.refs
            .iter()
            .map(|((repo, name), oid)| RefEntry {
                repo: repo.clone(),
                name: name.clone(),
                oid: oid.clone(),
            })
            .collect()
    }

    /// The content of a file, when known.
    #[must_use]
    pub fn file(&self, path: &str) -> Option<&[u8]> {
        self.files.get(path).map(Vec::as_slice)
    }

    /// Whether the fold matches a checkpoint's manifest and refs.
    #[must_use]
    pub fn matches(&self, checkpoint: &Checkpoint) -> bool {
        self.manifest() == checkpoint.manifest && self.refs() == checkpoint.refs
    }
}
