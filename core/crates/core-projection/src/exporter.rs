//! The exporter (C1 §6.1, rules E1-E7): the holder turns letta-code's file changes into log
//! payloads.
//!
//! It reads files, never letta-code internals. It keeps the log's view of the agent (a
//! [`StateFold`] of everything exported, plus the transcript prefixes and the exported git
//! objects), so each scan emits exactly the difference between the disk and the log:
//!
//! - E1: a transcript that grew with its exported prefix unchanged becomes a `file_append` of the
//!   new bytes up to the last `\n` (a torn line is never exported); any other change is a
//!   `file_put`.
//! - E2: one scan is one batch; git objects come before the ref that needs them.
//! - E3: on a ref change, the objects reachable from the new oid that are not yet in the log.
//! - E5: agent and conversation records are linted; findings go in the next checkpoint.
//! - E6: a payload holding a secret or a binding value is blocked and reported, never appended.

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

use core_log::allowlist::STORE_FORMAT;
use core_log::fold::StateFold;
use core_log::hashes::GitAlgo;
use core_log::payload::{Checkpoint, GitObject, Payload};
use core_proto::ConversationId;
use core_proto::crypto::blake3_hash;
use serde_json::Value;
use walkdir::WalkDir;

use crate::ProjectionError;
use crate::git::{GitBackend, Head};
use crate::layout::{
    self, AGENTS_DIR, CONVERSATIONS_DIR, ConversationKey, PathClass, TRANSCRIPT, classify,
    conversation_key,
};
use crate::lint::{SecretScanner, h1_lint};

/// Raw object bytes per `git_objects` payload before a new payload is started.
const OBJECT_BATCH_BYTES: usize = 4 * 1024 * 1024;

/// A payload the deny-scanner blocked.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Blocked {
    /// The file, or the memfs repository.
    pub path: String,
    /// The rule (`secret_pattern` or `binding_value`).
    pub rule: &'static str,
}

/// One scan's output: the payloads to append in one transaction, in order.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ExportBatch {
    /// The payloads.
    pub payloads: Vec<Payload>,
    /// Exports refused by the deny-scanner (each one alarms; it is retried on the next scan).
    pub blocked: Vec<Blocked>,
}

impl ExportBatch {
    /// Whether there is nothing to append.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.payloads.is_empty()
    }
}

/// The holder's exporter for one agent tree (the agent and its subagents, which ride in its log).
pub struct StoreExporter<G> {
    root: PathBuf,
    git: G,
    scope: BTreeSet<String>,
    scanner: SecretScanner,
    fold: StateFold,
    transcripts: BTreeMap<String, (u64, [u8; 32])>,
    objects: BTreeSet<(String, Vec<u8>)>,
    heads: BTreeMap<String, Head>,
    violations: BTreeMap<String, Vec<String>>,
    owners: BTreeMap<String, String>,
}

impl<G: GitBackend> StoreExporter<G> {
    /// An exporter of `agent`'s files under the backend `root`, with nothing exported yet.
    pub fn new(root: impl Into<PathBuf>, git: G, agent: &str, scanner: SecretScanner) -> Self {
        Self {
            root: root.into(),
            git,
            scope: BTreeSet::from([agent.to_owned()]),
            scanner,
            fold: StateFold::new(),
            transcripts: BTreeMap::new(),
            objects: BTreeSet::new(),
            heads: BTreeMap::new(),
            violations: BTreeMap::new(),
            owners: BTreeMap::new(),
        }
    }

    /// Adds a subagent whose files ride in this agent's log.
    pub fn add_subagent(&mut self, agent: &str) {
        self.scope.insert(agent.to_owned());
    }

    /// The agents whose files this exporter exports.
    #[must_use]
    pub const fn scope(&self) -> &BTreeSet<String> {
        &self.scope
    }

    /// The backend root.
    #[must_use]
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Takes the log's canonical payloads as already exported (a new holder after materializing,
    /// or a restart), so only later changes are exported.
    ///
    /// # Errors
    ///
    /// [`ProjectionError::Fold`] when the payloads do not fold.
    pub fn seed<'a>(
        &mut self,
        payloads: impl IntoIterator<Item = &'a Payload>,
    ) -> Result<(), ProjectionError> {
        for payload in payloads {
            self.record(payload)?;
        }
        Ok(())
    }

    /// The log's view of the agent's files and refs.
    #[must_use]
    pub const fn fold(&self) -> &StateFold {
        &self.fold
    }

    fn record(&mut self, payload: &Payload) -> Result<(), ProjectionError> {
        self.fold.apply(payload)?;
        match payload {
            Payload::FilePut { path, .. } | Payload::FileAppend { path, .. } => {
                if is_transcript(path)
                    && let Some(content) = self.fold.file(path)
                {
                    let prefix = (content.len() as u64, blake3_hash(content));
                    self.transcripts.insert(path.clone(), prefix);
                }
            }
            Payload::FileDelete { path } => {
                self.transcripts.remove(path);
                self.violations.remove(path);
            }
            Payload::GitObjects { repo, objects, .. } => {
                for object in objects {
                    self.objects.insert((repo.clone(), object.oid.clone()));
                }
            }
            Payload::GitRef {
                repo,
                name,
                symref: Some(target),
                ..
            } if name == "HEAD" => {
                self.heads
                    .insert(repo.clone(), Head::Symbolic(target.clone()));
            }
            Payload::GitRef {
                repo,
                name,
                new_oid: Some(id),
                symref: None,
                ..
            } if name == "HEAD" => {
                self.heads.insert(repo.clone(), Head::Detached(id.clone()));
            }
            _ => {}
        }
        Ok(())
    }

    /// Scans the backend and returns the batch that brings the log up to the disk. The exporter
    /// counts the batch as exported; if appending it fails, rebuild the exporter from the log.
    ///
    /// # Errors
    ///
    /// [`ProjectionError`] when a file or repository cannot be read.
    pub fn scan(&mut self) -> Result<ExportBatch, ProjectionError> {
        let mut batch = ExportBatch::default();
        self.scan_files(&mut batch)?;
        self.scan_repos(&mut batch)?;
        for payload in &batch.payloads {
            self.record(payload)?;
        }
        Ok(batch)
    }

    fn scan_files(&mut self, batch: &mut ExportBatch) -> Result<(), ProjectionError> {
        let mut seen = BTreeSet::new();
        for path in self.replicated_files() {
            let native = layout::to_native(&self.root, &path);
            let content = match std::fs::read(&native) {
                Ok(content) => content,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
                Err(error) => return Err(ProjectionError::io(&native, &error)),
            };
            seen.insert(path.clone());
            let Some(payload) = self.file_payload(&path, content) else {
                continue;
            };
            let bytes = match &payload {
                Payload::FilePut { content, .. } => content.as_slice(),
                Payload::FileAppend { bytes, .. } => bytes.as_slice(),
                _ => &[],
            };
            if let Some(rule) = self.scanner.check(bytes) {
                batch.blocked.push(Blocked { path, rule });
                continue;
            }
            if let Payload::FilePut { content, .. } = &payload {
                let findings = h1_lint(&path, content);
                if findings.is_empty() {
                    self.violations.remove(&path);
                } else {
                    self.violations.insert(path.clone(), findings);
                }
            }
            batch.payloads.push(payload);
        }
        let gone: Vec<String> = self
            .fold
            .manifest()
            .into_iter()
            .map(|entry| entry.path)
            .filter(|path| !seen.contains(path))
            .collect();
        for path in gone {
            batch.payloads.push(Payload::FileDelete { path });
        }
        Ok(())
    }

    /// The payload that brings `path` from the log's content to `content`, if it differs.
    fn file_payload(&self, path: &str, mut content: Vec<u8>) -> Option<Payload> {
        if !is_transcript(path) {
            return (self.fold.file(path) != Some(content.as_slice()))
                .then(|| Payload::file_put(path, content, false));
        }
        // E1: never export a torn line.
        let complete = content
            .iter()
            .rposition(|b| *b == b'\n')
            .map_or(0, |i| i + 1);
        content.truncate(complete);
        match self.transcripts.get(path) {
            Some(&(len, prefix)) => {
                let exported = usize::try_from(len).unwrap_or(usize::MAX);
                if content.len() >= exported && blake3_hash(&content[..exported]) == prefix {
                    (content.len() > exported).then(|| Payload::FileAppend {
                        path: path.to_owned(),
                        offset: len,
                        bytes: content[exported..].to_vec(),
                    })
                } else {
                    Some(Payload::file_put(path, content, false))
                }
            }
            None if content.is_empty() => None,
            None => Some(Payload::file_put(path, content, false)),
        }
    }

    /// The replicated files of the agents in scope, as backend-relative paths.
    fn replicated_files(&mut self) -> Vec<String> {
        let mut out = Vec::new();
        for agent in &self.scope {
            let record = layout::agent_record_path(agent);
            if layout::to_native(&self.root, &record).is_file() {
                out.push(record);
            }
        }
        let conversations = self.root.join(CONVERSATIONS_DIR);
        let Ok(entries) = std::fs::read_dir(&conversations) else {
            return out;
        };
        let mut dirs: Vec<String> = entries
            .flatten()
            .filter_map(|entry| entry.file_name().to_str().map(str::to_owned))
            .collect();
        dirs.sort();
        for dir in dirs {
            if !self.owns_conversation(&dir) {
                continue;
            }
            let base = conversations.join(&dir);
            for entry in WalkDir::new(&base)
                .sort_by_file_name()
                .into_iter()
                .flatten()
            {
                if !entry.file_type().is_file() {
                    continue;
                }
                if let Some(path) = layout::to_relative(&self.root, entry.path())
                    && classify(&path) == PathClass::Replicated
                {
                    out.push(path);
                }
            }
        }
        out
    }

    /// Whether a conversation directory belongs to an agent in scope: the default conversation
    /// names its agent; another names it in `conversation.json` (`agent_id` or `agentId`).
    fn owns_conversation(&mut self, dir: &str) -> bool {
        let owner = match conversation_key(dir) {
            Some(ConversationKey::Default { agent }) => Some(agent),
            Some(ConversationKey::Conversation { .. }) => {
                if let Some(owner) = self.owners.get(dir) {
                    Some(owner.clone())
                } else {
                    let path = self
                        .root
                        .join(CONVERSATIONS_DIR)
                        .join(dir)
                        .join("conversation.json");
                    let owner = std::fs::read(path)
                        .ok()
                        .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
                        .and_then(|record| {
                            ["agent_id", "agentId"].iter().find_map(|key| {
                                record.get(key).and_then(Value::as_str).map(str::to_owned)
                            })
                        });
                    if let Some(owner) = &owner {
                        self.owners.insert(dir.to_owned(), owner.clone());
                    }
                    owner
                }
            }
            None => None,
        };
        owner.is_some_and(|owner| self.scope.contains(&owner))
    }

    fn scan_repos(&self, batch: &mut ExportBatch) -> Result<(), ProjectionError> {
        let exported: BTreeMap<(String, String), Vec<u8>> = self
            .fold
            .refs()
            .into_iter()
            .map(|r| ((r.repo, r.name), r.oid))
            .collect();
        for agent in &self.scope {
            let Some(repo) = layout::memfs_repo_of(&self.root, agent) else {
                continue;
            };
            let dir = layout::to_native(&self.root, &repo);
            let algo = self.git.algo(&dir)?;
            let disk = self.git.refs(&dir)?;
            let mut wanted: Vec<(String, Vec<u8>)> = disk.refs.clone();
            if let Some(Head::Detached(id)) = &disk.head {
                wanted.push(("HEAD".to_owned(), id.clone()));
            }
            let changed: Vec<&(String, Vec<u8>)> = wanted
                .iter()
                .filter(|(name, id)| exported.get(&(repo.clone(), name.clone())) != Some(id))
                .collect();
            let tips: Vec<Vec<u8>> = changed.iter().map(|(_, id)| id.clone()).collect();
            let objects = self.git.objects_since(&dir, &tips, &|oid: &[u8]| {
                self.objects.contains(&(repo.clone(), oid.to_vec()))
            })?;
            if let Some(rule) = objects
                .iter()
                .find_map(|object| self.scanner.check(&object.content))
            {
                batch.blocked.push(Blocked { path: repo, rule });
                continue;
            }
            push_objects(batch, &repo, algo, objects);
            for (name, id) in changed {
                batch.payloads.push(Payload::GitRef {
                    repo: repo.clone(),
                    name: name.clone(),
                    old_oid: exported.get(&(repo.clone(), name.clone())).cloned(),
                    new_oid: Some(id.clone()),
                    symref: None,
                });
            }
            for ((ref_repo, name), id) in &exported {
                if *ref_repo == repo && !wanted.iter().any(|(n, _)| n == name) {
                    batch.payloads.push(Payload::GitRef {
                        repo: repo.clone(),
                        name: name.clone(),
                        old_oid: Some(id.clone()),
                        new_oid: None,
                        symref: None,
                    });
                }
            }
            if let Some(Head::Symbolic(target)) = &disk.head
                && self.heads.get(&repo) != disk.head.as_ref()
            {
                batch.payloads.push(Payload::GitRef {
                    repo: repo.clone(),
                    name: "HEAD".to_owned(),
                    old_oid: None,
                    new_oid: None,
                    symref: Some(target.clone()),
                });
            }
        }
        Ok(())
    }

    /// A checkpoint of what the log holds now (E4), with the H1 findings (E5) and I6 parity (E7).
    #[must_use]
    pub fn checkpoint(
        &self,
        feed_heads: Vec<(ConversationId, u64)>,
        runtime_parity: &str,
    ) -> Checkpoint {
        let mut h1_violations: Vec<String> = self.violations.values().flatten().cloned().collect();
        h1_violations.sort();
        h1_violations.dedup();
        Checkpoint {
            manifest: self.fold.manifest(),
            refs: self.fold.refs(),
            feed_heads,
            store_format: STORE_FORMAT,
            runtime_parity: runtime_parity.to_owned(),
            h1_violations,
        }
    }
}

fn push_objects(batch: &mut ExportBatch, repo: &str, algo: GitAlgo, objects: Vec<GitObject>) {
    let mut chunk: Vec<GitObject> = Vec::new();
    let mut bytes = 0;
    for object in objects {
        if bytes + object.content.len() > OBJECT_BATCH_BYTES && !chunk.is_empty() {
            batch.payloads.push(Payload::GitObjects {
                repo: repo.to_owned(),
                algo,
                objects: std::mem::take(&mut chunk),
            });
            bytes = 0;
        }
        bytes += object.content.len();
        chunk.push(object);
    }
    if !chunk.is_empty() {
        batch.payloads.push(Payload::GitObjects {
            repo: repo.to_owned(),
            algo,
            objects: chunk,
        });
    }
}

fn is_transcript(path: &str) -> bool {
    path.starts_with(&format!("{CONVERSATIONS_DIR}/")) && path.ends_with(&format!("/{TRANSCRIPT}"))
}

/// Whether a backend path is an agent record.
#[must_use]
pub fn is_agent_record(path: &str) -> bool {
    path.starts_with(&format!("{AGENTS_DIR}/"))
}
