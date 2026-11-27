//! Layers 1 and 2 of the access gate (catalog-v1 §3.3, §3.4): the per-message filter between a link
//! and the local samod connection. A pure state machine: frames and time in, verdicts out.
//!
//! - The principal is the authenticated endpoint id, never samod's `senderId`; `join` and `peer`
//!   must carry it, and every later message must name it as sender and us as target.
//! - Inbound `request`/`sync` are ruled on by the principal's access to the document: dropped with
//!   a `doc-unavailable` reply for no access, changes stripped and quarantined for read access
//!   (closing on the third violation), changes parsed for write access.
//! - Outbound messages about documents the principal cannot read are dropped, and a read-only node
//!   strips the changes from its own sync messages.

use automerge::{Automerge, Change};
use core_proto::NodeId;
use core_proto::auth::{AuthErrorCode, AuthFailureLimiter, FailureOutcome};
use core_proto::closure::Closure;
use core_store::DocId;

use crate::ids::{DocClass, DocUrl};
use crate::policy::{Access, closures};
use crate::wire::{MAX_FRAME, Message, text_array};

/// The read-only violations after which a link closes.
pub const MAX_VIOLATIONS: u32 = 3;
/// Denials allowed per window before `4429` (more than this closes).
pub const MAX_DENIALS: usize = 32;
/// The denial window.
pub const DENIAL_WINDOW_MS: u64 = 60_000;
/// The largest single change a writer may send.
pub const MAX_CHANGE: usize = 1024 * 1024;

/// The access the gate looks up per document.
pub trait GateAccess {
    /// The registered class of `doc` and what the principal may do with it; `None` when the
    /// document is not registered.
    fn peer(&self, doc: &DocId) -> Option<(DocClass, Access)>;

    /// What this node itself may do with `doc` (self-restraint on outbound changes).
    fn local(&self, doc: &DocId) -> Access;
}

/// What happens to one frame.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Verdict {
    /// Pass the original bytes on.
    PassOriginal,
    /// Pass these re-encoded bytes on (only to the local samod, or out for our own messages).
    PassRewritten(Vec<u8>),
    /// Drop it.
    Drop,
    /// Drop it and send this reply back.
    Reply(Vec<u8>),
    /// Close the link.
    Close(Closure),
}

/// Why changes were quarantined.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum QuarantineReason {
    /// A read-only principal sent changes.
    UnauthorizedChange,
    /// A change that does not parse.
    MalformedChange,
    /// A change over 1 MiB.
    OversizedChange,
}

impl QuarantineReason {
    /// The stored reason.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::UnauthorizedChange => "unauthorized_change",
            Self::MalformedChange => "malformed_change",
            Self::OversizedChange => "oversized_change",
        }
    }
}

/// Changes the gate kept out of the document.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Quarantined {
    /// Why.
    pub reason: QuarantineReason,
    /// The document they were for.
    pub doc_id: DocId,
    /// The hashes of the changes that parsed, hex.
    pub change_hashes: Vec<String>,
    /// The sync message's change chunks, concatenated.
    pub bytes: Vec<u8>,
}

/// The gate's decision on one inbound frame.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Inbound {
    /// What to do with the frame.
    pub verdict: Verdict,
    /// Changes to quarantine (and raise a `catalog_violation` event for).
    pub quarantine: Option<Quarantined>,
}

impl Inbound {
    const fn verdict(verdict: Verdict) -> Self {
        Self {
            verdict,
            quarantine: None,
        }
    }
}

/// The gate of one link.
#[derive(Debug)]
pub struct DocGate {
    ours: String,
    principal: String,
    violations: u32,
    denials: AuthFailureLimiter<()>,
}

impl DocGate {
    /// The gate of a link from `principal` (authenticated) to us.
    #[must_use]
    pub fn new(ours: &NodeId, principal: &NodeId) -> Self {
        Self {
            ours: ours.to_hex(),
            principal: principal.to_hex(),
            violations: 0,
            denials: AuthFailureLimiter::with_limits(MAX_DENIALS + 1, DENIAL_WINDOW_MS, 1),
        }
    }

    /// Read-only violations on this link so far.
    #[must_use]
    pub const fn violations(&self) -> u32 {
        self.violations
    }

    /// Rules on one inbound frame payload (the bytes after the length prefix).
    pub fn inbound(&mut self, frame: &[u8], access: &dyn GateAccess, now_ms: u64) -> Inbound {
        if frame.len() > MAX_FRAME {
            return Inbound::verdict(Verdict::Close(closures::FRAME_TOO_LARGE));
        }
        let Some(message) = Message::decode(frame) else {
            return Inbound::verdict(Verdict::Close(closures::MALFORMED_FRAME));
        };
        match message.kind() {
            "join" => self.handshake(&message, false),
            "peer" => self.handshake(&message, true),
            "error" => Self::pass_if_registered(&message, access),
            "leave" => self
                .addressed(&message, false)
                .unwrap_or(Inbound::verdict(Verdict::PassOriginal)),
            "doc-unavailable" => match self.addressed(&message, true) {
                Some(closed) => closed,
                None => Self::pass_if_registered(&message, access),
            },
            "request" | "sync" => match self.addressed(&message, true) {
                Some(closed) => closed,
                None => self.sync(&message, access, now_ms),
            },
            "ephemeral" => match self.addressed(&message, true) {
                Some(closed) => closed,
                None => {
                    Inbound::verdict(match doc_of(&message).and_then(|doc| access.peer(&doc)) {
                        Some((DocClass::Notebook, Access::Read | Access::Write)) => {
                            Verdict::PassOriginal
                        }
                        _ => Verdict::Drop,
                    })
                }
            },
            "remote-heads-changed" => match self.addressed(&message, true) {
                Some(closed) => closed,
                None => Inbound::verdict(readable_doc(&message, |doc| access.peer(doc))),
            },
            "remote-subscription-change" => match self.addressed(&message, true) {
                Some(closed) => closed,
                None => Inbound::verdict(filter_subscriptions(&message, |doc| {
                    access.peer(doc).map(|(_, a)| a)
                })),
            },
            _ => Inbound::verdict(Verdict::Drop),
        }
    }

    /// Rules on one outbound frame from the local samod.
    pub fn outbound(&mut self, frame: &[u8], access: &dyn GateAccess) -> Verdict {
        let Some(message) = Message::decode(frame) else {
            return Verdict::Drop;
        };
        match message.kind() {
            "join" | "peer" | "leave" | "error" => Verdict::PassOriginal,
            "remote-subscription-change" => {
                filter_subscriptions(&message, |doc| access.peer(doc).map(|(_, a)| a))
            }
            kind => {
                let Some(doc) = doc_of(&message) else {
                    return Verdict::Drop;
                };
                let Some((class, peer)) = access.peer(&doc) else {
                    return Verdict::Drop;
                };
                if peer == Access::None || (kind == "ephemeral" && class.is_catalog()) {
                    return Verdict::Drop;
                }
                if matches!(kind, "request" | "sync") && access.local(&doc) < Access::Write {
                    return match strip(&message) {
                        Ok(Some((rewritten, _))) => Verdict::PassRewritten(rewritten),
                        Ok(None) => Verdict::PassOriginal,
                        Err(()) => Verdict::Drop,
                    };
                }
                Verdict::PassOriginal
            }
        }
    }

    fn handshake(&self, message: &Message, peer: bool) -> Inbound {
        if message.text("senderId") != Some(self.principal.as_str()) {
            return Inbound::verdict(Verdict::Close(closures::PEER_ID_MISMATCH));
        }
        if peer && message.text("targetId") != Some(self.ours.as_str()) {
            return Inbound::verdict(Verdict::Close(closures::MALFORMED_FRAME));
        }
        Inbound::verdict(Verdict::PassOriginal)
    }

    /// `Some(close)` when the sender is not the principal or the target is not us.
    fn addressed(&self, message: &Message, needs_target: bool) -> Option<Inbound> {
        let sender_ok = message.text("senderId") == Some(self.principal.as_str());
        let target_ok = !needs_target || message.text("targetId") == Some(self.ours.as_str());
        (!(sender_ok && target_ok))
            .then(|| Inbound::verdict(Verdict::Close(closures::MALFORMED_FRAME)))
    }

    fn pass_if_registered(message: &Message, access: &dyn GateAccess) -> Inbound {
        if !message.has("documentId") {
            return Inbound::verdict(Verdict::PassOriginal);
        }
        Inbound::verdict(match doc_of(message) {
            Some(doc) if access.peer(&doc).is_some() => Verdict::PassOriginal,
            _ => Verdict::Drop,
        })
    }

    fn deny(&mut self, now_ms: u64) -> Option<Inbound> {
        match self.denials.deny(&(), AuthErrorCode::Denied, now_ms) {
            FailureOutcome::RateLimited => {
                Some(Inbound::verdict(Verdict::Close(closures::TOO_MANY_DENIALS)))
            }
            FailureOutcome::Denied(_) => None,
        }
    }

    fn sync(&mut self, message: &Message, access: &dyn GateAccess, now_ms: u64) -> Inbound {
        let Some(text) = message.text("documentId") else {
            return Inbound::verdict(Verdict::Close(closures::MALFORMED_FRAME));
        };
        let Ok(url) = DocUrl::from_bs58(text) else {
            return Inbound::verdict(Verdict::Close(closures::MALFORMED_FRAME));
        };
        let doc = url.doc_id();
        let Some((_, granted)) = access.peer(&doc) else {
            // Not listed by the constellation: no history is accepted, and no reply is sent.
            return Inbound::verdict(Verdict::Drop);
        };
        match granted {
            Access::None => {
                if let Some(closed) = self.deny(now_ms) {
                    return closed;
                }
                let reply = Message::doc_unavailable(&self.ours, &self.principal, text);
                Inbound::verdict(Verdict::Reply(reply.encode()))
            }
            Access::Read => match strip(message) {
                Err(()) => Inbound::verdict(Verdict::Close(closures::MALFORMED_FRAME)),
                Ok(None) => Inbound::verdict(Verdict::PassOriginal),
                Ok(Some((rewritten, changes))) => {
                    self.violations += 1;
                    let quarantine = Some(quarantined(
                        QuarantineReason::UnauthorizedChange,
                        doc,
                        &changes,
                    ));
                    let verdict = if self.violations >= MAX_VIOLATIONS {
                        Verdict::Close(closures::WRITE_NOT_PERMITTED)
                    } else {
                        Verdict::PassRewritten(rewritten)
                    };
                    Inbound {
                        verdict,
                        quarantine,
                    }
                }
            },
            Access::Write => {
                let Some(data) = message.data() else {
                    return Inbound::verdict(Verdict::Close(closures::MALFORMED_FRAME));
                };
                let Ok(sync) = automerge::sync::Message::decode(data) else {
                    return Inbound::verdict(Verdict::Close(closures::MALFORMED_FRAME));
                };
                let chunks: Vec<Vec<u8>> = sync.changes.iter().map(<[u8]>::to_vec).collect();
                match check_changes(&chunks) {
                    Ok(()) => Inbound::verdict(Verdict::PassOriginal),
                    Err(reason) => Inbound {
                        verdict: Verdict::Close(closures::MALFORMED_FRAME),
                        quarantine: Some(quarantined(reason, doc, &chunks)),
                    },
                }
            }
        }
    }
}

fn doc_of(message: &Message) -> Option<DocId> {
    DocUrl::from_bs58(message.text("documentId")?)
        .ok()
        .map(|url| url.doc_id())
}

fn readable_doc(
    message: &Message,
    lookup: impl Fn(&DocId) -> Option<(DocClass, Access)>,
) -> Verdict {
    match doc_of(message).and_then(|doc| lookup(&doc)) {
        Some((_, Access::Read | Access::Write)) => Verdict::PassOriginal,
        _ => Verdict::Drop,
    }
}

/// Filters `add` and `remove` to readable documents: pass unchanged, rewrite, or drop when nothing
/// is left.
fn filter_subscriptions(message: &Message, lookup: impl Fn(&DocId) -> Option<Access>) -> Verdict {
    let readable = |id: &String| {
        DocUrl::from_bs58(id)
            .ok()
            .and_then(|url| lookup(&url.doc_id()))
            .is_some_and(|access| access >= Access::Read)
    };
    let mut rewritten = message.clone();
    let mut changed = false;
    let mut remaining = 0;
    for key in ["add", "remove"] {
        let Some(list) = message.text_list(key) else {
            continue;
        };
        let kept: Vec<String> = list.iter().filter(|id| readable(id)).cloned().collect();
        remaining += kept.len();
        if kept.len() != list.len() {
            changed = true;
            rewritten.set(key, text_array(&kept));
        }
    }
    if remaining == 0 {
        Verdict::Drop
    } else if changed {
        Verdict::PassRewritten(rewritten.encode())
    } else {
        Verdict::PassOriginal
    }
}

/// A rewritten frame and the change chunks removed from it.
type Stripped = (Vec<u8>, Vec<Vec<u8>>);

/// Removes the changes from a `request`/`sync`: `Ok(None)` when it carried none, `Ok(Some(..))`
/// with the rewritten frame and the removed chunks, `Err` when `data` is not a sync message.
fn strip(message: &Message) -> Result<Option<Stripped>, ()> {
    let data = message.data().ok_or(())?;
    let mut sync = automerge::sync::Message::decode(data).map_err(|_| ())?;
    if sync.changes.is_empty() {
        return Ok(None);
    }
    let removed: Vec<Vec<u8>> = sync.changes.iter().map(<[u8]>::to_vec).collect();
    sync.changes = automerge::sync::ChunkList::empty();
    let mut rewritten = message.clone();
    rewritten.set("data", ciborium::Value::Bytes(sync.encode()));
    Ok(Some((rewritten.encode(), removed)))
}

/// Parses a writer's change chunks: each must load, and no single change may exceed 1 MiB.
fn check_changes(chunks: &[Vec<u8>]) -> Result<(), QuarantineReason> {
    for chunk in chunks {
        if Change::from_bytes(chunk.clone()).is_ok() && chunk.len() > MAX_CHANGE {
            return Err(QuarantineReason::OversizedChange);
        }
    }
    let joined: Vec<u8> = chunks.concat();
    if joined.is_empty() {
        return Ok(());
    }
    Automerge::new()
        .load_incremental(&joined)
        .map(|_| ())
        .map_err(|_| QuarantineReason::MalformedChange)
}

fn quarantined(reason: QuarantineReason, doc_id: DocId, chunks: &[Vec<u8>]) -> Quarantined {
    Quarantined {
        reason,
        doc_id,
        change_hashes: chunks
            .iter()
            .filter_map(|chunk| Change::from_bytes(chunk.clone()).ok())
            .map(|change| change.hash().to_string())
            .collect(),
        bytes: chunks.concat(),
    }
}
