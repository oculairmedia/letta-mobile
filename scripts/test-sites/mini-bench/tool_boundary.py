"""Validate evidence-derived arguments before launching capture tools."""
import math
from pathlib import Path
import re
import subprocess


def evidence_path(path, root):
    resolved = Path(path).resolve()
    if not resolved.is_relative_to(Path(root).resolve()):
        raise ValueError('path escapes evidence directory')
    return str(resolved)


def region_arg(value):
    if not re.fullmatch(r'[0-9]+,[0-9]+,[0-9]+,[0-9]+', value):
        raise ValueError('invalid region')
    return value


def number_arg(value):
    number = float(value)
    if not math.isfinite(number):
        raise ValueError('non-finite number')
    return str(number)


def serial_arg(value):
    if not re.fullmatch(r'[A-Za-z0-9.:_-]+', value):
        raise ValueError('invalid serial')
    return value


def run_tool(argv, *, popen=False, **kwargs):
    if not argv or Path(argv[0]).name not in {'adb', 'ssh', 'pgrep', 'python3', 'ffmpeg', 'ffprobe', 'aapt'}:
        raise ValueError('unsupported tool')
    if Path(argv[0]).name == 'adb':
        if len(argv) < 3 or argv[1] != '-s':
            raise ValueError('adb requires a serial')
        serial_arg(argv[2])
    return subprocess.Popen(argv, **kwargs) if popen else subprocess.run(argv, **kwargs)
