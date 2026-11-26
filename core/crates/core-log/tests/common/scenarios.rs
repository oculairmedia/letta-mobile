//! The chain and divergence scenarios shared by the fixtures and the property tests.

use core_log::Reason;
use core_log::payload::{Checkpoint, ManifestEntry, Payload};

use super::{Built, append_payload, build, epoch, genesis_payload, open_payload, seal_payload};

/// A named scenario: entries in a valid delivery order.
pub struct Scenario {
    pub name: &'static str,
    pub notes: &'static str,
    pub entries: Vec<(&'static str, Built)>,
}

fn checkpoint_payload() -> Payload {
    Payload::Checkpoint(Checkpoint {
        manifest: vec![ManifestEntry {
            path: "conversations/c1/messages.jsonl".into(),
            sha256: core_log::hashes::sha256(b"m1\n"),
            len: 3,
        }],
        refs: Vec::new(),
        feed_heads: Vec::new(),
        store_format: 1,
        runtime_parity: "letta-code@0.0.0+lcp:00".into(),
        h1_violations: Vec::new(),
    })
}

#[allow(clippy::too_many_lines)]
pub fn all() -> Vec<Scenario> {
    let mut out = Vec::new();

    let g = build(1, 1, None, &genesis_payload(), 1_000);
    let a1 = build(1, 1, Some(&g), &append_payload("c1", 0, "m1\n"), 1_001);
    let a2 = build(1, 1, Some(&a1), &append_payload("c1", 3, "m2\n"), 1_002);
    out.push(Scenario {
        name: "genesis-and-appends",
        notes: "One segment; the canonical line is the whole log.",
        entries: vec![("g", g.clone()), ("a1", a1.clone()), ("a2", a2.clone())],
    });

    let cp = build(1, 1, Some(&a1), &checkpoint_payload(), 1_002);
    let seal = build(
        1,
        1,
        Some(&cp),
        &seal_payload(Some(epoch(2, 2)), cp.pos()),
        1_003,
    );
    let mv = build(
        2,
        2,
        Some(&seal),
        &open_payload(Reason::Move, epoch(1, 1)),
        1_004,
    );
    let b1 = build(2, 2, Some(&mv), &append_payload("c1", 3, "m2\n"), 1_005);
    out.push(Scenario {
        name: "clean-move",
        notes: "N1 checkpoints and seals to (2, N2); N2 opens with reason move. No divergence.",
        entries: vec![
            ("g", g.clone()),
            ("a1", a1.clone()),
            ("cp", cp),
            ("seal", seal),
            ("open_n2", mv),
            ("b1", b1),
        ],
    });

    let fo = build(
        2,
        2,
        Some(&a1),
        &open_payload(Reason::Failover, epoch(1, 1)),
        2_000,
    );
    let b1 = build(2, 2, Some(&fo), &append_payload("c1", 3, "b1\n"), 2_001);
    let a3 = build(1, 1, Some(&a2), &append_payload("c1", 6, "m3\n"), 1_003);
    out.push(Scenario {
        name: "failover-partition",
        notes: "N2 fails over from a1 while N1, partitioned, writes a2 and a3: a cross-epoch divergence, stranded (1, N1), fork at seq 1.",
        entries: vec![
            ("g", g.clone()),
            ("a1", a1.clone()),
            ("a2", a2.clone()),
            ("open_n2", fo),
            ("b1", b1),
            ("a3", a3),
        ],
    });

    let c2 = build(
        2,
        2,
        Some(&a1),
        &open_payload(Reason::Failover, epoch(1, 1)),
        3_000,
    );
    let c3 = build(
        3,
        2,
        Some(&a1),
        &open_payload(Reason::Failover, epoch(1, 1)),
        3_000,
    );
    let x2 = build(2, 2, Some(&c2), &append_payload("c1", 3, "x\n"), 3_001);
    let x3 = build(3, 2, Some(&c3), &append_payload("c1", 3, "y\n"), 3_001);
    out.push(Scenario {
        name: "simultaneous-claims",
        notes: "N2 and N3 both claim n = 2 from a1; the higher claimant id wins on every node, the other is a divergence.",
        entries: vec![
            ("g", g.clone()),
            ("a1", a1.clone()),
            ("open_n2", c2),
            ("open_n3", c3),
            ("x_n2", x2),
            ("x_n3", x3),
        ],
    });

    let e1 = build(1, 1, Some(&a1), &append_payload("c1", 3, "left\n"), 4_000);
    let e2 = build(1, 1, Some(&a1), &append_payload("c1", 3, "right\n"), 4_000);
    let (low, high) = if e1.entry.hash() < e2.entry.hash() {
        (e1, e2)
    } else {
        (e2, e1)
    };
    let low_next = build(1, 1, Some(&low), &append_payload("c1", 8, "more\n"), 4_001);
    out.push(Scenario {
        name: "equivocation",
        notes: "N1 (restored from a backup) writes two entries at seq 2; the lower entry hash stays, the other is a divergent tail with equivocation = true.",
        entries: vec![
            ("g", g.clone()),
            ("a1", a1.clone()),
            ("high", high),
            ("low", low),
            ("low_next", low_next),
        ],
    });

    let too_far = build(
        2,
        2 + core_log::EPOCH_N_MAX_STEP,
        Some(&a1),
        &open_payload(Reason::Failover, epoch(1, 1)),
        5_000,
    );
    out.push(Scenario {
        name: "superseded-unknown",
        notes: "A claim whose n exceeds its ancestry by more than EPOCH_N_MAX_STEP is invalid; a higher n is known, so nobody holds the agent.",
        entries: vec![("g", g.clone()), ("a1", a1.clone()), ("too_far", too_far)],
    });

    let bad_move = build(
        2,
        2,
        Some(&a1),
        &open_payload(Reason::Move, epoch(1, 1)),
        6_000,
    );
    out.push(Scenario {
        name: "move-without-seal",
        notes: "A move whose parent is not a seal naming it is not claim-valid: stored, not canonical, superseded_unknown.",
        entries: vec![("g", g.clone()), ("a1", a1.clone()), ("bad_move", bad_move)],
    });

    let seal_nowhere = build(
        1,
        1,
        Some(&a1),
        &seal_payload(Some(epoch(2, 2)), a1.pos()),
        7_000,
    );
    let fo3 = build(
        3,
        2,
        Some(&seal_nowhere),
        &open_payload(Reason::Failover, epoch(1, 1)),
        7_100,
    );
    out.push(Scenario {
        name: "seal-target-never-opened",
        notes: "N1 seals to (2, N2), which never opens; N3 fails over from the seal at (2, N3).",
        entries: vec![
            ("g", g),
            ("a1", a1),
            ("seal", seal_nowhere),
            ("open_n3", fo3),
        ],
    });
    out
}
