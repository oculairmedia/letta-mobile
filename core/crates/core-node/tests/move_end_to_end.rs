//! Two nodes in one process on the in-process carrier, one agent: genesis and export on node A, a
//! turn appended through a fake runtime host, replication to node B, a move to B, and B's rebuilt
//! store byte-identical to A's apart from the H1 node-local files. Then a turn on B replicates back.

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use async_trait::async_trait;
use core_carrier::in_process::InProcessNetwork;
use core_carrier::{NodeAddr, VerifiedPeer};
use core_membership::{CatalogRoot, MembershipLog, Op, OpHeader, OpPayload, Preset, SignedOp};
use core_node::{
    AgentRuntime, AppServerEndpoint, FeedEntry, MoveOptions, Node, NodeConfig, Quiesce,
    RuntimeError, RuntimeHost, RuntimeInfo,
};
use core_placement::Gate;
use core_projection::Gitoxide;
use core_projection::layout::{self, ConversationKey};
use core_projection::materialize::disk_manifest;
use core_projection::testing::{CommitSpec, FixtureStore, append_file, commit, expand};
use core_proto::crypto::NodeKey;
use core_proto::{AgentId, ConversationId, NodeId};
use core_store::{BindingKey, MemoryStore, Store};
use core_testkit::contract_path;
use serde_json::Value;

/// A runtime host that runs nothing: it records calls, and a "turn" writes letta-code's files the
/// way the App Server would (a transcript append, the conversation record, a memfs commit).
#[derive(Default)]
struct FakeHost {
    calls: Mutex<Vec<String>>,
}

impl FakeHost {
    fn calls(&self) -> Vec<String> {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    fn note(&self, call: String) {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push(call);
    }

    fn turn(&self, backend: &Path, store: &FixtureStore, n: u32) {
        let dir = ConversationKey::Default {
            agent: store.agent.clone(),
        }
        .dir_name();
        let transcript = format!("conversations/{dir}/messages.jsonl");
        let line = format!(
            "{{\"type\":\"message\",\"id\":\"turn{n:04}\",\"message\":{{\"id\":\"ui-msg-t{n}\",\"role\":\"user\",\"content\":[{{\"type\":\"text\",\"text\":\"turn {n}\"}}]}}}}\n"
        );
        append_file(backend, &transcript, line.as_bytes()).expect("append");
        let record = format!(
            "{{\"id\":\"default\",\"agent_id\":\"{}\",\"model\":null,\"in_context_message_ids\":[\"ui-msg-t{n}\"]}}\n",
            store.agent
        );
        core_projection::testing::write_file(
            backend,
            &format!("conversations/{dir}/conversation.json"),
            record.as_bytes(),
        )
        .expect("record");
        // A cache letta-code rewrites every turn: node-local, never exported.
        core_projection::testing::write_file(
            backend,
            &format!("conversations/{dir}/system-prompt.json"),
            format!("{{\"compiledAt\":{n}}}").as_bytes(),
        )
        .expect("cache");
        let mut files = store.commits.last().expect("commit").files.clone();
        files.insert(format!("turns/{n}.md"), format!("turn {n}\n"));
        commit(
            &Gitoxide,
            &layout::to_native(backend, &store.repo),
            &store.agent,
            &[CommitSpec {
                message: format!("turn {n}"),
                files,
                time: 1_764_200_000 + u64::from(n),
            }],
        )
        .expect("memfs commit");
        self.note(format!("turn {n}"));
    }
}

#[async_trait]
impl RuntimeHost for FakeHost {
    fn info(&self) -> RuntimeInfo {
        RuntimeInfo {
            letta_code_version: Some("0.34.70".into()),
            patch_ids: Some(vec!["lcp-memory-lf".into(), "lcp-model-profile".into()]),
            patches_applied: Some((2, 0)),
            models: vec!["profile/main".into(), "profile/fast".into()],
        }
    }

    async fn start(&self, spec: &AgentRuntime) -> Result<AppServerEndpoint, RuntimeError> {
        self.note(format!("start {}", spec.agent_id.as_str()));
        Ok(AppServerEndpoint {
            url: "ws://127.0.0.1:0".into(),
        })
    }

    async fn stop(&self, agent: &AgentId) -> Result<(), RuntimeError> {
        self.note(format!("stop {}", agent.as_str()));
        Ok(())
    }

    async fn quiesce(&self, agent: &AgentId, _timeout: Duration) -> Result<Quiesce, RuntimeError> {
        self.note(format!("quiesce {}", agent.as_str()));
        Ok(Quiesce::Idle)
    }

    async fn checkpoint(&self, agent: &AgentId) -> Result<(), RuntimeError> {
        self.note(format!("checkpoint {}", agent.as_str()));
        Ok(())
    }

    async fn abort_turn(&self, agent: &AgentId) -> Result<(), RuntimeError> {
        self.note(format!("abort {}", agent.as_str()));
        Ok(())
    }

    fn endpoint(&self) -> Option<AppServerEndpoint> {
        None
    }
}

fn key(n: u8) -> NodeKey {
    NodeKey::from_seed(&[n; 32])
}

/// Node 1 founds the constellation; node 2 joins as a desktop node.
fn membership(policy: Option<core_proto::bcbor::Value>) -> (core_membership::OpId, Vec<SignedOp>) {
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
                name: Some("node-a".into()),
                label: None,
                catalog_root: CatalogRoot([0; 16]),
            },
            extensions: BTreeMap::new(),
        },
        &key(1),
    )
    .expect("genesis");
    let constellation = genesis.id();
    let add = SignedOp::sign(
        &Op {
            header: OpHeader {
                constellation: Some(constellation),
                issuer: key(1).node_id(),
                parents: BTreeSet::from([constellation]),
                ts_ms: 2,
            },
            payload: OpPayload::Add {
                subject: key(2).node_id(),
                caps: Preset::DesktopNode.capabilities(),
                name: Some("node-b".into()),
            },
            extensions: BTreeMap::new(),
        },
        &key(1),
    )
    .expect("add");
    let mut ops = vec![genesis, add];
    if let Some(policy) = policy {
        let parent = ops[1].id();
        ops.push(
            SignedOp::sign(
                &Op {
                    header: OpHeader {
                        constellation: Some(constellation),
                        issuer: key(1).node_id(),
                        parents: BTreeSet::from([parent]),
                        ts_ms: 3,
                    },
                    payload: OpPayload::PolicySet { policy },
                    extensions: BTreeMap::new(),
                },
                &key(1),
            )
            .expect("policy"),
        );
    }
    let mut log = MembershipLog::new(constellation);
    for op in &ops {
        log.insert(op.clone()).expect("insert");
    }
    assert!(log.evaluate().rejections.is_empty());
    (constellation, ops)
}

struct TestNode {
    node: Node<MemoryStore>,
    host: Arc<FakeHost>,
    backend: PathBuf,
}

async fn start_node(
    n: u8,
    root: &Path,
    network: &InProcessNetwork,
    constellation: core_membership::OpId,
    ops: &[SignedOp],
) -> TestNode {
    let backend = root.join(format!("node{n}")).join("backend");
    let data = root.join(format!("node{n}")).join("data");
    std::fs::create_dir_all(&backend).expect("backend");
    let store = Arc::new(MemoryStore::new());
    for (profile, handle) in [
        ("profile/main", "lmstudio/x-main"),
        ("profile/fast", "lmstudio/x-fast"),
    ] {
        store
            .bindings()
            .set_binding(
                &BindingKey::ModelProfile(profile.into()),
                &format!("{handle}-{n}"),
            )
            .expect("binding");
    }
    let mut config = NodeConfig::new(
        Arc::new(key(n)),
        backend.clone(),
        data,
        constellation,
        ops.to_vec(),
    );
    config.tick = Duration::from_millis(100);
    config.app_version = "0.0.0-test".into();
    let id = key(n).node_id();
    let host = Arc::new(FakeHost::default());
    let carrier = Arc::new(network.carrier(VerifiedPeer::Node(id)));
    let node = Node::start(
        config,
        store,
        carrier,
        Arc::clone(&host) as Arc<dyn RuntimeHost>,
    )
    .await
    .expect("start node");
    network.serve(id, node.protocols());
    TestNode {
        node,
        host,
        backend,
    }
}

async fn eventually(what: &str, mut check: impl FnMut() -> bool) {
    for _ in 0..300 {
        if check() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    panic!("timed out waiting for {what}");
}

fn tip(node: &Node<MemoryStore>, agent: &AgentId) -> Option<(u64, NodeId)> {
    node.placement(agent)
        .ok()
        .flatten()
        .map(|r| (r.tip.seq, r.epoch.claimant))
}

/// Every file under `dir`, relative, skipping `.git`.
fn tree(dir: &Path) -> BTreeMap<String, Vec<u8>> {
    fn walk(base: &Path, dir: &Path, out: &mut BTreeMap<String, Vec<u8>>) {
        for entry in std::fs::read_dir(dir).expect("dir").flatten() {
            let path = entry.path();
            if path.file_name().is_some_and(|n| n == ".git") {
                continue;
            }
            if path.is_dir() {
                walk(base, &path, out);
            } else {
                let rel = path
                    .strip_prefix(base)
                    .expect("prefix")
                    .to_string_lossy()
                    .replace('\\', "/");
                out.insert(rel, std::fs::read(&path).expect("read"));
            }
        }
    }
    let mut out = BTreeMap::new();
    walk(dir, dir, &mut out);
    out
}

/// B's rebuilt store: every replicated file byte-identical to A's, nothing node-local copied, the
/// memfs checkout and refs equal, the hooks node-local, and the feed journal carried over.
fn assert_rebuilt_store(
    a: &TestNode,
    b: &TestNode,
    store: &FixtureStore,
    never: &BTreeSet<String>,
) {
    let ours = |path: &str| !never.contains(path);
    let a_manifest = disk_manifest(&a.backend, ours).expect("A manifest");
    let b_manifest = disk_manifest(&b.backend, |_| true).expect("B manifest");
    assert_eq!(
        a_manifest, b_manifest,
        "replicated files are byte-identical"
    );
    for path in never {
        assert!(
            !layout::to_native(&b.backend, path).exists(),
            "{path} is node-local and must not reach B"
        );
    }
    let a_repo = layout::to_native(&a.backend, &store.repo);
    let b_repo = layout::to_native(&b.backend, &store.repo);
    assert_eq!(tree(&a_repo), tree(&b_repo), "memfs working tree");
    assert!(b_repo.join("turns").join("1.md").is_file());
    let refs =
        |dir: &Path| <Gitoxide as core_projection::GitBackend>::refs(&Gitoxide, dir).expect("refs");
    assert_eq!(refs(&a_repo), refs(&b_repo), "memfs refs and HEAD");
    assert!(
        !b_repo
            .join(".git")
            .join("hooks")
            .join("pre-commit")
            .exists()
    );
    let config = std::fs::read_to_string(b_repo.join(".git").join("config")).expect("config");
    assert!(config.contains("hooksPath") && config.contains("autocrlf = false"));
    // The feed journal moved with the log.
    let head = b
        .node
        .store()
        .read(|txn| txn.feed_head(&ConversationId::new(format!("default:{}", store.agent))))
        .expect("feed head");
    assert_eq!(head, Some(1));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_turn_replicates_and_the_agent_moves_with_a_byte_identical_store() {
    let fixture: Value = serde_json::from_str(
        &std::fs::read_to_string(contract_path("projection/projection-v1-store.json"))
            .expect("fixture"),
    )
    .expect("json");
    let store = FixtureStore::from_json(&fixture);
    let never: BTreeSet<String> = fixture["never_exported"]
        .as_array()
        .expect("list")
        .iter()
        .map(|p| expand(p.as_str().expect("path"), &store.agent))
        .collect();
    let agent = AgentId::new(store.agent.clone());

    let temp = tempfile::tempdir().expect("tempdir");
    let network = InProcessNetwork::new();
    let (constellation, ops) = membership(None);
    let a = start_node(1, temp.path(), &network, constellation, &ops).await;
    let b = start_node(2, temp.path(), &network, constellation, &ops).await;
    assert_eq!(a.node.runtime_parity(), b.node.runtime_parity());
    let info = a.node.info();
    assert_eq!(info.letta_code_version, "0.34.70");
    assert!(info.capabilities.contains(&"execute".to_owned()));

    // Node A holds the agent: letta-code's store is on its disk; genesis exports it.
    store.write(&Gitoxide, &a.backend).expect("fixture store");
    a.node.create_agent(&agent).await.expect("create agent");
    assert_eq!(a.node.gate(&agent).expect("gate"), Gate::Active);

    // A turn through the runtime host, exported and journalled.
    a.host.turn(&a.backend, &store, 1);
    let handle_a = a.node.agent(&agent);
    handle_a.flush().await.expect("flush");
    let feed_seq = handle_a
        .append_feed(FeedEntry {
            conversation_id: ConversationId::new(format!("default:{}", store.agent)),
            turn: "turn-1".into(),
            kind: "turn_closed".into(),
            entry: br#"{"type":"turn_closed","turn":"turn-1"}"#.to_vec(),
            replaces_feed_seq: None,
        })
        .await
        .expect("feed");
    assert_eq!(feed_seq, 1);

    // Replication to B.
    a.node
        .connect(NodeAddr::new(b.node.id()))
        .await
        .expect("connect");
    eventually("B to replicate A's line", || {
        tip(&b.node, &agent).is_some() && tip(&b.node, &agent) == tip(&a.node, &agent)
    })
    .await;
    assert_eq!(b.node.gate(&agent).expect("gate"), Gate::NotHolder);

    // Move to B.
    a.node
        .agent(&agent)
        .move_to(
            b.node.id(),
            MoveOptions {
                quiesce_timeout_ms: 10_000,
                stop_turn: false,
            },
        )
        .await
        .expect("move");
    eventually("B to hold the agent and start its runtime", || {
        b.node.gate(&agent).ok() == Some(Gate::Active)
            && b.host.calls().iter().any(|c| c.starts_with("start"))
    })
    .await;
    let record = b.node.placement(&agent).expect("record").expect("placed");
    assert_eq!(record.epoch.claimant, b.node.id());
    assert_eq!(record.epoch.n, 2);
    eventually("A to see B's epoch", || {
        a.node.gate(&agent).ok() == Some(Gate::NotHolder)
    })
    .await;
    assert!(a.host.calls().iter().any(|c| c.starts_with("quiesce")));
    assert!(a.host.calls().iter().any(|c| c.starts_with("stop")));
    assert!(b.host.calls().iter().any(|c| c.starts_with("start")));

    assert_rebuilt_store(&a, &b, &store, &never);

    // B is the holder now: its turn replicates back to A.
    b.host.turn(&b.backend, &store, 2);
    b.node.agent(&agent).flush().await.expect("flush on B");
    eventually("A to replicate B's turn", || {
        tip(&a.node, &agent) == tip(&b.node, &agent)
    })
    .await;
    assert!(
        a.node.agent(&agent).flush().await.is_err(),
        "A may not write"
    );

    a.node.shutdown().await;
    b.node.shutdown().await;
}

/// Failover and the returning holder: A is partitioned while it writes a turn; B claims by
/// failover, materializes and holds; on healing, A sees B's epoch, stops (F4) and reports
/// `fenced`, and B records A's stranded tail as a divergence.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn failover_then_the_returning_holder_is_fenced_and_its_tail_recorded() {
    use core_proto::bcbor::Value as Cbor;
    let fixture: Value = serde_json::from_str(
        &std::fs::read_to_string(contract_path("projection/projection-v1-store.json"))
            .expect("fixture"),
    )
    .expect("json");
    let store = FixtureStore::from_json(&fixture);
    let agent = AgentId::new(store.agent.clone());
    let temp = tempfile::tempdir().expect("tempdir");
    let network = InProcessNetwork::new();
    // Automatic failover after 1 s without hearing the holder.
    let policy = Cbor::Map(BTreeMap::from([(1, Cbor::Uint(1_000)), (2, Cbor::Uint(0))]));
    let (constellation, ops) = membership(Some(policy));
    let a = start_node(1, temp.path(), &network, constellation, &ops).await;
    let b = start_node(2, temp.path(), &network, constellation, &ops).await;
    store.write(&Gitoxide, &a.backend).expect("fixture store");
    a.node.create_agent(&agent).await.expect("create agent");
    a.node
        .connect(NodeAddr::new(b.node.id()))
        .await
        .expect("connect");
    b.node
        .connect(NodeAddr::new(a.node.id()))
        .await
        .expect("connect");
    eventually("B to replicate A's line", || {
        tip(&b.node, &agent).is_some() && tip(&b.node, &agent) == tip(&a.node, &agent)
    })
    .await;

    let (pa, pb) = (
        VerifiedPeer::Node(a.node.id()),
        VerifiedPeer::Node(b.node.id()),
    );
    network.partition(pa, pb);
    // A keeps writing while partitioned: its tail will be stranded.
    a.host.turn(&a.backend, &store, 1);
    a.node.agent(&agent).flush().await.expect("flush on A");

    eventually("B to fail over and start the runtime", || {
        b.node.gate(&agent).ok() == Some(Gate::Active)
            && b.host.calls().iter().any(|c| c.starts_with("start"))
    })
    .await;
    let record = b.node.placement(&agent).expect("record").expect("placed");
    assert_eq!(record.epoch.claimant, b.node.id());
    // B's store was rebuilt from the line it had: the turn A wrote while partitioned is not in it.
    assert!(
        !layout::to_native(&b.backend, &store.repo)
            .join("turns")
            .join("1.md")
            .exists()
    );

    network.heal(pa, pb);
    eventually("A to be fenced", || {
        a.node.gate(&agent).ok() == Some(Gate::NotHolder)
            && a.host.calls().iter().any(|c| c.starts_with("abort"))
    })
    .await;
    let log_id = core_log::hashes::log_id(&agent);
    eventually("B to record A's stranded tail", || {
        b.node
            .store()
            .read(|txn| txn.divergences(&log_id))
            .is_ok_and(|records| records.iter().any(|r| r.recorded_entry.is_some()))
    })
    .await;
    assert!(
        a.node.agent(&agent).flush().await.is_err(),
        "A may not write"
    );

    a.node.shutdown().await;
    b.node.shutdown().await;
}
