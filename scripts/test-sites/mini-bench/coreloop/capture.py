from dataclasses import dataclass
import json
import shlex
import select
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from . import scoring as oracle
from tool_boundary import run_tool, tool_arguments, path_arg, numeric_arg, crop_arg
from .config import SERIAL, PACKAGE, SCENARIO_TIMEOUTS, HERE
from .device import (
    wait_for_pixel,
    adb,
    installed_app_hash,
    command,
    wall_ms,
    dump,
    send,
    restore_network,
    stop_process,
    reply_region,
)
from .fixture import select_conversation
from .remote import REMOTE_AUDIT, REMOTE_TAIL
from .scenarios import perform_scenario_step, ScenarioStep
from .evidence import parse_log, normalize, has_partial_capture, measured_row_events


@dataclass
class Capture:
    args: object
    scenario: str
    index: int

    def prepare(self):
        wait_for_pixel()
        self.directory = self.args.output / f"{self.scenario}-{self.index}"
        self.directory.mkdir(parents=True, exist_ok=False)
        self.wifi = adb("shell", "settings", "get", "global", "wifi_on")
        self.data = adb("shell", "settings", "get", "global", "mobile_data")
        if self.wifi not in ("0", "1") or self.data not in ("0", "1"):
            raise RuntimeError("cannot determine original network state")
        actual_apk_hash = installed_app_hash()
        if actual_apk_hash != self.args.apk_sha256:
            raise RuntimeError("installed APK changed; refusing to send from an unverified client")
        self.metadata = dict(
            scenario=self.scenario, trial=self.index, wifi=self.wifi, data=self.data, apkSha256=actual_apk_hash
        )
        self.scope = json.loads(
            command(["ssh", "-o", "BatchMode=yes", "graphiti", "python3", "-c", shlex.quote(REMOTE_AUDIT)])
        )
        self.metadata["fixtureScope"] = self.scope
        self.metadata["conversationId"] = self.scope["conversation_id"]
        self.logcat = self.wrapper = self.clock_process = None
        self.video_pid = None
        self.log_handle = (self.directory / "logcat.txt").open("w")
        self.wrapper_handle = (self.directory / "wrapper.jsonl").open("w")
        self.tree = None

    def collect(self):
        select_conversation(self.directory, self.scope)
        self.logcat = run_tool(
            *tool_arguments(["adb", "-s", SERIAL, "logcat", "-v", "epoch", "-T", "1"]),
            stdout=self.log_handle,
            stderr=subprocess.STDOUT,
            popen=True,
        )
        self.wrapper = run_tool(
            *tool_arguments(
                ["ssh", "-o", "BatchMode=yes", "graphiti", "python3", "-u", "-c", shlex.quote(REMOTE_TAIL)]
            ),
            stdout=self.wrapper_handle,
            stderr=subprocess.STDOUT,
            popen=True,
        )

    def phone_clock(self):
        begin = wall_ms()
        phone = float(adb("shell", "date", "+%s%3N"))
        end = wall_ms()
        self.metadata["phoneRttMs"] = end - begin
        self.metadata["phoneOffsetMs"] = phone - (begin + end) / 2

    def open_clock(self):
        self.clock_process = run_tool(
            *tool_arguments(
                [
                    "ssh",
                    "-o",
                    "BatchMode=yes",
                    "graphiti",
                    "python3",
                    "-u",
                    "-c",
                    shlex.quote("import sys,time\nfor line in sys.stdin: print(time.time_ns()/1000000,flush=True)"),
                ]
            ),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            popen=True,
        )

    def clock_sample(self):
        begin = wall_ms()
        self.clock_process.stdin.write("clock\n")
        self.clock_process.stdin.flush()
        if not select.select([self.clock_process.stdout], [], [], 10)[0]:
            raise RuntimeError("mini clock exchange timed out")
        mini = float(self.clock_process.stdout.readline())
        end = wall_ms()
        return dict(rttMs=end - begin, offsetMs=mini - (begin + end) / 2)

    def finish_clock(self):
        clock_samples = [self.clock_sample() for _ in range(4)]
        self.clock_process.stdin.close()
        self.clock_process.wait(timeout=10)
        best = min(clock_samples[1:], key=lambda sample: sample["rttMs"])
        self.metadata["clockSamples"] = clock_samples
        self.metadata["miniToPhoneOffsetMs"] = self.metadata["phoneOffsetMs"] - best["offsetMs"]
        self.metadata["miniRttMs"] = best["rttMs"]
        self.metadata["clockAligned"] = best["rttMs"] < 200 and self.metadata["phoneRttMs"] < 200

    def ready(self):
        self.metadata["simState"] = adb("shell", "getprop", "gsm.sim.state")
        time.sleep(1)
        if self.wrapper.poll() is not None or self.logcat.poll() is not None:
            raise RuntimeError("evidence collector exited before send")
        self.metadata["existingKeys"] = list({e["key"] for e in parse_log(self.directory / "logcat.txt")})
        if not self.metadata["existingKeys"]:
            pid = adb("shell", "pidof", PACKAGE)
            gate = adb("logcat", "-d", "--pid", pid, "-s", "Telemetry/CoreLoop:I")
            (self.directory / "capture-gate.log").write_text(gate)
            if "gate enabled=true" not in gate:
                raise RuntimeError(
                    "root-debug row capture is not verified; restart the capture APK with LettaCoreLoop VERBOSE"
                )

    def start_video(self):
        adb("shell", "rm", "-f", "/sdcard/core-loop.mp4")
        self.video_pid = adb(
            "shell",
            "screenrecord --time-limit 180 /sdcard/core-loop.mp4 >/sdcard/core-loop-screenrecord.log 2>&1 & echo $!",
        )
        time.sleep(0.3)
        if not self.video_pid.isdigit():
            raise RuntimeError("screenrecord PID missing")
        adb("shell", "kill", "-0", self.video_pid)
        self.metadata["videoStartMs"] = wall_ms() + self.metadata["phoneOffsetMs"]
        self.metadata["sendMs"] = send(self.directory, f"[core-loop:{self.scenario}] trial-{self.index}", "first")
        self.metadata["sendBeginPhoneMs"] = self.metadata["sendMs"] + self.metadata["phoneOffsetMs"]
        time.sleep(3)
        perform_scenario_step(
            ScenarioStep(self.directory, self.metadata, self.index, (self.wifi, self.data)), self.scenario
        )

    def wait_terminal(self):
        deadline = time.monotonic() + SCENARIO_TIMEOUTS[self.scenario]
        while time.monotonic() < deadline:
            self.tree = dump(self.directory, "latest")
            wall_ms()
            if "turn.finished_relayed" in (self.directory / "wrapper.jsonl").read_text(errors="replace") and (
                not any((n.get("content-desc") == "Stop" for n in self.tree.iter("node")))
            ):
                self.metadata["stopGoneMs"] = wall_ms() + self.metadata["phoneOffsetMs"]
                break
            time.sleep(1)

    def final_snapshot(self):
        time.sleep(6)
        self.tree = dump(self.directory, "final")
        (self.directory / "final.png").write_bytes(command(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"]))
        worker = command(
            [
                "ssh",
                "-o",
                "BatchMode=yes",
                "graphiti",
                "tail",
                "-n",
                "30",
                "/opt/letta-test-server/bench-fixture/worker.log",
            ]
        )
        (self.directory / "worker.jsonl").write_bytes(worker)
        model = command(
            [
                "ssh",
                "-o",
                "BatchMode=yes",
                "graphiti",
                "tail",
                "-c",
                "+" + str(self.scope["modelLogOffset"] + 1),
                "/opt/letta-test-server/bench-fixture/model.log",
            ]
        )
        (self.directory / "model.jsonl").write_bytes(model)

    def restore(self):
        try:
            self.metadata["restoredNetworking"] = restore_network(self.wifi, self.data)
        except Exception as error:
            self.cleanup_errors.append(str(error))

    def stop_recording(self):
        if not self.video_pid or not self.video_pid.isdigit():
            return
        try:
            adb("shell", "kill", "-2", self.video_pid)
            self.wait_recording()
            self.metadata["recordStopVerified"] = True
            command(
                [
                    "adb",
                    "-s",
                    SERIAL,
                    "pull",
                    "/sdcard/core-loop.mp4",
                    path_arg(self.directory / "screen.mp4", self.directory),
                ]
            )
        except Exception as error:
            self.cleanup_errors.append(str(error))

    def wait_recording(self):
        record_deadline = time.monotonic() + 10
        while time.monotonic() < record_deadline:
            alive = run_tool(
                *tool_arguments(["adb", "-s", SERIAL, "shell", "kill", "-0", self.video_pid]),
                capture_output=True,
                timeout=10,
            )
            if alive.returncode != 0:
                break
            time.sleep(0.25)
        else:
            adb("shell", "kill", "-9", self.video_pid)
            raise RuntimeError("screenrecord did not stop cleanly")

    def recorder_log(self):
        try:
            recorder_log = command(["adb", "-s", SERIAL, "exec-out", "cat", "/sdcard/core-loop-screenrecord.log"])
            (self.directory / "screenrecord.log").write_bytes(recorder_log)
        except Exception as error:
            self.cleanup_errors.append(str(error))

    def close_collectors(self):
        for process in (self.logcat, self.wrapper, self.clock_process):
            try:
                stop_process(process)
            except Exception as error:
                self.cleanup_errors.append(str(error))
        self.log_handle.close()
        self.wrapper_handle.close()
        self.metadata["cleanupErrors"] = self.cleanup_errors
        (self.directory / "metadata.json").write_text(json.dumps(self.metadata, indent=2))
        if self.cleanup_errors:
            raise RuntimeError("; ".join(self.cleanup_errors))

    def recover(self):
        if self.tree is None:
            raise RuntimeError("final UI missing")
        if any((n.get("content-desc") == "Stop" for n in self.tree.iter("node"))):
            self.metadata["recoveryAfterStuckSpinner"] = True
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-n", PACKAGE + "/com.letta.mobile.MainActivity")
            time.sleep(5)
            recovery = dump(self.directory, "recovery")
            self.metadata["recoveryStillShowsStop"] = any(
                (n.get("content-desc") == "Stop" for n in recovery.iter("node"))
            )
            (self.directory / "metadata.json").write_text(json.dumps(self.metadata, indent=2))

    def run(self):
        self.prepare()
        try:
            self.collect()
            self.phone_clock()
            self.open_clock()
            self.finish_clock()
            self.ready()
            self.start_video()
            self.wait_terminal()
            self.final_snapshot()
        finally:
            self.cleanup_errors = []
            self.restore()
            self.stop_recording()
            self.recorder_log()
            self.close_collectors()
        self.recover()
        return analyze_capture(self.directory, self.metadata, self.tree, self.args.region)


def trial(args, scenario, index):
    return Capture(args, scenario, index).run()


def analyze_final_pixels(directory, metadata, region, terminals):
    if terminals:
        write_row_evidence(directory, metadata, terminals[-1], "pixels")
        seconds = (terminals[-1]["wallMs"] + metadata["miniToPhoneOffsetMs"] - metadata["videoStartMs"]) / 1000
        analysis = run_tool(
            *tool_arguments(
                [
                    "python3",
                    path_arg(HERE / "analyze-settle.py", HERE),
                    path_arg(directory / "screen.mp4", directory),
                    path_arg(directory / "pixels", directory),
                    "--region",
                    crop_arg(region),
                    "--settle-seconds",
                    numeric_arg(seconds),
                    "--post-seconds",
                    "5",
                ]
            ),
            capture_output=True,
            timeout=300,
        )
        (directory / "pixel-analysis.log").write_bytes(analysis.stdout + analysis.stderr)
        if analysis.returncode:
            raise RuntimeError("pixel analysis failed; see pixel-analysis.log")
        shutil.rmtree(directory / "pixels" / "raw", ignore_errors=True)


def capture_terminals(directory, metadata):
    lines = [
        json.loads(line) for line in (directory / "wrapper.jsonl").read_text().splitlines() if line.startswith("{")
    ]
    return [
        e
        for e in lines
        if "turn.finished_relayed" in e["line"] and f"conversationId={metadata['conversationId']} " in e["line"]
    ]


def analyze_partial(directory, metadata, terminals):
    tree = ET.parse(directory / "before-interrupt.xml").getroot()
    metadata["partialRegion"] = reply_region(tree, oracle.FIXED_REPLY[:30], 70)
    if not metadata["partialRegion"]:
        return False
    seconds = (terminals[0]["wallMs"] + metadata["miniToPhoneOffsetMs"] - metadata["videoStartMs"]) / 1000
    write_row_evidence(directory, metadata, terminals[0], "partial-pixels")
    reference = (metadata["interruptReferenceMs"] - metadata["videoStartMs"]) / 1000
    analysis = run_tool(
        *tool_arguments(
            [
                "python3",
                path_arg(HERE / "analyze-settle.py", HERE),
                path_arg(directory / "screen.mp4", directory),
                path_arg(directory / "partial-pixels", directory),
                "--region",
                crop_arg(metadata["partialRegion"]),
                "--settle-seconds",
                numeric_arg(seconds),
                "--reference-seconds",
                numeric_arg(reference),
                "--post-seconds",
                "5",
                "--track-y",
                "1000",
            ]
        ),
        capture_output=True,
        timeout=300,
    )
    (directory / "partial-pixel-analysis.log").write_bytes(analysis.stdout + analysis.stderr)
    if analysis.returncode:
        raise RuntimeError("partial pixel analysis failed; see partial-pixel-analysis.log")
    shutil.rmtree(directory / "partial-pixels" / "raw", ignore_errors=True)
    return True


def capture_region(metadata, tree, explicit_region):
    if explicit_region:
        return explicit_region
    expected = "DONE" if metadata["scenario"] == "S2" else oracle.FIXED_REPLY.split("\n\n")[0]
    return reply_region(tree, expected, 120)


def analyze_capture(directory, metadata, tree, explicit_region=None):
    for name in ("pixels", "partial-pixels"):
        (directory / name / "summary.json").unlink(missing_ok=True)
    region = capture_region(metadata, tree, explicit_region)
    if region:
        terminals = capture_terminals(directory, metadata)
        analyze_final_pixels(directory, metadata, region, terminals)
        if has_partial_capture(metadata["scenario"], terminals, metadata):
            if not analyze_partial(directory, metadata, terminals):
                return normalize(directory, metadata, tree)
    (directory / "metadata.json").write_text(json.dumps(metadata, indent=2))
    return normalize(directory, metadata, tree)


def rescore_captures(args):
    results = []
    for directory in sorted(args.output.glob("S[1-4]-*")):
        try:
            metadata = json.loads((directory / "metadata.json").read_text())
            for name in ("result", "evidence", "metadata"):
                source = directory / (name + ".json")
                backup = directory / (name + "-pre-rescore.json")
                if source.exists() and (not backup.exists()):
                    shutil.copy2(source, backup)
            tree = ET.parse(directory / "final.xml").getroot()
            result = analyze_capture(directory, metadata, tree, args.region)
        except Exception as error:
            result = dict(scenario=directory.name.split("-")[0], client="pixel2xl", status="FAIL", errors=[str(error)])
        (directory / "result.json").write_text(json.dumps(result, indent=2))
        results.append(result)
    if not results:
        raise RuntimeError("no captures to rescore")
    return results


def write_row_evidence(directory, metadata, terminal, name):
    output = directory / name
    output.mkdir(exist_ok=True)
    events = measured_row_events(directory, metadata, terminal)
    (output / "row-evidence.json").write_text(json.dumps(events, indent=2))
