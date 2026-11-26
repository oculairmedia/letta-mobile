//! Claim votes and quorum certificates (C1 §5.8).
//!
//! A vote is a BCBOR body signed over its `vote_id` (BLAKE3 derive-key with [`VOTE_ID_CONTEXT`]).
//! The envelope is `[body bstr, sig bstr64]`, and an `epoch_open` carries the envelopes it was
//! certified with, verbatim. Encoding and hashing go through `core_proto::bcbor` and
//! `core_proto::crypto`.

use std::collections::{BTreeMap, BTreeSet};

use core_log::Pos;
use core_log::value::epoch_value;
use core_membership::policy::Quorum;
use core_proto::bcbor::{self, BcborError, Value};
use core_proto::crypto::{NodeKey, Signature, derive_key, verify_strict};
use core_proto::{Epoch, NodeId};
use core_store::{EntryHash, LogId};
use thiserror::Error;

/// The BLAKE3 derive-key context of a vote id.
pub const VOTE_ID_CONTEXT: &str = "baken 2026-11-26 claim vote v1";

/// The only vote body version.
pub const VOTE_VERSION: u64 = 1;

/// What a voter agrees to: this log may move to `epoch`, forking at `parent`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ClaimVote {
    /// The agent log.
    pub log_id: LogId,
    /// The claimed epoch.
    pub epoch: Epoch,
    /// The claim's parent (fork point).
    pub parent: Pos,
    /// The voter.
    pub voter: NodeId,
    /// The voter's clock, informational only.
    pub ts_ms: u64,
}

/// Why a vote or certificate is not valid. Each variant has a stable code ([`VoteError::code`]).
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub enum VoteError {
    /// Not BCBOR.
    #[error("bcbor: {0}")]
    Bcbor(#[from] BcborError),
    /// Not a `[body, sig]` envelope, or the body is not a vote.
    #[error("not a vote")]
    Shape,
    /// The signature does not verify under the voter.
    #[error("bad vote signature")]
    BadSignature,
    /// The vote names another log, epoch or parent.
    #[error("the vote is for another claim")]
    WrongClaim,
    /// The voter is not in the policy's voter set.
    #[error("the voter is not in the quorum")]
    NotVoter,
    /// Two votes by one voter.
    #[error("a voter voted twice")]
    DuplicateVoter,
    /// Fewer distinct votes than the quorum requires.
    #[error("too few votes")]
    TooFew,
}

impl VoteError {
    /// The stable code fixtures name.
    #[must_use]
    pub const fn code(&self) -> &'static str {
        match self {
            Self::Bcbor(_) | Self::Shape => "bad_vote",
            Self::BadSignature => "bad_signature",
            Self::WrongClaim => "wrong_claim",
            Self::NotVoter => "not_voter",
            Self::DuplicateVoter => "duplicate_voter",
            Self::TooFew => "too_few",
        }
    }
}

impl ClaimVote {
    /// The canonical body.
    #[must_use]
    pub fn body(&self) -> Vec<u8> {
        let mut map = BTreeMap::new();
        map.insert(0, Value::Uint(VOTE_VERSION));
        map.insert(1, Value::Bytes(self.log_id.as_bytes().to_vec()));
        map.insert(2, epoch_value(&self.epoch));
        map.insert(3, Value::Uint(self.parent.seq));
        map.insert(4, Value::Bytes(self.parent.hash.as_bytes().to_vec()));
        map.insert(5, Value::Bytes(self.voter.as_bytes().to_vec()));
        map.insert(6, Value::Uint(self.ts_ms));
        bcbor::encode(&Value::Map(map))
    }

    /// `derive_key("claim vote", body)`.
    #[must_use]
    pub fn vote_id(&self) -> [u8; 32] {
        derive_key(VOTE_ID_CONTEXT, &self.body())
    }

    /// Decodes a canonical body.
    ///
    /// # Errors
    ///
    /// [`VoteError::Bcbor`] or [`VoteError::Shape`].
    pub fn decode_body(body: &[u8]) -> Result<Self, VoteError> {
        let Value::Map(map) = bcbor::decode(body)? else {
            return Err(VoteError::Shape);
        };
        if map.keys().any(|key| (7..64).contains(key)) || map.get(&0) != Some(&Value::Uint(1)) {
            return Err(VoteError::Shape);
        }
        Ok(Self {
            log_id: LogId::from_bytes(bytes32(map.get(&1))?),
            epoch: epoch(map.get(&2))?,
            parent: Pos {
                seq: uint(map.get(&3))?,
                hash: EntryHash::from_bytes(bytes32(map.get(&4))?),
            },
            voter: NodeId::from_bytes(bytes32(map.get(&5))?),
            ts_ms: uint(map.get(&6))?,
        })
    }

    /// Whether this vote is for exactly `(log_id, epoch, parent)`.
    #[must_use]
    pub fn is_for(&self, log_id: &LogId, epoch: &Epoch, parent: &Pos) -> bool {
        self.log_id == *log_id && self.epoch == *epoch && self.parent == *parent
    }
}

/// A vote with its signature.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SignedVote {
    vote: ClaimVote,
    body: Vec<u8>,
    sig: Signature,
}

impl SignedVote {
    /// Signs `vote` with `key`, which must be the voter's.
    ///
    /// # Errors
    ///
    /// [`VoteError::BadSignature`] when `key` is not the voter's.
    pub fn sign(vote: ClaimVote, key: &NodeKey) -> Result<Self, VoteError> {
        if key.node_id() != vote.voter {
            return Err(VoteError::BadSignature);
        }
        let body = vote.body();
        let sig = key.sign(&derive_key(VOTE_ID_CONTEXT, &body));
        Ok(Self { vote, body, sig })
    }

    /// Decodes and verifies an envelope value (`[body, sig]`), as an `epoch_open` carries it.
    ///
    /// # Errors
    ///
    /// [`VoteError`] when the shape, body or signature is invalid.
    pub fn from_value(value: &Value) -> Result<Self, VoteError> {
        let Value::Array(parts) = value else {
            return Err(VoteError::Shape);
        };
        let [Value::Bytes(body), Value::Bytes(sig)] = parts.as_slice() else {
            return Err(VoteError::Shape);
        };
        let sig = Signature::from_bytes(
            <[u8; 64]>::try_from(sig.as_slice()).map_err(|_| VoteError::Shape)?,
        );
        let vote = ClaimVote::decode_body(body)?;
        if !verify_strict(&vote.voter, &derive_key(VOTE_ID_CONTEXT, body), &sig) {
            return Err(VoteError::BadSignature);
        }
        Ok(Self {
            vote,
            body: body.clone(),
            sig,
        })
    }

    /// Decodes and verifies envelope bytes.
    ///
    /// # Errors
    ///
    /// As [`SignedVote::from_value`].
    pub fn decode(envelope: &[u8]) -> Result<Self, VoteError> {
        Self::from_value(&bcbor::decode(envelope)?)
    }

    /// The envelope value `[body, sig]`.
    #[must_use]
    pub fn to_value(&self) -> Value {
        Value::Array(vec![
            Value::Bytes(self.body.clone()),
            Value::Bytes(self.sig.as_bytes().to_vec()),
        ])
    }

    /// The envelope bytes.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        bcbor::encode(&self.to_value())
    }

    /// The vote.
    #[must_use]
    pub const fn vote(&self) -> &ClaimVote {
        &self.vote
    }

    /// The signature.
    #[must_use]
    pub const fn signature(&self) -> &Signature {
        &self.sig
    }
}

/// Checks a certificate: every envelope is a valid vote for exactly `(log_id, epoch, parent)` by a
/// distinct voter of `quorum`, and there are at least `quorum.required` of them. One bad vote makes
/// the whole certificate invalid, so every replica reaches the same verdict from the same bytes.
///
/// # Errors
///
/// The first [`VoteError`] found.
pub fn check_certificate(
    votes: &[Value],
    log_id: &LogId,
    epoch: &Epoch,
    parent: &Pos,
    quorum: &Quorum,
) -> Result<(), VoteError> {
    let mut seen = BTreeSet::new();
    for value in votes {
        let signed = SignedVote::from_value(value)?;
        let vote = signed.vote();
        if !vote.is_for(log_id, epoch, parent) {
            return Err(VoteError::WrongClaim);
        }
        if !quorum.voters.contains(&vote.voter) {
            return Err(VoteError::NotVoter);
        }
        if !seen.insert(vote.voter) {
            return Err(VoteError::DuplicateVoter);
        }
    }
    if (seen.len() as u64) < quorum.required {
        return Err(VoteError::TooFew);
    }
    Ok(())
}

fn uint(value: Option<&Value>) -> Result<u64, VoteError> {
    match value {
        Some(Value::Uint(n)) => Ok(*n),
        _ => Err(VoteError::Shape),
    }
}

fn bytes32(value: Option<&Value>) -> Result<[u8; 32], VoteError> {
    match value {
        Some(Value::Bytes(bytes)) => {
            <[u8; 32]>::try_from(bytes.as_slice()).map_err(|_| VoteError::Shape)
        }
        _ => Err(VoteError::Shape),
    }
}

pub(crate) fn epoch(value: Option<&Value>) -> Result<Epoch, VoteError> {
    match value {
        Some(Value::Array(parts)) => match parts.as_slice() {
            [Value::Uint(n), claimant] => Ok(Epoch {
                n: *n,
                claimant: NodeId::from_bytes(bytes32(Some(claimant))?),
            }),
            _ => Err(VoteError::Shape),
        },
        _ => Err(VoteError::Shape),
    }
}
