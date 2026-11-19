"""Dev installs must be AOT-compiled before any capture or perf measurement.

A fresh `adb install -r` leaves com.letta.mobile.dev at status=run-from-apk, so
every dex is interpreted and cold start measures ~7.4 s instead of ~1.0 s. These
tests drive the harness against a fake adb (no device) and fail when the
compile/status gate is absent.
"""

import json
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pytest

import coreloop.device as device
from coreloop import cli

VERIFY_DUMPSYS = """
Dexopt state:
  [com.letta.mobile.dev]
    path: /data/app/~~aaa==/com.letta.mobile.dev-bbb==/base.apk
    arm64: [status=verify] [reason=cmdline] [primary-abi]
    arm: [status=verify] [reason=cmdline]
"""
RUN_FROM_APK_DUMPSYS = """
Dexopt state:
  [com.letta.mobile.dev]
    path: /data/app/~~aaa==/com.letta.mobile.dev-bbb==/base.apk
    arm64: [status=run-from-apk] [reason=install]
"""



def dumpsys_with_status(status):
    return RUN_FROM_APK_DUMPSYS.replace("status=run-from-apk", f"status={status}")


# The gate may re-read the status (a status read, then one reading per poll).
ALLOWED_DUMPSYS_READS = 1 + device.DEXOPT_POLLS


class FakeDevice:
    """Fake adb answering exactly what the harness reads.

    Rejects calls it cannot resolve (unknown `pm path` payloads) so a passing
    test cannot come from a fake that accepts anything.
    """

    def __init__(self, dumpsys_sequence, apk_path="/data/app/~~aaa==/com.letta.mobile.dev-bbb==/base.apk", digest="abc123"):
        self.dumpsys_sequence = list(dumpsys_sequence)
        self.apk_path = apk_path
        self.digest = digest
        self.calls = []
        self.timeouts = []

    def dispatch(self, *args, timeout=None):
        return self.adb(*(arg.render() if hasattr(arg, "render") else arg for arg in args), timeout=timeout)

    def adb(self, *args, timeout=None):
        if args and args[0] in ("am", "pm", "cmd", "dumpsys", "settings"):
            raise subprocess.CalledProcessError(1, ["adb", *args], stderr=b"adb: unknown command " + args[0].encode())
        self.calls.append(args)
        self.timeouts.append((args, timeout))
        if args[:2] == ("install", "-r"):
            return "Success"
        if args[:4] == ("shell", "cmd", "package", "compile"):
            return "Success"
        if "pm" in args and "path" in args:
            # Real `pm path` exits 0 with an empty payload when nothing is installed;
            # the harness's installed_app_hash() then rejects len(paths) != 1.
            return "package:" + self.apk_path if self.apk_path else ""
        if "sha256sum" in args:
            return f"{self.digest}  base.apk"
        if "dumpsys" in args:
            assert self.dumpsys_sequence, "dumpsys read more often than the fake was given output for"
            return self.dumpsys_sequence.pop(0)
        if "get-state" in args:
            return "device"
        return "ok"

    def compile_calls(self):
        return [call for call in self.calls if call[:4] == ("shell", "cmd", "package", "compile")]


def verify_fake():
    return FakeDevice([VERIFY_DUMPSYS] * ALLOWED_DUMPSYS_READS)


def run_from_apk_fake():
    return FakeDevice([RUN_FROM_APK_DUMPSYS] * ALLOWED_DUMPSYS_READS)


def test_compile_installs_runs_compile_then_reports_verify_status():
    fake = verify_fake()
    with patch.object(device, "adb", side_effect=fake.dispatch), patch.object(device.time, "sleep"):
        status = device.compile_installed_app()
    assert status["dexoptStatus"] == "verify"
    assert status["dexoptCompiled"] is True
    compile_argv = list(fake.compile_calls()[0])
    assert compile_argv[:4] == ["shell", "cmd", "package", "compile"]
    assert compile_argv[compile_argv.index("-m") + 1] == "verify"
    assert compile_argv[compile_argv.index("-f") + 1] == device.PACKAGE
    assert len(fake.compile_calls()) == 1
    commands = status["dexoptCompileCommands"]
    assert commands == [f"adb -s {device.SERIAL} shell cmd package compile -m verify -f {device.PACKAGE}"]
    assert [step["dexoptStatus"] for step in status["dexoptReadings"]] == ["verify"]


def test_compile_installs_requires_installed_package_before_compiling():
    fake = FakeDevice([], apk_path="")
    with patch.object(device, "adb", side_effect=fake.dispatch), patch.object(device.time, "sleep"):
        with pytest.raises(RuntimeError, match="expected one installed capture APK"):
            device.compile_installed_app()
    assert fake.compile_calls() == []


def test_compile_installs_still_run_from_apk_is_reported_as_uncompiled():
    fake = FakeDevice([RUN_FROM_APK_DUMPSYS] * device.DEXOPT_POLLS)
    with patch.object(device, "adb", side_effect=fake.dispatch), patch.object(device.time, "sleep"):
        status = device.compile_installed_app()
    assert status["dexoptStatus"] == "run-from-apk"
    assert status["dexoptCompiled"] is False
    assert status["dexoptReadings"][-1]["dexoptDetail"] == "uncompiled: dexes are interpreted from the APK"


def test_compile_installs_waits_for_a_concurrent_install_to_finish_compiling():
    fake = FakeDevice([RUN_FROM_APK_DUMPSYS, VERIFY_DUMPSYS])
    with patch.object(device, "adb", side_effect=fake.dispatch), patch.object(device.time, "sleep"):
        status = device.compile_installed_app()
    assert status["dexoptStatus"] == "verify"
    assert [step["dexoptStatus"] for step in status["dexoptReadings"]] == ["run-from-apk", "verify"]
    assert status["dexoptReadings"][0]["dexoptReason"] == "install"


def test_capture_refuses_an_uncompiled_install_naming_the_compile_command():
    fake = run_from_apk_fake()
    with patch.object(device, "adb", side_effect=fake.dispatch), pytest.raises(RuntimeError) as refused:
        device.require_compiled_install()
    message = str(refused.value)
    assert "run-from-apk" in message
    assert f"adb -s {device.SERIAL} shell cmd package compile -m verify -f {device.PACKAGE}" in message
    assert "uncompiled" in message
    assert fake.compile_calls() == []


def test_capture_accepts_compiled_install_and_reports_single_reading():
    with patch.object(device, "adb", side_effect=verify_fake().adb):
        status = device.require_compiled_install()
    assert status["dexoptStatus"] == "verify"
    assert status["dexoptCompiled"] is True
    assert status["dexoptCompileCommands"] == [device.compiled_command()]


def capture_args(tmp_path, install):
    return SimpleNamespace(
        serial=cli.SERIAL,
        trials=3,
        apk=tmp_path / "app.apk",
        output=tmp_path / "out",
        install=install,
    )


@pytest.mark.parametrize("install", [False, True])
def test_prepare_capture_refuses_run_from_apk_install(tmp_path, install):
    fake = FakeDevice([RUN_FROM_APK_DUMPSYS] * (device.DEXOPT_POLLS + 1))
    args = capture_args(tmp_path, install)
    with (
        patch.object(cli, "wait_for_pixel"),
        patch.object(cli, "adb", side_effect=fake.dispatch),
        patch.object(cli, "apk_digest", return_value="abc123"),
        patch.object(cli, "installed_app_hash", return_value="abc123"),
        patch.object(cli, "command", return_value=b"package: name='com.letta.mobile.dev'"),
        patch.object(device, "adb", side_effect=fake.dispatch),
        patch.object(device.time, "sleep"),
        patch.object(cli.signal, "signal"),
    ):
        with pytest.raises(RuntimeError, match="run-from-apk"):
            cli.prepare_capture(args, None)
    assert not (tmp_path / "out").exists()
    assert bool(fake.compile_calls()) is install


def test_prepare_capture_without_install_records_verified_dexopt_status(tmp_path):
    fake = verify_fake()
    args = capture_args(tmp_path, False)
    with (
        patch.object(cli, "wait_for_pixel"),
        patch.object(cli, "adb", side_effect=fake.dispatch),
        patch.object(cli, "apk_digest", return_value="abc123"),
        patch.object(cli, "installed_app_hash", return_value="abc123"),
        patch.object(cli, "command", return_value=b"package: name='com.letta.mobile.dev'"),
        patch.object(device, "adb", side_effect=fake.dispatch),
        patch.object(cli.signal, "signal"),
    ):
        cli.prepare_capture(args, None)
    assert args.dexopt_status["dexoptStatus"] == "verify"
    assert fake.compile_calls() == []


def test_install_path_compiles_after_installing_and_records_verify(tmp_path):
    fake = verify_fake()
    args = capture_args(tmp_path, True)
    with (
        patch.object(cli, "wait_for_pixel"),
        patch.object(cli, "adb", side_effect=fake.dispatch),
        patch.object(cli, "apk_digest", return_value="abc123"),
        patch.object(cli, "installed_app_hash", return_value="abc123"),
        patch.object(cli, "command", return_value=b"package: name='com.letta.mobile.dev'"),
        patch.object(cli, "compile_installed_app", wraps=lambda: device.compile_installed_app()) as compile_step,
        patch.object(device, "adb", side_effect=fake.dispatch),
        patch.object(device.time, "sleep"),
        patch.object(cli.signal, "signal"),
    ):
        cli.prepare_capture(args, None)
    assert compile_step.call_count == 1
    assert args.dexopt_status["dexoptStatus"] == "verify"
    assert args.dexopt_status["dexoptCompiled"] is True
    install_index = next(index for index, call in enumerate(fake.calls) if call[:2] == ("install", "-r"))
    assert any(call[:4] == ("shell", "cmd", "package", "compile") for call in fake.calls[install_index + 1 :])


def test_hash_mismatch_names_both_hashes_and_the_apk_hint(tmp_path):
    args = capture_args(tmp_path, False)
    with (
        patch.object(cli, "wait_for_pixel"),
        patch.object(cli, "adb", return_value="device"),
        patch.object(cli, "apk_digest", return_value="capture-hash"),
        patch.object(cli, "installed_app_hash", return_value="installed-hash"),
        patch.object(cli, "require_compiled_install", return_value=dict(dexoptStatus="verify", dexoptCompiled=True, dexoptCompileCommands=[device.compiled_command()])),
        patch.object(cli.signal, "signal"),
    ):
        with pytest.raises(RuntimeError) as mismatch:
            cli.prepare_capture(args, None)
    message = str(mismatch.value)
    assert "capture-hash" in message
    assert "installed-hash" in message
    assert "--apk" in message
    assert str(args.apk) in message


def test_install_capture_compiles_before_relaunching_the_app(tmp_path):
    args = capture_args(tmp_path, True)
    argv = []

    def command(argv_in, **kwargs):
        argv.extend(argv_in)
        return b"package: name='com.letta.mobile.dev'"

    with (
        patch.object(cli, "command", side_effect=command),
        patch.object(cli, "adb", return_value="") as adb,
        patch.object(cli, "compile_installed_app", return_value=dict(dexoptStatus="verify", dexoptCompiled=True)) as compile_step,
        patch.object(cli.time, "sleep") as sleep,
    ):
        cli.install_capture(args)
    install_index = adb.call_args_list.index(
        next(call for call in adb.call_args_list if call.args[:2] == ("install", "-r"))
    )
    compile_after_install = adb.call_args_list[install_index + 1 :]
    assert compile_step.call_count == 1
    assert compile_after_install, "compile must follow the install"


def test_dexopt_status_is_written_into_every_metadata_json(tmp_path):
    from coreloop import capture

    trial = capture.Capture(SimpleNamespace(output=tmp_path, apk_sha256="abc123", capture_id="fixture"), "S1", 1)
    trial.wifi = trial.data = "1"
    fake = verify_fake()
    with (
        patch.object(capture, "wait_for_pixel"),
        patch.object(capture, "require_compiled_install", return_value=dict(dexoptStatus="verify", dexoptCompiled=True, dexoptCompileCommands=[device.compiled_command()])),
        patch.object(capture, "installed_app_hash", return_value="abc123"),
        patch.object(capture, "run_tool", return_value=subprocess.CompletedProcess([], 1)),
        patch.object(capture, "adb", return_value="1"),
        patch.object(capture, "command", return_value=json.dumps(dict(conversation_id="conv-fixture")).encode()),
        patch.object(capture, "select_conversation"),
        patch.object(capture, "parse_log", return_value=[dict(key="existing")]),
        patch.object(capture.time, "sleep"),
    ):
        trial.prepare()
    assert trial.metadata["dexoptStatus"] == "verify"
    assert trial.metadata["dexoptCompiled"] is True
    assert trial.metadata["dexoptCompileCommands"] == [device.compiled_command()]
    assert fake.compile_calls() == []


def test_trial_refuses_an_uncompiled_install_before_the_fixture_audit(tmp_path):
    """The real gate raises on run-from-apk; prepare() must stop before the ssh audit or any send."""
    from coreloop import capture

    trial = capture.Capture(SimpleNamespace(output=tmp_path, apk_sha256="abc123", capture_id="fixture"), "S1", 1)
    with (
        patch.object(capture, "wait_for_pixel"),
        patch.object(capture, "installed_app_hash", return_value="abc123"),
        patch.object(capture, "adb", return_value="1"),
        patch.object(capture, "require_compiled_install", side_effect=RuntimeError("com.letta.mobile.dev is at dexopt status=run-from-apk")) as require_compiled,
        patch.object(capture, "command") as audit,
        patch.object(capture, "select_conversation") as select_conversation,
    ):
        with pytest.raises(RuntimeError, match="run-from-apk"):
            trial.prepare()
    require_compiled.assert_called_once_with()
    audit.assert_not_called()
    select_conversation.assert_not_called()


@pytest.mark.parametrize("status", ["run-from-apk", "run-from-apk-fallback", "run-from-vdex-fallback", "error"])
def test_fallback_and_error_statuses_are_uncompiled(status):
    fake = FakeDevice([dumpsys_with_status(status)])
    with patch.object(device, "adb", side_effect=fake.dispatch):
        with pytest.raises(RuntimeError, match="refusing to measure an uncompiled install"):
            device.require_compiled_install()


def test_compile_force_stops_through_adb_shell():
    """Real adb rejects a top-level `am`; the settle step must go through `adb shell`."""
    fake = verify_fake()
    with patch.object(device, "adb", side_effect=fake.dispatch), patch.object(device.time, "sleep"):
        device.compile_installed_app()
    assert ("shell", "am", "force-stop", device.PACKAGE) in fake.calls


def test_compile_gets_its_own_timeout_budget_above_the_default_adb_timeout():
    """A forced verify compile took 27.3 s on the Pixel 2 XL; the 30 s default would race it."""
    fake = verify_fake()
    with patch.object(device, "adb", side_effect=fake.dispatch), patch.object(device.time, "sleep"):
        device.compile_installed_app()
    compile_timeouts = [t for args, t in fake.timeouts if args[:4] == ("shell", "cmd", "package", "compile")]
    assert compile_timeouts == [device.DEXOPT_COMPILE_TIMEOUT_SECONDS]
    assert device.DEXOPT_COMPILE_TIMEOUT_SECONDS >= 120
    assert all(t is None for args, t in fake.timeouts if args[:4] != ("shell", "cmd", "package", "compile"))
