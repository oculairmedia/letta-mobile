//! R2 at the interface: the letta-code store as log payloads, and back.
//!
//! The normative text is `docs/reference/contracts/projection/projection-v1.md`, with C1 §3.5 and
//! §6 (log-v1 R-20 to R-23) and the H1 split. The projection reads letta-code's **files**, never
//! its internals, and never rewrites a replicated byte.
//!
//! - [`layout`]: the local-backend layout and the H1 split (replicated, memfs git, node-local).
//! - [`exporter`]: [`StoreExporter`], the holder's file-to-payload export (E1-E7).
//! - [`materialize`]: [`Materializer`], the canonical line back into files and a git checkout,
//!   read back and compared with the checkpoint manifest; [`materialize::install`] swaps it in.
//! - [`divergence`]: [`diff_lines`], what a `divergence` entry needs from the two lines.
//! - [`lint`]: the H1 lint and the secret deny-scanner.
//! - [`git`]: the [`GitBackend`] trait; [`gitoxide`] implements it with gix.
//! - [`watch`]: [`BackendWatcher`], debounced change reports from notify.
//! - `testing` (feature `testing`): the fixture store and raw memfs commits for tests.

pub mod divergence;
pub mod exporter;
pub mod git;
pub mod gitoxide;
pub mod layout;
pub mod lint;
pub mod materialize;
#[cfg(feature = "testing")]
pub mod testing;
pub mod watch;

use std::path::Path;

use thiserror::Error;

pub use divergence::diff_lines;
pub use exporter::{Blocked, ExportBatch, StoreExporter};
pub use git::{GitBackend, GitError, Head, NodeGitConfig, RepoRefs};
pub use gitoxide::Gitoxide;
pub use materialize::{Materialized, Materializer, Mismatch};
pub use watch::{BackendWatcher, Change};

/// Why a projection step failed.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum ProjectionError {
    /// A file could not be read or written.
    #[error("io: {0}")]
    Io(String),
    /// A payload does not apply to the folded state.
    #[error("fold: {0}")]
    Fold(#[from] core_log::fold::FoldError),
    /// A git operation failed.
    #[error("git: {0}")]
    Git(#[from] GitError),
    /// The line holds a kind below `0x8000` this build does not know.
    #[error("store_format_unsupported: kind {0:#06x}")]
    StoreFormatUnsupported(u16),
    /// The watcher could not start.
    #[error("watch: {0}")]
    Watch(String),
}

impl ProjectionError {
    pub(crate) fn io(path: &Path, error: &std::io::Error) -> Self {
        Self::Io(format!("{}: {error}", path.display()))
    }
}
