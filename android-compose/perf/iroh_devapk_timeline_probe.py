#!/usr/bin/env python3
"""Headless probe for the dev APK Iroh timeline refresh path.

This is intentionally host-side and GUI-free. It sends a message to a known
agent/conversation through the local backend REST path, then verifies that:
  1. the local backend persisted new messages, and
  2. the running dev APK emitted timeline reconcile/projection logs for that
     conversation without manual refresh.

Example:
  python3 perf/iroh_devapk_timeline_probe.py \
    --adb 100.79.179.71:5555 \
    --agent agent-ca46df7f-c16a-4599-8e2d-3dc145c3e433 \
    --conversation conv-8d4b6225-a2f6-47a7-8f73-664d56143bbd \
    --message "probe $(date +%s)"
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import urllib.error
import sys
import time
import urllib.request
from pathlib import Path


DEFAULT_ADB = "100.79.179.71:5555"
DEFAULT_PACKAGE = "com.letta.mobile.dev"
DEFAULT_BACKEND = "http://127.0.0.1:8291"
DEFAULT_APP_SERVER_WS = "ws://127.0.0.1:4500"
DEFAULT_ACTIVITY = "com.letta.mobile.dev/com.letta.mobile.MainActivity"
DEFAULT_AGENT = "agent-ca46df7f-c16a-4599-8e2d-3dc145c3e433"
DEFAULT_CONVERSATION = "conv-8d4b6225-a2f6-47a7-8f73-664d56143bbd"
DEFAULT_JAVA_HOME = "/usr/lib/jvm/java-21-openjdk-amd64"
DEFAULT_ANDROID_HOME = "/opt/android-sdk"


def run(
    cmd: list[str],
    *,
    cwd: Path | None = None,
    timeout: int = 120,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    return subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
        env=merged_env,
    )


def get_json(url: str):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer not-needed"})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.loads(response.read().decode())


def message_count(backend: str, conversation: str) -> int:
    messages = get_json(f"{backend}/v1/conversations/{conversation}/messages")
    return len(messages)


def app_pid(adb: str, package: str) -> str:
    result = run(["adb", "-s", adb, "shell", "pidof", package], timeout=10)
    return result.stdout.strip().split()[0] if result.stdout.strip() else ""


def clear_logcat(adb: str) -> None:
    run(["adb", "-s", adb, "logcat", "-c"], timeout=10)


def read_logcat(adb: str, pid: str) -> str:
    result = run(["adb", "-s", adb, "logcat", "-d", "-v", "time"], timeout=30)
    if not pid:
        return result.stdout
    marker = f"({pid})"
    return "\n".join(line for line in result.stdout.splitlines() if marker in line)


def restart_app(adb: str, package: str, activity: str) -> str:
    run(["adb", "-s", adb, "shell", "am", "force-stop", package], timeout=10)
    run(["adb", "-s", adb, "shell", "am", "start", "--activity-clear-top", "-n", activity], timeout=10)
    deadline = time.time() + 20
    while time.time() < deadline:
        pid = app_pid(adb, package)
        if pid:
            return pid
        time.sleep(0.5)
    raise TimeoutError(f"{package} did not start on {adb}")


def post_conversation_message(backend: str, conversation: str, message: str, timeout: int) -> str:
    payload = json.dumps({"messages": [{"role": "user", "content": message}]}).encode()
    req = urllib.request.Request(
        f"{backend}/v1/conversations/{conversation}/messages",
        data=payload,
        method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer not-needed"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.read().decode(errors="ignore")
    except urllib.error.HTTPError as error:
        body = error.read().decode(errors="ignore")
        raise RuntimeError(f"backend POST failed: {error.code} {body}") from error


def wait_for_backend_growth(backend: str, conversation: str, before: int, timeout: int) -> int:
    deadline = time.time() + timeout
    last_count = before
    while time.time() < deadline:
        last_count = message_count(backend, conversation)
        if last_count > before:
            return last_count
        time.sleep(1)
    raise TimeoutError(f"backend message count did not grow from {before}; last={last_count}")


def wait_for_live_iroh_receive(adb: str, pid: str, conversation: str, timeout: int) -> tuple[bool, str]:
    deadline = time.time() + timeout
    last_logs = ""
    while time.time() < deadline:
        logs = read_logcat(adb, pid)
        last_logs = logs
        relevant = [line for line in logs.splitlines() if conversation in line or "Iroh" in line]
        text = "\n".join(relevant)
        saw_reader_frame = "IrohTransport" in text and "frame.recv" in text
        saw_bridge_emit = "IrohGate" in text and "gate1.emitBoth" in text
        saw_draft = "IrohTrace" in text and "transport.emitDraft" in text
        if saw_reader_frame and (saw_bridge_emit or saw_draft):
            return True, text
        time.sleep(1)
    return False, last_logs


def wait_for_apk_projection(adb: str, pid: str, conversation: str, expected_count: int, timeout: int) -> tuple[bool, str]:
    deadline = time.time() + timeout
    last_logs = ""
    expected_count_tokens = (
        f"serverCount={expected_count}",
        f"messageCount={expected_count}",
        f"eventsTotal={expected_count}",
        f"eventCount={expected_count}",
    )
    while time.time() < deadline:
        logs = read_logcat(adb, pid)
        last_logs = logs
        relevant = [line for line in logs.splitlines() if conversation in line or "irohActiveReconcile" in line]
        text = "\n".join(relevant)
        saw_reconcile = "irohActiveReconcile.ok" in text or "recentReconcile" in text
        saw_expected_count = any(token in text for token in expected_count_tokens)
        saw_projection = (
            "uiProjection.snapshot" in text
            or "Timeline ready" in text
            or "hydrate" in text
        )
        if saw_reconcile and saw_expected_count and saw_projection:
            return True, text
        time.sleep(1)
    return False, last_logs


def main() -> int:
    parser = argparse.ArgumentParser(description="Headless dev APK Iroh timeline probe")
    parser.add_argument("--adb", default=DEFAULT_ADB)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY)
    parser.add_argument("--backend", default=DEFAULT_BACKEND)
    parser.add_argument("--app-server-ws", default=DEFAULT_APP_SERVER_WS)
    parser.add_argument("--agent", default=DEFAULT_AGENT)
    parser.add_argument("--conversation", default=DEFAULT_CONVERSATION)
    parser.add_argument("--message", default=f"headless iroh probe {int(time.time())}")
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--no-restart", action="store_true", help="Do not restart the dev APK before probing.")
    parser.add_argument(
        "--expect-live-iroh",
        action="store_true",
        help="Fail unless the running APK logs live Iroh frame receive/emit gates. Use with --no-restart and send a GUI/Iroh turn while it waits.",
    )
    parser.add_argument("--backend-timeout", type=int, default=90)
    parser.add_argument("--apk-timeout", type=int, default=90)
    args = parser.parse_args()

    _repo = Path(args.repo)
    pid = app_pid(args.adb, args.package)
    if not pid:
        print(f"FAIL: {args.package} is not running on {args.adb}", file=sys.stderr)
        return 2

    before = message_count(args.backend, args.conversation)
    print(f"[probe] backend count before={before}")
    if not args.no_restart:
        pid = restart_app(args.adb, args.package, args.activity)
        time.sleep(5)
    print(f"[probe] app pid={pid}")
    clear_logcat(args.adb)

    if args.expect_live_iroh:
        print("[probe] waiting for live Iroh receive logs; send a dev APK/Iroh message now")
        ok, logs = wait_for_live_iroh_receive(args.adb, pid, args.conversation, args.apk_timeout)
        if not ok:
            out = Path("/tmp/iroh-devapk-live-receive-probe.log")
            out.write_text(logs)
            print(f"FAIL: APK did not log live Iroh frame receive/emit gates; wrote {out}", file=sys.stderr)
            return 1
        print(f"PASS: APK logged live Iroh receive for conversation {args.conversation}")
        return 0

    stream = post_conversation_message(args.backend, args.conversation, args.message, args.backend_timeout)
    print(stream[-2_000:])

    after = wait_for_backend_growth(args.backend, args.conversation, before, args.backend_timeout)
    print(f"[probe] backend count after={after}")

    ok, logs = wait_for_apk_projection(args.adb, pid, args.conversation, after, args.apk_timeout)
    if not ok:
        out = Path("/tmp/iroh-devapk-timeline-probe.log")
        out.write_text(logs)
        print(f"FAIL: APK did not project/reconcile expected count={after}; wrote {out}", file=sys.stderr)
        return 1

    print(f"PASS: APK reconciled/projected conversation {args.conversation} count={after}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
