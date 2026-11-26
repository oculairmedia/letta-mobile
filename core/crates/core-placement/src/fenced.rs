//! The fenced-holder report (C1 §5.9): a stopped old holder tells the new holder where its segment
//! ended, and the new holder decides what to record.
//!
//! The old holder sends `fenced {log_id, epoch, head}` and pushes its whole segment to every peer.
//! The new holder checks the report against its own log: the stranded entries become a divergent
//! tail by evaluation alone (fencing changes classification, never content), and the holder writes
//! the `divergence` entry for every divergence of that epoch it has not recorded yet.

use core_log::{Analysis, Divergence, Pos};
use core_proto::{Epoch, NodeId};
use core_store::{LogId, LogStore, StoreError};

use crate::record::PlacementRecord;

/// `fenced {log_id, epoch, head}`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FencedReport {
    /// The agent log.
    pub log_id: LogId,
    /// The fenced holder's epoch.
    pub epoch: Epoch,
    /// The head of its segment.
    pub head: Pos,
}

/// The report a node that held `held` (with segment head `head`) sends once its record shows a
/// higher epoch (F4 fired), or `None` while it is still the holder.
#[must_use]
pub fn report_if_fenced(
    record: &PlacementRecord,
    held: Epoch,
    segment_head: Pos,
) -> Option<FencedReport> {
    let fenced = record.epoch > held || record.max_n_seen > held.n;
    fenced.then_some(FencedReport {
        log_id: record.log_id,
        epoch: held,
        head: segment_head,
    })
}

/// What the receiving holder makes of a report.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FencedAssessment {
    /// The sender is not the claimant of the reported epoch: ignored.
    NotClaimant,
    /// The reported epoch is not below the canonical epoch: nothing is fenced, ignored.
    NotLower,
    /// The reported head is not stored yet: fetch the segment (`want` from this epoch), then assess
    /// again.
    NeedEntries {
        /// The epoch to want.
        epoch: Epoch,
    },
    /// The reported head is on the canonical line: the old holder stopped cleanly (a seal, or a
    /// claim parented at its head). No divergence.
    Clean,
    /// Divergences of the reported epoch that this holder still has to record, and those already
    /// recorded.
    Divergent {
        /// Not yet recorded: write a `divergence` entry for each.
        to_record: Vec<Divergence>,
        /// Already recorded.
        recorded: usize,
    },
}

/// Assesses a report received from `sender`, against this node's analysis of the log.
///
/// # Errors
///
/// A store error.
pub fn assess<S: LogStore + ?Sized>(
    report: &FencedReport,
    sender: &NodeId,
    analysis: &Analysis,
    store: &S,
) -> Result<FencedAssessment, StoreError> {
    if report.epoch.claimant != *sender {
        return Ok(FencedAssessment::NotClaimant);
    }
    let Some(canonical) = analysis.canonical else {
        return Ok(FencedAssessment::NeedEntries {
            epoch: report.epoch,
        });
    };
    if report.epoch >= canonical {
        return Ok(FencedAssessment::NotLower);
    }
    let stored = store
        .entry(&report.head.hash)?
        .filter(|e| e.log_id == report.log_id && e.seq == report.head.seq);
    if stored.is_none() {
        return Ok(FencedAssessment::NeedEntries {
            epoch: report.epoch,
        });
    }
    if analysis
        .line
        .iter()
        .any(|e| e.entry_hash == report.head.hash)
    {
        return Ok(FencedAssessment::Clean);
    }
    let mut to_record = Vec::new();
    let mut recorded = 0;
    for divergence in analysis
        .divergences
        .iter()
        .filter(|d| d.inputs.stranded_epoch == report.epoch)
    {
        let done = store
            .divergence(&divergence.inputs.id())?
            .is_some_and(|r| r.recorded_entry.is_some());
        if done {
            recorded += 1;
        } else {
            to_record.push(divergence.clone());
        }
    }
    Ok(FencedAssessment::Divergent {
        to_record,
        recorded,
    })
}
