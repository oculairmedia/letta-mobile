//! The node-carrier v1 contract suite, runnable against any [`Carrier`].
//!
//! Each scenario of the shared fixture (`contracts/node-carrier/v1.json`, the file the Kotlin
//! suite reads) runs from both ends at once: a scripted shell dials through the carrier under test
//! and a scripted node, served by the same carrier, matches every frame it receives exactly. The
//! shell sends the frames the fixture lists, byte for byte as serialized here, so the node's exact
//! text comparison checks carrier rule 1 (frames unchanged). The rule tests that need no fixture
//! follow the Kotlin suite's names.
//!
//! A carrier plugs in with a [`ContractHarness`] and [`node_carrier_contract_tests!`]:
//!
//! ```text
//! core_carrier::node_carrier_contract_tests!(MyHarness::default());
//! ```

use std::collections::{BTreeSet, HashMap, VecDeque};
use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use async_trait::async_trait;
use core_proto::NodeId;
use core_proto::alpn::Alpn;
use core_proto::capability::FRAME_PART;
use core_proto::closure::Closure;
use serde_json::{Value, json};
use tokio::sync::mpsc;

use crate::in_process::InProcessNetwork;
use crate::{
    AcceptedLink, AppClose, CallLane, Carrier, CarrierError, DialedLink, Frame, Lane, Link,
    LinkClosure, MembershipAccess, MembershipQuery, NodeAddr, PeerLink, ProtocolHandler, Protocols,
    VerifiedPeer,
};

/// Overrides where the node-carrier fixture is read from.
pub const FIXTURE_ENV: &str = "BAKEN_NODE_CARRIER_FIXTURE";

/// The fixture's path below the repository root.
pub const FIXTURE_RELATIVE: &str =
    "android-compose/sharedLogic/src/commonTest/resources/contracts/node-carrier/v1.json";

/// Every scenario the suite runs; the fixture must list exactly these.
pub const SCENARIOS: [&str; 6] = [
    "auth",
    "capability_gate",
    "admin_rpc",
    "turn",
    "detach",
    "feed_resume",
];

/// How long one step may wait for the other end.
const STEP_TIMEOUT: Duration = Duration::from_secs(20);

/// How long one whole scenario or rule test may take.
const TEST_TIMEOUT: Duration = Duration::from_secs(90);

/// The close a scripted end uses when the other end broke the script.
const MISMATCH: (u64, &str) = (4900, "contract_mismatch");

/// What a suite result is: `Err` carries the failure text.
pub type Outcome = Result<(), String>;

/// Stops a harness's endpoints.
pub type Stop = Box<dyn FnOnce() -> Pin<Box<dyn Future<Output = ()> + Send>> + Send>;

/// The endpoints a [`ContractHarness`] started around the suite's node.
pub struct HarnessEndpoints {
    /// The carrier the scripted shell dials with.
    pub shell: Arc<dyn Carrier>,
    /// A second dialler with another identity, which the suite keeps out of the membership. `None`
    /// skips the access-hook test.
    pub outsider: Option<Arc<dyn Carrier>>,
    /// Where the suite's node answers.
    pub node: NodeAddr,
    /// A well-formed address where no node answers.
    pub unreachable: NodeAddr,
    /// Drops the transport under every open link without a close. `None` skips the loss test (a
    /// carrier whose transport a test cannot drop says why in its own test).
    pub sever: Option<Box<dyn Fn() + Send + Sync>>,
    /// Stops the endpoints.
    pub stop: Stop,
}

/// Starts a carrier's endpoints around the suite's node.
#[async_trait]
pub trait ContractHarness: Send + Sync {
    /// Starts a node that serves `protocols` on the carrier under test, and a shell carrier that can
    /// dial it.
    async fn start(&self, protocols: Protocols) -> HarnessEndpoints;
}

/// Panics with the failure text of `outcome`.
///
/// # Panics
///
/// When `outcome` is an error.
pub fn check(outcome: Outcome) {
    if let Err(failure) = outcome {
        panic!("{failure}");
    }
}

/// Where the fixture is read from: [`FIXTURE_ENV`], or [`FIXTURE_RELATIVE`] under the repository.
#[must_use]
pub fn fixture_path() -> PathBuf {
    std::env::var_os(FIXTURE_ENV).map_or_else(
        || {
            core_testkit::contracts_dir()
                .join("../../..")
                .join(FIXTURE_RELATIVE)
        },
        PathBuf::from,
    )
}

/// Reads the fixture.
///
/// # Errors
///
/// When the file is missing or not JSON.
pub fn load_fixture() -> Result<Value, String> {
    let path = fixture_path();
    let text = std::fs::read_to_string(&path)
        .map_err(|error| format!("reading {}: {error}", path.display()))?;
    serde_json::from_str(&text).map_err(|error| format!("parsing {}: {error}", path.display()))
}

/// The scenario names the fixture lists, in order.
///
/// # Errors
///
/// When the fixture cannot be read or has no scenario list.
pub fn fixture_scenarios() -> Result<Vec<String>, String> {
    let fixture = load_fixture()?;
    let scenarios = fixture["scenarios"]
        .as_array()
        .ok_or("fixture has no scenarios")?;
    Ok(scenarios
        .iter()
        .filter_map(|scenario| scenario["name"].as_str().map(str::to_owned))
        .collect())
}

/// The session protocol every scenario speaks.
fn session_alpn() -> &'static [u8] {
    Alpn::AppServer.as_bytes()
}

fn frame_of(value: &Value) -> Frame {
    Frame::new(value.to_string())
}

fn expect_frame(received: &Frame, expected: &Value, what: &str) -> Outcome {
    let wanted = expected.to_string();
    if received.as_str() == wanted {
        return Ok(());
    }
    Err(format!(
        "{what}: frame mismatch\n  expected {wanted}\n  received {}",
        received.as_str()
    ))
}

async fn within<T>(what: &str, future: impl Future<Output = T>) -> Result<T, String> {
    tokio::time::timeout(STEP_TIMEOUT, future)
        .await
        .map_err(|_| format!("{what}: timed out after {STEP_TIMEOUT:?}"))
}

/// Bounds a whole test, so a carrier that stalls an unbounded write fails instead of hanging.
async fn bounded<T>(future: impl Future<Output = Result<T, String>>) -> Result<T, String> {
    tokio::time::timeout(TEST_TIMEOUT, future)
        .await
        .unwrap_or_else(|_| Err(format!("timed out after {TEST_TIMEOUT:?}")))
}

fn mismatch() -> AppClose {
    AppClose::new(MISMATCH.0, MISMATCH.1)
}

fn field<'a>(step: &'a Value, name: &str) -> Result<&'a Value, String> {
    step.get(name)
        .ok_or_else(|| format!("step {} has no {name}", step["op"]))
}

fn label(step: &Value) -> Result<String, String> {
    field(step, "session")?
        .as_str()
        .map(str::to_owned)
        .ok_or_else(|| "session label is not a string".to_owned())
}

fn replies(step: &Value) -> Result<Vec<(Lane, Value)>, String> {
    let Some(list) = step.get("replies") else {
        return Ok(Vec::new());
    };
    list.as_array()
        .ok_or("replies is not a list")?
        .iter()
        .map(|reply| {
            let lane = match reply["lane"].as_str() {
                Some("control") => Lane::Control,
                Some("push") => Lane::Push,
                other => return Err(format!("unknown lane {other:?}")),
            };
            Ok((lane, reply["frame"].clone()))
        })
        .collect()
}

/// A membership the suite fills in after the harness started (the shell's identity is known only
/// then).
#[derive(Clone, Default)]
struct SuiteMembers(Arc<Mutex<BTreeSet<NodeId>>>);

impl SuiteMembers {
    fn admit(&self, peer: VerifiedPeer) {
        if let VerifiedPeer::Node(id) = peer {
            self.0
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .insert(id);
        }
    }
}

#[async_trait]
impl MembershipQuery for SuiteMembers {
    async fn is_member(&self, node: &NodeId) -> bool {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .contains(node)
    }
}

/// Hands every accepted link to the script and keeps serving it until it closes.
struct ScriptHandler {
    links: mpsc::UnboundedSender<AcceptedLink>,
}

#[async_trait]
impl ProtocolHandler for ScriptHandler {
    async fn serve(&self, accepted: AcceptedLink) {
        let link = Arc::clone(&accepted.link);
        if self.links.send(accepted).is_ok() {
            link.closed().await;
        }
    }
}

/// A started harness with the suite's node behind it.
struct Rig {
    endpoints: HarnessEndpoints,
    links: mpsc::UnboundedReceiver<AcceptedLink>,
}

impl Rig {
    async fn start(harness: &dyn ContractHarness) -> Self {
        let (links_tx, links) = mpsc::unbounded_channel();
        let members = SuiteMembers::default();
        let protocols = Protocols::builder()
            .access(MembershipAccess::new(members.clone()))
            .accept(session_alpn(), ScriptHandler { links: links_tx })
            .build();
        let endpoints = harness.start(protocols).await;
        members.admit(endpoints.shell.local());
        Self { endpoints, links }
    }

    async fn stop(self) {
        (self.endpoints.stop)().await;
    }

    /// Dials, authenticates with a minimal exchange and reports `frame_part` to the node end.
    async fn open_simple(&mut self) -> Result<(DialedLink, AcceptedLink), String> {
        let mut dialed = within(
            "dial",
            self.endpoints
                .shell
                .dial(&self.endpoints.node, session_alpn()),
        )
        .await?
        .map_err(|error| format!("dial: {error}"))?;
        let auth = json!({"type": "auth", "request_id": "r-1", "capabilities": [FRAME_PART]});
        dialed
            .link
            .send_control(frame_of(&auth))
            .await
            .map_err(|error| format!("send auth: {error}"))?;
        let mut accepted = within("accept", self.links.recv())
            .await?
            .ok_or("the node accepted no link")?;
        let received = within("auth at node", accepted.control.recv())
            .await?
            .ok_or("no auth frame at the node")?;
        expect_frame(&received, &auth, "auth at node")?;
        accepted.link.on_peer_capabilities(&[FRAME_PART.to_owned()]);
        let reply = json!({"type": "auth_response", "request_id": "r-1", "success": true, "capabilities": [FRAME_PART]});
        accepted
            .link
            .send_control(frame_of(&reply))
            .await
            .map_err(|error| format!("send auth_response: {error}"))?;
        match within("auth_response", dialed.inbound.recv()).await? {
            Some((Lane::Control, frame)) => expect_frame(&frame, &reply, "auth_response")?,
            other => return Err(format!("expected auth_response, got {other:?}")),
        }
        Ok((dialed, accepted))
    }
}

/// Runs one fixture scenario over the harness's carrier.
///
/// # Errors
///
/// The node's mismatch text when the node saw a frame the script does not allow, else the shell's
/// failure.
pub async fn run_scenario(harness: &dyn ContractHarness, name: &str) -> Outcome {
    let fixture = load_fixture()?;
    let scenario = fixture["scenarios"]
        .as_array()
        .and_then(|list| list.iter().find(|scenario| scenario["name"] == name))
        .ok_or_else(|| format!("fixture has no scenario {name}"))?;
    let steps = scenario["steps"]
        .as_array()
        .ok_or("scenario has no steps")?
        .clone();

    let rig = Rig::start(harness).await;
    let mut shell = ShellScript {
        carrier: Arc::clone(&rig.endpoints.shell),
        node: rig.endpoints.node.clone(),
        sessions: HashMap::new(),
    };
    let Rig { endpoints, links } = rig;
    let mut node = NodeScript {
        links,
        expected_peer: shell.carrier.local(),
        sessions: HashMap::new(),
    };
    let both = bounded(async {
        let (shell_outcome, node_outcome) = tokio::join!(shell.run(&steps), node.run(&steps));
        Ok((shell_outcome, node_outcome))
    })
    .await;
    let (shell_outcome, node_outcome) = match both {
        Ok(outcomes) => outcomes,
        Err(failure) => (Err(failure.clone()), Err(failure)),
    };
    (endpoints.stop)().await;
    match (node_outcome, shell_outcome) {
        (Err(failure), _) => Err(format!("{name}: node: {failure}")),
        (Ok(()), Err(failure)) => Err(format!("{name}: shell: {failure}")),
        (Ok(()), Ok(())) => Ok(()),
    }
}

struct ShellSession {
    link: Arc<dyn Link>,
    inbound: Box<dyn crate::InboundSource>,
}

struct ShellScript {
    carrier: Arc<dyn Carrier>,
    node: NodeAddr,
    sessions: HashMap<String, ShellSession>,
}

impl ShellScript {
    async fn run(&mut self, steps: &[Value]) -> Outcome {
        for (index, step) in steps.iter().enumerate() {
            if let Err(failure) = self.step(step).await {
                for session in self.sessions.values() {
                    session.link.close(mismatch());
                }
                return Err(format!("step {index} ({}): {failure}", step["op"]));
            }
        }
        Ok(())
    }

    fn session(&mut self, step: &Value) -> Result<&mut ShellSession, String> {
        let label = label(step)?;
        self.sessions
            .get_mut(&label)
            .ok_or_else(|| format!("no open session {label}"))
    }

    async fn step(&mut self, step: &Value) -> Outcome {
        match step["op"].as_str() {
            Some("open") => self.open(step).await,
            Some("request") => {
                let frame = field(step, "frame")?.clone();
                let replies = replies(step)?;
                let session = self.session(step)?;
                session
                    .link
                    .send_control(frame_of(&frame))
                    .await
                    .map_err(|error| format!("send: {error}"))?;
                expect_replies(session.inbound.as_mut(), replies).await
            }
            Some("call") => {
                let link = Arc::clone(&self.session(step)?.link);
                one_call(link, step).await
            }
            Some("calls") => {
                let link = Arc::clone(&self.session(step)?.link);
                let mut calls = tokio::task::JoinSet::new();
                for call in field(step, "calls")?
                    .as_array()
                    .ok_or("calls is not a list")?
                {
                    calls.spawn(one_call(Arc::clone(&link), call.clone()));
                }
                while let Some(joined) = calls.join_next().await {
                    joined.map_err(|error| format!("call task: {error}"))??;
                }
                Ok(())
            }
            Some("resume") => {
                let link = Arc::clone(&self.session(step)?.link);
                let calls = field(step, "calls")?
                    .as_array()
                    .ok_or("calls is not a list")?;
                for call in calls {
                    one_call(Arc::clone(&link), call).await?;
                }
                if let (Some(refused), Some(last)) = (step["expect"].get("refused"), calls.last()) {
                    let response = &last["response"];
                    if response["success"] != false || &response["error"] != refused {
                        return Err(format!("expected a refusal {refused}, got {response}"));
                    }
                }
                Ok(())
            }
            Some("close") => {
                let session = self.session(step)?;
                let closed: LinkClosure = AppClose::closed().into();
                session.link.close(AppClose::closed());
                if session.link.closure() != Some(closed.clone()) {
                    return Err(format!(
                        "closure after close() is {:?}, not 0 closed",
                        session.link.closure()
                    ));
                }
                match session
                    .link
                    .send_control(Frame::new("{\"type\":\"sync\"}"))
                    .await
                {
                    Err(CarrierError::Closed(closure)) if closure == closed => {}
                    other => return Err(format!("a send after close returned {other:?}")),
                }
                match session.link.open_call(Frame::new("{}")).await {
                    Err(CarrierError::Closed(_)) => Ok(()),
                    other => Err(format!("open_call after close returned {other:?}")),
                }
            }
            other => Err(format!("unknown op {other:?}")),
        }
    }

    async fn open(&mut self, step: &Value) -> Outcome {
        let label = label(step)?;
        let mut dialed = within("dial", self.carrier.dial(&self.node, session_alpn()))
            .await?
            .map_err(|error| format!("dial: {error}"))?;
        let expected_node = VerifiedPeer::Node(self.node.id);
        if dialed.link.remote() != expected_node {
            return Err(format!(
                "link reports node {}, dialled {expected_node}",
                dialed.link.remote()
            ));
        }
        dialed
            .link
            .send_control(frame_of(field(step, "expect_frame")?))
            .await
            .map_err(|error| format!("send auth: {error}"))?;
        let replies = replies(step)?;
        let expect = field(step, "expect")?;
        if let Some(reason) = expect.get("auth_rejected") {
            let (_, reply) = replies.first().ok_or("a refusal needs a reply")?;
            match within("auth_response", dialed.inbound.recv()).await? {
                Some((Lane::Control, frame)) => expect_frame(&frame, reply, "auth_response")?,
                Some(other) => return Err(format!("expected the refusal, got {other:?}")),
                None => {
                    let closure = dialed.link.closure();
                    let allowed = expect.get("or_closed").map(|closed| {
                        LinkClosure::App(AppClose::new(
                            closed["code"].as_u64().unwrap_or_default(),
                            closed["reason"].as_str().unwrap_or_default(),
                        ))
                    });
                    if allowed.is_none() || closure != allowed {
                        return Err(format!(
                            "link ended {closure:?} before the refusal {reason}"
                        ));
                    }
                }
            }
            // The session closes the link on a refusal.
            dialed.link.close(AppClose::closed());
        } else {
            let (_, reply) = replies.first().ok_or("an open needs a reply")?.clone();
            expect_replies(dialed.inbound.as_mut(), replies).await?;
            check_auth_expectation(&reply, expect)?;
        }
        self.sessions.insert(
            label,
            ShellSession {
                link: dialed.link,
                inbound: dialed.inbound,
            },
        );
        Ok(())
    }
}

/// Checks the fixture's own consistency: the accepted reply carries what `expect` says.
fn check_auth_expectation(reply: &Value, expect: &Value) -> Outcome {
    if reply["capabilities"] != expect["capabilities"] {
        return Err(format!(
            "capabilities {} do not match the expectation {}",
            reply["capabilities"], expect["capabilities"]
        ));
    }
    let paired = reply["paired"].as_bool().unwrap_or(false);
    if Some(paired) != expect["paired"].as_bool() {
        return Err(format!("paired is {paired}, expected {}", expect["paired"]));
    }
    if let Some(supports) = expect.get("supports").and_then(Value::as_object) {
        let capabilities = reply["capabilities"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        for (capability, expected) in supports {
            let has = capabilities.iter().any(|c| c == capability);
            if Some(has) != expected.as_bool() {
                return Err(format!(
                    "supports({capability}) is {has}, expected {expected}"
                ));
            }
        }
    }
    Ok(())
}

/// Reads inbound frames until every expected reply arrived, each lane in its own order.
async fn expect_replies(
    inbound: &mut dyn crate::InboundSource,
    replies: Vec<(Lane, Value)>,
) -> Outcome {
    let mut control: VecDeque<Value> = VecDeque::new();
    let mut push: VecDeque<Value> = VecDeque::new();
    for (lane, frame) in replies {
        match lane {
            Lane::Control => control.push_back(frame),
            Lane::Push => push.push_back(frame),
        }
    }
    while !control.is_empty() || !push.is_empty() {
        let (lane, frame) = within("reply", inbound.recv())
            .await?
            .ok_or("the link ended before every reply arrived")?;
        let queue = match lane {
            Lane::Control => &mut control,
            Lane::Push => &mut push,
        };
        let expected = queue
            .pop_front()
            .ok_or_else(|| format!("unexpected {lane:?} frame {}", frame.as_str()))?;
        expect_frame(&frame, &expected, &format!("{lane:?} reply"))?;
    }
    Ok(())
}

/// One call from the shell: open the lane with the request, finish sending, expect the response,
/// then the lane's end.
async fn one_call(link: Arc<dyn Link>, call: impl std::borrow::Borrow<Value>) -> Outcome {
    let call = call.borrow();
    let request = field(call, "expect_frame")?;
    let response = field(call, "response")?;
    let mut lane = link
        .open_call(frame_of(request))
        .await
        .map_err(|error| format!("open_call: {error}"))?;
    lane.sink
        .finish()
        .await
        .map_err(|error| format!("finish: {error}"))?;
    let received = within("call response", lane.source.recv())
        .await?
        .ok_or_else(|| format!("call {} ended without a response", request["request_id"]))?;
    expect_frame(&received, response, "call response")?;
    match within("call lane end", lane.source.recv()).await? {
        None => Ok(()),
        Some(extra) => Err(format!("unexpected frame after the response: {extra:?}")),
    }
}

struct NodeSession {
    link: Arc<dyn PeerLink>,
    control: Box<dyn crate::FrameSource>,
    calls: Box<dyn crate::CallSource>,
}

struct NodeScript {
    links: mpsc::UnboundedReceiver<AcceptedLink>,
    expected_peer: VerifiedPeer,
    sessions: HashMap<String, NodeSession>,
}

impl NodeScript {
    async fn run(&mut self, steps: &[Value]) -> Outcome {
        for (index, step) in steps.iter().enumerate() {
            if let Err(failure) = self.step(step).await {
                for session in self.sessions.values() {
                    session.link.close(mismatch());
                }
                return Err(format!("step {index} ({}): {failure}", step["op"]));
            }
        }
        Ok(())
    }

    fn session(&mut self, step: &Value) -> Result<&mut NodeSession, String> {
        let label = label(step)?;
        self.sessions
            .get_mut(&label)
            .ok_or_else(|| format!("no accepted session {label}"))
    }

    async fn step(&mut self, step: &Value) -> Outcome {
        match step["op"].as_str() {
            Some("open") => self.open(step).await,
            Some("request") => {
                let frame = field(step, "frame")?.clone();
                let replies = replies(step)?;
                let session = self.session(step)?;
                let received = within("request at node", session.control.recv())
                    .await?
                    .ok_or("the control lane ended")?;
                expect_frame(&received, &frame, "request")?;
                send_replies(session.link.as_ref(), replies).await
            }
            Some("call") => {
                let session = self.session(step)?;
                let mut lane = accept_call(session, step).await?;
                answer_call(&mut lane, step).await
            }
            Some("calls") => {
                let calls = field(step, "calls")?
                    .as_array()
                    .ok_or("calls is not a list")?
                    .clone();
                let session = self.session(step)?;
                answer_concurrent_calls(session, &calls).await
            }
            Some("resume") => {
                let calls = field(step, "calls")?
                    .as_array()
                    .ok_or("calls is not a list")?
                    .clone();
                let session = self.session(step)?;
                for call in &calls {
                    let mut lane = accept_call(session, call).await?;
                    answer_call(&mut lane, call).await?;
                }
                Ok(())
            }
            Some("close") => {
                let session = self.session(step)?;
                let closure = within("close at node", session.link.closed()).await?;
                if closure == AppClose::closed().into() {
                    Ok(())
                } else {
                    Err(format!("the node saw {closure}, not 0 closed"))
                }
            }
            other => Err(format!("unknown op {other:?}")),
        }
    }

    async fn open(&mut self, step: &Value) -> Outcome {
        let label = label(step)?;
        let mut accepted = within("accept", self.links.recv())
            .await?
            .ok_or("the node accepted no link")?;
        if accepted.link.remote() != self.expected_peer {
            return Err(format!(
                "link reports peer {}, the shell is {}",
                accepted.link.remote(),
                self.expected_peer
            ));
        }
        let auth = field(step, "expect_frame")?;
        let received = within("auth at node", accepted.control.recv())
            .await?
            .ok_or("no auth frame at the node")?;
        expect_frame(&received, auth, "auth")?;
        let replies = replies(step)?;
        let accepted_auth = replies
            .first()
            .is_some_and(|(_, reply)| reply["success"] == true);
        if accepted_auth {
            let capabilities: Vec<String> = auth["capabilities"]
                .as_array()
                .map(|list| {
                    list.iter()
                        .filter_map(|c| c.as_str().map(str::to_owned))
                        .collect()
                })
                .unwrap_or_default();
            accepted.link.on_peer_capabilities(&capabilities);
        }
        send_replies(accepted.link.as_ref(), replies).await?;
        if let Some(close) = step.get("node_close") {
            accepted.link.close(AppClose::new(
                close["code"].as_u64().unwrap_or_default(),
                close["reason"].as_str().unwrap_or_default(),
            ));
        } else if field(step, "expect")?.get("auth_rejected").is_some() {
            within("refused session close", accepted.link.closed()).await?;
        }
        self.sessions.insert(
            label,
            NodeSession {
                link: accepted.link,
                control: accepted.control,
                calls: accepted.calls,
            },
        );
        Ok(())
    }
}

async fn send_replies(link: &dyn PeerLink, replies: Vec<(Lane, Value)>) -> Outcome {
    for (lane, frame) in replies {
        let sent = match lane {
            Lane::Control => link.send_control(frame_of(&frame)).await,
            Lane::Push => link.push(frame_of(&frame)).await,
        };
        sent.map_err(|error| format!("send {lane:?} reply: {error}"))?;
    }
    Ok(())
}

/// Accepts the next call lane and matches its request.
async fn accept_call(session: &mut NodeSession, call: &Value) -> Result<CallLane, String> {
    let mut lane = within("call lane", session.calls.accept())
        .await?
        .ok_or("the link ended before the call lane")?;
    let received = within("call request", lane.source.recv())
        .await?
        .ok_or("the call lane ended before its request")?;
    expect_frame(&received, field(call, "expect_frame")?, "call request")?;
    Ok(lane)
}

/// Answers a call lane and expects the shell's half to end.
async fn answer_call(lane: &mut CallLane, call: &Value) -> Outcome {
    lane.sink
        .send(frame_of(field(call, "response")?))
        .await
        .map_err(|error| format!("send response: {error}"))?;
    lane.sink
        .finish()
        .await
        .map_err(|error| format!("finish: {error}"))?;
    match within("shell half end", lane.source.recv()).await? {
        None => Ok(()),
        Some(extra) => Err(format!("unexpected frame on the call lane: {extra:?}")),
    }
}

/// Accepts every call of a `calls` step (in whatever order the lanes arrive), then answers each
/// only after the calls its `answer_after` names.
async fn answer_concurrent_calls(session: &mut NodeSession, calls: &[Value]) -> Outcome {
    let mut lanes: HashMap<String, (CallLane, &Value)> = HashMap::new();
    for _ in calls {
        let mut lane = within("call lane", session.calls.accept())
            .await?
            .ok_or("the link ended before every call lane")?;
        let request = within("call request", lane.source.recv())
            .await?
            .ok_or("a call lane ended before its request")?;
        let parsed: Value = serde_json::from_str(request.as_str())
            .map_err(|error| format!("call request is not JSON: {error}"))?;
        let id = parsed["request_id"].as_str().unwrap_or_default().to_owned();
        let call = calls
            .iter()
            .find(|call| call["expect_frame"]["request_id"] == id.as_str())
            .ok_or_else(|| format!("no call in the script has request_id {id}"))?;
        expect_frame(&request, &call["expect_frame"], "call request")?;
        lanes.insert(id, (lane, call));
    }
    let mut answered: BTreeSet<String> = BTreeSet::new();
    while answered.len() < calls.len() {
        let next = lanes
            .iter()
            .find(|(id, (_, call))| {
                !answered.contains(*id)
                    && call["answer_after"].as_array().is_none_or(|after| {
                        after
                            .iter()
                            .all(|dep| dep.as_str().is_some_and(|dep| answered.contains(dep)))
                    })
            })
            .map(|(id, _)| id.clone())
            .ok_or("answer_after names a call that is never answered")?;
        let (lane, call) = lanes.get_mut(&next).ok_or("lost a call lane")?;
        let call = *call;
        answer_call(lane, call).await?;
        answered.insert(next);
    }
    Ok(())
}

/// Rules 7 and 9: the node closes with `4403 peer_revoked` while a call lane is open. Both ends see
/// that closure, the open lane completes, and every later write fails `Closed`.
///
/// # Errors
///
/// The first broken expectation.
pub async fn a_node_close_ends_open_calls_and_refuses_later_writes(
    harness: &dyn ContractHarness,
) -> Outcome {
    let mut rig = Rig::start(harness).await;
    let outcome = bounded(node_close_body(&mut rig)).await;
    rig.stop().await;
    outcome
}

async fn node_close_body(rig: &mut Rig) -> Outcome {
    let (mut dialed, mut accepted) = rig.open_simple().await?;
    let request = json!({"type": "admin_rpc", "request_id": "r-2", "method": "health.check"});
    let mut shell_lane = dialed
        .link
        .open_call(frame_of(&request))
        .await
        .map_err(|error| format!("open_call: {error}"))?;
    let mut node_lane = within("call lane", accepted.calls.accept())
        .await?
        .ok_or("no call lane at the node")?;
    let received = within("call request", node_lane.source.recv())
        .await?
        .ok_or("no request on the call lane")?;
    expect_frame(&received, &request, "call request")?;

    let revoked: LinkClosure = Closure::PEER_REVOKED.into();
    accepted.link.close(Closure::PEER_REVOKED.into());
    if accepted.link.closure() != Some(revoked.clone()) {
        return Err(format!(
            "node closure after close() is {:?}",
            accepted.link.closure()
        ));
    }
    let shell_saw = within("shell closure", dialed.link.closed()).await?;
    if shell_saw != revoked {
        return Err(format!("the shell saw {shell_saw}, not {revoked}"));
    }
    if let Some(frame) = within("shell call lane end", shell_lane.source.recv()).await? {
        return Err(format!(
            "the open call lane delivered {frame:?} after the close"
        ));
    }
    if let Some(frame) = within("inbound end", dialed.inbound.recv()).await? {
        return Err(format!("inbound delivered {frame:?} after the close"));
    }
    if within("calls end", accepted.calls.accept())
        .await?
        .is_some()
    {
        return Err("the node accepted a call lane after the close".to_owned());
    }
    if within("control end", accepted.control.recv())
        .await?
        .is_some()
    {
        return Err("the control lane delivered a frame after the close".to_owned());
    }

    let probe = || Frame::new("{\"type\":\"sync\"}");
    let writes: Vec<(&str, Result<(), CarrierError>)> = vec![
        (
            "shell send_control",
            dialed.link.send_control(probe()).await,
        ),
        (
            "shell open_call",
            dialed.link.open_call(probe()).await.map(|_| ()),
        ),
        ("shell call send", shell_lane.sink.send(probe()).await),
        (
            "node send_control",
            accepted.link.send_control(probe()).await,
        ),
        ("node push", accepted.link.push(probe()).await),
        ("node call send", node_lane.sink.send(probe()).await),
    ];
    for (what, result) in writes {
        if !matches!(result, Err(CarrierError::Closed(_))) {
            return Err(format!("{what} after the close returned {result:?}"));
        }
    }
    Ok(())
}

/// Rule 11: after the node reports `frame_part`, frames larger than one transport frame (1.5 MiB)
/// cross whole and unchanged in both directions.
///
/// # Errors
///
/// The first broken expectation.
pub async fn peer_capabilities_reach_the_carrier(harness: &dyn ContractHarness) -> Outcome {
    let mut rig = Rig::start(harness).await;
    let outcome = bounded(large_frames_body(&mut rig)).await;
    rig.stop().await;
    outcome
}

async fn large_frames_body(rig: &mut Rig) -> Outcome {
    let (mut dialed, mut accepted) = rig.open_simple().await?;
    let payload = "x".repeat(1_572_864);
    let push = json!({"type": "blob", "data": payload});
    accepted
        .link
        .push(frame_of(&push))
        .await
        .map_err(|error| format!("push: {error}"))?;
    match within("large push", dialed.inbound.recv()).await? {
        Some((Lane::Push, frame)) => expect_frame(&frame, &push, "large push")?,
        other => {
            return Err(format!(
                "expected the large push, got {:?}",
                other.map(|(lane, frame)| (lane, frame.len()))
            ));
        }
    }
    let control = json!({"type": "blob_upload", "request_id": "r-3", "data": payload});
    // A stream transport applies backpressure, so the node reads while the shell writes.
    let (sent, received) = tokio::join!(
        dialed.link.send_control(frame_of(&control)),
        within("large control frame", accepted.control.recv())
    );
    sent.map_err(|error| format!("send_control: {error}"))?;
    let received = received?.ok_or("the control lane ended before the large frame")?;
    expect_frame(&received, &control, "large control frame")?;
    dialed.link.close(AppClose::closed());
    Ok(())
}

/// Rule 7: a transport dropped under an open link is [`LinkClosure::Lost`] at both ends. Passes
/// without running when the harness cannot drop its transport.
///
/// # Errors
///
/// The first broken expectation.
pub async fn a_transport_loss_is_lost(harness: &dyn ContractHarness) -> Outcome {
    let mut rig = Rig::start(harness).await;
    let outcome = bounded(loss_body(&mut rig)).await;
    rig.stop().await;
    outcome
}

async fn loss_body(rig: &mut Rig) -> Outcome {
    if rig.endpoints.sever.is_none() {
        return Ok(());
    }
    let (dialed, accepted) = rig.open_simple().await?;
    if let Some(sever) = &rig.endpoints.sever {
        sever();
    }
    let shell_saw = within("shell loss", dialed.link.closed()).await?;
    let node_saw = within("node loss", accepted.link.closed()).await?;
    if shell_saw != LinkClosure::Lost || node_saw != LinkClosure::Lost {
        return Err(format!(
            "expected lost at both ends, saw {shell_saw} and {node_saw}"
        ));
    }
    Ok(())
}

/// Rule 3: dialling a well-formed address where no node answers fails `Unreachable`.
///
/// # Errors
///
/// When the dial does anything else.
pub async fn dialing_an_address_where_no_node_answers_is_unreachable(
    harness: &dyn ContractHarness,
) -> Outcome {
    let rig = Rig::start(harness).await;
    let result = within(
        "unreachable dial",
        rig.endpoints
            .shell
            .dial(&rig.endpoints.unreachable, session_alpn()),
    )
    .await;
    rig.stop().await;
    match result? {
        Err(CarrierError::Unreachable(_)) => Ok(()),
        Err(other) => Err(format!("expected Unreachable, got {other}")),
        Ok(_) => Err("dialling an address where no node answers succeeded".to_owned()),
    }
}

/// The access hook: a peer outside the membership is refused `4403 peer_not_allowed` before any
/// handler runs, either at the dial or as the link's closure. Passes without running when the
/// harness has no second identity.
///
/// # Errors
///
/// When the outsider reached the handler or saw another outcome.
pub async fn a_non_member_is_refused_before_any_handler(harness: &dyn ContractHarness) -> Outcome {
    let mut rig = Rig::start(harness).await;
    let outcome = bounded(non_member_body(&mut rig)).await;
    rig.stop().await;
    outcome
}

async fn non_member_body(rig: &mut Rig) -> Outcome {
    let Some(outsider) = rig.endpoints.outsider.clone() else {
        return Ok(());
    };
    let refused: LinkClosure = Closure::PEER_NOT_ALLOWED.into();
    match within(
        "outsider dial",
        outsider.dial(&rig.endpoints.node, session_alpn()),
    )
    .await?
    {
        Err(CarrierError::Closed(closure)) if closure == refused => {}
        Err(other) => return Err(format!("the outsider's dial failed with {other}")),
        Ok(dialed) => {
            // Some transports finish the dial before the node's hook runs; the frame must never
            // reach a handler and the link must end with the refusal.
            let _ = dialed
                .link
                .send_control(Frame::new("{\"type\":\"auth\",\"request_id\":\"o-1\"}"))
                .await;
            let closure = within("outsider closure", dialed.link.closed()).await?;
            if closure != refused {
                return Err(format!(
                    "the outsider's link ended {closure}, not {refused}"
                ));
            }
        }
    }
    match rig.links.try_recv() {
        Ok(_) => Err("the outsider's link reached the protocol handler".to_owned()),
        Err(_) => Ok(()),
    }
}

/// The in-process carrier as a [`ContractHarness`]: the node, the shell and an outsider on one
/// [`InProcessNetwork`], each with its own NodeId.
#[derive(Clone, Copy, Debug, Default)]
pub struct InProcessHarness;

impl InProcessHarness {
    /// The suite node's id.
    pub const NODE: NodeId = NodeId::from_bytes([0x11; 32]);
    /// The shell's id.
    pub const SHELL: NodeId = NodeId::from_bytes([0x22; 32]);
    /// The outsider's id.
    pub const OUTSIDER: NodeId = NodeId::from_bytes([0x33; 32]);
    /// An id nobody serves.
    pub const NOBODY: NodeId = NodeId::from_bytes([0x44; 32]);
}

#[async_trait]
impl ContractHarness for InProcessHarness {
    async fn start(&self, protocols: Protocols) -> HarnessEndpoints {
        let network = InProcessNetwork::new();
        network.serve(Self::NODE, protocols);
        let sever_network = network.clone();
        HarnessEndpoints {
            shell: Arc::new(network.carrier(VerifiedPeer::Node(Self::SHELL))),
            outsider: Some(Arc::new(
                network.carrier(VerifiedPeer::Node(Self::OUTSIDER)),
            )),
            node: NodeAddr::new(Self::NODE),
            unreachable: NodeAddr::new(Self::NOBODY),
            sever: Some(Box::new(move || {
                sever_network.sever_all();
            })),
            stop: Box::new(move || {
                Box::pin(async move {
                    network.stop(&Self::NODE);
                })
            }),
        }
    }
}

/// Declares one `#[tokio::test]` per contract scenario and rule test for a harness expression.
///
/// The calling crate needs `tokio` with the `macros` and `rt-multi-thread` features.
#[macro_export]
macro_rules! node_carrier_contract_tests {
    ($harness:expr) => {
        $crate::node_carrier_contract_tests!(@scenario $harness, scenario_auth, "auth");
        $crate::node_carrier_contract_tests!(@scenario $harness, scenario_capability_gate, "capability_gate");
        $crate::node_carrier_contract_tests!(@scenario $harness, scenario_admin_rpc, "admin_rpc");
        $crate::node_carrier_contract_tests!(@scenario $harness, scenario_turn, "turn");
        $crate::node_carrier_contract_tests!(@scenario $harness, scenario_detach, "detach");
        $crate::node_carrier_contract_tests!(@scenario $harness, scenario_feed_resume, "feed_resume");
        $crate::node_carrier_contract_tests!(@rule $harness, a_node_close_ends_open_calls_and_refuses_later_writes);
        $crate::node_carrier_contract_tests!(@rule $harness, peer_capabilities_reach_the_carrier);
        $crate::node_carrier_contract_tests!(@rule $harness, a_transport_loss_is_lost);
        $crate::node_carrier_contract_tests!(@rule $harness, dialing_an_address_where_no_node_answers_is_unreachable);
        $crate::node_carrier_contract_tests!(@rule $harness, a_non_member_is_refused_before_any_handler);

        #[test]
        fn the_suite_runs_every_fixture_scenario() {
            let listed = $crate::contract::fixture_scenarios().expect("fixture");
            assert_eq!(listed, $crate::contract::SCENARIOS.to_vec());
        }
    };
    (@scenario $harness:expr, $test:ident, $name:literal) => {
        #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
        async fn $test() {
            $crate::contract::check($crate::contract::run_scenario(&$harness, $name).await);
        }
    };
    (@rule $harness:expr, $test:ident) => {
        #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
        async fn $test() {
            $crate::contract::check($crate::contract::$test(&$harness).await);
        }
    };
}
