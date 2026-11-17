#!/usr/bin/env python3
"""Measure reply ink across settlement against its final rendered pixels."""
import argparse
import csv
import json
from pathlib import Path
import subprocess
import os
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from tool_boundary import evidence_path, number_arg, region_arg, run_tool
# Small per-frame dot products must not launch a host-sized BLAS pool.
os.environ['OPENBLAS_NUM_THREADS'] = '1'
os.environ['OMP_NUM_THREADS'] = '1'
import numpy as np
from PIL import Image


def select_frames(video, raw, begin, end):
    if video.is_dir():
        frames = json.loads((video / 'frames.json').read_text())
        selected = [frame for frame in frames if begin <= frame['seconds'] <= end]
        times = [frame['seconds'] for frame in selected]
        paths = [video / frame['file'] for frame in selected]
    else:
        selection = rf'select=between(t\,{number_arg(begin)}\,{number_arg(end)})'
        video = evidence_path(video, Path(video).parent)
        raw = Path(evidence_path(raw, Path(video).parent))
        run_tool(['ffmpeg', '-v', 'error', '-i', str(video), '-vf', selection,
            '-vsync', '0', str(raw / '%06d.png')], check=True)
        probe = json.loads(run_tool(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
            '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(video)], check=True, capture_output=True).stdout)
        times = [float(frame['best_effort_timestamp_time']) for frame in probe['frames']
            if begin <= float(frame['best_effort_timestamp_time']) <= end]
        paths = sorted(raw.glob('*.png'))
    return paths, times


def correlation_at(offset, pixels, height, patch_norms, ink_rows, ink_columns, vector):
    patch = pixels[offset:offset + height]
    norm = float(patch_norms[offset])
    if norm == 0:
        return 0.0
    return float(np.clip(patch[ink_rows, ink_columns] @ vector / norm, -1.0, 1.0))


def improves_match(value, best, candidate, offset):
    return value > best or (value == best and candidate < offset)


def best_offset(pixels, height, denominator, reference_row_norms, ink_rows, ink_columns, vector):
    # Cauchy-Schwarz bounds prune candidates without approximating the pixel oracle.
    row_norms = np.sqrt(np.square(pixels).sum(axis=1))
    patch_norms = np.sqrt(np.convolve(np.square(row_norms), np.ones(height), mode='valid') * denominator)
    bounds = np.divide(np.correlate(row_norms, reference_row_norms, mode='valid'),
                       patch_norms, out=np.zeros_like(patch_norms), where=patch_norms > 0)
    offset, best = 0, -float('inf')
    for candidate in np.argsort(bounds)[::-1]:
        if bounds[candidate] + 1e-10 < best:
            break
        value = correlation_at(candidate, pixels, height, patch_norms, ink_rows, ink_columns, vector)
        if improves_match(value, best, candidate, offset):
            offset, best = int(candidate), value
    return offset, patch_norms


def measure_frames(times, paths, luminance, track_y, height, denominator, reference_row_norms, ink_rows, ink_columns, vector, reference_mass):
    rows = []
    for timestamp, path in zip(times, paths):
        pixels = luminance(path, track_y)
        pixels -= np.median(pixels)
        offset, patch_norms = best_offset(pixels, height, denominator, reference_row_norms, ink_rows, ink_columns, vector)
        patch = pixels[offset:offset + height]
        alpha = float(np.abs(patch).sum() / reference_mass)
        fixed = float(np.abs(pixels[track_y:track_y + height]).sum() / reference_mass)
        rows.append((timestamp, alpha, float(patch.mean()), offset - track_y, fixed, correlation_at(offset, pixels, height, patch_norms, ink_rows, ink_columns, vector)))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('video', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--region', required=True, help='x,y,width,height of reply text, excluding chrome')
    parser.add_argument('--settle-seconds', type=float, required=True)
    parser.add_argument('--reference-seconds', type=float, help='explicit stable pre-event template timestamp')
    parser.add_argument('--post-seconds', type=float, default=20, help='include live clearance and post-send Paging revisions')
    parser.add_argument('--track-y', type=int, default=320, help='vertical search radius for the reply bounding box')
    args = parser.parse_args()
    x, y, width, height = map(int, region_arg(args.region).split(','))
    raw = args.output / 'raw'
    raw.mkdir(parents=True, exist_ok=True)
    begin = max(0,min(args.settle_seconds - 2,args.reference_seconds - .1 if args.reference_seconds is not None else args.settle_seconds - 2))
    end = args.settle_seconds + args.post_seconds
    paths, times = select_frames(args.video, raw, begin, end)
    if len(paths) != len(times):
        raise ValueError('decoded frames and timestamps differ')
    def luminance(path, margin=0):
        rgb = np.asarray(Image.open(path).convert('RGB').crop((x, y - margin, x + width, y + height + margin)), dtype=float)
        return rgb @ np.array([0.2126, 0.7152, 0.0722])
    reference_seconds = args.reference_seconds if args.reference_seconds is not None else args.settle_seconds - 0.5
    reference_index = min(range(len(times)), key=lambda i: abs(times[i] - reference_seconds))
    reference = luminance(paths[reference_index])
    contrast = reference - np.median(reference)
    ink = np.abs(contrast) > 40
    if ink.sum() < 100:
        raise ValueError('region has too little reply ink for analysis')
    ink_rows, ink_columns = np.nonzero(ink)
    vector = contrast[ink_rows, ink_columns]
    denominator = float(vector @ vector)
    reference_mass = float(np.abs(contrast).sum())
    reference_row_norms = np.sqrt(np.square(np.where(ink, contrast, 0)).sum(axis=1))
    rows = measure_frames(times, paths, luminance, args.track_y, height, denominator, reference_row_norms, ink_rows, ink_columns, vector, reference_mass)
    before = [row[1] for row in rows if args.settle_seconds - 1.0 <= row[0] <= args.settle_seconds - 0.2]
    during = [row for row in rows if args.settle_seconds - 0.1 <= row[0] <= args.settle_seconds + args.post_seconds]
    if not before or not during:
        raise ValueError('video does not cover the settlement window')
    baseline = float(np.median(before))
    lowest = min(during, key=lambda row: row[1])
    summary = dict(frames=len(rows), ink_pixels=int(ink.sum()), baseline_alpha=baseline,
        minimum_alpha=lowest[1], dip_percent=100 * (baseline - lowest[1]) / baseline,
        minimum_seconds=lowest[0], settle_seconds=args.settle_seconds, region=[x, y, width, height],
        reference_seconds=times[reference_index], vertical_search=args.track_y,
        minimum_fixed_alpha=min(row[4] for row in during), minimum_template_correlation=min(row[5] for row in during),
        paint_loss_frames=sum(row[5] < 0.9 for row in during))
    with (args.output / 'frames.csv').open('w') as handle:
        writer = csv.writer(handle)
        writer.writerow(['seconds', 'relative_ink_alpha', 'mean_luminance', 'vertical_offset', 'fixed_region_alpha', 'template_correlation'])
        writer.writerows(rows)
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
