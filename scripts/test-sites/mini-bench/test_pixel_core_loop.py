"""Check the reused pixel oracle detects paint loss using independent images."""

import json
from pathlib import Path
import subprocess
from PIL import Image


import pytest
from PIL import ImageDraw


def ink_image():
    image = Image.new("RGB", (64, 64), "white")
    points = [(x, y) for x in range(8, 56) for y in range(8, 56) if x % 4 < 2 and y % 4 < 2]
    ImageDraw.Draw(image).point(points, fill=(0, 0, 0))
    return image


def frame_image(ink, seconds, options):
    missing, contrast, partial = options
    image = Image.new("RGB", (64, 64), "white") if missing and seconds == 2.5 else ink
    if not missing and seconds < 1.9:
        image = image.point(lambda value: 255 - contrast if value == 0 else value)
    if partial and seconds >= 2:
        image = image.copy()
        ImageDraw.Draw(image).rectangle((0, 0, 31, 63), fill="white")
    return image


def make_frames(video, options):
    ink = ink_image()
    frames = []
    for index, seconds in enumerate(index / 20 for index in range(61)):
        filename = f"{index}.png"
        frame_image(ink, seconds, options).save(video / filename)
        frames.append(dict(file=filename, seconds=seconds))
    return frames


@pytest.mark.parametrize(
    "missing,contrast,partial", ((False, 255, False), (True, 255, False), (False, 100, False), (False, 100, True))
)
def test_stable_ink_and_one_frame_paint_loss(tmp_path, missing, contrast, partial):
    video = tmp_path / "frames"
    video.mkdir()
    frames = make_frames(video, (missing, contrast, partial))
    (video / "frames.json").write_text(json.dumps(frames))
    output = tmp_path / "analysis"
    output.mkdir()
    times = [frame["seconds"] for frame in frames]
    (output / "row-evidence.json").write_text(json.dumps(dict(draw_times=times, log_times=times)))
    result = subprocess.run(
        [
            "python3",
            str(Path(__file__).with_name("analyze-settle.py")),
            str(video),
            str(tmp_path / "analysis"),
            "--region",
            "0,0,64,64",
            "--settle-seconds",
            "2",
            "--post-seconds",
            "1",
            "--track-y",
            "0",
        ],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr
    score = json.loads((tmp_path / "analysis" / "summary.json").read_text())
    expected_correlation, expected_dip = expected_metrics(missing, contrast, partial)
    assert score["minimum_template_correlation"] == pytest.approx(expected_correlation, abs=0.5e-7, rel=0)
    assert score["dip_percent"] == pytest.approx(expected_dip, abs=0.5e-7, rel=0)


def expected_metrics(missing, contrast, partial):
    correlation = 0 if missing else 0.5**0.5 if partial else 1
    dip = 100 if missing else 100 * (1 - 255 / contrast * (0.5 if partial else 1))
    return correlation, dip


def test_incomplete_pixel_window_is_rejected():
    from types import SimpleNamespace
    from coreloop.pixels import summarize

    args = SimpleNamespace(settle_seconds=2, post_seconds=20, region='0,0,64,64', track_y=0)
    template = SimpleNamespace(ink_pixels=100)
    rows = [(t, 1, 0, 0, 1, 1) for t in (1, 1.5, 2)]
    with pytest.raises(ValueError, match='coverage'):
        summarize(rows, args, template, 1.5)


@pytest.mark.parametrize(
    "times,valid",
    (
        ([1 + i / 10 for i in range(21)], True),
        ([1, 1.15] + [1.2 + i / 10 for i in range(19)], False),
        ([1 + i / 20 for i in range(40)], False),
    ),
)
def test_analyzer_coverage_boundaries(times, valid):
    from types import SimpleNamespace
    from coreloop.pixels import pixel_coverage

    rows = [(t, 1) for t in times]
    args = SimpleNamespace(settle_seconds=2, post_seconds=1, draw_times=times, log_times=times)
    if valid:
        assert pixel_coverage(rows, args)["end_seconds"] == 3
    else:
        with pytest.raises(ValueError, match="coverage"):
            pixel_coverage(rows, args)

@pytest.mark.parametrize("draws,logs,reason", [
    ([], [1 + i / 20 for i in range(41)], None),
    ([1.5], [1 + i / 20 for i in range(41)], "draw inside pixel gap"),
    ([], [1, 1.5, 2, 2.5, 3], "row log discontinuous"),
])
def test_vfr_coverage_requires_draw_and_resident_evidence(draws, logs, reason):
    from types import SimpleNamespace
    from coreloop.pixels import pixel_coverage

    rows = [(1.0, 1), (2.0, 1), (3.0, 1)]
    args = SimpleNamespace(settle_seconds=2, post_seconds=1, draw_times=draws, log_times=logs)
    if reason:
        with pytest.raises(ValueError, match=reason):
            pixel_coverage(rows, args)
    else:
        coverage = pixel_coverage(rows, args)
        assert coverage["complete"] is True
        assert coverage["held_spans"] == [[1, 2], [2, 3]]
        assert coverage["missing_spans"] == []


def test_held_pixels_contribute_previous_values_to_window():
    from types import SimpleNamespace
    from coreloop.pixels import held_measurements, settlement_window

    args = SimpleNamespace(settle_seconds=2, post_seconds=1)
    rows = [(0.5, 0.25), (3.5, 1)]
    carried = held_measurements(rows, dict(held_spans=[[0.5, 3.5]]), args)
    before, during = settlement_window(carried, args)
    assert before == [0.25, 0.25]
    assert [row[1] for row in during] == [0.25, 0.25]


def test_extraction_preserves_exact_vfr_pts(tmp_path, capfd):
    from coreloop.pixels import decode_frames

    video = tmp_path / "vfr.mp4"
    subprocess.run([
        "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=64x64:rate=30:duration=2",
        "-vf", "select=eq(n\\,0)+eq(n\\,1)+eq(n\\,20)+eq(n\\,21)+eq(n\\,59)",
        "-fps_mode", "vfr", "-pix_fmt", "yuv420p", str(video),
    ], check=True, capture_output=True)
    raw = tmp_path / "raw"
    raw.mkdir()
    paths, times = decode_frames(video, raw, 0, 2)
    expected = json.loads(subprocess.run([
        "ffprobe", "-v", "error", "-show_entries", "frame=best_effort_timestamp,best_effort_timestamp_time",
        "-of", "json", str(video),
    ], check=True, capture_output=True).stdout)["frames"]
    assert times == [float(frame["best_effort_timestamp_time"]) for frame in expected]
    assert [int(path.stem) for path in paths] == [int(frame["best_effort_timestamp"]) for frame in expected]
    assert all(path.exists() for path in paths)
    assert "non monotonically" not in capfd.readouterr().err
