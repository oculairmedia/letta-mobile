//! The catalog writers: genesis per class, and every write catalog-v1 §2 allows.
//!
//! Every value is checked by [`crate::secrets`] before it reaches Automerge. Atomic objects are
//! written by putting a new map at their key; containers are only created at genesis (and by
//! [`crate::repair`] and [`crate::rollover`]). Writers never write a document whose `supersededBy` is
//! set, and refuse non-essential writes at the hard cap (§5.3).

use automerge::transaction::Transactable;
use automerge::{ObjId, ObjType, ReadDoc, ScalarValue, Value as AmValue};
use core_log::DivergenceInputs;
use core_proto::crypto::blake3_hash;
use core_proto::{ConversationId, Epoch};
use serde::Serialize;
use serde_json::{Map, Value, json};

use crate::canonical::{bytes_json, write_map};
use crate::error::CatalogError;
use crate::ids::{DocClass, DocUrl};
use crate::model::{
    ActorRef, Archived, DirEntry, DivergenceDetail, Entry, MemberView, MemoryDivergence, NewNode,
    NodeView, Pin, PlacementCopy, Resolution, SubagentStatus,
};
use crate::secrets::{check_str, field_kind, scan};

/// The in-band prefix of C2's memory-divergence id.
pub const MEMDIV_PREFIX: &[u8] = b"baken.memdiv.v1";

/// The values every genesis change writes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Genesis {
    /// The constellation id (C1's genesis op id, hex).
    pub constellation_id: String,
    /// The creation time.
    pub created_at_ms: i64,
    /// The agent of an agent document.
    pub agent_id: Option<String>,
    /// The constellation's display name (constellation documents).
    pub name: Option<String>,
}

/// Writes the genesis change of `class` into an empty document: every root key and every
/// container, with `v = 1` and counters at 0.
///
/// # Errors
///
/// [`CatalogError::Schema`] for a notebook (not a catalog class) or an agent genesis without an
/// agent id; [`CatalogError::Refused`] for a value that fails the secrets check.
///
/// # Panics
///
/// Never: the genesis values are maps.
pub fn genesis<T: Transactable>(
    doc: &mut T,
    class: DocClass,
    genesis: &Genesis,
) -> Result<(), CatalogError> {
    let schema = class
        .schema_v1()
        .ok_or_else(|| CatalogError::Schema("notebooks have no catalog genesis".into()))?;
    let mut root = json!({
        "schema": schema,
        "v": 1,
        "constellationId": genesis.constellation_id,
        "createdAtMs": genesis.created_at_ms,
        "supersededBy": null,
        "ext": {},
    });
    let extra = match class {
        DocClass::Constellation => json!({
            "meta": { "name": genesis.name.clone().unwrap_or_default(), "iconToken": null },
            "docs": {},
            "membershipOps": {},
            "members": {},
            "nodes": {},
            "agents": {},
            "settings": { "signed": {}, "display": {} },
        }),
        DocClass::Shell => json!({
            "docRev": { "$counter": 0 },
            "entries": {},
            "pinsRev": { "$counter": 0 },
            "pins": {},
            "archived": {},
            "devices": {},
        }),
        DocClass::Agent => {
            let agent = genesis
                .agent_id
                .clone()
                .ok_or_else(|| CatalogError::Schema("an agent genesis needs an agent id".into()))?;
            json!({
                "agentId": agent,
                "placement": null,
                "conversations": {},
                "deleted": {},
                "subagentLinks": {},
                "memoryDivergences": {},
            })
        }
        DocClass::Notebook => unreachable!("refused above"),
    };
    let object = root.as_object_mut().expect("an object");
    object.extend(extra.as_object().expect("an object").clone());
    refuse_findings("", &root)?;
    write_map(doc, &automerge::ROOT, root.as_object().expect("an object"))
}

/// The id of a root container, or [`CatalogError::Schema`] when it is missing or not a map.
///
/// # Errors
///
/// [`CatalogError::Schema`].
pub fn container<D: ReadDoc>(doc: &D, key: &str) -> Result<ObjId, CatalogError> {
    child_map(doc, &automerge::ROOT, key)
        .ok_or_else(|| CatalogError::Schema(format!("container {key} is missing; repair first")))
}

/// The id of the map at `key` in `obj`, if there is one (the Automerge winner).
#[must_use]
pub fn child_map<D: ReadDoc>(doc: &D, obj: &ObjId, key: &str) -> Option<ObjId> {
    match doc.get(obj, key).ok()?? {
        (AmValue::Object(ObjType::Map), id) => Some(id),
        _ => None,
    }
}

/// Refuses writes to a superseded document.
///
/// # Errors
///
/// [`CatalogError::Schema`] naming the successor.
pub fn ensure_live<D: ReadDoc>(doc: &D) -> Result<(), CatalogError> {
    match doc.get(automerge::ROOT, "supersededBy")? {
        Some((AmValue::Scalar(scalar), _)) if !matches!(scalar.as_ref(), ScalarValue::Null) => {
            Err(CatalogError::Schema(format!(
                "document superseded by {scalar}; write the successor"
            )))
        }
        _ => Ok(()),
    }
}

fn refuse_findings(path: &str, value: &Value) -> Result<(), CatalogError> {
    let wrapped;
    let target = if path.is_empty() {
        value
    } else {
        // Scan under the field's own key so it gets its field kind.
        let key = path.rsplit('/').next().unwrap_or(path).to_owned();
        wrapped = json!({ key: value });
        &wrapped
    };
    if let Some(finding) = scan(target).into_iter().next() {
        return Err(CatalogError::Refused {
            path: format!("{path}{}", finding.path),
            reason: finding.reason,
        });
    }
    Ok(())
}

fn to_map<T: Serialize>(value: &T) -> Map<String, Value> {
    match serde_json::to_value(value).expect("catalog values serialize") {
        Value::Object(map) => map,
        _ => unreachable!("catalog values are maps"),
    }
}

fn check_key(path: &str, key: &str) -> Result<(), CatalogError> {
    if let Some(reason) = check_str(key, crate::secrets::FieldKind::Id) {
        return Err(CatalogError::Refused {
            path: format!("{path}/{key}"),
            reason,
        });
    }
    Ok(())
}

/// Puts a new map object built from `value` at `key` of `obj` (an atomic write).
fn put_atomic<T: Transactable>(
    doc: &mut T,
    obj: &ObjId,
    path: &str,
    key: &str,
    value: &Map<String, Value>,
) -> Result<ObjId, CatalogError> {
    check_key(path, key)?;
    refuse_findings(&format!("{path}/{key}"), &Value::Object(value.clone()))?;
    let child = doc.put_object(obj, key, ObjType::Map)?;
    write_map(doc, &child, value)?;
    Ok(child)
}

/// Puts one field of a map that is edited in place.
fn put_field<T: Transactable>(
    doc: &mut T,
    obj: &ObjId,
    path: &str,
    key: &str,
    value: &Value,
) -> Result<(), CatalogError> {
    if let Value::String(text) = value
        && let Some(reason) = check_str(text, field_kind(key))
    {
        return Err(CatalogError::Refused {
            path: format!("{path}/{key}"),
            reason,
        });
    }
    doc.put(obj, key, crate::canonical::scalar_of(value)?)?;
    Ok(())
}

// --- constellation -----------------------------------------------------------------------------

/// Sets `docs.shell`.
///
/// # Errors
///
/// A missing container or an Automerge error.
pub fn set_shell_doc<T: Transactable>(doc: &mut T, shell: &DocUrl) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let docs = container(doc, "docs")?;
    doc.put(&docs, "shell", shell.to_string())?;
    Ok(())
}

/// Adds a membership op copy: canonical C1 body, signature and issuer, keyed by the op id hex.
///
/// # Errors
///
/// A missing container or an Automerge error.
pub fn put_membership_op<T: Transactable>(
    doc: &mut T,
    op: &core_membership::SignedOp,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let ops = container(doc, "membershipOps")?;
    let key = hex::encode(op.id().as_bytes());
    if child_map(doc, &ops, &key).is_some() {
        return Ok(());
    }
    let mut value = Map::new();
    value.insert("body".into(), bytes_json(op.body()));
    value.insert("sig".into(), bytes_json(op.signature().as_bytes()));
    value.insert(
        "issuer".into(),
        Value::String(op.op().header.issuer.to_hex()),
    );
    put_atomic(doc, &ops, "/membershipOps", &key, &value)?;
    Ok(())
}

/// Writes a node's display row (only that node writes it).
///
/// # Errors
///
/// A missing container, a refused value or an Automerge error.
pub fn put_node_view<T: Transactable>(
    doc: &mut T,
    node: &str,
    view: &NodeView,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let nodes = container(doc, "nodes")?;
    put_atomic(doc, &nodes, "/nodes", node, &to_map(view))?;
    Ok(())
}

/// Writes a member's advisory row.
///
/// # Errors
///
/// A missing container, a refused value or an Automerge error.
pub fn put_member_view<T: Transactable>(
    doc: &mut T,
    node: &str,
    view: &MemberView,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let members = container(doc, "members")?;
    put_atomic(doc, &members, "/members", node, &to_map(view))?;
    Ok(())
}

/// Lists an agent in the directory (a new entry map).
///
/// # Errors
///
/// A missing container, a refused value or an Automerge error.
pub fn put_dir_entry<T: Transactable>(doc: &mut T, entry: &DirEntry) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let agents = container(doc, "agents")?;
    put_atomic(doc, &agents, "/agents", &entry.agent_id, &to_map(entry))?;
    Ok(())
}

/// Sets one field of an agent's directory entry (`name`, `description`, `docUrl`, `deletedAtMs`,
/// ...).
///
/// # Errors
///
/// [`CatalogError::UnknownDocument`] when the agent is not listed, or a refused value.
pub fn set_dir_field<T: Transactable>(
    doc: &mut T,
    agent: &str,
    key: &str,
    value: &Value,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let agents = container(doc, "agents")?;
    let entry = child_map(doc, &agents, agent)
        .ok_or_else(|| CatalogError::UnknownDocument(format!("agent {agent}")))?;
    put_field(doc, &entry, &format!("/agents/{agent}"), key, value)
}

// --- shell -------------------------------------------------------------------------------------

/// Sets a shell-state entry: increments `docRev` by one and writes the entry with the new
/// revision. Returns the revision.
///
/// # Errors
///
/// [`CatalogError::Schema`] for a value over 8192 bytes, a refused value, or an Automerge error.
pub fn set_entry<T: Transactable>(
    doc: &mut T,
    key: &str,
    value_json: &str,
    by: &ActorRef,
    now_ms: i64,
) -> Result<i64, CatalogError> {
    ensure_live(doc)?;
    if value_json.len() > 8192 {
        return Err(CatalogError::Schema(format!(
            "entry {key} is {} bytes; the limit is 8192",
            value_json.len()
        )));
    }
    let rev = bump(doc, "docRev")?;
    let entries = container(doc, "entries")?;
    let entry = Entry {
        value_json: value_json.to_owned(),
        rev,
        updated_by: by.clone(),
        updated_at_ms: now_ms,
    };
    put_atomic(doc, &entries, "/entries", key, &to_map(&entry))?;
    Ok(rev)
}

/// Removes a shell-state entry (incrementing `docRev`). Returns the revision.
///
/// # Errors
///
/// An Automerge error.
pub fn remove_entry<T: Transactable>(doc: &mut T, key: &str) -> Result<i64, CatalogError> {
    ensure_live(doc)?;
    let rev = bump(doc, "docRev")?;
    let entries = container(doc, "entries")?;
    doc.delete(&entries, key)?;
    Ok(rev)
}

/// Increments a root counter by one and returns the value this replica then sees.
fn bump<T: Transactable>(doc: &mut T, key: &str) -> Result<i64, CatalogError> {
    doc.increment(automerge::ROOT, key, 1)?;
    match doc.get(automerge::ROOT, key)? {
        Some((AmValue::Scalar(scalar), _)) => match scalar.as_ref() {
            ScalarValue::Counter(counter) => Ok(i64::from(counter)),
            _ => Err(CatalogError::Schema(format!("{key} is not a counter"))),
        },
        _ => Err(CatalogError::Schema(format!("{key} is missing"))),
    }
}

/// Pins an agent at `pos` (incrementing `pinsRev`).
///
/// # Errors
///
/// A refused value or an Automerge error.
pub fn set_pin<T: Transactable>(doc: &mut T, agent: &str, pin: &Pin) -> Result<i64, CatalogError> {
    ensure_live(doc)?;
    let rev = bump(doc, "pinsRev")?;
    let pins = container(doc, "pins")?;
    put_atomic(doc, &pins, "/pins", agent, &to_map(pin))?;
    Ok(rev)
}

/// Unpins an agent (incrementing `pinsRev`).
///
/// # Errors
///
/// An Automerge error.
pub fn remove_pin<T: Transactable>(doc: &mut T, agent: &str) -> Result<i64, CatalogError> {
    ensure_live(doc)?;
    let rev = bump(doc, "pinsRev")?;
    let pins = container(doc, "pins")?;
    doc.delete(&pins, agent)?;
    Ok(rev)
}

/// Archives a conversation.
///
/// # Errors
///
/// A refused value or an Automerge error.
pub fn archive<T: Transactable>(
    doc: &mut T,
    conversation: &str,
    archived: &Archived,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let map = container(doc, "archived")?;
    put_atomic(doc, &map, "/archived", conversation, &to_map(archived))?;
    Ok(())
}

/// Unarchives a conversation.
///
/// # Errors
///
/// An Automerge error.
pub fn unarchive<T: Transactable>(doc: &mut T, conversation: &str) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let map = container(doc, "archived")?;
    doc.delete(&map, conversation)?;
    Ok(())
}

// --- agent -------------------------------------------------------------------------------------

/// Writes the placement copy (atomic). A writer never writes below the verified copy it holds;
/// the caller checks that against the typed view.
///
/// # Errors
///
/// A refused value or an Automerge error.
pub fn put_placement<T: Transactable>(
    doc: &mut T,
    copy: &PlacementCopy,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let mut value = Map::new();
    value.insert("holder".into(), json!(copy.holder));
    value.insert("epochN".into(), json!(copy.epoch_n));
    value.insert("epochClaimant".into(), json!(copy.epoch_claimant));
    value.insert("state".into(), json!(copy.state));
    value.insert("prevHolder".into(), json!(copy.prev_holder));
    value.insert("record".into(), bytes_json(&copy.record));
    value.insert("sig".into(), bytes_json(&copy.sig));
    value.insert("signer".into(), json!(copy.signer));
    value.insert("copiedAtMs".into(), json!(copy.copied_at_ms));
    value.insert("copiedBy".into(), json!(copy.copied_by));
    put_atomic(doc, &automerge::ROOT, "", "placement", &value)?;
    Ok(())
}

/// Creates a session-tree node. Its `forkPoint` and `createdBy` are atomic sub-objects; the other
/// fields are edited in place later.
///
/// # Errors
///
/// [`CatalogError::Schema`] when `parentId` is missing for a non-root node; a refused value; an
/// Automerge error.
pub fn put_node<T: Transactable>(doc: &mut T, node: &NewNode) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    if node.parent_id.is_none() != (node.kind == crate::model::NodeKind::Root) {
        return Err(CatalogError::Schema(format!(
            "node {}: parentId is null exactly for root",
            node.id
        )));
    }
    let conversations = container(doc, "conversations")?;
    let mut value = to_map(node);
    value.insert("ext".into(), json!({}));
    put_atomic(doc, &conversations, "/conversations", &node.id, &value)?;
    Ok(())
}

/// Sets a mutable node field: `title`, `titleSource`, `lastActivityAtMs` or `deletedAtMs`.
///
/// # Errors
///
/// [`CatalogError::Schema`] for an immutable field, [`CatalogError::UnknownDocument`] for an unknown
/// node, or a refused value.
pub fn set_node_field<T: Transactable>(
    doc: &mut T,
    conversation: &str,
    key: &str,
    value: &Value,
) -> Result<(), CatalogError> {
    const MUTABLE: [&str; 4] = ["title", "titleSource", "lastActivityAtMs", "deletedAtMs"];
    ensure_live(doc)?;
    if !MUTABLE.contains(&key) {
        return Err(CatalogError::Schema(format!("{key} is immutable")));
    }
    let node = node_obj(doc, conversation)?;
    put_field(
        doc,
        &node,
        &format!("/conversations/{conversation}"),
        key,
        value,
    )
}

/// Ends a subagent: sets `detail.status` and `detail.endedAtMs`.
///
/// # Errors
///
/// [`CatalogError::UnknownDocument`] for an unknown node or one without detail.
///
/// # Panics
///
/// Never: statuses serialize.
pub fn end_subagent<T: Transactable>(
    doc: &mut T,
    conversation: &str,
    status: SubagentStatus,
    ended_at_ms: i64,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let node = node_obj(doc, conversation)?;
    let detail = child_map(doc, &node, "detail")
        .ok_or_else(|| CatalogError::UnknownDocument(format!("{conversation} detail")))?;
    let status = serde_json::to_value(status).expect("serializes");
    doc.put(&detail, "status", crate::canonical::scalar_of(&status)?)?;
    doc.put(&detail, "endedAtMs", ended_at_ms)?;
    Ok(())
}

/// Sets the resolution of a divergence node.
///
/// # Errors
///
/// [`CatalogError::UnknownDocument`] for an unknown node.
pub fn resolve_divergence<T: Transactable>(
    doc: &mut T,
    conversation: &str,
    resolution: &Resolution,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let node = node_obj(doc, conversation)?;
    let detail = child_map(doc, &node, "detail")
        .ok_or_else(|| CatalogError::UnknownDocument(format!("{conversation} detail")))?;
    put_atomic(
        doc,
        &detail,
        &format!("/conversations/{conversation}/detail"),
        "resolution",
        &to_map(resolution),
    )?;
    Ok(())
}

/// Adds a tombstone in `deleted` (survives rollover).
///
/// # Errors
///
/// An Automerge error.
pub fn tombstone<T: Transactable>(
    doc: &mut T,
    conversation: &str,
    deleted_at_ms: i64,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let deleted = container(doc, "deleted")?;
    check_key("/deleted", conversation)?;
    doc.put(&deleted, conversation, deleted_at_ms)?;
    Ok(())
}

/// Records which conversation dispatched a subagent agent.
///
/// # Errors
///
/// A refused value or an Automerge error.
///
/// # Panics
///
/// Never: the link is a map.
pub fn put_subagent_link<T: Transactable>(
    doc: &mut T,
    subagent: &str,
    dispatched_from: &str,
    at_ms: i64,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let links = container(doc, "subagentLinks")?;
    let value = json!({ "dispatchedFrom": dispatched_from, "atMs": at_ms });
    put_atomic(
        doc,
        &links,
        "/subagentLinks",
        subagent,
        value.as_object().expect("an object"),
    )?;
    Ok(())
}

/// Writes a memory divergence record.
///
/// # Errors
///
/// A refused value or an Automerge error.
pub fn put_memory_divergence<T: Transactable>(
    doc: &mut T,
    record: &MemoryDivergence,
) -> Result<(), CatalogError> {
    ensure_live(doc)?;
    let map = container(doc, "memoryDivergences")?;
    put_atomic(doc, &map, "/memoryDivergences", &record.id, &to_map(record))?;
    Ok(())
}

fn node_obj<D: ReadDoc>(doc: &D, conversation: &str) -> Result<ObjId, CatalogError> {
    let conversations = container(doc, "conversations")?;
    child_map(doc, &conversations, conversation)
        .ok_or_else(|| CatalogError::UnknownDocument(format!("conversation {conversation}")))
}

// --- divergence ids ----------------------------------------------------------------------------

/// The divergence node for `conversation` from core-log's divergence inputs: the id is C2's UUIDv8
/// (bare, no prefix), so both detectors write the same node.
#[must_use]
pub fn divergence_node(
    inputs: &DivergenceInputs,
    conversation: &ConversationId,
    last_common_message_id: Option<String>,
    detected: (i64, &str),
) -> NewNode {
    let id = core_log::hashes::uuid_text(&inputs.catalog_node_id(conversation));
    let (detected_at_ms, detected_by) = detected;
    let fork_seq = i64::try_from(inputs.fork.seq).unwrap_or(i64::MAX);
    NewNode {
        id,
        agent_id: inputs.agent_id.as_str().to_owned(),
        parent_id: Some(conversation.as_str().to_owned()),
        kind: crate::model::NodeKind::Divergence,
        fork_point: Some(crate::model::ForkPoint {
            last_shared_message_id: last_common_message_id.clone(),
            log_epoch_n: Some(epoch_n(&inputs.fork_epoch)),
            log_epoch_claimant: Some(inputs.fork_epoch.claimant.to_hex()),
            log_seq: Some(fork_seq),
        }),
        created_by: ActorRef::node(detected_by),
        created_at_ms: detected_at_ms,
        title: None,
        title_source: None,
        last_activity_at_ms: None,
        deleted_at_ms: None,
        detail: crate::model::Detail::Divergence {
            divergence: DivergenceDetail {
                stranded_holder: inputs.stranded_epoch.claimant.to_hex(),
                stranded_epoch_n: epoch_n(&inputs.stranded_epoch),
                stranded_epoch_claimant: inputs.stranded_epoch.claimant.to_hex(),
                winning_epoch_n: epoch_n(&inputs.winning_epoch),
                winning_epoch_claimant: inputs.winning_epoch.claimant.to_hex(),
                last_common_message_id,
                last_common_log_seq: Some(fork_seq),
                detected_at_ms,
                detected_by: detected_by.to_owned(),
            },
            resolution: Resolution::open(),
        },
    }
}

fn epoch_n(epoch: &Epoch) -> i64 {
    i64::try_from(epoch.n).unwrap_or(i64::MAX)
}

/// The memory-divergence id: the first 16 bytes of
/// `blake3("baken.memdiv.v1" ‖ agentId ‖ 0x00 ‖ u64be(n) ‖ claimant)` as an RFC 9562 UUIDv8.
#[must_use]
pub fn memory_divergence_id(agent: &str, stranded: &Epoch) -> String {
    let mut input = Vec::new();
    input.extend_from_slice(MEMDIV_PREFIX);
    input.extend_from_slice(agent.as_bytes());
    input.push(0);
    input.extend_from_slice(&core_log::hashes::epoch_bytes(stranded));
    let digest = blake3_hash(&input);
    let mut custom = [0_u8; 16];
    custom.copy_from_slice(&digest[..16]);
    uuid::Builder::from_custom_bytes(custom)
        .into_uuid()
        .hyphenated()
        .to_string()
}

/// The memory divergence record for a memfs ref leaf. The branch is C1's ref with `refs/heads/`
/// removed: `divergence/<n>-<claimant8>/<leaf>`.
#[must_use]
pub fn memory_divergence(
    inputs: &DivergenceInputs,
    leaf: &str,
    detected: (i64, &str),
) -> MemoryDivergence {
    let branch = inputs.memfs_branch(leaf);
    MemoryDivergence {
        id: memory_divergence_id(inputs.agent_id.as_str(), &inputs.stranded_epoch),
        stranded_epoch_n: epoch_n(&inputs.stranded_epoch),
        stranded_epoch_claimant: inputs.stranded_epoch.claimant.to_hex(),
        memfs_branch: branch
            .strip_prefix("refs/heads/")
            .unwrap_or(&branch)
            .to_owned(),
        detected_at_ms: detected.0,
        detected_by: detected.1.to_owned(),
        resolution: Resolution::open(),
    }
}
