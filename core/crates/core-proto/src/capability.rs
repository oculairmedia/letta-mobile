//! Transport capabilities: the strings a peer lists in its `auth` frame and a node lists in its
//! `auth_response`. Feature capabilities and peer grants have their own registry; only the ones the
//! node protocol v1 core relies on are here.

/// The peer (or node) reassembles `frame_part` sequences, so frames larger than one frame may be sent
/// to it in parts. Clients advertise it; nodes always advertise it.
pub const FRAME_PART: &str = "frame_part";

/// Every push-lane frame of a connection carries a strictly increasing `event_seq`. Nodes always
/// advertise it. The spelling is exactly this; any other spelling is a different capability.
pub const EVENT_SEQ_ORDERED_V1: &str = "event_seq_ordered_v1";

/// The node pushes conversation activity events. Nodes always advertise it.
pub const CONVERSATION_ACTIVITY_V1: &str = "conversation_activity_v1";

/// The node relays app-tool calls to this connection. Advertised only while a relay is attached.
pub const APP_TOOL_RELAY_V1: &str = "app_tool_relay_v1";

/// The capabilities every node lists first in a successful `auth_response`, in this order.
pub const ALWAYS_ADVERTISED: [&str; 3] =
    [CONVERSATION_ACTIVITY_V1, FRAME_PART, EVENT_SEQ_ORDERED_V1];

/// Whether `capabilities` contains `capability`.
#[must_use]
pub fn advertises<S: AsRef<str>>(capabilities: &[S], capability: &str) -> bool {
    capabilities
        .iter()
        .any(|candidate| candidate.as_ref() == capability)
}
