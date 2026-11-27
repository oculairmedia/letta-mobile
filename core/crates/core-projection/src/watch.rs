//! Watching the backend directory: the only module that names notify.
//!
//! letta-code writes in bursts (a transcript append, a conversation record, a memory commit). The
//! watcher debounces them and reports the touched paths that matter to the exporter (replicated
//! files and memfs objects and refs); one report becomes one export batch (E2). Node-local paths
//! (caches, hooks, `.git/config`, the working tree) never trigger an export.

use std::path::{Path, PathBuf};
use std::time::Duration;

use notify_debouncer_mini::notify::{RecommendedWatcher, RecursiveMode};
use notify_debouncer_mini::{DebounceEventResult, DebouncedEventBatch, Debouncer, new_debouncer};

use crate::ProjectionError;
use crate::layout::{PathClass, classify, to_relative};

/// What the watcher reports.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Change {
    /// These backend-relative paths changed (replicated files, or a memfs repository's git dir).
    Paths(Vec<String>),
    /// Events were lost; rescan everything.
    Rescan,
}

/// A running watcher; dropping it stops watching.
pub struct BackendWatcher {
    _debouncer: Debouncer<RecommendedWatcher>,
    root: PathBuf,
}

impl std::fmt::Debug for BackendWatcher {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("BackendWatcher")
            .field("root", &self.root)
            .finish_non_exhaustive()
    }
}

impl BackendWatcher {
    /// Watches `root` recursively and calls `on_change` once per debounced burst that touched a
    /// path the exporter cares about.
    ///
    /// # Errors
    ///
    /// [`ProjectionError::Watch`] when the platform watcher cannot start.
    pub fn start(
        root: &Path,
        debounce: Duration,
        on_change: impl Fn(Change) + Send + 'static,
    ) -> Result<Self, ProjectionError> {
        let base = root.to_path_buf();
        let canonical = std::fs::canonicalize(root).unwrap_or_else(|_| base.clone());
        let handler = move |result: DebounceEventResult| match result {
            Ok(DebouncedEventBatch::Events(events)) => {
                let mut paths: Vec<String> = events
                    .iter()
                    .filter_map(|event| {
                        to_relative(&base, &event.path)
                            .or_else(|| to_relative(&canonical, &event.path))
                    })
                    .filter(|path| {
                        matches!(
                            classify(path),
                            PathClass::Replicated | PathClass::MemfsGit { .. }
                        )
                    })
                    .collect();
                paths.sort();
                paths.dedup();
                if !paths.is_empty() {
                    on_change(Change::Paths(paths));
                }
            }
            // `Rescan`, an error, or a batch kind this build does not know: rescan everything.
            _ => on_change(Change::Rescan),
        };
        let mut debouncer =
            new_debouncer(debounce, handler).map_err(|e| ProjectionError::Watch(e.to_string()))?;
        debouncer
            .watcher()
            .watch(root, RecursiveMode::Recursive)
            .map_err(|e| ProjectionError::Watch(e.to_string()))?;
        Ok(Self {
            _debouncer: debouncer,
            root: root.to_path_buf(),
        })
    }
}
