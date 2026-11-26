//! [`GitBackend`] on gix (gitoxide), the only module that names a git library.
//!
//! gix reads refs (loose and packed) and objects (loose and packed), writes loose objects and refs
//! through its own transactions, and checks a tree out through `gix-worktree-state`, the code path
//! of its own clone. Nothing shells out to `git`.

use std::collections::{BTreeSet, VecDeque};
use std::path::Path;
use std::sync::atomic::AtomicBool;

use core_log::hashes::{GitAlgo, git_oid};
use core_log::payload::GitObject;
use gix::ObjectId;
use gix::objs::Kind;
use gix::prelude::Write as _;
use gix::refs::Target;
use gix::refs::transaction::{Change, LogChange, PreviousValue, RefEdit, RefLog};

use crate::git::{GitBackend, GitError, Head, NodeGitConfig, RepoRefs};

/// The gix implementation of [`GitBackend`].
#[derive(Clone, Copy, Debug, Default)]
pub struct Gitoxide;

fn op(error: impl std::fmt::Display) -> GitError {
    GitError::Operation(error.to_string())
}

fn open(dir: &Path) -> Result<gix::Repository, GitError> {
    gix::open(dir).map_err(|error| GitError::Repo {
        path: dir.display().to_string(),
        message: error.to_string(),
    })
}

fn oid(bytes: &[u8]) -> Result<ObjectId, GitError> {
    ObjectId::try_from(bytes).map_err(op)
}

const fn kind_code(kind: Kind) -> u8 {
    match kind {
        Kind::Commit => 1,
        Kind::Tree => 2,
        Kind::Blob => 3,
        Kind::Tag => 4,
    }
}

fn kind_of(code: u8) -> Result<Kind, GitError> {
    Ok(match code {
        1 => Kind::Commit,
        2 => Kind::Tree,
        3 => Kind::Blob,
        4 => Kind::Tag,
        other => return Err(GitError::Operation(format!("object type {other}"))),
    })
}

fn algo_of(repo: &gix::Repository) -> Result<GitAlgo, GitError> {
    match repo.object_hash() {
        gix::hash::Kind::Sha1 => Ok(GitAlgo::Sha1),
        #[allow(unreachable_patterns)]
        other => Err(GitError::UnsupportedHash(format!("{other:?}"))),
    }
}

impl GitBackend for Gitoxide {
    fn algo(&self, dir: &Path) -> Result<GitAlgo, GitError> {
        algo_of(&open(dir)?)
    }

    fn refs(&self, dir: &Path) -> Result<RepoRefs, GitError> {
        let repo = open(dir)?;
        let head =
            repo.refs
                .try_find("HEAD")
                .map_err(op)?
                .map(|reference| match reference.target {
                    Target::Symbolic(name) => Head::Symbolic(name.as_bstr().to_string()),
                    Target::Object(id) => Head::Detached(id.as_bytes().to_vec()),
                });
        let mut refs = Vec::new();
        let platform = repo.refs.iter().map_err(op)?;
        for reference in platform.all().map_err(op)? {
            let reference = reference.map_err(op)?;
            let name = reference.name.as_bstr().to_string();
            if !name.starts_with("refs/") {
                continue;
            }
            if let Target::Object(id) = reference.target {
                refs.push((name, id.as_bytes().to_vec()));
            }
        }
        refs.sort();
        Ok(RepoRefs { head, refs })
    }

    fn objects_since(
        &self,
        dir: &Path,
        tips: &[Vec<u8>],
        known: &dyn Fn(&[u8]) -> bool,
    ) -> Result<Vec<GitObject>, GitError> {
        let repo = open(dir)?;
        let mut seen: BTreeSet<ObjectId> = BTreeSet::new();
        let mut queue: VecDeque<ObjectId> = VecDeque::new();
        for tip in tips {
            queue.push_back(oid(tip)?);
        }
        let mut out = Vec::new();
        while let Some(id) = queue.pop_front() {
            if !seen.insert(id) || known(id.as_bytes()) {
                continue;
            }
            let object = repo.find_object(id).map_err(op)?;
            let kind = object.kind;
            let data = object.data.clone();
            match kind {
                Kind::Commit => {
                    let commit = object.into_commit();
                    queue.push_back(commit.tree_id().map_err(op)?.detach());
                    queue.extend(commit.parent_ids().map(gix::Id::detach));
                }
                Kind::Tree => {
                    let tree = object.into_tree();
                    for entry in tree.iter() {
                        let entry = entry.map_err(op)?;
                        // Gitlinks (submodules) name commits of other repositories.
                        if !entry.mode().is_commit() {
                            queue.push_back(entry.oid().to_owned());
                        }
                    }
                }
                Kind::Tag => {
                    let tag = object.into_tag();
                    queue.push_back(tag.target_id().map_err(op)?.detach());
                }
                Kind::Blob => {}
            }
            out.push(GitObject {
                oid: id.as_bytes().to_vec(),
                kind: kind_code(kind),
                content: data,
            });
        }
        Ok(out)
    }

    fn init(&self, dir: &Path, config: &NodeGitConfig) -> Result<(), GitError> {
        std::fs::create_dir_all(dir).map_err(op)?;
        let repo = gix::init(dir).map_err(|error| GitError::Repo {
            path: dir.display().to_string(),
            message: error.to_string(),
        })?;
        let config_path = repo.git_dir().join("config");
        let mut file = gix::config::File::from_path_no_includes(
            config_path.clone(),
            gix::config::Source::Local,
        )
        .map_err(op)?;
        file.set_raw_value_by("core", None, "autocrlf", "false")
            .map_err(op)?;
        file.set_raw_value_by("core", None, "logAllRefUpdates", "false")
            .map_err(op)?;
        if let Some(hooks) = &config.hooks_path {
            let hooks = hooks.to_string_lossy().replace('\\', "/");
            file.set_raw_value_by("core", None, "hooksPath", hooks.as_str())
                .map_err(op)?;
        }
        let mut bytes = Vec::new();
        file.write_to(&mut bytes).map_err(op)?;
        std::fs::write(&config_path, bytes).map_err(op)?;
        Ok(())
    }

    fn write_objects(&self, dir: &Path, objects: &[GitObject]) -> Result<(), GitError> {
        let repo = open(dir)?;
        let algo = algo_of(&repo)?;
        for object in objects {
            let kind = kind_of(object.kind)?;
            let expected = git_oid(algo, &kind.to_string(), &object.content);
            if expected != object.oid {
                return Err(GitError::Operation(format!(
                    "object id {} does not match its content",
                    hex::encode(&object.oid)
                )));
            }
            let written = repo.objects.write_buf(kind, &object.content).map_err(op)?;
            if written.as_bytes() != object.oid.as_slice() {
                return Err(GitError::Operation("written object id differs".into()));
            }
        }
        Ok(())
    }

    fn set_refs(
        &self,
        dir: &Path,
        refs: &[(String, Vec<u8>)],
        head: Option<&Head>,
    ) -> Result<(), GitError> {
        let repo = open(dir)?;
        let log = LogChange {
            mode: RefLog::AndReference,
            force_create_reflog: false,
            message: "baken materialize".into(),
        };
        let mut edits = Vec::new();
        for (name, id) in refs {
            edits.push(RefEdit {
                change: Change::Update {
                    log: log.clone(),
                    expected: PreviousValue::Any,
                    new: Target::Object(oid(id)?),
                },
                name: name.as_str().try_into().map_err(op)?,
                deref: false,
            });
        }
        if let Some(head) = head {
            let new = match head {
                Head::Symbolic(target) => Target::Symbolic(target.as_str().try_into().map_err(op)?),
                Head::Detached(id) => Target::Object(oid(id)?),
            };
            edits.push(RefEdit {
                change: Change::Update {
                    log,
                    expected: PreviousValue::Any,
                    new,
                },
                name: "HEAD".try_into().map_err(op)?,
                deref: false,
            });
        }
        repo.edit_references(edits).map_err(op)?;
        Ok(())
    }

    fn checkout_head(&self, dir: &Path) -> Result<(), GitError> {
        let repo = open(dir)?;
        let mut head = repo.head().map_err(op)?;
        if head.is_unborn() {
            return Ok(());
        }
        let tree = head
            .peel_to_commit()
            .map_err(op)?
            .tree_id()
            .map_err(op)?
            .detach();
        let workdir = repo
            .workdir()
            .ok_or_else(|| GitError::Operation("bare repository".into()))?
            .to_path_buf();
        let mut index = repo.index_from_tree(&tree).map_err(op)?;
        let mut options = repo
            .checkout_options(gix::worktree::stack::state::attributes::Source::IdMapping)
            .map_err(op)?;
        options.destination_is_initially_empty = false;
        options.overwrite_existing = true;
        let objects = repo.objects.clone().into_arc().map_err(op)?;
        let interrupt = AtomicBool::new(false);
        gix::worktree::state::checkout(
            &mut index,
            workdir,
            objects,
            &gix::progress::Discard,
            &gix::progress::Discard,
            &interrupt,
            options,
        )
        .map_err(op)?;
        index
            .write(
                gix::index::write::Options::default(),
                #[allow(clippy::default_trait_access)]
                Default::default(),
            )
            .map_err(op)?;
        Ok(())
    }
}
