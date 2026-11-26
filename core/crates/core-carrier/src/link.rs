//! The carrier traits: dialling, the two ends of a link, and their lanes.
//!
//! Receivers are returned as owned values next to the link (in [`DialedLink`], [`AcceptedLink`] and
//! [`CallLane`]), so each lane has exactly one collector by construction.

use std::sync::Arc;

use async_trait::async_trait;

use crate::{AppClose, CarrierError, Frame, Lane, LinkClosure, NodeAddr, StreamLink, VerifiedPeer};

/// Dials nodes. One carrier per local endpoint; its dials all carry the endpoint's identity.
#[async_trait]
pub trait Carrier: Send + Sync {
    /// The identity this carrier's links prove to the nodes it dials.
    fn local(&self) -> VerifiedPeer;

    /// Opens a link to `target` for the protocol `alpn`.
    ///
    /// Opens the control and push lanes and sends nothing: the first frame is the caller's. A node
    /// that refuses the dialler may fail the dial with [`CarrierError::Closed`] or return a link
    /// that closes with the node's closure.
    ///
    /// # Errors
    ///
    /// [`CarrierError::Unreachable`] when the carrier does not dial `target` or no node answers;
    /// [`CarrierError::NoProtocol`] when the node does not serve `alpn`; [`CarrierError::Closed`]
    /// when the node refused the dialler before the dial returned.
    async fn dial(&self, target: &NodeAddr, alpn: &[u8]) -> Result<DialedLink, CarrierError>;

    /// Opens a link to `target` for the byte-stream protocol `alpn`: one raw stream, no lanes.
    ///
    /// The default refuses every ALPN; carriers that carry byte-stream protocols override it.
    ///
    /// # Errors
    ///
    /// As for [`dial`](Self::dial).
    async fn dial_stream(
        &self,
        target: &NodeAddr,
        alpn: &[u8],
    ) -> Result<StreamLink, CarrierError> {
        let _ = target;
        Err(CarrierError::NoProtocol(
            String::from_utf8_lossy(alpn).into_owned(),
        ))
    }
}

/// What both ends of a link have: the other end's identity, the protocol, and closure.
#[async_trait]
pub trait LinkControl: Send + Sync {
    /// The other end, as the transport proved it.
    fn remote(&self) -> VerifiedPeer;

    /// The protocol (ALPN) this link speaks.
    fn alpn(&self) -> &[u8];

    /// Closes the link with `close`, once; later calls do nothing. When this returns,
    /// [`closure`](Self::closure) is already set at this end. Frames sent before are delivered
    /// before the close wherever the transport allows. Closing never cancels node work.
    fn close(&self, close: AppClose);

    /// How the link ended, or `None` while it is open.
    fn closure(&self) -> Option<LinkClosure>;

    /// Waits until the link ends and returns how.
    async fn closed(&self) -> LinkClosure;
}

/// The dialling end of a link (a shell, or a node dialling another node).
#[async_trait]
pub trait Link: LinkControl {
    /// Writes a frame on the control lane.
    ///
    /// # Errors
    ///
    /// [`CarrierError::Closed`] after the link ended; [`CarrierError::Frame`] when the frame is too
    /// large for the transport.
    async fn send_control(&self, frame: Frame) -> Result<(), CarrierError>;

    /// Opens a new call lane and writes `request` as its first frame.
    ///
    /// # Errors
    ///
    /// [`CarrierError::Closed`] after the link ended.
    async fn open_call(&self, request: Frame) -> Result<CallLane, CarrierError>;
}

/// The accepting end of a link: the node serving a peer.
#[async_trait]
pub trait PeerLink: LinkControl {
    /// Writes a frame on the control lane.
    ///
    /// # Errors
    ///
    /// [`CarrierError::Closed`] after the link ended; [`CarrierError::Frame`] when the frame cannot
    /// be sent to this peer.
    async fn send_control(&self, frame: Frame) -> Result<(), CarrierError>;

    /// Writes a frame on the push lane. Push frames arrive in the order they were pushed.
    ///
    /// # Errors
    ///
    /// As for [`send_control`](Self::send_control).
    async fn push(&self, frame: Frame) -> Result<(), CarrierError>;

    /// Tells the carrier what the peer advertised in its accepted `auth`. The node calls it once,
    /// after the peer authenticated; the carrier trusts it. The Iroh carrier splits node-to-peer
    /// frames into `frame_part`s only after `frame_part` was reported here.
    fn on_peer_capabilities(&self, capabilities: &[String]);
}

/// The sending half of a call lane.
#[async_trait]
pub trait FrameSink: Send + Sync {
    /// Writes a frame on this lane.
    ///
    /// # Errors
    ///
    /// [`CarrierError::Closed`] after the link ended or after [`finish`](Self::finish);
    /// [`CarrierError::Frame`] when the frame cannot be sent.
    async fn send(&self, frame: Frame) -> Result<(), CarrierError>;

    /// Ends this half: the other end's source completes after the frames already sent.
    ///
    /// # Errors
    ///
    /// [`CarrierError::Closed`] after the link ended.
    async fn finish(&self) -> Result<(), CarrierError>;
}

/// The receiving half of a lane.
#[async_trait]
pub trait FrameSource: Send {
    /// The next frame, or `None` once the other end finished sending or the link ended.
    async fn recv(&mut self) -> Option<Frame>;
}

/// The dialler's inbound frames: the control lane's replies and the push lane, each in send order.
/// There is no order across the two lanes.
#[async_trait]
pub trait InboundSource: Send {
    /// The next frame and its lane, or `None` once the link ended.
    async fn recv(&mut self) -> Option<(Lane, Frame)>;
}

/// The call lanes a peer opens, in the order the node accepts them.
#[async_trait]
pub trait CallSource: Send {
    /// The next call lane (its request is the first frame on its source), or `None` once the link
    /// ended.
    async fn accept(&mut self) -> Option<CallLane>;
}

/// One call lane, as either end holds it.
pub struct CallLane {
    /// Writes to the other end.
    pub sink: Box<dyn FrameSink>,
    /// Reads what the other end wrote.
    pub source: Box<dyn FrameSource>,
}

impl std::fmt::Debug for CallLane {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("CallLane")
    }
}

/// A dialled link: the link and its inbound frames.
pub struct DialedLink {
    /// The dialling end.
    pub link: Arc<dyn Link>,
    /// Control replies and pushes from the node.
    pub inbound: Box<dyn InboundSource>,
}

/// An accepted link, as a [`ProtocolHandler`](crate::ProtocolHandler) receives it.
pub struct AcceptedLink {
    /// The accepting end.
    pub link: Arc<dyn PeerLink>,
    /// The control frames the peer sends.
    pub control: Box<dyn FrameSource>,
    /// The call lanes the peer opens.
    pub calls: Box<dyn CallSource>,
}
