"""Independent mutation checks for the offline evidence scorer, not device acceptance."""

import copy
import unittest

from coreloop import scoring as oracle


def evidence():
    return dict(
        scenario="S2",
        client="pixel2xl",
        initialUnclassifiedRows=0,
        duplicateUserRowSamples=0,
        terminalMs=200,
        storedDisplayedMs=250,
        stopGoneMs=300,
        finalStopPresent=False,
        visibleText="DONE",
        clockAligned=True,
        scenarioEvidence=dict(
            terminalReason="end_turn",
            inputCount=1,
            newStoredTextObserved=True,
            heldToolMs=40000,
            heldToolExpectedMs=40000,
        ),
        lifecycle=[dict(event="mount", key="reply-1")],
        frames=[dict(ms=ms, rows=[dict(id="message-1", key="reply-1")]) for ms in range(0, 5300, 50)],
        pixels=dict(
            frames=106,
            minimum_template_correlation=1.0,
            dip_percent=0.0,
            settle_seconds=2,
            post_seconds=5,
            coverage=complete_pixel_record(),
        ),
    )


class OracleTest(unittest.TestCase):
    def test_complete_observations_pass(self):
        result = oracle.score(evidence())
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["storedCopyLatencyMs"], 50)

    def test_every_required_evidence_class_is_mandatory(self):
        for key in evidence():
            with self.subTest(key=key):
                sample = evidence()
                del sample[key]
                self.assertEqual(oracle.score(sample)["status"], "FAIL")

    def test_rejects_behavioral_regressions(self):
        mutations = [
            lambda e: e["frames"][20]["rows"].clear(),
            lambda e: e["frames"][20]["rows"][0].update(key="new-key"),
            lambda e: e["frames"][20]["rows"].append(copy.deepcopy(e["frames"][20]["rows"][0])),
            lambda e: e["lifecycle"].append(dict(event="dispose", key="reply-1")),
            lambda e: e.update(storedDisplayedMs=4000),
            lambda e: e.update(stopGoneMs=6000),
            lambda e: e.update(finalStopPresent=True),
            lambda e: e.update(visibleText="DONE DONE"),
            lambda e: e["pixels"].update(minimum_template_correlation=0.4),
            lambda e: e["pixels"].update(minimum_template_correlation=1.01),
            lambda e: e["pixels"].update(dip_percent=40),
            lambda e: e["pixels"].update(dip_percent=float("nan")),
            lambda e: e.update(terminalMs=True),
            lambda e: e.update(duplicateUserRowSamples=1),
            lambda e: e.update(initialUnclassifiedRows=1),
            lambda e: e.update(frames=e["frames"][::4]),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                sample = evidence()
                mutate(sample)
                self.assertEqual(oracle.score(sample)["status"], "FAIL")

    def test_empty_and_invalid_shapes_fail(self):
        for sample in ({}, dict(frames=None), dict(frames=[{}], pixels=[])):
            with self.subTest(sample=sample):
                self.assertEqual(oracle.score(sample)["status"], "FAIL")

    def test_s3_requires_cancel_and_tight_follow_up(self):
        sample = evidence()
        sample.update(
            scenario="S3",
            visibleText=oracle.FIXED_REPLY,
            scenarioEvidence=dict(
                terminalReason="end_turn",
                inputCount=2,
                newStoredTextObserved=True,
                cancelled=True,
                cancelledPartialIncomplete=True,
                cancelToResendMs=500,
            ),
        )
        self.assertEqual(oracle.score(sample)["status"], "PASS")
        for key in ("cancelled", "cancelledPartialIncomplete", "cancelToResendMs"):
            invalid = copy.deepcopy(sample)
            del invalid["scenarioEvidence"][key]
            self.assertEqual(oracle.score(invalid)["status"], "FAIL")
        sample["scenarioEvidence"]["cancelToResendMs"] = 2000
        self.assertEqual(oracle.score(sample)["status"], "FAIL")

    def test_s4_requires_verified_outage_during_live_reply(self):
        sample = evidence()
        sample.update(
            scenario="S4",
            visibleText=oracle.FIXED_REPLY,
            scenarioEvidence=dict(
                terminalReason="end_turn",
                inputCount=1,
                newStoredTextObserved=True,
                networkDownMs=5000,
                networkOverlappedLive=True,
            ),
        )
        self.assertEqual(oracle.score(sample)["status"], "PASS")
        sample["scenarioEvidence"]["networkOverlappedLive"] = False
        self.assertEqual(oracle.score(sample)["status"], "FAIL")


def test_nan_diagnostic_does_not_abort_batch(tmp_path):
    import json
    import subprocess
    from pathlib import Path

    invalid = evidence()
    invalid['initialUnclassifiedRows'] = float('nan')
    first, second = tmp_path / 'first.json', tmp_path / 'second.json'
    first.write_text(json.dumps(invalid))
    second.write_text(json.dumps(evidence()))
    output = tmp_path / 'results'
    process = subprocess.run(['python3', str(Path(__file__).with_name('score-core-loop.py')),
                              str(first), str(second), '--output', str(output)], capture_output=True)
    assert process.returncode == 1
    assert (output / 'run-1.json').exists()
    assert json.loads((output / 'run-1.json').read_text())['status'] == 'FAIL'
    assert json.loads((output / 'run-2.json').read_text())['status'] == 'PASS'


def test_pixel_coverage_record_is_mandatory():
    sample = evidence()
    del sample["pixels"]["coverage"]
    assert oracle.score(sample)["status"] == "FAIL"


def test_pixel_coverage_boundaries():
    for gap, end, expected in (
        (6, 7, "PASS"),
        (0.15, 7, "FAIL"),
        (0.1, 6.95, "FAIL"),
    ):
        sample = evidence()
        sample["pixels"]["coverage"].update(max_gap_seconds=gap, end_seconds=end)
        assert oracle.score(sample)["status"] == expected


def test_partial_pixel_coverage_is_enforced():
    sample = evidence()
    sample["pixels"]["partial"] = {}
    assert oracle.score(sample)["status"] == "FAIL"


def complete_pixel_record():
    # Independent fixture: a six-second held image with uninterrupted row heartbeats.
    return dict(
        start_seconds=1, end_seconds=7, max_gap_seconds=6,
        frame_times=[1, 7], draw_times=[], log_times=[1 + i / 20 for i in range(121)],
        held_spans=[[1, 7]], missing_spans=[], log_continuous=True, complete=True, reasons=[],
    )


def test_scorer_recomputes_vfr_coverage_instead_of_trusting_flags():
    sample = evidence()
    assert oracle.score(sample)["status"] == "PASS"
    sample["pixels"]["coverage"]["draw_times"] = [3]
    assert oracle.score(sample)["status"] == "FAIL"
    sample["pixels"]["coverage"]["draw_times"] = []
    sample["pixels"]["coverage"]["log_times"] = [1, 7]
    assert oracle.score(sample)["status"] == "FAIL"
