//! The catalog's value types, shared by the writers (which serialize them into new map objects)
//! and the typed views (which read them back). Field names are the exact catalog-v1 keys.

use serde::{Deserialize, Serialize};

/// Who asked for a write. Display only and unauthenticated (catalog-v1 §3.6).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActorRef {
    /// `user`, `agent` or `node`.
    pub t: ActorKind,
    /// The node that wrote the change.
    pub node_id: String,
    /// The authenticated endpoint of the shell that asked, or `host-local`.
    pub client_id: Option<String>,
    /// Set when an agent tool asked.
    pub agent_id: Option<String>,
}

impl ActorRef {
    /// A node acting on its own.
    #[must_use]
    pub fn node(node_id: impl Into<String>) -> Self {
        Self {
            t: ActorKind::Node,
            node_id: node_id.into(),
            client_id: None,
            agent_id: None,
        }
    }
}

/// The kind of an [`ActorRef`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ActorKind {
    /// A person, through a shell.
    User,
    /// An agent tool.
    Agent,
    /// The node itself.
    Node,
}

/// Where a child conversation leaves its parent: a letta-code message id and an R1 position.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForkPoint {
    /// The last message the child shares with the parent; `None` is "from the start".
    pub last_shared_message_id: Option<String>,
    /// The R1 epoch number of the last shared entry.
    pub log_epoch_n: Option<i64>,
    /// The R1 epoch claimant of the last shared entry.
    pub log_epoch_claimant: Option<String>,
    /// The R1 sequence number of the last shared entry.
    pub log_seq: Option<i64>,
}

/// A session-tree node kind. `Unknown` is any value from a later minor version.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum NodeKind {
    /// A conversation with no parent.
    Root,
    /// A plain fork.
    Fork,
    /// A fork that starts from an edited user message.
    Edit,
    /// A fork that re-sends a user message.
    Rerun,
    /// A side conversation summarised back into its parent.
    Aside,
    /// A subagent dispatch.
    Subagent,
    /// The stranded line of a placement divergence.
    Divergence,
    /// A kind this build does not know.
    #[serde(other)]
    Unknown,
}

impl NodeKind {
    /// The stored name.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Root => "root",
            Self::Fork => "fork",
            Self::Edit => "edit",
            Self::Rerun => "rerun",
            Self::Aside => "aside",
            Self::Subagent => "subagent",
            Self::Divergence => "divergence",
            Self::Unknown => "unknown",
        }
    }
}

/// A subagent's status. Only `status` and `endedAtMs` change after creation.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SubagentStatus {
    /// Still running.
    Running,
    /// Finished.
    Done,
    /// Failed.
    Failed,
    /// Cancelled.
    Cancelled,
    /// A value this build does not know.
    #[serde(other)]
    Unknown,
}

impl SubagentStatus {
    /// Whether the status is terminal.
    #[must_use]
    pub const fn is_terminal(self) -> bool {
        matches!(self, Self::Done | Self::Failed | Self::Cancelled)
    }

    /// The tie order between terminal values: `cancelled` > `failed` > `done`.
    #[must_use]
    pub const fn terminal_rank(self) -> u8 {
        match self {
            Self::Cancelled => 3,
            Self::Failed => 2,
            Self::Done => 1,
            Self::Running | Self::Unknown => 0,
        }
    }
}

/// The state of a divergence resolution.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ResolutionState {
    /// Nobody decided yet.
    Open,
    /// Hidden by a person.
    Dismissed,
    /// Kept as a branch.
    Kept,
    /// A value this build does not know.
    #[serde(other)]
    Unknown,
}

impl ResolutionState {
    /// The conflict rank: `kept` > `dismissed` > `open`.
    #[must_use]
    pub const fn rank(self) -> u8 {
        match self {
            Self::Kept => 3,
            Self::Dismissed => 2,
            Self::Open => 1,
            Self::Unknown => 0,
        }
    }
}

/// A divergence or memory-divergence resolution (mutable by any catalog writer).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Resolution {
    /// The state.
    pub state: ResolutionState,
    /// When it was decided.
    pub at_ms: Option<i64>,
    /// Who decided.
    pub by: Option<ActorRef>,
}

impl Resolution {
    /// An open resolution.
    #[must_use]
    pub const fn open() -> Self {
        Self {
            state: ResolutionState::Open,
            at_ms: None,
            by: None,
        }
    }
}

/// The immutable part of a divergence node's detail.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DivergenceDetail {
    /// The holder whose line was stranded.
    pub stranded_holder: String,
    /// The stranded epoch number.
    pub stranded_epoch_n: i64,
    /// The stranded epoch claimant.
    pub stranded_epoch_claimant: String,
    /// The winning epoch number.
    pub winning_epoch_n: i64,
    /// The winning epoch claimant.
    pub winning_epoch_claimant: String,
    /// The last message both lines share.
    pub last_common_message_id: Option<String>,
    /// The last R1 sequence number both lines share.
    pub last_common_log_seq: Option<i64>,
    /// When it was detected.
    pub detected_at_ms: i64,
    /// Which node detected it.
    pub detected_by: String,
}

/// The kind-specific detail of a node.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(untagged)]
pub enum Detail {
    /// `root` and `fork`: no keys.
    None {},
    /// `edit`.
    Edit {
        /// The user message whose edited copy starts the child.
        #[serde(rename = "replacedMessageId")]
        replaced_message_id: String,
    },
    /// `rerun`.
    Rerun {
        /// The user message re-sent in the child.
        #[serde(rename = "rerunMessageId")]
        rerun_message_id: String,
    },
    /// `aside`.
    Aside {
        /// The parent message the aside hangs off.
        #[serde(rename = "anchorMessageId")]
        anchor_message_id: Option<String>,
        /// When it was summarised back.
        #[serde(rename = "returnedAtMs")]
        returned_at_ms: Option<i64>,
        /// The summary message in the parent.
        #[serde(rename = "summaryMessageId")]
        summary_message_id: Option<String>,
    },
    /// `subagent`.
    Subagent {
        /// The dispatching tool call.
        #[serde(rename = "toolCallId")]
        tool_call_id: String,
        /// The dispatching message.
        #[serde(rename = "dispatchMessageId")]
        dispatch_message_id: Option<String>,
        /// The subagent type.
        #[serde(rename = "subagentType")]
        subagent_type: String,
        /// The status.
        status: SubagentStatus,
        /// When it started.
        #[serde(rename = "startedAtMs")]
        started_at_ms: i64,
        /// When it ended.
        #[serde(rename = "endedAtMs")]
        ended_at_ms: Option<i64>,
    },
    /// `divergence`: the immutable fields plus a mutable resolution, in one map.
    Divergence {
        /// The immutable fields, written once with the node.
        #[serde(flatten)]
        divergence: DivergenceDetail,
        /// The mutable resolution.
        resolution: Resolution,
    },
}

/// A new session-tree node, as its holder creates it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NewNode {
    /// The conversation id (equals the map key).
    pub id: String,
    /// The agent that owns the conversation (the doc's agent or a subagent agent).
    pub agent_id: String,
    /// The parent conversation; `None` only for `root`.
    pub parent_id: Option<String>,
    /// The kind.
    pub kind: NodeKind,
    /// Where it leaves the parent; `None` for `root`.
    pub fork_point: Option<ForkPoint>,
    /// Who asked.
    pub created_by: ActorRef,
    /// When.
    pub created_at_ms: i64,
    /// The projected title.
    pub title: Option<String>,
    /// `user`, `agent` or `auto`.
    pub title_source: Option<String>,
    /// Coarse last activity.
    pub last_activity_at_ms: Option<i64>,
    /// Soft delete.
    pub deleted_at_ms: Option<i64>,
    /// Kind-specific detail.
    pub detail: Detail,
}

/// A memory divergence record.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemoryDivergence {
    /// The id (UUIDv8 text), equal to the map key.
    pub id: String,
    /// The stranded epoch number.
    pub stranded_epoch_n: i64,
    /// The stranded epoch claimant.
    pub stranded_epoch_claimant: String,
    /// The memfs branch, `divergence/<n>-<claimant8>/<leaf>` (no `refs/heads/`).
    pub memfs_branch: String,
    /// When it was detected.
    pub detected_at_ms: i64,
    /// Which node detected it.
    pub detected_by: String,
    /// The resolution.
    pub resolution: Resolution,
}

/// A copy of C1's fenced placement: the claimant's signed `epoch_open` entry header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlacementCopy {
    /// The holder (the claimant of the epoch).
    pub holder: String,
    /// The epoch number.
    pub epoch_n: i64,
    /// The epoch claimant.
    pub epoch_claimant: String,
    /// `active`, `handoff` or `released`.
    pub state: String,
    /// The previous holder.
    pub prev_holder: Option<String>,
    /// The 168-byte `epoch_open` entry header.
    pub record: Vec<u8>,
    /// The claimant's 64-byte signature over the entry hash.
    pub sig: Vec<u8>,
    /// The signer (the claimant).
    pub signer: String,
    /// When this copy was written.
    pub copied_at_ms: i64,
    /// Which node wrote it.
    pub copied_by: String,
}

/// An agent directory entry (fields edited in place).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DirEntry {
    /// The agent.
    pub agent_id: String,
    /// `standard` or `subagent`.
    pub kind: String,
    /// The projected name.
    pub name: String,
    /// The projected description.
    pub description: Option<String>,
    /// The avatar token.
    pub avatar_token: Option<String>,
    /// The agent doc URL (null for subagents).
    pub doc_url: Option<String>,
    /// The tree agent of a subagent.
    pub tree_agent_id: Option<String>,
    /// When it was listed.
    pub created_at_ms: i64,
    /// Who listed it.
    pub created_by: ActorRef,
    /// Soft delete.
    pub deleted_at_ms: Option<i64>,
}

/// A node's display row, written only by that node.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NodeView {
    /// Display name.
    pub name: String,
    /// `windows`, `linux`, `macos`, `android` or `ios`.
    pub platform: String,
    /// Roles from `ui`, `catalog`, `approve`, `execute`, `store`, `always-on`.
    pub roles: Vec<String>,
    /// App version.
    pub app_version: String,
    /// letta-code version, if it executes.
    pub letta_code_version: Option<String>,
    /// Store format.
    pub store_format: i64,
    /// When node.info last changed.
    pub info_updated_at_ms: i64,
}

/// The advisory member row, derived from the membership ops.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemberView {
    /// Display name.
    pub name: String,
    /// Capabilities.
    pub caps: Vec<String>,
    /// `active` or `revoked`.
    pub state: String,
    /// The issuer of the add.
    pub added_by: String,
    /// When added.
    pub added_at_ms: i64,
    /// When revoked.
    pub revoked_at_ms: Option<i64>,
    /// The op DAG head the row was derived at.
    pub derived_from_head: String,
}

/// A shell-state entry (atomic).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    /// UTF-8 JSON, at most 8192 bytes.
    pub value_json: String,
    /// The `docRev` the writer saw after its increment.
    pub rev: i64,
    /// Who wrote it.
    pub updated_by: ActorRef,
    /// When.
    pub updated_at_ms: i64,
}

/// A pin (atomic).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Pin {
    /// Fractional index position.
    pub pos: String,
    /// When pinned.
    pub pinned_at_ms: i64,
    /// Who pinned.
    pub by: ActorRef,
}

/// A conversation archive flag (atomic).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Archived {
    /// The agent of the conversation.
    pub agent_id: String,
    /// When archived.
    pub at_ms: i64,
    /// Who archived.
    pub by: ActorRef,
}
