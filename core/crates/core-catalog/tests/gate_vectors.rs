//! catalog-v1 gate vectors (§6.2): byte-exact CBOR frames with the verdict the gate must reach.
//! Generated here (`BAKEN_REGEN_FIXTURES=1` rewrites `gate/vectors.json`), then every vector is
//! decoded from the file and run through [`DocGate`]. The Kotlin doc-id gate runs the vectors whose
//! `needsSyncDecode` is false.

mod common;

use std::collections::BTreeMap;

use automerge::sync::{ChunkList, Message as SyncMessage, MessageVersion};
use automerge::transaction::Transactable as _;
use bytes::BytesMut;
use ciborium::Value as Cbor;
use common::{check_or_write_json, doc, node, read_json};
use core_catalog::gate::{GateAccess, MAX_DENIALS, MAX_VIOLATIONS};
use core_catalog::wire::{MAX_FRAME, Message};
use core_catalog::{Access, DocClass, DocGate, DocUrl, Verdict};
use core_store::DocId;
use serde_json::{Value, json};
use tokio_util::codec::{Decoder as _, LengthDelimitedCodec};

const NOW: u64 = 1_790_000_000_000;

fn ours() -> String {
    node(1).to_hex()
}

fn them() -> String {
    node(2).to_hex()
}

fn url(byte: u8) -> String {
    DocUrl::new(DocId::from_bytes([byte; 16])).bs58()
}

fn agent_doc() -> String {
    url(0xa1)
}

fn notebook_doc() -> String {
    url(0xb2)
}

fn unregistered_doc() -> String {
    url(0xc3)
}

fn text(value: &str) -> Cbor {
    Cbor::Text(value.to_owned())
}

fn sync_data(changes: Vec<Vec<u8>>) -> Vec<u8> {
    SyncMessage {
        heads: Vec::new(),
        need: Vec::new(),
        have: Vec::new(),
        changes: ChunkList::from(changes),
        supported_capabilities: None,
        version: MessageVersion::V1,
    }
    .encode()
}

fn a_change() -> Vec<u8> {
    let mut d = doc(9);
    d.put(automerge::ROOT, "k", "v").expect("put");
    d.commit();
    d.get_changes(&[])[0].raw_bytes().to_vec()
}

fn doc_message(
    kind: &str,
    sender: &str,
    target: &str,
    document: &str,
    data: Option<Vec<u8>>,
) -> Vec<u8> {
    let mut entries = vec![
        ("type", text(kind)),
        ("documentId", text(document)),
        ("senderId", text(sender)),
        ("targetId", text(target)),
    ];
    if let Some(data) = data {
        entries.push(("data", Cbor::Bytes(data)));
    }
    Message::new(entries).encode()
}

fn sync(document: &str, changes: Vec<Vec<u8>>) -> Vec<u8> {
    doc_message("sync", &them(), &ours(), document, Some(sync_data(changes)))
}

fn standard_access() -> Value {
    json!({
        agent_doc(): { "class": "agent", "access": "read" },
        notebook_doc(): { "class": "notebook", "access": "read" },
    })
}

fn with_access(access: &str) -> Value {
    json!({
        agent_doc(): { "class": "agent", "access": access },
        notebook_doc(): { "class": "notebook", "access": "read" },
    })
}

/// Every vector: `(name, frame, access, needs sync decode)`.
#[allow(
    clippy::too_many_lines,
    reason = "one fixture table, read top to bottom"
)]
fn vectors() -> Vec<(&'static str, Vec<u8>, Value, bool)> {
    let join = |sender: &str| {
        Message::new(vec![
            ("type", text("join")),
            ("senderId", text(sender)),
            ("supportedProtocolVersions", Cbor::Array(vec![text("1")])),
        ])
        .encode()
    };
    let peer = |target: &str| {
        Message::new(vec![
            ("type", text("peer")),
            ("senderId", text(&them())),
            ("selectedProtocolVersion", text("1")),
            ("targetId", text(target)),
        ])
        .encode()
    };
    let simple = |kind: &str, document: Option<&str>| {
        let mut entries = vec![
            ("type", text(kind)),
            ("senderId", text(&them())),
            ("targetId", text(&ours())),
        ];
        if let Some(document) = document {
            entries.push(("documentId", text(document)));
        }
        Message::new(entries).encode()
    };
    let ephemeral = |document: &str| {
        Message::new(vec![
            ("type", text("ephemeral")),
            ("senderId", text(&them())),
            ("targetId", text(&ours())),
            ("count", Cbor::Integer(1.into())),
            ("sessionId", text("session-1")),
            ("documentId", text(document)),
            ("data", Cbor::Bytes(vec![1, 2, 3])),
        ])
        .encode()
    };
    let subscriptions = |add: Vec<String>| {
        Message::new(vec![
            ("type", text("remote-subscription-change")),
            ("senderId", text(&them())),
            ("targetId", text(&ours())),
            ("add", Cbor::Array(add.iter().map(|d| text(d)).collect())),
            ("remove", Cbor::Array(Vec::new())),
        ])
        .encode()
    };
    let heads = |document: &str| {
        Message::new(vec![
            ("type", text("remote-heads-changed")),
            ("senderId", text(&them())),
            ("targetId", text(&ours())),
            ("documentId", text(document)),
            ("newHeads", Cbor::Map(Vec::new())),
        ])
        .encode()
    };
    let mut bad_cbor = vec![0xa1, 0x64];
    bad_cbor.extend_from_slice(b"ty");
    vec![
        ("join", join(&them()), standard_access(), false),
        (
            "join_sender_mismatch",
            join(&ours()),
            standard_access(),
            false,
        ),
        ("peer", peer(&ours()), standard_access(), false),
        (
            "peer_target_mismatch",
            peer(&them()),
            standard_access(),
            false,
        ),
        (
            "request_write_without_changes",
            doc_message(
                "request",
                &them(),
                &ours(),
                &agent_doc(),
                Some(sync_data(Vec::new())),
            ),
            with_access("write"),
            true,
        ),
        (
            "sync_read_without_changes",
            sync(&agent_doc(), Vec::new()),
            standard_access(),
            true,
        ),
        (
            "sync_read_with_changes",
            sync(&agent_doc(), vec![a_change()]),
            standard_access(),
            true,
        ),
        (
            "sync_write_with_changes",
            sync(&agent_doc(), vec![a_change()]),
            with_access("write"),
            true,
        ),
        (
            "sync_write_malformed_change",
            sync(&agent_doc(), vec![b"not a change".to_vec()]),
            with_access("write"),
            true,
        ),
        (
            "sync_no_access",
            sync(&agent_doc(), Vec::new()),
            with_access("none"),
            false,
        ),
        (
            "sync_unregistered",
            sync(&unregistered_doc(), Vec::new()),
            standard_access(),
            false,
        ),
        (
            "sync_sender_mismatch",
            doc_message(
                "sync",
                &ours(),
                &ours(),
                &agent_doc(),
                Some(sync_data(Vec::new())),
            ),
            standard_access(),
            false,
        ),
        (
            "sync_target_mismatch",
            doc_message(
                "sync",
                &them(),
                &them(),
                &agent_doc(),
                Some(sync_data(Vec::new())),
            ),
            standard_access(),
            false,
        ),
        (
            "doc_unavailable_registered",
            simple("doc-unavailable", Some(&agent_doc())),
            standard_access(),
            false,
        ),
        (
            "doc_unavailable_unregistered",
            simple("doc-unavailable", Some(&unregistered_doc())),
            standard_access(),
            false,
        ),
        (
            "error",
            Message::new(vec![("type", text("error")), ("message", text("boom"))]).encode(),
            standard_access(),
            false,
        ),
        (
            "ephemeral_catalog",
            ephemeral(&agent_doc()),
            standard_access(),
            false,
        ),
        (
            "ephemeral_notebook",
            ephemeral(&notebook_doc()),
            standard_access(),
            false,
        ),
        (
            "remote_heads_readable",
            heads(&agent_doc()),
            standard_access(),
            false,
        ),
        (
            "remote_heads_unreadable",
            heads(&agent_doc()),
            with_access("none"),
            false,
        ),
        (
            "subscription_filtered",
            subscriptions(vec![agent_doc(), unregistered_doc()]),
            standard_access(),
            false,
        ),
        (
            "subscription_all_unreadable",
            subscriptions(vec![unregistered_doc()]),
            standard_access(),
            false,
        ),
        (
            "unknown_type",
            simple("future-thing", None),
            standard_access(),
            false,
        ),
        ("bad_cbor", bad_cbor, standard_access(), false),
        (
            "no_type",
            Message::new(vec![("senderId", text(&them()))]).encode(),
            standard_access(),
            false,
        ),
    ]
}

struct VectorAccess(BTreeMap<DocId, (DocClass, Access)>);

impl VectorAccess {
    fn from_json(value: &Value) -> Self {
        let mut map = BTreeMap::new();
        for (id, entry) in value.as_object().expect("an object") {
            let doc = DocUrl::from_bs58(id).expect("an id").doc_id();
            let class = DocClass::parse(entry["class"].as_str().expect("class")).expect("class");
            let access = Access::parse(entry["access"].as_str().expect("access")).expect("access");
            map.insert(doc, (class, access));
        }
        Self(map)
    }
}

impl GateAccess for VectorAccess {
    fn peer(&self, doc: &DocId) -> Option<(DocClass, Access)> {
        self.0.get(doc).copied()
    }

    fn local(&self, _doc: &DocId) -> Access {
        Access::Write
    }
}

fn expectation(gate: &mut DocGate, frame: &[u8], access: &VectorAccess) -> Value {
    let decision = gate.inbound(frame, access, NOW);
    let mut out = match &decision.verdict {
        Verdict::PassOriginal => json!({ "verdict": "pass_original" }),
        Verdict::PassRewritten(bytes) => {
            json!({ "verdict": "pass_rewritten", "rewrittenHex": hex::encode(bytes) })
        }
        Verdict::Drop => json!({ "verdict": "drop" }),
        Verdict::Reply(bytes) => json!({ "verdict": "reply", "replyHex": hex::encode(bytes) }),
        Verdict::Close(close) => {
            json!({ "verdict": "close", "closeCode": close.code, "closeReason": close.reason })
        }
    };
    if let Some(quarantine) = decision.quarantine {
        out["quarantine"] = json!(quarantine.reason.as_str());
    }
    out
}

#[test]
fn gate_vectors_match_the_generator_and_the_gate() {
    let generated: Vec<Value> = vectors()
        .into_iter()
        .map(|(name, frame, access, needs_sync_decode)| {
            let mut gate = DocGate::new(&node(1), &node(2));
            let expect = expectation(&mut gate, &frame, &VectorAccess::from_json(&access));
            json!({
                "case": name,
                "frameHex": hex::encode(&frame),
                "authenticatedId": them(),
                "ourId": ours(),
                "principalAccess": access,
                "needsSyncDecode": needs_sync_decode,
                "expect": expect,
            })
        })
        .collect();
    check_or_write_json(
        "gate/vectors.json",
        &json!({
            "generator": "core/crates/core-catalog/tests/gate_vectors.rs (BAKEN_REGEN_FIXTURES=1 cargo test -p core-catalog --test gate_vectors)",
            "vectors": generated,
        }),
    );

    // Run every vector from the file, as another binding would.
    let file = read_json("gate/vectors.json");
    for vector in file["vectors"].as_array().expect("vectors") {
        let frame = hex::decode(vector["frameHex"].as_str().expect("hex")).expect("hex");
        let principal = vector["authenticatedId"]
            .as_str()
            .expect("id")
            .parse()
            .expect("node id");
        let ours = vector["ourId"]
            .as_str()
            .expect("id")
            .parse()
            .expect("node id");
        let mut gate = DocGate::new(&ours, &principal);
        let access = VectorAccess::from_json(&vector["principalAccess"]);
        assert_eq!(
            expectation(&mut gate, &frame, &access),
            vector["expect"],
            "{}",
            vector["case"]
        );
    }
}

#[test]
fn every_verdict_and_closure_is_covered() {
    let file = read_json("gate/vectors.json");
    let text = file.to_string();
    for needed in [
        "pass_original",
        "pass_rewritten",
        "\"drop\"",
        "\"reply\"",
        "peer_id_mismatch",
        "malformed_frame",
        "unauthorized_change",
        "malformed_change",
    ] {
        assert!(text.contains(needed), "no vector covers {needed}");
    }
}

#[test]
fn a_length_prefix_over_4_mib_is_refused_before_allocation() {
    let mut codec = LengthDelimitedCodec::builder()
        .length_field_length(4)
        .max_frame_length(MAX_FRAME)
        .new_codec();
    let declared = u32::try_from(MAX_FRAME + 1).expect("fits");
    let mut buffer = BytesMut::from(&declared.to_be_bytes()[..]);
    let error = codec.decode(&mut buffer).expect_err("refused");
    assert_eq!(
        error.kind(),
        std::io::ErrorKind::InvalidData,
        "the driver maps this to 4413"
    );
}

#[test]
fn the_third_read_only_violation_closes_write_not_permitted() {
    let access = VectorAccess::from_json(&standard_access());
    let mut gate = DocGate::new(&node(1), &node(2));
    let frame = sync(&agent_doc(), vec![a_change()]);
    for round in 1..MAX_VIOLATIONS {
        let decision = gate.inbound(&frame, &access, NOW);
        assert!(
            matches!(decision.verdict, Verdict::PassRewritten(_)),
            "round {round}"
        );
    }
    let last = gate.inbound(&frame, &access, NOW);
    assert_eq!(
        last.verdict,
        Verdict::Close(core_catalog::policy::closures::WRITE_NOT_PERMITTED)
    );
    assert!(last.quarantine.is_some());
}

#[test]
fn more_than_32_denials_in_a_minute_close_too_many_denials() {
    let access = VectorAccess::from_json(&with_access("none"));
    let mut gate = DocGate::new(&node(1), &node(2));
    let frame = sync(&agent_doc(), Vec::new());
    for _ in 0..MAX_DENIALS {
        assert!(matches!(
            gate.inbound(&frame, &access, NOW).verdict,
            Verdict::Reply(_)
        ));
    }
    assert_eq!(
        gate.inbound(&frame, &access, NOW).verdict,
        Verdict::Close(core_catalog::policy::closures::TOO_MANY_DENIALS)
    );
    // A minute later the budget is back.
    let mut later = DocGate::new(&node(1), &node(2));
    for at in 0..40 {
        let verdict = later.inbound(&frame, &access, NOW + at * 2_000).verdict;
        assert!(matches!(verdict, Verdict::Reply(_)), "{at}");
    }
}

#[test]
fn an_oversized_change_from_a_writer_closes_and_is_quarantined() {
    let mut d = doc(9);
    d.put(automerge::ROOT, "big", "x".repeat(1024 * 1024 + 16))
        .expect("put");
    d.commit();
    let change = d.get_changes(&[])[0].raw_bytes().to_vec();
    let access = VectorAccess::from_json(&with_access("write"));
    let mut gate = DocGate::new(&node(1), &node(2));
    let decision = gate.inbound(&sync(&agent_doc(), vec![change]), &access, NOW);
    assert_eq!(
        decision.verdict,
        Verdict::Close(core_catalog::policy::closures::MALFORMED_FRAME)
    );
    assert_eq!(
        decision.quarantine.map(|q| q.reason),
        Some(core_catalog::QuarantineReason::OversizedChange)
    );
}

#[test]
fn outbound_messages_are_filtered_and_a_read_only_node_strips_its_own_changes() {
    struct ReadOnlySelf(VectorAccess);
    impl GateAccess for ReadOnlySelf {
        fn peer(&self, doc: &DocId) -> Option<(DocClass, Access)> {
            self.0.peer(doc)
        }
        fn local(&self, _doc: &DocId) -> Access {
            Access::Read
        }
    }
    let ours_to_them = |document: &str, changes| {
        doc_message("sync", &ours(), &them(), document, Some(sync_data(changes)))
    };
    let mut gate = DocGate::new(&node(1), &node(2));
    let access = VectorAccess::from_json(&with_access("none"));
    assert_eq!(
        gate.outbound(&ours_to_them(&agent_doc(), Vec::new()), &access),
        Verdict::Drop
    );
    assert_eq!(
        gate.outbound(&ours_to_them(&unregistered_doc(), Vec::new()), &access),
        Verdict::Drop
    );
    let reader = ReadOnlySelf(VectorAccess::from_json(&with_access("write")));
    let verdict = gate.outbound(&ours_to_them(&agent_doc(), vec![a_change()]), &reader);
    let Verdict::PassRewritten(bytes) = verdict else {
        panic!("expected a rewrite, got {verdict:?}");
    };
    let message = Message::decode(&bytes).expect("decodes");
    let data = SyncMessage::decode(message.data().expect("data")).expect("sync");
    assert!(data.changes.is_empty());
}
