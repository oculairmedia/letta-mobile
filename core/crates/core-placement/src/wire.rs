//! `baken/place/1` messages (C1 §5.10): a `u32 BE` length, then a BCBOR map whose key 0 is the
//! type, at most `core_log::MAX_FRAME` bytes. Encoding goes through `core_proto::bcbor`.

use std::collections::BTreeMap;

use core_log::header::SIGNED_LEN;
use core_log::value::{epoch_value, pos_value};
use core_log::{MAX_FRAME, Pos, Reason};
use core_proto::bcbor::{self, BcborError, Value};
use core_proto::{Epoch, NodeId};
use core_store::{EntryHash, LogId};
use thiserror::Error;

use crate::fenced::FencedReport;
use crate::vote::SignedVote;

/// One `verify_failed` difference: path, expected SHA-256, actual SHA-256 (absent file: `None`).
pub type Diff = (String, [u8; 32], Option<[u8; 32]>);

/// A `baken/place/1` message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PlaceMessage {
    /// 1: ask the holder to move the agent.
    MoveRequest {
        /// The agent log.
        log_id: LogId,
        /// The target.
        target: NodeId,
    },
    /// 2: the source's prepare.
    Prepare {
        /// The agent log.
        log_id: LogId,
        /// The source's epoch.
        from: Epoch,
        /// The target's epoch.
        proposed: Epoch,
        /// The source's store format.
        store_format: u64,
        /// The source's runtime parity.
        runtime_parity: String,
    },
    /// 3: the target accepts; its complete position.
    Prepared {
        /// The complete position.
        complete: Pos,
    },
    /// 4: the target refuses.
    Refused {
        /// Why.
        reason: String,
    },
    /// 5: the materialized checkpoint matches.
    Verified {
        /// The checkpoint.
        checkpoint: Pos,
    },
    /// 6: it does not.
    VerifyFailed {
        /// File differences.
        diffs: Vec<Diff>,
        /// Whether refs differ.
        refs_mismatch: bool,
    },
    /// 7: the seal, signed (232 bytes).
    Commit {
        /// The signed seal entry.
        seal: Vec<u8>,
    },
    /// 8: either side ends the move.
    Abort {
        /// Why.
        reason: String,
    },
    /// 9: a candidate asks for a vote.
    ClaimRequest {
        /// The agent log.
        log_id: LogId,
        /// The epoch it will claim.
        epoch: Epoch,
        /// Its fork point.
        parent: Pos,
        /// `failover` or `takeover` (voters waive V3 for a takeover).
        reason: Reason,
    },
    /// 10: a vote.
    ClaimVote {
        /// The signed vote.
        vote: SignedVote,
    },
    /// 11: a voter refuses.
    ClaimRefused {
        /// `promised`, `behind`, `holder_alive` or `not_voter`.
        reason: String,
    },
    /// 12: a fenced old holder reports its segment head.
    Fenced(FencedReport),
}

/// Why a frame does not decode.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum PlaceWireError {
    /// Not canonical BCBOR.
    #[error("bcbor: {0}")]
    Bcbor(#[from] BcborError),
    /// A field is missing or has the wrong shape.
    #[error("bad field {0}")]
    Field(u64),
    /// An unknown message type.
    #[error("unknown type {0}")]
    UnknownType(u64),
    /// Over `MAX_FRAME`, or a length prefix that does not match.
    #[error("bad frame length")]
    Length,
}

impl PlaceWireError {
    /// The stable code fixtures name.
    #[must_use]
    pub const fn code(&self) -> &'static str {
        match self {
            Self::Bcbor(error) => error.code(),
            Self::Field(_) => "bad_field",
            Self::UnknownType(_) => "unknown_type",
            Self::Length => "bad_length",
        }
    }
}

impl PlaceMessage {
    /// The message type number.
    #[must_use]
    pub const fn type_number(&self) -> u64 {
        match self {
            Self::MoveRequest { .. } => 1,
            Self::Prepare { .. } => 2,
            Self::Prepared { .. } => 3,
            Self::Refused { .. } => 4,
            Self::Verified { .. } => 5,
            Self::VerifyFailed { .. } => 6,
            Self::Commit { .. } => 7,
            Self::Abort { .. } => 8,
            Self::ClaimRequest { .. } => 9,
            Self::ClaimVote { .. } => 10,
            Self::ClaimRefused { .. } => 11,
            Self::Fenced(_) => 12,
        }
    }

    /// The BCBOR map.
    #[must_use]
    pub fn to_value(&self) -> Value {
        let mut map = BTreeMap::new();
        map.insert(0, Value::Uint(self.type_number()));
        let mut put = |key: u64, value: Value| {
            map.insert(key, value);
        };
        match self {
            Self::MoveRequest { log_id, target } => {
                put(1, bytes(log_id.as_bytes()));
                put(2, bytes(target.as_bytes()));
            }
            Self::Prepare {
                log_id,
                from,
                proposed,
                store_format,
                runtime_parity,
            } => {
                put(1, bytes(log_id.as_bytes()));
                put(2, epoch_value(from));
                put(3, epoch_value(proposed));
                put(4, Value::Uint(*store_format));
                put(5, Value::Text(runtime_parity.clone()));
            }
            Self::Prepared { complete } => put(1, pos_value(complete)),
            Self::Refused { reason } | Self::Abort { reason } | Self::ClaimRefused { reason } => {
                put(1, Value::Text(reason.clone()));
            }
            Self::Verified { checkpoint } => put(1, pos_value(checkpoint)),
            Self::VerifyFailed {
                diffs,
                refs_mismatch,
            } => {
                put(
                    1,
                    Value::Array(
                        diffs
                            .iter()
                            .map(|(path, expected, actual)| {
                                Value::Array(vec![
                                    Value::Text(path.clone()),
                                    bytes(expected),
                                    actual.map_or(Value::Null, |a| bytes(&a)),
                                ])
                            })
                            .collect(),
                    ),
                );
                put(2, Value::Bool(*refs_mismatch));
            }
            Self::Commit { seal } => put(1, Value::Bytes(seal.clone())),
            Self::ClaimRequest {
                log_id,
                epoch,
                parent,
                reason,
            } => {
                put(1, bytes(log_id.as_bytes()));
                put(2, epoch_value(epoch));
                put(3, pos_value(parent));
                put(4, Value::Uint(reason.code()));
            }
            Self::ClaimVote { vote } => put(1, vote.to_value()),
            Self::Fenced(report) => {
                put(1, bytes(report.log_id.as_bytes()));
                put(2, epoch_value(&report.epoch));
                put(3, pos_value(&report.head));
            }
        }
        Value::Map(map)
    }

    /// The canonical body.
    #[must_use]
    pub fn encode_body(&self) -> Vec<u8> {
        bcbor::encode(&self.to_value())
    }

    /// The framed bytes: `u32 BE` length, then the body.
    #[must_use]
    pub fn encode_frame(&self) -> Vec<u8> {
        let body = self.encode_body();
        let mut out = Vec::with_capacity(body.len() + 4);
        out.extend_from_slice(&u32::try_from(body.len()).unwrap_or(u32::MAX).to_be_bytes());
        out.extend_from_slice(&body);
        out
    }

    /// Decodes a framed message.
    ///
    /// # Errors
    ///
    /// [`PlaceWireError`].
    pub fn decode_frame(frame: &[u8]) -> Result<Self, PlaceWireError> {
        let (prefix, body) = frame.split_at_checked(4).ok_or(PlaceWireError::Length)?;
        let len = u32::from_be_bytes([prefix[0], prefix[1], prefix[2], prefix[3]]);
        if usize::try_from(len).ok() != Some(body.len()) {
            return Err(PlaceWireError::Length);
        }
        Self::decode_body(body)
    }

    /// Decodes a body.
    ///
    /// # Errors
    ///
    /// [`PlaceWireError`].
    pub fn decode_body(body: &[u8]) -> Result<Self, PlaceWireError> {
        if body.len() > MAX_FRAME {
            return Err(PlaceWireError::Length);
        }
        let Value::Map(map) = bcbor::decode(body)? else {
            return Err(PlaceWireError::Field(0));
        };
        let f = Fields(&map);
        let kind = f.uint(0)?;
        let allowed: u64 = match kind {
            1 | 6 => 2,
            2 => 5,
            3 | 4 | 5 | 7 | 8 | 10 | 11 => 1,
            9 => 4,
            12 => 3,
            other => return Err(PlaceWireError::UnknownType(other)),
        };
        if let Some(extra) = map.keys().find(|k| **k > allowed && **k < 64) {
            return Err(PlaceWireError::Field(*extra));
        }
        Ok(match kind {
            1 => Self::MoveRequest {
                log_id: LogId::from_bytes(f.bytes32(1)?),
                target: NodeId::from_bytes(f.bytes32(2)?),
            },
            2 => Self::Prepare {
                log_id: LogId::from_bytes(f.bytes32(1)?),
                from: f.epoch(2)?,
                proposed: f.epoch(3)?,
                store_format: f.uint(4)?,
                runtime_parity: f.text(5)?,
            },
            3 => Self::Prepared {
                complete: f.pos(1)?,
            },
            4 => Self::Refused { reason: f.text(1)? },
            5 => Self::Verified {
                checkpoint: f.pos(1)?,
            },
            6 => Self::VerifyFailed {
                diffs: f.diffs(1)?,
                refs_mismatch: match map.get(&2) {
                    Some(Value::Bool(b)) => *b,
                    _ => return Err(PlaceWireError::Field(2)),
                },
            },
            7 => {
                let seal = f.bytes(1)?;
                if seal.len() != SIGNED_LEN {
                    return Err(PlaceWireError::Field(1));
                }
                Self::Commit { seal }
            }
            8 => Self::Abort { reason: f.text(1)? },
            9 => Self::ClaimRequest {
                log_id: LogId::from_bytes(f.bytes32(1)?),
                epoch: f.epoch(2)?,
                parent: f.pos(3)?,
                reason: match Reason::from_code(f.uint(4)?) {
                    Some(reason @ (Reason::Failover | Reason::Takeover)) => reason,
                    _ => return Err(PlaceWireError::Field(4)),
                },
            },
            10 => Self::ClaimVote {
                vote: SignedVote::from_value(f.get(1)?).map_err(|_| PlaceWireError::Field(1))?,
            },
            11 => Self::ClaimRefused { reason: f.text(1)? },
            _ => Self::Fenced(FencedReport {
                log_id: LogId::from_bytes(f.bytes32(1)?),
                epoch: f.epoch(2)?,
                head: f.pos(3)?,
            }),
        })
    }
}

fn bytes(b: &[u8]) -> Value {
    Value::Bytes(b.to_vec())
}

struct Fields<'a>(&'a BTreeMap<u64, Value>);

impl Fields<'_> {
    fn get(&self, key: u64) -> Result<&Value, PlaceWireError> {
        self.0.get(&key).ok_or(PlaceWireError::Field(key))
    }

    fn uint(&self, key: u64) -> Result<u64, PlaceWireError> {
        match self.get(key)? {
            Value::Uint(n) => Ok(*n),
            _ => Err(PlaceWireError::Field(key)),
        }
    }

    fn text(&self, key: u64) -> Result<String, PlaceWireError> {
        match self.get(key)? {
            Value::Text(t) => Ok(t.clone()),
            _ => Err(PlaceWireError::Field(key)),
        }
    }

    fn bytes(&self, key: u64) -> Result<Vec<u8>, PlaceWireError> {
        match self.get(key)? {
            Value::Bytes(b) => Ok(b.clone()),
            _ => Err(PlaceWireError::Field(key)),
        }
    }

    fn bytes32(&self, key: u64) -> Result<[u8; 32], PlaceWireError> {
        <[u8; 32]>::try_from(self.bytes(key)?.as_slice()).map_err(|_| PlaceWireError::Field(key))
    }

    fn epoch(&self, key: u64) -> Result<Epoch, PlaceWireError> {
        crate::vote::epoch(Some(self.get(key)?)).map_err(|_| PlaceWireError::Field(key))
    }

    fn pos(&self, key: u64) -> Result<Pos, PlaceWireError> {
        let Value::Map(pos) = self.get(key)? else {
            return Err(PlaceWireError::Field(key));
        };
        match (pos.get(&0), pos.get(&1), pos.len()) {
            (Some(Value::Uint(seq)), Some(Value::Bytes(hash)), 2) => Ok(Pos {
                seq: *seq,
                hash: EntryHash::from_bytes(
                    <[u8; 32]>::try_from(hash.as_slice())
                        .map_err(|_| PlaceWireError::Field(key))?,
                ),
            }),
            _ => Err(PlaceWireError::Field(key)),
        }
    }

    fn diffs(&self, key: u64) -> Result<Vec<Diff>, PlaceWireError> {
        let Value::Array(items) = self.get(key)? else {
            return Err(PlaceWireError::Field(key));
        };
        let sha = |v: &Value| match v {
            Value::Bytes(b) => <[u8; 32]>::try_from(b.as_slice()).ok(),
            _ => None,
        };
        items
            .iter()
            .map(|item| match item {
                Value::Array(parts) => match parts.as_slice() {
                    [Value::Text(path), expected, actual] => Ok((
                        path.clone(),
                        sha(expected).ok_or(PlaceWireError::Field(key))?,
                        match actual {
                            Value::Null => None,
                            other => Some(sha(other).ok_or(PlaceWireError::Field(key))?),
                        },
                    )),
                    _ => Err(PlaceWireError::Field(key)),
                },
                _ => Err(PlaceWireError::Field(key)),
            })
            .collect()
    }
}
