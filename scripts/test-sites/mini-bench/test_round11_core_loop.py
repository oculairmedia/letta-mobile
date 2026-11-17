"""Final review regressions: capture identity, clock tags and fail-closed evidence."""
import copy
from types import SimpleNamespace
from unittest.mock import patch
import xml.etree.ElementTree as ET

import pytest
from coreloop import capture, cli, device, evidence, scenarios, scoring
from test_run_core_loop import raw_fixture
from test_score_core_loop import evidence as valid_evidence


@pytest.mark.parametrize('resource', ['chat-row-clock', 'com.letta.mobile.dev:id/chat-row-clock'])
def test_clock_tag_drops_only_clock(resource):
    row = ET.fromstring('<node><node text="DONE"/><node text="11:39 PM"/><node text="11:40 PM"/></node>')
    row[-1].set('resource-id', resource)
    assert evidence.row_texts(row) == ['DONE', '11:39 PM']
    row[-1].set('resource-id', 'delivery-time')
    assert evidence.row_texts(row) == ['DONE', '11:39 PM', '11:40 PM']


def test_capture_prompt_is_unique_and_identifies_both_sends(tmp_path):
    prompts = []
    for run in ('first-capture', 'second-capture'):
        trial = capture.Capture(SimpleNamespace(capture_id=run), 'S3', 1)
        trial.directory = tmp_path
        trial.metadata = dict(phoneOffsetMs=0, captureId=run, scenario='S3', trial=1)
        trial.wifi = trial.data = '1'
        with patch.object(capture, 'adb', return_value='123'), patch.object(capture.time, 'sleep'), \
             patch.object(capture, 'send', return_value=1000) as first, \
             patch.object(capture, 'perform_scenario_step'):
            trial.start_video()
        with patch.object(scenarios, 'dump'), patch.object(scenarios, 'reply_region', return_value=None), \
             patch.object(scenarios, 'send', return_value=2000) as second, patch.object(scenarios, 'tap_node'):
            scenarios.interrupt_reply(scenarios.ScenarioStep(tmp_path, trial.metadata, 1, ('1', '1')))
        sent = [first.call_args.args[1], second.call_args.args[1]]
        assert all(run in prompt for prompt in sent)
        assert sent[0] != sent[1]
        prompts.append(sent)
    assert set(prompts[0]).isdisjoint(prompts[1])


def test_stale_capture_with_same_trial_number_cannot_supply_reply(tmp_path):
    metadata, tree = raw_fixture(tmp_path, False)
    metadata.update(scenario='S1', trial=1, captureId='new-capture')
    context = evidence.Observation(tmp_path, metadata, tree)
    assert evidence.normalized_evidence(context, [250])['visibleText'] == ''
    for node in tree.iter('node'):
        if node.get('text') == '[core-loop:S1] trial-1-fixture':
            node.set('text', '[core-loop:S1] trial-1-new-capture')
    assert evidence.normalized_evidence(context, [250])['visibleText'] == scoring.FIXED_REPLY
    metadata['captureId'] = ''
    assert evidence.normalized_evidence(context, [250])['visibleText'] == ''


@pytest.mark.parametrize('bad', [None, '2', True, float('nan'), float('inf')])
def test_invalid_pixel_timestamp_is_normal_coverage_fail(bad):
    sample = valid_evidence()
    sample['pixels']['coverage']['frame_times'] = [1, bad]
    result = scoring.score(sample)
    assert result['status'] == 'FAIL'
    assert 'pixel coverage missing or incomplete' in result['errors']


@pytest.mark.parametrize('field,value,reason', [
    ('minimum_template_correlation', 0, 'partial pixel template missing or lost'),
    ('minimum_template_correlation', 1.01, 'partial pixel template missing or lost'),
    ('minimum_template_correlation', None, 'partial pixel template missing or lost'),
    ('dip_percent', 100, 'partial reply luminance missing or dipped'),
    ('dip_percent', None, 'partial reply luminance missing or dipped'),
])
def test_partial_metrics_have_final_bounds(field, value, reason):
    sample = valid_evidence()
    sample.update(scenario='S3', visibleText=scoring.FIXED_REPLY,
                  scenarioEvidence=dict(terminalReason='end_turn', inputCount=2, newStoredTextObserved=True,
                                        cancelled=True, cancelledPartialIncomplete=True, cancelToResendMs=500))
    sample['pixels']['partial'] = copy.deepcopy(sample['pixels'])
    assert scoring.score(sample)['status'] == 'PASS'
    sample['pixels']['partial'][field] = value
    assert reason in scoring.score(sample)['errors']


def test_failed_uiautomator_dump_cannot_read_stale_tree(tmp_path):
    calls = []
    remote = {'tree': b'<hierarchy><node text="stale"/></hierarchy>'}
    def adb(*args):
        calls.append(args)
        if args[:3] == ('shell', 'rm', '-f'):
            remote.clear()
        return ''  # uiautomator can exit zero without producing a file
    def read(*args, **kwargs):
        if not remote:
            raise RuntimeError('device dump missing')
        return remote['tree']
    with patch.object(device, 'adb', side_effect=adb), patch.object(device, 'command', side_effect=read):
        with pytest.raises(RuntimeError, match='device dump missing'):
            device.dump(tmp_path, 'final')
    assert calls[0] == ('shell', 'rm', '-f', '/sdcard/core-loop-ui.xml')
    assert not (tmp_path/'final.xml').exists()


def test_capture_invocations_allocate_distinct_identity_once(tmp_path):
    identities = []
    for name in ('first', 'second'):
        args = SimpleNamespace(serial=cli.SERIAL, trials=3, apk=tmp_path/'app.apk',
                               output=tmp_path/name, install=False)
        with patch.object(cli, 'wait_for_pixel'), patch.object(cli, 'adb', return_value='device'), \
             patch.object(cli, 'apk_digest', return_value='hash'), \
             patch.object(cli, 'installed_app_hash', return_value='hash'), patch.object(cli.signal, 'signal'):
            cli.prepare_capture(args, None)
        identities.append(getattr(args, 'capture_id', None))
    assert all(isinstance(value, str) and len(value) == 32 for value in identities)
    assert identities[0] != identities[1]
