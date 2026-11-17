"""Measure reply ink across settlement against its final rendered pixels."""

from dataclasses import dataclass

import csv
import json
from pathlib import Path
import os
from tool_boundary import evidence_path, region_arg, run_tool, tool_arguments, path_arg, filter_arg

os.environ["OPENBLAS_NUM_THREADS"] = "1"
os.environ["OMP_NUM_THREADS"] = "1"
import numpy as np
from PIL import Image


def select_frames(video, raw, begin, end):
    if video.is_dir():
        frames = json.loads((video / "frames.json").read_text())
        selected = [frame for frame in frames if begin <= frame["seconds"] <= end]
        times = [frame["seconds"] for frame in selected]
        paths = [video / frame["file"] for frame in selected]
    else:
        paths, times = decode_frames(video, raw, begin, end)
    return (paths, times)


@dataclass
class Template:
    height: int
    denominator: float
    row_norms: object
    ink_rows: object
    ink_columns: object
    vector: object
    mass: float
    ink_pixels: int


@dataclass
class Crop:
    x: int
    y: int
    width: int
    height: int

    def luminance(self, path, margin=0):
        bounds = (self.x, self.y - margin, self.x + self.width, self.y + self.height + margin)
        rgb = np.asarray(Image.open(path).convert("RGB").crop(bounds), dtype=float)
        return rgb @ np.array([0.2126, 0.7152, 0.0722])


def correlation_at(offset, pixels, template, patch_norms):
    patch = pixels[offset : offset + template.height]
    norm = float(patch_norms[offset])
    if norm == 0:
        return 0.0
    return float(np.clip(patch[template.ink_rows, template.ink_columns] @ template.vector / norm, -1.0, 1.0))


def improves_match(value, best, candidate, offset):
    return value > best or (value == best and candidate < offset)


def best_offset(pixels, template):
    height, denominator, reference_row_norms = (template.height, template.denominator, template.row_norms)
    row_norms = np.sqrt(np.square(pixels).sum(axis=1))
    patch_norms = np.sqrt(np.convolve(np.square(row_norms), np.ones(height), mode="valid") * denominator)
    bounds = np.divide(
        np.correlate(row_norms, reference_row_norms, mode="valid"),
        patch_norms,
        out=np.zeros_like(patch_norms),
        where=patch_norms > 0,
    )
    offset, best = (0, -float("inf"))
    for candidate in np.argsort(bounds)[::-1]:
        if bounds[candidate] + 1e-10 < best:
            break
        value = correlation_at(candidate, pixels, template, patch_norms)
        if improves_match(value, best, candidate, offset):
            offset, best = (int(candidate), value)
    return (offset, patch_norms)


def measure_frame(sample, crop, template, track_y):
    timestamp, path = sample
    pixels = crop.luminance(path, track_y)
    pixels -= np.median(pixels)
    offset, patch_norms = best_offset(pixels, template)
    patch = pixels[offset : offset + template.height]
    alpha = float(np.abs(patch).sum() / template.mass)
    fixed = float(np.abs(pixels[track_y : track_y + template.height]).sum() / template.mass)
    return (
        timestamp,
        alpha,
        float(patch.mean()),
        offset - track_y,
        fixed,
        correlation_at(offset, pixels, template, patch_norms),
    )


def measure_frames(samples, crop, template, track_y):
    return [measure_frame(sample, crop, template, track_y) for sample in samples]


def make_template(reference, height):
    contrast = reference - np.median(reference)
    ink = np.abs(contrast) > 40
    if ink.sum() < 100:
        raise ValueError("region has too little reply ink for analysis")
    ink_rows, ink_columns = np.nonzero(ink)
    vector = contrast[ink_rows, ink_columns]
    row_norms = np.sqrt(np.square(np.where(ink, contrast, 0)).sum(axis=1))
    return Template(
        height,
        float(vector @ vector),
        row_norms,
        ink_rows,
        ink_columns,
        vector,
        float(np.abs(contrast).sum()),
        int(ink.sum()),
    )


def summarize(rows, args, template, reference_seconds):
    before = [row[1] for row in rows if args.settle_seconds - 1.0 <= row[0] <= args.settle_seconds - 0.2]
    during = [row for row in rows if args.settle_seconds - 0.1 <= row[0] <= args.settle_seconds + args.post_seconds]
    if not before or not during:
        raise ValueError("video does not cover the settlement window")
    baseline = float(np.median(before))
    lowest = min(during, key=lambda row: row[1])
    return dict(
        frames=len(rows),
        ink_pixels=template.ink_pixels,
        baseline_alpha=baseline,
        minimum_alpha=lowest[1],
        dip_percent=100 * (baseline - lowest[1]) / baseline,
        minimum_seconds=lowest[0],
        settle_seconds=args.settle_seconds,
        region=list(map(int, region_arg(args.region).split(","))),
        reference_seconds=reference_seconds,
        vertical_search=args.track_y,
        minimum_fixed_alpha=min((row[4] for row in during)),
        minimum_template_correlation=min((row[5] for row in during)),
        paint_loss_frames=sum((row[5] < 0.9 for row in during)),
    )


def write_analysis(output, rows, summary):
    with (output / "frames.csv").open("w") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "seconds",
                "relative_ink_alpha",
                "mean_luminance",
                "vertical_offset",
                "fixed_region_alpha",
                "template_correlation",
            ]
        )
        writer.writerows(rows)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))


def analyze_pixels(args):
    crop = Crop(*map(int, region_arg(args.region).split(",")))
    raw = args.output / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    early = args.reference_seconds - 0.1 if args.reference_seconds is not None else args.settle_seconds - 2
    begin = max(0, min(args.settle_seconds - 2, early))
    paths, times = select_frames(args.video, raw, begin, args.settle_seconds + args.post_seconds)
    if len(paths) != len(times):
        raise ValueError("decoded frames and timestamps differ")
    reference_seconds = args.reference_seconds if args.reference_seconds is not None else args.settle_seconds - 0.5
    reference_index = min(range(len(times)), key=lambda i: abs(times[i] - reference_seconds))
    template = make_template(crop.luminance(paths[reference_index]), crop.height)
    rows = measure_frames(zip(times, paths), crop, template, args.track_y)
    write_analysis(args.output, rows, summarize(rows, args, template, times[reference_index]))


def decode_frames(video, raw, begin, end):
    selection = filter_arg(begin, end)
    video = Path(evidence_path(video, Path(video).parent))
    raw = Path(evidence_path(raw, Path(video).parent))
    run_tool(
        *tool_arguments(
            [
                "ffmpeg",
                "-v",
                "error",
                "-i",
                path_arg(video, video.parent),
                "-vf",
                selection,
                "-vsync",
                "0",
                path_arg(raw / "%06d.png", video.parent),
            ]
        ),
        check=True,
    )
    probe = json.loads(
        run_tool(
            *tool_arguments(
                [
                    "ffprobe",
                    "-v",
                    "error",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "frame=best_effort_timestamp_time",
                    "-of",
                    "json",
                    path_arg(video, video.parent),
                ]
            ),
            check=True,
            capture_output=True,
        ).stdout
    )
    times = [
        float(frame["best_effort_timestamp_time"])
        for frame in probe["frames"]
        if begin <= float(frame["best_effort_timestamp_time"]) <= end
    ]
    paths = sorted(raw.glob("*.png"))
    return paths, times
