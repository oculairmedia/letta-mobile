//! Divergence support (C1 §6.3): what the holder's `divergence` entry needs from the two lines.
//!
//! [`diff_lines`] reads the canonical line up to the fork point and the divergent tail as payloads
//! (never a running store) and reports the conversations the tail touched with the last message
//! both lines share, the memfs refs the tail moved (as divergence branch names), and the tool calls
//! the tail started without finishing.

use std::collections::{BTreeMap, BTreeSet};

use core_log::fold::StateFold;
use core_log::payload::{Action, ActionState, Payload, RefEntry};
use core_log::{DivergenceInputs, DivergenceProjection, Pos, TouchedConversation};
use core_proto::{AgentId, ConversationId};
use serde_json::Value;

use crate::layout::{self, ConversationKey, TRANSCRIPT, conversation_dir, conversation_key};

/// Computes the projection of one divergence from the canonical payloads up to and including the
/// fork entry (`canonical`) and the divergent tail's payloads (`tail`), both in seq order. `tail`
/// carries each entry's position, for the uncertain actions' `started_at`.
#[must_use]
pub fn diff_lines(
    inputs: &DivergenceInputs,
    canonical: &[Payload],
    tail: &[(Pos, Payload)],
    from_revoked: bool,
) -> DivergenceProjection {
    let mut common = StateFold::new();
    for payload in canonical {
        // A canonical line that does not fold cannot have been materialized; skip the bad entry.
        let _ = common.apply(payload);
    }
    let mut touched: BTreeMap<String, TouchedConversation> = BTreeMap::new();
    let mut branches: BTreeSet<RefEntry> = BTreeSet::new();
    let mut started: BTreeMap<String, Action> = BTreeMap::new();
    for (pos, payload) in tail {
        match payload {
            Payload::FilePut { path, .. }
            | Payload::FileAppend { path, .. }
            | Payload::FileDelete { path } => {
                if let Some(dir) = conversation_dir(path)
                    && let Some(key) = conversation_key(dir)
                {
                    touched
                        .entry(dir.to_owned())
                        .or_insert_with(|| touched_conversation(&common, dir, &key, inputs));
                }
            }
            Payload::FeedAppend {
                conversation_id, ..
            } => {
                let dir = ConversationKey::Conversation {
                    id: conversation_id.as_str().to_owned(),
                }
                .dir_name();
                touched
                    .entry(dir.clone())
                    .or_insert_with(|| TouchedConversation {
                        conversation_id: conversation_id.clone(),
                        last_common_message_id: last_message_id(
                            common.file(&format!("conversations/{dir}/{TRANSCRIPT}")),
                        ),
                        owner: inputs.agent_id.clone(),
                    });
            }
            Payload::GitRef {
                repo,
                name,
                new_oid: Some(oid),
                symref: None,
                ..
            } if name.starts_with("refs/heads/") => {
                let leaf = name.trim_start_matches("refs/heads/");
                branches.insert(RefEntry {
                    repo: repo.clone(),
                    name: inputs.memfs_branch(leaf),
                    oid: oid.clone(),
                });
            }
            Payload::ActionStatus {
                idempotency_key,
                tool_call_id,
                conversation_id,
                state,
            } => match state {
                ActionState::Started => {
                    started.insert(
                        idempotency_key.clone(),
                        Action {
                            idempotency_key: idempotency_key.clone(),
                            tool_call_id: tool_call_id.clone(),
                            conversation_id: conversation_id.clone(),
                            started_at: *pos,
                        },
                    );
                }
                _ => {
                    started.remove(idempotency_key);
                }
            },
            _ => {}
        }
    }
    let mut conversations: Vec<TouchedConversation> = touched.into_values().collect();
    conversations.sort_by(|a, b| a.conversation_id.as_str().cmp(b.conversation_id.as_str()));
    DivergenceProjection {
        conversations,
        memfs_branches: branches.into_iter().collect(),
        uncertain: started.into_values().collect(),
        from_revoked,
    }
}

fn touched_conversation(
    common: &StateFold,
    dir: &str,
    key: &ConversationKey,
    inputs: &DivergenceInputs,
) -> TouchedConversation {
    let record = common
        .file(&format!("conversations/{dir}/conversation.json"))
        .and_then(|bytes| serde_json::from_slice::<Value>(bytes).ok());
    let owner = match key {
        ConversationKey::Default { agent } => AgentId::new(agent.clone()),
        ConversationKey::Conversation { .. } => record
            .as_ref()
            .and_then(|r| {
                ["agent_id", "agentId"]
                    .iter()
                    .find_map(|k| r.get(k).and_then(Value::as_str))
            })
            .map_or_else(|| inputs.agent_id.clone(), |a| AgentId::new(a.to_owned())),
    };
    TouchedConversation {
        conversation_id: ConversationId::new(key.conversation_id()),
        last_common_message_id: last_message_id(
            common.file(&format!("conversations/{dir}/{TRANSCRIPT}")),
        ),
        owner,
    }
}

/// The id of the last message in a transcript: the last line's `message.id`, else its `id`, for a
/// line whose `type` is `message` (or that carries a `message` object).
#[must_use]
pub fn last_message_id(transcript: Option<&[u8]>) -> Option<String> {
    let text = std::str::from_utf8(transcript?).ok()?;
    text.lines().rev().find_map(|line| {
        let value: Value = serde_json::from_str(line).ok()?;
        let message = value.get("message");
        let is_message =
            value.get("type").and_then(Value::as_str) == Some("message") || message.is_some();
        if !is_message {
            return None;
        }
        message
            .and_then(|m| m.get("id"))
            .or_else(|| value.get("id"))
            .and_then(Value::as_str)
            .map(str::to_owned)
    })
}

/// The memfs repository directory of `agent` in a store that `fold` describes, if its refs are in
/// the log.
#[must_use]
pub fn repo_in_log(fold: &StateFold, agent: &str) -> Option<String> {
    fold.refs()
        .into_iter()
        .map(|r| r.repo)
        .find(|repo| layout::repo_agent(repo) == Some(agent))
}
