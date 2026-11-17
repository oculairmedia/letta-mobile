from pathlib import Path
import time
from . import scoring as oracle
from .device import dump, reply_region, wall_ms, send, tap_node, adb, restore_network
from dataclasses import dataclass


@dataclass
class ScenarioStep:
    directory: Path
    metadata: dict
    index: int
    networking: tuple


def normal_reply(step):
    return None


def working_reply(step):
    return None


def interrupt_reply(step):
    directory, metadata = (step.directory, step.metadata)
    partial_tree = dump(directory, "before-interrupt")
    region = reply_region(partial_tree, oracle.FIXED_REPLY[:30], 70)
    if region:
        metadata["partialRegion"] = region
        metadata["interruptReferenceMs"] = wall_ms() + metadata["phoneOffsetMs"]
    metadata["resendMs"] = send(directory, f"[core-loop:S3] follow-up-{step.index}", "second", fast=True)
    queued = dump(directory, "queued-resend")
    metadata["interruptRequestedMs"] = wall_ms() + metadata["phoneOffsetMs"]
    tap_node(queued, "text", "Send now")


def reconnect_reply(step):
    metadata = step.metadata
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    metadata["networkDisabledMs"] = wall_ms() + metadata["phoneOffsetMs"]
    metadata["networkDisabled"] = (
        adb("shell", "settings", "get", "global", "wifi_on") == "0"
        and adb("shell", "settings", "get", "global", "mobile_data") == "0"
    )
    time.sleep(5)
    restore_network(*step.networking)
    metadata["networkRestoredMs"] = wall_ms() + metadata["phoneOffsetMs"]
    metadata["networkVariant"] = "wifi off/on; cellular disabled during drop"


STEPS = {"S1": normal_reply, "S2": working_reply, "S3": interrupt_reply, "S4": reconnect_reply}


def perform_scenario_step(step, scenario):
    STEPS[scenario](step)
