//! Payload kinds (C1 §3.4) and their BCBOR encodings.
//!
//! Encoding is canonical by construction. Decoding rejects non-canonical bytes (BCBOR) and broken
//! schema rules, and ignores unknown keys: a verifier hashes the bytes as received and never
//! re-encodes them, so unknown keys survive relaying untouched.

use core_proto::bcbor::{self, Value};
use core_proto::{AgentId, ConversationId, Epoch, NodeId};
use core_store::{DivergenceId, EntryHash};

use crate::allowlist::is_well_formed;
use crate::hashes::{GitAlgo, git_oid, sha256};
use crate::header::FLAG_COMPACTABLE;
use crate::value::{
    Fields, Pos, ShapeError, as_array, as_bytes, as_bytes32, as_text, as_uint, boolean, bytes,
    check_ascending, epoch_value, map, pos_value, text, uint,
};

/// Payload kind numbers.
pub mod kind {
    /// The first entry of every segment.
    pub const EPOCH_OPEN: u16 = 0x0001;
    /// The last entry of a segment the holder hands on or releases.
    pub const EPOCH_SEAL: u16 = 0x0002;
    /// A manifest of the materialized state.
    pub const CHECKPOINT: u16 = 0x0003;
    /// The holder's record of a divergence.
    pub const DIVERGENCE: u16 = 0x0004;
    /// Whole-file write.
    pub const FILE_PUT: u16 = 0x0100;
    /// Offset-checked append.
    pub const FILE_APPEND: u16 = 0x0101;
    /// File removal.
    pub const FILE_DELETE: u16 = 0x0102;
    /// Raw memfs git objects.
    pub const GIT_OBJECTS: u16 = 0x0200;
    /// A memfs ref update.
    pub const GIT_REF: u16 = 0x0201;
    /// A feed journal entry.
    pub const FEED_APPEND: u16 = 0x0300;
    /// Tool-call lifecycle.
    pub const ACTION_STATUS: u16 = 0x0400;
    /// Kinds at or above this may be skipped by a materializer.
    pub const IGNORABLE_FROM: u16 = 0x8000;
}

/// Why an `epoch_open` was written.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Reason {
    /// The agent's first segment.
    Genesis,
    /// A planned handoff after a seal.
    Move,
    /// An automatic claim by policy.
    Failover,
    /// A manual claim.
    Takeover,
    /// An admin claim without a certificate.
    TakeoverForced,
    /// The holder's key rotated.
    Rekey,
}

impl Reason {
    /// The wire number.
    #[must_use]
    pub const fn code(self) -> u64 {
        match self {
            Self::Genesis => 0,
            Self::Move => 1,
            Self::Failover => 2,
            Self::Takeover => 3,
            Self::TakeoverForced => 4,
            Self::Rekey => 5,
        }
    }

    /// Parses the wire number.
    #[must_use]
    pub const fn from_code(code: u64) -> Option<Self> {
        Some(match code {
            0 => Self::Genesis,
            1 => Self::Move,
            2 => Self::Failover,
            3 => Self::Takeover,
            4 => Self::TakeoverForced,
            5 => Self::Rekey,
            _ => return None,
        })
    }
}

/// A tool call that may or may not have taken effect.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Action {
    /// The idempotency key.
    pub idempotency_key: String,
    /// The tool call.
    pub tool_call_id: String,
    /// The conversation.
    pub conversation_id: ConversationId,
    /// Where it started.
    pub started_at: Pos,
}

/// `epoch_open` (C1 §3.4).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EpochOpen {
    /// Required at genesis.
    pub agent_id: Option<AgentId>,
    /// Why.
    pub reason: Reason,
    /// The epoch claimed over (absent at genesis).
    pub prior_epoch: Option<Epoch>,
    /// Vote envelopes, verbatim (judged by placement).
    pub votes: Vec<Value>,
    /// The store format of the claimant.
    pub store_format: u64,
    /// I6 runtime parity string.
    pub runtime_parity: Option<String>,
    /// Failover: the old holder's highest position the claimant knew of.
    pub prior_head_seen: Option<Pos>,
    /// Actions in doubt at the fork.
    pub uncertain: Vec<Action>,
}

/// `epoch_seal`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EpochSeal {
    /// The next epoch, or `None` to release the agent.
    pub next_epoch: Option<Epoch>,
    /// The checkpoint the target verified.
    pub checkpoint: Pos,
}

/// One manifest line: path, SHA-256, length.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct ManifestEntry {
    /// The relative path.
    pub path: String,
    /// SHA-256 of the file.
    pub sha256: [u8; 32],
    /// Length in bytes.
    pub len: u64,
}

/// One ref: repo, ref name, object id.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct RefEntry {
    /// The repo (`"memfs"`).
    pub repo: String,
    /// The ref name.
    pub name: String,
    /// The object id.
    pub oid: Vec<u8>,
}

/// `checkpoint`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Checkpoint {
    /// Every replicated file, sorted by path.
    pub manifest: Vec<ManifestEntry>,
    /// Every ref, sorted.
    pub refs: Vec<RefEntry>,
    /// Feed heads per conversation, sorted.
    pub feed_heads: Vec<(ConversationId, u64)>,
    /// The store format.
    pub store_format: u64,
    /// I6 runtime parity.
    pub runtime_parity: String,
    /// H1 lint findings.
    pub h1_violations: Vec<String>,
}

/// A conversation touched by a divergent tail.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DivergentConversation {
    /// The conversation.
    pub conversation_id: ConversationId,
    /// The last message both lines share, if any.
    pub last_common_message_id: Option<String>,
    /// `local-conv-<catalog node uuid>`.
    pub branch_conversation_id: ConversationId,
    /// C2's divergence node id.
    pub catalog_node_id: [u8; 16],
    /// The owning agent (the tree agent or a subagent).
    pub agent_id: AgentId,
}

/// `divergence` (C1 §3.4, §3.8).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DivergencePayload {
    /// `derive_key("divergence id", ...)`.
    pub divergence_id: DivergenceId,
    /// The stranded epoch.
    pub losing_epoch: Epoch,
    /// The last common entry.
    pub fork: Pos,
    /// Newest known entry of the losing tail.
    pub tail_head: Pos,
    /// Conversations the tail touched.
    pub conversations: Vec<DivergentConversation>,
    /// Memfs divergence branches.
    pub memfs_branches: Vec<RefEntry>,
    /// Actions in doubt.
    pub uncertain: Vec<Action>,
    /// Same-epoch equivocation.
    pub equivocation: bool,
    /// The losing claimant was revoked.
    pub from_revoked: bool,
    /// The agent.
    pub agent_id: AgentId,
    /// The epoch holding the fork entry.
    pub fork_epoch: Epoch,
    /// The winning epoch.
    pub winning_epoch: Epoch,
    /// The two entry hashes at `fork.seq + 1`, ascending.
    pub competing: [EntryHash; 2],
    /// `losing_epoch.claimant`.
    pub stranded_holder: NodeId,
}

/// One raw git object.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GitObject {
    /// Its id; checked against the content.
    pub oid: Vec<u8>,
    /// 1 commit, 2 tree, 3 blob, 4 tag.
    pub kind: u8,
    /// Raw, uncompressed content.
    pub content: Vec<u8>,
}

impl GitObject {
    /// Git's type name for `kind`.
    #[must_use]
    pub const fn type_name(kind: u8) -> Option<&'static str> {
        match kind {
            1 => Some("commit"),
            2 => Some("tree"),
            3 => Some("blob"),
            4 => Some("tag"),
            _ => None,
        }
    }
}

/// Tool-call state.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ActionState {
    /// Started.
    Started,
    /// Finished.
    Finished,
    /// Failed.
    Failed,
    /// The user acknowledged it as uncertain.
    AcknowledgedUncertain,
}

impl ActionState {
    /// Wire number.
    #[must_use]
    pub const fn code(self) -> u64 {
        match self {
            Self::Started => 0,
            Self::Finished => 1,
            Self::Failed => 2,
            Self::AcknowledgedUncertain => 3,
        }
    }

    /// Parses the wire number.
    #[must_use]
    pub const fn from_code(code: u64) -> Option<Self> {
        Some(match code {
            0 => Self::Started,
            1 => Self::Finished,
            2 => Self::Failed,
            3 => Self::AcknowledgedUncertain,
            _ => return None,
        })
    }
}

/// A decoded payload.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Payload {
    /// `0x0001`.
    EpochOpen(EpochOpen),
    /// `0x0002`.
    EpochSeal(EpochSeal),
    /// `0x0003`.
    Checkpoint(Checkpoint),
    /// `0x0004`.
    Divergence(Box<DivergencePayload>),
    /// `0x0100`.
    FilePut {
        /// Path.
        path: String,
        /// SHA-256 of `content`.
        sha256: [u8; 32],
        /// The whole file.
        content: Vec<u8>,
        /// Executable bit.
        exec: bool,
    },
    /// `0x0101`.
    FileAppend {
        /// Path.
        path: String,
        /// File length before the append.
        offset: u64,
        /// Appended bytes.
        bytes: Vec<u8>,
    },
    /// `0x0102`.
    FileDelete {
        /// Path.
        path: String,
    },
    /// `0x0200`.
    GitObjects {
        /// Repo.
        repo: String,
        /// Hash algorithm.
        algo: GitAlgo,
        /// Objects.
        objects: Vec<GitObject>,
    },
    /// `0x0201`.
    GitRef {
        /// Repo.
        repo: String,
        /// Ref name.
        name: String,
        /// Expected current oid (`None` = absent).
        old_oid: Option<Vec<u8>>,
        /// New oid (`None` = delete).
        new_oid: Option<Vec<u8>>,
        /// Symbolic target (for `HEAD`), in place of the oids.
        symref: Option<String>,
    },
    /// `0x0300`.
    FeedAppend {
        /// Conversation.
        conversation_id: ConversationId,
        /// Feed seq.
        feed_seq: u64,
        /// Turn id.
        turn: String,
        /// Feed entry kind (`turn_open`, `stream_delta`, `turn_closed`, ...).
        entry_kind: String,
        /// The feed v1 entry JSON, verbatim.
        entry: Vec<u8>,
        /// A cumulative snapshot this one supersedes.
        replaces_feed_seq: Option<u64>,
    },
    /// `0x0400`.
    ActionStatus {
        /// Idempotency key.
        idempotency_key: String,
        /// Tool call.
        tool_call_id: String,
        /// Conversation.
        conversation_id: ConversationId,
        /// State.
        state: ActionState,
    },
    /// A kind this build does not know: stored and relayed verbatim.
    Unknown {
        /// The kind.
        kind: u16,
        /// The bytes.
        bytes: Vec<u8>,
    },
}

impl Payload {
    /// The kind number.
    #[must_use]
    pub const fn kind(&self) -> u16 {
        match self {
            Self::EpochOpen(_) => kind::EPOCH_OPEN,
            Self::EpochSeal(_) => kind::EPOCH_SEAL,
            Self::Checkpoint(_) => kind::CHECKPOINT,
            Self::Divergence(_) => kind::DIVERGENCE,
            Self::FilePut { .. } => kind::FILE_PUT,
            Self::FileAppend { .. } => kind::FILE_APPEND,
            Self::FileDelete { .. } => kind::FILE_DELETE,
            Self::GitObjects { .. } => kind::GIT_OBJECTS,
            Self::GitRef { .. } => kind::GIT_REF,
            Self::FeedAppend { .. } => kind::FEED_APPEND,
            Self::ActionStatus { .. } => kind::ACTION_STATUS,
            Self::Unknown { kind, .. } => *kind,
        }
    }

    /// The header flags the holder writes for this kind: `compactable` for `file_put` and
    /// `feed_append`, nothing otherwise.
    #[must_use]
    pub const fn default_flags(&self) -> u16 {
        match self {
            Self::FilePut { .. } | Self::FeedAppend { .. } => FLAG_COMPACTABLE,
            _ => 0,
        }
    }

    /// A `file_put` with its SHA-256 computed.
    #[must_use]
    pub fn file_put(path: &str, content: Vec<u8>, exec: bool) -> Self {
        Self::FilePut {
            path: path.to_owned(),
            sha256: sha256(&content),
            content,
            exec,
        }
    }

    /// The canonical BCBOR bytes. An [`Payload::Unknown`] returns its bytes verbatim.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        match self {
            Self::Unknown { bytes, .. } => bytes.clone(),
            other => bcbor::encode(&other.to_value()),
        }
    }

    #[allow(clippy::too_many_lines)]
    fn to_value(&self) -> Value {
        match self {
            Self::EpochOpen(open) => map([
                (0, open.agent_id.as_ref().and_then(|a| text(a.as_str()))),
                (1, uint(open.reason.code())),
                (2, open.prior_epoch.as_ref().map(epoch_value)),
                (
                    3,
                    (!open.votes.is_empty()).then(|| Value::Array(open.votes.clone())),
                ),
                (4, uint(open.store_format)),
                (5, open.runtime_parity.as_deref().and_then(text)),
                (6, open.prior_head_seen.as_ref().map(pos_value)),
                (7, actions_value(&open.uncertain)),
            ]),
            Self::EpochSeal(seal) => Value::Map(
                [
                    (0, seal.next_epoch.as_ref().map_or(Value::Null, epoch_value)),
                    (1, pos_value(&seal.checkpoint)),
                ]
                .into_iter()
                .collect(),
            ),
            Self::Checkpoint(cp) => map([
                (
                    0,
                    Some(Value::Array(
                        cp.manifest
                            .iter()
                            .map(|m| {
                                Value::Array(vec![
                                    Value::Text(m.path.clone()),
                                    Value::Bytes(m.sha256.to_vec()),
                                    Value::Uint(m.len),
                                ])
                            })
                            .collect(),
                    )),
                ),
                (1, Some(refs_value(&cp.refs))),
                (
                    2,
                    Some(Value::Array(
                        cp.feed_heads
                            .iter()
                            .map(|(c, s)| {
                                Value::Array(vec![Value::Text(c.as_str().into()), Value::Uint(*s)])
                            })
                            .collect(),
                    )),
                ),
                (3, uint(cp.store_format)),
                (4, text(&cp.runtime_parity)),
                (
                    5,
                    Some(Value::Array(
                        cp.h1_violations
                            .iter()
                            .map(|v| Value::Text(v.clone()))
                            .collect(),
                    )),
                ),
            ]),
            Self::Divergence(d) => map([
                (0, bytes(d.divergence_id.as_bytes())),
                (1, Some(epoch_value(&d.losing_epoch))),
                (2, Some(pos_value(&d.fork))),
                (3, Some(pos_value(&d.tail_head))),
                (
                    4,
                    Some(Value::Array(
                        d.conversations
                            .iter()
                            .map(|c| {
                                map([
                                    (0, text(c.conversation_id.as_str())),
                                    (
                                        1,
                                        Some(
                                            c.last_common_message_id
                                                .as_ref()
                                                .map_or(Value::Null, |m| Value::Text(m.clone())),
                                        ),
                                    ),
                                    (2, text(c.branch_conversation_id.as_str())),
                                    (3, bytes(&c.catalog_node_id)),
                                    (4, text(c.agent_id.as_str())),
                                ])
                            })
                            .collect(),
                    )),
                ),
                (5, Some(refs_value(&d.memfs_branches))),
                (
                    6,
                    Some(actions_value(&d.uncertain).unwrap_or(Value::Array(vec![]))),
                ),
                (7, boolean(d.equivocation)),
                (8, boolean(d.from_revoked)),
                (9, text(d.agent_id.as_str())),
                (10, Some(epoch_value(&d.fork_epoch))),
                (11, Some(epoch_value(&d.winning_epoch))),
                (
                    12,
                    Some(Value::Array(vec![
                        Value::Bytes(d.competing[0].as_bytes().to_vec()),
                        Value::Bytes(d.competing[1].as_bytes().to_vec()),
                    ])),
                ),
                (13, bytes(d.stranded_holder.as_bytes())),
            ]),
            Self::FilePut {
                path,
                sha256,
                content,
                exec,
            } => map([
                (0, text(path)),
                (1, bytes(sha256)),
                (2, bytes(content)),
                (3, boolean(*exec)),
            ]),
            Self::FileAppend {
                path,
                offset,
                bytes: appended,
            } => map([(0, text(path)), (1, uint(*offset)), (2, bytes(appended))]),
            Self::FileDelete { path } => map([(0, text(path))]),
            Self::GitObjects {
                repo,
                algo,
                objects,
            } => map([
                (0, text(repo)),
                (1, text(algo.name())),
                (
                    2,
                    Some(Value::Array(
                        objects
                            .iter()
                            .map(|o| {
                                Value::Array(vec![
                                    Value::Bytes(o.oid.clone()),
                                    Value::Uint(u64::from(o.kind)),
                                    Value::Bytes(o.content.clone()),
                                ])
                            })
                            .collect(),
                    )),
                ),
            ]),
            Self::GitRef {
                repo,
                name,
                old_oid,
                new_oid,
                symref,
            } => {
                let oid = |o: &Option<Vec<u8>>| {
                    Some(o.as_ref().map_or(Value::Null, |o| Value::Bytes(o.clone())))
                };
                if let Some(target) = symref {
                    map([(0, text(repo)), (1, text(name)), (4, text(target))])
                } else {
                    map([
                        (0, text(repo)),
                        (1, text(name)),
                        (2, oid(old_oid)),
                        (3, oid(new_oid)),
                    ])
                }
            }
            Self::FeedAppend {
                conversation_id,
                feed_seq,
                turn,
                entry_kind,
                entry,
                replaces_feed_seq,
            } => map([
                (0, text(conversation_id.as_str())),
                (1, uint(*feed_seq)),
                (2, text(turn)),
                (3, text(entry_kind)),
                (4, bytes(entry)),
                (5, replaces_feed_seq.and_then(uint)),
            ]),
            Self::ActionStatus {
                idempotency_key,
                tool_call_id,
                conversation_id,
                state,
            } => map([
                (0, text(idempotency_key)),
                (1, text(tool_call_id)),
                (2, text(conversation_id.as_str())),
                (3, uint(state.code())),
            ]),
            Self::Unknown { .. } => Value::Null,
        }
    }

    /// Decodes a payload of `kind`. Unknown kinds decode to [`Payload::Unknown`].
    ///
    /// # Errors
    ///
    /// [`ShapeError`] for non-canonical bytes or a broken schema rule.
    pub fn decode(kind: u16, bytes: &[u8]) -> Result<Self, ShapeError> {
        let known = matches!(
            kind,
            kind::EPOCH_OPEN
                | kind::EPOCH_SEAL
                | kind::CHECKPOINT
                | kind::DIVERGENCE
                | kind::FILE_PUT
                | kind::FILE_APPEND
                | kind::FILE_DELETE
                | kind::GIT_OBJECTS
                | kind::GIT_REF
                | kind::FEED_APPEND
                | kind::ACTION_STATUS
        );
        if !known {
            return Ok(Self::Unknown {
                kind,
                bytes: bytes.to_vec(),
            });
        }
        let value = bcbor::decode(bytes)?;
        let f = Fields::of(&value)?;
        Ok(match kind {
            kind::EPOCH_OPEN => Self::EpochOpen(EpochOpen {
                agent_id: f.opt_text(0)?.map(AgentId::new),
                reason: Reason::from_code(f.uint(1)?)
                    .ok_or_else(|| ShapeError::Invalid("unknown reason".into()))?,
                prior_epoch: f.opt_epoch(2)?,
                votes: f.opt_array(3)?.to_vec(),
                store_format: f.uint(4)?,
                runtime_parity: f.opt_text(5)?,
                prior_head_seen: f.opt_pos(6)?,
                uncertain: actions(f.opt_array(7)?)?,
            }),
            kind::EPOCH_SEAL => {
                if !f.0.contains_key(&0) {
                    return Err(ShapeError::Missing(0));
                }
                Self::EpochSeal(EpochSeal {
                    next_epoch: f.opt_epoch(0)?,
                    checkpoint: f.pos(1)?,
                })
            }
            kind::CHECKPOINT => Self::Checkpoint(checkpoint(&f)?),
            kind::DIVERGENCE => Self::Divergence(Box::new(divergence(&f)?)),
            kind::FILE_PUT => {
                let path = path(&f)?;
                let digest = f.bytes32(1)?;
                let content = f.bytes(2)?;
                if sha256(&content) != digest {
                    return Err(ShapeError::Invalid("file_put sha256 mismatch".into()));
                }
                Self::FilePut {
                    path,
                    sha256: digest,
                    content,
                    exec: f.opt_bool(3)?,
                }
            }
            kind::FILE_APPEND => Self::FileAppend {
                path: path(&f)?,
                offset: f.uint(1)?,
                bytes: f.bytes(2)?,
            },
            kind::FILE_DELETE => Self::FileDelete { path: path(&f)? },
            kind::GIT_OBJECTS => git_objects(&f)?,
            kind::GIT_REF => {
                let repo = f.text(0)?;
                let name = f.text(1)?;
                let symref = f.opt_text(4)?;
                Self::GitRef {
                    repo,
                    name,
                    old_oid: f.opt_bytes(2)?,
                    new_oid: f.opt_bytes(3)?,
                    symref,
                }
            }
            kind::FEED_APPEND => Self::FeedAppend {
                conversation_id: ConversationId::new(f.text(0)?),
                feed_seq: f.uint(1)?,
                turn: f.text(2)?,
                entry_kind: f.text(3)?,
                entry: f.bytes(4)?,
                replaces_feed_seq: f.opt_uint(5)?,
            },
            _ => Self::ActionStatus {
                idempotency_key: f.text(0)?,
                tool_call_id: f.text(1)?,
                conversation_id: ConversationId::new(f.text(2)?),
                state: ActionState::from_code(f.uint(3)?)
                    .ok_or_else(|| ShapeError::Invalid("unknown action state".into()))?,
            },
        })
    }
}

fn path(f: &Fields<'_>) -> Result<String, ShapeError> {
    let path = f.text(0)?;
    if is_well_formed(&path) {
        Ok(path)
    } else {
        Err(ShapeError::Invalid(format!("malformed path {path:?}")))
    }
}

fn refs_value(refs: &[RefEntry]) -> Value {
    Value::Array(
        refs.iter()
            .map(|r| {
                Value::Array(vec![
                    Value::Text(r.repo.clone()),
                    Value::Text(r.name.clone()),
                    Value::Bytes(r.oid.clone()),
                ])
            })
            .collect(),
    )
}

fn refs(items: &[Value], key: u64) -> Result<Vec<RefEntry>, ShapeError> {
    let refs = items
        .iter()
        .map(|item| match as_array(item, key)? {
            [repo, name, oid] => Ok(RefEntry {
                repo: as_text(repo, key)?,
                name: as_text(name, key)?,
                oid: as_bytes(oid, key)?.to_vec(),
            }),
            _ => Err(ShapeError::Type(key)),
        })
        .collect::<Result<Vec<_>, _>>()?;
    check_ascending(&refs, Clone::clone, "refs")?;
    Ok(refs)
}

fn actions_value(actions: &[Action]) -> Option<Value> {
    (!actions.is_empty()).then(|| {
        Value::Array(
            actions
                .iter()
                .map(|a| {
                    map([
                        (0, text(&a.idempotency_key)),
                        (1, text(&a.tool_call_id)),
                        (2, text(a.conversation_id.as_str())),
                        (3, Some(pos_value(&a.started_at))),
                    ])
                })
                .collect(),
        )
    })
}

fn actions(items: &[Value]) -> Result<Vec<Action>, ShapeError> {
    items
        .iter()
        .map(|item| {
            let f = Fields::of(item)?;
            Ok(Action {
                idempotency_key: f.text(0)?,
                tool_call_id: f.text(1)?,
                conversation_id: ConversationId::new(f.text(2)?),
                started_at: f.pos(3)?,
            })
        })
        .collect()
}

fn checkpoint(f: &Fields<'_>) -> Result<Checkpoint, ShapeError> {
    let manifest = f
        .array(0)?
        .iter()
        .map(|item| match as_array(item, 0)? {
            [path, digest, len] => Ok(ManifestEntry {
                path: as_text(path, 0)?,
                sha256: as_bytes32(digest, 0)?,
                len: as_uint(len, 0)?,
            }),
            _ => Err(ShapeError::Type(0)),
        })
        .collect::<Result<Vec<_>, _>>()?;
    check_ascending(&manifest, |m| m.path.clone(), "manifest")?;
    let feed_heads = f
        .array(2)?
        .iter()
        .map(|item| match as_array(item, 2)? {
            [conversation, seq] => Ok((
                ConversationId::new(as_text(conversation, 2)?),
                as_uint(seq, 2)?,
            )),
            _ => Err(ShapeError::Type(2)),
        })
        .collect::<Result<Vec<_>, _>>()?;
    check_ascending(&feed_heads, |(c, _)| c.clone(), "feed_heads")?;
    Ok(Checkpoint {
        manifest,
        refs: refs(f.array(1)?, 1)?,
        feed_heads,
        store_format: f.uint(3)?,
        runtime_parity: f.text(4)?,
        h1_violations: f
            .array(5)?
            .iter()
            .map(|v| as_text(v, 5))
            .collect::<Result<_, _>>()?,
    })
}

fn divergence(f: &Fields<'_>) -> Result<DivergencePayload, ShapeError> {
    let conversations = f
        .array(4)?
        .iter()
        .map(|item| {
            let c = Fields::of(item)?;
            Ok(DivergentConversation {
                conversation_id: ConversationId::new(c.text(0)?),
                last_common_message_id: c.opt_text(1)?,
                branch_conversation_id: ConversationId::new(c.text(2)?),
                catalog_node_id: <[u8; 16]>::try_from(c.bytes(3)?.as_slice())
                    .map_err(|_| ShapeError::Type(3))?,
                agent_id: AgentId::new(c.text(4)?),
            })
        })
        .collect::<Result<Vec<_>, ShapeError>>()?;
    let competing = match f.array(12)? {
        [a, b] => [
            EntryHash::from_bytes(as_bytes32(a, 12)?),
            EntryHash::from_bytes(as_bytes32(b, 12)?),
        ],
        _ => return Err(ShapeError::Type(12)),
    };
    if competing[0] >= competing[1] {
        return Err(ShapeError::Invalid(
            "competing hashes must be strictly ascending".into(),
        ));
    }
    let losing_epoch = f.epoch(1)?;
    let stranded_holder = NodeId::from_bytes(f.bytes32(13)?);
    if stranded_holder != losing_epoch.claimant {
        return Err(ShapeError::Invalid(
            "stranded_holder must be the losing claimant".into(),
        ));
    }
    Ok(DivergencePayload {
        divergence_id: DivergenceId::from_bytes(f.bytes32(0)?),
        losing_epoch,
        fork: f.pos(2)?,
        tail_head: f.pos(3)?,
        conversations,
        memfs_branches: refs(f.array(5)?, 5)?,
        uncertain: actions(f.array(6)?)?,
        equivocation: f.boolean(7)?,
        from_revoked: f.boolean(8)?,
        agent_id: AgentId::new(f.text(9)?),
        fork_epoch: f.epoch(10)?,
        winning_epoch: f.epoch(11)?,
        competing,
        stranded_holder,
    })
}

fn git_objects(f: &Fields<'_>) -> Result<Payload, ShapeError> {
    let repo = f.text(0)?;
    let algo = GitAlgo::from_name(&f.text(1)?)
        .ok_or_else(|| ShapeError::Invalid("unknown git algo".into()))?;
    let objects = f
        .array(2)?
        .iter()
        .map(|item| match as_array(item, 2)? {
            [oid, kind, content] => {
                let kind = u8::try_from(as_uint(kind, 2)?).map_err(|_| ShapeError::Type(2))?;
                let type_name = GitObject::type_name(kind)
                    .ok_or_else(|| ShapeError::Invalid("unknown git object type".into()))?;
                let oid = as_bytes(oid, 2)?.to_vec();
                let content = as_bytes(content, 2)?.to_vec();
                if git_oid(algo, type_name, &content) != oid {
                    return Err(ShapeError::Invalid("git object id mismatch".into()));
                }
                Ok(GitObject { oid, kind, content })
            }
            _ => Err(ShapeError::Type(2)),
        })
        .collect::<Result<Vec<_>, _>>()?;
    Ok(Payload::GitObjects {
        repo,
        algo,
        objects,
    })
}
