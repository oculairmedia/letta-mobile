//! The records and enums that cross the boundary, and their conversions from the core's types.
//! Ids cross as lowercase hex strings, agent ids as their text.

use std::collections::HashMap;
use std::net::SocketAddr;

use core_carrier::NodeAddr;
use core_proto::NodeId;

use crate::error::CoreError;

/// A node's class, as node.info reports it (failover ranks always-on over desktop over phone).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum NodeClass {
    /// An always-on node (a server).
    AlwaysOn,
    /// A desktop.
    Desktop,
    /// A phone.
    Phone,
}

impl From<NodeClass> for core_placement::NodeClass {
    fn from(class: NodeClass) -> Self {
        match class {
            NodeClass::AlwaysOn => Self::AlwaysOn,
            NodeClass::Desktop => Self::Desktop,
            NodeClass::Phone => Self::Phone,
        }
    }
}

/// How a node is set up. The node key is not here: [`crate::PlatformHooks`] supplies it at start.
#[derive(Clone, Debug, uniffi::Record)]
pub struct NodeOptions {
    /// letta-code's local backend directory.
    pub backend_dir: String,
    /// The node's own data directory: its SQLite store (`node.sqlite`), staging and hooks.
    pub data_dir: String,
    /// The node's class.
    pub class: NodeClass,
    /// The Baken build, for node.info.
    pub app_version: String,
    /// Tokio worker threads for the node's runtime (2 on phones, 4 on desktop).
    pub worker_threads: u32,
    /// The constellation id (the genesis op id), hex.
    pub constellation: String,
    /// The membership op envelopes the node holds at start.
    pub membership_ops: Vec<Vec<u8>>,
    /// The agent actors' tick in milliseconds (move deadlines, failover, checkpoints).
    pub tick_ms: u64,
    /// The backend watcher's debounce in milliseconds.
    pub watch_debounce_ms: u64,
}

/// Where a node can be dialled.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PeerAddress {
    /// The node id, hex.
    pub node_id: String,
    /// Direct socket addresses (`ip:port`), possibly none.
    pub direct: Vec<String>,
    /// A relay URL, if the node has one.
    pub relay: Option<String>,
}

impl PeerAddress {
    pub(crate) fn to_core(&self) -> Result<NodeAddr, CoreError> {
        let mut addr = NodeAddr::new(parse_node_id(&self.node_id)?);
        for direct in &self.direct {
            let socket: SocketAddr = direct
                .parse()
                .map_err(|_| CoreError::invalid(format!("not an ip:port: {direct}")))?;
            addr = addr.with_direct(socket);
        }
        addr.relay.clone_from(&self.relay);
        Ok(addr)
    }

    pub(crate) fn from_core(addr: &NodeAddr) -> Self {
        Self {
            node_id: addr.id.to_hex(),
            direct: addr.direct.iter().map(ToString::to_string).collect(),
            relay: addr.relay.clone(),
        }
    }
}

pub(crate) fn parse_node_id(text: &str) -> Result<NodeId, CoreError> {
    text.parse()
        .map_err(|_| CoreError::invalid(format!("not a node id: {text}")))
}

pub(crate) fn parse_hex32(what: &str, text: &str) -> Result<[u8; 32], CoreError> {
    let bytes = hex::decode(text).map_err(|_| CoreError::invalid(format!("{what} is not hex")))?;
    bytes
        .try_into()
        .map_err(|_| CoreError::invalid(format!("{what} is not 32 bytes")))
}

/// `store_format {major, minor}`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct StoreFormat {
    /// A different major blocks `execute` and `store`.
    pub major: u64,
    /// Additive revisions.
    pub minor: u64,
}

/// `runtime_patches`.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct RuntimePatches {
    /// `boot_line` when read from the patch loader, else `unknown`.
    pub source: String,
    /// Patches applied, if known.
    pub applied: Option<u32>,
    /// Patches skipped, if known.
    pub skipped: Option<u32>,
    /// SHA-256 of the sorted patch-id list, if known.
    pub patch_ids_sha256: Option<String>,
}

/// `node.info` v1.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct NodeInfo {
    /// The schema.
    pub schema: u32,
    /// `windows`, `linux`, `macos`, `android`, `ios` or `unknown`.
    pub os: String,
    /// The Baken build, or `unknown`.
    pub app_version: String,
    /// letta-code's version, or `unknown`.
    pub letta_code_version: String,
    /// The agent store layout this node writes.
    pub store_format: StoreFormat,
    /// Any of `ui`, `catalog`, `approve`, `execute`, `store`, `always-on`.
    pub capabilities: Vec<String>,
    /// Model handles the runtime offers.
    pub models: Vec<String>,
    /// The runtime patch set.
    pub runtime_patches: RuntimePatches,
}

impl From<core_node::NodeInfo> for NodeInfo {
    fn from(info: core_node::NodeInfo) -> Self {
        Self {
            schema: info.schema,
            os: info.os,
            app_version: info.app_version,
            letta_code_version: info.letta_code_version,
            store_format: StoreFormat {
                major: info.store_format.major,
                minor: info.store_format.minor,
            },
            capabilities: info.capabilities,
            models: info.models,
            runtime_patches: RuntimePatches {
                source: info.runtime_patches.source,
                applied: info.runtime_patches.applied,
                skipped: info.runtime_patches.skipped,
                patch_ids_sha256: info.runtime_patches.patch_ids_sha256,
            },
        }
    }
}

/// A member's status.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum MemberStatus {
    /// An active member.
    Active,
    /// Revoked for good.
    Revoked,
    /// Moved to a new key ([`Member::rotated_to`]).
    Rotated,
}

/// One id the membership log names.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct Member {
    /// The node id, hex.
    pub node_id: String,
    /// Its status.
    pub status: MemberStatus,
    /// The key it rotated to, hex, when rotated.
    pub rotated_to: Option<String>,
    /// Its display name.
    pub name: Option<String>,
    /// Its capabilities (empty unless active).
    pub capabilities: Vec<String>,
}

/// The membership view a node evaluated from the ops it holds. A snapshot: membership is small, so
/// every change delivers the whole view.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct MembershipView {
    /// The constellation id, hex.
    pub constellation: String,
    /// The constellation's display name.
    pub label: Option<String>,
    /// Whether no active admin is left.
    pub locked: bool,
    /// The op DAG heads the view was evaluated at, hex.
    pub heads: Vec<String>,
    /// Every id with a status, ordered by id.
    pub members: Vec<Member>,
}

impl From<&core_membership::MembershipView> for MembershipView {
    fn from(view: &core_membership::MembershipView) -> Self {
        let members = view
            .statuses()
            .iter()
            .map(|(node, status)| {
                let (status, rotated_to) = match status {
                    core_membership::MemberStatus::Active => (MemberStatus::Active, None),
                    core_membership::MemberStatus::Revoked => (MemberStatus::Revoked, None),
                    core_membership::MemberStatus::Rotated { to } => {
                        (MemberStatus::Rotated, Some(to.to_hex()))
                    }
                };
                Member {
                    node_id: node.to_hex(),
                    status,
                    rotated_to,
                    name: view.name_of(node).map(str::to_owned),
                    capabilities: view
                        .capabilities_of(node)
                        .iter()
                        .map(|cap| cap.as_str().to_owned())
                        .collect(),
                }
            })
            .collect();
        Self {
            constellation: hex::encode(view.constellation().as_bytes()),
            label: view.label().map(str::to_owned),
            locked: view.is_locked(),
            heads: view
                .heads()
                .iter()
                .map(|head| hex::encode(head.as_bytes()))
                .collect(),
            members,
        }
    }
}

/// Where an agent lives, from this node's copy of its log, and this node's input gate for it.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PlacementStatus {
    /// The agent.
    pub agent_id: String,
    /// The holder, hex, or none.
    pub holder_node_id: Option<String>,
    /// The canonical epoch number.
    pub epoch_n: u64,
    /// The canonical epoch's claimant, hex.
    pub epoch_claimant: String,
    /// `active`, `moving`, `placeless` or `superseded_unknown`.
    pub state: String,
    /// Present only for `moving`.
    pub retry_after_ms: Option<u64>,
    /// This node's gate: `active`, `moving`, `not_holder`, `placeless` or `superseded_unknown`.
    pub gate: String,
    /// The canonical tip's seq.
    pub tip_seq: u64,
}

impl PlacementStatus {
    pub(crate) fn of(record: &core_placement::PlacementRecord, gate: core_placement::Gate) -> Self {
        let placement =
            core_placement::feed::PlacementJson::of(record, gate == core_placement::Gate::Moving);
        Self {
            agent_id: placement.agent_id,
            holder_node_id: placement.holder_node_id,
            epoch_n: placement.epoch.n,
            epoch_claimant: placement.epoch.claimant,
            state: placement.state,
            retry_after_ms: placement.retry_after_ms,
            gate: gate.name().to_owned(),
            tip_seq: record.tip.seq,
        }
    }
}

/// One document the node's catalog registry lists.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CatalogDocument {
    /// The `automerge:` URL.
    pub url: String,
    /// The class: `constellation`, `shell`, `agent`, `notebook`, ...
    pub class: String,
    /// The agent an agent document belongs to.
    pub agent_id: Option<String>,
    /// `live`, `superseded` or `retired`.
    pub state: String,
    /// When this node registered it.
    pub registered_at_ms: u64,
}

impl From<core_store::CatalogDoc> for CatalogDocument {
    fn from(doc: core_store::CatalogDoc) -> Self {
        Self {
            url: core_catalog::DocUrl::new(doc.doc_id).to_string(),
            class: doc.class,
            agent_id: doc.agent_id.map(|agent| agent.as_str().to_owned()),
            state: doc.state.as_str().to_owned(),
            registered_at_ms: doc.registered_at_ms,
        }
    }
}

/// Something that happened to the node itself.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum NodeEvent {
    /// The node is stopping; every stream of it ends.
    Stopping,
    /// The platform reported the app moving to or from the foreground.
    ForegroundChanged {
        /// Whether the app is now in the foreground.
        foreground: bool,
    },
    /// The platform reported a network change (the transport was told to re-probe).
    NetworkChanged,
}

/// What the runtime is, for node.info and I6 parity.
#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct RuntimeInfo {
    /// letta-code's version, if the App Server reported it.
    pub letta_code_version: Option<String>,
    /// The lcp runtime patch ids applied, when the host can see them.
    pub patch_ids: Option<Vec<String>>,
    /// Patches applied, from the boot line, when the host read it.
    pub patches_applied: Option<u32>,
    /// Patches skipped, from the boot line, when the host read it.
    pub patches_skipped: Option<u32>,
    /// The model handles `model.list` shows by default.
    pub models: Vec<String>,
}

impl From<RuntimeInfo> for core_node::RuntimeInfo {
    fn from(info: RuntimeInfo) -> Self {
        Self {
            letta_code_version: info.letta_code_version,
            patch_ids: info.patch_ids,
            patches_applied: info
                .patches_applied
                .map(|applied| (applied, info.patches_skipped.unwrap_or(0))),
            models: info.models,
        }
    }
}

/// Starting the runtime for one agent.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AgentRuntimeSpec {
    /// The agent (its subagents run in the same runtime).
    pub agent_id: String,
    /// The letta-code local backend directory (`LETTA_LOCAL_BACKEND_DIR`).
    pub backend_dir: String,
    /// The node's hooks directory for memfs (`core.hooksPath`).
    pub hooks_dir: String,
    /// Node-local environment from the bindings; never replicated.
    pub env: HashMap<String, String>,
}

impl From<&core_node::AgentRuntime> for AgentRuntimeSpec {
    fn from(spec: &core_node::AgentRuntime) -> Self {
        Self {
            agent_id: spec.agent_id.as_str().to_owned(),
            backend_dir: spec.backend_dir.to_string_lossy().into_owned(),
            hooks_dir: spec.hooks_dir.to_string_lossy().into_owned(),
            env: spec
                .env
                .iter()
                .map(|(key, value)| (key.clone(), value.clone()))
                .collect(),
        }
    }
}

/// The outcome of a quiesce request.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum QuiesceOutcome {
    /// No turn is running and none will start.
    Idle,
    /// A turn was still running when the timeout passed.
    Busy,
}

impl From<QuiesceOutcome> for core_node::Quiesce {
    fn from(outcome: QuiesceOutcome) -> Self {
        match outcome {
            QuiesceOutcome::Idle => Self::Idle,
            QuiesceOutcome::Busy => Self::Busy,
        }
    }
}

/// The capability presets the UI offers when it adds a device.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum MemberPreset {
    /// A desktop node.
    DesktopNode,
    /// An always-on Linux node.
    AlwaysOnLinux,
    /// A read-only member.
    ReadOnly,
    /// A phone; `execution` when the user opted it into running turns.
    Phone {
        /// Whether the phone runs turns.
        execution: bool,
    },
}

impl From<MemberPreset> for core_membership::Preset {
    fn from(preset: MemberPreset) -> Self {
        match preset {
            MemberPreset::DesktopNode => Self::DesktopNode,
            MemberPreset::AlwaysOnLinux => Self::AlwaysOnLinux,
            MemberPreset::ReadOnly => Self::ReadOnly,
            MemberPreset::Phone { execution } => Self::Phone { execution },
        }
    }
}
