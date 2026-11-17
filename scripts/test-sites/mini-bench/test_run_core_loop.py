"""Driver safety and evidence parsing checks; never connects to a real device."""

import json
import xml.etree.ElementTree as ET
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess

import pytest
from coreloop import device, evidence, capture, cli


class DriverTest(unittest.TestCase):
    def test_adb_is_always_serial_restricted(self):
        with patch.object(device, "command", return_value=b"device\n") as command:
            self.assertEqual(device.adb("get-state"), "device")
        command.assert_called_once_with(["adb", "-s", "711KPAE0914240", "get-state"])

    def test_restore_preserves_both_original_states(self):
        with patch.object(device, "adb", side_effect=["", "", "1", "0"]) as adb:
            restored = device.restore_network("1", "0")
        self.assertEqual(restored, {"wifi_on": "1", "mobile_data": "0"})
        self.assertEqual(adb.call_args_list[0].args, ("shell", "svc", "wifi", "enable"))
        self.assertEqual(adb.call_args_list[1].args, ("shell", "svc", "data", "disable"))

    def test_restore_attempts_data_even_if_wifi_restore_fails(self):
        with patch.object(device, "adb", side_effect=[RuntimeError("disconnected"), "", "1", "1"]) as adb:
            with self.assertRaisesRegex(RuntimeError, "NETWORK RESTORE FAILED"):
                device.restore_network("1", "1")
        self.assertEqual(adb.call_count, 4)

    def test_parses_only_timestamped_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "logcat.txt"
            path.write_text(
                "garbage\n         1234.567 1 2 I System.out: CORE_LOOP draw key=run-a identity=message-a stored=true assistant=true\n"
                "1234.568 1 2 I System.out: CORE_LOOP resident key=run-a\n"
            )
            events = evidence.parse_log(path)
        self.assertEqual(len(events), 2)
        self.assertEqual(events[0]["ms"], 1234567)
        self.assertEqual(events[0]["stored"], "true")
        self.assertEqual(events[1]["event"], "resident")

    def test_failed_pixel_analysis_cannot_reuse_a_stale_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "wrapper.jsonl").write_text(
                json.dumps(dict(wallMs=200, line="turn.finished_relayed conversationId=fixture stopReason=end_turn"))
                + "\n"
            )
            (root / "pixels").mkdir()
            (root / "pixels" / "summary.json").write_text("{}")
            metadata = dict(scenario="S1", conversationId="fixture", miniToPhoneOffsetMs=0, videoStartMs=0)
            (root / "logcat.txt").write_text("")
            metadata["existingKeys"] = []
            failed = subprocess.CompletedProcess([], 1, b"", b"invalid capture")
            with (
                patch.object(subprocess, "run", return_value=failed),
                patch.object(capture, "normalize", return_value={}) as normalize,
            ):
                with self.assertRaisesRegex(RuntimeError, "pixel analysis failed"):
                    capture.analyze_capture(root, metadata, ET.Element("hierarchy"), "0,0,64,64")
                normalize.assert_not_called()

    def test_reply_region_selects_newest_physical_reply_not_xml_order(self):
        tree = ET.Element("hierarchy")
        ET.SubElement(tree, "node", text="fixed reply", bounds="[10,500][200,650]")
        ET.SubElement(tree, "node", text="fixed reply", bounds="[10,100][200,250]")
        self.assertEqual(device.reply_region(tree, "fixed reply", 70), "10,500,190,70")
        self.assertIsNone(device.reply_region(tree, "missing reply", 70))

    def test_fresh_capture_requires_visible_serial_before_any_device_access(self):
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch("sys.argv", ["driver", "--output", temporary]),
            patch.object(cli, "wait_for_pixel"),
            patch.object(cli, "adb") as adb,
        ):
            with self.assertRaises(SystemExit) as stopped:
                cli.run_main()
            self.assertEqual(stopped.exception.code, 2)
            adb.assert_not_called()



def reply_tree():
    tree = ET.Element("hierarchy")
    timeline = ET.SubElement(tree, "node", scrollable="true")
    user = ET.SubElement(timeline, "node", {"long-clickable": "true", "bounds": "[0,300][100,400]"})
    ET.SubElement(user, "node", text="You")
    ET.SubElement(user, "node", text="[core-loop:S1] trial-1-fixture")
    row = ET.SubElement(timeline, "node", {"long-clickable": "true", "bounds": "[0,500][100,900]"})
    ET.SubElement(row, "node", text=evidence.oracle.FIXED_REPLY)
    return tree


def raw_fixture(directory, duplicate):
    lines = [
        "0.090 1 2 I System.out: CORE_LOOP mount key=run-a",
        "0.100 1 2 I System.out: CORE_LOOP draw key=run-a identity=message-a stored=false assistant=true",
    ]
    lines += [
        f"{ms / 1000:.3f} 1 2 I System.out: CORE_LOOP resident key=run-a" for ms in range(150, 5250, 50)
    ]
    lines.append(
        "0.230 1 2 I System.out: CORE_LOOP draw key=run-a identity=message-a stored=true assistant=true hashes=0"
    )
    lines.append(
        "0.250 1 2 I System.out: CORE_LOOP draw key=run-a identity=durable-run-alias stored=true assistant=true hashes=699287813"
    )
    if duplicate:
        lines += [
            "0.270 1 2 I System.out: CORE_LOOP mount key=second",
            "0.280 1 2 I System.out: CORE_LOOP draw key=second identity=durable-run-alias stored=true assistant=true hashes=699287813",
        ]
    lines.sort(key=lambda line: float(line.split()[0]))
    (directory / "logcat.txt").write_text("\n".join(lines))
    (directory / "wrapper.jsonl").write_text(
        json.dumps(
            dict(
                wallMs=150,
                line="input.accepted conversationId=fixture-conversation disposition=queued",
            )
        )
        + "\n"
        + json.dumps(
            dict(
                wallMs=200,
                line="turn.finished_relayed conversationId=fixture-conversation stopReason=end_turn",
            )
        )
        + "\n"
    )
    (directory / "pixels").mkdir()
    (directory / "pixels" / "summary.json").write_text(
        json.dumps(
            dict(
                frames=100,
                minimum_template_correlation=1.0,
                dip_percent=0.0,
                settle_seconds=2,
                post_seconds=5,
                coverage=complete_pixel_record(),
            )
        )
    )
    tree = reply_tree()
    metadata = dict(
        scenario="S1",
        trial=1, captureId="fixture",
        sendMs=0,
        phoneOffsetMs=0,
        miniToPhoneOffsetMs=0,
        clockAligned=True,
        existingKeys=[],
        stopGoneMs=300,
        conversationId="fixture-conversation",
    )
    return metadata, tree


def history_fixture(root, behavior):
    lines = [
        "0.010 1 2 I System.out: CORE_LOOP mount key=old",
        "0.020 1 2 I System.out: CORE_LOOP draw key=old identity=old-id stored=true assistant=true hashes=1",
        "0.150 1 2 I System.out: CORE_LOOP mount key=new",
        "0.160 1 2 I System.out: CORE_LOOP draw key=new identity=new-id stored=false assistant=true hashes=0",
        "0.300 1 2 I System.out: CORE_LOOP draw key=new identity=new-id stored=true assistant=true hashes=699287813",
    ]
    if behavior == "assistant_remount":
        lines += [
            "0.250 1 2 I System.out: CORE_LOOP dispose key=old",
            "0.260 1 2 I System.out: CORE_LOOP mount key=old",
        ]
    if behavior == "user_duplicate":
        lines += [
            "0.170 1 2 I System.out: CORE_LOOP mount key=user-a",
            "0.180 1 2 I System.out: CORE_LOOP draw key=user-a identity=client-id stored=true assistant=false hashes=7",
            "0.190 1 2 I System.out: CORE_LOOP mount key=user-b",
            "0.195 1 2 I System.out: CORE_LOOP draw key=user-b identity=client-id stored=true assistant=false hashes=7",
        ]
    lines += [
        f"{ms / 1000:.3f} 1 2 I System.out: CORE_LOOP resident key={key}"
        for ms in range(150, 5300, 50)
        for key in ("old", "new")
    ]
    lines.sort(key=lambda line: float(line.split()[0]))
    (root / "logcat.txt").write_text("\n".join(lines))
    (root / "wrapper.jsonl").write_text(
        json.dumps(
            dict(
                wallMs=140,
                line="input.accepted conversationId=fixture disposition=queued",
            )
        )
        + "\n"
        + json.dumps(
            dict(
                wallMs=200,
                line="turn.finished_relayed conversationId=fixture stopReason=end_turn",
            )
        )
        + "\n"
    )
    (root / "pixels").mkdir()
    (root / "pixels" / "summary.json").write_text(
        json.dumps(
            dict(
                frames=100,
                minimum_template_correlation=1,
                dip_percent=0,
                settle_seconds=2,
                post_seconds=5,
                coverage=complete_pixel_record(),
            )
        )
    )
    tree = reply_tree()
    metadata = dict(
        scenario="S1",
        trial=1, captureId="fixture",
        sendMs=100,
        phoneOffsetMs=0,
        miniToPhoneOffsetMs=0,
        existingKeys=["old"],
        stopGoneMs=400,
        clockAligned=True,
        conversationId="fixture",
    )
    return metadata, tree


@pytest.mark.parametrize("duplicate", (False, True))
def test_complete_raw_observations_normalize_without_invented_frames(tmp_path, duplicate):
    directory = tmp_path
    metadata, tree = raw_fixture(directory, duplicate)
    result = evidence.normalize(directory, metadata, tree)
    assert result["status"] == ("FAIL" if duplicate else "PASS"), result["errors"]
    if duplicate:
        assert "duplicate assistant row" in result["errors"]
    else:
        assert result["storedCopyLatencyMs"] == 50


@pytest.mark.parametrize("behavior", ("stable", "assistant_remount", "user_duplicate"))
def test_existing_assistant_remount_is_not_excluded_as_history(tmp_path, behavior):
    root = tmp_path
    metadata, tree = history_fixture(root, behavior)
    result = evidence.normalize(root, metadata, tree)
    assert result["status"] == ("PASS" if behavior == "stable" else "FAIL"), result["errors"]


def test_late_draw_alias_rekeys_earlier_identity():
    events = [dict(event='draw', key='a', identity='run'),
              dict(event='draw', key='b', identity='durable'),
              dict(event='draw', key='a', identity='durable')]
    identities = evidence.assistant_identities(events, {'a', 'b'})
    assert identities['a'] == identities['b']


def test_skipped_pixel_analysis_invalidates_stale_summaries(tmp_path):
    for name in ('pixels', 'partial-pixels'):
        folder = tmp_path / name
        folder.mkdir()
        (folder / 'summary.json').write_text('{}')
    metadata = {'scenario': 'S3'}
    with patch.object(capture, 'capture_region', return_value=None), \
         patch.object(capture, 'normalize', return_value={'status': 'FAIL'}):
        result = capture.analyze_capture(tmp_path, metadata, None)
    assert result['status'] == 'FAIL'
    assert not (tmp_path / 'pixels' / 'summary.json').exists()
    assert not (tmp_path / 'partial-pixels' / 'summary.json').exists()


def test_late_alias_check_rows_has_no_false_removal():
    events = [
        dict(event="draw", key="a", identity="run", ms=0),
        dict(event="draw", key="b", identity="durable", ms=50),
        dict(event="draw", key="a", identity="durable", ms=100),
    ]
    frames = evidence.row_frames(events, events, set())
    window = evidence.oracle.RowWindow()
    errors = []
    for frame in frames:
        evidence.oracle.check_frame(
            frame, window, lambda ok, reason: errors.append(reason) if not ok else None
        )
    assert len(window.identities) == 1
    assert "assistant row removed and re-added" not in errors


@pytest.mark.parametrize("fails", (False, True))
def test_normalize_never_reads_previous_analysis(tmp_path, fails):
    metadata, tree = raw_fixture(tmp_path, False)
    with (
        patch.object(capture, "capture_region", return_value="1,2,3,4" if fails else None),
        patch.object(capture, "capture_terminals", return_value=[]),
        patch.object(
            capture,
            "analyze_final_pixels",
            side_effect=RuntimeError("analysis failed") if fails else None,
        ),
    ):
        if fails:
            with pytest.raises(RuntimeError, match="analysis failed"):
                capture.analyze_capture(tmp_path, metadata, tree)
        else:
            assert capture.analyze_capture(tmp_path, metadata, tree)["status"] == "FAIL"
    result = evidence.normalize(tmp_path, metadata, tree)
    assert result["minimumCorrelation"] is None
    assert result["status"] == "FAIL"


def complete_pixel_record():
    # Independent fixture: a six-second held image with uninterrupted row heartbeats.
    return dict(
        start_seconds=1, end_seconds=7, max_gap_seconds=6,
        frame_times=[1, 7], draw_times=[], log_times=[1 + i / 20 for i in range(121)],
        held_spans=[[1, 7]], missing_spans=[], log_continuous=True, complete=True, reasons=[],
    )


def test_pixel_events_bind_measured_row_and_align_to_video(tmp_path):
    events = [
        '10.000 1 2 I Telemetry/CoreLoop: draw key=reply identity=run-a assistant=true stored=false hashes=0',
        '10.050 1 2 I Telemetry/CoreLoop: resident key=reply',
        '10.075 1 2 I Telemetry/CoreLoop: draw key=unrelated identity=run-b assistant=true stored=false hashes=0',
        '10.100 1 2 I Telemetry/CoreLoop: draw key=reply identity=stored-a assistant=true stored=true hashes=699287813',
        '10.080 1 2 I Telemetry/CoreLoop: draw key=reasoning identity=run-a assistant=true stored=false',
        '10.090 1 2 I Telemetry/CoreLoop: resident key=reasoning',
    ]
    (tmp_path / 'logcat.txt').write_text('\n'.join(events))
    metadata = dict(scenario='S1', videoStartMs=9900, miniToPhoneOffsetMs=20, existingKeys=[])
    terminal = dict(wallMs=10000, line='turn.finished_relayed runId=run-a stopReason=end_turn')
    result = evidence.measured_row_events(tmp_path, metadata, terminal)
    assert result == dict(row_keys=['reply'], draw_times=[0.1, 0.2], log_times=[0.1, 0.15, 0.2])
