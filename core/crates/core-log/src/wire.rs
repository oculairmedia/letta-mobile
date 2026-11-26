//! `baken/log/1` messages (C1 §4.2) and their framing: a `u32 BE` length, then a BCBOR map whose
//! key 0 is the type, at most `MAX_FRAME` bytes. The framing is `tokio_util`'s
//! `LengthDelimitedCodec` ([`frame_codec`]).

use ::bytes::{Bytes, BytesMut};
use core_proto::bcbor::{self, Value};
use core_proto::{AgentId, Epoch, NodeId};
use core_store::{EntryHash, LogId};
use thiserror::Error;
use tokio_util::codec::{Decoder as _, Encoder as _, LengthDelimitedCodec};

use crate::MAX_FRAME;
use crate::value::{
    Fields, Pos, ShapeError, as_array, as_bytes, as_bytes32, as_uint, boolean, bytes, epoch_value,
    map, pos_value, text, uint,
};

/// The protocol version carried in `hello`.
pub const VERSION: u64 = 1;
/// At most this many `AgentHave` per `have` frame.
pub const MAX_HAVE_PER_FRAME: usize = 256;

/// Message type numbers.
pub mod msg {
    /// `hello`.
    pub const HELLO: u64 = 1;
    /// `have`.
    pub const HAVE: u64 = 2;
    /// `want`.
    pub const WANT: u64 = 3;
    /// `entries`.
    pub const ENTRIES: u64 = 4;
    /// `ack`.
    pub const ACK: u64 = 5;
    /// `head`.
    pub const HEAD: u64 = 6;
    /// `ping`.
    pub const PING: u64 = 7;
    /// `resync`.
    pub const RESYNC: u64 = 8;
    /// `error`.
    pub const ERROR: u64 = 9;
}

/// Whether a peer wants entries pushed (live) or only head notices.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Mode {
    /// Push every newly stored entry.
    Live,
    /// Send `head` notices; the peer wants when it chooses (a backgrounded phone, a metered link).
    HeadsOnly,
}

/// `hello`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Hello {
    /// Protocol version (1).
    pub version: u64,
    /// The sender.
    pub node_id: NodeId,
    /// The constellation (genesis op id).
    pub constellation_id: [u8; 32],
    /// The sender's membership heads, a sorted set.
    pub member_heads: Vec<[u8; 32]>,
    /// The sender's log digest (C1 §4.4).
    pub log_digest: [u8; 32],
    /// Live or heads-only.
    pub mode: Mode,
    /// The sender's store format.
    pub store_format: u64,
}

/// One segment as summarized in `have`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SegSummary {
    /// The segment's epoch.
    pub epoch: Epoch,
    /// Its first seq.
    pub start_seq: u64,
    /// Its fork point (`None` for the genesis segment).
    pub parent_hash: Option<EntryHash>,
    /// Its head.
    pub head: Pos,
    /// Its state, as the store's integer code.
    pub state: u64,
    /// How many entries of the segment the sender stores (every branch).
    pub entries: u64,
    /// XOR of the entry hashes of the segment the sender stores: with `entries`, tells two
    /// replicas whether they hold the same set even when off-head branches differ.
    pub set_xor: [u8; 32],
}

/// One log as summarized in `have`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AgentHave {
    /// The log.
    pub log_id: LogId,
    /// The agent, when known.
    pub agent_id: Option<AgentId>,
    /// The highest valid epoch.
    pub hwm: Option<Epoch>,
    /// The highest epoch number stored.
    pub max_n_seen: u64,
    /// The canonical tip.
    pub tip: Option<Pos>,
    /// The canonical segment's `complete_seq`.
    pub complete_seq: Option<u64>,
    /// Every segment.
    pub segments: Vec<SegSummary>,
}

/// How a payload travels in `entries`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum WirePayload {
    /// Inline bytes (at most `MAX_INLINE_PAYLOAD`).
    Inline(Vec<u8>),
    /// Fetch by hash over `baken/blob/1` (`0` on the wire).
    ByHash,
    /// Pruned at the sender (`1`); compactable entries only.
    Pruned,
}

/// A `baken/log/1` message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Message {
    /// Type 1.
    Hello(Hello),
    /// Type 2.
    Have {
        /// Logs.
        agents: Vec<AgentHave>,
        /// Last `have` frame.
        last: bool,
    },
    /// Type 3.
    Want {
        /// The log.
        log_id: LogId,
        /// The segment to read.
        epoch: Epoch,
        /// Exclusive start; `None` = every entry of that epoch the responder holds (every
        /// branch, every open of the epoch).
        after: Option<Pos>,
        /// Keep pushing new entries of this log afterwards.
        live: bool,
        /// Send payloads.
        payloads: bool,
    },
    /// Type 4.
    Entries {
        /// The log.
        log_id: LogId,
        /// `(signed header, payload)` pairs.
        entries: Vec<(Vec<u8>, WirePayload)>,
    },
    /// Type 5.
    Ack {
        /// The log.
        log_id: LogId,
        /// The segment.
        epoch: Epoch,
        /// The highest position of that segment the receiver has taken (stored or buffered).
        complete: Pos,
    },
    /// Type 6.
    Head {
        /// The log.
        log_id: LogId,
        /// The segment.
        epoch: Epoch,
        /// Its head.
        pos: Pos,
    },
    /// Type 7.
    Ping {
        /// Nonce.
        nonce: u64,
        /// `(node, ms since heard)` liveness gossip.
        heard: Vec<(NodeId, u64)>,
    },
    /// Type 8.
    Resync {
        /// The log.
        log_id: LogId,
        /// Why the live tail stopped.
        reason: String,
    },
    /// Type 9.
    Error {
        /// Error code.
        code: String,
        /// Human text.
        message: String,
        /// The log, when the error concerns one.
        log_id: Option<LogId>,
    },
}

/// Why a frame could not be decoded.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum WireError {
    /// The length prefix exceeds `MAX_FRAME`.
    #[error("frame too large")]
    TooLarge,
    /// The body is not a valid message.
    #[error("bad message: {0}")]
    Shape(#[from] ShapeError),
    /// An unknown message type.
    #[error("unknown message type {0}")]
    UnknownType(u64),
}

impl WireError {
    /// The stable code fixtures name.
    #[must_use]
    pub fn code(&self) -> String {
        match self {
            Self::TooLarge => "too_large".into(),
            Self::Shape(shape) => shape.code(),
            Self::UnknownType(_) => "unknown_type".into(),
        }
    }
}

impl Message {
    #[allow(clippy::too_many_lines)]
    fn to_value(&self) -> Value {
        match self {
            Self::Hello(h) => map([
                (0, uint(msg::HELLO)),
                (1, uint(h.version)),
                (2, bytes(h.node_id.as_bytes())),
                (3, bytes(&h.constellation_id)),
                (
                    4,
                    Some(bcbor::sorted_set(
                        h.member_heads.iter().map(|m| Value::Bytes(m.to_vec())),
                    )),
                ),
                (5, bytes(&h.log_digest)),
                (6, uint(u64::from(h.mode == Mode::HeadsOnly))),
                (7, uint(h.store_format)),
            ]),
            Self::Have { agents, last } => map([
                (0, uint(msg::HAVE)),
                (
                    1,
                    Some(Value::Array(agents.iter().map(agent_have_value).collect())),
                ),
                (2, boolean(*last)),
            ]),
            Self::Want {
                log_id,
                epoch,
                after,
                live,
                payloads,
            } => map([
                (0, uint(msg::WANT)),
                (1, bytes(log_id.as_bytes())),
                (2, Some(epoch_value(epoch))),
                (3, Some(after.as_ref().map_or(Value::Null, pos_value))),
                (4, boolean(*live)),
                (5, boolean(*payloads)),
            ]),
            Self::Entries { log_id, entries } => map([
                (0, uint(msg::ENTRIES)),
                (1, bytes(log_id.as_bytes())),
                (
                    2,
                    Some(Value::Array(
                        entries
                            .iter()
                            .map(|(header, payload)| {
                                Value::Array(vec![
                                    Value::Bytes(header.clone()),
                                    match payload {
                                        WirePayload::Inline(b) => Value::Bytes(b.clone()),
                                        WirePayload::ByHash => Value::Uint(0),
                                        WirePayload::Pruned => Value::Uint(1),
                                    },
                                ])
                            })
                            .collect(),
                    )),
                ),
            ]),
            Self::Ack {
                log_id,
                epoch,
                complete,
            } => map([
                (0, uint(msg::ACK)),
                (1, bytes(log_id.as_bytes())),
                (2, Some(epoch_value(epoch))),
                (3, Some(pos_value(complete))),
            ]),
            Self::Head { log_id, epoch, pos } => map([
                (0, uint(msg::HEAD)),
                (1, bytes(log_id.as_bytes())),
                (2, Some(epoch_value(epoch))),
                (3, Some(pos_value(pos))),
            ]),
            Self::Ping { nonce, heard } => map([
                (0, uint(msg::PING)),
                (1, uint(*nonce)),
                (
                    2,
                    Some(Value::Array(
                        heard
                            .iter()
                            .map(|(node, ms)| {
                                Value::Array(vec![
                                    Value::Bytes(node.as_bytes().to_vec()),
                                    Value::Uint(*ms),
                                ])
                            })
                            .collect(),
                    )),
                ),
            ]),
            Self::Resync { log_id, reason } => map([
                (0, uint(msg::RESYNC)),
                (1, bytes(log_id.as_bytes())),
                (2, text(reason)),
            ]),
            Self::Error {
                code,
                message,
                log_id,
            } => map([
                (0, uint(msg::ERROR)),
                (1, text(code)),
                (2, text(message)),
                (3, log_id.as_ref().and_then(|l| bytes(l.as_bytes()))),
            ]),
        }
    }

    /// The canonical BCBOR body (without the length prefix).
    #[must_use]
    pub fn encode_body(&self) -> Vec<u8> {
        bcbor::encode(&self.to_value())
    }

    /// The framed bytes: `u32 BE` length, then the body, as [`frame_codec`] writes them.
    ///
    /// # Panics
    ///
    /// When the body is longer than `MAX_FRAME`; no message this crate builds is.
    #[must_use]
    pub fn encode_frame(&self) -> Vec<u8> {
        let mut out = BytesMut::new();
        frame_codec()
            .encode(Bytes::from(self.encode_body()), &mut out)
            .expect("a message body fits in MAX_FRAME");
        out.to_vec()
    }

    /// Decodes a body (without the length prefix).
    ///
    /// # Errors
    ///
    /// [`WireError`] for non-canonical BCBOR, a broken shape or an unknown type.
    #[allow(clippy::too_many_lines)]
    pub fn decode_body(body: &[u8]) -> Result<Self, WireError> {
        if body.len() > MAX_FRAME {
            return Err(WireError::TooLarge);
        }
        let value = bcbor::decode(body).map_err(ShapeError::from)?;
        let f = Fields::of(&value)?;
        let log = |key| f.bytes32(key).map(LogId::from_bytes);
        Ok(match f.uint(0)? {
            msg::HELLO => {
                let heads = f.array(4)?;
                bcbor::check_sorted_set(heads).map_err(ShapeError::from)?;
                let version = f.uint(1)?;
                if version != VERSION {
                    return Err(ShapeError::Invalid(format!("version {version}")).into());
                }
                Self::Hello(Hello {
                    version,
                    node_id: NodeId::from_bytes(f.bytes32(2)?),
                    constellation_id: f.bytes32(3)?,
                    member_heads: heads
                        .iter()
                        .map(|h| as_bytes32(h, 4))
                        .collect::<Result<_, _>>()?,
                    log_digest: f.bytes32(5)?,
                    mode: match f.uint(6)? {
                        0 => Mode::Live,
                        1 => Mode::HeadsOnly,
                        _ => return Err(ShapeError::Invalid("mode".into()).into()),
                    },
                    store_format: f.uint(7)?,
                })
            }
            msg::HAVE => {
                let agents = f.array(1)?;
                if agents.len() > MAX_HAVE_PER_FRAME {
                    return Err(ShapeError::Invalid("too many agents".into()).into());
                }
                Self::Have {
                    agents: agents.iter().map(agent_have).collect::<Result<_, _>>()?,
                    last: f.boolean(2)?,
                }
            }
            msg::WANT => Self::Want {
                log_id: log(1)?,
                epoch: f.epoch(2)?,
                after: f.opt_pos(3)?,
                live: f.boolean(4)?,
                payloads: f.boolean(5)?,
            },
            msg::ENTRIES => Self::Entries {
                log_id: log(1)?,
                entries: f
                    .array(2)?
                    .iter()
                    .map(|pair| match as_array(pair, 2)? {
                        [header, payload] => Ok((
                            as_bytes(header, 2)?.to_vec(),
                            match payload {
                                Value::Bytes(b) => WirePayload::Inline(b.clone()),
                                Value::Uint(0) => WirePayload::ByHash,
                                Value::Uint(1) => WirePayload::Pruned,
                                _ => return Err(ShapeError::Type(2)),
                            },
                        )),
                        _ => Err(ShapeError::Type(2)),
                    })
                    .collect::<Result<_, _>>()?,
            },
            msg::ACK => Self::Ack {
                log_id: log(1)?,
                epoch: f.epoch(2)?,
                complete: f.pos(3)?,
            },
            msg::HEAD => Self::Head {
                log_id: log(1)?,
                epoch: f.epoch(2)?,
                pos: f.pos(3)?,
            },
            msg::PING => Self::Ping {
                nonce: f.uint(1)?,
                heard: f
                    .array(2)?
                    .iter()
                    .map(|pair| match as_array(pair, 2)? {
                        [node, ms] => {
                            Ok((NodeId::from_bytes(as_bytes32(node, 2)?), as_uint(ms, 2)?))
                        }
                        _ => Err(ShapeError::Type(2)),
                    })
                    .collect::<Result<_, _>>()?,
            },
            msg::RESYNC => Self::Resync {
                log_id: log(1)?,
                reason: f.text(2)?,
            },
            msg::ERROR => Self::Error {
                code: f.text(1)?,
                message: f.text(2)?,
                log_id: f
                    .opt_bytes(3)?
                    .map(|b| LogId::from_slice(&b).ok_or(ShapeError::Type(3)))
                    .transpose()?,
            },
            other => return Err(WireError::UnknownType(other)),
        })
    }
}

fn agent_have_value(a: &AgentHave) -> Value {
    map([
        (0, bytes(a.log_id.as_bytes())),
        (1, a.agent_id.as_ref().and_then(|x| text(x.as_str()))),
        (2, a.hwm.as_ref().map(epoch_value)),
        (3, uint(a.max_n_seen)),
        (4, a.tip.as_ref().map(pos_value)),
        (5, a.complete_seq.and_then(uint)),
        (
            6,
            Some(Value::Array(
                a.segments
                    .iter()
                    .map(|s| {
                        map([
                            (0, Some(epoch_value(&s.epoch))),
                            (1, uint(s.start_seq)),
                            (2, s.parent_hash.as_ref().and_then(|h| bytes(h.as_bytes()))),
                            (3, Some(pos_value(&s.head))),
                            (4, uint(s.state)),
                            (5, uint(s.entries)),
                            (6, bytes(&s.set_xor)),
                        ])
                    })
                    .collect(),
            )),
        ),
    ])
}

fn agent_have(value: &Value) -> Result<AgentHave, ShapeError> {
    let f = Fields::of(value)?;
    Ok(AgentHave {
        log_id: LogId::from_bytes(f.bytes32(0)?),
        agent_id: f.opt_text(1)?.map(AgentId::new),
        hwm: f.opt_epoch(2)?,
        max_n_seen: f.uint(3)?,
        tip: f.opt_pos(4)?,
        complete_seq: f.opt_uint(5)?,
        segments: f
            .array(6)?
            .iter()
            .map(|s| {
                let s = Fields::of(s)?;
                Ok(SegSummary {
                    epoch: s.epoch(0)?,
                    start_seq: s.uint(1)?,
                    parent_hash: s
                        .opt_bytes(2)?
                        .map(|b| EntryHash::from_slice(&b).ok_or(ShapeError::Type(2)))
                        .transpose()?,
                    head: s.pos(3)?,
                    state: s.uint(4)?,
                    entries: s.uint(5)?,
                    set_xor: s.bytes32(6)?,
                })
            })
            .collect::<Result<_, ShapeError>>()?,
    })
}

/// The `baken/log/1` framing: `tokio_util`'s [`LengthDelimitedCodec`] with a `u32 BE` length
/// prefix (its default) and bodies of at most `MAX_FRAME` bytes. A longer prefix is refused from the
/// header alone, before its body is buffered.
#[must_use]
pub fn frame_codec() -> LengthDelimitedCodec {
    LengthDelimitedCodec::builder()
        .length_field_type::<u32>()
        .max_frame_length(MAX_FRAME)
        .new_codec()
}

/// Splits a byte stream into frame bodies with [`frame_codec`], for callers that hold bytes rather
/// than a stream.
#[derive(Debug)]
pub struct FrameReader {
    codec: LengthDelimitedCodec,
    buffer: BytesMut,
}

impl Default for FrameReader {
    fn default() -> Self {
        Self::new()
    }
}

impl FrameReader {
    /// An empty reader.
    #[must_use]
    pub fn new() -> Self {
        Self {
            codec: frame_codec(),
            buffer: BytesMut::new(),
        }
    }

    /// Appends received bytes.
    pub fn push(&mut self, bytes: &[u8]) {
        self.buffer.extend_from_slice(bytes);
    }

    /// The next complete frame body, if one is buffered.
    ///
    /// # Errors
    ///
    /// [`WireError::TooLarge`] when the length prefix exceeds `MAX_FRAME`; the stream is then
    /// unusable.
    pub fn next_body(&mut self) -> Result<Option<Vec<u8>>, WireError> {
        self.codec
            .decode(&mut self.buffer)
            .map(|body| body.map(|body| body.to_vec()))
            .map_err(|_| WireError::TooLarge)
    }
}
