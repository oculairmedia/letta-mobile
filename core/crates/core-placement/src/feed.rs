//! Feed v1 placement amendment (C1 §7): the feed epoch as a continuity line, `cursor.line`,
//! serving from a lagging replica, and the `not_holder` / `moved` refusals with a placement object.
//!
//! The JSON shapes are serde types (the feed is a JSON protocol); unknown fields are ignored on
//! both sides, as feed v1 already requires.

use core_log::payload::kind;
use core_log::{Payload, Reason};
use core_proto::Epoch;
use core_proto::crypto::blake3_hash;
use core_store::{EntryHash, LogStore, PlacementState, StoreError, StoredEntry};
use serde::{Deserialize, Serialize};

use crate::record::PlacementRecord;

/// The auth capability an amended host advertises beside `conversation_feed_v1`.
pub const CAPABILITY: &str = "conversation_feed_placement_v1";

/// The retry hint sent with a `moving` refusal.
pub const MOVING_RETRY_AFTER_MS: u64 = 2_000;

/// `feed_epoch = (n << 21) | (u32_be(blake3(claimant)[0..4]) >> 11)`: below 2^53 for every
/// `n ≤ 2^32 − 1`, and growing with `n`.
#[must_use]
pub fn feed_epoch(epoch: &Epoch) -> u64 {
    let digest = blake3_hash(epoch.claimant.as_bytes());
    let bits = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) >> 11;
    (epoch.n << 21) | u64::from(bits)
}

/// Whether an `epoch_open` of this reason starts a new continuity line (genesis, failover,
/// takeover, forced takeover). Moves and rekeys keep the feed epoch.
#[must_use]
pub const fn breaks_continuity(reason: Reason) -> bool {
    matches!(
        reason,
        Reason::Genesis | Reason::Failover | Reason::Takeover | Reason::TakeoverForced
    )
}

/// The epoch whose feed epoch the canonical `line` serves: that of its most recent `epoch_open`
/// that breaks continuity. Opens whose payload is not held are skipped.
///
/// # Errors
///
/// A store error.
pub fn continuity_epoch<S: LogStore + ?Sized>(
    line: &[StoredEntry],
    store: &S,
) -> Result<Option<Epoch>, StoreError> {
    for entry in line.iter().rev().filter(|e| e.kind == kind::EPOCH_OPEN) {
        let Some(bytes) = store.blob(&entry.payload_hash)? else {
            continue;
        };
        if let Ok(Payload::EpochOpen(open)) = Payload::decode(entry.kind, &bytes)
            && breaks_continuity(open.reason)
        {
            return Ok(Some(entry.epoch));
        }
    }
    Ok(None)
}

/// `cursor.line`: hex of the first 8 bytes of the entry hash of the log entry holding the
/// cursor's feed entry.
#[must_use]
pub fn cursor_line(entry: &EntryHash) -> String {
    hex::encode(&entry.as_bytes()[..8])
}

/// A feed cursor with the optional `line`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FeedCursor {
    /// The feed epoch.
    pub epoch: u64,
    /// The seq.
    pub seq: u64,
    /// `cursor.line`, when the host sent it and the client echoes it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub line: Option<String>,
}

/// A page's resync reason, including the amendment's `line_changed`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ResyncReason {
    /// The caller named no cursor.
    NoCursor,
    /// The feed epoch differs.
    EpochChanged,
    /// The cursor is below retention.
    BehindHorizon,
    /// The cursor is above the head and the line cannot explain it.
    AheadOfHead,
    /// Same epoch and seq, different `line`.
    LineChanged,
}

/// What this node's journal for one conversation looks like.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JournalView {
    /// The feed epoch of the line this node serves.
    pub feed_epoch: u64,
    /// The lowest retained seq.
    pub base_seq: u64,
    /// The journal head.
    pub head_seq: u64,
    /// The `cursor.line` of the entry at the cursor's seq, when this node holds it.
    pub line_at_cursor: Option<String>,
    /// Whether this node holds the agent (a holder above its own head lost data; a replica lags).
    pub is_holder: bool,
}

/// How to answer a `feed.subscribe` read.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CursorVerdict {
    /// `mode: replay` from the cursor.
    Replay,
    /// `mode: replay` with no entries, the client's own cursor and `"lagging": true`.
    Lagging,
    /// `mode: snapshot` with this reason.
    Snapshot(ResyncReason),
}

/// Classifies a cursor against this node's journal (feed v1 plus C1 §7.3 and §7.5).
#[must_use]
pub fn classify(cursor: Option<&FeedCursor>, journal: &JournalView) -> CursorVerdict {
    let Some(cursor) = cursor else {
        return CursorVerdict::Snapshot(ResyncReason::NoCursor);
    };
    if cursor.epoch != journal.feed_epoch {
        return CursorVerdict::Snapshot(ResyncReason::EpochChanged);
    }
    if cursor.seq < journal.base_seq {
        return CursorVerdict::Snapshot(ResyncReason::BehindHorizon);
    }
    if cursor.seq > journal.head_seq {
        return if journal.is_holder {
            CursorVerdict::Snapshot(ResyncReason::AheadOfHead)
        } else {
            CursorVerdict::Lagging
        };
    }
    match (&cursor.line, &journal.line_at_cursor) {
        (Some(theirs), Some(ours)) if theirs != ours => {
            CursorVerdict::Snapshot(ResyncReason::LineChanged)
        }
        _ => CursorVerdict::Replay,
    }
}

/// An epoch as JSON: `{"n": 7, "claimant": "<hex>"}`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EpochJson {
    /// The epoch number.
    pub n: u64,
    /// The claimant, hex.
    pub claimant: String,
}

impl From<&Epoch> for EpochJson {
    fn from(epoch: &Epoch) -> Self {
        Self {
            n: epoch.n,
            claimant: epoch.claimant.to_hex(),
        }
    }
}

/// The `placement` object of refusals and pages.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementJson {
    /// The agent.
    pub agent_id: String,
    /// The holder, hex, or null.
    pub holder_node_id: Option<String>,
    /// The canonical epoch.
    pub epoch: EpochJson,
    /// `active`, `moving`, `placeless` or `superseded_unknown`.
    pub state: String,
    /// Present only for `moving`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retry_after_ms: Option<u64>,
}

impl PlacementJson {
    /// The object for `record`. `moving` is set when the holder reports a move in progress
    /// (its gate is `moving`); a seal naming a target that has not opened is also `moving`, with
    /// the target as holder.
    #[must_use]
    pub fn of(record: &PlacementRecord, moving: bool) -> Self {
        let superseded = record.max_n_seen > record.epoch.n;
        let (holder, state) = match record.state {
            _ if superseded => (None, "superseded_unknown"),
            PlacementState::Active if moving => (Some(record.epoch.claimant), "moving"),
            PlacementState::Active => (Some(record.epoch.claimant), "active"),
            PlacementState::SealedTo(next) => (Some(next.claimant), "moving"),
            PlacementState::Placeless => (None, "placeless"),
            PlacementState::SupersededUnknown => (None, "superseded_unknown"),
        };
        Self {
            agent_id: record.agent_id.as_str().to_owned(),
            holder_node_id: holder.map(|h| h.to_hex()),
            epoch: EpochJson::from(&record.epoch),
            state: state.to_owned(),
            retry_after_ms: (state == "moving").then_some(MOVING_RETRY_AFTER_MS),
        }
    }
}

/// A refusal: `{"error": "...", "placement": {...}}`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Refusal {
    /// The v1 error string.
    pub error: String,
    /// Where the agent lives.
    pub placement: PlacementJson,
}

impl Refusal {
    /// `not_holder: agent lives on <holder hex or none>`, for a turn command this node does not
    /// hold (or holds while moving).
    #[must_use]
    pub fn not_holder(record: &PlacementRecord, moving: bool) -> Self {
        let placement = PlacementJson::of(record, moving);
        Self {
            error: format!("not_holder: agent lives on {}", where_(&placement)),
            placement,
        }
    }

    /// `moved: agent lives on <holder hex or none>`, for `feed.subscribe` on a node with no
    /// replica of the agent.
    #[must_use]
    pub fn moved(record: &PlacementRecord) -> Self {
        let placement = PlacementJson::of(record, false);
        Self {
            error: format!("moved: agent lives on {}", where_(&placement)),
            placement,
        }
    }
}

fn where_(placement: &PlacementJson) -> &str {
    placement.holder_node_id.as_deref().unwrap_or("none")
}

/// `served_by`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServedBy {
    /// The serving node, hex.
    pub node_id: String,
    /// Whether it holds the agent.
    pub is_holder: bool,
}

/// The optional page fields the amendment adds (flattened into a feed v1 page).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PageAdditions {
    /// Who served the page.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub served_by: Option<ServedBy>,
    /// Where the agent lives.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub placement: Option<PlacementJson>,
    /// The replica is behind the client's cursor.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub lagging: bool,
}
