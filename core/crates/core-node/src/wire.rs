//! The node's own byte-stream protocols and their one-shot exchanges.
//!
//! `baken/log/1` is core-log's replicator (its driver runs over the link's stream).
//! `baken/place/1` and `baken/member/1` carry one message per stream: the dialler writes one
//! `u32 BE` length-prefixed BCBOR body and closes (C1 §5.10: one stream per exchange; replies are
//! exchanges of their own). Framing is `tokio_util`'s length-delimited codec, as for the log.

use std::collections::BTreeMap;

use bytes::Bytes;
use core_carrier::{ByteStream, Carrier, CarrierError, NodeAddr};
use core_log::MAX_FRAME;
use core_membership::{OpId, SignedOp};
use core_placement::PlaceMessage;
use core_proto::bcbor::{self, Value};
use futures::{SinkExt as _, StreamExt as _};
use tokio::io::AsyncWriteExt as _;
use tokio_util::codec::{Framed, LengthDelimitedCodec};

/// `baken/log/1`: the agent-log replicator.
pub const LOG_ALPN: &[u8] = b"baken/log/1";
/// `baken/place/1`: moves, claims and fenced reports.
pub const PLACE_ALPN: &[u8] = b"baken/place/1";
/// `baken/member/1`: membership op push (anti-entropy).
pub const MEMBER_ALPN: &[u8] = b"baken/member/1";

fn codec() -> LengthDelimitedCodec {
    LengthDelimitedCodec::builder()
        .length_field_length(4)
        .big_endian()
        .max_frame_length(MAX_FRAME)
        .new_codec()
}

/// Dials `target` on `alpn`, writes one framed body and closes the stream.
///
/// # Errors
///
/// [`CarrierError`] when the dial fails; an I/O failure after the dial is reported as
/// [`CarrierError::Unreachable`].
pub async fn send_one(
    carrier: &dyn Carrier,
    target: &NodeAddr,
    alpn: &[u8],
    body: Vec<u8>,
) -> Result<(), CarrierError> {
    let link = carrier.dial_stream(target, alpn).await?;
    let mut framed = Framed::new(link.stream, codec());
    let io = |e: std::io::Error| CarrierError::Unreachable(e.to_string());
    framed.send(Bytes::from(body)).await.map_err(io)?;
    let mut stream = framed.into_inner();
    stream.shutdown().await.map_err(io)?;
    // Wait for the other end to finish reading (it closes its side).
    let mut sink = Vec::new();
    let _ = tokio::io::AsyncReadExt::read_to_end(&mut stream, &mut sink).await;
    Ok(())
}

/// Reads the one framed body of an accepted stream.
pub async fn read_one(stream: Box<dyn ByteStream>) -> Option<Vec<u8>> {
    let mut framed = Framed::new(stream, codec());
    let body = framed.next().await?.ok()?;
    let mut stream = framed.into_inner();
    let _ = stream.shutdown().await;
    Some(body.to_vec())
}

/// Encodes a place message body.
#[must_use]
pub fn encode_place(message: &PlaceMessage) -> Vec<u8> {
    message.encode_body()
}

/// A `baken/member/1` push: the sender's heads and the ops it sends (C9's anti-entropy flow:
/// a receiver whose heads the sender lacks pushes everything back).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MemberPush {
    /// The sender's membership heads.
    pub heads: Vec<OpId>,
    /// Signed op envelopes.
    pub ops: Vec<SignedOp>,
}

impl MemberPush {
    /// `{0: 1, 1: [head bstr32…], 2: [envelope bstr…]}`.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut map = BTreeMap::new();
        map.insert(0, Value::Uint(1));
        map.insert(
            1,
            Value::Array(
                self.heads
                    .iter()
                    .map(|h| Value::Bytes(h.as_bytes().to_vec()))
                    .collect(),
            ),
        );
        map.insert(
            2,
            Value::Array(
                self.ops
                    .iter()
                    .map(|op| Value::Bytes(op.envelope()))
                    .collect(),
            ),
        );
        bcbor::encode(&Value::Map(map))
    }

    /// Decodes a push; undecodable ops are dropped (each op is verified on insert anyway).
    #[must_use]
    pub fn decode(body: &[u8]) -> Option<Self> {
        let Value::Map(map) = bcbor::decode(body).ok()? else {
            return None;
        };
        if map.get(&0) != Some(&Value::Uint(1)) {
            return None;
        }
        let heads = match map.get(&1) {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|item| match item {
                    Value::Bytes(b) => <[u8; 32]>::try_from(b.as_slice())
                        .ok()
                        .map(OpId::from_bytes),
                    _ => None,
                })
                .collect(),
            _ => return None,
        };
        let ops = match map.get(&2) {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|item| match item {
                    Value::Bytes(b) => SignedOp::decode_envelope(b).ok(),
                    _ => None,
                })
                .collect(),
            _ => return None,
        };
        Some(Self { heads, ops })
    }
}
