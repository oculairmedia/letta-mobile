//! The git operations the projection needs, as our own trait. [`crate::gitoxide`] implements it
//! with gix; nothing else in the crate names a git library.
//!
//! The exporter reads refs and the objects reachable from them, byte-exact as committed (I4
//! normalisation happens in git at commit time, never here). The materializer initialises a
//! repository node-locally (hooks path, `core.autocrlf=false`), writes loose objects, sets refs and
//! `HEAD`, and checks the working tree out from `HEAD`.

use std::path::{Path, PathBuf};

use core_log::hashes::GitAlgo;
use core_log::payload::GitObject;
use thiserror::Error;

/// A git operation failed.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum GitError {
    /// The repository could not be opened or created.
    #[error("repository {path}: {message}")]
    Repo {
        /// The repository directory.
        path: String,
        /// What went wrong.
        message: String,
    },
    /// An object, ref or checkout operation failed.
    #[error("{0}")]
    Operation(String),
    /// The repository uses a hash this build does not handle.
    #[error("unsupported object hash {0}")]
    UnsupportedHash(String),
}

/// What `HEAD` holds.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Head {
    /// A symbolic ref (`refs/heads/main`).
    Symbolic(String),
    /// A detached object id.
    Detached(Vec<u8>),
}

/// The refs of a repository: `HEAD` and every direct ref under `refs/`, sorted by name.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RepoRefs {
    /// `HEAD`, when the repository has one.
    pub head: Option<Head>,
    /// `(name, oid)` for each direct ref, sorted by name.
    pub refs: Vec<(String, Vec<u8>)>,
}

/// The node-local git configuration a materialized repository gets (I3, I4).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct NodeGitConfig {
    /// `core.hooksPath`: the node's own hooks directory. Hooks are never replicated.
    pub hooks_path: Option<PathBuf>,
}

/// Git access for the projection.
pub trait GitBackend: Send + Sync {
    /// The object hash of the repository at `dir` (its work tree root).
    ///
    /// # Errors
    ///
    /// [`GitError`] when the repository cannot be opened.
    fn algo(&self, dir: &Path) -> Result<GitAlgo, GitError>;

    /// The repository's `HEAD` and direct refs.
    ///
    /// # Errors
    ///
    /// [`GitError`] when the repository cannot be read.
    fn refs(&self, dir: &Path) -> Result<RepoRefs, GitError>;

    /// Every object reachable from `tips` that `known` does not report as already exported, as
    /// raw content. Traversal stops at a known commit (its ancestry was exported with it).
    ///
    /// # Errors
    ///
    /// [`GitError`] when an object is missing or unreadable.
    fn objects_since(
        &self,
        dir: &Path,
        tips: &[Vec<u8>],
        known: &dyn Fn(&[u8]) -> bool,
    ) -> Result<Vec<GitObject>, GitError>;

    /// Creates an empty repository at `dir` with the node-local configuration.
    ///
    /// # Errors
    ///
    /// [`GitError`] when it cannot be created.
    fn init(&self, dir: &Path, config: &NodeGitConfig) -> Result<(), GitError>;

    /// Writes raw objects as loose objects; each id is checked against its content.
    ///
    /// # Errors
    ///
    /// [`GitError`] when an object cannot be written or its id does not match.
    fn write_objects(&self, dir: &Path, objects: &[GitObject]) -> Result<(), GitError>;

    /// Sets direct refs and `HEAD`.
    ///
    /// # Errors
    ///
    /// [`GitError`] when a ref cannot be written.
    fn set_refs(
        &self,
        dir: &Path,
        refs: &[(String, Vec<u8>)],
        head: Option<&Head>,
    ) -> Result<(), GitError>;

    /// Checks the working tree out from `HEAD` (nothing to do for an unborn `HEAD`).
    ///
    /// # Errors
    ///
    /// [`GitError`] when the checkout fails.
    fn checkout_head(&self, dir: &Path) -> Result<(), GitError>;
}
