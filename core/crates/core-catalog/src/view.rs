//! Typed views (layer 3, catalog-v1 §3.7) with the app conflict rules (§4).
//!
//! A view is computed from the canonical JSON ([`crate::canonical`]), so it sees every conflicting
//! value. It validates shape, enums, ids, parents, cycles and signatures; invalid entries are hidden
//! and listed in `violations` with their path, never deleted. Every rule is deterministic, so every
//! node shows the same view of the same document.

use std::collections::{BTreeMap, BTreeSet};

use core_log::header::SignedEntry;
use core_log::payload::kind;
use core_membership::{MemberStatus, MembershipView, SignedOp};
use core_proto::crypto::Signature;
use core_proto::{AgentId, Epoch, NodeId};
use serde::Serialize;
use serde_json::{Map, Value};

use crate::canonical::{bytes_of, is_map, values_of, winner};
use crate::ids::{DocClass, DocUrl};
use crate::model::{
    ActorRef, Archived, DirEntry, Entry, ForkPoint, MemberView, MemoryDivergence, NodeKind,
    NodeView, Pin, Resolution, ResolutionState, SubagentStatus,
};

/// The most pins a view shows (pins v1's cap).
pub const MAX_PINS: usize = 200;

/// What a view needs from outside the document.
#[derive(Clone, Copy, Debug, Default)]
pub struct ViewContext<'a> {
    /// The local membership evaluation, to check `members` rows against.
    pub membership: Option<&'a MembershipView>,
    /// The local fenced placement high-water mark (C1), maxed with verified copies.
    pub placement_hwm: Option<Epoch>,
}

/// One hidden or flagged entry.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize)]
pub struct Violation {
    /// The key path, `/`-separated.
    pub path: String,
    /// Why.
    pub reason: String,
}

/// The common root keys.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Header {
    /// `baken.catalog.<class>/<major>`.
    pub schema: String,
    /// The major version.
    pub major: u32,
    /// The additive minor version.
    pub v: i64,
    /// The constellation id.
    pub constellation_id: String,
    /// When the document was created.
    pub created_at_ms: i64,
    /// The successor's URL after rollover.
    pub superseded_by: Option<String>,
    /// Whether this build may write it (it knows the major and at least this minor).
    pub writable: bool,
}

/// The minor version this build knows.
pub const KNOWN_MINOR: i64 = 1;

/// A typed view of one document.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum CatalogView {
    /// A schema major this build does not know: shown as unavailable and never written.
    Unavailable {
        /// The schema the document declares.
        schema: Option<String>,
    },
    /// A constellation document.
    Constellation(ConstellationView),
    /// A shell document.
    Shell(ShellView),
    /// An agent document.
    Agent(AgentView),
}

impl CatalogView {
    /// The view's violations.
    #[must_use]
    pub fn violations(&self) -> &[Violation] {
        match self {
            Self::Unavailable { .. } => &[],
            Self::Constellation(view) => &view.violations,
            Self::Shell(view) => &view.violations,
            Self::Agent(view) => &view.violations,
        }
    }
}

/// Computes the typed view of a document of `class` from its canonical JSON.
#[must_use]
pub fn view(class: DocClass, root: &Value, ctx: &ViewContext<'_>) -> CatalogView {
    let mut v = Validator::default();
    let Some(header) = v.header(class, root) else {
        let schema = root.get("schema").map(winner).and_then(Value::as_str);
        return CatalogView::Unavailable {
            schema: schema.map(str::to_owned),
        };
    };
    match class {
        DocClass::Constellation => CatalogView::Constellation(constellation(header, root, ctx, v)),
        DocClass::Shell => CatalogView::Shell(shell(header, root, v)),
        DocClass::Agent => CatalogView::Agent(agent(header, root, ctx, v)),
        DocClass::Notebook => CatalogView::Unavailable {
            schema: Some("notebook/1".into()),
        },
    }
}

#[derive(Default)]
struct Validator {
    violations: Vec<Violation>,
}

impl Validator {
    fn flag(&mut self, path: impl Into<String>, reason: &str) {
        self.violations.push(Violation {
            path: path.into(),
            reason: reason.to_owned(),
        });
    }

    fn finish(mut self) -> Vec<Violation> {
        self.violations.sort();
        self.violations.dedup();
        self.violations
    }

    fn header(&mut self, class: DocClass, root: &Value) -> Option<Header> {
        let schema = root.get("schema").map(winner)?.as_str()?.to_owned();
        let prefix = format!("baken.catalog.{}/", class.as_str());
        let major: u32 = schema.strip_prefix(&prefix)?.parse().ok()?;
        if major != 1 {
            return None;
        }
        let v = int(root.get("v")).unwrap_or(0);
        let constellation_id = string(root.get("constellationId")).unwrap_or_default();
        let created_at_ms = int(root.get("createdAtMs")).unwrap_or(0);
        let superseded_by = string(root.get("supersededBy"));
        if let Some(url) = &superseded_by
            && url.parse::<DocUrl>().is_err()
        {
            self.flag("/supersededBy", "bad_url");
        }
        Some(Header {
            schema,
            major,
            v,
            constellation_id,
            created_at_ms,
            superseded_by,
            writable: v <= KNOWN_MINOR,
        })
    }

    /// The union of every map object at a root container key (§2.1), the winner first on
    /// duplicate keys. A missing or non-map container is flagged for repair.
    fn container<'a>(&mut self, root: &'a Value, key: &str) -> BTreeMap<String, &'a Value> {
        let mut out = BTreeMap::new();
        let Some(slot) = root.get(key) else {
            self.flag(format!("/{key}"), "missing_container");
            return out;
        };
        let values = values_of(slot);
        if values.len() > 1 {
            self.flag(format!("/{key}"), "container_conflict_unioned");
        }
        let mut any_map = false;
        for value in values {
            let Some(map) = value.as_object().filter(|_| is_map(value)) else {
                continue;
            };
            any_map = true;
            for (entry, item) in map {
                out.entry(entry.clone()).or_insert(item);
            }
        }
        if !any_map {
            self.flag(format!("/{key}"), "missing_container");
        }
        out
    }

    /// Deserializes an atomic object (conflicts resolved to the winner).
    fn atomic<T: serde::de::DeserializeOwned>(&mut self, path: &str, slot: &Value) -> Option<T> {
        let resolved = resolve(winner(slot));
        if let Ok(value) = serde_json::from_value(resolved) {
            Some(value)
        } else {
            self.flag(path, "bad_shape");
            None
        }
    }
}

/// Resolves every conflict in a value to its winner.
#[must_use]
pub fn resolve(value: &Value) -> Value {
    match value {
        Value::Object(map) if map.contains_key("$conflict") => resolve(winner(value)),
        Value::Object(map) if is_map(value) => Value::Object(
            map.iter()
                .map(|(key, item)| (key.clone(), resolve(item)))
                .collect(),
        ),
        Value::Array(items) => Value::Array(items.iter().map(resolve).collect()),
        other => other.clone(),
    }
}

fn string(slot: Option<&Value>) -> Option<String> {
    slot.map(winner).and_then(Value::as_str).map(str::to_owned)
}

fn int(slot: Option<&Value>) -> Option<i64> {
    let value = slot.map(winner)?;
    value
        .as_i64()
        .or_else(|| value.get("$counter").and_then(Value::as_i64))
}

// --- constellation -----------------------------------------------------------------------------

/// The constellation document's view.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConstellationView {
    /// Root keys.
    pub header: Header,
    /// The display name.
    pub name: String,
    /// The shell document's URL.
    pub shell_doc: Option<String>,
    /// Verified membership op copies, by op id hex, with their issuer.
    pub membership_ops: BTreeMap<String, String>,
    /// Member rows (recomputed from the local evaluation when it disagrees).
    pub members: BTreeMap<String, MemberView>,
    /// Node display rows.
    pub nodes: BTreeMap<String, NodeView>,
    /// The agent directory, deleted entries hidden.
    pub agents: BTreeMap<String, AgentDirView>,
    /// Hidden or flagged entries.
    pub violations: Vec<Violation>,
}

/// One agent directory entry after the conflict rules.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentDirView {
    /// The entry.
    #[serde(flatten)]
    pub entry: DirEntry,
    /// Other agent doc URLs seen in a `docUrl` conflict (the losers to merge and supersede).
    pub superseded_doc_urls: Vec<String>,
}

#[allow(
    clippy::too_many_lines,
    reason = "one pass over the document's sections, in schema order"
)]
fn constellation(
    header: Header,
    root: &Value,
    ctx: &ViewContext<'_>,
    mut v: Validator,
) -> ConstellationView {
    let meta = v.container(root, "meta");
    let name = string(meta.get("name").copied()).unwrap_or_default();
    let docs = v.container(root, "docs");
    let shell_doc = string(docs.get("shell").copied());
    if let Some(url) = &shell_doc
        && url.parse::<DocUrl>().is_err()
    {
        v.flag("/docs/shell", "bad_url");
    }

    let mut membership_ops = BTreeMap::new();
    for (key, slot) in v.container(root, "membershipOps") {
        let item = resolve(winner(slot));
        let verified = bytes_of(&item["body"])
            .zip(bytes_of(&item["sig"]).and_then(|sig| <[u8; 64]>::try_from(sig).ok()))
            .and_then(|(body, sig)| SignedOp::from_parts(body, Signature::from_bytes(sig)).ok())
            .filter(|op| hex::encode(op.id().as_bytes()) == key);
        match verified {
            Some(op) => {
                membership_ops.insert(key, op.op().header.issuer.to_hex());
            }
            None => v.flag(format!("/membershipOps/{key}"), "unverified_op"),
        }
    }

    let mut members = BTreeMap::new();
    for (key, slot) in v.container(root, "members") {
        let path = format!("/members/{key}");
        let Some(mut row) = v.atomic::<MemberView>(&path, slot) else {
            continue;
        };
        if let Some(membership) = ctx.membership
            && let Ok(node) = key.parse::<NodeId>()
        {
            let state = match membership.status(&node) {
                Some(MemberStatus::Active) => "active",
                Some(_) => "revoked",
                None => "unknown",
            };
            let caps: Vec<String> = membership
                .capabilities_of(&node)
                .iter()
                .map(|cap| cap.as_str().to_owned())
                .collect();
            if row.state != state || row.caps != caps {
                v.flag(&path, "stale_member_row");
                state.clone_into(&mut row.state);
                row.caps = caps;
            }
        }
        members.insert(key, row);
    }

    let mut nodes = BTreeMap::new();
    for (key, slot) in v.container(root, "nodes") {
        if let Some(row) = v.atomic::<NodeView>(&format!("/nodes/{key}"), slot) {
            nodes.insert(key, row);
        }
    }

    let mut agents = BTreeMap::new();
    for (key, slot) in v.container(root, "agents") {
        let path = format!("/agents/{key}");
        let Some(map) = winner(slot).as_object() else {
            v.flag(&path, "bad_shape");
            continue;
        };
        // docUrl: the lowest id bytes win; deletedAtMs: non-null wins.
        let urls: Vec<DocUrl> = map
            .get("docUrl")
            .map(values_of)
            .unwrap_or_default()
            .into_iter()
            .filter_map(|value| value.as_str().and_then(|text| text.parse().ok()))
            .collect();
        let best = urls.iter().min_by_key(|url| url.doc_id()).copied();
        let losers: BTreeSet<String> = urls
            .iter()
            .filter(|url| Some(**url) != best)
            .map(ToString::to_string)
            .collect();
        let deleted = non_null_max(map.get("deletedAtMs"));
        let mut resolved = resolve(&Value::Object(map.clone()));
        resolved["docUrl"] = best.map_or(Value::Null, |url| Value::String(url.to_string()));
        resolved["deletedAtMs"] = deleted.map_or(Value::Null, Value::from);
        let Ok(entry) = serde_json::from_value::<DirEntry>(resolved) else {
            v.flag(&path, "bad_shape");
            continue;
        };
        if entry.agent_id != key {
            v.flag(&path, "id_mismatch");
            continue;
        }
        if !matches!(entry.kind.as_str(), "standard" | "subagent") {
            v.flag(format!("{path}/kind"), "unknown_enum");
        }
        if entry.deleted_at_ms.is_some() {
            continue;
        }
        agents.insert(
            key,
            AgentDirView {
                entry,
                superseded_doc_urls: losers.into_iter().collect(),
            },
        );
    }

    ConstellationView {
        header,
        name,
        shell_doc,
        membership_ops,
        members,
        nodes,
        agents,
        violations: v.finish(),
    }
}

fn non_null_max(slot: Option<&Value>) -> Option<i64> {
    slot.map(values_of)
        .unwrap_or_default()
        .into_iter()
        .filter_map(Value::as_i64)
        .max()
}

// --- shell -------------------------------------------------------------------------------------

/// The shell document's view.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShellView {
    /// Root keys.
    pub header: Header,
    /// shell-state v1 `document_revision`.
    pub doc_rev: i64,
    /// Entries by key.
    pub entries: BTreeMap<String, Entry>,
    /// pins v1 `revision`.
    pub pins_rev: i64,
    /// Pinned agents in view order `(pos, agentId)`, at most [`MAX_PINS`].
    pub pins: Vec<PinView>,
    /// Archived conversations.
    pub archived: BTreeMap<String, Archived>,
    /// Per-device entries, by node id.
    pub devices: BTreeMap<String, BTreeMap<String, Entry>>,
    /// Hidden or flagged entries.
    pub violations: Vec<Violation>,
}

/// One pin in view order.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PinView {
    /// The agent.
    pub agent_id: String,
    /// The pin.
    #[serde(flatten)]
    pub pin: Pin,
}

fn shell(header: Header, root: &Value, mut v: Validator) -> ShellView {
    let doc_rev = int(root.get("docRev")).unwrap_or(0);
    let pins_rev = int(root.get("pinsRev")).unwrap_or(0);
    let mut entries = BTreeMap::new();
    for (key, slot) in v.container(root, "entries") {
        if let Some(entry) = v.atomic::<Entry>(&format!("/entries/{key}"), slot) {
            entries.insert(key, entry);
        }
    }
    let mut pins = Vec::new();
    for (key, slot) in v.container(root, "pins") {
        if let Some(pin) = v.atomic::<Pin>(&format!("/pins/{key}"), slot) {
            pins.push(PinView { agent_id: key, pin });
        }
    }
    pins.sort_by(|a, b| {
        (a.pin.pos.as_str(), a.agent_id.as_str()).cmp(&(b.pin.pos.as_str(), b.agent_id.as_str()))
    });
    if pins.len() > MAX_PINS {
        v.flag("/pins", "over_pin_cap");
        pins.truncate(MAX_PINS);
    }
    let mut archived = BTreeMap::new();
    for (key, slot) in v.container(root, "archived") {
        if let Some(flag) = v.atomic::<Archived>(&format!("/archived/{key}"), slot) {
            archived.insert(key, flag);
        }
    }
    let mut devices = BTreeMap::new();
    for (node, slot) in v.container(root, "devices") {
        let mut device = BTreeMap::new();
        if let Some(map) = winner(slot)
            .get("entries")
            .map(winner)
            .and_then(Value::as_object)
        {
            for (key, item) in map {
                if let Some(entry) = v.atomic::<Entry>(&format!("/devices/{node}/{key}"), item) {
                    device.insert(key.clone(), entry);
                }
            }
        }
        devices.insert(node, device);
    }
    ShellView {
        header,
        doc_rev,
        entries,
        pins_rev,
        pins,
        archived,
        devices,
        violations: v.finish(),
    }
}

// --- agent -------------------------------------------------------------------------------------

/// The agent document's view.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentView {
    /// Root keys.
    pub header: Header,
    /// The agent.
    pub agent_id: String,
    /// The placement after the conflict and verification rules.
    pub placement: Option<PlacementView>,
    /// The session tree, by conversation id. Deleted nodes are kept (marked) so their children
    /// render under a placeholder.
    pub conversations: BTreeMap<String, TreeNode>,
    /// Tombstones.
    pub deleted: BTreeMap<String, i64>,
    /// Subagent agent to the conversation that dispatched it.
    pub subagent_links: BTreeMap<String, String>,
    /// Memory divergences, by id.
    pub memory_divergences: BTreeMap<String, MemoryDivergence>,
    /// Hidden or flagged entries.
    pub violations: Vec<Violation>,
}

/// The placement as a reader may use it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlacementView {
    /// The holder: the claimant of the verified record (or the plain field when unverified).
    pub holder: String,
    /// The epoch number.
    pub epoch_n: u64,
    /// The epoch claimant.
    pub epoch_claimant: String,
    /// The copy's state field.
    pub state: String,
    /// Whether the record's signature verified. An unverified copy is never used for routing.
    pub verified: bool,
    /// `max(verified copies, local high-water mark)`, the epoch the node acts on.
    pub effective_epoch_n: Option<u64>,
    /// The effective epoch's claimant.
    pub effective_epoch_claimant: Option<String>,
}

/// One session-tree node after the conflict rules.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TreeNode {
    /// The conversation id.
    pub id: String,
    /// The owning agent.
    pub agent_id: String,
    /// The parent, or `None` for a root (or a node shown as a root because of a cycle).
    pub parent_id: Option<String>,
    /// The kind (`unknown` for values from a later minor).
    pub kind: NodeKind,
    /// Where it leaves the parent.
    pub fork_point: Option<ForkPoint>,
    /// Who asked.
    pub created_by: Option<ActorRef>,
    /// When.
    pub created_at_ms: i64,
    /// The projected title.
    pub title: Option<String>,
    /// `user`, `agent` or `auto`.
    pub title_source: Option<String>,
    /// The max of conflicting values.
    pub last_activity_at_ms: Option<i64>,
    /// Non-null wins; also set from a tombstone.
    pub deleted_at_ms: Option<i64>,
    /// The parent has not arrived yet.
    pub orphan: bool,
    /// Subagent status after the terminal rule.
    pub status: Option<SubagentStatus>,
    /// Divergence resolution after the kept > dismissed > open rule.
    pub resolution: Option<Resolution>,
    /// The kind-specific detail, conflicts resolved.
    pub detail: Value,
}

fn agent(header: Header, root: &Value, ctx: &ViewContext<'_>, mut v: Validator) -> AgentView {
    let agent_id = string(root.get("agentId")).unwrap_or_default();
    let placement = placement(&agent_id, root.get("placement"), ctx, &mut v);

    let mut deleted = BTreeMap::new();
    for (key, slot) in v.container(root, "deleted") {
        match winner(slot).as_i64() {
            Some(at) => {
                deleted.insert(key, at);
            }
            None => v.flag(format!("/deleted/{key}"), "bad_shape"),
        }
    }

    let mut conversations = BTreeMap::new();
    for (key, slot) in v.container(root, "conversations") {
        if let Some(node) = tree_node(&key, slot, &deleted, &mut v) {
            conversations.insert(key, node);
        }
    }
    mark_cycles_and_orphans(&mut conversations, &mut v);

    let mut subagent_links = BTreeMap::new();
    for (key, slot) in v.container(root, "subagentLinks") {
        match string(winner(slot).get("dispatchedFrom")) {
            Some(from) => {
                subagent_links.insert(key, from);
            }
            None => v.flag(format!("/subagentLinks/{key}"), "bad_shape"),
        }
    }

    let mut memory_divergences = BTreeMap::new();
    for (key, slot) in v.container(root, "memoryDivergences") {
        let path = format!("/memoryDivergences/{key}");
        let Some(map) = winner(slot).as_object() else {
            v.flag(&path, "bad_shape");
            continue;
        };
        let resolution = resolution_rule(map.get("resolution"));
        let mut resolved = resolve(&Value::Object(map.clone()));
        if let Some(resolution) = &resolution {
            resolved["resolution"] = serde_json::to_value(resolution).expect("serializes");
        }
        match serde_json::from_value::<MemoryDivergence>(resolved) {
            Ok(record) if record.id == key => {
                memory_divergences.insert(key, record);
            }
            Ok(_) => v.flag(&path, "id_mismatch"),
            Err(_) => v.flag(&path, "bad_shape"),
        }
    }

    AgentView {
        header,
        agent_id,
        placement,
        conversations,
        deleted,
        subagent_links,
        memory_divergences,
        violations: v.finish(),
    }
}

/// A verified placement copy's epoch and holder.
fn verify_placement(agent: &str, copy: &Value) -> Option<Epoch> {
    let record = bytes_of(&copy["record"])?;
    let sig = <[u8; 64]>::try_from(bytes_of(&copy["sig"])?).ok()?;
    let entry = SignedEntry::from_parts(&record, &sig).ok()?;
    let header = entry.header();
    let signer = copy["signer"].as_str()?;
    (header.kind == kind::EPOCH_OPEN
        && header.log_id == core_log::hashes::log_id(&AgentId::new(agent))
        && header.epoch.claimant.to_hex() == signer)
        .then_some(header.epoch)
}

fn placement(
    agent: &str,
    slot: Option<&Value>,
    ctx: &ViewContext<'_>,
    v: &mut Validator,
) -> Option<PlacementView> {
    let copies: Vec<Value> = slot
        .map(values_of)
        .unwrap_or_default()
        .into_iter()
        .filter(|value| value.is_object())
        .map(resolve)
        .collect();
    let mut best: Option<(Epoch, &Value)> = None;
    for copy in &copies {
        match verify_placement(agent, copy) {
            Some(epoch) => {
                if best.as_ref().is_none_or(|(current, _)| epoch > *current) {
                    best = Some((epoch, copy));
                }
            }
            None => v.flag("/placement", "unverified_placement"),
        }
    }
    let effective = match (best.as_ref().map(|(epoch, _)| *epoch), ctx.placement_hwm) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (a, b) => a.or(b),
    };
    let state = |copy: &Value| copy["state"].as_str().unwrap_or("unknown").to_owned();
    if let Some((epoch, copy)) = best {
        return Some(PlacementView {
            holder: epoch.claimant.to_hex(),
            epoch_n: epoch.n,
            epoch_claimant: epoch.claimant.to_hex(),
            state: state(copy),
            verified: true,
            effective_epoch_n: effective.map(|e| e.n),
            effective_epoch_claimant: effective.map(|e| e.claimant.to_hex()),
        });
    }
    let copy = copies.first()?;
    Some(PlacementView {
        holder: copy["holder"].as_str().unwrap_or_default().to_owned(),
        epoch_n: copy["epochN"].as_u64().unwrap_or(0),
        epoch_claimant: copy["epochClaimant"]
            .as_str()
            .unwrap_or_default()
            .to_owned(),
        state: state(copy),
        verified: false,
        effective_epoch_n: effective.map(|e| e.n),
        effective_epoch_claimant: effective.map(|e| e.claimant.to_hex()),
    })
}

/// The earliest-created value of an immutable field: the lowest op id among conflicting values.
fn immutable<'a>(
    map: &'a Map<String, Value>,
    key: &str,
    path: &str,
    v: &mut Validator,
) -> Option<&'a Value> {
    let values = values_of(map.get(key)?);
    if values.len() > 1 {
        v.flag(format!("{path}/{key}"), "immutable_changed");
        // `values` is the winner, then the others in op id order: the lowest op id is next.
        return Some(values[1]);
    }
    Some(values[0])
}

fn tree_node(
    key: &str,
    slot: &Value,
    deleted: &BTreeMap<String, i64>,
    v: &mut Validator,
) -> Option<TreeNode> {
    let path = format!("/conversations/{key}");
    let Some(map) = winner(slot).as_object().filter(|_| is_map(winner(slot))) else {
        v.flag(&path, "bad_shape");
        return None;
    };
    let text = |value: Option<&Value>| value.and_then(Value::as_str).map(str::to_owned);
    let id = text(immutable(map, "id", &path, v));
    if id.as_deref() != Some(key) {
        v.flag(&path, "id_mismatch");
        return None;
    }
    let Some(agent_id) = text(immutable(map, "agentId", &path, v)) else {
        v.flag(&path, "bad_shape");
        return None;
    };
    let kind_value = immutable(map, "kind", &path, v)
        .cloned()
        .unwrap_or(Value::Null);
    let kind: NodeKind = serde_json::from_value(kind_value).unwrap_or(NodeKind::Unknown);
    if kind == NodeKind::Unknown {
        v.flag(format!("{path}/kind"), "unknown_enum");
    }
    let parent_id = text(immutable(map, "parentId", &path, v));
    if parent_id.is_none() != (kind == NodeKind::Root) && kind != NodeKind::Unknown {
        v.flag(format!("{path}/parentId"), "parent_rule");
        parent_id.as_ref()?;
    }
    let fork_point = immutable(map, "forkPoint", &path, v)
        .map(resolve)
        .and_then(|value| serde_json::from_value(value).ok());
    let created_by = immutable(map, "createdBy", &path, v)
        .map(resolve)
        .and_then(|value| serde_json::from_value(value).ok());
    let created_at_ms = immutable(map, "createdAtMs", &path, v)
        .and_then(Value::as_i64)
        .unwrap_or(0);
    let mut deleted_at_ms = non_null_max(map.get("deletedAtMs"));
    if let Some(&tombstone) = deleted.get(key) {
        if created_at_ms > tombstone {
            v.flag(&path, "id_reused_after_tombstone");
        } else {
            deleted_at_ms = Some(deleted_at_ms.map_or(tombstone, |at| at.max(tombstone)));
        }
    }
    let detail_map = map
        .get("detail")
        .map(winner)
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();
    let (status, resolution) = match kind {
        NodeKind::Subagent => (status_rule(&detail_map), None),
        NodeKind::Divergence => (None, resolution_rule(detail_map.get("resolution"))),
        _ => (None, None),
    };
    let mut detail = resolve(&Value::Object(detail_map));
    if let Some(status) = status {
        detail["status"] = serde_json::to_value(status).expect("serializes");
    }
    if let Some(resolution) = &resolution {
        detail["resolution"] = serde_json::to_value(resolution).expect("serializes");
    }
    if status == Some(SubagentStatus::Unknown) {
        v.flag(format!("{path}/detail/status"), "unknown_enum");
    }
    Some(TreeNode {
        id: key.to_owned(),
        agent_id,
        parent_id,
        kind,
        fork_point,
        created_by,
        created_at_ms,
        title: string(map.get("title")),
        title_source: string(map.get("titleSource")),
        last_activity_at_ms: non_null_max(map.get("lastActivityAtMs")),
        deleted_at_ms,
        orphan: false,
        status,
        resolution,
        detail,
    })
}

/// A terminal value beats `running`; between terminals the later `endedAtMs` wins, ties on
/// `cancelled` > `failed` > `done`.
fn status_rule(detail: &Map<String, Value>) -> Option<SubagentStatus> {
    let statuses: Vec<SubagentStatus> = detail
        .get("status")
        .map(values_of)
        .unwrap_or_default()
        .into_iter()
        .map(|value| serde_json::from_value(value.clone()).unwrap_or(SubagentStatus::Unknown))
        .collect();
    let ended = non_null_max(detail.get("endedAtMs"));
    statuses.into_iter().max_by_key(|status| {
        (
            status.is_terminal(),
            status.is_terminal().then_some(ended).flatten(),
            status.terminal_rank(),
        )
    })
}

/// `kept` beats `dismissed`, which beats `open`.
fn resolution_rule(slot: Option<&Value>) -> Option<Resolution> {
    slot.map(values_of)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|value| serde_json::from_value::<Resolution>(resolve(value)).ok())
        .max_by_key(|resolution| resolution.state.rank())
        .or_else(|| {
            slot.map(|_| Resolution {
                state: ResolutionState::Unknown,
                at_ms: None,
                by: None,
            })
        })
}

/// A node that revisits an ancestor is shown as a root and flagged; a node whose parent has not
/// arrived is an orphan (shown under "loading").
fn mark_cycles_and_orphans(nodes: &mut BTreeMap<String, TreeNode>, v: &mut Validator) {
    let parents: BTreeMap<String, Option<String>> = nodes
        .iter()
        .map(|(id, node)| (id.clone(), node.parent_id.clone()))
        .collect();
    for (id, node) in nodes.iter_mut() {
        let mut seen = BTreeSet::from([id.clone()]);
        let mut current = node.parent_id.clone();
        let mut cyclic = false;
        while let Some(parent) = current.take() {
            if parent == *id {
                cyclic = true;
                break;
            }
            if !seen.insert(parent.clone()) {
                // A loop further up that does not pass through this node.
                break;
            }
            if let Some(next) = parents.get(&parent) {
                current.clone_from(next);
            } else {
                if node.parent_id.as_deref() == Some(parent.as_str()) {
                    node.orphan = true;
                }
                break;
            }
        }
        if cyclic {
            v.flag(format!("/conversations/{id}"), "cycle");
            node.parent_id = None;
        }
    }
}
