//! Random schedules over three nodes: turns, tool calls, moves, forced takeovers, partitions,
//! heals, severed links, crashes, restarts, loss and time. Every step is followed by the safety
//! check; at the end the network is healed, every node restarted, and the convergence invariants
//! are checked at quiescence.
//!
//! The runner's generator is seeded, so CI runs the same schedules every time; `PROPTEST_CASES`
//! raises the count locally.

use std::cell::RefCell;
use std::rc::Rc;

use core_log::Reason;
use core_membership::Preset;
use core_sim::{NodeSpec, Policy, Step, run};
use proptest::collection::vec;
use proptest::prelude::*;
use proptest::test_runner::{Config, RngAlgorithm, TestRng, TestRunner};

const AGENT: &str = "agent-alpha";
const SEED: [u8; 32] = *b"baken core simulator fuzz seed 1";

fn others(node: u8) -> Vec<u8> {
    (1..=3).filter(|n| *n != node).collect()
}

fn step() -> impl Strategy<Value = Step> {
    let node = || 1_u8..=3;
    prop_oneof![
        5 => (node(), 1_usize..4).prop_map(|(node, n)| Step::Turn {
            node,
            agent: AGENT.into(),
            conv: "conv-1".into(),
            n,
        }),
        4 => prop_oneof![Just(500_u64), Just(5_000), Just(70_000), Just(200_000)]
            .prop_map(Step::Advance),
        1 => node().prop_map(|n| Step::Partition(vec![vec![n], others(n)])),
        2 => Just(Step::Heal),
        1 => node().prop_map(Step::Crash),
        2 => node().prop_map(Step::Restart),
        1 => (node(), node()).prop_map(|(a, b)| Step::Sever(a, b)),
        1 => (node(), node()).prop_map(|(from, to)| Step::Move {
            from,
            agent: AGENT.into(),
            to,
        }),
        1 => node().prop_map(|node| Step::Claim {
            node,
            agent: AGENT.into(),
            reason: Reason::TakeoverForced,
        }),
        1 => prop_oneof![Just(0_u32), Just(5_000)].prop_map(Step::DropRate),
        1 => (node(), 0_u8..4, any::<bool>()).prop_map(|(node, k, finish)| Step::ToolCall {
            node,
            agent: AGENT.into(),
            key: format!("tool-{k}"),
            finish,
        }),
    ]
}

/// Runs one schedule; returns the convergence violations at the end (a safety violation during
/// the schedule panics, which the runner reports the same way).
fn run_schedule(seed: u64, steps: &[Step]) -> Vec<String> {
    let out = Rc::new(RefCell::new(Vec::new()));
    let sink = Rc::clone(&out);
    let specs: Vec<NodeSpec> = (1..=3).map(NodeSpec::desktop).collect();
    run(seed, &specs, 1, |sim| async move {
        for n in [2, 3] {
            sim.add(1, n, Preset::DesktopNode.capabilities())
                .expect("add");
        }
        sim.policy(1, &Policy::auto(60_000, 10_000))
            .expect("policy");
        sim.create_agent(1, AGENT).expect("genesis");
        sim.advance(2_000).await;
        for step in steps {
            sim.step(step).await;
        }
        sim.drop_rate(0);
        sim.heal();
        for n in 1..=3 {
            sim.restart(n);
        }
        let violations = sim.settle(5 * 60_000).await;
        *sink.borrow_mut() = violations.iter().map(ToString::to_string).collect();
    });
    out.take()
}

#[test]
fn random_schedules_keep_every_invariant() {
    let cases = std::env::var("PROPTEST_CASES")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(24);
    let config = Config {
        cases,
        max_shrink_iters: 64,
        failure_persistence: None,
        ..Config::default()
    };
    let mut runner =
        TestRunner::new_with_rng(config, TestRng::from_seed(RngAlgorithm::ChaCha, &SEED));
    let result = runner.run(&(any::<u64>(), vec(step(), 6..24)), |(seed, steps)| {
        let violations = run_schedule(seed, &steps);
        prop_assert!(violations.is_empty(), "{violations:#?}");
        Ok(())
    });
    if let Err(error) = result {
        panic!("{error}");
    }
}
