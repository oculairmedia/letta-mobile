//! The node session's control lane: `auth` for a member, then `node.info`; a stranger is closed by
//! the access hook before any handler runs.

use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use core_carrier::in_process::InProcessNetwork;
use core_carrier::{Carrier, Frame, NodeAddr, VerifiedPeer};
use core_membership::{CatalogRoot, Op, OpHeader, OpPayload, Preset, SignedOp};
use core_node::{
    AgentRuntime, AppServerEndpoint, Node, NodeConfig, Quiesce, RuntimeError, RuntimeHost,
    RuntimeInfo,
};
use core_proto::AgentId;
use core_proto::alpn::Alpn;
use core_proto::crypto::NodeKey;
use core_store::MemoryStore;
use serde_json::Value;

struct IdleHost;

#[async_trait]
impl RuntimeHost for IdleHost {
    fn info(&self) -> RuntimeInfo {
        RuntimeInfo::default()
    }
    async fn start(&self, _: &AgentRuntime) -> Result<AppServerEndpoint, RuntimeError> {
        Err(RuntimeError::NotRunning)
    }
    async fn stop(&self, _: &AgentId) -> Result<(), RuntimeError> {
        Ok(())
    }
    async fn quiesce(&self, _: &AgentId, _: Duration) -> Result<Quiesce, RuntimeError> {
        Ok(Quiesce::Idle)
    }
    async fn checkpoint(&self, _: &AgentId) -> Result<(), RuntimeError> {
        Ok(())
    }
    async fn abort_turn(&self, _: &AgentId) -> Result<(), RuntimeError> {
        Ok(())
    }
    fn endpoint(&self) -> Option<AppServerEndpoint> {
        None
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_member_reads_node_info_and_a_stranger_is_refused() {
    let key = |n: u8| NodeKey::from_seed(&[n; 32]);
    let genesis = SignedOp::sign(
        &Op {
            header: OpHeader {
                constellation: None,
                issuer: key(1).node_id(),
                parents: BTreeSet::new(),
                ts_ms: 1,
            },
            payload: OpPayload::Genesis {
                caps: Preset::FirstDevice.capabilities(),
                name: None,
                label: None,
                catalog_root: CatalogRoot([0; 16]),
            },
            extensions: BTreeMap::new(),
        },
        &key(1),
    )
    .expect("genesis");
    let temp = tempfile::tempdir().expect("tempdir");
    let network = InProcessNetwork::new();
    let id = key(1).node_id();
    let node = Node::start(
        NodeConfig::new(
            Arc::new(key(1)),
            temp.path().join("backend"),
            temp.path().join("data"),
            genesis.id(),
            vec![genesis],
        ),
        Arc::new(MemoryStore::new()),
        Arc::new(network.carrier(VerifiedPeer::Node(id))),
        Arc::new(IdleHost),
    )
    .await
    .expect("start");
    network.serve(id, node.protocols());

    // The node's own shell (in process) is a paired peer.
    let shell = network.carrier(VerifiedPeer::LocalShell);
    let mut dialed = shell
        .dial(&NodeAddr::new(id), Alpn::AppServer.as_bytes())
        .await
        .expect("dial");
    let call = |text: &str| {
        let link = Arc::clone(&dialed.link);
        let text = text.to_owned();
        async move { link.send_control(Frame::new(text)).await.expect("send") }
    };
    call(r#"{"type":"admin_rpc","request_id":"r0","method":"node.info"}"#).await;
    let (_, frame) = dialed.inbound.recv().await.expect("reply");
    let reply: Value = serde_json::from_str(frame.as_str()).expect("json");
    assert_eq!(reply["message"], "unauthorized", "{reply}");
    call(r#"{"type":"auth","request_id":"r1"}"#).await;
    let (_, frame) = dialed.inbound.recv().await.expect("reply");
    let reply: Value = serde_json::from_str(frame.as_str()).expect("json");
    assert_eq!(reply["success"], true, "{reply}");
    call(r#"{"type":"admin_rpc","request_id":"r2","method":"node.info"}"#).await;
    let (_, frame) = dialed.inbound.recv().await.expect("reply");
    let reply: Value = serde_json::from_str(frame.as_str()).expect("json");
    assert_eq!(reply["type"], "admin_rpc_response");
    assert_eq!(reply["success"], true);
    assert_eq!(reply["result"]["schema"], 1);
    assert_eq!(reply["result"]["letta_code_version"], "unknown");
    assert_eq!(reply["result"]["runtime_patches"]["source"], "unknown");

    // A NodeId outside the constellation never reaches a handler.
    let stranger = network.carrier(VerifiedPeer::Node(key(9).node_id()));
    let refused = stranger
        .dial(&NodeAddr::new(id), Alpn::AppServer.as_bytes())
        .await;
    let closed = match refused {
        Err(_) => true,
        Ok(link) => tokio::time::timeout(Duration::from_secs(5), link.link.closed())
            .await
            .is_ok_and(|closure| closure.app().is_some_and(|c| c.code == 4403)),
    };
    assert!(closed, "the stranger is refused 4403");
    node.shutdown().await;
}
