//! Optional majority acknowledgement (C1 §5.8): the voter's rules V1-V4 with a promise persisted
//! in the node store before the vote leaves, and the claimant's tally.

use std::collections::BTreeMap;

use core_log::Pos;
use core_membership::policy::{FailoverPolicy, Quorum};
use core_proto::bcbor::Value;
use core_proto::crypto::NodeKey;
use core_proto::{Epoch, NodeId};
use core_store::{LogId, LogStore, StoreError, StoredEntry, VotePromise};
use thiserror::Error;

use crate::vote::{ClaimVote, SignedVote, VoteError};

/// A `claim_request`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ClaimRequest {
    /// The agent log.
    pub log_id: LogId,
    /// The epoch the candidate will claim.
    pub epoch: Epoch,
    /// Its fork point.
    pub parent: Pos,
    /// A manual `takeover` (V3 is waived) rather than an automatic failover.
    pub manual: bool,
}

/// Why a voter refuses (`claim_refused` reasons).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Error)]
pub enum Refusal {
    /// V1: already promised this log to this epoch or a higher one (another fork point).
    #[error("promised")]
    Promised,
    /// V2: the parent is not on this voter's canonical line at or beyond its complete tip.
    #[error("behind")]
    Behind,
    /// V3: this voter heard the holder within the failover window.
    #[error("holder_alive")]
    HolderAlive,
    /// This node is not a voter of the policy.
    #[error("not_voter")]
    NotVoter,
}

impl Refusal {
    /// The wire reason.
    #[must_use]
    pub const fn code(self) -> &'static str {
        match self {
            Self::Promised => "promised",
            Self::Behind => "behind",
            Self::HolderAlive => "holder_alive",
            Self::NotVoter => "not_voter",
        }
    }
}

/// What the voter knows when it decides.
#[derive(Clone, Copy, Debug)]
pub struct VoterView<'a> {
    /// This node.
    pub me: NodeId,
    /// The membership policy.
    pub policy: &'a FailoverPolicy,
    /// This node's canonical line of the log, genesis first.
    pub line: &'a [StoredEntry],
    /// This node's complete seq on that line.
    pub complete_seq: u64,
    /// When this node last heard the holder, if ever.
    pub holder_heard_ms: Option<u64>,
    /// This node's clock.
    pub now_ms: u64,
}

/// Rules V1-V3, as a pure function: the promise to persist, or the refusal.
///
/// # Errors
///
/// The [`Refusal`].
pub fn decide(
    request: &ClaimRequest,
    promised: Option<&VotePromise>,
    view: &VoterView<'_>,
) -> Result<VotePromise, Refusal> {
    let quorum = view.policy.quorum.as_ref().ok_or(Refusal::NotVoter)?;
    if !quorum.voters.contains(&view.me) {
        return Err(Refusal::NotVoter);
    }
    let promise = VotePromise {
        log_id: request.log_id,
        epoch: request.epoch,
        parent_seq: request.parent.seq,
        parent_hash: request.parent.hash,
    };
    if let Some(existing) = promised {
        let same = existing == &promise;
        if existing.epoch >= request.epoch && !same {
            return Err(Refusal::Promised);
        }
    }
    let on_line = usize::try_from(request.parent.seq)
        .ok()
        .and_then(|seq| view.line.get(seq))
        .is_some_and(|e| e.entry_hash == request.parent.hash);
    if !on_line || request.parent.seq < view.complete_seq {
        return Err(Refusal::Behind);
    }
    if !request.manual
        && view
            .holder_heard_ms
            .is_some_and(|t| view.now_ms.saturating_sub(t) < view.policy.after_ms)
    {
        return Err(Refusal::HolderAlive);
    }
    Ok(promise)
}

/// Why casting a vote failed.
#[derive(Debug, Error)]
pub enum CastError {
    /// The voter refused.
    #[error("refused: {0}")]
    Refused(#[from] Refusal),
    /// The store failed, or refused the promise.
    #[error("store: {0}")]
    Store(#[from] StoreError),
    /// Signing failed (the key is not this voter's).
    #[error("vote: {0}")]
    Vote(#[from] VoteError),
}

/// V1-V4: decides, persists the promise in `store` (inside the caller's transaction, which must
/// commit before the vote is sent), and signs the vote.
///
/// # Errors
///
/// [`CastError`]; nothing is persisted then.
pub fn cast_vote<S: LogStore + ?Sized>(
    store: &mut S,
    key: &NodeKey,
    request: &ClaimRequest,
    view: &VoterView<'_>,
) -> Result<SignedVote, CastError> {
    let promised = store.vote_promise(&request.log_id)?;
    let promise = decide(request, promised.as_ref(), view)?;
    let vote = SignedVote::sign(
        ClaimVote {
            log_id: request.log_id,
            epoch: request.epoch,
            parent: request.parent,
            voter: view.me,
            ts_ms: view.now_ms,
        },
        key,
    )?;
    store.set_vote_promise(&promise)?;
    Ok(vote)
}

/// The claimant's tally of votes for one claim.
#[derive(Clone, Debug)]
pub struct Tally {
    request: ClaimRequest,
    quorum: Quorum,
    votes: BTreeMap<NodeId, SignedVote>,
}

impl Tally {
    /// A tally for `request` under `quorum`.
    #[must_use]
    pub const fn new(request: ClaimRequest, quorum: Quorum) -> Self {
        Self {
            request,
            quorum,
            votes: BTreeMap::new(),
        }
    }

    /// Adds one received vote. Votes for another claim, by non-voters, or duplicates are ignored.
    /// Returns whether the vote counted.
    pub fn add(&mut self, vote: SignedVote) -> bool {
        let v = vote.vote();
        if !v.is_for(
            &self.request.log_id,
            &self.request.epoch,
            &self.request.parent,
        ) || !self.quorum.voters.contains(&v.voter)
            || self.votes.contains_key(&v.voter)
        {
            return false;
        }
        self.votes.insert(v.voter, vote);
        true
    }

    /// The certificate (envelopes ordered by voter) once `required` distinct votes are in.
    #[must_use]
    pub fn certificate(&self) -> Option<Vec<Value>> {
        ((self.votes.len() as u64) >= self.quorum.required)
            .then(|| self.votes.values().map(SignedVote::to_value).collect())
    }
}
