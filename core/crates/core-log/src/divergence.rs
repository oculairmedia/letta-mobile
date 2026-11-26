//! Divergence inputs (C1 §3.8): the values every replica derives identically from the two signed
//! lines, which C2's catalog node id and the holder's `divergence` entry are built from.

use core_proto::{AgentId, ConversationId, Epoch};
use core_store::{DivergenceId, EntryHash, LogId};

use crate::hashes::{c2_divergence_node_id, divergence_id, hex, uuid_text};
use crate::payload::{Action, DivergencePayload, DivergentConversation, RefEntry};
use crate::value::Pos;

/// The canonical inputs of one divergence. Every field is a function of the stored entries alone,
/// never of which node noticed first or when.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct DivergenceInputs {
    /// The agent log.
    pub log_id: LogId,
    /// The agent.
    pub agent_id: AgentId,
    /// The epoch holding the fork entry.
    pub fork_epoch: Epoch,
    /// The last common entry.
    pub fork: Pos,
    /// The two entry hashes at `fork.seq + 1`, ascending bytewise.
    pub competing: [EntryHash; 2],
    /// The losing (stranded) epoch: the epoch of the divergent entry at `fork.seq + 1`.
    pub stranded_epoch: Epoch,
    /// The winning epoch: the epoch of the canonical entry at `fork.seq + 1`.
    pub winning_epoch: Epoch,
    /// Both entries at `fork.seq + 1` are in one epoch.
    pub equivocation: bool,
}

impl DivergenceInputs {
    /// `derive_key("divergence id", log_id ‖ stranded_epoch ‖ fork.seq)`.
    #[must_use]
    pub fn id(&self) -> DivergenceId {
        divergence_id(&self.log_id, &self.stranded_epoch, self.fork.seq)
    }

    /// C2's catalog divergence node id for `conversation`.
    #[must_use]
    pub fn catalog_node_id(&self, conversation: &ConversationId) -> [u8; 16] {
        c2_divergence_node_id(
            self.agent_id.as_str(),
            conversation.as_str(),
            &self.stranded_epoch,
        )
    }

    /// `local-conv-<catalog node uuid>`: the branch conversation, sharing the catalog node's id.
    #[must_use]
    pub fn branch_conversation_id(&self, conversation: &ConversationId) -> ConversationId {
        ConversationId::new(format!(
            "local-conv-{}",
            uuid_text(&self.catalog_node_id(conversation))
        ))
    }

    /// `refs/heads/divergence/<n>-<first 8 hex of claimant>/<leaf>`.
    #[must_use]
    pub fn memfs_branch(&self, leaf: &str) -> String {
        format!(
            "refs/heads/divergence/{}-{}/{leaf}",
            self.stranded_epoch.n,
            &hex(self.stranded_epoch.claimant.as_bytes())[..8]
        )
    }

    /// The holder's `divergence` payload from these inputs plus what `core-projection` computed
    /// from the two lines.
    #[must_use]
    pub fn to_payload(
        &self,
        tail_head: Pos,
        projection: &DivergenceProjection,
    ) -> DivergencePayload {
        DivergencePayload {
            divergence_id: self.id(),
            losing_epoch: self.stranded_epoch,
            fork: self.fork,
            tail_head,
            conversations: projection
                .conversations
                .iter()
                .map(|c| DivergentConversation {
                    conversation_id: c.conversation_id.clone(),
                    last_common_message_id: c.last_common_message_id.clone(),
                    branch_conversation_id: self.branch_conversation_id(&c.conversation_id),
                    catalog_node_id: self.catalog_node_id(&c.conversation_id),
                    agent_id: c.owner.clone(),
                })
                .collect(),
            memfs_branches: projection.memfs_branches.clone(),
            uncertain: projection.uncertain.clone(),
            equivocation: self.equivocation,
            from_revoked: projection.from_revoked,
            agent_id: self.agent_id.clone(),
            fork_epoch: self.fork_epoch,
            winning_epoch: self.winning_epoch,
            competing: self.competing,
            stranded_holder: self.stranded_epoch.claimant,
        }
    }
}

/// One conversation the divergent tail touched, as `core-projection` reports it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TouchedConversation {
    /// The conversation.
    pub conversation_id: ConversationId,
    /// The last message both lines share.
    pub last_common_message_id: Option<String>,
    /// The owning agent (the tree agent or a subagent).
    pub owner: AgentId,
}

/// What `core-projection` computes from the two lines (C1 §6.3) for the holder's entry.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DivergenceProjection {
    /// Conversations touched by the tail.
    pub conversations: Vec<TouchedConversation>,
    /// Memfs divergence branches (use [`DivergenceInputs::memfs_branch`] for the names).
    pub memfs_branches: Vec<RefEntry>,
    /// Actions in doubt.
    pub uncertain: Vec<Action>,
    /// The losing claimant was revoked.
    pub from_revoked: bool,
}

/// A divergence a replica detected.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Divergence {
    /// The canonical inputs.
    pub inputs: DivergenceInputs,
    /// The newest entry of the divergent tail (highest seq, then lowest hash).
    pub tail_head: Pos,
    /// Every entry of the divergent tail, ordered by seq then hash. Kept for ever, never
    /// materialized.
    pub tail: Vec<EntryHash>,
}
