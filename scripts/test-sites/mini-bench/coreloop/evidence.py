from .config import trial_prompt
from dataclasses import dataclass, field
import json
from pathlib import Path
import re
from . import scoring as oracle


def log_fields(parts):
    fields = {}
    for part in parts:
        key, separator, value = part.partition("=")
        if valid_log_field(key, separator, value):
            fields[key] = value
    return fields


def parse_line(line):
    words = line.lstrip().split(maxsplit=1)
    if not words or not re.fullmatch("[0-9]+\\.[0-9]+", words[0]):
        return None
    positions = log_markers(line)
    if not positions:
        return None
    position, marker = min(positions)
    parts = line[position + len(marker) :].split()
    if not parts or parts[0] not in ("mount", "dispose", "draw", "resident"):
        return None
    return dict(ms=float(words[0]) * 1000, event=parts[0], **log_fields(parts[1:]))


def parse_log(path):
    parsed = map(parse_line, path.read_text(errors="replace").splitlines())
    return [event for event in parsed if event is not None]


def wrapper_terminals(directory, metadata):
    terminal = []
    wrapper_events = []
    for line in (directory / "wrapper.jsonl").read_text(errors="replace").splitlines():
        try:
            event = json.loads(line)
            wrapper_events.append(event)
            if (
                "turn.finished_relayed" in event["line"]
                and f"conversationId={metadata['conversationId']} " in event["line"]
            ):
                terminal.append(event)
        except (ValueError, KeyError):
            continue
    return (terminal, wrapper_events)


def update_state(state, event, value):
    if event["event"] == "dispose":
        state.pop(event["key"], None)
    else:
        state[event["key"]] = value


def assistant_identities(all_events, tracked_keys):
    parents, identities = {}, {}

    def root(value):
        parents.setdefault(value, value)
        while parents[value] != value:
            value = parents[value]
        return value

    for event in all_events:
        if event["event"] != "draw" or event["key"] not in tracked_keys:
            continue
        raw = event.get("identity")
        key = event["key"]
        if key in identities:
            parents[root(raw)] = root(identities[key])
        else:
            identities[key] = raw
    return {key: root(identity) for key, identity in identities.items()}


def row_frames(all_events, observed, initial_assistants):
    identities = assistant_identities(all_events, initial_assistants | {e["key"] for e in observed})
    state = {key: identities.get(key, key) for key in initial_assistants}
    frames = []
    for event in observed:
        update_state(state, event, identities.get(event["key"], event["key"]))
        frames.append(dict(ms=event["ms"], rows=[dict(key=k, id=v) for k, v in state.items()]))
    return list({frame["ms"]: frame for frame in frames}.values())


def role_keys(all_events, role):
    return {e["key"] for e in all_events if e["event"] == "draw" and e.get("assistant") == role}


def prior_state(all_events, start):
    prior = {}
    for event in all_events:
        if event["ms"] >= start:
            break
        update_state(prior, event, event)
    return prior


def duplicate_users(all_events, events, prior, users):
    identities = {e["key"]: e.get("identity") for e in all_events if e["event"] == "draw" and e["key"] in users}
    state = {key: identities[key] for key in set(prior) & users}
    duplicates = set()
    for event in events:
        if event["key"] in users:
            update_state(state, event, identities[event["key"]])
        if len(state.values()) != len(set(state.values())):
            duplicates.add(event["ms"])
    return duplicates


def classify_events(all_events, events, start):
    assistant, users = (role_keys(all_events, "true"), role_keys(all_events, "false"))
    prior = prior_state(all_events, start)
    unknown = set(prior) - assistant - users
    removed = [e["key"] for e in events if e["event"] == "dispose" and e["key"] in unknown]
    observed = [e for e in events if e.get("key") in assistant]
    duplicates = duplicate_users(all_events, events, prior, users)
    return (set(prior) & assistant, assistant - set(prior), unknown, removed, observed, duplicates)


def matching_stored(event, context):
    if context.last_terminal is None:
        return False
    return (
        is_draw(event)
        and event["ms"] >= context.last_terminal
        and is_new_stored_reply(event, context.new_keys, context.expected_hash)
    )


def is_draw(event):
    return event["event"] == "draw"


def run_id(terminal):
    return re.search("runId=(\\S+)", terminal["line"])


def live_run(event, run):
    return is_draw(event) and bool(run) and (event.get("identity") == run[1])


def stored_copy(observed, context):
    matches = stored_matches(observed, context)
    stored_keys = {e["key"] for e in matches}
    final_run = run_id(context.terminal[-1]) if context.terminal else None
    live_keys = live_keys_for_run(observed, final_run)
    preserved = stored_keys & live_keys
    if preserved:
        matches = [e for e in matches if e["key"] in preserved]
    elif len(stored_keys) != 1:
        matches = []
    return ([e["ms"] for e in matches], stored_keys, preserved)


def is_new_stored_reply(event, new_keys, expected_hash):
    return (
        event["key"] in new_keys
        and event.get("stored") == "true"
        and (str(expected_hash) in event.get("hashes", "").split(","))
    )


def has_partial_capture(scenario, terminals, metadata):
    return scenario == "S3" and terminals and metadata.get("interruptReferenceMs")


def network_outage(metadata):
    disabled, restored = (metadata.get("networkDisabledMs"), metadata.get("networkRestoredMs"))
    return restored - disabled if oracle.number(restored) and oracle.number(disabled) else None


def network_overlapped(context):
    disabled = context.metadata.get("networkDisabledMs")
    if not context.metadata.get("networkDisabled") or not oracle.number(disabled):
        return False
    if not oracle.number(context.last_terminal) or disabled >= context.last_terminal:
        return False
    return any((is_draw(e) and e.get("stored") == "false" and (e["ms"] < disabled) for e in context.observed))


def check_network(context, errors):
    if not context.metadata.get("networkDisabled"):
        errors.append("network loss not verified")
    outage = network_outage(context.metadata)
    if outage is None or outage < 5000:
        errors.append("network outage timing missing or too short")
    elif not network_overlapped(context):
        errors.append("network drop did not overlap observed live reply")


def held_tool(context):
    worker = [json.loads(line) for line in (context.directory / "worker.jsonl").read_text().splitlines()]
    worker = [e for e in worker if e.get("wallMs", 0) + context.metadata["miniToPhoneOffsetMs"] >= context.start]
    starts = {e["requestId"]: e["wallMs"] for e in worker if e.get("event") == "start"}
    durations = [e.get("seconds") for e in worker if e.get("event") == "start"]
    expected = None
    if len(durations) == 1 and oracle.number(durations[0]):
        expected = durations[0] * 1000
    held = held_durations(worker, starts)
    return (held, expected)


def has_stop(tree):
    return any((n.get("content-desc") == "Stop" for n in tree.iter("node")))


def new_stored(context):
    return any((is_new_stored_reply(e, context.new_keys, context.expected_hash) for e in context.observed))


def check_special_scenario(context, errors):
    scenario = context.metadata["scenario"]
    if cancelled_terminal_missing(context):
        errors.append("cancelled terminal not observed")
    if scenario == "S4":
        check_network(context, errors)
    if scenario != "S2":
        return ([], None)
    held, expected = held_tool(context)
    if not held_evidence_valid(held, expected):
        errors.append("current held tool start/end missing or hold too short")
    return (held, expected)


def scenario_checks(context):
    errors = ["initial row disposed without role evidence"] if context.unknown_removed else []
    held, expected = check_special_scenario(context, errors)
    if has_stop(context.tree):
        errors.append("Stop still visible in final UI")
    if not new_stored(context):
        errors.append("fixed reply not evidenced in a newly drawn stored row")
    return (errors, held, expected)


@dataclass
class Observation:
    directory: Path
    metadata: dict
    tree: object
    start: float = field(init=False)
    all_events: list = field(init=False)
    observed: list = field(init=False)
    terminal: list = field(init=False)
    wrapper_events: list = field(init=False)
    last_terminal: float = field(init=False)
    expected_hash: int = field(init=False)

    def __post_init__(self):
        m = self.metadata
        self.start = m.get("sendBeginPhoneMs", m.get("videoStartMs", m["sendMs"] + m["phoneOffsetMs"]))
        self.all_events = parse_log(self.directory / "logcat.txt")
        events = [e for e in self.all_events if e["ms"] >= self.start]
        roles = classify_events(self.all_events, events, self.start)
        self.initial, self.new_keys, self.unknown, self.unknown_removed, self.observed, self.duplicates = roles
        self.terminal, self.wrapper_events = wrapper_terminals(self.directory, m)
        self.last_terminal = self.terminal[-1]["wallMs"] + m["miniToPhoneOffsetMs"] if self.terminal else None
        self.expected = "DONE" if m["scenario"] == "S2" else oracle.FIXED_REPLY
        self.expected_hash = text_hash(self.expected)


def text_hash(text):
    value = 0
    for character in text:
        value = 31 * value + ord(character) & 4294967295
    return value - 4294967296 if value >= 2147483648 else value


def load_pixels(context):
    path = context.directory / "pixels" / "summary.json"
    pixels = json.loads(path.read_text()) if path.exists() else {}
    if context.metadata["scenario"] != "S3":
        return pixels
    partial_path = context.directory / "partial-pixels" / "summary.json"
    if not partial_path.exists():
        return {}
    partial = json.loads(partial_path.read_text())
    pixels["partial"] = partial
    pixels["minimum_template_correlation"] = min(
        pixels.get("minimum_template_correlation", 0),
        partial["minimum_template_correlation"],
    )
    pixels["dip_percent"] = max(pixels.get("dip_percent", 100), partial["dip_percent"])
    return pixels


def partial_incomplete(context, cancelled):
    if not cancelled:
        return False
    run = run_id(cancelled[0])
    cutoff = cancelled[0]["wallMs"] + context.metadata["miniToPhoneOffsetMs"]
    live = [e for e in context.observed if live_run(e, run) and e["ms"] <= cutoff]
    has_ink = any((e.get("hashes") not in (None, "", "0") for e in live))
    complete = any((str(context.expected_hash) in e.get("hashes", "").split(",") for e in live))
    return bool(live) and has_ink and (not complete)


def terminal_reason(terminal):
    return "end_turn" if terminal and "stopReason=end_turn" in terminal[-1]["line"] else None


def scenario_evidence(context, held, expected_hold):
    accepted = [
        e
        for e in context.wrapper_events
        if f"input.accepted conversationId={context.metadata['conversationId']} " in e["line"]
    ]
    cancelled = [e for e in context.terminal if "stopReason=cancelled" in e["line"]]
    following = []
    if cancelled:
        following = [e["wallMs"] - cancelled[0]["wallMs"] for e in accepted if e["wallMs"] >= cancelled[0]["wallMs"]]
    return dict(
        terminalReason=terminal_reason(context.terminal),
        inputCount=len(accepted),
        heldToolMs=max(held) if held else None,
        heldToolExpectedMs=expected_hold,
        cancelled=bool(cancelled),
        cancelledPartialIncomplete=partial_incomplete(context, cancelled),
        cancelToResendMs=min(following) if following else None,
        networkDownMs=network_outage(context.metadata),
        networkOverlappedLive=network_overlapped(context),
        newStoredTextObserved=new_stored(context),
    )


def node_top(node):
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return int(bounds[2]) if bounds else -1


def timeline_rows(tree):
    timelines = [n for n in tree.iter("node") if n.get("scrollable") == "true"]
    return [n for timeline in timelines for n in timeline.iter("node")
            if n.get("long-clickable") == "true"]


def row_texts(row):
    return [n.get("text") for n in row.iter("node")
            if n.get("text") and n.get("resource-id", "").split(":id/")[-1] != "chat-row-clock"]


def final_reply_text(tree, prompt):
    rows = [(node_top(row), row_texts(row)) for row in timeline_rows(tree)]
    users = [top for top, texts in rows if texts[:2] == ["You", prompt] and top >= 0]
    if not users:
        return ""
    prompt_top = max(users)
    replies = [(top, texts) for top, texts in rows if top > prompt_top and texts and texts[0] != "You"]
    _, texts = max(replies, default=(-1, []), key=lambda item: item[0])
    return "\n\n".join(texts)


def normalized_evidence(context, stored):
    prompt = trial_prompt(context.metadata, follow_up=context.metadata.get("scenario") == "S3")
    visible = final_reply_text(context.tree, prompt) if prompt else ""
    lifecycle = [dict(event="present", key=key) for key in sorted(context.initial)]
    lifecycle += [dict(event=e["event"], key=e["key"]) for e in context.observed if e["event"] in ("mount", "dispose")]
    return dict(
        scenario=context.metadata["scenario"],
        client="pixel2xl",
        terminalMs=context.last_terminal,
        initialUnclassifiedRows=len(context.unknown),
        duplicateUserRowSamples=len(context.duplicates),
        finalStopPresent=has_stop(context.tree),
        storedDisplayedMs=min(stored) if stored else None,
        stopGoneMs=context.metadata.get("stopGoneMs"),
        visibleText=visible,
        lifecycle=lifecycle,
        frames=row_frames(context.all_events, context.observed, context.initial),
        clockAligned=context.metadata["clockAligned"],
        pixels=load_pixels(context),
    )


def association(stored_keys, preserved):
    if preserved:
        return "preservedLiveKey"
    return "uniqueNewStoredText" if len(stored_keys) == 1 else "unresolved"


def normalize(directory, metadata, tree):
    context = Observation(directory, metadata, tree)
    stored, stored_keys, preserved = stored_copy(context.observed, context)
    evidence = normalized_evidence(context, stored)
    errors, held, expected = scenario_checks(context)
    details = scenario_evidence(context, held, expected)
    evidence["scenarioEvidence"] = details
    (directory / "evidence.json").write_text(json.dumps(evidence, indent=2))
    result = oracle.score(evidence)
    result["errors"] = sorted(set(result["errors"] + errors))
    result.update(
        storedCopyAssociation=association(stored_keys, preserved),
        matchingNewStoredRows=len(stored_keys),
        cancelledPartialIncomplete=details["cancelledPartialIncomplete"],
        cancelToResendMs=details["cancelToResendMs"],
        networkDownMs=details["networkDownMs"],
        initialUnclassifiedDisposals=len(context.unknown_removed),
        heldToolMs=details["heldToolMs"],
        status="FAIL" if result["errors"] else "PASS",
    )
    return result


def stored_matches(observed, context):
    return [e for e in observed if matching_stored(e, context)]


def live_keys_for_run(observed, final_run):
    return {e["key"] for e in observed if live_run(e, final_run) and e.get("stored") == "false"}


def held_durations(worker, starts):
    return [
        e["wallMs"] - starts[e["requestId"]] for e in worker if e.get("event") == "end" and e.get("requestId") in starts
    ]


def cancelled_terminal_missing(context):
    return context.metadata["scenario"] == "S3" and not any(
        "stopReason=cancelled" in e["line"] for e in context.terminal
    )


def log_markers(line):
    markers = ("CORE_LOOP ", "Telemetry/CoreLoop: ")
    return [(line.find(marker), marker) for marker in markers if line.find(marker) >= 0]


def valid_log_field(key, separator, value):
    return separator and re.fullmatch(r"\w+", key) and value


def held_evidence_valid(held, expected):
    return bool(held) and expected is not None and min(held) >= expected - 1000


def measured_row_events(directory, metadata, terminal):
    """Bind the pixel region to its run's live row and matching new stored copy."""
    events = parse_log(directory / "logcat.txt")
    keys = measured_live_keys(events, metadata, terminal)
    cutoff = terminal["wallMs"] + metadata["miniToPhoneOffsetMs"]
    expected = "DONE" if metadata["scenario"] == "S2" else oracle.FIXED_REPLY
    new_keys = role_keys(events, "true") - set(metadata["existingKeys"])
    stored = [e for e in events if is_draw(e) and e["ms"] >= cutoff
              and is_new_stored_reply(e, new_keys, text_hash(expected))]
    if "stopReason=end_turn" in terminal["line"]:
        keys.update(e["key"] for e in stored)
    return aligned_row_events(events, keys, metadata["videoStartMs"])


def aligned_row_events(events, keys, origin):
    row_events = [e for e in events if e.get("key") in keys and e["event"] in ("draw", "resident")]
    return dict(
        row_keys=sorted(keys),
        draw_times=sorted({(e["ms"] - origin) / 1000 for e in row_events if is_draw(e)}),
        log_times=sorted({(e["ms"] - origin) / 1000 for e in row_events}),
    )


def measured_live_keys(events, metadata, terminal):
    live_keys = live_keys_for_run(events, run_id(terminal))
    draws = [e for e in events if is_draw(e) and e["key"] in live_keys]
    if "stopReason=cancelled" in terminal["line"]:
        cutoff = metadata["interruptReferenceMs"]
        return {e["key"] for e in draws if e["ms"] <= cutoff and e.get("hashes") not in (None, "", "0")}
    expected = "DONE" if metadata["scenario"] == "S2" else oracle.FIXED_REPLY
    text_keys = {e["key"] for e in events if str(text_hash(expected)) in e.get("hashes", "").split(",")}
    return live_keys & text_keys
