//! The async driver over an in-memory byte pipe: catch-up, then the live tail of holder appends,
//! relayed through a third node that first heard of the log through the relay. (Stateless resume,
//! fresh sessions over stores that already hold data, is the `sessions_converge` property.)

#![allow(clippy::manual_async_fn)]

mod common;

use std::cell::Cell;
use std::future::Future;
use std::rc::Rc;

use common::{agent, append_payload, genesis_payload, key, log, node};
use core_log::driver::{Clock, Hub, Notice, run_session};
use core_log::payload::Payload;
use core_log::{AgentLog, Mode, SessionConfig, StructuralJudge};
use core_store::{MemoryStore, Store, StoreError};
use futures::executor::LocalPool;
use futures::task::LocalSpawnExt as _;

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

/// Connects two nodes with an in-memory duplex stream and spawns both session drivers. The duplex
/// buffer is small, so frames arrive in pieces and exercise reassembly.
fn connect(pool: &LocalPool, a: &Node, b: &Node) {
    let spawner = pool.spawner();
    let (a_end, b_end) = tokio::io::duplex(97);
    for (node, end) in [(a, a_end), (b, b_end)] {
        let (reader, writer) = tokio::io::split(end);
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
