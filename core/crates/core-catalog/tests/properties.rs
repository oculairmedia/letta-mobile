//! Properties: documents built by random catalog API calls carry no path or secret, every view of
//! them is free of violations, and a secret or a host path never reaches Automerge.

mod common;

use automerge::AutoCommit;
use common::{doc, node};
use core_catalog::canonical::canonical_view;
use core_catalog::model::{ActorRef, Detail, NewNode, NodeKind};
use core_catalog::write::{self, Genesis};
use core_catalog::{CatalogError, DocClass, ViewContext, secrets, view};
use proptest::prelude::*;

#[derive(Clone, Debug)]
enum Call {
    Node {
        id: u8,
        parent: Option<u8>,
        title: String,
    },
    Title {
        id: u8,
        title: String,
    },
    Delete {
        id: u8,
        at: i64,
    },
    Tombstone {
        id: u8,
        at: i64,
    },
}

fn call() -> impl Strategy<Value = Call> {
    let title = "[A-Za-z0-9 ,.:\\\\-]{0,40}";
    prop_oneof![
        (any::<u8>(), proptest::option::of(any::<u8>()), title)
            .prop_map(|(id, parent, title)| Call::Node { id, parent, title }),
        (any::<u8>(), title).prop_map(|(id, title)| Call::Title { id, title }),
        (any::<u8>(), 0_i64..1_000_000).prop_map(|(id, at)| Call::Delete { id, at }),
        (any::<u8>(), 0_i64..1_000_000).prop_map(|(id, at)| Call::Tombstone { id, at }),
    ]
}

fn agent_doc() -> AutoCommit {
    let mut d = doc(1);
    write::genesis(
        &mut d,
        DocClass::Agent,
        &Genesis {
            constellation_id: "c".repeat(64),
            created_at_ms: 0,
            agent_id: Some("agent-a".into()),
            name: None,
        },
    )
    .expect("genesis");
    d
}

fn apply(d: &mut AutoCommit, call: &Call) {
    let id = |n: &u8| format!("conv-{n}");
    let _ = match call {
        Call::Node {
            id: n,
            parent,
            title,
        } => write::put_node(
            d,
            &NewNode {
                id: id(n),
                agent_id: "agent-a".into(),
                parent_id: parent.as_ref().map(id),
                kind: if parent.is_some() {
                    NodeKind::Fork
                } else {
                    NodeKind::Root
                },
                fork_point: None,
                created_by: ActorRef::node(node(1).to_hex()),
                created_at_ms: 0,
                title: Some(title.clone()),
                title_source: Some("user".into()),
                last_activity_at_ms: None,
                deleted_at_ms: None,
                detail: Detail::None {},
            },
        ),
        Call::Title { id: n, title } => {
            write::set_node_field(d, &id(n), "title", &serde_json::json!(title))
        }
        Call::Delete { id: n, at } => {
            write::set_node_field(d, &id(n), "deletedAtMs", &serde_json::json!(at))
        }
        Call::Tombstone { id: n, at } => write::tombstone(d, &id(n), *at),
    };
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(48))]

    #[test]
    fn random_api_calls_leave_no_path_secret_or_violation(calls in proptest::collection::vec(call(), 0..40)) {
        let mut d = agent_doc();
        for call in &calls {
            apply(&mut d, call);
        }
        let root = canonical_view(&d);
        prop_assert!(secrets::scan(&root).is_empty());
        let typed = view(DocClass::Agent, &root, &ViewContext::default());
        // Random parents may form cycles or orphans; those are flagged, nothing else is.
        for violation in typed.violations() {
            prop_assert!(
                violation.reason == "cycle" || violation.reason == "id_reused_after_tombstone",
                "{violation:?}"
            );
        }
    }

    #[test]
    fn secrets_and_host_paths_never_reach_automerge(
        secret in prop_oneof![
            Just("sk-abcdefghijklmnop".to_owned()),
            Just("ghp_abcdefghijklmnop".to_owned()),
            Just("Bearer token".to_owned()),
            Just("-----BEGIN PRIVATE KEY".to_owned()),
        ],
        path in prop_oneof![
            Just("C:\\Users\\me".to_owned()),
            Just("/home/me/x".to_owned()),
            Just("file:///etc".to_owned()),
        ],
    ) {
        let mut d = agent_doc();
        let before = canonical_view(&d);
        let refused = write::set_node_field(&mut d, "conv-x", "title", &serde_json::json!(secret));
        prop_assert!(refused.is_err());
        let node = NewNode {
            id: path.clone(),
            agent_id: "agent-a".into(),
            parent_id: None,
            kind: NodeKind::Root,
            fork_point: None,
            created_by: ActorRef::node(node(1).to_hex()),
            created_at_ms: 0,
            title: Some(secret.clone()),
            title_source: None,
            last_activity_at_ms: None,
            deleted_at_ms: None,
            detail: Detail::None {},
        };
        let refused_node = write::put_node(&mut d, &node);
        prop_assert!(
            matches!(refused_node, Err(CatalogError::Refused { .. })),
            "{refused_node:?}"
        );
        prop_assert_eq!(canonical_view(&d), before);
    }
}
