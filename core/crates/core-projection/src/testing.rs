//! Test support (feature `testing`): a letta-code store layout from the projection fixture, and
//! memfs commits written as raw git objects through a [`GitBackend`].
//!
//! The fixture (`docs/reference/contracts/projection/store-v1.json`) follows the layout the X0
//! spike recorded on letta-code 0.34.39 and the H1 inventory: `agents/<b64url(id)>.json`,
//! `conversations/<b64url(key)>/{conversation.json, messages.jsonl, system-prompt.json,
//! manifest.json}`, `memfs/<id>/memory` with its `.git` (hooks and config included), plus
//! `settings.json` and `providers/auth.json`.

use std::collections::BTreeMap;
use std::path::Path;

use core_log::hashes::{GitAlgo, git_oid};
use core_log::payload::GitObject;
use serde_json::Value;

use crate::ProjectionError;
use crate::git::{GitBackend, Head, NodeGitConfig};
use crate::layout;

/// One memfs commit: the full tree as `path -> content` (paths may contain `/`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommitSpec {
    /// The commit message.
    pub message: String,
    /// The tree.
    pub files: BTreeMap<String, String>,
    /// Commit time (seconds), for deterministic ids.
    pub time: u64,
}

/// A raw commit chain written into a repository.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Chain {
    /// Every object written.
    pub objects: Vec<GitObject>,
    /// The commit ids, oldest first.
    pub commits: Vec<Vec<u8>>,
}

fn object(kind: u8, name: &str, content: Vec<u8>) -> GitObject {
    GitObject {
        oid: git_oid(GitAlgo::Sha1, name, &content),
        kind,
        content,
    }
}

/// The raw objects of a tree (subtrees first) and its id.
fn tree_objects(files: &BTreeMap<String, String>, out: &mut Vec<GitObject>) -> Vec<u8> {
    let mut blobs: BTreeMap<String, Vec<u8>> = BTreeMap::new();
    let mut dirs: BTreeMap<String, BTreeMap<String, String>> = BTreeMap::new();
    for (path, content) in files {
        if let Some((dir, rest)) = path.split_once('/') {
            dirs.entry(dir.to_owned())
                .or_default()
                .insert(rest.to_owned(), content.clone());
        } else {
            let blob = object(3, "blob", content.clone().into_bytes());
            blobs.insert(path.clone(), blob.oid.clone());
            out.push(blob);
        }
    }
    // Git sorts tree entries by name, with directories compared as if suffixed by `/`.
    let mut entries: Vec<(String, &str, Vec<u8>)> = blobs
        .into_iter()
        .map(|(name, oid)| (name, "100644", oid))
        .collect();
    for (dir, inner) in &dirs {
        let oid = tree_objects(inner, out);
        entries.push((dir.clone(), "40000", oid));
    }
    entries.sort_by(|a, b| {
        let key = |e: &(String, &str, Vec<u8>)| {
            let mut k = e.0.clone().into_bytes();
            if e.1 == "40000" {
                k.push(b'/');
            }
            k
        };
        key(a).cmp(&key(b))
    });
    let mut content = Vec::new();
    for (name, mode, oid) in entries {
        content.extend_from_slice(format!("{mode} {name}\0").as_bytes());
        content.extend_from_slice(&oid);
    }
    let tree = object(2, "tree", content);
    let oid = tree.oid.clone();
    out.push(tree);
    oid
}

/// The raw objects of a commit chain on top of `parent`.
#[must_use]
pub fn chain(agent: &str, parent: Option<&[u8]>, commits: &[CommitSpec]) -> Chain {
    let mut out = Chain::default();
    let mut parent = parent.map(<[u8]>::to_vec);
    for spec in commits {
        let tree = tree_objects(&spec.files, &mut out.objects);
        let parent_line = parent
            .as_ref()
            .map(|p| format!("parent {}\n", hex::encode(p)))
            .unwrap_or_default();
        let who = format!("{agent} <{agent}@letta.com> {} +0000", spec.time);
        let text = format!(
            "tree {}\n{parent_line}author {who}\ncommitter {who}\n\n{}\n",
            hex::encode(&tree),
            spec.message
        );
        let commit = object(1, "commit", text.into_bytes());
        parent = Some(commit.oid.clone());
        out.commits.push(commit.oid.clone());
        out.objects.push(commit);
    }
    out
}

/// Writes `commits` into the repository at `dir` (created when absent) on `refs/heads/main`,
/// updates `HEAD` and checks the tree out, the way letta-code's memfs commit leaves a repository.
///
/// # Errors
///
/// Git errors.
pub fn commit<G: GitBackend>(
    git: &G,
    dir: &Path,
    agent: &str,
    commits: &[CommitSpec],
) -> Result<Chain, ProjectionError> {
    if !dir.join(".git").is_dir() {
        git.init(dir, &NodeGitConfig::default())?;
    }
    let refs = git.refs(dir)?;
    let parent = refs
        .refs
        .iter()
        .find(|(name, _)| name == "refs/heads/main")
        .map(|(_, oid)| oid.clone());
    let chain = chain(agent, parent.as_deref(), commits);
    git.write_objects(dir, &chain.objects)?;
    if let Some(tip) = chain.commits.last() {
        git.set_refs(
            dir,
            &[("refs/heads/main".to_owned(), tip.clone())],
            Some(&Head::Symbolic("refs/heads/main".to_owned())),
        )?;
        git.checkout_head(dir)?;
    }
    Ok(chain)
}

/// The fixture store, parsed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FixtureStore {
    /// The agent.
    pub agent: String,
    /// Backend-relative files and their text.
    pub files: BTreeMap<String, String>,
    /// The memfs repository directory.
    pub repo: String,
    /// The memfs commits.
    pub commits: Vec<CommitSpec>,
    /// Node-local files inside the repository's `.git` (hooks, layout policy).
    pub git_local: BTreeMap<String, String>,
}

impl FixtureStore {
    /// Parses the fixture JSON.
    ///
    /// # Panics
    ///
    /// On a malformed fixture.
    #[must_use]
    pub fn from_json(value: &Value) -> Self {
        let strings = |v: &Value| -> BTreeMap<String, String> {
            v.as_object()
                .map(|m| {
                    m.iter()
                        .map(|(k, v)| (k.clone(), v.as_str().expect("text").to_owned()))
                        .collect()
                })
                .unwrap_or_default()
        };
        let agent = value["agent"].as_str().expect("agent").to_owned();
        let files = strings(&value["files"])
            .into_iter()
            .map(|(k, v)| (expand(&k, &agent), v))
            .collect();
        Self {
            repo: expand(value["memfs"]["repo"].as_str().expect("repo"), &agent),
            commits: value["memfs"]["commits"]
                .as_array()
                .expect("commits")
                .iter()
                .map(|c| CommitSpec {
                    message: c["message"].as_str().expect("message").to_owned(),
                    files: strings(&c["files"]),
                    time: c["time"].as_u64().expect("time"),
                })
                .collect(),
            git_local: strings(&value["memfs"]["git_local"]),
            files,
            agent,
        }
    }

    /// Writes the store under `root`.
    ///
    /// # Errors
    ///
    /// I/O and git errors.
    pub fn write<G: GitBackend>(&self, git: &G, root: &Path) -> Result<Chain, ProjectionError> {
        for (path, text) in &self.files {
            write_file(root, path, text.as_bytes())?;
        }
        let dir = layout::to_native(root, &self.repo);
        let chain = commit(git, &dir, &self.agent, &self.commits)?;
        for (path, text) in &self.git_local {
            write_file(&dir.join(".git"), path, text.as_bytes())?;
        }
        Ok(chain)
    }
}

/// Expands `{agent}`, `{agent_b64}`, `{default_b64}` and `{conv:<id>}` in a fixture path.
#[must_use]
pub fn expand(path: &str, agent: &str) -> String {
    use base64::Engine as _;
    let agent_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(agent);
    let mut out = path
        .replace("{agent_b64}", &agent_b64)
        .replace(
            "{default_b64}",
            &layout::ConversationKey::Default {
                agent: agent.to_owned(),
            }
            .dir_name(),
        )
        .replace("{agent}", agent);
    while let Some(start) = out.find("{conv:") {
        let end = out[start..].find('}').map_or(out.len(), |e| start + e);
        let id = out[start + 6..end].to_owned();
        let dir = layout::ConversationKey::Conversation { id }.dir_name();
        out.replace_range(start..=end, &dir);
    }
    out
}

/// Writes one backend file, creating its directories.
///
/// # Errors
///
/// I/O errors.
pub fn write_file(root: &Path, relative: &str, bytes: &[u8]) -> Result<(), ProjectionError> {
    let path = layout::to_native(root, relative);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| ProjectionError::io(parent, &e))?;
    }
    std::fs::write(&path, bytes).map_err(|e| ProjectionError::io(&path, &e))
}

/// Appends to one backend file.
///
/// # Errors
///
/// I/O errors.
pub fn append_file(root: &Path, relative: &str, bytes: &[u8]) -> Result<(), ProjectionError> {
    use std::io::Write as _;
    let path = layout::to_native(root, relative);
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .create(true)
        .open(&path)
        .map_err(|e| ProjectionError::io(&path, &e))?;
    file.write_all(bytes)
        .map_err(|e| ProjectionError::io(&path, &e))
}
