//! Both ends of an Iroh link: lanes as QUIC streams, frames as core-proto's codec.

use std::collections::VecDeque;
use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use async_trait::async_trait;
use core_carrier::{
    AcceptedLink, AppClose, CallLane, CallSource, CarrierError, DialedLink, Frame, FrameSink,
    FrameSource, InboundSource, Lane, Link, LinkClosure, LinkControl, PeerLink, Protocols,
    VerifiedPeer,
};
use core_proto::NodeId;
use core_proto::capability::FRAME_PART;
use core_proto::codec::{FrameDecoder, Limits, encode_frame, encode_frame_parts};
use iroh::endpoint::{Connection, ConnectionError, RecvStream, SendStream, VarInt, WriteError};
use iroh::protocol::{AcceptError, ProtocolHandler};
use tokio::sync::{Mutex, mpsc, watch};

use crate::node_id;

/// Largest chunk read from a stream at once.
const READ_CHUNK: usize = 64 * 1024;

/// How long a node waits for a dialler's control and push lanes after the handshake.
const LANE_SETUP: Duration = Duration::from_secs(15);

/// How a link ended, from a connection error: an application close is passed through unchanged; a
/// local close is `None` (the closing end recorded its own closure); anything else is
/// [`LinkClosure::Lost`].
#[must_use]
pub fn closure_of(error: &ConnectionError) -> Option<LinkClosure> {
    match error {
        ConnectionError::ApplicationClosed(close) => Some(LinkClosure::App(AppClose::new(
            close.error_code.into_inner(),
            String::from_utf8_lossy(&close.reason),
        ))),
        ConnectionError::LocallyClosed => None,
        _ => Some(LinkClosure::Lost),
    }
}

/// What both ends of one link share: the connection and its closure.
struct State {
    connection: Connection,
    closure: watch::Sender<Option<LinkClosure>>,
}

impl State {
    fn new(connection: Connection) -> Arc<Self> {
        let (closure, _) = watch::channel(None);
        let state = Arc::new(Self {
            connection,
            closure,
        });
        let watcher = Arc::clone(&state);
        tokio::spawn(async move {
            let error = watcher.connection.closed().await;
            watcher.record(closure_of(&error).unwrap_or(LinkClosure::Lost));
        });
        state
    }

    /// Records the connection's closure if it has already closed (no wait; the watcher task records it
    /// too, and the first record wins).
    fn record_if_closed(&self) {
        if let Some(error) = self.connection.close_reason() {
            self.record(closure_of(&error).unwrap_or(LinkClosure::Lost));
        }
    }

    /// Sets the closure once.
    fn record(&self, closure: LinkClosure) {
        self.closure.send_if_modified(|current| {
            if current.is_some() {
                false
            } else {
                *current = Some(closure);
                true
            }
        });
    }

    fn closure(&self) -> Option<LinkClosure> {
        self.closure.borrow().clone()
    }

    fn ensure_open(&self) -> Result<(), CarrierError> {
        self.closure()
            .map_or(Ok(()), |closure| Err(CarrierError::Closed(closure)))
    }

    fn close(&self, close: AppClose) {
        if self.closure().is_some() {
            return;
        }
        let code = VarInt::from_u64(close.code).unwrap_or(VarInt::MAX);
        let reason = close.reason.clone();
        self.record(LinkClosure::App(close));
        self.connection.close(code, reason.as_bytes());
    }

    async fn closed(&self) -> LinkClosure {
        let mut receiver = self.closure.subscribe();
        match receiver.wait_for(Option::is_some).await {
            Ok(closure) => closure.clone().unwrap_or(LinkClosure::Lost),
            Err(_) => LinkClosure::Lost,
        }
    }

    /// The carrier error for a failed write.
    fn write_error(&self, error: &WriteError) -> CarrierError {
        match error {
            WriteError::ConnectionLost(lost) => {
                if let Some(closure) = closure_of(lost) {
                    self.record(closure);
                }
                CarrierError::Closed(self.closure().unwrap_or(LinkClosure::Lost))
            }
            _ => match self.closure() {
                Some(closure) => CarrierError::Closed(closure),
                None => CarrierError::Finished,
            },
        }
    }

    fn opening_error(&self, error: &ConnectionError) -> CarrierError {
        if let Some(closure) = closure_of(error) {
            self.record(closure);
        }
        CarrierError::Closed(self.closure().unwrap_or(LinkClosure::Lost))
    }
}

/// Whether frames may go out as `frame_part`s, and how much a reader may reassemble.
#[derive(Clone)]
struct Encoding {
    peer_frame_part: Arc<AtomicBool>,
}

impl Encoding {
    fn new(peer_frame_part: bool) -> Self {
        Self {
            peer_frame_part: Arc::new(AtomicBool::new(peer_frame_part)),
        }
    }

    fn parts_allowed(&self) -> bool {
        self.peer_frame_part.load(Ordering::Acquire)
    }

    fn encode(&self, frame: &Frame) -> Result<Vec<Vec<u8>>, CarrierError> {
        let encoded = if self.parts_allowed() {
            encode_frame_parts(frame.as_str(), Limits::DEFAULT)
        } else {
            encode_frame(frame.as_str(), Limits::DEFAULT).map(|bytes| vec![bytes])
        };
        encoded.map_err(|error| CarrierError::Frame(error.to_string()))
    }

    fn limits(&self) -> Limits {
        if self.parts_allowed() {
            Limits::DEFAULT
        } else {
            Limits::PRE_AUTH
        }
    }
}

/// The writing half of one stream.
struct StreamSink {
    send: Mutex<SendStream>,
    state: Arc<State>,
    encoding: Encoding,
}

impl StreamSink {
    fn new(send: SendStream, state: &Arc<State>, encoding: &Encoding) -> Self {
        Self {
            send: Mutex::new(send),
            state: Arc::clone(state),
            encoding: encoding.clone(),
        }
    }

    async fn write(&self, frame: &Frame) -> Result<(), CarrierError> {
        self.state.ensure_open()?;
        let parts = self.encoding.encode(frame)?;
        let mut send = self.send.lock().await;
        for part in parts {
            send.write_all(&part)
                .await
                .map_err(|error| self.state.write_error(&error))?;
        }
        Ok(())
    }
}

#[async_trait]
impl FrameSink for StreamSink {
    async fn send(&self, frame: Frame) -> Result<(), CarrierError> {
        self.write(&frame).await
    }

    async fn finish(&self) -> Result<(), CarrierError> {
        self.state.ensure_open()?;
        self.send
            .lock()
            .await
            .finish()
            .map_err(|_| CarrierError::Finished)
    }
}

/// The reading half of one stream. A protocol error in the stream ends it (the source completes).
struct StreamSource {
    recv: RecvStream,
    state: Arc<State>,
    decoder: Option<FrameDecoder>,
    pending: VecDeque<Frame>,
    encoding: Encoding,
    widened: bool,
}

impl StreamSource {
    fn new(recv: RecvStream, state: &Arc<State>, encoding: &Encoding) -> Self {
        Self {
            recv,
            state: Arc::clone(state),
            decoder: Some(FrameDecoder::new(encoding.limits())),
            pending: VecDeque::new(),
            encoding: encoding.clone(),
            widened: encoding.parts_allowed(),
        }
    }
}

#[async_trait]
impl FrameSource for StreamSource {
    async fn recv(&mut self) -> Option<Frame> {
        loop {
            if let Some(frame) = self.pending.pop_front() {
                return Some(frame);
            }
            let decoder = self.decoder.as_mut()?;
            if !self.widened && self.encoding.parts_allowed() {
                decoder.set_limits(Limits::DEFAULT);
                self.widened = true;
            }
            if let Ok(Some(chunk)) = self.recv.read_chunk(READ_CHUNK).await {
                if let Ok(frames) = decoder.push(&chunk) {
                    self.pending.extend(frames.into_iter().map(Frame::from));
                } else {
                    self.decoder = None;
                    let _ = self.recv.stop(VarInt::from_u32(0));
                }
            } else {
                self.decoder = None;
                // The lane ended: if the connection is already closed, its closure is recorded
                // before this source reports the end, so a reader that sees the end sees why.
                self.state.record_if_closed();
            }
        }
    }
}

/// Opens the dialler's lanes on a fresh connection: the control stream, then the push stream with
/// its send half finished at once (that FIN is what lets the node accept both).
pub(crate) async fn dial_lanes(
    connection: Connection,
    expected: NodeId,
) -> Result<DialedLink, CarrierError> {
    let remote = node_id(connection.remote_id());
    if remote != expected {
        connection.close(VarInt::from_u32(0), b"closed");
        return Err(CarrierError::Unreachable(format!(
            "dialled {expected}, reached {remote}"
        )));
    }
    let alpn = connection.alpn().to_vec();
    let state = State::new(connection);
    let (control_send, control_recv) = state
        .connection
        .open_bi()
        .await
        .map_err(|error| state.opening_error(&error))?;
    let (mut push_send, push_recv) = state
        .connection
        .open_bi()
        .await
        .map_err(|error| state.opening_error(&error))?;
    push_send.finish().map_err(|_| CarrierError::Finished)?;

    // A dialler always reassembles parts and may always send them (it advertises `frame_part`).
    let encoding = Encoding::new(true);
    let (inbound_tx, inbound_rx) = mpsc::unbounded_channel();
    for (lane, recv) in [(Lane::Control, control_recv), (Lane::Push, push_recv)] {
        let mut source = StreamSource::new(recv, &state, &encoding);
        let tx = inbound_tx.clone();
        tokio::spawn(async move {
            while let Some(frame) = source.recv().await {
                if tx.send((lane, frame)).is_err() {
                    break;
                }
            }
        });
    }
    let link = Arc::new(DialEnd {
        control: StreamSink::new(control_send, &state, &encoding),
        state,
        remote: VerifiedPeer::Node(remote),
        alpn,
        encoding,
        _push_send: push_send,
    });
    Ok(DialedLink {
        link,
        inbound: Box::new(Inbound(inbound_rx)),
    })
}

struct Inbound(mpsc::UnboundedReceiver<(Lane, Frame)>);

#[async_trait]
impl InboundSource for Inbound {
    async fn recv(&mut self) -> Option<(Lane, Frame)> {
        self.0.recv().await
    }
}

/// The dialling end.
struct DialEnd {
    state: Arc<State>,
    remote: VerifiedPeer,
    alpn: Vec<u8>,
    control: StreamSink,
    encoding: Encoding,
    // Held so the finished push stream is not reset by a drop before the node accepts it.
    _push_send: SendStream,
}

impl Drop for DialEnd {
    fn drop(&mut self) {
        // A link nobody holds any more is a detach.
        self.state.close(AppClose::closed());
    }
}

#[async_trait]
impl LinkControl for DialEnd {
    fn remote(&self) -> VerifiedPeer {
        self.remote
    }

    fn alpn(&self) -> &[u8] {
        &self.alpn
    }

    fn close(&self, close: AppClose) {
        self.state.close(close);
    }

    fn closure(&self) -> Option<LinkClosure> {
        self.state.closure()
    }

    async fn closed(&self) -> LinkClosure {
        self.state.closed().await
    }
}

#[async_trait]
impl Link for DialEnd {
    async fn send_control(&self, frame: Frame) -> Result<(), CarrierError> {
        self.control.write(&frame).await
    }

    async fn open_call(&self, request: Frame) -> Result<CallLane, CarrierError> {
        self.state.ensure_open()?;
        let (send, recv) = self
            .state
            .connection
            .open_bi()
            .await
            .map_err(|error| self.state.opening_error(&error))?;
        let sink = StreamSink::new(send, &self.state, &self.encoding);
        sink.write(&request).await?;
        Ok(CallLane {
            sink: Box::new(sink),
            source: Box::new(StreamSource::new(recv, &self.state, &self.encoding)),
        })
    }
}

/// Serves one ALPN's connections from iroh's router through the node's [`Protocols`].
pub(crate) struct RouterAdapter {
    protocols: Arc<Protocols>,
}

impl RouterAdapter {
    pub(crate) fn new(protocols: Arc<Protocols>) -> Self {
        Self { protocols }
    }
}

impl fmt::Debug for RouterAdapter {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RouterAdapter")
            .field("protocols", &self.protocols)
            .finish()
    }
}

impl ProtocolHandler for RouterAdapter {
    async fn accept(&self, connection: Connection) -> Result<(), AcceptError> {
        let remote = VerifiedPeer::Node(node_id(connection.remote_id()));
        let alpn = connection.alpn().to_vec();
        let lanes = tokio::time::timeout(LANE_SETUP, async {
            let control = connection.accept_bi().await?;
            let push = connection.accept_bi().await?;
            Ok::<_, ConnectionError>((control, push))
        })
        .await;
        let Ok(Ok(((control_send, control_recv), (push_send, mut push_recv)))) = lanes else {
            // No session lanes: the dialler is gone or never opened them.
            return Ok(());
        };
        // The push lane's receive half carries only the dialler's FIN; read at most one byte.
        tokio::spawn(async move {
            let mut byte = [0_u8; 1];
            let _ = push_recv.read(&mut byte).await;
        });
        let state = State::new(connection);
        let encoding = Encoding::new(false);
        let link = Arc::new(ServeEnd {
            control: StreamSink::new(control_send, &state, &encoding),
            push: StreamSink::new(push_send, &state, &encoding),
            state: Arc::clone(&state),
            remote,
            alpn,
            encoding: encoding.clone(),
        });
        let accepted = AcceptedLink {
            link,
            control: Box::new(StreamSource::new(control_recv, &state, &encoding)),
            calls: Box::new(Calls { state, encoding }),
        };
        self.protocols.serve(accepted).await;
        Ok(())
    }
}

/// The call lanes a dialler opens: every stream after the control and push lanes.
struct Calls {
    state: Arc<State>,
    encoding: Encoding,
}

#[async_trait]
impl CallSource for Calls {
    async fn accept(&mut self) -> Option<CallLane> {
        let (send, recv) = self.state.connection.accept_bi().await.ok()?;
        Some(CallLane {
            sink: Box::new(StreamSink::new(send, &self.state, &self.encoding)),
            source: Box::new(StreamSource::new(recv, &self.state, &self.encoding)),
        })
    }
}

/// The serving (node) end.
struct ServeEnd {
    state: Arc<State>,
    remote: VerifiedPeer,
    alpn: Vec<u8>,
    control: StreamSink,
    push: StreamSink,
    encoding: Encoding,
}

#[async_trait]
impl LinkControl for ServeEnd {
    fn remote(&self) -> VerifiedPeer {
        self.remote
    }

    fn alpn(&self) -> &[u8] {
        &self.alpn
    }

    fn close(&self, close: AppClose) {
        self.state.close(close);
    }

    fn closure(&self) -> Option<LinkClosure> {
        self.state.closure()
    }

    async fn closed(&self) -> LinkClosure {
        self.state.closed().await
    }
}

#[async_trait]
impl PeerLink for ServeEnd {
    async fn send_control(&self, frame: Frame) -> Result<(), CarrierError> {
        self.control.write(&frame).await
    }

    async fn push(&self, frame: Frame) -> Result<(), CarrierError> {
        self.push.write(&frame).await
    }

    fn on_peer_capabilities(&self, capabilities: &[String]) {
        let frame_part = capabilities
            .iter()
            .any(|capability| capability == FRAME_PART);
        self.encoding
            .peer_frame_part
            .store(frame_part, Ordering::Release);
    }
}
