//! catalog-v1 document fixtures (§6.1): every case is built here with fixed actors and commit time
//! 0, so the saved bytes are reproducible. The test checks the committed `docs/<case>.automerge`,
//! `.json` and `.meta.json` against the generator (run with `BAKEN_REGEN_FIXTURES=1` to rewrite
//! them), then loads each binary back and checks the canonical JSON, the typed view, the heads after
//! a re-save, and that no string in it is a host path or a secret.

mod common;

use automerge::AutoCommit;
use automerge::sync::SyncDoc as _;
use automerge::transaction::Transactable;
use common::{
    Constellation, actor, check_or_write_bytes, check_or_write_json, doc, fixture_doc_id, key, node,
};
use core_catalog::canonical::canonical_view;
use core_catalog::ids::{DocClass, DocUrl};
use core_catalog::model::{
    ActorRef, Archived, Detail, DirEntry, ForkPoint, MemberView, NewNode, NodeKind, Pin,
    PlacementCopy, SubagentStatus,
};
use core_catalog::view::{ViewContext, view};
use core_catalog::{rollover, secrets, write};
use core_log::header::{EntryHeader, SignedEntry};
use core_log::payload::kind;
use core_log::{DivergenceInputs, Pos};
use core_membership::{MembershipView, Preset, SignedOp};
use core_proto::crypto::Signature;
use core_proto::{AgentId, ConversationId, Epoch};
use core_store::{BlobHash, EntryHash};
use serde_json::{Value, json};

const AGENT: &str = "agent-a";
const NOW: i64 = 1_790_000_000_000;
const DAY: i64 = 24 * 60 * 60 * 1000;

struct Case {
    name: &'static str,
    class: DocClass,
    doc: AutoCommit,
    membership: Option<MembershipView>,
}

fn constellation_id() -> String {
    Constellation::new(&[], fixture_doc_id("genesis_constellation")).id_hex()
}

fn genesis_doc(class: DocClass, n: u8) -> AutoCommit {
    let mut d = doc(n);
    write::genesis(
        &mut d,
        class,
        &write::Genesis {
            constellation_id: constellation_id(),
            created_at_ms: 0,
            agent_id: (class == DocClass::Agent).then(|| AGENT.to_owned()),
            name: (class == DocClass::Constellation).then(|| "Home".to_owned()),
        },
    )
    .expect("genesis");
    d.commit();
    d
}

fn by(n: u8) -> ActorRef {
    ActorRef::node(node(n).to_hex())
}

fn new_node(id: &str, parent: Option<&str>, kind: NodeKind, detail: Detail) -> NewNode {
    NewNode {
        id: id.into(),
        agent_id: AGENT.into(),
        parent_id: parent.map(str::to_owned),
        kind,
        fork_point: parent.map(|_| ForkPoint {
            last_shared_message_id: Some("message-1".into()),
            log_epoch_n: Some(1),
            log_epoch_claimant: Some(node(1).to_hex()),
            log_seq: Some(4),
        }),
        created_by: by(1),
        created_at_ms: 1_000,
        title: Some(format!("Conversation {id}")),
        title_source: Some("auto".into()),
        last_activity_at_ms: None,
        deleted_at_ms: None,
        detail,
    }
}

fn inputs() -> DivergenceInputs {
    let epoch = |n: u64, k: u8| Epoch {
        n,
        claimant: node(k),
    };
    DivergenceInputs {
        log_id: core_log::hashes::log_id(&AgentId::new(AGENT)),
        agent_id: AgentId::new(AGENT),
        fork_epoch: epoch(1, 1),
        fork: Pos {
            seq: 4,
            hash: EntryHash::from_bytes([4; 32]),
        },
        competing: [
            EntryHash::from_bytes([5; 32]),
            EntryHash::from_bytes([6; 32]),
        ],
        stranded_epoch: epoch(2, 2),
        winning_epoch: epoch(2, 3),
        equivocation: false,
    }
}

fn placement_copy(n: u64, k: u8, forged: bool) -> PlacementCopy {
    let epoch = Epoch {
        n,
        claimant: node(k),
    };
    let header = EntryHeader {
        log_id: core_log::hashes::log_id(&AgentId::new(AGENT)),
        epoch,
        seq: n,
        prev: EntryHash::from_bytes([1; 32]),
        kind: kind::EPOCH_OPEN,
        flags: 0,
        ts_ms: 0,
        payload_len: 0,
        payload_hash: BlobHash::from_bytes([0; 32]),
    };
    let signed = SignedEntry::sign(header, &key(k)).expect("signs");
    PlacementCopy {
        holder: node(k).to_hex(),
        epoch_n: i64::try_from(n).expect("small"),
        epoch_claimant: node(k).to_hex(),
        state: "active".into(),
        prev_holder: None,
        record: signed.header_bytes().to_vec(),
        sig: if forged {
            vec![0; 64]
        } else {
            signed.signature().as_bytes().to_vec()
        },
        signer: node(k).to_hex(),
        copied_at_ms: 0,
        copied_by: node(k).to_hex(),
    }
}

fn agent_tree() -> AutoCommit {
    let mut d = genesis_doc(DocClass::Agent, 1);
    let nodes = [
        new_node("conv-root", None, NodeKind::Root, Detail::None {}),
        new_node(
            "conv-fork",
            Some("conv-root"),
            NodeKind::Fork,
            Detail::None {},
        ),
        new_node(
            "conv-edit",
            Some("conv-root"),
            NodeKind::Edit,
            Detail::Edit {
                replaced_message_id: "message-2".into(),
            },
        ),
        new_node(
            "conv-rerun",
            Some("conv-root"),
            NodeKind::Rerun,
            Detail::Rerun {
                rerun_message_id: "message-3".into(),
            },
        ),
        new_node(
            "conv-aside",
            Some("conv-root"),
            NodeKind::Aside,
            Detail::Aside {
                anchor_message_id: Some("message-2".into()),
                returned_at_ms: Some(2_000),
                summary_message_id: Some("message-9".into()),
            },
        ),
        NewNode {
            agent_id: "agent-sub-1".into(),
            ..new_node(
                "conv-sub",
                Some("conv-root"),
                NodeKind::Subagent,
                Detail::Subagent {
                    tool_call_id: "call-1".into(),
                    dispatch_message_id: Some("message-4".into()),
                    subagent_type: "explore".into(),
                    status: SubagentStatus::Running,
                    started_at_ms: 1_500,
                    ended_at_ms: None,
                },
            )
        },
    ];
    for n in &nodes {
        write::put_node(&mut d, n).expect("node");
    }
    write::put_subagent_link(&mut d, "agent-sub-1", "conv-root", 1_500).expect("link");
    write::end_subagent(&mut d, "conv-sub", SubagentStatus::Done, 3_000).expect("end");
    let divergence = write::divergence_node(
        &inputs(),
        &ConversationId::new("conv-root"),
        Some("message-4".into()),
        (5_000, &node(3).to_hex()),
    );
    write::put_node(&mut d, &divergence).expect("divergence");
    d.commit();
    d
}

fn merged(mut a: AutoCommit, mut b: AutoCommit) -> AutoCommit {
    a.merge(&mut b).expect("merge");
    a
}

#[allow(
    clippy::too_many_lines,
    reason = "one fixture table, read top to bottom"
)]
fn cases() -> Vec<Case> {
    let plain = |name, class, doc| Case {
        name,
        class,
        doc,
        membership: None,
    };
    let mut out = Vec::new();

    let mut constellation = genesis_doc(DocClass::Constellation, 1);
    write::set_shell_doc(
        &mut constellation,
        &DocUrl::new(fixture_doc_id("genesis_shell")),
    )
    .expect("shell");
    constellation.commit();
    out.push(plain(
        "genesis_constellation",
        DocClass::Constellation,
        constellation,
    ));
    out.push(plain(
        "genesis_shell",
        DocClass::Shell,
        genesis_doc(DocClass::Shell, 1),
    ));
    out.push(plain(
        "genesis_agent",
        DocClass::Agent,
        genesis_doc(DocClass::Agent, 1),
    ));
    out.push(plain("agent_tree", DocClass::Agent, agent_tree()));

    let mut memdiv = genesis_doc(DocClass::Agent, 1);
    write::put_memory_divergence(
        &mut memdiv,
        &write::memory_divergence(&inputs(), "main", (5_000, &node(3).to_hex())),
    )
    .expect("memdiv");
    memdiv.commit();
    out.push(plain("memory_divergence", DocClass::Agent, memdiv));

    for (name, forged) in [("placement_verified", false), ("placement_forged", true)] {
        let mut d = genesis_doc(DocClass::Agent, 1);
        write::put_placement(&mut d, &placement_copy(1, 1, forged)).expect("placement");
        d.commit();
        out.push(plain(name, DocClass::Agent, d));
    }

    // Two verified copies written concurrently; actor 2 writes the lower epoch and wins in
    // Automerge, and the view still picks the higher epoch.
    let mut base = genesis_doc(DocClass::Agent, 1);
    let mut other = base.fork().with_actor(actor(2));
    write::put_placement(&mut base, &placement_copy(2, 2, false)).expect("placement");
    write::put_placement(&mut other, &placement_copy(1, 1, false)).expect("placement");
    out.push(plain(
        "placement_conflict",
        DocClass::Agent,
        merged(base, other),
    ));

    let mut shell = genesis_doc(DocClass::Shell, 1);
    let pin = |pos: &str| Pin {
        pos: pos.into(),
        pinned_at_ms: 0,
        by: by(1),
    };
    write::set_pin(&mut shell, AGENT, &pin("V")).expect("pin");
    write::archive(
        &mut shell,
        "conv-x",
        &Archived {
            agent_id: AGENT.into(),
            at_ms: 0,
            by: by(1),
        },
    )
    .expect("archive");
    shell.commit();
    let mut second = shell.fork().with_actor(actor(2));
    write::set_entry(&mut shell, "shell.layout", "{\"lanes\":1}", &by(1), 10).expect("entry");
    write::set_entry(&mut second, "shell.layout", "{\"lanes\":2}", &by(2), 11).expect("entry");
    write::remove_pin(&mut shell, AGENT).expect("unpin");
    write::set_pin(&mut second, AGENT, &pin("W")).expect("repin");
    write::unarchive(&mut shell, "conv-x").expect("unarchive");
    write::archive(
        &mut second,
        "conv-x",
        &Archived {
            agent_id: AGENT.into(),
            at_ms: 5,
            by: by(2),
        },
    )
    .expect("archive");
    out.push(plain("shell_races", DocClass::Shell, merged(shell, second)));

    let mut pins = genesis_doc(DocClass::Shell, 1);
    for index in 0..205 {
        write::set_pin(
            &mut pins,
            &format!("agent-{index:03}"),
            &pin(&format!("a{index:03}")),
        )
        .expect("pin");
    }
    pins.commit();
    out.push(plain("shell_pin_cap", DocClass::Shell, pins));

    let mut dir = genesis_doc(DocClass::Constellation, 1);
    write::put_dir_entry(
        &mut dir,
        &DirEntry {
            agent_id: AGENT.into(),
            kind: "standard".into(),
            name: "Agent A".into(),
            description: None,
            avatar_token: None,
            doc_url: None,
            tree_agent_id: None,
            created_at_ms: 0,
            created_by: by(1),
            deleted_at_ms: None,
        },
    )
    .expect("dir");
    dir.commit();
    let mut dir2 = dir.fork().with_actor(actor(2));
    let low = DocUrl::new(core_store::DocId::from_bytes([0x10; 16]));
    let high = DocUrl::new(core_store::DocId::from_bytes([0xf0; 16]));
    write::set_dir_field(&mut dir, AGENT, "docUrl", &json!(low.to_string())).expect("url");
    write::set_dir_field(&mut dir2, AGENT, "docUrl", &json!(high.to_string())).expect("url");
    out.push(plain(
        "constellation_docurl_conflict",
        DocClass::Constellation,
        merged(dir, dir2),
    ));

    let members = Constellation::new(
        &[(2, Preset::DesktopNode)],
        fixture_doc_id("genesis_constellation"),
    );
    let mut roster = genesis_doc(DocClass::Constellation, 1);
    for op in &members.ops {
        write::put_membership_op(&mut roster, op).expect("op");
    }
    // A forged op: a valid body under a signature that does not verify.
    let forged_body = members.ops[1].body().to_vec();
    let ops_obj = write::container(&roster, "membershipOps").expect("ops");
    let forged_key = hex::encode(core_membership::OpId::of_body(&forged_body).as_bytes());
    let entry = roster
        .put_object(&ops_obj, format!("{forged_key}00"), automerge::ObjType::Map)
        .expect("forged");
    roster.put(&entry, "body", forged_body).expect("body");
    roster.put(&entry, "sig", vec![0_u8; 64]).expect("sig");
    roster
        .put(&entry, "issuer", node(1).to_hex())
        .expect("issuer");
    write::put_member_view(
        &mut roster,
        &node(2).to_hex(),
        &MemberView {
            name: "node-2".into(),
            caps: vec!["ui".into()],
            state: "active".into(),
            added_by: node(1).to_hex(),
            added_at_ms: 0,
            revoked_at_ms: None,
            derived_from_head: "stale".into(),
        },
    )
    .expect("member");
    roster.commit();
    out.push(Case {
        name: "constellation_members",
        class: DocClass::Constellation,
        doc: roster,
        membership: Some(members.view.clone()),
    });

    let mut future = agent_tree();
    future.put(automerge::ROOT, "v", 2).expect("v");
    future
        .put(automerge::ROOT, "futureKey", "kept")
        .expect("future");
    let conversations = write::container(&future, "conversations").expect("conversations");
    let fork = write::child_map(&future, &conversations, "conv-fork").expect("fork");
    future.put(&fork, "futureField", 7).expect("field");
    future.put(&fork, "kind", "branch").expect("kind");
    future.commit();
    out.push(plain("forward_compat", DocClass::Agent, future));

    let mut major = genesis_doc(DocClass::Agent, 1);
    major
        .put(automerge::ROOT, "schema", "baken.catalog.agent/2")
        .expect("schema");
    major.commit();
    out.push(plain("unknown_major", DocClass::Agent, major));

    let mut union_a = genesis_doc(DocClass::Agent, 1);
    let mut union_b = union_a.fork().with_actor(actor(2));
    for (d, id) in [(&mut union_a, "conv-a"), (&mut union_b, "conv-b")] {
        d.put_object(automerge::ROOT, "conversations", automerge::ObjType::Map)
            .expect("container");
        write::put_node(d, &new_node(id, None, NodeKind::Root, Detail::None {})).expect("node");
    }
    out.push(plain(
        "container_union",
        DocClass::Agent,
        merged(union_a, union_b),
    ));

    let mut broken = agent_tree();
    broken
        .delete(automerge::ROOT, "conversations")
        .expect("delete");
    broken.commit();
    let mut repaired = broken.fork().with_actor(actor(2));
    out.push(plain("container_deleted", DocClass::Agent, broken));
    let fixed = rollover::repair(DocClass::Agent, &mut repaired).expect("repair");
    assert_eq!(fixed, vec!["conversations".to_owned()]);
    repaired.commit();
    out.push(plain("container_repaired", DocClass::Agent, repaired));

    let mut cyclic = genesis_doc(DocClass::Agent, 1);
    for (id, parent) in [
        ("conv-a", "conv-b"),
        ("conv-b", "conv-a"),
        ("conv-c", "conv-gone"),
    ] {
        write::put_node(
            &mut cyclic,
            &new_node(id, Some(parent), NodeKind::Fork, Detail::None {}),
        )
        .expect("node");
    }
    cyclic.commit();
    out.push(plain("cycle_orphan", DocClass::Agent, cyclic));

    let mut old = genesis_doc(DocClass::Agent, 1);
    write::put_node(
        &mut old,
        &new_node("conv-root", None, NodeKind::Root, Detail::None {}),
    )
    .expect("root");
    for (id, deleted) in [
        ("conv-1", NOW - DAY),
        ("conv-2", NOW - 2 * DAY),
        ("conv-3", NOW - 200 * DAY),
    ] {
        let mut n = new_node(id, Some("conv-root"), NodeKind::Fork, Detail::None {});
        n.deleted_at_ms = Some(deleted);
        write::put_node(&mut old, &n).expect("node");
    }
    write::tombstone(&mut old, "conv-ancient", NOW - 400 * DAY).expect("tombstone");
    old.commit();
    let root = canonical_view(&old);
    assert_eq!(
        rollover::trigger(DocClass::Agent, old.save().len(), &root),
        Some(rollover::Trigger::Deleted)
    );
    let mut new = rollover::successor(DocClass::Agent, &old, actor(3), NOW).expect("successor");
    let rolled_at = old.get_heads();
    rollover::mark_superseded(&mut old, &DocUrl::new(fixture_doc_id("rollover_new")))
        .expect("superseded");
    // A late change still arrives at the old document after the rollover.
    let mut late = old.fork().with_actor(actor(2));
    write::put_node(
        &mut late,
        &new_node(
            "conv-late",
            Some("conv-root"),
            NodeKind::Fork,
            Detail::None {},
        ),
    )
    .expect_err("writers refuse a superseded document");
    let conversations = write::container(&late, "conversations").expect("conversations");
    let child = late
        .put_object(&conversations, "conv-late", automerge::ObjType::Map)
        .expect("late");
    late.put(&child, "id", "conv-late").expect("id");
    late.put(&child, "agentId", AGENT).expect("agent");
    late.put(&child, "parentId", "conv-root").expect("parent");
    late.put(&child, "kind", "fork").expect("kind");
    late.put(&child, "createdAtMs", 9_000).expect("created");
    old.merge(&mut late).expect("merge");
    old.commit();
    assert_eq!(
        rollover::sweep(DocClass::Agent, &old, &rolled_at, &mut new).expect("sweep"),
        1
    );
    assert_eq!(
        rollover::sweep(DocClass::Agent, &old, &rolled_at, &mut new).expect("sweep"),
        0
    );
    new.commit();
    out.push(plain("rollover_old", DocClass::Agent, old));
    out.push(plain("rollover_new", DocClass::Agent, new));

    out
}

fn heads_hex(doc: &mut AutoCommit) -> Vec<String> {
    let mut heads: Vec<String> = doc.get_heads().iter().map(ToString::to_string).collect();
    heads.sort();
    heads
}

#[test]
fn document_fixtures_match_the_generator_and_load_back() {
    let mut index = Vec::new();
    for mut case in cases() {
        let ctx = ViewContext {
            membership: case.membership.as_ref(),
            placement_hwm: None,
        };
        let bytes = case.doc.save();
        let canonical = canonical_view(&case.doc);
        let typed = view(case.class, &canonical, &ctx);
        let meta = json!({
            "class": case.class.as_str(),
            "documentId": DocUrl::new(fixture_doc_id(case.name)).bs58(),
            "heads": heads_hex(&mut case.doc),
            "schemaMajor": canonical.get("schema").and_then(Value::as_str),
            "v": canonical.get("v"),
            "context": { "membershipFromDocOps": case.membership.is_some() },
            "expectedView": typed,
            "expectedViolations": typed.violations(),
        });
        check_or_write_bytes(&format!("docs/{}.automerge", case.name), &bytes);
        check_or_write_json(&format!("docs/{}.json", case.name), &canonical);
        check_or_write_json(&format!("docs/{}.meta.json", case.name), &meta);

        // Load the binary back: same canonical JSON, same view, and a re-save keeps the heads.
        let mut loaded = AutoCommit::load(&bytes).expect("loads");
        assert_eq!(canonical_view(&loaded), canonical, "{}", case.name);
        let mut resaved = AutoCommit::load(&loaded.save()).expect("reloads");
        assert_eq!(
            heads_hex(&mut resaved),
            heads_hex(&mut loaded),
            "{}",
            case.name
        );
        assert_eq!(
            serde_json::to_value(view(case.class, &canonical, &ctx)).expect("json"),
            meta["expectedView"],
            "{}",
            case.name
        );

        // M3 acceptance: no host path and no secret anywhere in a catalog document.
        let findings = secrets::scan(&canonical);
        assert!(findings.is_empty(), "{}: {findings:?}", case.name);
        index.push(json!({ "case": case.name, "class": case.class.as_str() }));
    }
    check_or_write_json(
        "docs/index.json",
        &json!({
            "generator": "core/crates/core-catalog/tests/doc_fixtures.rs (BAKEN_REGEN_FIXTURES=1 cargo test -p core-catalog --test doc_fixtures)",
            "cases": index,
        }),
    );
}

#[test]
fn the_views_apply_the_conflict_rules() {
    let cases = cases();
    let get = |name: &str| {
        let case = cases.iter().find(|c| c.name == name).expect("case");
        let ctx = ViewContext {
            membership: case.membership.as_ref(),
            placement_hwm: None,
        };
        serde_json::to_value(view(case.class, &canonical_view(&case.doc), &ctx)).expect("json")
    };
    let tree = get("agent_tree");
    let nodes = &tree["conversations"];
    assert_eq!(nodes.as_object().expect("nodes").len(), 7);
    assert_eq!(nodes["conv-sub"]["status"], "done");
    let divergence_id =
        core_log::hashes::uuid_text(&inputs().catalog_node_id(&ConversationId::new("conv-root")));
    assert_eq!(nodes[&divergence_id]["kind"], "divergence");
    assert_eq!(nodes[&divergence_id]["resolution"]["state"], "open");

    let memdiv = get("memory_divergence");
    let record = memdiv["memoryDivergences"]
        .as_object()
        .expect("records")
        .values()
        .next()
        .expect("one");
    assert_eq!(
        record["memfsBranch"],
        format!("divergence/2-{}/main", &node(2).to_hex()[..8])
    );

    assert_eq!(get("placement_verified")["placement"]["verified"], true);
    assert_eq!(get("placement_forged")["placement"]["verified"], false);
    let conflict = get("placement_conflict");
    assert_eq!(
        conflict["placement"]["epochN"], 2,
        "the higher verified epoch wins"
    );

    let races = get("shell_races");
    assert_eq!(races["docRev"], 2, "counters sum across writers");
    assert_eq!(races["pins"][0]["agentId"], AGENT, "pin beats unpin");
    assert!(
        races["archived"].get("conv-x").is_some(),
        "archive beats unarchive"
    );
    assert_eq!(
        get("shell_pin_cap")["pins"].as_array().expect("pins").len(),
        200
    );

    let dir = get("constellation_docurl_conflict");
    assert_eq!(
        dir["agents"][AGENT]["docUrl"],
        DocUrl::new(core_store::DocId::from_bytes([0x10; 16])).to_string()
    );
    let roster = get("constellation_members");
    assert!(roster["violations"].to_string().contains("unverified_op"));
    assert!(
        roster["violations"]
            .to_string()
            .contains("stale_member_row")
    );
    assert_eq!(roster["membershipOps"].as_object().expect("ops").len(), 2);

    let future = get("forward_compat");
    assert_eq!(future["header"]["writable"], false);
    assert_eq!(future["conversations"]["conv-fork"]["kind"], "unknown");
    assert_eq!(get("unknown_major")["status"], "unavailable");

    let union = get("container_union");
    assert_eq!(union["conversations"].as_object().expect("nodes").len(), 2);
    assert!(
        get("container_deleted")["violations"]
            .to_string()
            .contains("missing_container")
    );
    assert_eq!(
        get("container_repaired")["conversations"]
            .as_object()
            .expect("nodes")
            .len(),
        7
    );

    let cyclic = get("cycle_orphan");
    assert!(cyclic["conversations"]["conv-a"]["parentId"].is_null());
    assert_eq!(cyclic["conversations"]["conv-c"]["orphan"], true);

    let old = get("rollover_old");
    assert!(old["header"]["supersededBy"].is_string());
    let new = get("rollover_new");
    let conversations = new["conversations"].as_object().expect("nodes");
    assert!(
        conversations.contains_key("conv-late"),
        "the sweep copied the late node"
    );
    assert!(
        !conversations.contains_key("conv-1"),
        "deleted leaves fold into tombstones"
    );
    let deleted = new["deleted"].as_object().expect("deleted");
    assert!(deleted.contains_key("conv-1") && deleted.contains_key("conv-2"));
    assert!(!deleted.contains_key("conv-3") && !deleted.contains_key("conv-ancient"));
}

#[test]
fn signed_copies_verify_with_the_c1_rules() {
    let copy = placement_copy(1, 1, false);
    let sig: [u8; 64] = copy.sig.clone().try_into().expect("64");
    assert!(SignedEntry::from_parts(&copy.record, &sig).is_ok());
    let members = Constellation::new(&[], fixture_doc_id("genesis_constellation"));
    let op = &members.ops[0];
    let reparsed = SignedOp::from_parts(
        op.body().to_vec(),
        Signature::from_bytes(*op.signature().as_bytes()),
    );
    assert!(reparsed.is_ok());
}

/// Slow in a debug build; run with `cargo test --release -p core-catalog --test doc_fixtures -- --ignored`.
#[test]
#[ignore = "a size benchmark; run in release"]
fn a_2000_node_agent_document_stays_under_the_rollover_threshold() {
    let mut d = genesis_doc(DocClass::Agent, 1);
    write::put_node(
        &mut d,
        &new_node("conv-0", None, NodeKind::Root, Detail::None {}),
    )
    .expect("root");
    for index in 1..2000 {
        let id = format!("conv-{index}");
        write::put_node(
            &mut d,
            &new_node(&id, Some("conv-0"), NodeKind::Fork, Detail::None {}),
        )
        .expect("node");
        if index % 100 == 0 {
            d.commit();
        }
    }
    d.commit();
    let saved = d.save().len();
    let mut state = automerge::sync::State::new();
    let mut fresh = automerge::sync::State::new();
    let mut empty = AutoCommit::new();
    // The first sync: an empty peer asks, the full peer answers with its history.
    let request = empty
        .sync()
        .generate_sync_message(&mut fresh)
        .expect("a request");
    d.sync()
        .receive_sync_message(&mut state, request)
        .expect("receive");
    let reply = d.sync().generate_sync_message(&mut state).expect("a reply");
    let first_sync = reply.encode().len();
    eprintln!("2000 nodes: saved {saved} bytes, first sync {first_sync} bytes");
    assert!(saved < rollover::ROLLOVER_BYTES, "{saved}");
    assert!(first_sync < core_catalog::wire::MAX_FRAME, "{first_sync}");
}
