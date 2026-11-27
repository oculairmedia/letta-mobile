//! The node-carrier v1 contract suite on the Iroh carrier: three loopback endpoints (node, shell,
//! outsider) with relays and address lookup disabled, so nothing leaves the host.

use std::sync::Arc;

use async_trait::async_trait;
use core_carrier::contract::{ContractHarness, HarnessEndpoints};
use core_carrier::{NodeAddr, Protocols};
use core_carrier_iroh::{IrohConfig, IrohNode, node_id_of_secret};

#[derive(Clone, Copy, Debug, Default)]
struct IrohHarness;

async fn loopback(protocols: Protocols) -> IrohNode {
    IrohNode::bind(
        IrohConfig::loopback(IrohConfig::random_secret_key()),
        protocols,
    )
    .await
    .expect("bind a loopback endpoint")
}

#[async_trait]
impl ContractHarness for IrohHarness {
    async fn start(&self, protocols: Protocols) -> HarnessEndpoints {
        let node = loopback(protocols).await;
        let shell = loopback(Protocols::none()).await;
        let outsider = loopback(Protocols::none()).await;
        let node_addr = node.addr();
        assert!(
            !node_addr.direct.is_empty(),
            "the loopback node has a direct address"
        );
        let nobody = node_id_of_secret(&IrohConfig::random_secret_key());
        HarnessEndpoints {
            shell: Arc::new(shell.carrier()),
            outsider: Some(Arc::new(outsider.carrier())),
            node: node_addr,
            unreachable: NodeAddr::new(nobody),
            // A QUIC path over loopback cannot be dropped from a test without an endpoint close,
            // which sends a close frame (an application close, not a loss); a silent drop would
            // only surface after the idle timeout. The in-process carrier covers the loss rule.
            sever: None,
            stop: Box::new(move || {
                Box::pin(async move {
                    node.shutdown().await;
                    shell.shutdown().await;
                    outsider.shutdown().await;
                })
            }),
        }
    }
}

core_carrier::node_carrier_contract_tests!(IrohHarness);
