//! The letta-code local-backend layout as files (store_format 1), and the H1 split between what
//! replicates and what stays on the node.
//!
//! Paths are relative to the backend root and use `/`. Replicated files are the store_format 1
//! allowlist (`core_log::allowlist`). Memfs repositories replicate as git objects and refs only;
//! their working tree, `.git/config`, `.git/index` and `.git/hooks` stay node-local, as do the
//! letta-code caches (`system-prompt.json`, `manifest.json`), `settings.json`, provider
//! credentials, mods and the bindings table.

use std::path::{Path, PathBuf};

use base64::Engine as _;
use base64::engine::general_purpose::{STANDARD, URL_SAFE, URL_SAFE_NO_PAD};
use core_log::allowlist::{is_replicated, is_well_formed};

/// The directory of agent records.
pub const AGENTS_DIR: &str = "agents";
/// The directory of conversations.
pub const CONVERSATIONS_DIR: &str = "conversations";
/// The directory of memfs repositories.
pub const MEMFS_DIR: &str = "memfs";
/// The transcript file of a conversation.
pub const TRANSCRIPT: &str = "messages.jsonl";

/// What a backend path is, for the exporter and the watcher.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PathClass {
    /// On the replicated allowlist: exported as `file_*` entries.
    Replicated,
    /// Inside a memfs repository's git directory, where objects or refs live: a change there
    /// means the repository's refs may have moved.
    MemfsGit {
        /// The repository, as its backend-relative directory.
        repo: String,
    },
    /// Node-local under H1; the reason names the rule.
    NodeLocal(NodeLocal),
    /// Anything else (ignored).
    Other,
}

/// Why a path never replicates (H1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NodeLocal {
    /// `.git/hooks`: every node generates its own (I3).
    GitHooks,
    /// `.git/config`, `.git/index` and other per-checkout git state (I3, I4).
    GitLocalState,
    /// The memfs working tree: checked out from refs on materialize.
    WorkingTree,
    /// A letta-code cache that rebuilds (`system-prompt.json`, `manifest.json`).
    Cache,
    /// `settings.json`, provider credentials, mods and bindings.
    Settings,
}

impl NodeLocal {
    /// A stable name for logs and tests.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::GitHooks => "git_hooks",
            Self::GitLocalState => "git_local_state",
            Self::WorkingTree => "working_tree",
            Self::Cache => "cache",
            Self::Settings => "settings",
        }
    }
}

/// Classifies a backend-relative path.
#[must_use]
pub fn classify(path: &str) -> PathClass {
    if !is_well_formed(path) {
        return PathClass::Other;
    }
    if is_replicated(path) {
        return PathClass::Replicated;
    }
    let segments: Vec<&str> = path.split('/').collect();
    match segments.as_slice() {
        ["settings.json" | "bindings.json"] | ["providers" | "mods", ..] => {
            PathClass::NodeLocal(NodeLocal::Settings)
        }
        [CONVERSATIONS_DIR, _, "system-prompt.json" | "manifest.json"] => {
            PathClass::NodeLocal(NodeLocal::Cache)
        }
        [MEMFS_DIR, rest @ ..] => classify_memfs(rest),
        _ => PathClass::Other,
    }
}

fn classify_memfs(rest: &[&str]) -> PathClass {
    // `memfs/<agent>/memory/.git/...` (letta-code's layout) or `memfs/<agent>/.git/...`.
    let (repo_len, git) = match rest {
        [_, "memory", ".git", git @ ..] => (3, git),
        [_, ".git", git @ ..] => (2, git),
        [] | [_] => return PathClass::Other,
        _ => return PathClass::NodeLocal(NodeLocal::WorkingTree),
    };
    let repo = format!("{MEMFS_DIR}/{}", rest[..repo_len - 1].join("/"));
    match git {
        ["hooks", ..] => PathClass::NodeLocal(NodeLocal::GitHooks),
        ["HEAD" | "packed-refs"] | ["refs" | "objects", ..] => PathClass::MemfsGit { repo },
        _ => PathClass::NodeLocal(NodeLocal::GitLocalState),
    }
}

/// The memfs repositories under `root`, as backend-relative directories (`memfs/<agent>/memory`
/// when letta-code's nested layout is used, else `memfs/<agent>`), sorted.
#[must_use]
pub fn memfs_repos(root: &Path) -> Vec<String> {
    let mut repos = Vec::new();
    let Ok(entries) = std::fs::read_dir(root.join(MEMFS_DIR)) else {
        return repos;
    };
    for entry in entries.flatten() {
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if let Some(repo) = memfs_repo_of(root, &name) {
            repos.push(repo);
        }
    }
    repos.sort();
    repos
}

/// The memfs repository of `agent` under `root`, when it exists.
#[must_use]
pub fn memfs_repo_of(root: &Path, agent: &str) -> Option<String> {
    let nested = format!("{MEMFS_DIR}/{agent}/memory");
    let flat = format!("{MEMFS_DIR}/{agent}");
    [nested, flat]
        .into_iter()
        .find(|repo| to_native(root, repo).join(".git").is_dir())
}

/// The agent a memfs repository belongs to.
#[must_use]
pub fn repo_agent(repo: &str) -> Option<&str> {
    let mut segments = repo.split('/');
    (segments.next() == Some(MEMFS_DIR))
        .then(|| segments.next())
        .flatten()
}

/// The native path of a backend-relative path.
#[must_use]
pub fn to_native(root: &Path, relative: &str) -> PathBuf {
    relative
        .split('/')
        .fold(root.to_path_buf(), |path, segment| path.join(segment))
}

/// The backend-relative form of `path` under `root`, when it is under it and well formed.
#[must_use]
pub fn to_relative(root: &Path, path: &Path) -> Option<String> {
    let relative = path.strip_prefix(root).ok()?;
    let parts: Option<Vec<&str>> = relative
        .components()
        .map(|c| c.as_os_str().to_str())
        .collect();
    let joined = parts?.join("/");
    is_well_formed(&joined).then_some(joined)
}

/// The key of a conversation directory: `default:<agent>` or `conversation:<id>`.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum ConversationKey {
    /// The agent's default conversation.
    Default {
        /// The agent.
        agent: String,
    },
    /// A named conversation.
    Conversation {
        /// The conversation id.
        id: String,
    },
}

impl ConversationKey {
    /// The conversation id the feed and the divergence entry use: the id, or `default:<agent>`.
    #[must_use]
    pub fn conversation_id(&self) -> String {
        match self {
            Self::Default { agent } => format!("default:{agent}"),
            Self::Conversation { id } => id.clone(),
        }
    }

    /// The directory name: base64url (no padding) of the key text.
    #[must_use]
    pub fn dir_name(&self) -> String {
        let text = match self {
            Self::Default { agent } => format!("default:{agent}"),
            Self::Conversation { id } => format!("conversation:{id}"),
        };
        URL_SAFE_NO_PAD.encode(text)
    }
}

/// Decodes a conversation directory name. letta-code writes base64url; padded and standard
/// alphabets are accepted too.
#[must_use]
pub fn conversation_key(dir_name: &str) -> Option<ConversationKey> {
    let bytes = [&URL_SAFE_NO_PAD, &URL_SAFE, &STANDARD]
        .iter()
        .find_map(|engine| engine.decode(dir_name).ok())?;
    let text = String::from_utf8(bytes).ok()?;
    if let Some(agent) = text.strip_prefix("default:") {
        return Some(ConversationKey::Default {
            agent: agent.to_owned(),
        });
    }
    text.strip_prefix("conversation:")
        .map(|id| ConversationKey::Conversation { id: id.to_owned() })
}

/// The agent record path of `agent`: `agents/<base64url(agent)>.json`.
#[must_use]
pub fn agent_record_path(agent: &str) -> String {
    format!("{AGENTS_DIR}/{}.json", URL_SAFE_NO_PAD.encode(agent))
}

/// The agent a record path names, decoded from its file name.
#[must_use]
pub fn agent_of_record(path: &str) -> Option<String> {
    let name = path
        .strip_prefix("agents/")?
        .strip_suffix(".json")
        .filter(|n| !n.contains('/'))?;
    [&URL_SAFE_NO_PAD, &URL_SAFE, &STANDARD]
        .iter()
        .find_map(|engine| engine.decode(name).ok())
        .and_then(|bytes| String::from_utf8(bytes).ok())
}

/// The conversation directory of a replicated conversation path.
#[must_use]
pub fn conversation_dir(path: &str) -> Option<&str> {
    let mut segments = path.split('/');
    (segments.next() == Some(CONVERSATIONS_DIR))
        .then(|| segments.next())
        .flatten()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_h1_split() {
        assert_eq!(classify("agents/YQ.json"), PathClass::Replicated);
        assert_eq!(
            classify("conversations/abc/messages.jsonl"),
            PathClass::Replicated
        );
        assert_eq!(
            classify("conversations/abc/system-prompt.json"),
            PathClass::NodeLocal(NodeLocal::Cache)
        );
        assert_eq!(
            classify("conversations/abc/manifest.json"),
            PathClass::NodeLocal(NodeLocal::Cache)
        );
        assert_eq!(
            classify("settings.json"),
            PathClass::NodeLocal(NodeLocal::Settings)
        );
        assert_eq!(
            classify("providers/auth.json"),
            PathClass::NodeLocal(NodeLocal::Settings)
        );
        assert_eq!(
            classify("memfs/a/memory/.git/hooks/pre-commit"),
            PathClass::NodeLocal(NodeLocal::GitHooks)
        );
        assert_eq!(
            classify("memfs/a/memory/.git/config"),
            PathClass::NodeLocal(NodeLocal::GitLocalState)
        );
        assert_eq!(
            classify("memfs/a/memory/.git/index"),
            PathClass::NodeLocal(NodeLocal::GitLocalState)
        );
        assert_eq!(
            classify("memfs/a/memory/notes.md"),
            PathClass::NodeLocal(NodeLocal::WorkingTree)
        );
        assert_eq!(
            classify("memfs/a/memory/.git/refs/heads/main"),
            PathClass::MemfsGit {
                repo: "memfs/a/memory".into()
            }
        );
        assert_eq!(
            classify("memfs/a/.git/HEAD"),
            PathClass::MemfsGit {
                repo: "memfs/a".into()
            }
        );
    }

    #[test]
    fn conversation_keys_round_trip() {
        for key in [
            ConversationKey::Default {
                agent: "agent-local-1".into(),
            },
            ConversationKey::Conversation {
                id: "local-conv-7b0c".into(),
            },
        ] {
            assert_eq!(conversation_key(&key.dir_name()), Some(key));
        }
        assert_eq!(
            agent_of_record(&agent_record_path("agent-x")).as_deref(),
            Some("agent-x")
        );
    }
}
