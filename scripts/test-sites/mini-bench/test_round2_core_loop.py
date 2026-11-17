"""Behavioural regressions for the nine round-two harness findings."""
import copy
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import xml.etree.ElementTree as ET

import pytest
from coreloop import capture, cli, device, evidence, pixels, scoring
from test_run_core_loop import raw_fixture
from test_score_core_loop import evidence as valid_evidence
from tool_boundary import Tool, tool_arguments


def test_01_video_origin_is_calibrated_and_auditable(tmp_path):
    trial = capture.Capture(None, 'S1', 1)
    trial.directory = tmp_path
    trial.metadata = dict(phoneOffsetMs=20)
    trial.wifi = trial.data = '1'
    with patch.object(capture, 'adb', return_value='123'), patch.object(capture.time, 'sleep'), \
         patch.object(capture, 'wall_ms', return_value=1000), patch.object(capture, 'send', return_value=1100), \
         patch.object(capture, 'perform_scenario_step'):
        trial.start_video()
    assert trial.metadata['videoStartMs'] == pytest.approx(974.89501953125)
    assert trial.metadata.get('videoOrigin', {}).get('samples') == 5
    assert trial.metadata['videoOrigin']['method'] == 'pixel2xl-first-frame-calibration'


@pytest.mark.parametrize('line,stamp', [
    ('turn.finished_relayed conversationId=other stopReason=end_turn', 400),
    ('turn.finished_relayed conversationId=fixture stopReason=cancelled', 100),
    ('turn.finished_relayed conversationId=fixture stopReason=end_turn', 300),
])
def test_02_terminal_wait_is_scoped_after_follow_up(tmp_path, line, stamp):
    trial = capture.Capture(None, 'S3', 1)
    trial.directory = tmp_path
    trial.metadata = dict(conversationId='fixture', resendMs=200, phoneOffsetMs=10, miniToPhoneOffsetMs=20)
    (tmp_path / 'wrapper.jsonl').write_text(json.dumps(dict(line=line, wallMs=stamp))+'\n')
    with patch.object(capture.time, 'monotonic', side_effect=[0, 1, 1000]), \
         patch.object(capture.time, 'sleep'), patch.object(capture, 'dump', return_value=ET.Element('hierarchy')):
        trial.wait_terminal()
    assert ('stopGoneMs' in trial.metadata) is (stamp == 300)


@pytest.mark.parametrize('recovery_error', [False, True])
def test_03_exception_recovers_without_losing_original(tmp_path, recovery_error):
    trial = capture.Capture(None, 'S1', 1)
    trial.directory = tmp_path
    trial.metadata = {}
    error = RuntimeError('snapshot SSH failed')
    with patch.multiple(capture.Capture, **{name: lambda self: None for name in (
            'prepare','collect','phone_clock','open_clock','finish_clock','ready','start_video',
            'wait_terminal','restore','stop_recording','recorder_log','close_collectors')}), \
         patch.object(trial, 'final_snapshot', side_effect=error), \
         patch.object(trial, 'recover', side_effect=RuntimeError('recovery disconnected') if recovery_error else None) as recover:
        with pytest.raises(RuntimeError) as raised:
            trial.run()
    assert raised.value is error
    assert recover.call_count == 1
    if recovery_error:
        assert 'recovery disconnected' in json.dumps(trial.metadata)


def test_04_rescore_leaves_excluded_scenarios_untouched(tmp_path):
    for name in ('S1-1', 'S4-1'):
        directory = tmp_path / name
        directory.mkdir()
        (directory / 'metadata.json').write_text(json.dumps(dict(scenario=name[:2])))
        (directory / 'final.xml').write_text('<hierarchy/>')
        (directory / 'result.json').write_text('original')
    args = SimpleNamespace(output=tmp_path, scenarios=['S1'], region=None)
    with patch.object(capture, 'analyze_capture', return_value=dict(status='PASS')) as analyze:
        results = capture.rescore_captures(args)
    assert len(results) == 1
    assert analyze.call_count == 1
    assert (tmp_path / 'S4-1' / 'result.json').read_text() == 'original'
    assert not (tmp_path / 'S4-1' / 'metadata-pre-rescore.json').exists()


def test_05_aapt_checked_is_aapt_executed(tmp_path):
    args = SimpleNamespace(apk=tmp_path / 'dev.apk')
    with patch.object(Path, 'glob', return_value=[tmp_path / 'different/aapt']), \
         patch.object(Path, 'is_file', return_value=False), patch.object(cli, 'command', return_value=b"package: name='other'") as command:
        with pytest.raises(RuntimeError, match='aapt required'):
            cli.install_capture(args)
    command.assert_not_called()


def test_06_art_path_reaches_tool_boundary_without_quotes():
    path = '/data/app/~~x==/pkg-y==/base.apk'
    calls = []
    def command(argv, **kwargs):
        tool, args = tool_arguments(argv)
        assert tool is Tool.ADB
        calls.append([arg.render() for arg in args])
        return ('package:'+path+'\n').encode() if 'pm' in argv else b'abc123  base.apk\n'
    result, rejected = None, None
    with patch.object(device, 'command', side_effect=command):
        try:
            result = device.installed_app_hash()
        except ValueError as error:
            rejected = str(error)
    assert result == 'abc123', f'ART path rejected before adb: {rejected}'
    assert calls[-1][-1] == path


def reply_rows(tree, entries):
    timeline = ET.SubElement(tree, 'node', scrollable='true')
    for top, text in entries:
        row = ET.SubElement(timeline, 'node', {'long-clickable': 'true', 'bounds': f'[0,{top}][100,{top+50}]'})
        ET.SubElement(row, 'node', text=text)
        if text == 'You':
            ET.SubElement(row, 'node', text='[core-loop:S2] trial-1-fixture')


@pytest.mark.parametrize('actual', ['unexpected prefix DONE', 'DONE unexpected suffix', 'wrong reply'])
def test_07_new_reply_full_text_is_preserved(tmp_path, actual):
    metadata, tree = raw_fixture(tmp_path, False)
    metadata['scenario'] = 'S2'
    tree.clear()
    reply_rows(tree, ((100, 'DONE'), (300, 'You'), (500, actual)))
    metadata['trial'] = 1
    context = evidence.Observation(tmp_path, metadata, tree)
    observed = evidence.normalized_evidence(context, [250])
    assert observed['visibleText'] == actual
    assert scoring.score(observed)['finalTextCorrect'] is False


def test_08_settlement_keeps_endpoint_bracket_and_checks_gap():
    args = SimpleNamespace(settle_seconds=2, post_seconds=1)
    rows = [(0.975+i*.05, 1) for i in range(42)]
    before, during = pixels.settlement_window(rows, args)
    assert during[-1][0] == pytest.approx(3.025)
    assert before
    args.draw_times = [row[0] for row in rows]
    args.log_times = args.draw_times
    assert pixels.pixel_coverage(rows, args)['complete']
    rows[-1] = (3.2, 1)
    args.draw_times = sorted([*args.draw_times[:-1], 3.1])
    args.log_times = [0.95+i*.05 for i in range(47)]
    with pytest.raises(ValueError, match='draw inside pixel gap'):
        pixels.pixel_coverage(rows, args)


@pytest.mark.parametrize('partial', [None, {}, dict(coverage={})])
def test_09_s3_requires_partial_coverage(partial):
    sample = valid_evidence()
    sample.update(scenario='S3', visibleText=scoring.FIXED_REPLY,
                  scenarioEvidence=dict(terminalReason='end_turn', inputCount=2, newStoredTextObserved=True,
                                        cancelled=True, cancelledPartialIncomplete=True, cancelToResendMs=500))
    if partial is not None:
        sample['pixels']['partial'] = partial
    result = scoring.score(sample)
    assert result['status'] == 'FAIL'
    assert 'partial pixel coverage missing or incomplete' in result['errors']
    sample['pixels']['partial'] = copy.deepcopy(valid_evidence()['pixels'])
    assert scoring.score(sample)['status'] == 'PASS'


def test_02_terminal_before_send_now_is_not_follow_up(tmp_path):
    trial = capture.Capture(None, 'S3', 1)
    trial.directory = tmp_path
    trial.metadata = dict(conversationId='fixture', resendMs=100, phoneOffsetMs=0,
                          miniToPhoneOffsetMs=0, followUpSentPhoneMs=250)
    (tmp_path/'wrapper.jsonl').write_text(json.dumps(dict(wallMs=200,
        line='turn.finished_relayed conversationId=fixture stopReason=end_turn'))+'\n')
    with patch.object(capture.time, 'monotonic', side_effect=[0, 1, 1000]), \
         patch.object(capture.time, 'sleep'), patch.object(capture, 'dump', return_value=ET.Element('hierarchy')):
        trial.wait_terminal()
    assert 'stopGoneMs' not in trial.metadata


def test_03_recovery_refreshes_missing_tree_and_checks_ownership(tmp_path):
    trial = capture.Capture(None, 'S1', 1)
    trial.directory = tmp_path
    trial.tree = None
    trial.owns_trial = True
    trial.metadata = dict(conversationId='fixture')
    tree = ET.Element('hierarchy')
    ET.SubElement(tree, 'node', text='mini-bench')
    ET.SubElement(tree, 'node', {'content-desc': 'Stop'})
    with patch.object(capture, 'dump', return_value=tree), \
         patch.object(capture, 'command', return_value=b'{"conversation_id":"someone-else"}'), \
         patch.object(capture, 'adb') as adb:
        with pytest.raises(RuntimeError, match='ownership changed'):
            trial.recover()
    adb.assert_not_called()


def test_07_old_reply_cannot_replace_missing_new_reply(tmp_path):
    metadata, _ = raw_fixture(tmp_path, False)
    metadata['scenario'] = 'S2'
    tree = ET.Element('hierarchy')
    reply_rows(tree, ((100, scoring.FIXED_REPLY), (300, 'You')))
    metadata['trial'] = 1
    context = evidence.Observation(tmp_path, metadata, tree)
    assert evidence.normalized_evidence(context, [250])['visibleText'] == ''


def test_07_reply_paragraphs_and_delivery_timestamp(tmp_path):
    metadata, _ = raw_fixture(tmp_path, False)
    tree = ET.Element('hierarchy')
    timeline = ET.SubElement(tree, 'node', scrollable='true')
    user = ET.SubElement(timeline, 'node', {'long-clickable': 'true', 'bounds':'[0,300][100,400]'})
    ET.SubElement(user, 'node', text='You')
    ET.SubElement(user, 'node', text='[core-loop:S1] trial-1-fixture')
    row = ET.SubElement(timeline, 'node', {'long-clickable': 'true', 'bounds':'[0,500][100,900]'})
    for text in scoring.FIXED_REPLY.split('\n\n'):
        ET.SubElement(row, 'node', text=text)
    ET.SubElement(row, 'node', {'text': '11:39 PM', 'resource-id': 'chat-row-clock'})
    metadata['trial'] = 1
    context = evidence.Observation(tmp_path, metadata, tree)
    assert evidence.normalized_evidence(context, [250])['visibleText'] == scoring.FIXED_REPLY


def test_reply_requires_this_trial_prompt(tmp_path):
    tree = ET.fromstring("""<hierarchy><node scrollable="true">
      <node long-clickable="true" bounds="[0,100][100,150]"><node text="You"/><node text="[core-loop:S1] trial-2"/></node>
      <node long-clickable="true" bounds="[0,200][100,250]"><node text="old reply"/></node>
    </node></hierarchy>""")
    metadata, _ = raw_fixture(tmp_path, False)
    metadata.update(scenario='S1', trial=1)
    context = evidence.Observation(tmp_path, metadata, tree)
    assert evidence.normalized_evidence(context, [250])['visibleText'] == ''


def test_reply_preserves_time_shaped_markdown_paragraph():
    row = ET.fromstring("""<node><node text="DONE"/><node text="11:39 PM"/></node>""")
    assert evidence.row_texts(row) == ['DONE', '11:39 PM']
