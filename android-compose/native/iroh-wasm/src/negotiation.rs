//! Transport capability negotiation read off the control stream, as the native
//! `IrohAppServerTransport` does: a successful `auth_response` says whether the server reassembles
//! `frame_part`s, so control payloads over 1 MiB may go out in parts (letta-mobile-v27zq).

use serde_json::Value;

/// Auth capability that lets either side split frames larger than one frame.
pub const FRAME_PART_CAPABILITY: &str = "frame_part";

/// An `auth_response` is a few hundred bytes; larger frames are skipped without parsing them.
const MAX_AUTH_RESPONSE_BYTES: usize = 64 * 1024;

/// Whether [frame] is a successful `auth_response` that lists `frame_part`: `Some(true)` or
/// `Some(false)` for a successful one, `None` for a failed one or any other frame.
#[must_use]
pub fn auth_allows_frame_parts(frame: &str) -> Option<bool> {
    if frame.len() > MAX_AUTH_RESPONSE_BYTES || !frame.contains("auth_response") {
        return None;
    }
    let value: Value = serde_json::from_str(frame).ok()?;
    if value.get("type")?.as_str()? != "auth_response"
        || value.get("success")?.as_bool() != Some(true)
    {
        return None;
    }
    let allows = value
        .get("capabilities")
        .and_then(Value::as_array)
        .is_some_and(|capabilities| {
            capabilities
                .iter()
                .any(|capability| capability.as_str() == Some(FRAME_PART_CAPABILITY))
        });
    Some(allows)
}
