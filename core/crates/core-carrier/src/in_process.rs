//! The in-process carrier: links between endpoints in one process, for tests, the simulator and
//! the shell that shares a process with its node.
//!
//! An [`InProcessNetwork`] holds the nodes that serve [`Protocols`] and the fault model between
//! endpoints. Each lane direction is a channel; frames cross as owned strings (nothing is
//! re-encoded and nothing the sender keeps is shared). Faults apply per pair of endpoints:
//!
//! - **delay**: every frame between the pair arrives this long after it was sent, in order (the
//!   sender does not wait);
//! - **loss**: each frame sent between the pair loses the transport with this probability (per
//!   million). The node protocol runs over reliable streams, so what a layer above a carrier sees
//!   of packet loss is a link lost, never a frame missing from a lane; the link ends as
//!   [`LinkClosure::Lost`] at both ends and the frame is not delivered. The draw is from a seeded
//!   generator, so a run is reproducible;
//! - **partition**: dials between the pair fail [`CarrierError::Unreachable`] and links already
//!   open between them are lost;
//! - **sever**: drop the transport under the open links between a pair, once.
//!
//! Time is tokio's clock, so a test with a paused clock drives delays deterministically.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};
use std::time::Duration;

use async_trait::async_trait;
use core_proto::NodeId;
use tokio::sync::{mpsc, watch};
use tokio::time::Instant;

use crate::{
    AcceptedLink, AccessDecision, AppClose, CallLane, CallSource, Carrier, CarrierError,
    DialedLink, Frame, FrameSink, FrameSource, InboundSource, Lane, Link, LinkClosure, LinkControl,
    NodeAddr, PeerLink, Protocols, VerifiedPeer,
};

/// An unordered pair of endpoints, smaller identity first.
type Pair = (VerifiedPeer, VerifiedPeer);

fn pair(a: VerifiedPeer, b: VerifiedPeer) -> Pair {
    if a <= b { (a, b) } else { (b, a) }
}

/// A set of in-process endpoints and the faults between them. Cloning shares the network.
#[derive(Clone)]
pub struct InProcessNetwork {
    net: Arc<Net>,
}

struct Net {
    state: Mutex<NetState>,
}

struct NetState {
    nodes: HashMap<NodeId, Protocols>,
    partitions: HashSet<Pair>,
    delays: HashMap<Pair, Duration>,
    loss: HashMap<Pair, u32>,
    rng: u64,
    links: Vec<(Pair, Weak<Shared>)>,
}

impl Net {
    fn state(&self) -> MutexGuard<'_, NetState> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

impl NetState {
    /// xorshift64*: small, seeded and good enough to draw loss.
    fn next_random(&mut self) -> u64 {
        let mut x = self.rng;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.rng = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn sever_where(&mut self, matches: impl Fn(&Pair) -> bool) -> usize {
        let mut severed = 0;
        self.links.retain(|(pair, link)| {
            let Some(link) = link.upgrade() else {
                return false;
            };
            if link.closure().is_some() {
                return false;
            }
            if matches(pair) {
                if link.close(LinkClosure::Lost) {
                    severed += 1;
                }
                return false;
            }
            true
        });
        severed
    }
}

impl Default for InProcessNetwork {
    fn default() -> Self {
        Self::new()
    }
}

impl InProcessNetwork {
    /// An empty network with a fixed seed for the loss draws.
    #[must_use]
    pub fn new() -> Self {
        Self::with_seed(0x9E37_79B9_7F4A_7C15)
    }

    /// An empty network whose loss draws start from `seed`.
    #[must_use]
    pub fn with_seed(seed: u64) -> Self {
        Self {
            net: Arc::new(Net {
                state: Mutex::new(NetState {
                    nodes: HashMap::new(),
                    partitions: HashSet::new(),
                    delays: HashMap::new(),
                    loss: HashMap::new(),
                    rng: seed.max(1),
                    links: Vec::new(),
                }),
            }),
        }
    }

    /// Makes `node` reachable, serving `protocols`. Replaces what it served before; links already
    /// open keep their handlers.
    pub fn serve(&self, node: NodeId, protocols: Protocols) {
        self.net.state().nodes.insert(node, protocols);
    }

    /// Makes `node` unreachable and loses every open link to or from it.
    pub fn stop(&self, node: &NodeId) {
        let mut state = self.net.state();
        state.nodes.remove(node);
        let peer = VerifiedPeer::Node(*node);
        state.sever_where(|(a, b)| *a == peer || *b == peer);
    }

    /// A carrier whose links prove `local` to the nodes it dials.
    #[must_use]
    pub fn carrier(&self, local: VerifiedPeer) -> InProcessCarrier {
        InProcessCarrier {
            net: Arc::clone(&self.net),
            local,
        }
    }

    /// Partitions `a` from `b`: dials between them fail and open links between them are lost.
    pub fn partition(&self, a: VerifiedPeer, b: VerifiedPeer) {
        let mut state = self.net.state();
        let key = pair(a, b);
        state.partitions.insert(key);
        state.sever_where(|link| *link == key);
    }

    /// Heals the partition between `a` and `b`. Lost links stay lost; new dials succeed.
    pub fn heal(&self, a: VerifiedPeer, b: VerifiedPeer) {
        self.net.state().partitions.remove(&pair(a, b));
    }

    /// Heals every partition.
    pub fn heal_all(&self) {
        self.net.state().partitions.clear();
    }

    /// Delays every frame between `a` and `b` by `delay` from now on (zero removes the delay).
    pub fn set_delay(&self, a: VerifiedPeer, b: VerifiedPeer, delay: Duration) {
        let mut state = self.net.state();
        if delay.is_zero() {
            state.delays.remove(&pair(a, b));
        } else {
            state.delays.insert(pair(a, b), delay);
        }
    }

    /// Loses the link with probability `per_million` / 1 000 000 on each frame sent between `a` and
    /// `b` (zero removes the fault).
    pub fn set_loss(&self, a: VerifiedPeer, b: VerifiedPeer, per_million: u32) {
        let mut state = self.net.state();
        if per_million == 0 {
            state.loss.remove(&pair(a, b));
        } else {
            state.loss.insert(pair(a, b), per_million.min(1_000_000));
        }
    }

    /// Drops the transport under every open link between `a` and `b`: both ends see
    /// [`LinkClosure::Lost`]. Returns how many links were lost.
    #[allow(
        clippy::must_use_candidate,
        reason = "severing is the point; the count is optional"
    )]
    pub fn sever(&self, a: VerifiedPeer, b: VerifiedPeer) -> usize {
        let key = pair(a, b);
        self.net.state().sever_where(|link| *link == key)
    }

    /// Drops the transport under every open link. Returns how many links were lost.
    #[allow(
        clippy::must_use_candidate,
        reason = "severing is the point; the count is optional"
    )]
    pub fn sever_all(&self) -> usize {
        self.net.state().sever_where(|_| true)
    }

    /// How many links are open.
    #[must_use]
    pub fn open_links(&self) -> usize {
        let mut state = self.net.state();
        state
            .links
            .retain(|(_, link)| link.upgrade().is_some_and(|link| link.closure().is_none()));
        state.links.len()
    }
}

/// Dials nodes on an [`InProcessNetwork`] as one local identity.
#[derive(Clone)]
pub struct InProcessCarrier {
    net: Arc<Net>,
    local: VerifiedPeer,
}

#[async_trait]
impl Carrier for InProcessCarrier {
    fn local(&self) -> VerifiedPeer {
        self.local
    }

    async fn dial(&self, target: &NodeAddr, alpn: &[u8]) -> Result<DialedLink, CarrierError> {
        let remote = VerifiedPeer::Node(target.id);
        let key = pair(self.local, remote);
        let protocols = {
            let state = self.net.state();
            let Some(protocols) = state.nodes.get(&target.id) else {
                return Err(CarrierError::Unreachable(format!(
                    "no in-process node {}",
                    target.id
                )));
            };
            if state.partitions.contains(&key) {
                return Err(CarrierError::Unreachable(format!(
                    "partitioned from {}",
                    target.id
                )));
            }
            protocols.clone()
        };
        if !protocols.serves(alpn) {
            return Err(CarrierError::NoProtocol(
                String::from_utf8_lossy(alpn).into_owned(),
            ));
        }
        if let AccessDecision::Reject(close) = protocols.check(&self.local, alpn).await {
            return Err(CarrierError::Closed(close.into()));
        }

        let (closure, _) = watch::channel(None);
        let shared = Arc::new(Shared {
            pair: key,
            net: Arc::downgrade(&self.net),
            closure,
        });
        self.net.state().links.push((key, Arc::downgrade(&shared)));

        let (to_node_tx, to_node_rx) = mpsc::unbounded_channel();
        let (to_shell_tx, to_shell_rx) = mpsc::unbounded_channel();
        let (calls_tx, calls_rx) = mpsc::unbounded_channel();

        let shell = Arc::new(ShellEnd {
            shared: Arc::clone(&shared),
            remote,
            alpn: alpn.to_vec(),
            control: Tx::new(Lane::Control, to_node_tx, &shared),
            calls: calls_tx,
        });
        let node = Arc::new(NodeEnd {
            shared: Arc::clone(&shared),
            remote: self.local,
            alpn: alpn.to_vec(),
            control: Tx::new(Lane::Control, to_shell_tx.clone(), &shared),
            push: Tx::new(Lane::Push, to_shell_tx, &shared),
        });
        let accepted = AcceptedLink {
            link: node,
            control: Box::new(Rx::new(to_node_rx, &shared)),
            calls: Box::new(Calls {
                calls: calls_rx,
                shared: Arc::clone(&shared),
            }),
        };
        tokio::spawn(async move { protocols.serve(accepted).await });
        Ok(DialedLink {
            link: shell,
            inbound: Box::new(Rx::new(to_shell_rx, &shared)),
        })
    }
}

/// What both ends of one link share: its closure and where to look up faults.
struct Shared {
    pair: Pair,
    net: Weak<Net>,
    closure: watch::Sender<Option<LinkClosure>>,
}

/// How one frame travels.
struct Plan {
    lost: bool,
    deliver_at: Option<Instant>,
}

impl Shared {
    /// Sets the closure once; returns whether this call set it.
    fn close(&self, closure: LinkClosure) -> bool {
        self.closure.send_if_modified(|current| {
            if current.is_some() {
                false
            } else {
                *current = Some(closure);
                true
            }
        })
    }

    fn closure(&self) -> Option<LinkClosure> {
        self.closure.borrow().clone()
    }

    async fn closed(&self) -> LinkClosure {
        let mut receiver = self.closure.subscribe();
        match receiver.wait_for(Option::is_some).await {
            Ok(closure) => closure.clone().unwrap_or(LinkClosure::Lost),
            Err(_) => LinkClosure::Lost,
        }
    }

    fn ensure_open(&self) -> Result<(), CarrierError> {
        self.closure()
            .map_or(Ok(()), |closure| Err(CarrierError::Closed(closure)))
    }

    fn plan(&self) -> Plan {
        let Some(net) = self.net.upgrade() else {
            return Plan {
                lost: false,
                deliver_at: None,
            };
        };
        let mut state = net.state();
        let lost = match state.loss.get(&self.pair).copied() {
            Some(per_million) => state.next_random() % 1_000_000 < u64::from(per_million),
            None => false,
        };
        let deliver_at = state
            .delays
            .get(&self.pair)
            .map(|delay| Instant::now() + *delay);
        Plan { lost, deliver_at }
    }
}

struct Envelope {
    lane: Lane,
    deliver_at: Option<Instant>,
    frame: Frame,
}

/// The sending half of one lane direction.
struct Tx {
    lane: Lane,
    sender: Mutex<Option<mpsc::UnboundedSender<Envelope>>>,
    shared: Arc<Shared>,
}

impl Tx {
    fn new(lane: Lane, sender: mpsc::UnboundedSender<Envelope>, shared: &Arc<Shared>) -> Self {
        Self {
            lane,
            sender: Mutex::new(Some(sender)),
            shared: Arc::clone(shared),
        }
    }

    fn send(&self, frame: Frame) -> Result<(), CarrierError> {
        self.shared.ensure_open()?;
        let guard = self.sender.lock().unwrap_or_else(PoisonError::into_inner);
        let Some(sender) = guard.as_ref() else {
            return Err(CarrierError::Finished);
        };
        let plan = self.shared.plan();
        if plan.lost {
            self.shared.close(LinkClosure::Lost);
            return Ok(());
        }
        // A receiver that is gone has stopped reading; the frame is dropped, as on a stream whose
        // reader stopped.
        let _ = sender.send(Envelope {
            lane: self.lane,
            deliver_at: plan.deliver_at,
            frame,
        });
        Ok(())
    }

    fn finish(&self) -> Result<(), CarrierError> {
        self.shared.ensure_open()?;
        self.sender
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take();
        Ok(())
    }
}

#[async_trait]
impl FrameSink for Tx {
    async fn send(&self, frame: Frame) -> Result<(), CarrierError> {
        Tx::send(self, frame)
    }

    async fn finish(&self) -> Result<(), CarrierError> {
        Tx::finish(self)
    }
}

/// The receiving half of one lane direction.
struct Rx {
    receiver: mpsc::UnboundedReceiver<Envelope>,
    closure: watch::Receiver<Option<LinkClosure>>,
    // Keeps the closure's sender alive while this end reads.
    _shared: Arc<Shared>,
}

impl Rx {
    fn new(receiver: mpsc::UnboundedReceiver<Envelope>, shared: &Arc<Shared>) -> Self {
        Self {
            receiver,
            closure: shared.closure.subscribe(),
            _shared: Arc::clone(shared),
        }
    }

    async fn next(&mut self) -> Option<Envelope> {
        loop {
            let closure = self.closure.borrow_and_update().clone();
            match closure {
                // A lost transport drops what was in flight.
                Some(LinkClosure::Lost) => return None,
                // An orderly close delivers what was sent before it.
                Some(LinkClosure::App(_)) => return self.receiver.try_recv().ok(),
                None => {}
            }
            tokio::select! {
                biased;
                envelope = self.receiver.recv() => {
                    let envelope = envelope?;
                    if let Some(at) = envelope.deliver_at {
                        tokio::time::sleep_until(at).await;
                        if matches!(*self.closure.borrow(), Some(LinkClosure::Lost)) {
                            return None;
                        }
                    }
                    return Some(envelope);
                }
                changed = self.closure.changed() => {
                    if changed.is_err() {
                        return None;
                    }
                }
            }
        }
    }
}

#[async_trait]
impl FrameSource for Rx {
    async fn recv(&mut self) -> Option<Frame> {
        self.next().await.map(|envelope| envelope.frame)
    }
}

#[async_trait]
impl InboundSource for Rx {
    async fn recv(&mut self) -> Option<(Lane, Frame)> {
        self.next()
            .await
            .map(|envelope| (envelope.lane, envelope.frame))
    }
}

/// The call lanes a dialler opened, as the node accepts them.
struct Calls {
    calls: mpsc::UnboundedReceiver<CallLane>,
    shared: Arc<Shared>,
}

#[async_trait]
impl CallSource for Calls {
    async fn accept(&mut self) -> Option<CallLane> {
        let mut closure = self.shared.closure.subscribe();
        if closure.borrow_and_update().is_some() {
            return None;
        }
        tokio::select! {
            biased;
            lane = self.calls.recv() => lane,
            _ = closure.wait_for(Option::is_some) => None,
        }
    }
}

/// The dialling end of an in-process link.
struct ShellEnd {
    shared: Arc<Shared>,
    remote: VerifiedPeer,
    alpn: Vec<u8>,
    control: Tx,
    calls: mpsc::UnboundedSender<CallLane>,
}

#[async_trait]
impl LinkControl for ShellEnd {
    fn remote(&self) -> VerifiedPeer {
        self.remote
    }

    fn alpn(&self) -> &[u8] {
        &self.alpn
    }

    fn close(&self, close: AppClose) {
        self.shared.close(close.into());
    }

    fn closure(&self) -> Option<LinkClosure> {
        self.shared.closure()
    }

    async fn closed(&self) -> LinkClosure {
        self.shared.closed().await
    }
}

#[async_trait]
impl Link for ShellEnd {
    async fn send_control(&self, frame: Frame) -> Result<(), CarrierError> {
        self.control.send(frame)
    }

    async fn open_call(&self, request: Frame) -> Result<CallLane, CarrierError> {
        self.shared.ensure_open()?;
        let (to_node_tx, to_node_rx) = mpsc::unbounded_channel();
        let (to_shell_tx, to_shell_rx) = mpsc::unbounded_channel();
        let ours = CallLane {
            sink: Box::new(Tx::new(Lane::Control, to_node_tx, &self.shared)),
            source: Box::new(Rx::new(to_shell_rx, &self.shared)),
        };
        let theirs = CallLane {
            sink: Box::new(Tx::new(Lane::Control, to_shell_tx, &self.shared)),
            source: Box::new(Rx::new(to_node_rx, &self.shared)),
        };
        ours.sink.send(request).await?;
        self.calls.send(theirs).map_err(|_| {
            CarrierError::Closed(self.shared.closure().unwrap_or(LinkClosure::Lost))
        })?;
        Ok(ours)
    }
}

/// The accepting end of an in-process link.
struct NodeEnd {
    shared: Arc<Shared>,
    remote: VerifiedPeer,
    alpn: Vec<u8>,
    control: Tx,
    push: Tx,
}

#[async_trait]
impl LinkControl for NodeEnd {
    fn remote(&self) -> VerifiedPeer {
        self.remote
    }

    fn alpn(&self) -> &[u8] {
        &self.alpn
    }

    fn close(&self, close: AppClose) {
        self.shared.close(close.into());
    }

    fn closure(&self) -> Option<LinkClosure> {
        self.shared.closure()
    }

    async fn closed(&self) -> LinkClosure {
        self.shared.closed().await
    }
}

#[async_trait]
impl PeerLink for NodeEnd {
    async fn send_control(&self, frame: Frame) -> Result<(), CarrierError> {
        self.control.send(frame)
    }

    async fn push(&self, frame: Frame) -> Result<(), CarrierError> {
        self.push.send(frame)
    }

    fn on_peer_capabilities(&self, _capabilities: &[String]) {
        // Frames cross whole in process; there is no chunking to enable.
    }
}
