//! Interop with the Kotlin node: dials a running JVM Iroh host, authenticates with a token and
//! calls `health.check` on a call lane.
//!
//! Skipped unless `BAKEN_KOTLIN_NODE` names the node as `<64-hex NodeId>@<ip:port>` and
//! `BAKEN_KOTLIN_TOKEN` holds its bootstrap token. How to start the Kotlin host is in
//! `docs/reference/contracts/node-carrier/rust-carrier-notes-v1.md`.

use core_carrier::{Lane, NodeAddr, Protocols};
use core_carrier_iroh::{IrohConfig, IrohNode};
use core_proto::NodeId;
use core_proto::alpn::Alpn;
use core_proto::capability::{EVENT_SEQ_ORDERED_V1, FRAME_PART};
use serde_json::{Value, json};

const NODE_ENV: &str = "BAKEN_KOTLIN_NODE";
const TOKEN_ENV: &str = "BAKEN_KOTLIN_TOKEN";

fn target() -> Option<(NodeAddr, String)> {
    let node = std::env::var(NODE_ENV).ok()?;
    let token = std::env::var(TOKEN_ENV).ok()?;
    let (id, addr) = node
        .split_once('@')
        .expect("BAKEN_KOTLIN_NODE is <hex>@<ip:port>");
    let id: NodeId = id.parse().expect("a 64-hex NodeId");
    let addr = addr.parse().expect("an ip:port");
    Some((NodeAddr::new(id).with_direct(addr), token))
}

fn parse(frame: &core_carrier::Frame) -> Value {
    serde_json::from_str(frame.as_str()).expect("the Kotlin node sends JSON")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_rust_shell_authenticates_and_calls_health_check_on_the_kotlin_node() {
    let Some((target, token)) = target() else {
        eprintln!("skipped: set {NODE_ENV} and {TOKEN_ENV} to run against a Kotlin node");
        return;
    };
    let client = IrohNode::bind(
        IrohConfig::loopback(IrohConfig::random_secret_key()),
        Protocols::none(),
    )
    .await
    .expect("bind");
    let mut dialed =
        core_carrier::Carrier::dial(&client.carrier(), &target, Alpn::AppServer.as_bytes())
            .await
            .expect("dial the Kotlin node");

    let auth = json!({
        "type": "auth",
        "request_id": "rust-1",
        "token": token,
        "peer_name": "rust-carrier-interop",
        "capabilities": [FRAME_PART],
    });
    dialed
        .link
        .send_control(auth.to_string().into())
        .await
        .expect("send auth");
    let reply = loop {
        let (lane, frame) = dialed.inbound.recv().await.expect("an auth_response");
        let value = parse(&frame);
        if lane == Lane::Control && value["request_id"] == "rust-1" {
            break value;
        }
    };
    assert_eq!(reply["type"], "auth_response", "{reply}");
    assert_eq!(reply["success"], true, "{reply}");
    let capabilities = reply["capabilities"].as_array().expect("capabilities");
    for required in [FRAME_PART, EVENT_SEQ_ORDERED_V1] {
        assert!(
            capabilities.iter().any(|c| c == required),
            "the node advertises {required}: {reply}"
        );
    }

    let request = json!({"type": "admin_rpc", "request_id": "rust-2", "method": "health.check"});
    let mut call = dialed
        .link
        .open_call(request.to_string().into())
        .await
        .expect("open a call lane");
    call.sink.finish().await.expect("finish the request half");
    let response = parse(&call.source.recv().await.expect("a response"));
    assert_eq!(response["type"], "admin_rpc_response", "{response}");
    assert_eq!(response["request_id"], "rust-2", "{response}");
    assert_eq!(response["success"], true, "{response}");

    dialed.link.close(core_carrier::AppClose::closed());
    client.shutdown().await;
}
