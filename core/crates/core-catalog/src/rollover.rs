//! Rollover (catalog-v1 §5.3) and container repair (§3.7).
//!
//! Automerge cannot prune history in place, so a document that grows past 2 MiB (or is more than
//! half deleted, or needs a breaking schema change) is replaced by a new document whose genesis is
//! the materialised view. The old one gets `supersededBy`, a 7-day late-change sweep copies entries
//! that still arrive there, and after 30 days it is retired. A constellation rollover also issues
//! C1's `catalog_root_move` op.

use std::collections::BTreeSet;

use automerge::transaction::Transactable;
use automerge::{ActorId, AutoCommit, ObjType, ReadDoc};
use core_membership::{CatalogRoot, Op, OpHeader, OpPayload};
use core_store::DocId;
use serde_json::{Map, Value};

use crate::canonical::{canonical_view, is_map, values_of, winner, write_map};
use crate::error::CatalogError;
use crate::ids::{DocClass, DocUrl};
use crate::view::resolve;
use crate::write::child_map;

/// Saved size that triggers a rollover.
pub const ROLLOVER_BYTES: usize = 2 * 1024 * 1024;
/// Saved size at which writers refuse non-essential writes until the rollover is done.
pub const HARD_CAP_BYTES: usize = 7 * 512 * 1024;
/// Tombstones younger than this survive a rollover (the documented maximum offline window).
pub const TOMBSTONE_KEEP_MS: i64 = 180 * 24 * 60 * 60 * 1000;
/// How long the rolling node sweeps late changes from the old document.
pub const SWEEP_MS: i64 = 7 * 24 * 60 * 60 * 1000;
/// When nodes drop the old document and retire its registration.
pub const RETIRE_MS: i64 = 30 * 24 * 60 * 60 * 1000;

/// Why a document rolls over.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Trigger {
    /// The saved size passed [`ROLLOVER_BYTES`].
    Size,
    /// More than half its nodes are deleted.
    Deleted,
}

/// The root containers of each class, created at genesis.
#[must_use]
pub const fn containers(class: DocClass) -> &'static [&'static str] {
    match class {
        DocClass::Constellation => &[
            "meta",
            "docs",
            "membershipOps",
            "members",
            "nodes",
            "agents",
            "settings",
            "ext",
        ],
        DocClass::Shell => &["entries", "pins", "archived", "devices", "ext"],
        DocClass::Agent => &[
            "conversations",
            "deleted",
            "subagentLinks",
            "memoryDivergences",
            "ext",
        ],
        DocClass::Notebook => &[],
    }
}

/// Whether a document should roll over, from its saved size and canonical view.
#[must_use]
pub fn trigger(class: DocClass, saved_len: usize, root: &Value) -> Option<Trigger> {
    if saved_len > ROLLOVER_BYTES {
        return Some(Trigger::Size);
    }
    if class == DocClass::Agent {
        let nodes: Vec<&Value> = root
            .get("conversations")
            .map(winner)
            .and_then(Value::as_object)
            .map(|map| map.values().collect())
            .unwrap_or_default();
        let deleted = nodes
            .iter()
            .filter(|node| {
                !winner(node)
                    .get("deletedAtMs")
                    .map(winner)
                    .is_none_or(Value::is_null)
            })
            .count();
        if !nodes.is_empty() && deleted * 2 > nodes.len() {
            return Some(Trigger::Deleted);
        }
    }
    None
}

/// The materialised view a successor's genesis holds: conflicts resolved (containers unioned),
/// `supersededBy` cleared, unknown keys kept, deleted leaf nodes folded into `deleted`, and
/// tombstones older than [`TOMBSTONE_KEEP_MS`] dropped.
///
/// # Panics
///
/// Never: only map values are unioned.
#[must_use]
pub fn materialize(class: DocClass, root: &Value, now_ms: i64) -> Map<String, Value> {
    let mut out = Map::new();
    let empty = Map::new();
    for (key, slot) in root.as_object().unwrap_or(&empty) {
        let values = values_of(slot);
        let merged = if values.len() > 1 && values.iter().all(|value| is_map(value)) {
            let mut union = Map::new();
            for value in values {
                for (entry, item) in value.as_object().expect("a map") {
                    union.entry(entry.clone()).or_insert_with(|| resolve(item));
                }
            }
            Value::Object(union)
        } else {
            resolve(slot)
        };
        out.insert(key.clone(), merged);
    }
    out.insert("supersededBy".into(), Value::Null);
    if class == DocClass::Agent {
        fold_deleted(&mut out, now_ms);
    }
    out
}

fn fold_deleted(root: &mut Map<String, Value>, now_ms: i64) {
    let mut deleted = root
        .get("deleted")
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();
    let mut conversations = root
        .get("conversations")
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();
    let parents: BTreeSet<String> = conversations
        .values()
        .filter_map(|node| {
            node.get("parentId")
                .and_then(Value::as_str)
                .map(str::to_owned)
        })
        .collect();
    let gone: Vec<(String, i64)> = conversations
        .iter()
        .filter(|(id, _)| !parents.contains(*id))
        .filter_map(|(id, node)| Some((id.clone(), node.get("deletedAtMs")?.as_i64()?)))
        .collect();
    for (id, at) in gone {
        conversations.remove(&id);
        deleted.insert(id, Value::from(at));
    }
    deleted.retain(|_, at| {
        at.as_i64()
            .is_some_and(|at| now_ms - at < TOMBSTONE_KEEP_MS)
    });
    root.insert("conversations".into(), Value::Object(conversations));
    root.insert("deleted".into(), Value::Object(deleted));
}

/// A successor document for `old`, written as one genesis change by `actor` at `now_ms`.
///
/// # Errors
///
/// An Automerge error.
pub fn successor(
    class: DocClass,
    old: &AutoCommit,
    actor: ActorId,
    now_ms: i64,
) -> Result<AutoCommit, CatalogError> {
    let view = materialize(class, &canonical_view(old), now_ms);
    let mut doc = AutoCommit::new().with_actor(actor);
    write_map(&mut doc, &automerge::ROOT, &view)?;
    doc.commit_with(automerge::transaction::CommitOptions::default().with_time(now_ms / 1000));
    Ok(doc)
}

/// Sets `supersededBy` in the old document.
///
/// # Errors
///
/// An Automerge error.
pub fn mark_superseded(old: &mut AutoCommit, successor: &DocUrl) -> Result<(), CatalogError> {
    old.put(automerge::ROOT, "supersededBy", successor.to_string())?;
    Ok(())
}

/// The keyed containers the late-change sweep copies, per class.
const fn sweep_keys(class: DocClass) -> &'static [&'static str] {
    match class {
        DocClass::Constellation => &["membershipOps", "members", "nodes", "agents"],
        DocClass::Shell => &["entries", "pins", "archived"],
        DocClass::Agent => &[
            "conversations",
            "deleted",
            "subagentLinks",
            "memoryDivergences",
        ],
        DocClass::Notebook => &[],
    }
}

/// Copies entries that appeared (or changed) in the old document after the rollover into the
/// successor. `rolled_at` is the old document's heads when the successor was made. Keyed ids make it
/// idempotent; a node tombstoned in the successor is not brought back. Returns how many entries were
/// copied.
///
/// # Errors
///
/// An Automerge error, or a missing container in the successor.
pub fn sweep(
    class: DocClass,
    old: &AutoCommit,
    rolled_at: &[automerge::ChangeHash],
    new: &mut AutoCommit,
) -> Result<usize, CatalogError> {
    let old_view = canonical_view(old);
    let before = canonical_view(&old.clone().fork_at(rolled_at)?);
    let new_view = canonical_view(&*new);
    let tombstones: BTreeSet<String> = new_view
        .get("deleted")
        .and_then(Value::as_object)
        .map(|map| map.keys().cloned().collect())
        .unwrap_or_default();
    let mut copied = 0;
    for key in sweep_keys(class) {
        let Some(old_map) = old_view.get(*key).map(winner).and_then(Value::as_object) else {
            continue;
        };
        let new_keys: BTreeSet<String> = new_view
            .get(*key)
            .and_then(Value::as_object)
            .map(|map| map.keys().cloned().collect())
            .unwrap_or_default();
        let target = child_map(&*new, &automerge::ROOT, key)
            .ok_or_else(|| CatalogError::Schema(format!("successor lacks {key}")))?;
        for (entry, item) in old_map {
            let unchanged = before.get(*key).map(winner).and_then(|m| m.get(entry)) == Some(item);
            if unchanged
                || new_keys.contains(entry)
                || (*key == "conversations" && tombstones.contains(entry))
            {
                continue;
            }
            let mut single = Map::new();
            single.insert(entry.clone(), resolve(item));
            write_map(new, &target, &single)?;
            copied += 1;
        }
    }
    Ok(copied)
}

/// C1's `catalog_root_move` op for a constellation rollover, for the admin to sign.
#[must_use]
pub fn root_move(header: OpHeader, new_root: DocId, prev_root: DocId) -> Op {
    Op {
        header,
        payload: OpPayload::CatalogRootMove {
            new_root: CatalogRoot(*new_root.as_bytes()),
            prev_root: CatalogRoot(*prev_root.as_bytes()),
        },
        extensions: std::collections::BTreeMap::new(),
    }
}

/// Repairs every missing or mistyped root container of `class`: finds the newest heads at which
/// it was a map, creates a fresh map at the key and copies the entries valid then. A container
/// that never existed is created empty. Returns the repaired keys.
///
/// # Errors
///
/// An Automerge error.
pub fn repair(class: DocClass, doc: &mut AutoCommit) -> Result<Vec<String>, CatalogError> {
    let mut repaired = Vec::new();
    for key in containers(class) {
        if child_map(&*doc, &automerge::ROOT, key).is_some() {
            continue;
        }
        let changes: Vec<automerge::ChangeHash> = doc
            .get_changes(&[])
            .iter()
            .map(automerge::Change::hash)
            .collect();
        let mut entries = Map::new();
        for hash in changes.iter().rev() {
            if let Ok(Some((automerge::Value::Object(ObjType::Map), _))) =
                doc.get_at(automerge::ROOT, *key, &[*hash])
            {
                let past = doc.fork_at(&[*hash])?;
                let view = canonical_view(&past);
                if let Some(map) = view
                    .get(*key)
                    .map(resolve)
                    .and_then(|v| v.as_object().cloned())
                {
                    entries = map;
                }
                break;
            }
        }
        let fresh = doc.put_object(automerge::ROOT, *key, ObjType::Map)?;
        write_map(doc, &fresh, &entries)?;
        repaired.push((*key).to_owned());
    }
    Ok(repaired)
}
