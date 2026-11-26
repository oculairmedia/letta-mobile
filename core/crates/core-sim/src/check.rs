//! The invariant checker (C1 §8): the safety invariants after every step, and the convergence
//! invariants at quiescence, per connected component of up, mutually admitted nodes.
//!
//! Checked after every step, on every up node:
//! - **L1** every stored entry verifies against its epoch's claimant (new entries since the last
//!   check);
//! - **L2** the canonical line is contiguous from seq 0 and hash-chained;
//! - **L3** the set of stored headers only grows (a restore from backup resets the baseline);
//! - **L5** each segment's `complete_seq` never decreases;
//! - **L6** every entry of a segment up to its `complete_seq` has its payload;
//! - **P1** two nodes pass the F4 gate at different epochs only while partitioned (or within a
//!   grace period after the topology last changed).
//!
//! Checked at quiescence, per component:
//! - **M7, M9** every node holds the same op set and evaluates the same view (catalog root
//!   included);
//! - **L4** every node holds the same entry headers and the same canonical tip;
//! - **P2** every node derives the same placement record;
//! - **P1** at most one node passes the F4 gate per agent;
//! - **L9, D5** every node computes the same divergences (ids and inputs), and every stored entry
//!   off the canonical line belongs to a divergence tail;
//! - **L8, L10, L12** the canonical line folds without error (no divergent tail is applied), every
//!   replicated path is on the allowlist, and every node folds the same manifest.

use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;

use core_log::{AgentLog, Analysis, Payload, SignedEntry, allowlist};
use core_membership::{MembershipView, OpId};
use core_placement::PlacementRecord;
use core_proto::Epoch;
use core_store::{DivergenceId, EntryHash, LogId, Segment, Store, StoredEntry};

use crate::actor::payload;
use crate::world::{Node, World};

/// A broken invariant.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Violation {
    /// The invariant's name in C1 §8.
    pub invariant: &'static str,
    /// What was observed.
    pub detail: String,
}

impl std::fmt::Display for Violation {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.invariant, self.detail)
    }
}

/// One log as one node holds it.
#[derive(Clone, Debug)]
pub(crate) struct LogView {
    pub(crate) entries: BTreeMap<EntryHash, StoredEntry>,
    pub(crate) segments: Vec<Segment>,
    pub(crate) analysis: Analysis,
    pub(crate) record: Option<PlacementRecord>,
    pub(crate) passes_gate: bool,
}

/// One node's state, read in one snapshot.
#[derive(Clone, Debug)]
pub(crate) struct NodeView {
    pub(crate) ops: BTreeSet<OpId>,
    pub(crate) view: Option<MembershipView>,
    pub(crate) logs: BTreeMap<LogId, LogView>,
}

/// Reads node `name`'s state.
pub(crate) fn snapshot(node: &Node) -> NodeView {
    let judge = Rc::clone(&node.judge);
    let (ops, resumed) = {
        let st = node.st.borrow();
        (
            st.members
                .ops()
                .map(core_membership::SignedOp::id)
                .collect(),
            st.resumed,
        )
    };
    let logs = node
        .store()
        .read(|txn| {
            let mut logs = BTreeMap::new();
            for log in txn.logs()? {
                let agent_log = AgentLog::new(log, &*judge);
                let segments = txn.segments(&log)?;
                let mut entries = BTreeMap::new();
                for segment in &segments {
                    for entry in txn.segment_entries(&log, &segment.epoch, 0, usize::MAX)? {
                        entries.insert(entry.entry_hash, entry);
                    }
                }
                let analysis = agent_log.analyze(txn)?;
                let max_n = txn.max_n_seen(&log)?.unwrap_or(0);
                let record = PlacementRecord::from_analysis(log, &analysis, max_n);
                let passes_gate =
                    resumed && record.as_ref().is_some_and(|r| r.may_append(&node.id));
                logs.insert(
                    log,
                    LogView {
                        entries,
                        segments,
                        analysis,
                        record,
                        passes_gate,
                    },
                );
            }
            Ok::<_, core_log::LogError>(logs)
        })
        .unwrap_or_default();
    NodeView {
        ops,
        view: node.judge.view(),
        logs,
    }
}

/// What the checker remembers of each node between checks.
#[derive(Clone, Debug, Default)]
pub(crate) struct History {
    headers: BTreeSet<EntryHash>,
    complete: BTreeMap<(LogId, Epoch), u64>,
}

/// The checker's memory across steps.
#[derive(Debug, Default)]
pub(crate) struct Checker {
    history: BTreeMap<u8, History>,
    /// Pairs of nodes seen passing the gate at different epochs while connected, with the time
    /// first seen.
    split_since: BTreeMap<(u8, u8), u64>,
}

/// How long two connected nodes may both pass the gate at different epochs before P1 fails: the
/// time for a redial and a catch-up after a heal or restart.
pub(crate) const P1_GRACE_MS: u64 = 30_000;

impl Checker {
    /// Forgets node `name`'s history (its store was restored from a backup).
    pub(crate) fn reset(&mut self, name: u8) {
        self.history.remove(&name);
    }

    /// The safety invariants on every up node. `connected` tells whether two nodes can reach each
    /// other now.
    pub(crate) fn safety(
        &mut self,
        world: &World,
        now: u64,
        connected: &dyn Fn(u8, u8) -> bool,
    ) -> Vec<Violation> {
        let mut violations = Vec::new();
        let mut views = BTreeMap::new();
        for (name, node) in &world.nodes {
            let view = snapshot(node);
            self.node_safety(*name, &view, &mut violations);
            if node.is_up() {
                views.insert(*name, view);
            }
        }
        // P1: gate passers at different epochs.
        let mut seen = BTreeSet::new();
        for (a, va) in &views {
            for (b, vb) in views.range(a + 1..) {
                for (log, la) in &va.logs {
                    let Some(lb) = vb.logs.get(log) else { continue };
                    if la.passes_gate && lb.passes_gate {
                        let (ea, eb) = (
                            la.record.as_ref().map(|r| r.epoch),
                            lb.record.as_ref().map(|r| r.epoch),
                        );
                        if ea == eb {
                            violations.push(Violation {
                                invariant: "P1",
                                detail: format!(
                                    "nodes {a} and {b} pass the gate at one epoch {ea:?}"
                                ),
                            });
                        } else if connected(*a, *b) {
                            let since = *self.split_since.entry((*a, *b)).or_insert(now);
                            seen.insert((*a, *b));
                            if now.saturating_sub(since) > P1_GRACE_MS {
                                violations.push(Violation {
                                    invariant: "P1",
                                    detail: format!(
                                        "nodes {a} ({ea:?}) and {b} ({eb:?}) both pass the gate while connected for {} ms",
                                        now - since
                                    ),
                                });
                            }
                        }
                    }
                }
            }
        }
        self.split_since.retain(|pair, _| seen.contains(pair));
        violations
    }

    fn node_safety(&mut self, name: u8, view: &NodeView, violations: &mut Vec<Violation>) {
        let history = self.history.entry(name).or_default();
        let mut headers = BTreeSet::new();
        let mut complete = BTreeMap::new();
        for (log, lv) in &view.logs {
            for (hash, entry) in &lv.entries {
                headers.insert(*hash);
                if !history.headers.contains(hash) {
                    match SignedEntry::from_stored(entry) {
                        Ok(signed) if signed.header().epoch == entry.epoch => {}
                        other => violations.push(Violation {
                            invariant: "L1",
                            detail: format!(
                                "node {name}: entry {hash:?} does not verify: {other:?}"
                            ),
                        }),
                    }
                }
            }
            // L2.
            for (i, entry) in lv.analysis.line.iter().enumerate() {
                let contiguous = entry.seq == i as u64;
                let chained = i == 0 && entry.prev == EntryHash::ZERO
                    || i > 0 && entry.prev == lv.analysis.line[i - 1].entry_hash;
                if !contiguous || !chained {
                    violations.push(Violation {
                        invariant: "L2",
                        detail: format!("node {name}: canonical line breaks at index {i}"),
                    });
                    break;
                }
            }
            // An equivocation (two entries by one claimant at one seq) moves its segment to the
            // lower-hash branch (C1 §3.8), which may be shorter: L5 is about reconnection, so
            // such segments are left out of it.
            let equivocated: BTreeSet<Epoch> = lv
                .analysis
                .divergences
                .iter()
                .filter(|d| d.inputs.equivocation)
                .map(|d| d.inputs.stranded_epoch)
                .collect();
            for segment in &lv.segments {
                let Some(seq) = segment.complete_seq else {
                    continue;
                };
                complete.insert((*log, segment.epoch), seq);
                // L5.
                if let Some(before) = history.complete.get(&(*log, segment.epoch))
                    && seq < *before
                    && !equivocated.contains(&segment.epoch)
                {
                    violations.push(Violation {
                        invariant: "L5",
                        detail: format!(
                            "node {name}: complete_seq of {:?} fell from {before} to {seq}",
                            segment.epoch
                        ),
                    });
                }
                // L6: the segment's chain from its head back to its start.
                let mut cursor = lv.entries.get(&segment.head_hash);
                while let Some(entry) = cursor {
                    if entry.seq <= seq && !entry.payload_complete() {
                        violations.push(Violation {
                            invariant: "L6",
                            detail: format!(
                                "node {name}: complete_seq {seq} of {:?} covers seq {} without its payload",
                                segment.epoch, entry.seq
                            ),
                        });
                        break;
                    }
                    if entry.seq <= segment.start_seq {
                        break;
                    }
                    cursor = lv.entries.get(&entry.prev);
                }
            }
        }
        // L3.
        let lost: Vec<&EntryHash> = history.headers.difference(&headers).collect();
        if !lost.is_empty() {
            violations.push(Violation {
                invariant: "L3",
                detail: format!("node {name}: {} stored headers disappeared", lost.len()),
            });
        }
        *history = History { headers, complete };
    }
}

/// The convergence invariants within one component (names of up, connected nodes).
pub(crate) fn quiescent(world: &World, component: &[u8]) -> Vec<Violation> {
    let mut violations = Vec::new();
    let views: Vec<(u8, NodeView)> = component
        .iter()
        .map(|name| (*name, snapshot(world.node(*name))))
        .collect();
    let Some((first, reference)) = views.first() else {
        return violations;
    };
    for (name, view) in &views[1..] {
        compare(*first, reference, *name, view, &mut violations);
    }
    let mut passers: BTreeMap<LogId, Vec<u8>> = BTreeMap::new();
    let mut manifests: BTreeMap<LogId, Vec<(u8, Vec<core_log::payload::ManifestEntry>)>> =
        BTreeMap::new();
    for (name, view) in &views {
        let node = world.node(*name);
        for (log, lv) in &view.logs {
            if lv.passes_gate {
                passers.entry(*log).or_default().push(*name);
            }
            let fold = log_checks(*name, node, lv, &mut violations);
            let complete = lv.analysis.line.iter().all(StoredEntry::payload_complete);
            if complete {
                manifests
                    .entry(*log)
                    .or_default()
                    .push((*name, fold.manifest()));
            }
        }
    }
    for (log, names) in passers {
        if names.len() > 1 {
            violations.push(Violation {
                invariant: "P1",
                detail: format!("log {log:?}: nodes {names:?} all pass the gate in one component"),
            });
        }
    }
    for (log, list) in manifests {
        if let Some((a, ma)) = list.first() {
            for (b, mb) in &list[1..] {
                if ma != mb {
                    violations.push(Violation {
                        invariant: "L12",
                        detail: format!(
                            "log {log:?}: nodes {a} and {b} materialize different manifests"
                        ),
                    });
                }
            }
        }
    }
    violations
}

/// L9, L8 and L10 on one log of one node; returns the canonical line's fold.
fn log_checks(
    name: u8,
    node: &Node,
    lv: &LogView,
    violations: &mut Vec<Violation>,
) -> core_log::fold::StateFold {
    // L9: every entry off the canonical line is in a divergence tail.
    let on_line: BTreeSet<EntryHash> = lv.analysis.line.iter().map(|e| e.entry_hash).collect();
    let in_tails: BTreeSet<EntryHash> = lv
        .analysis
        .divergences
        .iter()
        .flat_map(|d| d.tail.iter().copied())
        .collect();
    // An off-line branch competes with the line where it leaves it; a branch that only
    // extends past the canonical tip (an invalid tip with nothing above it yet) has no
    // competitor and is not a divergence.
    let root_seq = |start: &StoredEntry| -> Option<u64> {
        let mut entry = start.clone();
        loop {
            if on_line.contains(&entry.prev) {
                return Some(entry.seq);
            }
            entry = lv.entries.get(&entry.prev)?.clone();
        }
    };
    let line_len = lv.analysis.line.len() as u64;
    let stray = lv
        .entries
        .iter()
        .filter(|(h, _)| !on_line.contains(h) && !in_tails.contains(h))
        .filter(|(_, e)| root_seq(e).is_some_and(|seq| seq < line_len))
        .count();
    if stray > 0 {
        violations.push(Violation {
            invariant: "L9",
            detail: format!(
                "node {name}: {stray} entries are neither canonical nor in a divergence"
            ),
        });
    }
    // L8, L10, L12.
    let mut fold = core_log::fold::StateFold::new();
    for entry in &lv.analysis.line {
        let Some(p) = payload(node, entry) else {
            continue;
        };
        if let Payload::FilePut { path, .. } | Payload::FileAppend { path, .. } = &p
            && !allowlist::is_replicated(path)
        {
            violations.push(Violation {
                invariant: "L8",
                detail: format!("node {name}: replicated path {path} is off the allowlist"),
            });
        }
        if let Err(error) = fold.apply(&p) {
            violations.push(Violation {
                invariant: "L10",
                detail: format!(
                    "node {name}: the canonical line does not fold at seq {}: {error}",
                    entry.seq
                ),
            });
            break;
        }
    }
    if !fold.alarms().is_empty() {
        violations.push(Violation {
            invariant: "L8",
            detail: format!("node {name}: fold alarms {:?}", fold.alarms()),
        });
    }
    fold
}

/// M7, L4, P2 and D5 between two nodes of one component.
fn compare(
    first: u8,
    reference: &NodeView,
    name: u8,
    view: &NodeView,
    violations: &mut Vec<Violation>,
) {
    if view.ops != reference.ops || view.view != reference.view {
        violations.push(Violation {
            invariant: "M7",
            detail: format!(
                "nodes {first} and {name} differ in membership ({} vs {} ops)",
                reference.ops.len(),
                view.ops.len()
            ),
        });
    }
    let logs: BTreeSet<&LogId> = reference.logs.keys().chain(view.logs.keys()).collect();
    for log in logs {
        let (Some(a), Some(b)) = (reference.logs.get(log), view.logs.get(log)) else {
            violations.push(Violation {
                invariant: "L4",
                detail: format!("nodes {first} and {name}: only one holds log {log:?}"),
            });
            continue;
        };
        let ka: BTreeSet<_> = a.entries.keys().collect();
        let kb: BTreeSet<_> = b.entries.keys().collect();
        if ka != kb {
            violations.push(Violation {
                invariant: "L4",
                detail: format!(
                    "nodes {first} and {name} hold different headers ({} vs {})",
                    ka.len(),
                    kb.len()
                ),
            });
        }
        if a.analysis.line.last().map(|e| e.entry_hash)
            != b.analysis.line.last().map(|e| e.entry_hash)
        {
            violations.push(Violation {
                invariant: "L4",
                detail: format!("nodes {first} and {name} have different canonical tips"),
            });
        }
        if a.record != b.record {
            violations.push(Violation {
                invariant: "P2",
                detail: format!(
                    "nodes {first} and {name} derive different placement: {:?} vs {:?}",
                    a.record, b.record
                ),
            });
        }
        let da: BTreeMap<DivergenceId, _> = a
            .analysis
            .divergences
            .iter()
            .map(|d| (d.inputs.id(), &d.inputs))
            .collect();
        let db: BTreeMap<DivergenceId, _> = b
            .analysis
            .divergences
            .iter()
            .map(|d| (d.inputs.id(), &d.inputs))
            .collect();
        if da != db {
            violations.push(Violation {
                invariant: "D5",
                detail: format!(
                    "nodes {first} and {name} compute different divergences ({} vs {})",
                    da.len(),
                    db.len()
                ),
            });
        }
    }
}
