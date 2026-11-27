#![allow(
    clippy::similar_names,
    reason = "exported and exporter, materializer and materialized, read best side by side"
)]

//! The projection against the fixture letta-code store: export, materialize, verify, the H1
//! exclusions, transcript appends, memfs commits, the materializer's refusals and the
//! deny-scanner.

use std::path::Path;

use core_log::fold::{FoldError, StateFold};
use core_log::payload::{Payload, kind};
use core_projection::layout::{self, ConversationKey};
use core_projection::lint::SecretScanner;
use core_projection::materialize::disk_manifest;
use core_projection::testing::{CommitSpec, FixtureStore, append_file, commit, expand, write_file};
use core_projection::{Gitoxide, Materializer, NodeGitConfig, ProjectionError, StoreExporter};
use core_testkit::contract_path;
use serde_json::{Value, json};

const REGEN: &str = "BAKEN_REGEN_FIXTURES";

fn fixture() -> (Value, FixtureStore) {
    let text = std::fs::read_to_string(contract_path("projection/projection-v1-store.json"))
        .expect("fixture");
    let value: Value = serde_json::from_str(&text).expect("json");
    let store = FixtureStore::from_json(&value);
    (value, store)
}

fn exporter(root: &Path, agent: &str) -> StoreExporter<Gitoxide> {
    StoreExporter::new(root, Gitoxide, agent, SecretScanner::default())
}

fn paths_of(payloads: &[Payload]) -> Vec<String> {
    payloads
        .iter()
        .filter_map(|p| match p {
            Payload::FilePut { path, .. }
            | Payload::FileAppend { path, .. }
            | Payload::FileDelete { path } => Some(path.clone()),
            _ => None,
        })
        .collect()
}

fn hooks_dir(base: &Path) -> NodeGitConfig {
    NodeGitConfig {
        hooks_path: Some(base.join("node-hooks")),
    }
}

#[test]
fn the_fixture_store_exports_materializes_and_verifies() {
    let (value, store) = fixture();
    let temp = tempfile::tempdir().expect("tempdir");
    let live = temp.path().join("live");
    store.write(&Gitoxide, &live).expect("write store");

    let mut exporter = exporter(&live, &store.agent);
    let batch = exporter.scan().expect("scan");
    assert!(batch.blocked.is_empty());
    let exported = paths_of(&batch.payloads);
    for never in value["never_exported"].as_array().expect("list") {
        let path = expand(never.as_str().expect("path"), &store.agent);
        assert!(!exported.contains(&path), "{path} must stay node-local");
    }
    assert!(
        exported.iter().all(|p| !p.starts_with("memfs/")),
        "memfs travels as git objects and refs only"
    );
    let objects: usize = batch
        .payloads
        .iter()
        .map(|p| match p {
            Payload::GitObjects { objects, .. } => objects.len(),
            _ => 0,
        })
        .sum();
    // Three commits; trees and blobs shared between commits are sent once.
    assert_eq!(objects, 3 + 5 + 4, "commits, trees and blobs");
    let checkpoint = exporter.checkpoint(Vec::new(), "letta-code@0.34.70+lcp:test");
    assert!(
        checkpoint.h1_violations.is_empty(),
        "{:?}",
        checkpoint.h1_violations
    );
    // A second scan with nothing changed exports nothing.
    assert!(exporter.scan().expect("rescan").is_empty());

    let staging = temp.path().join("staging");
    let materializer = Materializer::new(Gitoxide, hooks_dir(temp.path()));
    let materialized = materializer
        .materialize(&batch.payloads, &staging)
        .expect("materialize");
    assert_eq!(materialized.verify(&checkpoint), Ok(()));
    assert!(materialized.alarms.is_empty());

    // Every replicated file is byte-identical; node-local files are absent from staging.
    for entry in &materialized.manifest {
        let a = std::fs::read(layout::to_native(&live, &entry.path)).expect("live");
        let b = std::fs::read(layout::to_native(&staging, &entry.path)).expect("staged");
        assert_eq!(a, b, "{}", entry.path);
    }
    for never in value["never_exported"].as_array().expect("list") {
        let path = expand(never.as_str().expect("path"), &store.agent);
        assert!(!layout::to_native(&staging, &path).exists(), "{path}");
    }
    // The memfs working tree is checked out from refs; hooks were not copied; config is ours.
    let repo = layout::to_native(&staging, &store.repo);
    assert_eq!(
        std::fs::read_to_string(repo.join("x0-codeword.md")).expect("checkout"),
        "The codeword is HERON.\n"
    );
    assert!(repo.join("notes").join("conversations.md").is_file());
    assert!(!repo.join(".git").join("hooks").join("pre-commit").exists());
    assert!(
        !repo
            .join(".git")
            .join("letta-memory-layout-policy")
            .exists()
    );
    let config = std::fs::read_to_string(repo.join(".git").join("config")).expect("config");
    assert!(config.contains("autocrlf = false"), "{config}");
    assert!(config.contains("hooksPath"), "{config}");

    check_expected(&json!({
        "manifest": checkpoint.manifest.iter().map(|e| json!([e.path, hex::encode(e.sha256), e.len])).collect::<Vec<_>>(),
        "refs": checkpoint.refs.iter().map(|r| json!([r.repo, r.name, hex::encode(&r.oid)])).collect::<Vec<_>>(),
    }));
}

/// The expected manifest and refs of the fixture store (regenerate with `BAKEN_REGEN_FIXTURES=1`).
fn check_expected(actual: &Value) {
    let path = contract_path("projection/projection-v1-expected.json");
    let text = serde_json::to_string_pretty(actual).expect("json") + "\n";
    if std::env::var_os(REGEN).is_some() {
        std::fs::write(&path, &text).expect("write fixture");
        return;
    }
    let on_disk = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("{e}; regenerate with {REGEN}=1"))
        .replace("\r\n", "\n");
    assert_eq!(on_disk, text, "regenerate with {REGEN}=1 if deliberate");
}

#[test]
fn transcript_appends_are_offset_checked_and_torn_lines_wait() {
    let temp = tempfile::tempdir().expect("tempdir");
    let root = temp.path();
    let agent = "agent-a";
    let dir = ConversationKey::Default {
        agent: agent.into(),
    }
    .dir_name();
    let transcript = format!("conversations/{dir}/messages.jsonl");
    write_file(root, &transcript, b"{\"type\":\"session\"}\n").expect("write");
    let mut exporter = exporter(root, agent);
    let first = exporter.scan().expect("scan").payloads;
    assert!(matches!(&first[..], [Payload::FilePut { .. }]));

    // A complete line and a torn one: only the complete line is exported.
    append_file(root, &transcript, b"{\"a\":1}\n{\"b\":").expect("append");
    let second = exporter.scan().expect("scan").payloads;
    assert_eq!(
        second,
        vec![Payload::FileAppend {
            path: transcript.clone(),
            offset: 19,
            bytes: b"{\"a\":1}\n".to_vec(),
        }]
    );
    append_file(root, &transcript, b"2}\n").expect("append");
    let third = exporter.scan().expect("scan").payloads;
    assert!(
        matches!(&third[..], [Payload::FileAppend { offset: 27, bytes, .. }] if bytes == b"{\"b\":2}\n")
    );

    // A rewrite of an earlier line is a whole-file put.
    write_file(root, &transcript, b"{\"type\":\"session\",\"v\":2}\n").expect("rewrite");
    let fourth = exporter.scan().expect("scan").payloads;
    assert!(matches!(&fourth[..], [Payload::FilePut { .. }]));

    // A deleted file is a delete.
    std::fs::remove_file(layout::to_native(root, &transcript)).expect("remove");
    let fifth = exporter.scan().expect("scan").payloads;
    assert_eq!(fifth, vec![Payload::FileDelete { path: transcript }]);
}

#[test]
fn a_memfs_commit_exports_only_new_objects_then_the_ref() {
    let (_, store) = fixture();
    let temp = tempfile::tempdir().expect("tempdir");
    let live = temp.path().join("live");
    store.write(&Gitoxide, &live).expect("write");
    let mut exporter = exporter(&live, &store.agent);
    let first = exporter.scan().expect("scan").payloads;
    let mut files = store.commits.last().expect("commit").files.clone();
    files.insert(
        "x0-codeword.md".into(),
        "The codeword is HERON.\nAnd EGRET.\n".into(),
    );
    let chain = commit(
        &Gitoxide,
        &layout::to_native(&live, &store.repo),
        &store.agent,
        &[CommitSpec {
            message: "second codeword".into(),
            files,
            time: 1_764_101_000,
        }],
    )
    .expect("commit");
    let second = exporter.scan().expect("scan").payloads;
    match &second[..] {
        [
            Payload::GitObjects { objects, .. },
            Payload::GitRef {
                name,
                old_oid: Some(_),
                new_oid: Some(new),
                ..
            },
        ] => {
            // The new blob, the new root tree, the new commit; unchanged subtrees are known.
            assert_eq!(objects.len(), 3);
            assert_eq!(name, "refs/heads/main");
            assert_eq!(Some(new), chain.commits.last());
        }
        other => panic!("unexpected batch {other:?}"),
    }
    // The whole line still materializes and matches a fresh checkpoint.
    let staging = temp.path().join("staging");
    let line: Vec<Payload> = first.into_iter().chain(second).collect();
    let materialized = Materializer::new(Gitoxide, hooks_dir(temp.path()))
        .materialize(&line, &staging)
        .expect("materialize");
    assert_eq!(
        materialized.verify(&exporter.checkpoint(Vec::new(), "p")),
        Ok(())
    );
}

#[test]
fn the_materializer_refuses_a_wrong_offset_and_skips_paths_off_the_allowlist() {
    let temp = tempfile::tempdir().expect("tempdir");
    let path = "conversations/YQ/messages.jsonl".to_owned();
    let bad = vec![
        Payload::file_put(&path, b"one\n".to_vec(), false),
        Payload::FileAppend {
            path: path.clone(),
            offset: 3,
            bytes: b"two\n".to_vec(),
        },
    ];
    let materializer = Materializer::new(Gitoxide, NodeGitConfig::default());
    assert_eq!(
        materializer.materialize(&bad, &temp.path().join("a")),
        Err(ProjectionError::Fold(FoldError::OffsetMismatch {
            path,
            offset: 3,
            len: 4
        }))
    );
    let alarmed = vec![
        Payload::file_put("settings.json", b"{}".to_vec(), false),
        Payload::file_put("agents/YQ.json", b"{}".to_vec(), false),
    ];
    let staging = temp.path().join("b");
    let materialized = materializer
        .materialize(&alarmed, &staging)
        .expect("materialize");
    assert_eq!(materialized.alarms, vec!["settings.json".to_owned()]);
    assert!(!staging.join("settings.json").exists());
    assert_eq!(materialized.manifest.len(), 1);
    let unknown = vec![Payload::Unknown {
        kind: 0x0500,
        bytes: vec![0xa0],
    }];
    assert_eq!(
        materializer.materialize(&unknown, &temp.path().join("c")),
        Err(ProjectionError::StoreFormatUnsupported(0x0500))
    );
    let ignorable = vec![Payload::Unknown {
        kind: kind::IGNORABLE_FROM,
        bytes: vec![0xa0],
    }];
    assert!(
        materializer
            .materialize(&ignorable, &temp.path().join("d"))
            .is_ok()
    );
}

#[test]
fn a_secret_blocks_its_export_and_an_h1_violation_reaches_the_checkpoint() {
    let temp = tempfile::tempdir().expect("tempdir");
    let root = temp.path();
    let agent = "agent-a";
    write_file(
        root,
        &layout::agent_record_path(agent),
        br#"{"model":"anthropic/claude-sonnet","description":"created in /tmp/x0"}"#,
    )
    .expect("write");
    let dir = ConversationKey::Default {
        agent: agent.into(),
    }
    .dir_name();
    write_file(
        root,
        &format!("conversations/{dir}/messages.jsonl"),
        b"{\"text\":\"my key is sk-ant-api03-abcdefghijklmnopqrstuvwxyz\"}\n",
    )
    .expect("write");
    let mut exporter = StoreExporter::new(
        root,
        Gitoxide,
        agent,
        SecretScanner::new(["node-binding-secret-value".to_owned()]),
    );
    let batch = exporter.scan().expect("scan");
    assert_eq!(batch.payloads.len(), 1, "the record is exported");
    assert_eq!(batch.blocked.len(), 1, "the transcript is blocked");
    assert_eq!(batch.blocked[0].rule, "secret_pattern");
    let checkpoint = exporter.checkpoint(Vec::new(), "p");
    let record = layout::agent_record_path(agent);
    assert_eq!(
        checkpoint.h1_violations,
        vec![
            format!("I2:{record}:model"),
            format!("I5:{record}:description")
        ]
    );
    // Still blocked on the next scan (it alarms again, nothing is appended).
    assert_eq!(exporter.scan().expect("scan").blocked.len(), 1);
}

#[test]
fn disk_manifest_lists_only_replicated_files() {
    let (_, store) = fixture();
    let temp = tempfile::tempdir().expect("tempdir");
    store.write(&Gitoxide, temp.path()).expect("write");
    let manifest = disk_manifest(temp.path(), |_| true).expect("manifest");
    let paths: Vec<&str> = manifest.iter().map(|e| e.path.as_str()).collect();
    assert!(paths.iter().all(|p| !p.ends_with("system-prompt.json")));
    assert!(paths.iter().all(|p| !p.starts_with("memfs/")));
    assert!(paths.contains(&layout::agent_record_path(&store.agent).as_str()));
}

mod transcript_property {
    use proptest::prelude::*;

    use super::*;

    #[derive(Clone, Debug)]
    enum Step {
        Append(String),
        Torn(String),
        Rewrite(String),
    }

    fn step() -> impl Strategy<Value = Step> {
        prop_oneof![
            4 => "[a-z]{0,12}".prop_map(Step::Append),
            2 => "[a-z]{1,6}".prop_map(Step::Torn),
            1 => "[a-z]{0,12}".prop_map(Step::Rewrite),
        ]
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(48))]

        /// Whatever letta-code does to a transcript, folding the exported payloads yields the file
        /// up to its last newline.
        #[test]
        fn the_fold_of_exports_is_the_complete_prefix(steps in proptest::collection::vec(step(), 1..12)) {
            let temp = tempfile::tempdir().expect("tempdir");
            let root = temp.path();
            let path = "conversations/ZGVmYXVsdDphZ2VudC1h/messages.jsonl";
            write_file(root, path, b"").expect("write");
            let mut exporter = exporter(root, "agent-a");
            let mut fold = StateFold::new();
            let mut disk: Vec<u8> = Vec::new();
            for step in steps {
                match step {
                    Step::Append(text) => disk.extend_from_slice(format!("{text}\n").as_bytes()),
                    Step::Torn(text) => disk.extend_from_slice(text.as_bytes()),
                    Step::Rewrite(text) => disk = format!("{text}!\n").into_bytes(),
                }
                write_file(root, path, &disk).expect("write");
                for payload in exporter.scan().expect("scan").payloads {
                    fold.apply(&payload).expect("apply");
                }
                let complete = disk.iter().rposition(|b| *b == b'\n').map_or(0, |i| i + 1);
                let folded = fold.file(path).unwrap_or_default();
                prop_assert_eq!(folded, &disk[..complete]);
            }
        }
    }
}

#[test]
fn diff_lines_names_touched_conversations_branches_and_uncertain_actions() {
    use core_log::payload::ActionState;
    use core_log::{DivergenceInputs, Pos};
    use core_proto::{AgentId, ConversationId, Epoch, NodeId};
    use core_store::EntryHash;

    let agent = "agent-a";
    let dir = ConversationKey::Default {
        agent: agent.into(),
    }
    .dir_name();
    let transcript = format!("conversations/{dir}/messages.jsonl");
    let canonical = vec![Payload::file_put(
        &transcript,
        b"{\"type\":\"message\",\"message\":{\"id\":\"ui-msg-1\"}}\n".to_vec(),
        false,
    )];
    let pos = |seq| Pos {
        seq,
        hash: EntryHash::from_bytes([u8::try_from(seq).unwrap_or(0); 32]),
    };
    let tail = vec![
        (
            pos(5),
            Payload::FileAppend {
                path: transcript,
                offset: 47,
                bytes: b"{\"type\":\"message\",\"message\":{\"id\":\"ui-msg-2\"}}\n".to_vec(),
            },
        ),
        (
            pos(6),
            Payload::GitRef {
                repo: "memfs/agent-a/memory".into(),
                name: "refs/heads/main".into(),
                old_oid: Some(vec![1; 20]),
                new_oid: Some(vec![2; 20]),
                symref: None,
            },
        ),
        (
            pos(7),
            Payload::ActionStatus {
                idempotency_key: "k1".into(),
                tool_call_id: "t1".into(),
                conversation_id: ConversationId::new("default:agent-a"),
                state: ActionState::Started,
            },
        ),
    ];
    let epoch = |n, b| Epoch {
        n,
        claimant: NodeId::from_bytes([b; 32]),
    };
    let inputs = DivergenceInputs {
        log_id: core_log::hashes::log_id(&AgentId::new(agent)),
        agent_id: AgentId::new(agent),
        fork_epoch: epoch(1, 1),
        fork: pos(4),
        competing: [
            EntryHash::from_bytes([3; 32]),
            EntryHash::from_bytes([4; 32]),
        ],
        stranded_epoch: epoch(1, 1),
        winning_epoch: epoch(2, 2),
        equivocation: false,
    };
    let projection = core_projection::diff_lines(&inputs, &canonical, &tail, false);
    assert_eq!(projection.conversations.len(), 1);
    assert_eq!(
        projection.conversations[0]
            .last_common_message_id
            .as_deref(),
        Some("ui-msg-1")
    );
    assert_eq!(
        projection.conversations[0].conversation_id.as_str(),
        "default:agent-a"
    );
    assert_eq!(projection.memfs_branches.len(), 1);
    assert!(
        projection.memfs_branches[0]
            .name
            .starts_with("refs/heads/divergence/1-")
    );
    assert_eq!(projection.uncertain.len(), 1);
}
