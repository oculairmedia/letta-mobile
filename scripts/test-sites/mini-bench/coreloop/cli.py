"Pixel 2 XL-only core-loop evidence capture. Never selects an arbitrary adb device.\n\nRequires the main app plus opt-in root-debug CORE_LOOP diagnostics, mini-bench\nfixture installed on graphiti, and mini-bench already open in the phone. Run with\n--output <artifact directory>; records three trials per scenario by default.\nNetwork state is restored in finally and on termination; restoration failure is\nan error. No install, model changes, server restart or adb-server restart occurs."

import json
from pathlib import Path
import re
import time
from . import scoring as oracle
from tool_boundary import path_arg, Tool
from .config import SERIAL, PACKAGE, HERE
import argparse
import hashlib
import signal
import uuid
from .device import (
    wait_for_pixel,
    adb,
    installed_app_hash,
    require_compiled_install,
    compile_installed_app,
    command,
)
from .capture import trial, rescore_captures


def run_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--install", action="store_true", help="verify package and install the capture APK in place before trials"
    )
    parser.add_argument(
        "--apk",
        type=Path,
        default=HERE.parents[2] / "android-compose/app/build/outputs/apk/root/debug/app-root-debug.apk",
    )
    parser.add_argument(
        "--serial", choices=[SERIAL], help="required for captures so the ownership process is visible to other workers"
    )
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument("--output", type=Path)
    destination.add_argument("--rescore", type=Path, help="rebuild verdicts from saved raw captures; no device access")
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--scenarios", nargs="+", choices=oracle.SCENARIOS, default=list(oracle.SCENARIOS))
    parser.add_argument("--region", help="x,y,width,height of stable reply ink")
    return (parser, parser.parse_args())


def apk_digest(path):
    with path.open("rb") as apk:
        digest = hashlib.sha256()
        for chunk in iter(lambda: apk.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def install_capture(args):
    aapt = Path(Tool.AAPT.value)
    if not aapt.is_file():
        raise RuntimeError("aapt required to verify in-place dev package")
    badging = command([str(aapt), "dump", "badging", path_arg(args.apk, args.apk.parent)]).decode()
    if not re.search("package: name='com\\.letta\\.mobile\\.dev'", badging):
        raise RuntimeError("refusing to install anything except com.letta.mobile.dev")
    adb("install", "-r", path_arg(args.apk, args.apk.parent))
    # An install leaves the package at status=run-from-apk (interpreted, ~7 s cold
    # start); compile before the first launch so every trial measures compiled code.
    status = compile_installed_app()
    print(f"install: dexopt status={status['dexoptStatus']}", flush=True)
    if not status["dexoptCompiled"]:
        print(f"install: {PACKAGE} still {status['dexoptStatus']} after compile; run {status['dexoptCompileCommands']}", flush=True)
    adb("shell", "setprop", "log.tag.LettaCoreLoop", "VERBOSE")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", PACKAGE + "/com.letta.mobile.MainActivity")
    time.sleep(5)
    return status


def interrupted(signum, frame):
    raise KeyboardInterrupt(f"signal {signum}")


def verify_capture_device(args, parser):
    if args.serial != SERIAL:
        parser.error("fresh captures require --serial " + SERIAL)
    wait_for_pixel()
    if args.trials < 3:
        parser.error("baseline requires at least three trials")
    if adb("get-state") != "device":
        raise RuntimeError("authorized Pixel 2 XL unavailable")


def require_capture_apk(args):
    installed_sha256 = installed_app_hash()
    if installed_sha256 != args.apk_sha256:
        raise RuntimeError(
            f"installed APK sha256 {installed_sha256} does not match the capture APK "
            f"{args.apk} (sha256 {args.apk_sha256}); pass --apk <path to the installed build> "
            "or install the exact capture APK before running the baseline"
        )
    if not args.dexopt_status["dexoptCompiled"]:
        # The install path compiles first, so this only fires when the compile
        # did not persist or the existing install was never compiled.
        raise RuntimeError(
            f"{PACKAGE} is at dexopt status={args.dexopt_status['dexoptStatus']}; refusing to measure an "
            "uncompiled install. Run: " + args.dexopt_status["dexoptCompileCommands"][0]
        )


def prepare_capture(args, parser):
    verify_capture_device(args, parser)
    args.apk_sha256 = apk_digest(args.apk)
    args.dexopt_status = install_capture(args) if args.install else require_compiled_install()
    require_capture_apk(args)
    args.capture_id = uuid.uuid4().hex
    args.output.mkdir(parents=True, exist_ok=False)
    signal.signal(signal.SIGTERM, interrupted)


def capture_trial(args, scenario, index):
    try:
        result = trial(args, scenario, index)
    except Exception as error:
        result = dict(scenario=scenario, client="pixel2xl", status="FAIL", errors=[str(error)])
    directory = args.output / f"{scenario}-{index}"
    directory.mkdir(exist_ok=True)
    (directory / "result.json").write_text(json.dumps(result, indent=2))
    return result


def print_table(results, scenarios):
    print("| Scenario | Client | Pass | Fail | Stored ms | Stop ms | Correlation | Dip % |")
    print("| --- | --- | ---: | ---: | --- | --- | --- | --- |")
    for scenario in scenarios:
        rows = [r for r in results if r["scenario"] == scenario]
        metrics = [
            "; ".join((str(r.get(k, "missing")) for r in rows))
            for k in ("storedCopyLatencyMs", "stopLatencyMs", "minimumCorrelation", "dipPercent")
        ]
        print(
            f"| {scenario} | pixel2xl | {sum((r['status'] == 'PASS' for r in rows))} | {sum((r['status'] != 'PASS' for r in rows))} | "
            + " | ".join(metrics)
            + " |"
        )


def run_main():
    parser, args = run_parser()
    if args.rescore:
        args.output = args.rescore
        results = rescore_captures(args)
    else:
        prepare_capture(args, parser)
        results = [
            capture_trial(args, scenario, index) for scenario in args.scenarios for index in range(1, args.trials + 1)
        ]
    (args.output / "summary.json").write_text(json.dumps(results, indent=2))
    print_table(results, args.scenarios)
    return int(any((r["status"] != "PASS" for r in results)))


from .pixels import analyze_pixels


def pixel_main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--region", required=True, help="x,y,width,height of reply text, excluding chrome")
    parser.add_argument("--settle-seconds", type=float, required=True)
    parser.add_argument("--reference-seconds", type=float, help="explicit stable pre-event template timestamp")
    parser.add_argument(
        "--post-seconds", type=float, default=20, help="include live clearance and post-send Paging revisions"
    )
    parser.add_argument("--track-y", type=int, default=320, help="vertical search radius for the reply bounding box")
    args = parser.parse_args()
    analyze_pixels(args)


def score_main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    for index, path in enumerate(args.evidence):
        try:
            result = oracle.score(json.loads(path.read_text()))
            serialized = json.dumps(result, indent=2, allow_nan=False)
        except (OSError, ValueError, TypeError, AttributeError) as error:
            result = dict(status="FAIL", errors=[f"invalid evidence: {error}"])
            serialized = json.dumps(result, indent=2, allow_nan=False)
        (args.output / f"run-{index + 1}.json").write_text(serialized + "\n")
        results.append(result)
    print("| Scenario | Client | Result | Stored ms | Stop ms | Correlation | Dip % |")
    print("| --- | --- | --- | --- | --- | --- | --- |")
    for result in results:
        print(
            "| "
            + " | ".join(
                str(result.get(key, "missing"))
                for key in (
                    "scenario",
                    "client",
                    "status",
                    "storedCopyLatencyMs",
                    "stopLatencyMs",
                    "minimumCorrelation",
                    "dipPercent",
                )
            )
            + " |"
        )
    return int(any(result["status"] != "PASS" for result in results))
