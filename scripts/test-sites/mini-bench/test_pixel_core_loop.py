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
    if not missing and seconds < 2:
        image = image.point(lambda value: 255 - contrast if value == 0 else value)
    if partial and seconds >= 2:
        image = image.copy()
        ImageDraw.Draw(image).rectangle((0, 0, 31, 63), fill="white")
    return image


def make_frames(video, options):
    ink = ink_image()
    frames = []
    for index, seconds in enumerate((0, 1, 1.5, 1.8, 2, 2.5, 3)):
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
