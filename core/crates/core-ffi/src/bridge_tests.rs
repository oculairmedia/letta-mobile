//! Tests: the exported surface, driven from Rust exactly as a platform drives it: two nodes created,
//! started and connected, node.info and the membership view read, the membership stream, platform
//! events, placement and catalog reads, errors, and stop.

use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use crate::{
    AddMemberRequest, AgentRuntimeSpec, CoreError, CoreNode, GenesisRequest, HostError,
    InProcessNetwork, MemberPreset, MemberStatus, MembershipListener, MembershipView, NodeClass,
    NodeEvent, NodeEventListener, NodeOptions, PlatformEvents, PlatformHooks, QuiesceOutcome,
    RuntimeHost, RuntimeInfo, Transport, node_id_of_secret_key, sign_add_member_op,
    sign_genesis_op,
};
use async_trait::async_trait;

struct IdleHost;

#[async_trait]
impl RuntimeHost for IdleHost {
    fn info(&self) -> RuntimeInfo {
        RuntimeInfo {
            letta_code_version: Some("0.34.70".into()),
            ..RuntimeInfo::default()
        }
    }
    async fn start(&self, _: AgentRuntimeSpec) -> Result<String, HostError> {
        Err(HostError::NotRunning)
    }
    async fn stop(&self, _: String) -> Result<(), HostError> {
        Ok(())
    }
    async fn quiesce(&self, _: String, _: u64) -> Result<QuiesceOutcome, HostError> {
        Ok(QuiesceOutcome::Idle)
    }
    async fn checkpoint(&self, _: String) -> Result<(), HostError> {
        Ok(())
    }
    async fn abort_turn(&self, _: String) -> Result<(), HostError> {
        Ok(())
    }
    fn endpoint(&self) -> Option<String> {
        None
    }
}

/// Hands out a fixed key and keeps the events object the node attaches.
struct Hooks {
    secret: Vec<u8>,
    attached: Mutex<Option<Arc<PlatformEvents>>>,
}

impl Hooks {
    fn new(seed: u8) -> Arc<Self> {
        Arc::new(Self {
            secret: vec![seed; 32],
            attached: Mutex::new(None),
        })
    }

    fn events(&self) -> Option<Arc<PlatformEvents>> {
        self.attached
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }
}

#[async_trait]
impl PlatformHooks for Hooks {
    async fn node_secret_key(&self) -> Result<Vec<u8>, HostError> {
        Ok(self.secret.clone())
    }
    fn attach(&self, events: Arc<PlatformEvents>) {
        *self.attached.lock().unwrap_or_else(PoisonError::into_inner) = Some(events);
    }
    fn detach(&self) {
        self.attached
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take();
    }
}

struct Views(Mutex<Sender<MembershipView>>);

impl MembershipListener for Views {
    fn on_view(&self, view: MembershipView) {
        let _ = self
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .send(view);
    }
}

struct Events(Mutex<Sender<NodeEvent>>);

impl NodeEventListener for Events {
    fn on_event(&self, event: NodeEvent) {
        let _ = self
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .send(event);
    }
}

/// Node 1 founds the constellation and admits node 2 as a desktop node.
struct Constellation {
    id: String,
    genesis: Vec<u8>,
    add: Vec<u8>,
}

fn constellation() -> Constellation {
    let genesis = sign_genesis_op(
        vec![1; 32],
        GenesisRequest {
            node_name: Some("node-a".into()),
            label: Some("home".into()),
            catalog_root: vec![0; 16],
            ts_ms: 1,
        },
    )
    .expect("genesis");
    let add = sign_add_member_op(
        vec![1; 32],
        AddMemberRequest {
            constellation: genesis.op_id.clone(),
            parents: vec![genesis.op_id.clone()],
            subject: node_id_of_secret_key(vec![2; 32]).expect("id"),
            preset: MemberPreset::DesktopNode,
            name: Some("node-b".into()),
            ts_ms: 2,
        },
    )
    .expect("add");
    Constellation {
        id: genesis.op_id,
        genesis: genesis.envelope,
        add: add.envelope,
    }
}

fn options(dir: &std::path::Path, constellation: &str, ops: Vec<Vec<u8>>) -> NodeOptions {
    NodeOptions {
        backend_dir: dir.join("backend").to_string_lossy().into_owned(),
        data_dir: dir.join("data").to_string_lossy().into_owned(),
        class: NodeClass::Desktop,
        app_version: "1.0.0".into(),
        worker_threads: 2,
        constellation: constellation.to_owned(),
        membership_ops: ops,
        tick_ms: 200,
        watch_debounce_ms: 50,
    }
}

fn active_members(view: &MembershipView) -> usize {
    view.members
        .iter()
        .filter(|member| member.status == MemberStatus::Active)
        .count()
}

/// Waits for a view with `count` active members.
fn await_members(views: &Receiver<MembershipView>, count: usize) -> MembershipView {
    loop {
        let view = views
            .recv_timeout(Duration::from_secs(20))
            .expect("a membership view in time");
        if active_members(&view) == count {
            return view;
        }
    }
}

/// Node A holds genesis and the add of B; node B starts with genesis alone, so it is not yet a
/// member in its own view. Connecting B to A replicates the add to B.
async fn two_nodes_converge(transport: impl Fn() -> Transport) {
    let constellation = constellation();
    let dir = tempfile::tempdir().expect("tempdir");
    let hooks_a = Hooks::new(1);
    let hooks_b = Hooks::new(2);
    let a = CoreNode::new(
        options(
            &dir.path().join("a"),
            &constellation.id,
            vec![constellation.genesis.clone(), constellation.add.clone()],
        ),
        transport(),
        Arc::new(IdleHost),
        Arc::clone(&hooks_a) as Arc<dyn PlatformHooks>,
    )
    .expect("node a");
    let b = CoreNode::new(
        options(
            &dir.path().join("b"),
            &constellation.id,
            vec![constellation.genesis.clone()],
        ),
        transport(),
        Arc::new(IdleHost),
        Arc::clone(&hooks_b) as Arc<dyn PlatformHooks>,
    )
    .expect("node b");
    assert!(matches!(a.info(), Err(CoreError::NotRunning)));

    let id_a = a.start().await.expect("start a");
    let id_b = b.start().await.expect("start b");
    assert_eq!(id_b, node_id_of_secret_key(vec![2; 32]).expect("id"));
    assert!(matches!(a.start().await, Err(CoreError::AlreadyRunning)));

    let info = a.info().expect("info");
    assert_eq!(info.schema, 1);
    assert_eq!(info.letta_code_version, "0.34.70");
    assert_eq!(info.store_format.major, 1);
    assert!(info.capabilities.contains(&"store".to_owned()), "{info:?}");
    assert!(
        a.runtime_parity()
            .expect("parity")
            .starts_with("letta-code@0.34.70+lcp:")
    );
    assert_eq!(active_members(&a.membership().expect("view")), 2);
    assert_eq!(active_members(&b.membership().expect("view")), 1);

    let (sender, views) = channel();
    let stream = b
        .subscribe_membership(Arc::new(Views(Mutex::new(sender))))
        .expect("subscribe");
    assert_eq!(active_members(&await_members(&views, 1)), 1);

    b.connect(a.address().expect("address"))
        .await
        .expect("connect");
    let view = tokio::task::spawn_blocking(move || await_members(&views, 2))
        .await
        .expect("views");
    assert_eq!(view.constellation, constellation.id);
    assert_eq!(view.label.as_deref(), Some("home"));
    let a_row = view
        .members
        .iter()
        .find(|member| member.node_id == id_a)
        .expect("a listed");
    assert_eq!(a_row.name.as_deref(), Some("node-a"));
    assert!(a_row.capabilities.contains(&"admin.full".to_owned()));
    stream.cancel();
    stream.cancel();

    events_and_reads(&a, &hooks_a).await;

    a.stop().await.expect("stop a");
    b.stop().await.expect("stop b");
    assert!(hooks_a.events().is_none(), "stop detaches the platform");
    assert!(matches!(a.membership(), Err(CoreError::NotRunning)));
    a.stop().await.expect("a second stop does nothing");

    // A stopped node starts again on the same store.
    a.start().await.expect("restart");
    assert_eq!(active_members(&a.membership().expect("view")), 2);
    a.stop().await.expect("stop");
}

/// Platform events reach the node's stream; reads for an agent and a document the node does not
/// hold answer none and a refusal.
async fn events_and_reads(a: &CoreNode, hooks_a: &Hooks) {
    let (sender, events) = channel();
    let _events_stream = a
        .subscribe_node_events(Arc::new(Events(Mutex::new(sender))))
        .expect("events");
    let platform = hooks_a.events().expect("attached");
    platform.foreground_changed(false);
    platform.network_changed();
    assert_eq!(
        events.recv_timeout(Duration::from_secs(5)).expect("event"),
        NodeEvent::ForegroundChanged { foreground: false }
    );
    assert_eq!(
        events.recv_timeout(Duration::from_secs(5)).expect("event"),
        NodeEvent::NetworkChanged
    );

    // Reads for an agent and a document the node does not hold.
    assert_eq!(
        a.placement("agent-unknown".into())
            .await
            .expect("placement"),
        None
    );
    assert!(a.catalog_documents().await.is_ok());
    assert!(matches!(
        a.catalog_view_json("not a url".into()).await,
        Err(CoreError::InvalidArgument { .. })
    ));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn two_in_process_nodes_connect_and_replicate_membership() {
    let network = InProcessNetwork::new();
    two_nodes_converge(|| Transport::InProcess {
        network: Arc::clone(&network),
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn two_iroh_nodes_on_loopback_connect_and_replicate_membership() {
    two_nodes_converge(|| Transport::Iroh {
        bind_addr: "127.0.0.1:0".into(),
        relays: Vec::new(),
    })
    .await;
}

#[test]
fn bad_arguments_are_invalid_and_a_bad_key_fails_start() {
    let constellation = constellation();
    let dir = tempfile::tempdir().expect("tempdir");
    let host = || Arc::new(IdleHost) as Arc<dyn RuntimeHost>;
    let hooks = || Hooks::new(1) as Arc<dyn PlatformHooks>;
    let network = InProcessNetwork::new();
    let in_process = || Transport::InProcess {
        network: Arc::clone(&network),
    };
    let bad = CoreNode::new(
        options(dir.path(), "zz", Vec::new()),
        in_process(),
        host(),
        hooks(),
    );
    assert!(matches!(bad, Err(CoreError::InvalidArgument { .. })));
    let bad_op = CoreNode::new(
        options(dir.path(), &constellation.id, vec![vec![1, 2, 3]]),
        in_process(),
        host(),
        hooks(),
    );
    assert!(matches!(bad_op, Err(CoreError::InvalidArgument { .. })));
    let bad_bind = CoreNode::new(
        options(dir.path(), &constellation.id, Vec::new()),
        Transport::Iroh {
            bind_addr: "nowhere".into(),
            relays: Vec::new(),
        },
        host(),
        hooks(),
    );
    assert!(matches!(bad_bind, Err(CoreError::InvalidArgument { .. })));
    assert!(matches!(
        node_id_of_secret_key(vec![1; 31]),
        Err(CoreError::InvalidArgument { .. })
    ));

    let short_key = Arc::new(Hooks {
        secret: vec![1; 16],
        attached: Mutex::new(None),
    });
    let node = CoreNode::new(
        options(dir.path(), &constellation.id, vec![constellation.genesis]),
        in_process(),
        host(),
        short_key,
    )
    .expect("node");
    let runtime = tokio::runtime::Builder::new_current_thread()
        .build()
        .expect("runtime");
    assert!(matches!(
        runtime.block_on(node.start()),
        Err(CoreError::Platform { .. })
    ));
    assert!(!node.is_running());
}
