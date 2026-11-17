"""Independent mutation checks for the offline evidence scorer, not device acceptance."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('oracle', Path(__file__).with_name('score-core-loop.py'))
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def evidence():
    return dict(scenario='S2', client='pixel2xl', terminalMs=200, storedDisplayedMs=250,
                stopGoneMs=300, visibleText='DONE', clockAligned=True,
                lifecycle=[dict(event='mount', key='reply-1')],
                frames=[dict(ms=ms, rows=[dict(id='message-1', key='reply-1')]) for ms in range(0, 5300, 50)],
                pixels=dict(frames=106, minimum_template_correlation=1.0, dip_percent=0.0))


class OracleTest(unittest.TestCase):
    def test_complete_observations_pass(self):
        result = oracle.score(evidence())
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['storedCopyLatencyMs'], 50)

    def test_every_required_evidence_class_is_mandatory(self):
        for key in evidence():
            with self.subTest(key=key):
                sample = evidence()
                del sample[key]
                self.assertEqual(oracle.score(sample)['status'], 'FAIL')

    def test_rejects_behavioral_regressions(self):
        mutations = [
            lambda e: e['frames'][20]['rows'].clear(),
            lambda e: e['frames'][20]['rows'][0].update(key='new-key'),
            lambda e: e['frames'][20]['rows'].append(copy.deepcopy(e['frames'][20]['rows'][0])),
            lambda e: e['lifecycle'].append(dict(event='dispose', key='reply-1')),
            lambda e: e.update(storedDisplayedMs=4000),
            lambda e: e.update(stopGoneMs=6000),
            lambda e: e.update(visibleText='DONE DONE'),
            lambda e: e['pixels'].update(minimum_template_correlation=0.4),
            lambda e: e['pixels'].update(dip_percent=40),
            lambda e: e['pixels'].update(dip_percent=float('nan')),
            lambda e: e.update(terminalMs=True),
            lambda e: e.update(frames=e['frames'][::4]),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                sample = evidence()
                mutate(sample)
                self.assertEqual(oracle.score(sample)['status'], 'FAIL')

    def test_empty_and_invalid_shapes_fail(self):
        for sample in ({}, dict(frames=None), dict(frames=[{}], pixels=[])):
            with self.subTest(sample=sample):
                self.assertEqual(oracle.score(sample)['status'], 'FAIL')
