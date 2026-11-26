//! The `log/` contract fixtures: generated here, checked in, and compared on every run (set
//! `BAKEN_REGEN_FIXTURES=1` to rewrite them after a deliberate spec change). Every vector is then
//! decoded and re-encoded independently of the generator.

#![allow(clippy::too_many_lines)]

mod common;

use std::collections::BTreeMap;

use common::scenarios::{self, Scenario};
use common::{
    Built, Replica, agent, append_payload, build, epoch, genesis_payload, key, log, node,
};
use core_log::hashes::{
    CTX_AGENT_LOG_ID, CTX_DIVERGENCE_ID, CTX_LOG_DIGEST, CTX_LOG_ENTRY, GitAlgo,
    c2_divergence_node_id, divergence_id, entry_hash, epoch_bytes, git_oid, log_id, payload_hash,
    sha256, uuid_text,
};
use core_log::payload::{
    Action, ActionState, Checkpoint, DivergencePayload, DivergentConversation, GitObject,
    ManifestEntry, Payload, RefEntry, kind,
};
use core_log::wire::{AgentHave, FrameReader, Hello, Message, Mode, SegSummary, WirePayload};
use core_log::{
    AgentLog, DivergenceProjection, EntryHeader, HeaderError, Incoming, Pos, Reason, SignedEntry,
    StructuralJudge, TouchedConversation,
};
use core_proto::bcbor::{self, Value};
use core_proto::crypto::derive_key;
use core_proto::{AgentId, ConversationId, Epoch, NodeId};
use core_store::{DivergenceId, EntryHash, MemoryStore, PlacementState, SegmentState, Store};
use core_testkit::{contract_path, decode_hex, encode_hex, load_value};
use serde_json::{Value as Json, json};

const VECTORS: &str = "log/log-v1-vectors.json";
const SCENARIOS: &str = "log/log-v1-scenarios.json";
const WIRE: &str = "log/log-v1-wire.json";
const GENERATOR: &str = "core/crates/core-log/tests/fixtures.rs (BAKEN_REGEN_FIXTURES=1 cargo test -p core-log --test fixtures)";

fn hex(bytes: &[u8]) -> String {
    encode_hex(bytes)
}

fn unhex(text: &str) -> Vec<u8> {
    decode_hex(text).expect("hex")
}

fn check_or_write(relative: &str, value: &Json) {
    let text = serde_json::to_string_pretty(value).expect("json") + "\n";
    let path = contract_path(relative);
    if std::env::var_os("BAKEN_REGEN_FIXTURES").is_some() {
        std::fs::create_dir_all(path.parent().expect("dir")).expect("mkdir");
        std::fs::write(&path, &text).expect("write fixture");
        return;
    }
    let on_disk = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("{relative}: {e}; regenerate with BAKEN_REGEN_FIXTURES=1"))
        .replace("\r\n", "\n");
    assert!(
        on_disk == text,
        "{relative} differs from the generator; regenerate with BAKEN_REGEN_FIXTURES=1 if the change is deliberate"
    );
}

fn epoch_json(epoch: &Epoch) -> Json {
    json!({ "n": epoch.n, "claimant": epoch.claimant.to_hex() })
}

fn header_json(header: &EntryHeader) -> Json {
    json!({
        "log_id": hex(header.log_id.as_bytes()),
        "epoch": epoch_json(&header.epoch),
        "seq": header.seq,
        "prev": hex(header.prev.as_bytes()),
        "kind": header.kind,
        "flags": header.flags,
        "ts_ms": header.ts_ms,
        "payload_len": header.payload_len,
        "payload_hash": hex(header.payload_hash.as_bytes()),
    })
}

fn entry_json(name: &str, built: &Built) -> Json {
    json!({
        "name": name,
        "header": header_json(built.entry.header()),
        "header_hex": hex(built.entry.header_bytes()),
        "entry_hash": hex(built.entry.hash().as_bytes()),
        "signature": hex(built.entry.signature().as_bytes()),
        "signed_hex": hex(&built.entry.to_bytes()),
        "payload_hex": hex(&built.payload),
    })
}

/// Signs an arbitrary header with key `k` (for negative vectors).
fn sign_raw(k: u8, header: EntryHeader, payload: &[u8]) -> Built {
    let mut header = header;
    header.payload_len = payload.len() as u64;
    header.payload_hash = payload_hash(payload);
    Built {
        entry: SignedEntry::sign(header, &key(k)).expect("signs"),
        payload: payload.to_vec(),
    }
}

fn failover_divergence_entry() -> (Vec<(&'static str, Built)>, Built) {
    let scenario = scenarios::all()
        .into_iter()
        .find(|s| s.name == "failover-partition")
        .expect("scenario");
    let mut replica = Replica::new();
    let mut last = None;
    for (_, built) in &scenario.entries {
        last = Some(replica.deliver(built));
    }
    let evaluation = last.expect("entries");
    let divergence = evaluation
        .divergences
        .first()
        .expect("one divergence")
        .clone();
    let projection = DivergenceProjection {
        conversations: vec![TouchedConversation {
            conversation_id: ConversationId::new("c1"),
            last_common_message_id: Some("m1".into()),
            owner: agent(),
        }],
        memfs_branches: Vec::new(),
        uncertain: Vec::new(),
        from_revoked: false,
    };
    let payload = Payload::Divergence(Box::new(
        divergence
            .inputs
            .to_payload(divergence.tail_head, &projection),
    ));
    let b1 = scenario
        .entries
        .iter()
        .find(|(n, _)| *n == "b1")
        .map(|(_, b)| b.clone())
        .expect("b1");
    let entry = build(2, 2, Some(&b1), &payload, 2_100);
    (scenario.entries, entry)
}

fn sample_payloads() -> Vec<(&'static str, Payload)> {
    let blob = b"hello\n".to_vec();
    let blob_oid = git_oid(GitAlgo::Sha1, "blob", &blob);
    vec![
        ("epoch_open_genesis", genesis_payload()),
        (
            "epoch_open_failover",
            Payload::EpochOpen(core_log::EpochOpen {
                agent_id: None,
                reason: Reason::Failover,
                prior_epoch: Some(epoch(1, 1)),
                votes: Vec::new(),
                store_format: 1,
                runtime_parity: Some("letta-code@0.0.0+lcp:00".into()),
                prior_head_seen: Some(Pos {
                    seq: 7,
                    hash: EntryHash::from_bytes([7; 32]),
                }),
                uncertain: vec![Action {
                    idempotency_key: "idem-1".into(),
                    tool_call_id: "call-1".into(),
                    conversation_id: ConversationId::new("c1"),
                    started_at: Pos {
                        seq: 6,
                        hash: EntryHash::from_bytes([6; 32]),
                    },
                }],
            }),
        ),
        (
            "epoch_seal",
            common::seal_payload(
                Some(epoch(2, 2)),
                Pos {
                    seq: 3,
                    hash: EntryHash::from_bytes([3; 32]),
                },
            ),
        ),
        (
            "epoch_seal_release",
            common::seal_payload(
                None,
                Pos {
                    seq: 3,
                    hash: EntryHash::from_bytes([3; 32]),
                },
            ),
        ),
        (
            "checkpoint",
            Payload::Checkpoint(Checkpoint {
                manifest: vec![
                    ManifestEntry {
                        path: "agents/YWdlbnQtYWxwaGE.json".into(),
                        sha256: sha256(b"{}"),
                        len: 2,
                    },
                    ManifestEntry {
                        path: "conversations/c1/messages.jsonl".into(),
                        sha256: sha256(b"m1\n"),
                        len: 3,
                    },
                ],
                refs: vec![RefEntry {
                    repo: "memfs".into(),
                    name: "refs/heads/main".into(),
                    oid: blob_oid.clone(),
                }],
                feed_heads: vec![(ConversationId::new("c1"), 12)],
                store_format: 1,
                runtime_parity: "letta-code@0.0.0+lcp:00".into(),
                h1_violations: Vec::new(),
            }),
        ),
        (
            "divergence",
            Payload::Divergence(Box::new(DivergencePayload {
                divergence_id: DivergenceId::from_bytes([9; 32]),
                losing_epoch: epoch(1, 1),
                fork: Pos {
                    seq: 1,
                    hash: EntryHash::from_bytes([1; 32]),
                },
                tail_head: Pos {
                    seq: 3,
                    hash: EntryHash::from_bytes([3; 32]),
                },
                conversations: vec![DivergentConversation {
                    conversation_id: ConversationId::new("c1"),
                    last_common_message_id: None,
                    branch_conversation_id: ConversationId::new(
                        "local-conv-00000000-0000-8000-8000-000000000000",
                    ),
                    catalog_node_id: [0; 16],
                    agent_id: agent(),
                }],
                memfs_branches: Vec::new(),
                uncertain: Vec::new(),
                equivocation: false,
                from_revoked: false,
                agent_id: agent(),
                fork_epoch: epoch(1, 1),
                winning_epoch: epoch(2, 2),
                competing: [
                    EntryHash::from_bytes([2; 32]),
                    EntryHash::from_bytes([4; 32]),
                ],
                stranded_holder: node(1),
            })),
        ),
        (
            "file_put",
            Payload::file_put(
                "agents/YWdlbnQtYWxwaGE.json",
                b"{\"id\":\"agent-alpha\"}".to_vec(),
                false,
            ),
        ),
        ("file_append", append_payload("c1", 3, "m2\n")),
        (
            "file_delete",
            Payload::FileDelete {
                path: "conversations/c1/blobs/old.png".into(),
            },
        ),
        (
            "git_objects",
            Payload::GitObjects {
                repo: "memfs".into(),
                algo: GitAlgo::Sha1,
                objects: vec![GitObject {
                    oid: blob_oid.clone(),
                    kind: 3,
                    content: blob,
                }],
            },
        ),
        (
            "git_ref",
            Payload::GitRef {
                repo: "memfs".into(),
                name: "refs/heads/main".into(),
                old_oid: None,
                new_oid: Some(blob_oid),
                symref: None,
            },
        ),
        (
            "git_ref_symbolic_head",
            Payload::GitRef {
                repo: "memfs".into(),
                name: "HEAD".into(),
                old_oid: None,
                new_oid: None,
                symref: Some("refs/heads/main".into()),
            },
        ),
        (
            "feed_append",
            common::feed_payload("c1", 12, "turn-1", "turn_closed"),
        ),
        (
            "action_status",
            Payload::ActionStatus {
                idempotency_key: "idem-1".into(),
                tool_call_id: "call-1".into(),
                conversation_id: ConversationId::new("c1"),
                state: ActionState::Finished,
            },
        ),
        (
            "unknown_ignorable_kind",
            Payload::Unknown {
                kind: 0x8001,
                bytes: bcbor::encode(&Value::Map(
                    [(0, Value::Text("future".into()))].into_iter().collect(),
                )),
            },
        ),
    ]
}

fn invalid_payloads() -> Vec<(&'static str, u16, Vec<u8>, &'static str)> {
    let text = |s: &str| Value::Text(s.into());
    let map = |pairs: Vec<(u64, Value)>| bcbor::encode(&Value::Map(pairs.into_iter().collect()));
    vec![
        (
            "file_put_sha256_mismatch",
            kind::FILE_PUT,
            map(vec![
                (0, text("agents/a.json")),
                (1, Value::Bytes(vec![0; 32])),
                (2, Value::Bytes(b"{}".to_vec())),
                (3, Value::Bool(false)),
            ]),
            "invalid",
        ),
        (
            "file_append_dot_dot_path",
            kind::FILE_APPEND,
            map(vec![
                (0, text("conversations/../x")),
                (1, Value::Uint(0)),
                (2, Value::Bytes(b"x".to_vec())),
            ]),
            "invalid",
        ),
        (
            "file_delete_unsorted_keys",
            kind::FILE_DELETE,
            unhex("a2016178006161"),
            "bcbor_unsorted_keys",
        ),
        (
            "git_object_id_mismatch",
            kind::GIT_OBJECTS,
            map(vec![
                (0, text("memfs")),
                (1, text("sha1")),
                (
                    2,
                    Value::Array(vec![Value::Array(vec![
                        Value::Bytes(vec![0; 20]),
                        Value::Uint(3),
                        Value::Bytes(b"hello\n".to_vec()),
                    ])]),
                ),
            ]),
            "invalid",
        ),
        (
            "epoch_open_unknown_reason",
            kind::EPOCH_OPEN,
            map(vec![(1, Value::Uint(9)), (4, Value::Uint(1))]),
            "invalid",
        ),
        (
            "epoch_open_missing_store_format",
            kind::EPOCH_OPEN,
            map(vec![(1, Value::Uint(2))]),
            "missing_key",
        ),
        (
            "action_status_wrong_type",
            kind::ACTION_STATUS,
            map(vec![
                (0, text("k")),
                (1, text("c")),
                (2, text("conv")),
                (3, text("finished")),
            ]),
            "wrong_type",
        ),
        (
            "file_append_negative_int",
            kind::FILE_APPEND,
            unhex("a3006161012002417a"),
            "bcbor_negative_int",
        ),
    ]
}

#[allow(clippy::too_many_lines)]
fn build_vectors() -> Json {
    let keys: Vec<Json> = (1..=3_u8)
        .map(|k| json!({ "name": format!("N{k}"), "seed": hex(&[k; 32]), "node_id": node(k).to_hex() }))
        .collect();
    let all = scenarios::all();
    let get = |scenario: &str, name: &str| -> Built {
        all.iter()
            .find(|s| s.name == scenario)
            .and_then(|s| s.entries.iter().find(|(n, _)| *n == name))
            .map(|(_, b)| b.clone())
            .expect("entry")
    };
    let g = get("genesis-and-appends", "g");
    let a1 = get("genesis-and-appends", "a1");
    let (_, divergence_entry) = failover_divergence_entry();
    let entries = vec![
        entry_json("genesis", &g),
        entry_json("file_append", &a1),
        entry_json("checkpoint", &get("clean-move", "cp")),
        entry_json("seal", &get("clean-move", "seal")),
        entry_json("epoch_open_move", &get("clean-move", "open_n2")),
        entry_json("epoch_open_failover", &get("failover-partition", "open_n2")),
        entry_json("divergence", &divergence_entry),
    ];

    let mut reserved = a1.entry.header_bytes().to_vec();
    reserved[119] |= 0x02;
    reserved.extend_from_slice(a1.entry.signature().as_bytes());
    let mut magic = a1.entry.to_bytes();
    magic[0] = b'X';
    let mut nonzero_prev = g.entry.header_bytes().to_vec();
    nonzero_prev[84] = 1;
    nonzero_prev.extend_from_slice(g.entry.signature().as_bytes());
    let mut bad_sig = a1.entry.to_bytes();
    bad_sig[200] ^= 1;
    let mut epoch_zero = g.entry.header_bytes().to_vec();
    epoch_zero[43] = 0;
    epoch_zero.extend_from_slice(g.entry.signature().as_bytes());
    let header_invalid = vec![
        json!({ "name": "reserved_flag_bit", "signed_hex": hex(&reserved), "error": HeaderError::ReservedFlags.code() }),
        json!({ "name": "bad_magic", "signed_hex": hex(&magic), "error": HeaderError::BadMagic.code() }),
        json!({ "name": "genesis_with_nonzero_prev", "signed_hex": hex(&nonzero_prev), "error": HeaderError::GenesisPrev.code() }),
        json!({ "name": "epoch_n_zero", "signed_hex": hex(&epoch_zero), "error": HeaderError::EpochOutOfRange.code() }),
        json!({ "name": "flipped_signature_bit", "signed_hex": hex(&bad_sig), "error": HeaderError::BadSignature.code() }),
        json!({ "name": "short", "signed_hex": hex(&a1.entry.to_bytes()[..231]), "error": HeaderError::WrongLength.code() }),
    ];

    let base = *a1.entry.header();
    let file = append_payload("c1", 3, "x\n").encode();
    let prev_mismatch = sign_raw(
        1,
        EntryHeader {
            seq: 2,
            prev: g.entry.hash(),
            ..base
        },
        &file,
    );
    let open_same_epoch = sign_raw(
        1,
        EntryHeader {
            seq: 2,
            prev: a1.entry.hash(),
            kind: kind::EPOCH_OPEN,
            flags: 0,
            ..base
        },
        &common::open_payload(Reason::Takeover, epoch(1, 1)).encode(),
    );
    let cross_epoch_plain = sign_raw(
        2,
        EntryHeader {
            epoch: epoch(2, 2),
            seq: 2,
            prev: a1.entry.hash(),
            ..base
        },
        &file,
    );
    let beta = Payload::EpochOpen(core_log::EpochOpen {
        agent_id: Some(AgentId::new("agent-beta")),
        ..match genesis_payload() {
            Payload::EpochOpen(open) => open,
            _ => unreachable!(),
        }
    })
    .encode();
    let wrong_log = sign_raw(1, *g.entry.header(), &beta);
    let chain_invalid = vec![
        json!({ "name": "prev_is_not_seq_minus_one", "setup": ["genesis", "file_append"], "signed_hex": hex(&prev_mismatch.entry.to_bytes()), "payload_hex": hex(&prev_mismatch.payload), "error": "chain_mismatch" }),
        json!({ "name": "epoch_open_with_prev_in_same_epoch", "setup": ["genesis", "file_append"], "signed_hex": hex(&open_same_epoch.entry.to_bytes()), "payload_hex": hex(&open_same_epoch.payload), "error": "chain_mismatch" }),
        json!({ "name": "plain_entry_crossing_epochs", "setup": ["genesis", "file_append"], "signed_hex": hex(&cross_epoch_plain.entry.to_bytes()), "payload_hex": hex(&cross_epoch_plain.payload), "error": "chain_mismatch" }),
        json!({ "name": "genesis_for_another_agent", "setup": [], "signed_hex": hex(&wrong_log.entry.to_bytes()), "payload_hex": hex(&wrong_log.payload), "error": "chain_mismatch" }),
    ];

    let payloads: Vec<Json> = sample_payloads()
        .iter()
        .map(|(name, p)| {
            let bytes = p.encode();
            json!({ "name": name, "kind": p.kind(), "hex": hex(&bytes), "blake3": hex(payload_hash(&bytes).as_bytes()) })
        })
        .collect();
    let bad_payloads: Vec<Json> = invalid_payloads()
        .iter()
        .map(|(name, kind, bytes, error)| json!({ "name": name, "kind": kind, "hex": hex(bytes), "error": error }))
        .collect();

    let input = b"baken";
    let losing = epoch(1, 1);
    let mut div_input = log().as_bytes().to_vec();
    div_input.extend_from_slice(&epoch_bytes(&losing));
    div_input.extend_from_slice(&1_u64.to_be_bytes());
    let contexts = vec![
        json!({ "name": "agent_log_id", "context": CTX_AGENT_LOG_ID, "input_utf8": "agent-alpha", "output": hex(log_id(&agent()).as_bytes()) }),
        json!({ "name": "log_entry", "context": CTX_LOG_ENTRY, "input_hex": hex(g.entry.header_bytes()), "output": hex(entry_hash(g.entry.header_bytes()).as_bytes()) }),
        json!({ "name": "divergence_id", "context": CTX_DIVERGENCE_ID, "input_hex": hex(&div_input), "output": hex(divergence_id(&log(), &losing, 1).as_bytes()) }),
        json!({ "name": "log_digest", "context": CTX_LOG_DIGEST, "input_hex": hex(input), "output": hex(&derive_key(CTX_LOG_DIGEST, input)) }),
        json!({ "name": "payload_blake3", "context": null, "input_hex": hex(input), "output": hex(payload_hash(input).as_bytes()) }),
        json!({ "name": "file_sha256", "context": null, "input_hex": hex(input), "output": hex(&sha256(input)) }),
        json!({
            "name": "c2_divergence_node_id",
            "context": null,
            "agent_id": AGENT_ID,
            "conversation_id": "c1",
            "stranded_epoch": epoch_json(&losing),
            "output": uuid_text(&c2_divergence_node_id(AGENT_ID, "c1", &losing)),
        }),
    ];

    let mut low = [0_u8; 32];
    low[31] = 1;
    let mut high = [0_u8; 32];
    high[31] = 2;
    let pair = |a: Epoch, b: Epoch| {
        json!({ "a": epoch_json(&a), "b": epoch_json(&b), "order": match a.cmp(&b) {
            std::cmp::Ordering::Less => "less",
            std::cmp::Ordering::Equal => "equal",
            std::cmp::Ordering::Greater => "greater",
        }})
    };
    let e = |n, c: [u8; 32]| Epoch {
        n,
        claimant: NodeId::from_bytes(c),
    };
    let order = vec![
        pair(e(1, [0xff; 32]), e(2, [0; 32])),
        pair(e(3, low), e(3, high)),
        pair(e(3, high), e(3, low)),
        pair(e(5, low), e(5, low)),
        pair(e(u64::from(u32::MAX), [0; 32]), e(1, [0xff; 32])),
    ];

    json!({
        "contract": "log/vectors",
        "version": 1,
        "generator": GENERATOR,
        "notes": "Byte vectors for log-v1.md: test keys (seed k repeated 32 times), hash contexts, signed entries, header and chain negative cases, payload BCBOR per kind and negative payloads, epoch order.",
        "keys": keys,
        "hash_contexts": contexts,
        "entries": entries,
        "invalid_headers": header_invalid,
        "invalid_chain": chain_invalid,
        "payloads": payloads,
        "invalid_payloads": bad_payloads,
        "epoch_order": order,
    })
}

const AGENT_ID: &str = common::AGENT;

fn state_name(state: SegmentState) -> &'static str {
    match state {
        SegmentState::Open => "open",
        SegmentState::Sealed => "sealed",
        SegmentState::CanonicalAncestor => "canonical_ancestor",
        SegmentState::Divergent => "divergent",
        SegmentState::Invalid => "invalid",
        SegmentState::Quarantined => "quarantined",
    }
}

fn placement_json(state: Option<PlacementState>) -> Json {
    match state {
        None => Json::Null,
        Some(PlacementState::Active) => json!({ "state": "active" }),
        Some(PlacementState::SealedTo(e)) => {
            json!({ "state": "sealed_to", "next": epoch_json(&e) })
        }
        Some(PlacementState::Placeless) => json!({ "state": "placeless" }),
        Some(PlacementState::SupersededUnknown) => json!({ "state": "superseded_unknown" }),
    }
}

/// What a replica concludes after receiving every entry of a scenario.
fn expectation(store: &MemoryStore) -> Json {
    let judge = StructuralJudge;
    let analysis = store
        .read(|txn| {
            AgentLog::new(log(), &judge)
                .analyze(txn)
                .map_err(common::to_store)
        })
        .expect("analyze");
    let placement = store.read(|txn| txn.placement(&log())).expect("placement");
    json!({
        "canonical": analysis.canonical.as_ref().map(epoch_json),
        "tip": analysis.line.last().map(|e| json!({ "seq": e.seq, "hash": hex(e.entry_hash.as_bytes()) })),
        "placement": placement_json(analysis.placement_state),
        "placement_row_epoch": placement.as_ref().map(|p| epoch_json(&p.epoch)),
        "segments": analysis.segments.iter().map(|s| json!({
            "epoch": epoch_json(&s.epoch),
            "start_seq": s.start_seq,
            "head_seq": s.head_seq,
            "state": state_name(s.state),
        })).collect::<Vec<_>>(),
        "divergences": analysis.divergences.iter().map(|d| {
            let i = &d.inputs;
            let c1 = ConversationId::new("c1");
            json!({
                "divergence_id": hex(i.id().as_bytes()),
                "inputs": {
                    "agent_id": i.agent_id.as_str(),
                    "fork_epoch": epoch_json(&i.fork_epoch),
                    "fork_seq": i.fork.seq,
                    "fork_hash": hex(i.fork.hash.as_bytes()),
                    "competing": [hex(i.competing[0].as_bytes()), hex(i.competing[1].as_bytes())],
                    "stranded_epoch": epoch_json(&i.stranded_epoch),
                    "winning_epoch": epoch_json(&i.winning_epoch),
                    "equivocation": i.equivocation,
                },
                "tail_head": { "seq": d.tail_head.seq, "hash": hex(d.tail_head.hash.as_bytes()) },
                "tail": d.tail.iter().map(|h| hex(h.as_bytes())).collect::<Vec<_>>(),
                "c2_catalog_node_id_for_c1": uuid_text(&i.catalog_node_id(&c1)),
                "branch_conversation_id_for_c1": i.branch_conversation_id(&c1).as_str(),
                "memfs_branch_for_main": i.memfs_branch("main"),
            })
        }).collect::<Vec<_>>(),
    })
}

fn build_scenarios() -> Json {
    let scenarios: Vec<Json> = scenarios::all()
        .iter()
        .map(|s: &Scenario| {
            let mut replica = Replica::new();
            for (_, built) in &s.entries {
                replica.deliver(built);
            }
            json!({
                "name": s.name,
                "notes": s.notes,
                "entries": s.entries.iter().map(|(name, b)| json!({
                    "name": name,
                    "signed_hex": hex(&b.entry.to_bytes()),
                    "payload_hex": hex(&b.payload),
                })).collect::<Vec<_>>(),
                "expect": expectation(&replica.store),
            })
        })
        .collect();
    json!({
        "contract": "log/scenarios",
        "version": 1,
        "generator": GENERATOR,
        "notes": "Chain and divergence scenarios (placement-eval subset that needs no membership: the judge applies only the structural reason rules). Deliver the entries in any order that respects parents; every replica must reach `expect`. Divergence inputs are the values C2's catalog node id is built from.",
        "scenarios": scenarios,
    })
}

fn sample_messages() -> Vec<(&'static str, Message)> {
    let all = scenarios::all();
    let g = all[0].entries[0].1.clone();
    let pos = Pos {
        seq: 0,
        hash: g.entry.hash(),
    };
    vec![
        (
            "hello",
            Message::Hello(Hello {
                version: 1,
                node_id: node(1),
                constellation_id: [0xc0; 32],
                member_heads: vec![[1; 32], [2; 32]],
                log_digest: [0xd1; 32],
                mode: Mode::Live,
                store_format: 1,
            }),
        ),
        (
            "have",
            Message::Have {
                agents: vec![AgentHave {
                    log_id: log(),
                    agent_id: Some(agent()),
                    hwm: Some(epoch(1, 1)),
                    max_n_seen: 1,
                    tip: Some(pos),
                    complete_seq: Some(0),
                    segments: vec![SegSummary {
                        epoch: epoch(1, 1),
                        start_seq: 0,
                        parent_hash: None,
                        head: pos,
                        state: 0,
                        entries: 1,
                        set_xor: *pos.hash.as_bytes(),
                    }],
                }],
                last: true,
            },
        ),
        (
            "want_from_start",
            Message::Want {
                log_id: log(),
                epoch: epoch(1, 1),
                after: None,
                live: true,
                payloads: true,
            },
        ),
        (
            "want_after",
            Message::Want {
                log_id: log(),
                epoch: epoch(1, 1),
                after: Some(pos),
                live: false,
                payloads: true,
            },
        ),
        (
            "entries",
            Message::Entries {
                log_id: log(),
                entries: vec![
                    (g.entry.to_bytes(), WirePayload::Inline(g.payload.clone())),
                    (g.entry.to_bytes(), WirePayload::ByHash),
                    (g.entry.to_bytes(), WirePayload::Pruned),
                ],
            },
        ),
        (
            "ack",
            Message::Ack {
                log_id: log(),
                epoch: epoch(1, 1),
                complete: pos,
            },
        ),
        (
            "head",
            Message::Head {
                log_id: log(),
                epoch: epoch(1, 1),
                pos,
            },
        ),
        (
            "ping",
            Message::Ping {
                nonce: 7,
                heard: vec![(node(2), 1_500)],
            },
        ),
        (
            "resync",
            Message::Resync {
                log_id: log(),
                reason: "backpressure".into(),
            },
        ),
        (
            "error",
            Message::Error {
                code: "chain_mismatch".into(),
                message: "prev is not the entry at seq - 1".into(),
                log_id: Some(log()),
            },
        ),
    ]
}

fn build_wire() -> Json {
    let messages: Vec<Json> = sample_messages()
        .iter()
        .map(|(name, m)| json!({ "name": name, "frame_hex": hex(&m.encode_frame()) }))
        .collect();
    let invalid = vec![
        json!({ "name": "unknown_type", "body_hex": hex(&bcbor::encode(&Value::Map([(0, Value::Uint(99))].into_iter().collect()))), "error": "unknown_type" }),
        json!({ "name": "hello_version_2", "body_hex": hex(&{
            let Message::Hello(h) = sample_messages()[0].1.clone() else { unreachable!() };
            Message::Hello(Hello { version: 2, ..h }).encode_body()
        }), "error": "invalid" }),
        json!({ "name": "unsorted_keys", "body_hex": "a2010700a0", "error": "bcbor_unsorted_keys" }),
        json!({ "name": "ack_missing_pos", "body_hex": hex(&bcbor::encode(&Value::Map([(0, Value::Uint(5)), (1, Value::Bytes(log().as_bytes().to_vec())), (2, core_log::value::epoch_value(&epoch(1, 1)))].into_iter().collect()))), "error": "missing_key" }),
    ];
    json!({
        "contract": "log/wire",
        "version": 1,
        "generator": GENERATOR,
        "notes": "baken/log/1 frames: u32 BE length then a BCBOR map, key 0 the type. One example of every message type, then invalid bodies with their error code, then a length prefix above MAX_FRAME.",
        "messages": messages,
        "invalid_bodies": invalid,
        "too_large_prefix_hex": "00100001",
    })
}

#[test]
fn fixtures_match_the_generator() {
    check_or_write(VECTORS, &build_vectors());
    check_or_write(SCENARIOS, &build_scenarios());
    check_or_write(WIRE, &build_wire());
}

// ----- independent decoding of the checked-in vectors -----

fn epoch_from(json: &Json) -> Epoch {
    Epoch {
        n: json["n"].as_u64().expect("n"),
        claimant: json["claimant"]
            .as_str()
            .expect("claimant")
            .parse()
            .expect("node id"),
    }
}

#[test]
fn keys_hashes_and_entries_decode() {
    let v = load_value(VECTORS).expect("vectors");
    for k in v["keys"].as_array().expect("keys") {
        let seed: [u8; 32] = unhex(k["seed"].as_str().expect("seed"))
            .try_into()
            .expect("32");
        assert_eq!(
            core_proto::crypto::NodeKey::from_seed(&seed)
                .node_id()
                .to_hex(),
            k["node_id"]
        );
    }
    for c in v["hash_contexts"].as_array().expect("contexts") {
        let name = c["name"].as_str().expect("name");
        let input = c["input_hex"]
            .as_str()
            .map(unhex)
            .or_else(|| c["input_utf8"].as_str().map(|s| s.as_bytes().to_vec()));
        let output = c["output"].as_str().expect("output");
        let computed = match (name, c["context"].as_str(), input) {
            ("payload_blake3", None, Some(i)) => hex(payload_hash(&i).as_bytes()),
            ("file_sha256", None, Some(i)) => hex(&sha256(&i)),
            ("c2_divergence_node_id", None, None) => uuid_text(&c2_divergence_node_id(
                c["agent_id"].as_str().expect("agent"),
                c["conversation_id"].as_str().expect("conversation"),
                &epoch_from(&c["stranded_epoch"]),
            )),
            (_, Some(context), Some(i)) => hex(&derive_key(context, &i)),
            other => panic!("unexpected context vector {other:?}"),
        };
        assert_eq!(computed, output, "{name}");
    }
    for e in v["entries"].as_array().expect("entries") {
        let name = e["name"].as_str().expect("name");
        let signed = SignedEntry::from_bytes(&unhex(e["signed_hex"].as_str().expect("signed")))
            .unwrap_or_else(|err| panic!("{name}: {err}"));
        let h = signed.header();
        assert_eq!(hex(signed.header_bytes()), e["header_hex"], "{name}");
        assert_eq!(hex(signed.hash().as_bytes()), e["entry_hash"], "{name}");
        assert_eq!(hex(signed.signature().as_bytes()), e["signature"], "{name}");
        assert_eq!(
            h.encode().to_vec(),
            unhex(e["header_hex"].as_str().expect("hex")),
            "{name}"
        );
        assert_eq!(epoch_from(&e["header"]["epoch"]), h.epoch, "{name}");
        assert_eq!(e["header"]["seq"], h.seq, "{name}");
        assert_eq!(e["header"]["kind"], h.kind, "{name}");
        let payload = unhex(e["payload_hex"].as_str().expect("payload"));
        assert_eq!(payload_hash(&payload), h.payload_hash, "{name}");
        let decoded =
            Payload::decode(h.kind, &payload).unwrap_or_else(|err| panic!("{name}: {err}"));
        assert_eq!(decoded.encode(), payload, "{name} re-encodes");
    }
    for e in v["invalid_headers"].as_array().expect("invalid") {
        let err = SignedEntry::from_bytes(&unhex(e["signed_hex"].as_str().expect("hex")))
            .expect_err(e["name"].as_str().expect("name"));
        assert_eq!(err.code(), e["error"], "{}", e["name"]);
    }
}

#[test]
fn chain_negative_cases_are_refused() {
    let v = load_value(VECTORS).expect("vectors");
    let by_name: BTreeMap<&str, &Json> = v["entries"]
        .as_array()
        .expect("entries")
        .iter()
        .map(|e| (e["name"].as_str().expect("name"), e))
        .collect();
    let judge = StructuralJudge;
    for case in v["invalid_chain"].as_array().expect("cases") {
        let store = MemoryStore::new();
        let log = AgentLog::new(log(), &judge);
        for name in case["setup"].as_array().expect("setup") {
            let e = by_name[name.as_str().expect("name")];
            let signed = SignedEntry::from_bytes(&unhex(e["signed_hex"].as_str().expect("hex")))
                .expect("valid");
            let payload = Incoming::Inline(unhex(e["payload_hex"].as_str().expect("hex")));
            store
                .write(|txn| {
                    log.receive(txn, &signed, &payload)
                        .map_err(common::to_store)
                })
                .expect("setup stores");
        }
        let signed = SignedEntry::from_bytes(&unhex(case["signed_hex"].as_str().expect("hex")))
            .expect("signature valid");
        let payload = Incoming::Inline(unhex(case["payload_hex"].as_str().expect("hex")));
        let err = store
            .write(|txn| Ok::<_, core_store::StoreError>(log.receive(txn, &signed, &payload)))
            .expect("transaction")
            .expect_err(case["name"].as_str().expect("name"));
        assert_eq!(err.wire_code(), case["error"], "{}", case["name"]);
    }
}

#[test]
fn payload_vectors_round_trip_and_negatives_fail() {
    let v = load_value(VECTORS).expect("vectors");
    for p in v["payloads"].as_array().expect("payloads") {
        let name = p["name"].as_str().expect("name");
        let kind = u16::try_from(p["kind"].as_u64().expect("kind")).expect("u16");
        let bytes = unhex(p["hex"].as_str().expect("hex"));
        let decoded = Payload::decode(kind, &bytes).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(decoded.kind(), kind, "{name}");
        assert_eq!(decoded.encode(), bytes, "{name} re-encodes");
        assert_eq!(hex(payload_hash(&bytes).as_bytes()), p["blake3"], "{name}");
    }
    for p in v["invalid_payloads"].as_array().expect("invalid") {
        let name = p["name"].as_str().expect("name");
        let kind = u16::try_from(p["kind"].as_u64().expect("kind")).expect("u16");
        let err = Payload::decode(kind, &unhex(p["hex"].as_str().expect("hex"))).expect_err(name);
        assert_eq!(err.code(), p["error"], "{name}");
    }
    for pair in v["epoch_order"].as_array().expect("order") {
        let (a, b) = (epoch_from(&pair["a"]), epoch_from(&pair["b"]));
        let expected = match pair["order"].as_str().expect("order") {
            "less" => std::cmp::Ordering::Less,
            "equal" => std::cmp::Ordering::Equal,
            _ => std::cmp::Ordering::Greater,
        };
        assert_eq!(a.cmp(&b), expected);
    }
}

#[test]
fn scenarios_replay_identically_in_any_order() {
    let v = load_value(SCENARIOS).expect("scenarios");
    for scenario in v["scenarios"].as_array().expect("scenarios") {
        let name = scenario["name"].as_str().expect("name");
        let entries: Vec<Built> = scenario["entries"]
            .as_array()
            .expect("entries")
            .iter()
            .map(|e| Built {
                entry: SignedEntry::from_bytes(&unhex(e["signed_hex"].as_str().expect("hex")))
                    .expect("valid"),
                payload: unhex(e["payload_hex"].as_str().expect("hex")),
            })
            .collect();
        let orders: Vec<Vec<Built>> = vec![
            entries.clone(),
            entries.iter().rev().cloned().collect(),
            entries
                .iter()
                .skip(1)
                .chain(entries.iter().take(1))
                .cloned()
                .collect(),
        ];
        for (index, order) in orders.iter().enumerate() {
            let mut replica = Replica::new();
            for built in order {
                replica.deliver(built);
            }
            assert_eq!(replica.pending_len(), 0, "{name} order {index}");
            assert_eq!(
                expectation(&replica.store),
                scenario["expect"],
                "{name} order {index}"
            );
        }
    }
}

#[test]
fn wire_vectors_round_trip() {
    let v = load_value(WIRE).expect("wire");
    for m in v["messages"].as_array().expect("messages") {
        let name = m["name"].as_str().expect("name");
        let frame = unhex(m["frame_hex"].as_str().expect("hex"));
        let mut reader = FrameReader::new();
        reader.push(&frame[..3]);
        assert_eq!(reader.next_body(), Ok(None), "{name} partial");
        reader.push(&frame[3..]);
        let body = reader.next_body().expect("frame").expect("complete");
        let message = Message::decode_body(&body).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(message.encode_frame(), frame, "{name} re-encodes");
    }
    for m in v["invalid_bodies"].as_array().expect("invalid") {
        let name = m["name"].as_str().expect("name");
        let err =
            Message::decode_body(&unhex(m["body_hex"].as_str().expect("hex"))).expect_err(name);
        assert_eq!(err.code(), m["error"], "{name}");
    }
    let mut reader = FrameReader::new();
    reader.push(&unhex(v["too_large_prefix_hex"].as_str().expect("hex")));
    assert_eq!(
        reader.next_body().expect_err("too large").code(),
        "too_large"
    );
}
