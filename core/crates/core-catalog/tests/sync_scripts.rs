//! catalog-v1 sync scripts (§6.3): each `sync/<case>.json` is a list of steps run over the
//! in-process carrier with Rust catalog nodes, each on its own memory store. Frames are asserted by
//! effect (heads, views, closures, quarantine), never by bytes: samod session ids vary per run.

mod common;

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use automerge::transaction::Transactable as _;
use common::{Constellation, fixture_path, node};
use core_carrier::in_process::{InProcessCarrier, InProcessNetwork};
use core_carrier::{Carrier, CarrierError, LinkClosure, NodeAddr, Protocols, VerifiedPeer};
use core_catalog::model::{ActorRef, Detail, NewNode, NodeKind};
use core_catalog::protocol::system_clock;
use core_catalog::write::{self, Genesis};
use core_catalog::{
    ALPN, CatalogEvent, CatalogNode, CatalogPolicy, CatalogView, DocClass, DocUrl, NotebookGrant,
    ViewContext,
};
use core_membership::{CapSet, Capability, MembershipView, Preset};
use core_proto::{AgentId, NodeId};
use core_store::kv::encode_key_path;
use core_store::{DocId, MemoryStore, Namespace, Store};
use futures::{SinkExt, StreamExt};
use serde_json::Value;
use tokio_util::codec::{Framed, LengthDelimitedCodec};

const WAIT: Duration = Duration::from_secs(20);

struct World {
    net: InProcessNetwork,
    constellation: Constellation,
    nodes: BTreeMap<String, Peer>,
    docs: BTreeMap<String, DocUrl>,
    grants: core_catalog::NotebookGrants,
}

struct Peer {
    n: u8,
    node: CatalogNode<MemoryStore>,
    store: Arc<MemoryStore>,
    believes: Option<MembershipView>,
    closures: Arc<Mutex<Vec<(NodeId, LinkClosure)>>>,
    dialers: Vec<samod::DialerHandle>,
}

fn preset(name: &str) -> Preset {
    Preset::from_name(name).unwrap_or_else(|| panic!("unknown preset {name}"))
}

fn str_of<'a>(step: &'a Value, key: &str) -> &'a str {
    step[key]
        .as_str()
        .unwrap_or_else(|| panic!("step needs {key}: {step}"))
}

fn n_of(step: &Value, key: &str) -> u8 {
    u8::try_from(
        step[key]
            .as_u64()
            .unwrap_or_else(|| panic!("step needs {key}: {step}")),
    )
    .expect("small")
}

async fn eventually<F, Fut>(what: &str, mut check: F)
where
    F: FnMut() -> Fut,
    Fut: Future<Output = bool>,
{
    let deadline = tokio::time::Instant::now() + WAIT;
    while tokio::time::Instant::now() < deadline {
        if check().await {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("timed out waiting for {what}");
}

impl World {
    fn policy_for(&self, peer_view: Option<&MembershipView>, store: &MemoryStore) -> CatalogPolicy {
        let docs = store.read(|txn| txn.catalog_docs()).expect("registry");
        let view = peer_view
            .cloned()
            .unwrap_or_else(|| self.constellation.view.clone());
        CatalogPolicy::new(view, &docs, self.grants.clone())
    }

    fn republish(&self) {
        for peer in self.nodes.values() {
            peer.node
                .set_policy(self.policy_for(peer.believes.as_ref(), &peer.store));
        }
    }

    fn peer(&self, name: &str) -> &Peer {
        self.nodes
            .get(name)
            .unwrap_or_else(|| panic!("no node {name}"))
    }

    fn carrier(&self, n: u8) -> Arc<InProcessCarrier> {
        Arc::new(self.net.carrier(VerifiedPeer::Node(node(n))))
    }

    async fn nodes_of(&self, name: &str, doc: &str) -> Vec<String> {
        let peer = self.peer(name);
        let url = self.docs[doc];
        let Ok(CatalogView::Agent(view)) = peer.node.view(&url, &ViewContext::default()).await
        else {
            return Vec::new();
        };
        view.conversations.keys().cloned().collect()
    }

    #[allow(clippy::too_many_lines, reason = "one interpreter arm per script step")]
    async fn step(&mut self, step: &Value, members: &[(u8, Preset)]) {
        match str_of(step, "op") {
            "node" => {
                let n = n_of(step, "n");
                let believes = step.get("believes").and_then(Value::as_str).map(|name| {
                    let others: Vec<(u8, Preset)> = members
                        .iter()
                        .map(|(m, p)| (*m, if *m == n { preset(name) } else { *p }))
                        .collect();
                    Constellation::new(&others, root()).view
                });
                let store = Arc::new(MemoryStore::new());
                let policy = self.policy_for(believes.as_ref(), &store);
                let catalog =
                    CatalogNode::start(node(n), Arc::clone(&store), policy, system_clock()).await;
                let protocols = Protocols::builder()
                    .access(catalog.access_hook())
                    .accept_stream(ALPN, catalog.handler())
                    .build();
                self.net.serve(node(n), protocols);
                let closures = Arc::new(Mutex::new(Vec::new()));
                let sink = Arc::clone(&closures);
                let mut events = catalog.events();
                tokio::spawn(async move {
                    while let Ok(event) = events.recv().await {
                        if let CatalogEvent::LinkClosed { principal, closure } = event {
                            sink.lock().expect("lock").push((principal, closure));
                        }
                    }
                });
                self.nodes.insert(
                    str_of(step, "name").to_owned(),
                    Peer {
                        n,
                        node: catalog,
                        store,
                        believes,
                        closures,
                        dialers: Vec::new(),
                    },
                );
            }
            "create" => {
                let peer = self.peer(str_of(step, "name"));
                let class = DocClass::parse(str_of(step, "class")).expect("class");
                let url = peer
                    .node
                    .create(
                        class,
                        &Genesis {
                            constellation_id: self.constellation.id_hex(),
                            created_at_ms: 0,
                            agent_id: step["agent"].as_str().map(str::to_owned),
                            name: None,
                        },
                    )
                    .await
                    .expect("create");
                self.docs.insert(str_of(step, "as").to_owned(), url);
            }
            "create_notebook" => {
                let peer = self.peer(str_of(step, "name"));
                let mut doc = automerge::AutoCommit::new();
                doc.put(automerge::ROOT, "schema", "notebook/1")
                    .expect("schema");
                doc.put(automerge::ROOT, "title", str_of(step, "title"))
                    .expect("title");
                let loaded = automerge::Automerge::load(&doc.save()).expect("loads");
                let url = peer
                    .node
                    .import(DocClass::Notebook, None, loaded)
                    .await
                    .expect("import");
                self.docs.insert(str_of(step, "as").to_owned(), url);
            }
            "grant" => {
                let doc = self.docs[str_of(step, "doc")].doc_id();
                self.grants
                    .entry(node(n_of(step, "n")))
                    .or_default()
                    .insert(
                        doc,
                        NotebookGrant {
                            may_read: step["may_read"].as_bool().unwrap_or(false),
                            may_edit: step["may_edit"].as_bool().unwrap_or(false),
                        },
                    );
                self.republish();
            }
            "register" => {
                let peer = self.peer(str_of(step, "name"));
                let class = DocClass::parse(str_of(step, "class")).expect("class");
                let url = self.docs[str_of(step, "doc")];
                peer.node
                    .register(class, url.doc_id(), None::<AgentId>)
                    .expect("register");
                let policy = self.policy_for(peer.believes.as_ref(), &peer.store);
                peer.node.set_policy(policy);
            }
            "connect" => {
                let to = self.peer(str_of(step, "to")).n;
                let from = str_of(step, "from").to_owned();
                let carrier = self.carrier(self.peer(&from).n);
                let handle = self
                    .peer(&from)
                    .node
                    .dial(carrier, NodeAddr::new(node(to)))
                    .expect("dial");
                self.nodes
                    .get_mut(&from)
                    .expect("node")
                    .dialers
                    .push(handle);
            }
            "find" => {
                let peer = self.peer(str_of(step, "name"));
                let url = self.docs[str_of(step, "doc")];
                eventually("find", || async {
                    matches!(peer.node.find(&url).await, Ok(Some(_)))
                })
                .await;
            }
            "expect_not_found" => {
                let peer = self.peer(str_of(step, "name"));
                let url = self.docs[str_of(step, "doc")];
                let found = tokio::time::timeout(WAIT, peer.node.find(&url))
                    .await
                    .expect("find resolves");
                assert!(matches!(found, Ok(None)), "{step}");
            }
            "edit" => {
                let peer = self.peer(str_of(step, "name"));
                let url = self.docs[str_of(step, "doc")];
                let id = str_of(step, "put_root").to_owned();
                let by = ActorRef::node(node(peer.n).to_hex());
                peer.node
                    .write(&url, move |tx| {
                        write::put_node(
                            tx,
                            &NewNode {
                                id: id.clone(),
                                agent_id: "agent-a".into(),
                                parent_id: None,
                                kind: NodeKind::Root,
                                fork_point: None,
                                created_by: by,
                                created_at_ms: 0,
                                title: None,
                                title_source: None,
                                last_activity_at_ms: None,
                                deleted_at_ms: None,
                                detail: Detail::None {},
                            },
                        )
                    })
                    .await
                    .expect("write");
            }
            "settle" => tokio::time::sleep(Duration::from_millis(500)).await,
            "expect_heads_equal" => {
                let url = self.docs[str_of(step, "doc")];
                let a = &self.peer(str_of(step, "a")).node;
                let b = &self.peer(str_of(step, "b")).node;
                eventually("equal heads", || async {
                    let left = a.heads(&url).await.ok();
                    left.is_some() && left == b.heads(&url).await.ok()
                })
                .await;
            }
            "expect_nodes" => {
                let mut expected: Vec<String> = step["ids"]
                    .as_array()
                    .expect("ids")
                    .iter()
                    .map(|id| id.as_str().expect("id").to_owned())
                    .collect();
                expected.sort();
                let (name, doc) = (str_of(step, "name"), str_of(step, "doc"));
                if expected.is_empty() {
                    tokio::time::sleep(Duration::from_millis(300)).await;
                    assert!(self.nodes_of(name, doc).await.is_empty(), "{step}");
                } else {
                    eventually("nodes", || async {
                        self.nodes_of(name, doc).await == expected
                    })
                    .await;
                }
            }
            "expect_field" => {
                let peer = self.peer(str_of(step, "name"));
                let url = self.docs[str_of(step, "doc")];
                let root = peer.node.canonical(&url).await.expect("canonical");
                assert_eq!(root[str_of(step, "key")], step["value"], "{step}");
            }
            "expect_no_copy" => {
                let peer = self.peer(str_of(step, "name"));
                let url = self.docs[str_of(step, "doc")];
                let prefix = encode_key_path(&[url.bs58()]);
                let rows = peer
                    .store
                    .read(|txn| txn.kv_scan_prefix(Namespace::SAMOD, &prefix))
                    .expect("scan");
                assert!(rows.is_empty(), "an unregistered document was stored");
            }
            "membership" => {
                if let Some(n) = step["revoke"].as_u64() {
                    self.constellation.revoke(u8::try_from(n).expect("small"));
                }
                if let Some(n) = step["remove_caps"].as_u64() {
                    let caps: CapSet = step["caps"]
                        .as_array()
                        .expect("caps")
                        .iter()
                        .map(|c| Capability::new(c.as_str().expect("cap")).expect("cap"))
                        .collect();
                    self.constellation
                        .grant(u8::try_from(n).expect("small"), CapSet::new(), caps);
                }
                self.republish();
            }
            "expect_closed" => {
                let peer = self.peer(str_of(step, "name"));
                let principal = node(self.peer(str_of(step, "peer")).n);
                let code = step["code"].as_u64().expect("code");
                let reason = str_of(step, "reason").to_owned();
                let closures = Arc::clone(&peer.closures);
                eventually("closure", || {
                    let closures = Arc::clone(&closures);
                    let reason = reason.clone();
                    async move {
                        closures.lock().expect("lock").iter().any(|(who, closure)| {
                            *who == principal
                                && closure
                                    .app()
                                    .is_some_and(|c| c.code == code && c.reason == reason)
                        })
                    }
                })
                .await;
            }
            "expect_quarantine" => {
                let peer = self.peer(str_of(step, "name"));
                let reason = str_of(step, "reason");
                let at_least =
                    usize::try_from(step["at_least"].as_u64().unwrap_or(1)).expect("small");
                let store = Arc::clone(&peer.store);
                eventually("quarantine", || {
                    let store = Arc::clone(&store);
                    async move {
                        let rows = store
                            .read(|txn| core_catalog::quarantine::rows(txn))
                            .expect("rows");
                        rows.iter().filter(|row| row.reason == reason).count() >= at_least
                    }
                })
                .await;
            }
            "raw_join" => {
                let to = self.peer(str_of(step, "to")).n;
                let carrier = self.carrier(n_of(step, "n"));
                let link = carrier
                    .dial_stream(&NodeAddr::new(node(to)), ALPN)
                    .await
                    .expect("dial");
                let mut framed = Framed::new(link.stream, LengthDelimitedCodec::new());
                let join = core_catalog::wire::Message::new(vec![
                    ("type", ciborium::Value::Text("join".into())),
                    (
                        "senderId",
                        ciborium::Value::Text(str_of(step, "sender").into()),
                    ),
                    (
                        "supportedProtocolVersions",
                        ciborium::Value::Array(vec![ciborium::Value::Text("1".into())]),
                    ),
                ]);
                framed
                    .send(bytes::Bytes::from(join.encode()))
                    .await
                    .expect("send");
                let closure = tokio::time::timeout(WAIT, link.control.closed())
                    .await
                    .expect("closed");
                let app = closure.app().expect("an application close");
                assert_eq!(app.code, step["code"].as_u64().expect("code"));
                assert_eq!(app.reason, str_of(step, "reason"));
                drop(framed.next().await);
            }
            "dial_refused" => {
                let to = self.peer(str_of(step, "to")).n;
                let carrier = self.carrier(n_of(step, "n"));
                let refused = carrier.dial_stream(&NodeAddr::new(node(to)), ALPN).await;
                let Err(CarrierError::Closed(closure)) = refused else {
                    panic!("expected a refusal, got {refused:?}");
                };
                let app = closure.app().expect("an application close");
                assert_eq!(app.code, step["code"].as_u64().expect("code"));
                assert_eq!(app.reason, str_of(step, "reason"));
            }
            other => panic!("unknown step {other}"),
        }
    }
}

fn root() -> DocId {
    common::fixture_doc_id("sync root")
}

async fn run(case: &str) {
    let script: Value = serde_json::from_str(
        &std::fs::read_to_string(fixture_path(&format!("sync/{case}.json"))).expect("script"),
    )
    .expect("json");
    let members: Vec<(u8, Preset)> = script["members"]
        .as_array()
        .expect("members")
        .iter()
        .map(|m| (n_of(m, "n"), preset(str_of(m, "preset"))))
        .collect();
    let mut world = World {
        net: InProcessNetwork::new(),
        constellation: Constellation::new(&members, root()),
        nodes: BTreeMap::new(),
        docs: BTreeMap::new(),
        grants: BTreeMap::new(),
    };
    for step in script["steps"].as_array().expect("steps") {
        world.step(step, &members).await;
    }
    for peer in world.nodes.values() {
        peer.node.stop().await;
    }
}

macro_rules! scripts {
    ($($name:ident),* $(,)?) => {
        $(
            #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
            async fn $name() {
                run(stringify!($name)).await;
            }
        )*
    };
}

scripts!(
    convergence,
    read_only_strip,
    unknown_doc,
    revoke_and_caps,
    peer_id_mismatch,
    guest_notebook,
);

#[test]
fn every_script_on_disk_has_a_test() {
    let dir = fixture_path("sync");
    let mut on_disk: Vec<String> = std::fs::read_dir(dir)
        .expect("sync dir")
        .filter_map(|entry| {
            let name = entry.ok()?.file_name().into_string().ok()?;
            name.strip_suffix(".json").map(str::to_owned)
        })
        .collect();
    on_disk.sort();
    let mut tested = vec![
        "convergence",
        "guest_notebook",
        "peer_id_mismatch",
        "read_only_strip",
        "revoke_and_caps",
        "unknown_doc",
    ];
    tested.sort_unstable();
    assert_eq!(on_disk, tested);
}
