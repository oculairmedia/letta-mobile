//! `placement/placement-v1-vectors.json`: epoch order, feed epochs, votes and certificates, and one
//! framed example of every `baken/place/1` message with decode errors.
//!
//! `vectors_are_current` regenerates the file's content from the code and fails when it differs;
//! run with `BAKEN_REGEN_FIXTURES=1` to rewrite it. `vectors_verify` reads the checked-in file
//! independently: every vector decodes, re-encodes to its bytes, and gives its expected result.

mod common;

use common::{key, log, node};
use core_log::header::SIGNED_LEN;
use core_log::{Pos, Reason};
use core_membership::policy::Quorum;
use core_placement::PlaceMessage;
use core_placement::feed::{cursor_line, feed_epoch};
use core_placement::fenced::FencedReport;
use core_placement::vote::{ClaimVote, SignedVote, check_certificate};
use core_proto::bcbor::{self, Value};
use core_proto::{Epoch, NodeId};
use core_store::EntryHash;
use core_testkit::{contract_path, decode_hex, encode_hex, load_value};
use serde_json::{Value as Json, json};

const FILE: &str = "placement/placement-v1-vectors.json";

fn synthetic(last: u8, first: u8) -> NodeId {
    let mut bytes = [0x5a; 32];
    bytes[0] = first;
    bytes[31] = last;
    NodeId::from_bytes(bytes)
}

fn epoch_j(e: &Epoch) -> Json {
    json!([e.n, e.claimant.to_hex()])
}

fn parse_epoch(j: &Json) -> Epoch {
    let bytes = decode_hex(j[1].as_str().expect("claimant")).expect("hex");
    Epoch {
        n: j[0].as_u64().expect("n"),
        claimant: NodeId::from_bytes(bytes.try_into().expect("32 bytes")),
    }
}

fn pos(seq: u64, fill: u8) -> Pos {
    Pos {
        seq,
        hash: EntryHash::from_bytes([fill; 32]),
    }
}

fn quorum() -> Quorum {
    let mut voters = vec![node(3), node(6), node(7)];
    voters.sort();
    Quorum {
        voters,
        required: 2,
    }
}

fn vote(voter: u8, epoch: Epoch, parent: Pos) -> SignedVote {
    SignedVote::sign(
        ClaimVote {
            log_id: log(),
            epoch,
            parent,
            voter: node(voter),
            ts_ms: 1_764_000_000_000,
        },
        &key(voter),
    )
    .expect("sign")
}

fn messages() -> Vec<(&'static str, PlaceMessage)> {
    let e1 = Epoch {
        n: 1,
        claimant: node(1),
    };
    let e2 = Epoch {
        n: 2,
        claimant: node(2),
    };
    vec![
        (
            "move_request",
            PlaceMessage::MoveRequest {
                log_id: log(),
                target: node(2),
            },
        ),
        (
            "prepare",
            PlaceMessage::Prepare {
                log_id: log(),
                from: e1,
                proposed: e2,
                store_format: 1,
                runtime_parity: common::PARITY.into(),
            },
        ),
        (
            "prepared",
            PlaceMessage::Prepared {
                complete: pos(41, 0x41),
            },
        ),
        (
            "refused",
            PlaceMessage::Refused {
                reason: "missing_profiles".into(),
            },
        ),
        (
            "verified",
            PlaceMessage::Verified {
                checkpoint: pos(42, 0x42),
            },
        ),
        (
            "verify_failed",
            PlaceMessage::VerifyFailed {
                diffs: vec![
                    (
                        "memory/system/persona.md".into(),
                        [0xaa; 32],
                        Some([0xbb; 32]),
                    ),
                    ("agent.json".into(), [0xcc; 32], None),
                ],
                refs_mismatch: true,
            },
        ),
        (
            "commit",
            PlaceMessage::Commit {
                seal: vec![0x77; SIGNED_LEN],
            },
        ),
        (
            "abort",
            PlaceMessage::Abort {
                reason: "quiesce_timeout".into(),
            },
        ),
        (
            "claim_request",
            PlaceMessage::ClaimRequest {
                log_id: log(),
                epoch: e2,
                parent: pos(42, 0x42),
                reason: Reason::Failover,
            },
        ),
        (
            "claim_vote",
            PlaceMessage::ClaimVote {
                vote: vote(3, e2, pos(42, 0x42)),
            },
        ),
        (
            "claim_refused",
            PlaceMessage::ClaimRefused {
                reason: "holder_alive".into(),
            },
        ),
        (
            "fenced",
            PlaceMessage::Fenced(FencedReport {
                log_id: log(),
                epoch: e1,
                head: pos(44, 0x44),
            }),
        ),
    ]
}

fn frame(map: Vec<(u64, Value)>) -> String {
    let body = bcbor::encode(&Value::Map(map.into_iter().collect()));
    let mut out = (u32::try_from(body.len()).expect("len"))
        .to_be_bytes()
        .to_vec();
    out.extend(body);
    encode_hex(&out)
}

// One literal fixture document; splitting it would scatter the schema.
#[allow(clippy::too_many_lines)]
fn generate() -> Json {
    let a = synthetic(0x01, 0x10);
    let b = synthetic(0x02, 0x10);
    let c = synthetic(0x01, 0x20);
    let order = [
        (
            Epoch { n: 1, claimant: b },
            Epoch { n: 2, claimant: a },
            "lt",
        ),
        (
            Epoch { n: 7, claimant: a },
            Epoch { n: 7, claimant: b },
            "lt",
        ),
        (
            Epoch { n: 7, claimant: c },
            Epoch { n: 7, claimant: b },
            "gt",
        ),
        (
            Epoch { n: 7, claimant: a },
            Epoch { n: 7, claimant: a },
            "eq",
        ),
        (
            Epoch {
                n: u64::from(u32::MAX),
                claimant: a,
            },
            Epoch {
                n: 1 << 20,
                claimant: c,
            },
            "gt",
        ),
    ];
    let epoch_order: Vec<Json> = order
        .iter()
        .map(|(x, y, o)| json!({"a": epoch_j(x), "b": epoch_j(y), "order": o}))
        .collect();
    let feed: Vec<Json> = [
        Epoch {
            n: 1,
            claimant: node(1),
        },
        Epoch {
            n: 2,
            claimant: node(2),
        },
        Epoch {
            n: 2,
            claimant: node(3),
        },
        Epoch {
            n: u64::from(u32::MAX),
            claimant: node(1),
        },
    ]
    .iter()
    .map(|e| json!({"epoch": epoch_j(e), "feed_epoch": feed_epoch(e)}))
    .collect();
    let e2 = Epoch {
        n: 2,
        claimant: node(2),
    };
    let parent = pos(42, 0x42);
    let v3 = vote(3, e2, parent);
    let v6 = vote(6, e2, parent);
    let v7 = vote(7, e2, parent);
    let wrong = vote(6, e2, pos(41, 0x41));
    let non_voter = vote(4, e2, parent);
    let mut bad_sig = v6.encode();
    let last = bad_sig.len() - 1;
    bad_sig[last] ^= 1;
    let env = |v: &SignedVote| encode_hex(&v.encode());
    let certificates = json!([
        {"name": "two_of_three", "votes": [env(&v3), env(&v6)], "expect": "valid"},
        {"name": "all_three", "votes": [env(&v3), env(&v6), env(&v7)], "expect": "valid"},
        {"name": "one_vote", "votes": [env(&v3)], "expect": "too_few"},
        {"name": "wrong_parent", "votes": [env(&v3), env(&wrong)], "expect": "wrong_claim"},
        {"name": "duplicate_voter", "votes": [env(&v3), env(&v3)], "expect": "duplicate_voter"},
        {"name": "non_voter", "votes": [env(&v3), env(&non_voter)], "expect": "not_voter"},
        {"name": "bad_signature", "votes": [env(&v3), encode_hex(&bad_sig)], "expect": "bad_signature"},
    ]);
    let wire: Vec<Json> = messages()
        .into_iter()
        .map(|(name, m)| {
            json!({"name": name, "type": m.type_number(), "hex": encode_hex(&m.encode_frame())})
        })
        .collect();
    let log_bytes = || Value::Bytes(log().as_bytes().to_vec());
    let errors = json!([
        {"name": "unknown_type", "hex": frame(vec![(0, Value::Uint(13))]), "code": "unknown_type"},
        {"name": "missing_field", "hex": frame(vec![(0, Value::Uint(1)), (1, log_bytes())]), "code": "bad_field"},
        {"name": "reserved_extra_key", "hex": frame(vec![(0, Value::Uint(4)), (1, Value::Text("x".into())), (2, Value::Uint(0))]), "code": "bad_field"},
        {"name": "short_seal", "hex": frame(vec![(0, Value::Uint(7)), (1, Value::Bytes(vec![0; 64]))]), "code": "bad_field"},
        {"name": "claim_request_move_reason", "hex": frame(vec![
            (0, Value::Uint(9)), (1, log_bytes()),
            (2, Value::Array(vec![Value::Uint(2), Value::Bytes(node(2).as_bytes().to_vec())])),
            (3, Value::Map([(0, Value::Uint(1)), (1, Value::Bytes(vec![1; 32]))].into_iter().collect())),
            (4, Value::Uint(1)),
        ]), "code": "bad_field"},
        {"name": "length_mismatch", "hex": "0000000a01", "code": "bad_length"},
        {"name": "non_canonical_body", "hex": "00000004a1001801", "code": "non_shortest"},
    ]);
    json!({
        "version": 1,
        "description": "Placement byte vectors: epoch order, feed epochs, claim votes and certificates, baken/place/1 frames. See placement-v1.md.",
        "generator": "core-placement tests/vectors.rs (BAKEN_REGEN_FIXTURES=1)",
        "keys": "Nk is the Ed25519 key whose RFC 8032 seed is the byte k repeated 32 times",
        "log_id": encode_hex(log().as_bytes()),
        "epoch_order": epoch_order,
        "feed_epoch": feed,
        "cursor_line": {"entry_hash": encode_hex(&[0xab; 32]), "line": cursor_line(&EntryHash::from_bytes([0xab; 32]))},
        "vote": {
            "voter": "N3",
            "epoch": epoch_j(&e2),
            "parent": {"seq": parent.seq, "hash": encode_hex(parent.hash.as_bytes())},
            "ts_ms": 1_764_000_000_000_u64,
            "body": encode_hex(&v3.vote().body()),
            "vote_id": encode_hex(&v3.vote().vote_id()),
            "envelope": env(&v3),
        },
        "certificate_policy": {"voters": ["N3", "N6", "N7"], "required": 2, "epoch": epoch_j(&e2), "parent": {"seq": 42, "hash": encode_hex(&[0x42; 32])}},
        "certificates": certificates,
        "wire": wire,
        "wire_errors": errors,
    })
}

#[test]
fn vectors_are_current() {
    let generated = generate();
    let path = contract_path(FILE);
    if std::env::var_os("BAKEN_REGEN_FIXTURES").is_some() {
        let text = serde_json::to_string_pretty(&generated).expect("json") + "\n";
        std::fs::write(&path, text).expect("write fixture");
    }
    let stored = load_value(FILE).expect("fixture loads");
    assert_eq!(
        stored, generated,
        "fixture is stale; regenerate with BAKEN_REGEN_FIXTURES=1"
    );
}

#[test]
fn vectors_verify() {
    let f = load_value(FILE).expect("fixture loads");
    for case in f["epoch_order"].as_array().expect("order") {
        let (a, b) = (parse_epoch(&case["a"]), parse_epoch(&case["b"]));
        let expected = match case["order"].as_str().expect("order") {
            "lt" => std::cmp::Ordering::Less,
            "gt" => std::cmp::Ordering::Greater,
            _ => std::cmp::Ordering::Equal,
        };
        assert_eq!(a.cmp(&b), expected, "{case}");
        assert_eq!(b.cmp(&a), expected.reverse(), "{case}");
    }
    for case in f["feed_epoch"].as_array().expect("feed") {
        let value = feed_epoch(&parse_epoch(&case["epoch"]));
        assert_eq!(value, case["feed_epoch"].as_u64().expect("u64"));
        assert!(value < 1 << 53);
    }
    let v = &f["vote"];
    let envelope = decode_hex(v["envelope"].as_str().expect("envelope")).expect("hex");
    let signed = SignedVote::decode(&envelope).expect("vote verifies");
    assert_eq!(encode_hex(&signed.vote().body()), v["body"]);
    assert_eq!(encode_hex(&signed.vote().vote_id()), v["vote_id"]);
    assert_eq!(signed.encode(), envelope);
    assert_eq!(signed.vote().voter, node(3));
    let policy = &f["certificate_policy"];
    let epoch = parse_epoch(&policy["epoch"]);
    let parent = Pos {
        seq: policy["parent"]["seq"].as_u64().expect("seq"),
        hash: EntryHash::from_bytes(
            decode_hex(policy["parent"]["hash"].as_str().expect("hash"))
                .expect("hex")
                .try_into()
                .expect("32"),
        ),
    };
    for case in f["certificates"].as_array().expect("certificates") {
        let votes: Vec<Value> = case["votes"]
            .as_array()
            .expect("votes")
            .iter()
            .map(|h| {
                bcbor::decode(&decode_hex(h.as_str().expect("hex")).expect("hex")).expect("bcbor")
            })
            .collect();
        let result = check_certificate(&votes, &log(), &epoch, &parent, &quorum());
        let code = result.map_or_else(|e| e.code(), |()| "valid");
        assert_eq!(
            code,
            case["expect"].as_str().expect("expect"),
            "{}",
            case["name"]
        );
    }
    for case in f["wire"].as_array().expect("wire") {
        let bytes = decode_hex(case["hex"].as_str().expect("hex")).expect("hex");
        let message = PlaceMessage::decode_frame(&bytes).expect("decodes");
        assert_eq!(message.type_number(), case["type"].as_u64().expect("type"));
        assert_eq!(message.encode_frame(), bytes, "{}", case["name"]);
    }
    for case in f["wire_errors"].as_array().expect("errors") {
        let bytes = decode_hex(case["hex"].as_str().expect("hex")).expect("hex");
        let error = PlaceMessage::decode_frame(&bytes).expect_err("must not decode");
        assert_eq!(
            error.code(),
            case["code"].as_str().expect("code"),
            "{}",
            case["name"]
        );
    }
}
