"""Driver steps recorded from 812c7a73e before the package restructure."""

import json
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch
import pytest
from coreloop import scenarios as driver
from coreloop.scenarios import ScenarioStep


def record_call(calls, name, result):
    def call(*args, **kwargs):
        calls.append([name, [str(arg) for arg in args], kwargs])
        return result

    return call


@pytest.mark.parametrize("scenario", ("S1", "S2", "S3", "S4"))
def test_recorded_steps(scenario):
    calls = []
    values = dict(
        dump="TREE", reply_region="1,2,3,4", wall_ms=1000, send=2000, tap_node=None, adb="0", restore_network={}
    )
    with ExitStack() as stack:
        for name, result in values.items():
            stack.enter_context(patch.object(driver, name, side_effect=record_call(calls, name, result)))
        stack.enter_context(patch.object(driver.time, "sleep", side_effect=record_call(calls, "sleep", None)))
        metadata = {"phoneOffsetMs": 5}
        driver.perform_scenario_step(ScenarioStep(Path("capture"), metadata, 2, ("1", "0")), scenario)
    expected = json.loads(Path(__file__).with_name("scenario-steps.json").read_text())[scenario]
    assert {"calls": calls, "metadata": metadata} == expected
