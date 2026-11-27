//! A byte stream over one carrier call lane: the binding between core-log's session driver, which
//! runs over tokio's `AsyncRead` and `AsyncWrite`, and core-carrier's [`CallLane`], which moves
//! UTF-8 frames.
//!
//! The driver reads and writes one half of a tokio [`duplex`] pipe. Two pump tasks move bytes
//! between the other half and the lane: each chunk the driver writes crosses as one frame holding
//! the chunk's hex, and each frame received is decoded and written back into the pipe. The pumps
//! never parse the log protocol; the outbound pump only runs the log's own frame codec over a copy
//! of the bytes to count frames (pings apart from the rest) for the idle invariant.
//!
//! Ends map onto each other: when the lane ends (the peer finished, or the link was lost) the
//! driver reads end of stream; when the driver drops its halves the lane is finished.

use std::cell::RefCell;
use std::rc::Rc;

use bytes::BytesMut;
use core_carrier::{CallLane, Frame};
use core_log::Message;
use core_log::wire::frame_codec;
use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _, DuplexStream, ReadHalf, WriteHalf, duplex};
use tokio::task::{AbortHandle, spawn_local};
use tokio_util::codec::{Decoder as _, LengthDelimitedCodec};

/// The pipe buffer between the driver and the pumps.
pub const PIPE_BUFFER: usize = 64 * 1024;

/// The largest chunk the outbound pump puts in one frame.
pub const CHUNK: usize = 16 * 1024;

/// What crossed a node's lanes outbound, by `baken/log/1` message class.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FrameCounts {
    /// `ping` messages.
    pub pings: u64,
    /// Every other message.
    pub other: u64,
    /// Bytes of the log protocol (before the hex text encoding).
    pub bytes: u64,
}

/// A shared counter the pumps of one node add to.
pub type Tap = Rc<RefCell<FrameCounts>>;

/// The driver's ends of a bound lane, and the pump tasks to abort on a crash.
#[derive(Debug)]
pub struct LaneStream {
    /// The driver reads the peer's bytes here.
    pub reader: ReadHalf<DuplexStream>,
    /// The driver writes its bytes here.
    pub writer: WriteHalf<DuplexStream>,
    /// The two pump tasks.
    pub pumps: [AbortHandle; 2],
}

/// Binds `lane` to a byte stream. Must run inside a tokio `LocalSet`. The lane's request frame, if
/// it has one, must already have been read.
#[must_use]
pub fn bind(lane: CallLane, tap: Tap) -> LaneStream {
    let (ours, theirs) = duplex(PIPE_BUFFER);
    let (reader, writer) = tokio::io::split(ours);
    let (mut pipe_read, mut pipe_write) = tokio::io::split(theirs);
    let CallLane { sink, mut source } = lane;
    let outbound = spawn_local(async move {
        let mut chunk = vec![0_u8; CHUNK];
        let mut counter = Counter::new();
        loop {
            match pipe_read.read(&mut chunk).await {
                Ok(0) | Err(_) => {
                    // The peer learns the end from the finished lane; a lane already gone is the
                    // same end.
                    let _ = sink.finish().await;
                    break;
                }
                Ok(n) => {
                    counter.feed(&tap, &chunk[..n]);
                    if sink
                        .send(Frame::new(hex::encode(&chunk[..n])))
                        .await
                        .is_err()
                    {
                        break;
                    }
                }
            }
        }
    });
    let inbound = spawn_local(async move {
        while let Some(frame) = source.recv().await {
            let Ok(bytes) = hex::decode(frame.as_str()) else {
                break;
            };
            if pipe_write.write_all(&bytes).await.is_err() {
                break;
            }
        }
        let _ = pipe_write.shutdown().await;
    });
    LaneStream {
        reader,
        writer,
        pumps: [outbound.abort_handle(), inbound.abort_handle()],
    }
}

/// Reassembles the outbound byte stream into log frames to count them. The codec keeps its state
/// between chunks (a frame's length prefix may arrive in one chunk and its body in the next).
struct Counter {
    buffer: BytesMut,
    codec: LengthDelimitedCodec,
}

impl Counter {
    fn new() -> Self {
        Self {
            buffer: BytesMut::new(),
            codec: frame_codec(),
        }
    }

    fn feed(&mut self, tap: &Tap, bytes: &[u8]) {
        self.buffer.extend_from_slice(bytes);
        let mut counts = tap.borrow_mut();
        counts.bytes += bytes.len() as u64;
        while let Ok(Some(body)) = self.codec.decode(&mut self.buffer) {
            match Message::decode_body(&body) {
                Ok(Message::Ping { .. }) => counts.pings += 1,
                _ => counts.other += 1,
            }
        }
    }
}
