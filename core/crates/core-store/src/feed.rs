//! The conversation journal index: which log entry holds each feed entry of a conversation.
//!
//! The journal itself is `feed_append` entries in the agent log. This index lets any node serving
//! the journal page it by `(conversation, feed_seq)` without scanning the log. It follows the
//! canonical line: when a segment is reclassified, `core-feed` rewrites the affected rows in the
//! same transaction.

use core_proto::ConversationId;

use crate::error::StoreError;
use crate::ids::EntryHash;

/// One indexed feed entry.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FeedRow {
    /// The conversation.
    pub conversation_id: ConversationId,
    /// The feed seq the holder allocated.
    pub feed_seq: u64,
    /// The `feed_append` log entry holding it.
    pub entry_hash: EntryHash,
}

/// The feed index area of a transaction.
pub trait FeedJournal {
    /// Up to `limit` rows of the conversation with feed seq above `after` (or from the start),
    /// ordered by feed seq.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn feed_rows(
        &self,
        conversation_id: &ConversationId,
        after: Option<u64>,
        limit: usize,
    ) -> Result<Vec<FeedRow>, StoreError>;

    /// The highest indexed feed seq of the conversation.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn feed_head(&self, conversation_id: &ConversationId) -> Result<Option<u64>, StoreError>;

    /// Inserts or replaces the row at `(conversation, feed_seq)`.
    ///
    /// # Errors
    ///
    /// [`StoreError::Invalid`] when the feed seq exceeds `i64::MAX`.
    fn put_feed_row(&mut self, row: &FeedRow) -> Result<(), StoreError>;

    /// Deletes one row (retention). Returns `true` when it existed.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn delete_feed_row(
        &mut self,
        conversation_id: &ConversationId,
        feed_seq: u64,
    ) -> Result<bool, StoreError>;

    /// Deletes every row of the conversation above `feed_seq` (a reclassified tail). Returns how
    /// many were deleted.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn truncate_feed_after(
        &mut self,
        conversation_id: &ConversationId,
        feed_seq: u64,
    ) -> Result<u64, StoreError>;
}
