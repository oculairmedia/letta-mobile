//! The materializer (C1 §6.2): rebuild an agent's letta-code files from the canonical line, on
//! becoming holder and in a move's verify step.
//!
//! 1. Fold the payloads in seq order (offset-checked appends, old-oid-checked refs; paths off the
//!    allowlist are skipped and alarmed).
//! 2. Write every file verbatim; for each memfs repository, initialise `.git` node-locally
//!    (`core.hooksPath`, `core.autocrlf=false`), write the objects, set refs and `HEAD`, and check
//!    the working tree out from `HEAD`.
//! 3. Read the result back from disk (SHA-256 per replicated file, refs from git) and compare it
//!    with the checkpoint: the manifest check is the backstop against any rewrite (L12).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use core_log::fold::StateFold;
use core_log::payload::{Checkpoint, GitObject, ManifestEntry, Payload, RefEntry, kind};
use walkdir::WalkDir;

use crate::ProjectionError;
use crate::git::{GitBackend, Head, NodeGitConfig};
use crate::layout::{self, PathClass, classify};

/// A materialized store, read back from disk.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Materialized {
    /// SHA-256 and length of every replicated file on disk, sorted by path.
    pub manifest: Vec<ManifestEntry>,
    /// Every ref of every memfs repository on disk (`HEAD` only when detached), sorted.
    pub refs: Vec<RefEntry>,
    /// Paths the line named off the allowlist: skipped, never written.
    pub alarms: Vec<String>,
    /// The memfs repositories written.
    pub repos: Vec<String>,
}

/// One `verify_failed` difference: path, expected SHA-256, actual SHA-256 (absent: `None`).
pub type Diff = (String, [u8; 32], Option<[u8; 32]>);

/// How a materialized store differs from a checkpoint.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Mismatch {
    /// File differences, sorted by path.
    pub diffs: Vec<Diff>,
    /// Whether the refs differ.
    pub refs_mismatch: bool,
}

impl Materialized {
    /// Compares with `checkpoint` exactly: every manifest line and every ref.
    ///
    /// # Errors
    ///
    /// The [`Mismatch`].
    pub fn verify(&self, checkpoint: &Checkpoint) -> Result<(), Mismatch> {
        let mut diffs = Vec::new();
        let actual: BTreeMap<&str, &ManifestEntry> =
            self.manifest.iter().map(|e| (e.path.as_str(), e)).collect();
        let expected: BTreeMap<&str, &ManifestEntry> = checkpoint
            .manifest
            .iter()
            .map(|e| (e.path.as_str(), e))
            .collect();
        for (path, want) in &expected {
            match actual.get(path) {
                Some(got) if got.sha256 == want.sha256 && got.len == want.len => {}
                Some(got) => diffs.push(((*path).to_owned(), want.sha256, Some(got.sha256))),
                None => diffs.push(((*path).to_owned(), want.sha256, None)),
            }
        }
        for (path, got) in &actual {
            if !expected.contains_key(path) {
                diffs.push(((*path).to_owned(), [0; 32], Some(got.sha256)));
            }
        }
        diffs.sort();
        let refs_mismatch = self.refs != checkpoint.refs;
        if diffs.is_empty() && !refs_mismatch {
            Ok(())
        } else {
            Err(Mismatch {
                diffs,
                refs_mismatch,
            })
        }
    }
}

/// Builds stores from the log.
#[derive(Clone, Debug)]
pub struct Materializer<G> {
    git: G,
    config: NodeGitConfig,
}

#[derive(Default)]
struct Repo {
    objects: Vec<GitObject>,
    head: Option<Head>,
}

impl<G: GitBackend> Materializer<G> {
    /// A materializer that gives every repository `config` (this node's hooks dir).
    pub const fn new(git: G, config: NodeGitConfig) -> Self {
        Self { git, config }
    }

    /// Materializes `payloads` (the canonical line up to some seq, in order) into the empty
    /// directory `staging`, then reads it back.
    ///
    /// # Errors
    ///
    /// [`ProjectionError::Fold`] for a payload that does not apply (a wrong append offset, a ref
    /// whose old oid is not current); [`ProjectionError::StoreFormatUnsupported`] for an unknown
    /// kind below `0x8000`; I/O and git errors.
    pub fn materialize<'a>(
        &self,
        payloads: impl IntoIterator<Item = &'a Payload>,
        staging: &Path,
    ) -> Result<Materialized, ProjectionError> {
        let mut fold = StateFold::new();
        let mut repos: BTreeMap<String, Repo> = BTreeMap::new();
        for payload in payloads {
            if let Payload::Unknown { kind: code, .. } = payload
                && *code < kind::IGNORABLE_FROM
            {
                return Err(ProjectionError::StoreFormatUnsupported(*code));
            }
            fold.apply(payload)?;
            match payload {
                Payload::GitObjects { repo, objects, .. } if repo_allowed(repo) => {
                    repos
                        .entry(repo.clone())
                        .or_default()
                        .objects
                        .extend(objects.iter().cloned());
                }
                Payload::GitRef {
                    repo,
                    name,
                    new_oid,
                    symref,
                    ..
                } if name == "HEAD" && repo_allowed(repo) => {
                    let entry = repos.entry(repo.clone()).or_default();
                    entry.head = match (symref, new_oid) {
                        (Some(target), _) => Some(Head::Symbolic(target.clone())),
                        (None, Some(id)) => Some(Head::Detached(id.clone())),
                        (None, None) => None,
                    };
                }
                _ => {}
            }
        }
        std::fs::create_dir_all(staging).map_err(|e| ProjectionError::io(staging, &e))?;
        for entry in fold.manifest() {
            let content = fold.file(&entry.path).unwrap_or_default();
            let native = layout::to_native(staging, &entry.path);
            if let Some(parent) = native.parent() {
                std::fs::create_dir_all(parent).map_err(|e| ProjectionError::io(parent, &e))?;
            }
            std::fs::write(&native, content).map_err(|e| ProjectionError::io(&native, &e))?;
        }
        let refs = fold.refs();
        for (name, repo) in &repos {
            let dir = layout::to_native(staging, name);
            self.git.init(&dir, &self.config)?;
            self.git.write_objects(&dir, &repo.objects)?;
            let direct: Vec<(String, Vec<u8>)> = refs
                .iter()
                .filter(|r| r.repo == *name && r.name != "HEAD")
                .map(|r| (r.name.clone(), r.oid.clone()))
                .collect();
            self.git.set_refs(&dir, &direct, repo.head.as_ref())?;
            self.git.checkout_head(&dir)?;
        }
        let mut materialized = read_back(&self.git, staging, repos.keys().cloned().collect())?;
        materialized.alarms = fold.alarms().to_vec();
        Ok(materialized)
    }
}

fn repo_allowed(repo: &str) -> bool {
    layout::repo_agent(repo).is_some() && core_log::allowlist::is_well_formed(repo)
}

/// Reads a backend tree back: the manifest of its replicated files and the refs of `repos`.
///
/// # Errors
///
/// I/O and git errors.
pub fn read_back<G: GitBackend>(
    git: &G,
    root: &Path,
    repos: Vec<String>,
) -> Result<Materialized, ProjectionError> {
    let manifest = disk_manifest(root, |_| true)?;
    let mut refs = Vec::new();
    for repo in &repos {
        let disk = git.refs(&layout::to_native(root, repo))?;
        for (name, oid) in disk.refs {
            refs.push(RefEntry {
                repo: repo.clone(),
                name,
                oid,
            });
        }
        if let Some(Head::Detached(oid)) = disk.head {
            refs.push(RefEntry {
                repo: repo.clone(),
                name: "HEAD".to_owned(),
                oid,
            });
        }
    }
    refs.sort();
    Ok(Materialized {
        manifest,
        refs,
        alarms: Vec::new(),
        repos,
    })
}

/// SHA-256 and length of every replicated file under `root` that `keep` accepts, sorted by path.
///
/// # Errors
///
/// I/O errors.
pub fn disk_manifest(
    root: &Path,
    keep: impl Fn(&str) -> bool,
) -> Result<Vec<ManifestEntry>, ProjectionError> {
    let mut out = Vec::new();
    for entry in WalkDir::new(root).sort_by_file_name() {
        let entry = entry.map_err(|e| ProjectionError::Io(e.to_string()))?;
        if !entry.file_type().is_file() {
            continue;
        }
        let Some(path) = layout::to_relative(root, entry.path()) else {
            continue;
        };
        if classify(&path) != PathClass::Replicated || !keep(&path) {
            continue;
        }
        let content =
            std::fs::read(entry.path()).map_err(|e| ProjectionError::io(entry.path(), &e))?;
        out.push(ManifestEntry {
            sha256: core_log::hashes::sha256(&content),
            len: content.len() as u64,
            path,
        });
    }
    out.sort();
    Ok(out)
}

/// Moves a materialized agent from `staging` into the live backend `live`: each replicated file
/// replaces its live copy and each memfs repository replaces the live one. Node-local files in
/// `live` (caches, settings, other agents) are left alone.
///
/// # Errors
///
/// I/O errors.
pub fn install(
    staging: &Path,
    live: &Path,
    materialized: &Materialized,
) -> Result<(), ProjectionError> {
    for entry in &materialized.manifest {
        let from = layout::to_native(staging, &entry.path);
        let to = layout::to_native(live, &entry.path);
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent).map_err(|e| ProjectionError::io(parent, &e))?;
        }
        move_path(&from, &to)?;
    }
    for repo in &materialized.repos {
        let from = layout::to_native(staging, repo);
        let to = layout::to_native(live, repo);
        if to.exists() {
            std::fs::remove_dir_all(&to).map_err(|e| ProjectionError::io(&to, &e))?;
        }
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent).map_err(|e| ProjectionError::io(parent, &e))?;
        }
        move_path(&from, &to)?;
    }
    Ok(())
}

fn move_path(from: &Path, to: &Path) -> Result<(), ProjectionError> {
    if std::fs::rename(from, to).is_ok() {
        return Ok(());
    }
    // Across volumes: copy, then remove the staging copy.
    if from.is_dir() {
        for entry in WalkDir::new(from) {
            let entry = entry.map_err(|e| ProjectionError::Io(e.to_string()))?;
            let relative = entry
                .path()
                .strip_prefix(from)
                .map_err(|e| ProjectionError::Io(e.to_string()))?;
            let target: PathBuf = to.join(relative);
            if entry.file_type().is_dir() {
                std::fs::create_dir_all(&target).map_err(|e| ProjectionError::io(&target, &e))?;
            } else {
                std::fs::copy(entry.path(), &target)
                    .map_err(|e| ProjectionError::io(&target, &e))?;
            }
        }
        std::fs::remove_dir_all(from).map_err(|e| ProjectionError::io(from, &e))?;
    } else {
        std::fs::copy(from, to).map_err(|e| ProjectionError::io(to, &e))?;
        std::fs::remove_file(from).map_err(|e| ProjectionError::io(from, &e))?;
    }
    Ok(())
}
