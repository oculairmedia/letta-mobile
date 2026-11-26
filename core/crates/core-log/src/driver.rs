//! The async driver of one `baken/log/1` session, generic over a byte stream.
//!
//! The stream is the two halves of one bidirectional stream (a QUIC stream under Iroh) as tokio's
//! [`AsyncRead`] and [`AsyncWrite`], framed by `tokio_util`'s [`FramedRead`] and [`FramedWrite`]
//! with the log's length-delimited codec ([`crate::wire::frame_codec`]). Neither needs a tokio
//! runtime. [`Clock`] supplies time and timers, so the driver runs on any executor (tokio on nodes,
//! the browser's event loop for wasm, a manual clock in tests).
//!
//! Each received frame is handled in one store transaction, committed before any reply (an `ack`)
//! is sent. Entries a session stores are published on the node's [`Hub`], which hands them to its
//! other sessions: every replica relays.

use std::future::Future;
use std::sync::Mutex;

use bytes::Bytes;
use core_store::{EntryHash, LogId, Store, StoreError};
use futures::channel::mpsc;
use futures::{FutureExt as _, SinkExt, StreamExt as _};
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_util::codec::{FramedRead, FramedWrite, LengthDelimitedCodecError};

use crate::judge::ClaimJudge;
use crate::replicator::{Session, SessionConfig, SessionEvent};
use crate::wire::{self, Message, WireError};

/// Time for the driver.
pub trait Clock {
    /// Monotonic milliseconds.
    fn now_ms(&self) -> u64;
    /// Resolves at or after `at_ms`.
    fn sleep_until(&self, at_ms: u64) -> impl Future<Output = ()>;
}

/// Entries newly stored on this node.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Notice {
    /// The log.
    pub log_id: LogId,
    /// The entries.
    pub entries: Vec<EntryHash>,
}

type Subscribers = Vec<(u64, mpsc::UnboundedSender<Notice>)>;

/// A subscription to a [`Hub`].
#[derive(Debug)]
pub struct Subscription {
    id: u64,
    receiver: mpsc::UnboundedReceiver<Notice>,
}

/// Fans stored-entry notices out to every session of a node (relaying), and to the holder's own
/// appends.
#[derive(Debug, Default)]
pub struct Hub {
    inner: Mutex<(u64, Subscribers)>,
}

impl Hub {
    /// An empty hub.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Subscribes a session.
    pub fn subscribe(&self) -> Subscription {
        let (sender, receiver) = mpsc::unbounded();
        let mut inner = self
            .inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        inner.0 += 1;
        let id = inner.0;
        inner.1.push((id, sender));
        Subscription { id, receiver }
    }

    /// Publishes a notice to every subscriber except `from` (the session that stored it). The
    /// holder publishes its own appends with `from = None` after committing them.
    pub fn publish(&self, from: Option<u64>, notice: &Notice) {
        let mut inner = self
            .inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        inner.1.retain(|(id, sender)| {
            Some(*id) == from || sender.unbounded_send(notice.clone()).is_ok()
        });
    }
}

/// How a session ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionEnd {
    /// The peer finished the stream.
    PeerClosed,
    /// This side closed it (constellation mismatch).
    Closed,
}

/// Why a session failed.
#[derive(Debug, Error)]
pub enum DriverError {
    /// The stream failed.
    #[error("link: {0}")]
    Link(#[from] std::io::Error),
    /// The peer sent an undecodable frame.
    #[error("wire: {0}")]
    Wire(#[from] WireError),
    /// The store failed; the transaction was rolled back.
    #[error("store: {0}")]
    Store(#[from] StoreError),
}

enum Inbound {
    Frame(Message),
    Eof,
    Failed(DriverError),
}

/// Runs one session until the stream ends. `observe` sees every [`SessionEvent`] (evaluations,
/// rejections, membership differences) after its transaction committed.
///
/// # Errors
///
/// [`DriverError`]; the session is then over and a new one resumes statelessly.
#[allow(clippy::too_many_arguments)]
pub async fn run_session<R, W, S, J, C, F>(
    reader: R,
    writer: W,
    store: &S,
    judge: &J,
    hub: &Hub,
    clock: &C,
    config: SessionConfig,
    mut observe: F,
) -> Result<SessionEnd, DriverError>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
    S: Store,
    J: ClaimJudge + ?Sized,
    C: Clock,
    F: FnMut(&SessionEvent),
{
    let mut subscription = hub.subscribe();
    let mut writer = FramedWrite::new(writer, wire::frame_codec());
    let (inbound_tx, mut inbound_rx) = mpsc::unbounded::<Inbound>();
    let read_side = read_frames(reader, inbound_tx).fuse();
    let main = async {
        let mut session = Session::new(config);
        store.read(|txn| session.start(txn, clock.now_ms()))?;
        loop {
            while let Some(message) =
                store.read(|txn| session.poll_transmit(txn, clock.now_ms()))?
            {
                writer.send(Bytes::from(message.encode_body())).await?;
            }
            if session.is_closing() {
                // The session is over either way; a failed finish changes nothing.
                let _ = SinkExt::<Bytes>::close(&mut writer).await;
                return Ok(SessionEnd::Closed);
            }
            let now = clock.now_ms();
            let deadline = session.tick(now);
            if deadline <= now {
                continue;
            }
            let sleep = clock.sleep_until(deadline).fuse();
            futures::pin_mut!(sleep);
            futures::select_biased! {
                inbound = inbound_rx.next() => match inbound {
                    Some(Inbound::Frame(message)) => {
                        let now = clock.now_ms();
                        let events = store.write(|txn| session.handle(txn, judge, message, now))?;
                        for event in &events {
                            if let SessionEvent::Stored { log_id, entries } = event {
                                hub.publish(Some(subscription.id), &Notice {
                                    log_id: *log_id,
                                    entries: entries.clone(),
                                });
                            }
                            observe(event);
                        }
                    }
                    Some(Inbound::Failed(error)) => return Err(error),
                    Some(Inbound::Eof) | None => return Ok(SessionEnd::PeerClosed),
                },
                notice = subscription.receiver.next() => {
                    if let Some(notice) = notice {
                        store.read(|txn| session.on_stored(txn, notice.log_id, &notice.entries))?;
                    }
                },
                () = sleep => {},
            }
        }
    }
    .fuse();
    futures::pin_mut!(read_side, main);
    futures::select_biased! {
        result = main => result,
        () = read_side => Ok(SessionEnd::PeerClosed),
    }
}

/// Reads frames concurrently with the main loop (so a peer blocked writing is always read), and
/// hands each decoded message, the end of stream or the first failure to it.
async fn read_frames<R: AsyncRead + Unpin>(reader: R, inbound: mpsc::UnboundedSender<Inbound>) {
    let mut frames = FramedRead::new(reader, wire::frame_codec());
    loop {
        let item = match frames.next().await {
            None => Inbound::Eof,
            Some(Ok(body)) => Message::decode_body(&body)
                .map_or_else(|e| Inbound::Failed(e.into()), Inbound::Frame),
            Some(Err(error)) => Inbound::Failed(frame_error(error)),
        };
        let last = !matches!(item, Inbound::Frame(_));
        let _ = inbound.unbounded_send(item);
        if last {
            break;
        }
    }
    // Keep the select alive until the main loop has drained what was read.
    futures::future::pending::<()>().await;
}

/// The error behind a failed frame read: the codec reports an oversized length prefix as an I/O
/// error wrapping its own error type, which is the wire's [`WireError::TooLarge`].
fn frame_error(error: std::io::Error) -> DriverError {
    let too_large = error
        .get_ref()
        .and_then(|inner| inner.downcast_ref::<LengthDelimitedCodecError>())
        .is_some();
    if too_large {
        WireError::TooLarge.into()
    } else {
        error.into()
    }
}
