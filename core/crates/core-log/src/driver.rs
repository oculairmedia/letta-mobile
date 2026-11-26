//! The async driver of one `baken/log/1` session, generic over a minimal byte-stream link.
//!
//! [`ByteRead`] and [`ByteWrite`] are the two halves of one bidirectional stream (a QUIC stream
//! under Iroh). They are deliberately the smallest surface the protocol needs; when `core-carrier`
//! lands, its `Link` streams implement them through a thin adapter, so this driver does not depend
//! on any carrier. [`Clock`] supplies time and timers, so the driver runs on any executor (tokio on
//! nodes, the browser's event loop for wasm, a manual clock in tests).
//!
//! Each received frame is handled in one store transaction, committed before any reply (an `ack`)
//! is sent. Entries a session stores are published on the node's [`Hub`], which hands them to its
//! other sessions: every replica relays.

use std::future::Future;
use std::sync::Mutex;

use core_store::{EntryHash, LogId, Store, StoreError};
use futures::channel::mpsc;
use futures::{FutureExt as _, StreamExt as _};
use thiserror::Error;

use crate::judge::ClaimJudge;
use crate::replicator::{Session, SessionConfig, SessionEvent};
use crate::wire::{FrameReader, Message, WireError};

/// A link failure.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
#[error("link: {0}")]
pub struct LinkError(pub String);

/// The receiving half of a byte stream.
pub trait ByteRead {
    /// Reads at least one byte into `buf`, or returns 0 at end of stream.
    fn read(&mut self, buf: &mut [u8]) -> impl Future<Output = Result<usize, LinkError>>;
}

/// The sending half of a byte stream.
pub trait ByteWrite {
    /// Writes all of `bytes`.
    fn write_all(&mut self, bytes: &[u8]) -> impl Future<Output = Result<(), LinkError>>;
    /// Finishes the stream.
    fn close(&mut self) -> impl Future<Output = ()>;
}

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
    /// The link failed.
    #[error(transparent)]
    Link(#[from] LinkError),
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
    mut writer: W,
    store: &S,
    judge: &J,
    hub: &Hub,
    clock: &C,
    config: SessionConfig,
    mut observe: F,
) -> Result<SessionEnd, DriverError>
where
    R: ByteRead,
    W: ByteWrite,
    S: Store,
    J: ClaimJudge + ?Sized,
    C: Clock,
    F: FnMut(&SessionEvent),
{
    let mut subscription = hub.subscribe();
    let (inbound_tx, mut inbound_rx) = mpsc::unbounded::<Inbound>();
    let read_side = read_frames(reader, inbound_tx).fuse();
    let main = async {
        let mut session = Session::new(config);
        store.read(|txn| session.start(txn, clock.now_ms()))?;
        loop {
            while let Some(message) =
                store.read(|txn| session.poll_transmit(txn, clock.now_ms()))?
            {
                writer.write_all(&message.encode_frame()).await?;
            }
            if session.is_closing() {
                writer.close().await;
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

async fn read_frames<R: ByteRead>(mut reader: R, inbound: mpsc::UnboundedSender<Inbound>) {
    let mut frames = FrameReader::new();
    let mut buffer = vec![0_u8; 64 * 1024];
    loop {
        match reader.read(&mut buffer).await {
            Ok(0) => {
                let _ = inbound.unbounded_send(Inbound::Eof);
                break;
            }
            Ok(read) => {
                frames.push(&buffer[..read]);
                loop {
                    match frames.next_body() {
                        Ok(Some(body)) => {
                            let item = Message::decode_body(&body)
                                .map_or_else(|e| Inbound::Failed(e.into()), Inbound::Frame);
                            let failed = matches!(item, Inbound::Failed(_));
                            let _ = inbound.unbounded_send(item);
                            if failed {
                                futures::future::pending::<()>().await;
                            }
                        }
                        Ok(None) => break,
                        Err(error) => {
                            let _ = inbound.unbounded_send(Inbound::Failed(error.into()));
                            futures::future::pending::<()>().await;
                        }
                    }
                }
            }
            Err(error) => {
                let _ = inbound.unbounded_send(Inbound::Failed(error.into()));
                break;
            }
        }
    }
    // Keep the select alive until the main loop has drained what was read.
    futures::future::pending::<()>().await;
}
