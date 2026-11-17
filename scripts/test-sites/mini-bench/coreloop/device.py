import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from tool_boundary import run_tool, tool_arguments
from .config import SERIAL, PACKAGE


def command(argv, **kwargs):
    return run_tool(*tool_arguments(argv), check=True, capture_output=True, timeout=30, **kwargs).stdout


def adb(*args):
    return command(["adb", "-s", SERIAL, *args]).decode().strip()


def installed_app_hash():
    paths = adb("shell", "pm", "path", PACKAGE).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise RuntimeError("expected one installed capture APK")
    path = paths[0].removeprefix("package:")
    return adb("shell", "sha256sum", path).split()[0]


def wall_ms():
    return time.time_ns() / 1000000


def dump(directory, name):
    adb("shell", "uiautomator", "dump", "/sdcard/core-loop-ui.xml")
    raw = command(["adb", "-s", SERIAL, "exec-out", "cat", "/sdcard/core-loop-ui.xml"])
    (directory / f"{name}.xml").write_bytes(raw)
    return ET.fromstring(raw)


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
