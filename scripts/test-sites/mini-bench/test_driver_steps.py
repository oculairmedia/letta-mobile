"""Offline call trace recorded from the complete 812c7a73e trial driver."""

from contextlib import ExitStack
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import xml.etree.ElementTree as ET

import pytest
from coreloop import capture, scenarios
from tool_boundary import Tool


class Process:
    returncode = 1

    def __init__(self):
        self.stdin = self
        self.stdout = self

    def poll(self):
        return None

    def write(self, value):
        return None

    def flush(self):
        return None

    def readline(self):
        return "1000\n"

    def close(self):
        return None

    def wait(self, timeout):
        return None


def stable_arg(value):
    if isinstance(value, ET.Element):
        return "<UI hierarchy>"
    if hasattr(value, "render"):
        value = value.render()
    return str(value)


class Trace:
    def __init__(self, root):
        self.root = root
        self.calls = []
        self.tick = 0
        self.tree = ET.Element("hierarchy")

    def record(self, name, args):
        values = [stable_arg(value).replace(str(self.root), "<ROOT>") for value in args]
        self.calls.append([name, values])

    def noop(self, name):
        def call(*args, **kwargs):
            self.record(name, args)

        return call

    def adb(self, *args):
        self.record("adb", args)
        if args[:2] == ("shell", "date"):
            return "1000"
        if args[:2] == (
            "shell",
            "screenrecord --time-limit 180 /sdcard/core-loop.mp4 >/sdcard/core-loop-screenrecord.log 2>&1 & echo $!",
        ):
            return "123"
        return "1"

    def command(self, argv):
        self.record("command", argv)
        if is_remote_audit(argv):
            return json.dumps(dict(conversation_id="fixture", modelLogOffset=0)).encode()
        return b"{}"

    def run_tool(self, tool, args=(), **kwargs):
        argv = [Path(tool.value).name, *map(stable_arg, args)] if isinstance(tool, Tool) else tool
        self.record("run_tool", argv)
        if "stdout" in kwargs and hasattr(kwargs["stdout"], "write"):
            kwargs["stdout"].write(json.dumps(dict(wallMs=100000, line="turn.finished_relayed conversationId=fixture stopReason=end_turn"))+"\n")
            kwargs["stdout"].flush()
        return Process()

    def dump(self, *args):
        self.record("dump", args)
        return self.tree

    def clock(self):
        self.tick += 1
        return self.tick * 10


def trace_trial(root, scenario, module=capture, step_module=scenarios):
    trace = Trace(root)
    replacements = dict(
        wait_for_pixel=trace.noop("wait_for_pixel"),
        adb=trace.adb,
        installed_app_hash=lambda: "hash",
        command=trace.command,
        select_conversation=trace.noop("select_conversation"),
        run_tool=trace.run_tool,
        wall_ms=trace.clock,
        dump=trace.dump,
        reply_region=lambda *args: "1,2,3,4",
        send=lambda *args, **kwargs: 100,
        tap_node=trace.noop("tap_node"),
        restore_network=trace.noop("restore_network"),
        stop_process=lambda process: None,
    )
    with ExitStack() as stack:
        for name, value in replacements.items():
            stack.enter_context(patch.object(module, name, value, create=True))
        patch_scenario(stack, step_module, replacements)
        stack.enter_context(patch.object(module, "parse_log", return_value=[{"key": "existing"}]))
        stack.enter_context(patch.object(module, "analyze_capture", return_value={}))
        stack.enter_context(patch.object(module.select, "select", return_value=([1], [], [])))
        stack.enter_context(patch.object(module.time, "sleep", trace.noop("sleep")))
        args = SimpleNamespace(output=root, apk_sha256="hash", region=None, capture_id="fixture")
        module.trial(args, scenario, 2)
    return trace.calls


def patch_scenario(stack, module, replacements):
    for name in ("adb", "wall_ms", "dump", "reply_region", "send", "tap_node", "restore_network"):
        stack.enter_context(patch.object(module, name, replacements[name]))


@pytest.mark.parametrize("scenario", ("S1", "S2", "S3", "S4"))
def test_complete_driver_steps(tmp_path, scenario):
    expected = json.loads(Path(__file__).with_name("driver-steps.json").read_text())[scenario]
    assert trace_trial(tmp_path, scenario) == expected


def is_remote_audit(argv):
    return argv[:1] == ["ssh"] and "-c" in argv and "tail" not in argv
