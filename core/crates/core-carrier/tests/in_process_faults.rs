//! The in-process carrier's fault hooks: delay, loss, partition, sever and node stop.

use std::collections::BTreeSet;
use std::time::Duration;

use async_trait::async_trait;
use core_carrier::in_process::InProcessNetwork;
use core_carrier::{
    AcceptedLink, Carrier, CarrierError, Frame, Lane, LinkClosure, MembershipAccess, NodeAddr,
    ProtocolHandler, Protocols, VerifiedPeer,
};
use core_proto::NodeId;
use core_proto::alpn::Alpn;
use core_proto::closure::Closure;
use proptest::prelude::*;
use tokio::time::Instant;

const NODE: NodeId = NodeId::from_bytes([1; 32]);
const SHELL: NodeId = NodeId::from_bytes([2; 32]);

fn alpn() -> &'static [u8] {
    Alpn::AppServer.as_bytes()
}

/// Pushes back every control frame it receives.
struct Echo;

#[async_trait]
impl ProtocolHandler for Echo {
    async fn serve(&self, mut accepted: AcceptedLink) {
        while let Some(frame) = accepted.control.recv().await {
            if accepted.link.push(frame).await.is_err() {
                break;
            }
        }
    }
}

fn echo_network(seed: u64) -> InProcessNetwork {
    let network = InProcessNetwork::with_seed(seed);
    network.serve(NODE, Protocols::builder().accept(alpn(), Echo).build());
    network
}

fn shell() -> VerifiedPeer {
    VerifiedPeer::Node(SHELL)
}

fn node() -> VerifiedPeer {
    VerifiedPeer::Node(NODE)
}

#[tokio::test(start_paused = true)]
async fn a_delay_holds_every_frame_and_keeps_lane_order() {
    let network = echo_network(1);
    network.set_delay(shell(), node(), Duration::from_millis(500));
    let mut dialed = network
        .carrier(shell())
        .dial(&NodeAddr::new(NODE), alpn())
        .await
        .expect("dial");
    let started = Instant::now();
    for index in 0..5 {
        dialed
            .link
            .send_control(Frame::new(format!("{{\"n\":{index}}}")))
            .await
            .expect("send");
    }
    for index in 0..5 {
        let (lane, frame) = dialed.inbound.recv().await.expect("echo");
        assert_eq!(lane, Lane::Push);
        assert_eq!(frame.as_str(), format!("{{\"n\":{index}}}"));
    }
    let elapsed = started.elapsed();
    assert!(
        elapsed >= Duration::from_secs(1) && elapsed < Duration::from_millis(1100),
        "a round trip through two 500 ms hops took {elapsed:?}"
    );
}

#[tokio::test]
async fn certain_loss_loses_the_link_at_both_ends_and_delivers_nothing() {
    let network = echo_network(7);
    network.set_loss(shell(), node(), 1_000_000);
    let mut dialed = network
        .carrier(shell())
        .dial(&NodeAddr::new(NODE), alpn())
        .await
        .expect("dial");
    dialed
        .link
        .send_control(Frame::new("{}"))
        .await
        .expect("the sender does not learn of the loss at once");
    assert_eq!(dialed.link.closed().await, LinkClosure::Lost);
    assert!(dialed.inbound.recv().await.is_none());
    assert!(matches!(
        dialed.link.send_control(Frame::new("{}")).await,
        Err(CarrierError::Closed(LinkClosure::Lost))
    ));
}

#[tokio::test]
async fn a_partition_refuses_dials_loses_open_links_and_heals() {
    let network = echo_network(3);
    let carrier = network.carrier(shell());
    let open = carrier
        .dial(&NodeAddr::new(NODE), alpn())
        .await
        .expect("dial");
    assert_eq!(network.open_links(), 1);
    network.partition(shell(), node());
    assert_eq!(open.link.closed().await, LinkClosure::Lost);
    assert!(matches!(
        carrier.dial(&NodeAddr::new(NODE), alpn()).await,
        Err(CarrierError::Unreachable(_))
    ));
    network.heal(shell(), node());
    let mut healed = carrier
        .dial(&NodeAddr::new(NODE), alpn())
        .await
        .expect("dial after heal");
    healed
        .link
        .send_control(Frame::new("{\"ok\":true}"))
        .await
        .expect("send");
    let (_, echoed) = healed.inbound.recv().await.expect("echo");
    assert_eq!(echoed.as_str(), "{\"ok\":true}");
}

#[tokio::test]
async fn sever_and_stop_lose_links_and_stop_makes_the_node_unreachable() {
    let network = echo_network(4);
    let carrier = network.carrier(shell());
    let first = carrier
        .dial(&NodeAddr::new(NODE), alpn())
        .await
        .expect("dial");
    assert_eq!(network.sever(shell(), node()), 1);
    assert_eq!(first.link.closed().await, LinkClosure::Lost);
    let second = carrier
        .dial(&NodeAddr::new(NODE), alpn())
        .await
        .expect("dial");
    network.stop(&NODE);
    assert_eq!(second.link.closed().await, LinkClosure::Lost);
    assert!(matches!(
        carrier.dial(&NodeAddr::new(NODE), alpn()).await,
        Err(CarrierError::Unreachable(_))
    ));
}

#[tokio::test]
async fn an_unserved_alpn_fails_the_dial_and_the_local_shell_is_admitted_as_itself() {
    struct Report(tokio::sync::mpsc::UnboundedSender<VerifiedPeer>);
    #[async_trait]
    impl ProtocolHandler for Report {
        async fn serve(&self, accepted: AcceptedLink) {
            let _ = self.0.send(accepted.link.remote());
        }
    }

    let network = InProcessNetwork::new();
    let (seen_tx, mut seen) = tokio::sync::mpsc::unbounded_channel();
    network.serve(
        NODE,
        Protocols::builder()
            .access(MembershipAccess::new(BTreeSet::new()))
            .accept(alpn(), Report(seen_tx))
            .build(),
    );
    let local = network.carrier(VerifiedPeer::LocalShell);
    assert!(matches!(
        local
            .dial(&NodeAddr::new(NODE), Alpn::AgentToAgent.as_bytes())
            .await,
        Err(CarrierError::NoProtocol(_))
    ));
    let dialed = local
        .dial(&NodeAddr::new(NODE), alpn())
        .await
        .expect("the local shell is admitted");
    assert_eq!(seen.recv().await, Some(VerifiedPeer::LocalShell));
    // The handler returned, so the carrier closed the link as an orderly close.
    assert_eq!(dialed.link.closed().await, Closure::CLOSED.into());
    assert!(matches!(
        network.carrier(shell()).dial(&NodeAddr::new(NODE), alpn()).await,
        Err(CarrierError::Closed(closure)) if closure == Closure::PEER_NOT_ALLOWED.into()
    ));
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(48))]

    /// Whatever the loss rate and seed, a lane never shows a gap: the frames that arrive are a
    /// prefix of the frames sent, and the link ends lost exactly when fewer arrived.
    #[test]
    fn loss_never_shows_as_a_missing_frame(seed in 1_u64.., per_million in 0_u32..400_000, count in 1_usize..40) {
        let runtime = tokio::runtime::Builder::new_current_thread().enable_time().build().expect("runtime");
        let (received, closure) = runtime.block_on(async {
            let network = echo_network(seed);
            network.set_loss(shell(), node(), per_million);
            let mut dialed = network.carrier(shell()).dial(&NodeAddr::new(NODE), alpn()).await.expect("dial");
            for index in 0..count {
                if dialed.link.send_control(Frame::new(index.to_string())).await.is_err() {
                    break;
                }
            }
            let mut received = Vec::new();
            while received.len() < count {
                match dialed.inbound.recv().await {
                    Some((_, frame)) => received.push(frame.into_string()),
                    None => break,
                }
            }
            (received, dialed.link.closure())
        });
        let expected: Vec<String> = (0..received.len()).map(|index| index.to_string()).collect();
        prop_assert_eq!(&received, &expected);
        if received.len() < count {
            prop_assert_eq!(closure, Some(LinkClosure::Lost));
        }
    }
}
