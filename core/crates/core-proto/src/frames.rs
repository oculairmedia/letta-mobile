//! Control-lane frames, error frames and denial frames, as they are on the wire today.
//!
//! The frames the node itself defines (`auth`, `auth_response`, `error`, `runtime_start`,
//! `runtime_start_response`, `admin_rpc`, `admin_rpc_response`) are typed. Every other frame type
//! (the runtime commands the node forwards, such as `input`, `sync` or `abort_message`, and their
//! replies) is carried as a [`PassthroughFrame`] with its fields in their original order, because its
//! shape belongs to the runtime or to a later contract, not to this one.
//!
//! Struct fields are declared in the order the Kotlin node writes them, so a frame this crate
//! serializes is byte-for-byte the frame the Kotlin node sends.

use std::fmt;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use thiserror::Error;

use crate::auth::AuthErrorCode;
use crate::ids::{AgentId, ConversationId, RequestId};

/// The lane a frame travels on.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Lane {
    /// Both directions: auth, runtime commands and their replies, errors.
    Control,
    /// Node to peer: turn frames and pushes.
    Push,
    /// Both directions, one per call: one `admin_rpc` request and its reply.
    Call,
}

/// Longest a node waits for the `admin_rpc` request on a new call lane, in milliseconds.
pub const ADMIN_RPC_FIRST_FRAME_TIMEOUT_MS: u64 = 15_000;

/// Call lanes a node serves at once per session; later ones wait for a free slot, they are not refused.
pub const MAX_CONCURRENT_ADMIN_RPC_CALLS: usize = 16;

/// The control frame types a node accepts from a peer. Any other type is answered with
/// [`ErrorFrame::unknown_command_type`].
pub const NODE_ACCEPTED_CONTROL_TYPES: [&str; 9] = [
    "auth",
    "runtime_start",
    "input",
    "admin_rpc",
    "app_tool_call_response",
    "sync",
    "abort_message",
    "remove_queue_item",
    "resume_queue",
];

/// Control frame types that address an agent and so are checked against an agent-scoped peer's
/// scope before anything else happens.
pub const AGENT_ADDRESSED_CONTROL_TYPES: [&str; 6] = [
    "runtime_start",
    "input",
    "sync",
    "abort_message",
    "remove_queue_item",
    "resume_queue",
];

/// The first frame of a session: the peer presents a credential and its transport capabilities.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Auth {
    /// Matches the reply. Required; a frame without it is refused with
    /// [`AuthErrorCode::RequestIdRequired`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<RequestId>,
    /// The bootstrap bearer token, or `invite:<secret>` to redeem an invite. Absent for a paired peer.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    /// A display name for the peer, used when an invite pairs it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub peer_name: Option<String>,
    /// The peer's transport capabilities, such as `frame_part`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Vec<String>>,
}

impl fmt::Debug for Auth {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Auth")
            .field("request_id", &self.request_id)
            .field("token", &self.token.as_ref().map(|_| "<redacted>"))
            .field("peer_name", &self.peer_name)
            .field("capabilities", &self.capabilities)
            .finish()
    }
}

/// The node's answer to [`Auth`].
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuthResponse {
    /// The request's id; absent only when the request had none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<RequestId>,
    /// Whether the session is now authenticated.
    pub success: bool,
    /// `true` when the session is a paired device's (by invite now, or paired earlier).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub paired: Option<bool>,
    /// `true` when the frame carried an invite but the NodeId was already paired; the invite is left
    /// unused for the device it was meant for.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub already_paired: Option<bool>,
    /// The node's capabilities, on success.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Vec<String>>,
    /// Why the frame was refused, on failure.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<AuthErrorCode>,
}

impl AuthResponse {
    /// A refusal of the request `request_id` with `error`.
    #[must_use]
    pub fn refused(request_id: Option<RequestId>, error: AuthErrorCode) -> Self {
        Self {
            request_id,
            success: false,
            paired: None,
            already_paired: None,
            capabilities: None,
            error: Some(error),
        }
    }

    /// Acceptance of `request_id`, listing the node's `capabilities`.
    #[must_use]
    pub fn accepted(request_id: RequestId, capabilities: Vec<String>, pairing: Pairing) -> Self {
        Self {
            request_id: Some(request_id),
            success: true,
            paired: matches!(pairing, Pairing::NewlyPaired | Pairing::AlreadyPaired)
                .then_some(true),
            already_paired: matches!(pairing, Pairing::AlreadyPaired).then_some(true),
            capabilities: Some(capabilities),
            error: None,
        }
    }
}

/// What an accepted `auth` says about pairing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Pairing {
    /// Neither flag: a bootstrap session, or a paired NodeId that sent no invite.
    NotReported,
    /// `paired: true`: the frame redeemed an invite.
    NewlyPaired,
    /// `paired: true, already_paired: true`: the frame carried an invite for an already paired NodeId.
    AlreadyPaired,
}

/// The `message` of an [`ErrorFrame`].
#[derive(Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(from = "String", into = "String")]
pub enum ErrorMessage {
    /// `unauthorized`: the session has not authenticated.
    Unauthorized,
    /// `forbidden`: the session lacks a capability (named in `capability`) or the frame is outside its
    /// agent scope (`reason: agent_scope`).
    Forbidden,
    /// `Unknown command type: <type>`; `null` when the frame had no `type`.
    UnknownCommandType(String),
    /// `Failed to parse frame (<diagnostic>)`. The diagnostic names an error class only; nothing of the
    /// frame ever comes back, because a frame can carry a secret.
    ParseFailure(String),
    /// Any other text.
    Other(String),
}

const UNKNOWN_COMMAND_PREFIX: &str = "Unknown command type: ";
const PARSE_FAILURE_PREFIX: &str = "Failed to parse frame (";

impl From<String> for ErrorMessage {
    fn from(text: String) -> Self {
        if text == "unauthorized" {
            return Self::Unauthorized;
        }
        if text == "forbidden" {
            return Self::Forbidden;
        }
        if let Some(type_name) = text.strip_prefix(UNKNOWN_COMMAND_PREFIX) {
            return Self::UnknownCommandType(type_name.to_owned());
        }
        if let Some(diagnostic) = text
            .strip_prefix(PARSE_FAILURE_PREFIX)
            .and_then(|rest| rest.strip_suffix(')'))
        {
            return Self::ParseFailure(diagnostic.to_owned());
        }
        Self::Other(text)
    }
}

impl From<ErrorMessage> for String {
    fn from(message: ErrorMessage) -> Self {
        match message {
            ErrorMessage::Unauthorized => "unauthorized".to_owned(),
            ErrorMessage::Forbidden => "forbidden".to_owned(),
            ErrorMessage::UnknownCommandType(type_name) => {
                format!("{UNKNOWN_COMMAND_PREFIX}{type_name}")
            }
            ErrorMessage::ParseFailure(diagnostic) => {
                format!("{PARSE_FAILURE_PREFIX}{diagnostic})")
            }
            ErrorMessage::Other(text) => text,
        }
    }
}

impl fmt::Debug for ErrorMessage {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "ErrorMessage({:?})", String::from(self.clone()))
    }
}

/// The control lane's error and denial frame.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ErrorFrame {
    /// The refused request's id: empty when it had none. Absent on unknown-type and parse errors.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<RequestId>,
    /// What went wrong.
    pub message: ErrorMessage,
    /// The capability the session lacks, on a capability denial.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capability: Option<String>,
    /// `agent_scope` on an agent-scope denial.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

impl ErrorFrame {
    /// The `reason` of an agent-scope denial.
    pub const AGENT_SCOPE: &'static str = "agent_scope";

    /// A frame other than `auth` (or an unknown type) from a session that has not authenticated.
    #[must_use]
    pub fn unauthorized(request_id: Option<&RequestId>) -> Self {
        Self::denial(request_id, ErrorMessage::Unauthorized, None, None)
    }

    /// A runtime command from a session without `capability`.
    #[must_use]
    pub fn forbidden(request_id: Option<&RequestId>, capability: impl Into<String>) -> Self {
        Self::denial(
            request_id,
            ErrorMessage::Forbidden,
            Some(capability.into()),
            None,
        )
    }

    /// A runtime command naming an agent, or a conversation of an agent, outside the session's scope.
    #[must_use]
    pub fn agent_scope(request_id: Option<&RequestId>) -> Self {
        Self::denial(
            request_id,
            ErrorMessage::Forbidden,
            None,
            Some(Self::AGENT_SCOPE.to_owned()),
        )
    }

    /// A frame whose `type` the node does not accept (`None` when it had no string `type`).
    #[must_use]
    pub fn unknown_command_type(type_name: Option<&str>) -> Self {
        Self {
            request_id: None,
            message: ErrorMessage::UnknownCommandType(type_name.unwrap_or("null").to_owned()),
            capability: None,
            reason: None,
        }
    }

    /// A frame that is not a JSON object. `diagnostic` names an error class, never frame content.
    #[must_use]
    pub fn parse_failure(diagnostic: impl Into<String>) -> Self {
        Self {
            request_id: None,
            message: ErrorMessage::ParseFailure(diagnostic.into()),
            capability: None,
            reason: None,
        }
    }

    fn denial(
        request_id: Option<&RequestId>,
        message: ErrorMessage,
        capability: Option<String>,
        reason: Option<String>,
    ) -> Self {
        Self {
            request_id: Some(request_id.cloned().unwrap_or_else(|| RequestId::new(""))),
            message,
            capability,
            reason,
        }
    }
}

/// Starts (or attaches to) the runtime of one conversation.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimeStart {
    /// Matches the reply.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<RequestId>,
    /// The agent.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<AgentId>,
    /// The conversation.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub conversation_id: Option<ConversationId>,
    /// The working directory, when the peer chooses one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    /// A permission mode. Setting one needs `conversation.manage`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mode: Option<String>,
}

/// The agent and conversation a runtime serves.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimeScope {
    /// The agent.
    pub agent_id: AgentId,
    /// The conversation.
    pub conversation_id: ConversationId,
}

/// The node's answer to [`RuntimeStart`].
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimeStartResponse {
    /// The request's id; absent only when the request had none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<RequestId>,
    /// Whether the runtime started.
    pub success: bool,
    /// The runtime's scope, on success.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub runtime: Option<RuntimeScope>,
    /// Why it did not, on failure.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// One admin method call: the first frame of a call lane, or a control frame.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AdminRpc {
    /// Matches the reply.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<RequestId>,
    /// The method, such as `health.check`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    /// The method's parameters.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub params: Option<Map<String, Value>>,
}

/// The reply to [`AdminRpc`]. The fields every reply shares are typed; a method's own fields stay in
/// `body`, in their order.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AdminRpcResponse {
    /// The request's id (empty when it had none).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<RequestId>,
    /// Whether the call succeeded.
    pub success: bool,
    /// Why it did not.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// The capability the session lacks, on a capability denial.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capability: Option<String>,
    /// The method's own reply fields.
    #[serde(flatten)]
    pub body: Map<String, Value>,
}

impl AdminRpcResponse {
    /// A call refused because the session lacks `capability`.
    #[must_use]
    pub fn forbidden(request_id: RequestId, capability: impl Into<String>) -> Self {
        Self {
            request_id: Some(request_id),
            success: false,
            error: Some("forbidden".to_owned()),
            capability: Some(capability.into()),
            body: Map::new(),
        }
    }

    /// A call that failed with `error` and no other field (on a call lane: unauthenticated, malformed,
    /// or a reply too large for the peer).
    #[must_use]
    pub fn failed(request_id: RequestId, error: impl Into<String>) -> Self {
        Self {
            request_id: Some(request_id),
            success: false,
            error: Some(error.into()),
            capability: None,
            body: Map::new(),
        }
    }
}

/// A frame this contract does not type, kept whole: its `type` and every other field, in order.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PassthroughFrame {
    fields: Map<String, Value>,
}

impl PassthroughFrame {
    /// The frame's `type`.
    #[must_use]
    pub fn type_name(&self) -> &str {
        self.fields
            .get("type")
            .and_then(Value::as_str)
            .unwrap_or_default()
    }

    /// Every field of the frame, `type` included.
    #[must_use]
    pub const fn fields(&self) -> &Map<String, Value> {
        &self.fields
    }
}

/// The frames this contract types, tagged by `type`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Typed {
    Auth(Auth),
    AuthResponse(AuthResponse),
    Error(ErrorFrame),
    RuntimeStart(RuntimeStart),
    RuntimeStartResponse(RuntimeStartResponse),
    AdminRpc(AdminRpc),
    AdminRpcResponse(AdminRpcResponse),
}

const TYPED: [&str; 7] = [
    "auth",
    "auth_response",
    "error",
    "runtime_start",
    "runtime_start_response",
    "admin_rpc",
    "admin_rpc_response",
];

/// Any frame on the control lane (and the `admin_rpc` frames of call lanes).
#[derive(Clone, Debug, PartialEq)]
pub enum ControlFrame {
    /// `auth`.
    Auth(Auth),
    /// `auth_response`.
    AuthResponse(AuthResponse),
    /// `error`.
    Error(ErrorFrame),
    /// `runtime_start`.
    RuntimeStart(RuntimeStart),
    /// `runtime_start_response`.
    RuntimeStartResponse(RuntimeStartResponse),
    /// `admin_rpc`.
    AdminRpc(AdminRpc),
    /// `admin_rpc_response`.
    AdminRpcResponse(AdminRpcResponse),
    /// Any other type, kept whole.
    Passthrough(PassthroughFrame),
}

/// A frame that cannot be read as a [`ControlFrame`].
#[derive(Debug, Error)]
pub enum FrameParseError {
    /// Not JSON at all.
    #[error("frame is not JSON")]
    NotJson,
    /// JSON, but not an object.
    #[error("frame is not a JSON object")]
    NotAnObject,
    /// An object without a string `type`.
    #[error("frame has no string type")]
    MissingType,
    /// A typed frame whose fields do not match its type. The message names the field problem only.
    #[error("{type_name} frame is malformed")]
    Malformed {
        /// The frame's `type`.
        type_name: String,
    },
}

impl FrameParseError {
    /// The diagnostic a node puts in [`ErrorFrame::parse_failure`]: an error class, never content.
    #[must_use]
    pub const fn diagnostic(&self) -> &'static str {
        match self {
            Self::NotJson => "NotJson",
            Self::NotAnObject => "NotAnObject",
            Self::MissingType => "MissingType",
            Self::Malformed { .. } => "Malformed",
        }
    }
}

impl ControlFrame {
    /// Reads one frame's JSON text.
    ///
    /// # Errors
    ///
    /// [`FrameParseError`] when the text is not a JSON object with a string `type`, or a typed frame's
    /// fields have the wrong JSON types.
    pub fn parse(json: &str) -> Result<Self, FrameParseError> {
        let value: Value = serde_json::from_str(json).map_err(|_| FrameParseError::NotJson)?;
        let Value::Object(fields) = value else {
            return Err(FrameParseError::NotAnObject);
        };
        let type_name = match fields.get("type") {
            Some(Value::String(type_name)) => type_name.clone(),
            _ => return Err(FrameParseError::MissingType),
        };
        if !TYPED.contains(&type_name.as_str()) {
            return Ok(Self::Passthrough(PassthroughFrame { fields }));
        }
        let typed: Typed = serde_json::from_value(Value::Object(fields))
            .map_err(|_| FrameParseError::Malformed { type_name })?;
        Ok(typed.into())
    }

    /// The frame's JSON text, compact, fields in wire order.
    ///
    /// # Panics
    ///
    /// Never in practice: every field is a string, bool, list or JSON value, which always serialize.
    #[must_use]
    pub fn to_json(&self) -> String {
        let result = match self.clone().into_typed() {
            Ok(typed) => serde_json::to_string(&typed),
            Err(fields) => serde_json::to_string(&fields),
        };
        result.expect("frames serialize to JSON")
    }

    /// The frame's `type`.
    #[must_use]
    pub fn type_name(&self) -> &str {
        match self {
            Self::Auth(_) => "auth",
            Self::AuthResponse(_) => "auth_response",
            Self::Error(_) => "error",
            Self::RuntimeStart(_) => "runtime_start",
            Self::RuntimeStartResponse(_) => "runtime_start_response",
            Self::AdminRpc(_) => "admin_rpc",
            Self::AdminRpcResponse(_) => "admin_rpc_response",
            Self::Passthrough(frame) => frame.type_name(),
        }
    }

    /// The frame's `request_id`, if it carries one.
    #[must_use]
    pub fn request_id(&self) -> Option<&str> {
        match self {
            Self::Auth(frame) => frame.request_id.as_ref().map(RequestId::as_str),
            Self::AuthResponse(frame) => frame.request_id.as_ref().map(RequestId::as_str),
            Self::Error(frame) => frame.request_id.as_ref().map(RequestId::as_str),
            Self::RuntimeStart(frame) => frame.request_id.as_ref().map(RequestId::as_str),
            Self::RuntimeStartResponse(frame) => frame.request_id.as_ref().map(RequestId::as_str),
            Self::AdminRpc(frame) => frame.request_id.as_ref().map(RequestId::as_str),
            Self::AdminRpcResponse(frame) => frame.request_id.as_ref().map(RequestId::as_str),
            Self::Passthrough(frame) => frame.fields.get("request_id").and_then(Value::as_str),
        }
    }
}

impl From<Typed> for ControlFrame {
    fn from(typed: Typed) -> Self {
        match typed {
            Typed::Auth(frame) => Self::Auth(frame),
            Typed::AuthResponse(frame) => Self::AuthResponse(frame),
            Typed::Error(frame) => Self::Error(frame),
            Typed::RuntimeStart(frame) => Self::RuntimeStart(frame),
            Typed::RuntimeStartResponse(frame) => Self::RuntimeStartResponse(frame),
            Typed::AdminRpc(frame) => Self::AdminRpc(frame),
            Typed::AdminRpcResponse(frame) => Self::AdminRpcResponse(frame),
        }
    }
}

impl ControlFrame {
    /// The typed form for serialization, or a passthrough frame's own fields.
    fn into_typed(self) -> Result<Typed, Map<String, Value>> {
        Ok(match self {
            Self::Auth(frame) => Typed::Auth(frame),
            Self::AuthResponse(frame) => Typed::AuthResponse(frame),
            Self::Error(frame) => Typed::Error(frame),
            Self::RuntimeStart(frame) => Typed::RuntimeStart(frame),
            Self::RuntimeStartResponse(frame) => Typed::RuntimeStartResponse(frame),
            Self::AdminRpc(frame) => Typed::AdminRpc(frame),
            Self::AdminRpcResponse(frame) => Typed::AdminRpcResponse(frame),
            Self::Passthrough(frame) => return Err(frame.fields),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_debug_never_shows_the_token() {
        let auth = Auth {
            request_id: Some(RequestId::new("node-1")),
            token: Some("s3cret-bootstrap-token".to_owned()),
            peer_name: None,
            capabilities: None,
        };
        let debug = format!("{auth:?}");
        assert!(!debug.contains("s3cret"), "{debug}");
        assert!(debug.contains("<redacted>"));
    }

    #[test]
    fn error_messages_round_trip_their_text() {
        for text in [
            "unauthorized",
            "forbidden",
            "Unknown command type: null",
            "Failed to parse frame (SerializationException)",
            "something else",
        ] {
            assert_eq!(String::from(ErrorMessage::from(text.to_owned())), text);
        }
    }

    #[test]
    fn a_frame_without_a_type_is_refused() {
        assert!(matches!(
            ControlFrame::parse("{}"),
            Err(FrameParseError::MissingType)
        ));
        assert!(matches!(
            ControlFrame::parse("[1]"),
            Err(FrameParseError::NotAnObject)
        ));
        assert!(matches!(
            ControlFrame::parse("{"),
            Err(FrameParseError::NotJson)
        ));
        assert!(matches!(
            ControlFrame::parse(r#"{"type":"auth","capabilities":"frame_part"}"#),
            Err(FrameParseError::Malformed { .. })
        ));
    }
}
