import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from tool_boundary import run_tool, tool_arguments
from .config import SERIAL, PACKAGE

# Pixel 2 XL, 2026-11-19: a fresh `adb install -r` leaves the dev package at
# status=run-from-apk and cold start measures 7401 ms (n=20) instead of
# 1018 ms (n=10) at status=verify. PackageManager can report the old run-from-apk
# state while a concurrent install disappears, so the status is re-read.
UNCOMPILED_STATUS = "run-from-apk"
COMPILED_STATUS = "verify"
# ART compiler filters that mean the dexes are verified/compiled ahead of time.
# Anything else (run-from-apk, run-from-apk-fallback, run-from-vdex-fallback,
# error, or no status) is treated as uncompiled.
COMPILED_FILTERS = frozenset({"verify", "quicken", "space-profile", "space", "speed-profile", "speed", "everything", "everything-profile"})
DEXOPT_POLLS = 3
DEXOPT_POLL_SECONDS = 2
DEXOPT_SETTLE_SECONDS = 3
# Read-only restart of an already installed package; no data is cleared.
DEXOPT_SETTLE_COMMAND = ("am", "force-stop", PACKAGE)


ADB_TIMEOUT_SECONDS = 30
# Pixel 2 XL, 2026-11-19: a forced verify compile of the 26-dex debug APK took
# 27.3 s, too close to the default adb timeout; give the compile its own budget.
DEXOPT_COMPILE_TIMEOUT_SECONDS = 300


def command(argv, timeout=ADB_TIMEOUT_SECONDS, **kwargs):
    return run_tool(*tool_arguments(argv), check=True, capture_output=True, timeout=timeout, **kwargs).stdout


def adb(*args, timeout=None):
    argv = ["adb", "-s", SERIAL, *args]
    output = command(argv) if timeout is None else command(argv, timeout=timeout)
    return output.decode().strip()


def installed_apk_path():
    paths = adb("shell", "pm", "path", PACKAGE).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise RuntimeError("expected one installed capture APK")
    return paths[0].removeprefix("package:")


def installed_app_hash():
    return adb("shell", "sha256sum", installed_apk_path()).split()[0]


def adb_command(command_line):
    return "adb -s " + SERIAL + " " + " ".join(command_line)


def compiled_command():
    return f"adb -s {SERIAL} shell cmd package compile -m {COMPILED_STATUS} -f {PACKAGE}"


def require_installed_app():
    """Raise unless exactly one APK of the dev package is installed."""
    installed_apk_path()


def dexopt_detail(status):
    if status in COMPILED_FILTERS:
        return f"dexopt status={status}"
    if status == UNCOMPILED_STATUS:
        return "uncompiled: dexes are interpreted from the APK"
    return f"uncompiled: dexopt status={status} is not a compiled filter"


def dexopt_reading():
    """Parsed `dumpsys package dexopt` for the dev package, or an explicit absence."""
    lines = adb("shell", "dumpsys", "package", "dexopt").splitlines()
    header = lines.index(f"  [{PACKAGE}]") if f"  [{PACKAGE}]" in lines else None
    if header is None:
        return dict(dexoptStatus=None, dexoptCompiled=False, dexoptDetail="dexopt state not reported for the package", dexoptReason=None)
    section = []
    for line in lines[header + 1 :]:
        if line and not line.startswith("    "):
            break
        section.append(line.strip())
    statuses = re.findall(r"\[status=([A-Za-z_-]+)\]", " ".join(section))
    reasons = re.findall(r"\[reason=([A-Za-z_-]+)\]", " ".join(section))
    if not statuses:
        return dict(dexoptStatus=None, dexoptCompiled=False, dexoptDetail="no dexopt status within the package entry", dexoptReason=None)
    primary = statuses[0]
    return dict(
        dexoptStatus=primary,
        dexoptCompiled=primary in COMPILED_FILTERS,
        dexoptDetail=dexopt_detail(primary),
        dexoptReason=reasons[0] if reasons else None,
    )


def dexopt_record(installs=(), readings=None):
    """One dexopt verdict plus the commands it is valid for, shaped for metadata.json."""
    reading = readings[-1] if readings else dexopt_reading()
    return dict(
        **reading,
        dexoptReadings=readings or [reading],
        dexoptCompileCommands=[adb_command(installs)] if installs else [compiled_command()],
        dexoptCheckedAfter=installs or None,
        dexoptSettleCommands=[adb_command(("shell", *DEXOPT_SETTLE_COMMAND))] if installs else [],
    )


def compile_installed_app():
    """Compile the installed dev package for speed and return its parsed dexopt status.

    Call after any install: `cmd package compile -m verify -f` costs seconds per
    dex and persists until the next install. An install that PackageManager
    reports as finished can restart dexopt on its own, so the status is re-read.
    """
    require_installed_app()
    compile_argv = ["shell", "cmd", "package", "compile", "-m", COMPILED_STATUS, "-f", PACKAGE]
    adb(*compile_argv, timeout=DEXOPT_COMPILE_TIMEOUT_SECONDS)
    adb("shell", *DEXOPT_SETTLE_COMMAND)
    time.sleep(DEXOPT_SETTLE_SECONDS)
    readings = []
    for attempt in range(DEXOPT_POLLS):
        readings.append(dexopt_reading())
        if readings[-1]["dexoptCompiled"]:
            break
        if attempt + 1 < DEXOPT_POLLS:
            time.sleep(DEXOPT_POLL_SECONDS)
    return dexopt_record(compile_argv, readings)


def require_compiled_install():
    """Refuse a capture on an uncompiled install; every measured run must name its compile state."""
    status = dexopt_record()
    if not status["dexoptCompiled"]:
        # A status that cannot be read is treated as uncompiled, not as clean.
        raise RuntimeError(
            f"{PACKAGE} is at dexopt status={status['dexoptStatus'] or 'unknown'}; refusing to measure an "
            f"uncompiled install ({status['dexoptDetail']}). Run: {compiled_command()}"
        )
    return status


def wall_ms():
    return time.time_ns() / 1000000


def dump(directory, name):
    adb("shell", "rm", "-f", "/sdcard/core-loop-ui.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/core-loop-ui.xml")
    raw = command(["adb", "-s", SERIAL, "exec-out", "cat", "/sdcard/core-loop-ui.xml"])
    try:
        tree = ET.fromstring(raw)
    except ET.ParseError as failure:
        raise RuntimeError(f"{name}: UI hierarchy not produced: {failure}") from failure
    if tree.tag != "hierarchy":
        raise RuntimeError(f"{name}: unexpected UI hierarchy root")
    (directory / f"{name}.xml").write_bytes(raw)
    return tree


def tap_node(tree, attribute, value):
    nodes = [n for n in tree.iter("node") if n.get(attribute) == value]
    if len(nodes) != 1:
        raise RuntimeError(f"expected one {attribute}={value}, got {len(nodes)}")
    x1, y1, x2, y2 = map(int, re.findall("\\d+", nodes[0].get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def reply_region(tree, prefix, maximum_height):
    matches = []
    for node in tree.iter("node"):
        if prefix not in node.get("text", ""):
            continue
        bounds = tuple(map(int, re.findall("\\d+", node.get("bounds", ""))))
        if len(bounds) == 4:
            matches.append(bounds)
    if not matches:
        return None
    x1, y1, x2, y2 = max(matches, key=lambda bounds: bounds[1])
    return f"{x1},{y1},{x2 - x1},{min(y2 - y1, maximum_height)}"


def send(directory, prompt, name, fast=False):
    tree = ET.parse(directory / "first-composer.xml").getroot() if fast else dump(directory, name + "-composer")
    tap_node(tree, "class", "android.widget.EditText")
    adb("shell", "input", "text", prompt.replace(" ", "%s"))
    adb("shell", "input", "keyevent", "4")
    tree = ET.parse(directory / "first-typed.xml").getroot() if fast else dump(directory, name + "-typed")
    sent = wall_ms()
    tap_node(tree, "content-desc", "Send message")
    return sent


def restore_service(service, value, errors):
    try:
        adb("shell", "svc", service, "enable" if value == "1" else "disable")
    except Exception as error:
        errors.append(str(error))


def verify_service(key, expected, restored, errors):
    try:
        restored[key] = adb("shell", "settings", "get", "global", key)
        if restored[key] != expected:
            errors.append(f"{key} expected {expected}, observed {restored[key]}")
    except Exception as error:
        errors.append(str(error))


def restore_network(wifi, data):
    errors, restored = ([], {})
    for service, value in (("wifi", wifi), ("data", data)):
        restore_service(service, value, errors)
    for key, expected in (("wifi_on", wifi), ("mobile_data", data)):
        verify_service(key, expected, restored, errors)
    if errors:
        raise RuntimeError("NETWORK RESTORE FAILED: " + "; ".join(errors))
    return restored


def stop_process(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def wait_for_pixel():
    ancestors = {os.getpid()}
    parent = os.getppid()
    while parent > 1:
        ancestors.add(parent)
        parent = int(Path(f"/proc/{parent}/stat").read_text().split(") ")[1].split()[1])
    deadline = time.monotonic() + 300
    while True:
        owners = run_tool(*tool_arguments(["pgrep", "-af", SERIAL]), capture_output=True, text=True)
        other = [line for line in owners.stdout.splitlines() if int(line.split()[0]) not in ancestors]
        if not other:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Pixel already owned: " + "; ".join(other))
        time.sleep(5)
