//! Payload pruning that keeps the chain intact (C1 §3.9), and whole-log verification.
//!
//! Headers are kept for ever. Only compactable payloads on the canonical line are pruned; divergent
//! tails are evidence and keep every payload.

use std::collections::{BTreeMap, BTreeSet};

use core_proto::ConversationId;
use core_store::{EntryHash, LogStore, PayloadState, StoredEntry};

use crate::agent_log::{AgentLog, LogError, load_payload};
use crate::hashes::payload_hash;
use crate::header::SignedEntry;
use crate::judge::ClaimJudge;
use crate::payload::{Payload, kind};

/// Feed v1 retention per conversation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FeedRetention {
    /// Entries kept per conversation.
    pub max_entries: usize,
    /// Payload bytes kept per conversation.
    pub max_bytes: u64,
}

impl Default for FeedRetention {
    fn default() -> Self {
        Self {
            max_entries: 1_000,
            max_bytes: 4 * 1024 * 1024,
        }
    }
}

/// The feed entry kind that closes a turn.
pub const TURN_CLOSED: &str = "turn_closed";

fn close_runs(puts: &mut BTreeMap<String, Vec<Vec<EntryHash>>>) {
    for runs in puts.values_mut() {
        runs.push(Vec::new());
    }
}

/// Prunes compactable payloads of the canonical line:
/// - a `file_put` when a later `file_put` of the same path exists, both are at or below the latest
///   `checkpoint`, and no `file_append` to that path lies between them (so a materializer that
///   skips the pruned entry folds the same bytes);
/// - a `feed_append` named by a later one's `replaces_feed_seq`, and, per conversation, the oldest
///   entries of finished turns beyond `retention`. A running turn is never pruned.
///
/// Returns the entries pruned.
///
/// # Errors
///
/// A store error.
#[allow(clippy::too_many_lines)]
pub fn prune<S: LogStore + ?Sized, J: ClaimJudge + ?Sized>(
    log: &AgentLog<'_, J>,
    store: &mut S,
    retention: FeedRetention,
) -> Result<Vec<EntryHash>, LogError> {
    let line = log.canonical_line(store)?;
    let checkpoint = line
        .iter()
        .rev()
        .find(|e| e.kind == kind::CHECKPOINT)
        .map(|e| e.seq);
    let mut victims: BTreeSet<EntryHash> = BTreeSet::new();
    // Per path: runs of file_puts with no file_append between them, at or below the checkpoint.
    let mut puts: BTreeMap<String, Vec<Vec<EntryHash>>> = BTreeMap::new();
    // Per conversation: (feed_seq, entry hash, turn, payload bytes) in line order.
    let mut feed: BTreeMap<ConversationId, Vec<(u64, EntryHash, String, u64)>> = BTreeMap::new();
    let mut closed: BTreeSet<(ConversationId, String)> = BTreeSet::new();
    let mut replaced: Vec<(ConversationId, u64)> = Vec::new();
    for entry in &line {
        if entry.kind == kind::FILE_APPEND || entry.kind == kind::FILE_DELETE {
            match load_payload(store, entry)? {
                Some(Payload::FileAppend { path, .. } | Payload::FileDelete { path }) => {
                    puts.entry(path).or_default().push(Vec::new());
                }
                // A file_append whose payload is not held cannot be attributed: stop every run.
                _ => close_runs(&mut puts),
            }
            continue;
        }
        if entry.kind == kind::FILE_PUT && entry.payload_state != PayloadState::Stored {
            // Not held (or already pruned): its path is unknown, so close every run.
            if entry.payload_state == PayloadState::Missing {
                close_runs(&mut puts);
            }
            continue;
        }
        if !entry.is_compactable() || entry.payload_state != PayloadState::Stored {
            if entry.kind == kind::FEED_APPEND
                && let Some(Payload::FeedAppend {
                    conversation_id,
                    turn,
                    entry_kind,
                    ..
                }) = load_payload(store, entry)?
                && entry_kind == TURN_CLOSED
            {
                closed.insert((conversation_id, turn));
            }
            continue;
        }
        match load_payload(store, entry)? {
            Some(Payload::FilePut { path, .. }) if checkpoint.is_some_and(|c| entry.seq <= c) => {
                let runs = puts.entry(path).or_default();
                if runs.is_empty() {
                    runs.push(Vec::new());
                }
                if let Some(run) = runs.last_mut() {
                    run.push(entry.entry_hash);
                }
            }
            Some(Payload::FilePut { path, .. }) => {
                // Above the checkpoint: it supersedes nothing yet, and nothing after it is pruned.
                puts.entry(path).or_default().push(Vec::new());
            }
            Some(Payload::FeedAppend {
                conversation_id,
                feed_seq,
                turn,
                entry_kind,
                replaces_feed_seq,
                ..
            }) => {
                if entry_kind == TURN_CLOSED {
                    closed.insert((conversation_id.clone(), turn.clone()));
                }
                if let Some(old) = replaces_feed_seq {
                    replaced.push((conversation_id.clone(), old));
                }
                feed.entry(conversation_id).or_default().push((
                    feed_seq,
                    entry.entry_hash,
                    turn,
                    entry.payload_len,
                ));
            }
            _ => {}
        }
    }
    for runs in puts.values() {
        for run in runs {
            victims.extend(run.iter().take(run.len().saturating_sub(1)));
        }
    }
    for (conversation, old) in &replaced {
        if let Some(rows) = feed.get(conversation) {
            victims.extend(rows.iter().filter(|r| r.0 == *old).map(|r| r.1));
        }
    }
    for (conversation, rows) in &feed {
        let mut count = rows.iter().filter(|r| !victims.contains(&r.1)).count();
        let mut bytes: u64 = rows
            .iter()
            .filter(|r| !victims.contains(&r.1))
            .map(|r| r.3)
            .sum();
        for row in rows {
            if count <= retention.max_entries && bytes <= retention.max_bytes {
                break;
            }
            if victims.contains(&row.1) || !closed.contains(&(conversation.clone(), row.2.clone()))
            {
                continue;
            }
            victims.insert(row.1);
            count -= 1;
            bytes -= row.3;
        }
    }
    for hash in &victims {
        store.prune_payload(hash)?;
    }
    Ok(victims.into_iter().collect())
}

/// What [`verify_log`] checked.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct VerifyReport {
    /// Entries whose signature and chain link verified.
    pub entries: usize,
    /// Payloads held and matching their hash.
    pub payloads: usize,
    /// Payloads legitimately pruned.
    pub pruned: usize,
    /// Payloads not held.
    pub missing: usize,
}

/// Re-verifies every stored entry of the log: signature, chain link to its parent, and each held
/// payload's hash. A pruned payload is fine for a compactable entry and never breaks the chain.
///
/// # Errors
///
/// The first [`LogError`] found.
pub fn verify_log<S: LogStore + ?Sized>(
    store: &S,
    log_id: &core_store::LogId,
) -> Result<VerifyReport, LogError> {
    let mut report = VerifyReport::default();
    for segment in store.segments(log_id)? {
        for entry in store.segment_entries(log_id, &segment.epoch, 0, usize::MAX)? {
            verify_entry(store, &entry, &mut report)?;
        }
    }
    Ok(report)
}

fn verify_entry<S: LogStore + ?Sized>(
    store: &S,
    entry: &StoredEntry,
    report: &mut VerifyReport,
) -> Result<(), LogError> {
    let signed = SignedEntry::from_stored(entry)?;
    if signed.hash() != entry.entry_hash {
        return Err(LogError::ChainMismatch("stored hash differs".into()));
    }
    if entry.seq > 0 {
        let parent = store
            .entry(&entry.prev)?
            .ok_or_else(|| LogError::ChainMismatch("parent missing".into()))?;
        if parent.seq + 1 != entry.seq
            || (entry.kind == kind::EPOCH_OPEN) == (parent.epoch == entry.epoch)
        {
            return Err(LogError::ChainMismatch("bad link".into()));
        }
    }
    match entry.payload_state {
        PayloadState::Stored => {
            let bytes = store
                .blob(&entry.payload_hash)?
                .ok_or_else(|| LogError::ChainMismatch("stored payload missing".into()))?;
            if payload_hash(&bytes) != entry.payload_hash {
                return Err(LogError::PayloadMismatch);
            }
            report.payloads += 1;
        }
        PayloadState::Pruned => {
            if !entry.is_compactable() {
                return Err(LogError::PayloadMismatch);
            }
            report.pruned += 1;
        }
        PayloadState::Missing => report.missing += 1,
    }
    report.entries += 1;
    Ok(())
}
