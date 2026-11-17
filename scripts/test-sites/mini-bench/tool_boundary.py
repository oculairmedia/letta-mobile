"""Validate evidence-derived arguments before launching capture tools."""

from dataclasses import dataclass
from enum import Enum
import shlex
from coreloop.remote import REMOTE_AUDIT, REMOTE_TAIL, CLOCK

import math
from pathlib import Path
import re
import subprocess


def evidence_path(path, root):
    resolved = Path(path).resolve()
    if not resolved.is_relative_to(Path(root).resolve()):
        raise ValueError("path escapes evidence directory")
    return str(resolved)


def region_arg(value):
    if not re.fullmatch(r"[0-9]+,[0-9]+,[0-9]+,[0-9]+", value):
        raise ValueError("invalid region")
    return value


def number_arg(value):
    number = float(value)
    if not math.isfinite(number):
        raise ValueError("non-finite number")
    return str(number)


def serial_arg(value):
    if not re.fullmatch(r"[A-Za-z0-9.:_-]+", value):
        raise ValueError("invalid serial")
    return value


class Tool(Enum):
    ADB = "/usr/bin/adb"
    SSH = "/usr/bin/ssh"
    PGREP = "/usr/bin/pgrep"
    PYTHON = "/usr/bin/python3"
    FFMPEG = "/usr/bin/ffmpeg"
    FFPROBE = "/usr/bin/ffprobe"
    AAPT = "/opt/android-sdk/build-tools/36.0.0/aapt"


@dataclass(frozen=True)
class TextArg:
    value: str

    def __post_init__(self):
        match = re.fullmatch(r"[A-Za-z0-9.:_/%+,\[\]~=-]+", self.value)
        if not match:
            raise ValueError("unsupported tool argument")
        object.__setattr__(self, "value", match.group(0))

    def render(self):
        return self.value


@dataclass(frozen=True)
class NumberArg:
    value: float

    def __post_init__(self):
        object.__setattr__(self, "value", float(number_arg(self.value)))

    def render(self):
        return str(self.value)


@dataclass(frozen=True)
class PathArg:
    value: Path
    root: Path

    def __post_init__(self):
        object.__setattr__(self, "value", Path(evidence_path(self.value, self.root)))

    def render(self):
        return str(self.value)


@dataclass(frozen=True)
class RegionArg:
    values: tuple[int, ...]

    def __post_init__(self):
        region_arg(",".join(map(str, self.values)))

    def render(self):
        return ",".join(map(str, self.values))


def text_arg(value):
    return TextArg(value)


def path_arg(path, root):
    return PathArg(Path(path), Path(root))


def numeric_arg(value):
    return NumberArg(float(number_arg(value)))


def crop_arg(value):
    return RegionArg(tuple(map(int, region_arg(value).split(","))))


def device_arg(value):
    return text_arg(serial_arg(value))


# Shell syntax is restricted to these exact harness-owned programs.
class FixedProgram(Enum):
    AUDIT = shlex.quote(REMOTE_AUDIT)
    TAIL = shlex.quote(REMOTE_TAIL)
    CLOCK = shlex.quote(CLOCK)
    RECORD = "screenrecord --time-limit 180 /sdcard/core-loop.mp4 >/sdcard/core-loop-screenrecord.log 2>&1 & echo $!"

    def render(self):
        return self.value


FIXED_ARGUMENTS = {program.value: program for program in FixedProgram}
TOOLS_BY_NAME = {Path(tool.value).name: tool for tool in Tool}


def validated_arg(value):
    if isinstance(value, (TextArg, NumberArg, PathArg, RegionArg, FilterArg, FixedProgram)):
        return value
    if value in FIXED_ARGUMENTS:
        return FIXED_ARGUMENTS[value]
    return text_arg(value)


def tool_arguments(argv):
    if not argv:
        raise ValueError("unsupported tool")
    name = Path(argv[0]).name
    if name not in TOOLS_BY_NAME:
        raise ValueError("unsupported tool")
    tool = TOOLS_BY_NAME[name]
    args = list(argv[1:])
    if tool is Tool.ADB:
        args = adb_arguments(args)
    return tool, tuple(map(validated_arg, args))


def adb_arguments(args):
    if len(args) < 2 or args[0] != "-s":
        raise ValueError("adb requires a serial")
    return [text_arg("-s"), device_arg(args[1]), *args[2:]]


def run_tool(tool, args=(), *, popen=False, **kwargs):
    if not isinstance(tool, Tool):
        raise ValueError("unsupported tool")
    if not all(isinstance(arg, (TextArg, NumberArg, PathArg, RegionArg, FilterArg, FixedProgram)) for arg in args):
        raise TypeError("tool arguments must be validated typed values")
    argv = [tool.value, *(arg.render() for arg in args)]
    return subprocess.Popen(argv, **kwargs) if popen else subprocess.run(argv, **kwargs)


@dataclass(frozen=True)
class FilterArg:
    begin: float
    end: float

    def __post_init__(self):
        object.__setattr__(self, "begin", float(number_arg(self.begin)))
        object.__setattr__(self, "end", float(number_arg(self.end)))

    def render(self):
        return rf"select=between(t\,{self.begin}\,{self.end})"


def filter_arg(begin, end):
    return FilterArg(float(number_arg(begin)), float(number_arg(end)))
