//! The async driver over an in-memory byte pipe: catch-up, then the live tail of holder appends,
//! relayed through a third node that first heard of the log through the relay. (Stateless resume,
//! fresh sessions over stores that already hold data, is the `sessions_converge` property.)

#![allow(clippy::manual_async_fn)]

mod common;

use std::cell::Cell;
use std::future::Future;
use std::rc::Rc;

use common::{agent, append_payload, genesis_payload, key, log, node};
use core_log::driver::{ByteRead, ByteWrite, Clock, Hub, LinkError, Notice, run_session};
use core_log::payload::Payload;
use core_log::{AgentLog, Mode, SessionConfig, StructuralJudge};
use core_store::{MemoryStore, Store, StoreError};
use futures::StreamExt as _;
use futures::channel::mpsc;
use futures::executor::LocalPool;
use futures::task::LocalSpawnExt as _;

struct PipeRead {
    rx: mpsc::UnboundedReceiver<Vec<u8>>,
    pending: Vec<u8>,
}

struct PipeWrite {
    tx: Option<mpsc::UnboundedSender<Vec<u8>>>,
}

impl ByteRead for PipeRead {
    fn read(&mut self, buf: &mut [u8]) -> impl Future<Output = Result<usize, LinkError>> {
        async move {
            if self.pending.is_empty() {
                match self.rx.next().await {
                    Some(bytes) => self.pending = bytes,
                    None => return Ok(0),
                }
            }
            // Deliver in small pieces to exercise reassembly.
            let n = self.pending.len().min(buf.len()).min(97);
            buf[..n].copy_from_slice(&self.pending[..n]);
            self.pending.drain(..n);
            Ok(n)
        }
    }
}

impl ByteWrite for PipeWrite {
    fn write_all(&mut self, bytes: &[u8]) -> impl Future<Output = Result<(), LinkError>> {
        let result = self
            .tx
            .as_ref()
            .ok_or_else(|| LinkError("closed".into()))
            .and_then(|tx| {
                tx.unbounded_send(bytes.to_vec())
                    .map_err(|e| LinkError(e.to_string()))
            });
        async move { result }
    }

    fn close(&mut self) -> impl Future<Output = ()> {
        self.tx = None;
        async {}
    }
}

fn pipe() -> (PipeWrite, PipeRead) {
    let (tx, rx) = mpsc::unbounded();
    (
        PipeWrite { tx: Some(tx) },
        PipeRead {
            rx,
            pending: Vec::new(),
        },
    )
}

/// A clock whose timers never fire: the test drives everything by messages.
#[derive(Clone, Default)]
struct StillClock(Rc<Cell<u64>>);

impl Clock for StillClock {
    fn now_ms(&self) -> u64 {
        self.0.get()
    }

    fn sleep_until(&self, _at_ms: u64) -> impl Future<Output = ()> {
        futures::future::pending()
    }
}

struct Node {
    store: Rc<MemoryStore>,
    hub: Rc<Hub>,
    id: u8,
}

fn config(id: u8) -> SessionConfig {
    SessionConfig {
        node_id: node(id),
        constellation_id: [0xc0; 32],
        member_heads: vec![[1; 32]],
        mode: Mode::Live,
        store_format: 1,
    }
}

/// Connects two nodes with a pair of pipes and spawns both session drivers. Returns the two
/// write halves' kill switches (dropping them ends the link).
fn connect(pool: &LocalPool, a: &Node, b: &Node) {
    let spawner = pool.spawner();
    let (a_to_b_w, a_to_b_r) = pipe();
    let (b_to_a_w, b_to_a_r) = pipe();
    for (node, reader, writer) in [(a, b_to_a_r, a_to_b_w), (b, a_to_b_r, b_to_a_w)] {
        let store = node.store.clone();
        let hub = node.hub.clone();
        let id = node.id;
        spawner
            .spawn_local(async move {
                let judge = StructuralJudge;
                let clock = StillClock::default();
                let _ = run_session(
                    reader,
                    writer,
                    &*store,
                    &judge,
                    &hub,
                    &clock,
                    config(id),
                    |_| {},
                )
                .await;
            })
            .expect("spawn");
    }
}

fn tip(store: &MemoryStore) -> Option<(u64, core_store::EntryHash)> {
    store
        .read(|t| {
            let Some(p) = t.placement(&log())? else {
                return Ok::<_, StoreError>(None);
            };
            Ok(t.segment(&log(), &p.epoch)?
                .map(|s| (s.head_seq, s.head_hash)))
        })
        .expect("read")
}

#[test]
fn catch_up_then_live_tail_relayed_through_a_third_node() {
    let judge = StructuralJudge;
    let agent_log = AgentLog::for_agent(&agent(), &judge);
    let nodes: Vec<Node> = (1..=3)
        .map(|id| Node {
            store: Rc::new(MemoryStore::new()),
            hub: Rc::new(Hub::new()),
            id,
        })
        .collect();
    let holder = &nodes[0];
    let append = |text: &str, offset: u64| {
        let entry = holder
            .store
            .write(|t| {
                agent_log
                    .append(t, &key(1), &append_payload("c1", offset, text), offset)
                    .map_err(common::to_store)
            })
            .expect("append");
        holder.hub.publish(
            None,
            &Notice {
                log_id: log(),
                entries: vec![entry.hash()],
            },
        );
    };
    holder
        .store
        .write(|t| {
            agent_log
                .genesis(
                    t,
                    &key(1),
                    match genesis_payload() {
                        Payload::EpochOpen(o) => o,
                        _ => unreachable!(),
                    },
                    0,
                )
                .map_err(common::to_store)
        })
        .expect("genesis");
    let mut offset = 0;
    for i in 0..20 {
        let text = format!("line {i}\n");
        append(&text, offset);
        offset += text.len() as u64;
    }
    let mut pool = LocalPool::new();
    // A chain 1 - 2 - 3: node 3 only hears node 1's entries through node 2.
    connect(&pool, &nodes[0], &nodes[1]);
    connect(&pool, &nodes[1], &nodes[2]);
    pool.run_until_stalled();
    assert_eq!(tip(&nodes[1].store), tip(&holder.store), "catch-up");
    assert_eq!(tip(&nodes[2].store), tip(&holder.store), "relayed catch-up");
    for i in 20..25 {
        let text = format!("line {i}\n");
        append(&text, offset);
        offset += text.len() as u64;
        pool.run_until_stalled();
        assert_eq!(
            tip(&nodes[2].store),
            tip(&holder.store),
            "live tail relayed"
        );
    }
}
