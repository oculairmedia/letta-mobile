#!/usr/bin/env python3
"""Pixel 2 XL-only core-loop evidence capture. Never selects an arbitrary adb device.

Requires the main app plus opt-in root-debug CORE_LOOP diagnostics, mini-bench
fixture installed on graphiti, and mini-bench already open in the phone. Run with
--output <artifact directory>; records three trials per scenario by default.
Network state is restored in finally and on termination; restoration failure is
an error. No install, model changes, server restart or adb-server restart occurs.
"""
import argparse
import importlib.util
import json
import hashlib
import os
from pathlib import Path
import re
import signal
import shlex
import select
import shutil
import subprocess
import time
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from tool_boundary import evidence_path, number_arg, region_arg, run_tool
import xml.etree.ElementTree as ET

SERIAL = '711KPAE0914240'
PACKAGE = 'com.letta.mobile.dev'
SCENARIO_TIMEOUTS = {'S1': 75, 'S2': 150, 'S3': 75, 'S4': 75}
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('oracle', HERE / 'score-core-loop.py')
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def command(argv, **kwargs):
    return run_tool(argv, check=True, capture_output=True, timeout=30, **kwargs).stdout


def adb(*args):
    return command(['adb', '-s', SERIAL, *args]).decode().strip()


def installed_app_hash():
    paths = adb('shell','pm','path',PACKAGE).splitlines()
    if len(paths)!=1 or not paths[0].startswith('package:'):
        raise RuntimeError('expected one installed capture APK')
    path = paths[0].removeprefix('package:')
    return adb('shell','sha256sum',shlex.quote(path)).split()[0]


def wall_ms():
    return time.time_ns() / 1_000_000


def dump(directory, name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/core-loop-ui.xml')
    raw = command(['adb', '-s', SERIAL, 'exec-out', 'cat', '/sdcard/core-loop-ui.xml'])
    (directory / f'{name}.xml').write_bytes(raw)
    return ET.fromstring(raw)


def tap_node(tree, attribute, value):
    nodes = [n for n in tree.iter('node') if n.get(attribute) == value]
    if len(nodes) != 1:
        raise RuntimeError(f'expected one {attribute}={value}, got {len(nodes)}')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', nodes[0].get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def reply_region(tree, prefix, maximum_height):
    matches = []
    for node in tree.iter('node'):
        if prefix not in node.get('text', ''):
            continue
        bounds = tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))
        if len(bounds)==4:
            matches.append(bounds)
    if not matches:
        return None
    # Accessibility traversal is reversed on this timeline. Select by physical
    # position, so an identical reply from an older turn cannot become the oracle.
    x1,y1,x2,y2 = max(matches,key=lambda bounds:bounds[1])
    return f'{x1},{y1},{x2-x1},{min(y2-y1,maximum_height)}'


def send(directory, prompt, name, fast=False):
    tree = ET.parse(directory / 'first-composer.xml').getroot() if fast else dump(directory, name + '-composer')
    tap_node(tree, 'class', 'android.widget.EditText')
    # adb input text accepts this restricted fixture alphabet, with spaces escaped.
    adb('shell', 'input', 'text', prompt.replace(' ', '%s'))
    adb('shell', 'input', 'keyevent', '4')
    tree = ET.parse(directory / 'first-typed.xml').getroot() if fast else dump(directory, name + '-typed')
    sent = wall_ms()
    tap_node(tree, 'content-desc', 'Send message')
    return sent


def restore_network(wifi, data):
    errors = []
    for service, value in (('wifi', wifi), ('data', data)):
        try:
            adb('shell', 'svc', service, 'enable' if value == '1' else 'disable')
        except Exception as error:
            errors.append(str(error))
    restored = {}
    for key, expected in (('wifi_on',wifi),('mobile_data',data)):
        try:
            restored[key] = adb('shell','settings','get','global',key)
            if restored[key] != expected:
                errors.append(f'{key} expected {expected}, observed {restored[key]}')
        except Exception as error:
            errors.append(str(error))
    if errors:
        raise RuntimeError('NETWORK RESTORE FAILED: ' + '; '.join(errors))
    return restored


def stop_process(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def parse_log(path):
    events = []
    for line in path.read_text(errors='replace').splitlines():
        timestamp = line.lstrip().split(maxsplit=1)[0] if line.strip() else ''
        if not re.fullmatch(r'[0-9]+\.[0-9]+', timestamp):
            continue
        positions = [(line.find(marker), marker) for marker in ('CORE_LOOP ', 'Telemetry/CoreLoop: ')]
        positions = [(position, marker) for position, marker in positions if position >= 0]
        if not positions:
            continue
        position, marker = min(positions)
        parts = line[position + len(marker):].split()
        if not parts or parts[0] not in ('mount', 'dispose', 'draw', 'resident'):
            continue
        fields = {}
        for part in parts[1:]:
            key, separator, value = part.partition('=')
            if separator and re.fullmatch(r'\w+', key) and value:
                fields[key] = value
        events.append(dict(ms=float(timestamp) * 1000, event=parts[0], **fields))
    return events


def wrapper_terminals(directory, metadata):
    terminal = []
    wrapper_events = []
    for line in (directory / 'wrapper.jsonl').read_text(errors='replace').splitlines():
        try:
            event = json.loads(line)
            wrapper_events.append(event)
            if 'turn.finished_relayed' in event['line'] and f"conversationId={metadata['conversationId']} " in event['line']:
                terminal.append(event)
        except (ValueError, KeyError):
            continue
    return terminal, wrapper_events


def row_frames(all_events, observed, initial_assistants):
    # Reconstruct resident identities from actual lifecycle events; draw events update identity.
    # Each sample is supported by uninterrupted logcat lifecycle coverage, not by UI polling.
    state = {}
    frames = []
    identities = {}
    aliases = {}
    tracked_keys = initial_assistants | {event['key'] for event in observed}
    for e in all_events:
        if e['event'] == 'draw' and e['key'] in tracked_keys:
            raw_identity = e.get('identity')
            owner = identities.setdefault(e['key'], aliases.get(raw_identity, raw_identity))
            aliases[raw_identity] = owner
    state.update({key:identities.get(key,key) for key in initial_assistants})
    for e in observed:
        if e['event'] == 'dispose':
            state.pop(e['key'], None)
        else:
            state[e['key']] = identities.get(e['key'], e['key'])
        frames.append(dict(ms=e['ms'], rows=[dict(key=k, id=v) for k, v in state.items()]))
    frames = list({frame['ms']: frame for frame in frames}.values())
    return frames


def classify_events(all_events, events, start):
    assistant = {event['key'] for event in all_events if event['event']=='draw' and event.get('assistant')=='true'}
    users = {event['key'] for event in all_events if event['event']=='draw' and event.get('assistant')=='false'}
    prior = {}
    for event in all_events:
        if event['ms'] >= start:
            break
        if event['event']=='dispose':
            prior.pop(event['key'],None)
        else:
            prior[event['key']] = event
    initial_assistants = set(prior) & assistant
    new_keys = assistant - set(prior)
    unknown_initial = set(prior)-assistant-users
    unknown_removed = [event['key'] for event in events if event['event']=='dispose'
                       and event['key'] in set(prior)-assistant-users]
    observed = [event for event in events if event.get('key') in assistant]
    user_identities = {event['key']:event.get('identity') for event in all_events
                       if event['event']=='draw' and event['key'] in users}
    user_state = {key:user_identities[key] for key in set(prior)&users}
    duplicate_user_samples = set()
    for event in events:
        if event['key'] in users:
            if event['event']=='dispose':
                user_state.pop(event['key'],None)
            else:
                user_state[event['key']] = user_identities[event['key']]
        if len(user_state.values()) != len(set(user_state.values())):
            duplicate_user_samples.add(event['ms'])
    return initial_assistants, new_keys, unknown_initial, unknown_removed, observed, duplicate_user_samples


def stored_copy(observed, new_keys, last_terminal, expected_hash, terminal):
    stored = [e['ms'] for e in observed if e['event'] == 'draw' and e.get('stored') == 'true'
              and e['key'] in new_keys and last_terminal is not None and e['ms'] >= last_terminal and str(expected_hash) in e.get('hashes','').split(',')]
    stored_keys = {event['key'] for event in observed if event['event']=='draw' and event.get('stored')=='true'
                   and event['key'] in new_keys and last_terminal is not None and event['ms']>=last_terminal
                   and str(expected_hash) in event.get('hashes','').split(',')}
    final_run = re.search(r'runId=(\S+)',terminal[-1]['line']) if terminal else None
    live_keys = {event['key'] for event in observed if event['event']=='draw' and final_run
                 and event.get('identity')==final_run[1] and event.get('stored')=='false'}
    preserved = stored_keys & live_keys
    if preserved:
        stored = [event['ms'] for event in observed if event['event']=='draw' and event['key'] in preserved
                  and event.get('stored')=='true' and event['ms']>=last_terminal
                  and str(expected_hash) in event.get('hashes','').split(',')]
    elif len(stored_keys)!=1:
        stored = []
    return stored, stored_keys, preserved


def is_new_stored_reply(event, new_keys, expected_hash):
    return (event['key'] in new_keys and event.get('stored') == 'true'
            and str(expected_hash) in event.get('hashes', '').split(','))


def has_partial_capture(scenario, terminals, metadata):
    return scenario == 'S3' and terminals and metadata.get('interruptReferenceMs')


def scenario_checks(metadata, directory, unknown_removed, terminal, last_terminal, observed, tree, new_keys, expected_hash, start):
    scenario_errors = ['initial row disposed without role evidence'] if unknown_removed else []
    held = []
    expected_hold = None
    if metadata['scenario'] == 'S3' and not any('stopReason=cancelled' in e['line'] for e in terminal):
        scenario_errors.append('cancelled terminal not observed')
    if metadata['scenario'] == 'S4':
        disabled = metadata.get('networkDisabledMs')
        restored = metadata.get('networkRestoredMs')
        if not metadata.get('networkDisabled'):
            scenario_errors.append('network loss not verified')
        if not oracle.number(disabled) or not oracle.number(restored) or restored-disabled < 5000:
            scenario_errors.append('network outage timing missing or too short')
        elif last_terminal is None or disabled >= last_terminal or not any(
                e['event']=='draw' and e.get('stored')=='false' and e['ms']<disabled for e in observed):
            scenario_errors.append('network drop did not overlap observed live reply')
    if metadata['scenario'] == 'S2':
        worker = [json.loads(line) for line in (directory / 'worker.jsonl').read_text().splitlines()]
        worker = [e for e in worker if e.get('wallMs', 0) + metadata['miniToPhoneOffsetMs'] >= start]
        starts = {e['requestId']: e['wallMs'] for e in worker if e.get('event') == 'start'}
        durations = [e.get('seconds') for e in worker if e.get('event') == 'start']
        if len(durations)==1 and oracle.number(durations[0]):
            expected_hold = durations[0] * 1000
        held = [e['wallMs'] - starts[e['requestId']] for e in worker
                if e.get('event') == 'end' and e.get('requestId') in starts]
        if not held or expected_hold is None or min(held) < expected_hold - 1000:
            scenario_errors.append('current held tool start/end missing or hold too short')
    if any(n.get('content-desc') == 'Stop' for n in tree.iter('node')):
        scenario_errors.append('Stop still visible in final UI')
    if not any(is_new_stored_reply(e, new_keys, expected_hash) for e in observed):
        scenario_errors.append('fixed reply not evidenced in a newly drawn stored row')
    return scenario_errors, held, expected_hold


def normalize(directory, metadata, tree):
    all_events = parse_log(directory / 'logcat.txt')
    start = metadata.get('sendBeginPhoneMs',metadata.get('videoStartMs',metadata['sendMs'] + metadata['phoneOffsetMs']))
    events = [event for event in all_events if event['ms'] >= start]
    initial_assistants, new_keys, unknown_initial, unknown_removed, observed, duplicate_user_samples = classify_events(all_events, events, start)
    terminal, wrapper_events = wrapper_terminals(directory, metadata)
    text_nodes = [n.get('text', '') for n in tree.iter('node') if n.get('text')]
    expected = 'DONE' if metadata['scenario'] == 'S2' else oracle.FIXED_REPLY
    # Accessibility can split paragraph nodes. Only accept an exact complete visible reply.
    joined = '\n\n'.join(text_nodes)
    visible = expected if expected in joined else joined
    expected_hash = 0
    for character in expected:
        expected_hash = (31 * expected_hash + ord(character)) & 0xffffffff
    if expected_hash >= 0x80000000:
        expected_hash -= 0x100000000
    last_terminal = terminal[-1]['wallMs'] + metadata['miniToPhoneOffsetMs'] if terminal else None
    stored, stored_keys, preserved = stored_copy(observed, new_keys, last_terminal, expected_hash, terminal)
    lifecycle = [dict(event='present',key=key) for key in sorted(initial_assistants)]
    lifecycle += [dict(event=e['event'], key=e['key']) for e in observed if e['event'] in ('mount', 'dispose')]
    frames = row_frames(all_events, observed, initial_assistants)
    evidence = dict(scenario=metadata['scenario'], client='pixel2xl', terminalMs=last_terminal,
                    initialUnclassifiedRows=len(unknown_initial),duplicateUserRowSamples=len(duplicate_user_samples),
                    finalStopPresent=any(n.get('content-desc')=='Stop' for n in tree.iter('node')),
                    storedDisplayedMs=min(stored) if stored else None,
                    stopGoneMs=metadata.get('stopGoneMs'), visibleText=visible,
                    lifecycle=lifecycle, frames=frames, clockAligned=metadata['clockAligned'])
    # Never fabricate continuous row frames to satisfy the <=100 ms coverage gate.
    # If rendering/logging was sparse, the oracle correctly fails evidence coverage.
    pixels = directory / 'pixels' / 'summary.json'
    evidence['pixels'] = json.loads(pixels.read_text()) if pixels.exists() else {}
    if metadata['scenario'] == 'S3':
        partial_summary = directory/'partial-pixels'/'summary.json'
        if partial_summary.exists():
            partial_pixels = json.loads(partial_summary.read_text())
            evidence['pixels']['minimum_template_correlation'] = min(evidence['pixels'].get('minimum_template_correlation', 0), partial_pixels['minimum_template_correlation'])
            evidence['pixels']['dip_percent'] = max(evidence['pixels'].get('dip_percent', 100), partial_pixels['dip_percent'])
        else:
            evidence['pixels'] = {}
    scenario_errors, held, expected_hold = scenario_checks(metadata, directory, unknown_removed, terminal, last_terminal, observed, tree, new_keys, expected_hash, start)
    accepted = [event for event in wrapper_events if f"input.accepted conversationId={metadata['conversationId']} " in event['line']]
    cancelled = [event for event in terminal if 'stopReason=cancelled' in event['line']]
    cancelled_live = []
    if cancelled:
        run = re.search(r'runId=(\S+)',cancelled[0]['line'])
        cancelled_live = [event for event in observed if event['event']=='draw' and run
                          and event.get('identity')==run[1]
                          and event['ms']<=cancelled[0]['wallMs']+metadata['miniToPhoneOffsetMs']]
    incomplete_partial = bool(cancelled_live) and any(event.get('hashes') not in (None,'','0') for event in cancelled_live)
    incomplete_partial = incomplete_partial and not any(str(expected_hash) in event.get('hashes','').split(',') for event in cancelled_live)
    following = [event['wallMs']-cancelled[0]['wallMs'] for event in accepted
                 if cancelled and event['wallMs'] >= cancelled[0]['wallMs']]
    disabled = metadata.get('networkDisabledMs')
    restored = metadata.get('networkRestoredMs')
    outage = restored-disabled if oracle.number(restored) and oracle.number(disabled) else None
    evidence['scenarioEvidence'] = dict(
        terminalReason='end_turn' if terminal and 'stopReason=end_turn' in terminal[-1]['line'] else None,
        inputCount=len(accepted), heldToolMs=max(held) if held else None, heldToolExpectedMs=expected_hold,
        cancelled=bool(cancelled),cancelledPartialIncomplete=incomplete_partial,cancelToResendMs=min(following) if following else None,
        networkDownMs=outage,
        networkOverlappedLive=bool(metadata.get('networkDisabled') and oracle.number(disabled)
            and oracle.number(last_terminal) and disabled < last_terminal
            and any(e['event']=='draw' and e.get('stored')=='false' and e['ms']<disabled for e in observed)),
        newStoredTextObserved=any(is_new_stored_reply(e, new_keys, expected_hash) for e in observed),
    )
    (directory/'evidence.json').write_text(json.dumps(evidence,indent=2))
    result = oracle.score(evidence)
    result['errors'] = sorted(set(result['errors'] + scenario_errors))
    result['storedCopyAssociation'] = 'preservedLiveKey' if preserved else ('uniqueNewStoredText' if len(stored_keys)==1 else 'unresolved')
    result['matchingNewStoredRows'] = len(stored_keys)
    result['cancelledPartialIncomplete'] = incomplete_partial
    result['cancelToResendMs'] = evidence['scenarioEvidence']['cancelToResendMs']
    result['networkDownMs'] = outage
    result['initialUnclassifiedDisposals'] = len(unknown_removed)
    result['heldToolMs'] = evidence['scenarioEvidence']['heldToolMs']
    result['status'] = 'FAIL' if result['errors'] else 'PASS'
    return result


REMOTE_AUDIT = """import json,pathlib,base64,hashlib
root=pathlib.Path('/opt/letta-test-server')
s=json.load(open(root/'bench-fixture/scope.json'))
agent=[]
for p in (root/'state/agents').iterdir():
 try:
  a=json.load(open(p))
  if a.get('id')==s['agent_id']: agent.append(a)
 except (ValueError,IsADirectoryError): pass
assert len(agent)==1 and agent[0]['name']=='mini-bench' and agent[0]['model']=='ollama/bench-model', 'fixture agent mismatch'
key=base64.urlsafe_b64encode(('conversation:'+s['conversation_id']).encode()).decode().rstrip('=')
c=json.load(open(root/'state/conversations'/key/'conversation.json'))
assert c['agent_id']==s['agent_id'] and c.get('model') in (None,'ollama/bench-model'), 'fixture conversation model override'
print(json.dumps(dict(**s,agentModel=agent[0]['model'],conversationModel=c.get('model'),conversationTitle=c.get('summary'),modelLogOffset=(root/'bench-fixture/model.log').stat().st_size,modelSha256=hashlib.sha256((root/'bench-fixture/model.cjs').read_bytes()).hexdigest())))
"""


REMOTE_TAIL = """import json,time,sys,os
parent=os.getppid()
p=open('/tmp/mini-iroh-wrapper.log');p.seek(0,2)
while os.getppid()==parent:
 line=p.readline()
 if line: print(json.dumps({'wallMs':time.time_ns()/1000000,'line':line.rstrip()}),flush=True)
 else: time.sleep(.01)
"""


def wait_for_pixel():
    ancestors = {os.getpid()}
    parent = os.getppid()
    while parent > 1:
        ancestors.add(parent)
        parent = int(Path(f'/proc/{parent}/stat').read_text().split(') ')[1].split()[1])
    deadline = time.monotonic() + 300
    while True:
        owners = run_tool(['pgrep', '-af', SERIAL], capture_output=True, text=True)
        other = [line for line in owners.stdout.splitlines() if int(line.split()[0]) not in ancestors]
        if not other:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError('Pixel already owned: ' + '; '.join(other))
        time.sleep(5)


def perform_scenario_step(directory, metadata, scenario, index, wifi, data):
    if scenario == 'S3':
        partial_tree = dump(directory, 'before-interrupt')
        region = reply_region(partial_tree,oracle.FIXED_REPLY[:30],70)
        if region:
            metadata['partialRegion'] = region
            metadata['interruptReferenceMs'] = wall_ms() + metadata['phoneOffsetMs']
        metadata['resendMs'] = send(directory, f'[core-loop:S3] follow-up-{index}', 'second', fast=True)
        queued = dump(directory,'queued-resend')
        metadata['interruptRequestedMs'] = wall_ms() + metadata['phoneOffsetMs']
        tap_node(queued,'text','Send now')
    if scenario == 'S4':
        adb('shell', 'svc', 'wifi', 'disable')
        adb('shell', 'svc', 'data', 'disable')
        metadata['networkDisabledMs'] = wall_ms() + metadata['phoneOffsetMs']
        metadata['networkDisabled'] = adb('shell','settings','get','global','wifi_on') == '0' and adb('shell','settings','get','global','mobile_data') == '0'
        time.sleep(5)
        restore_network(wifi, data)
        metadata['networkRestoredMs'] = wall_ms() + metadata['phoneOffsetMs']
        metadata['networkVariant'] = 'wifi off/on; cellular disabled during drop'
    return None


def select_conversation(directory, scope):
    before = dump(directory, 'before')
    if not any(n.get('text') == 'mini-bench' or n.get('content-desc') == 'mini-bench' for n in before.iter('node')):
        raise RuntimeError('mini-bench must be open; refusing to send to another agent')
    if any(n.get('content-desc') == 'Stop' for n in before.iter('node')):
        raise RuntimeError('existing run active; refusing to interrupt another run')
    title = scope.get('conversationTitle')
    if not isinstance(title,str) or not title.startswith('core-loop-harness'):
        raise RuntimeError('fixture conversation needs its unique core-loop-harness title')
    if any(n.get('text')==title for n in before.iter('node')):
        tap_node(before,'text',title)
    else:
        if not any(n.get('content-desc')=='Close navigation menu' for n in before.iter('node')):
            tap_node(before,'content-desc','Agents and conversations')
        menu = dump(directory,'conversation-menu')
        if not any(n.get('text')==title for n in menu.iter('node')):
            tap_node(menu,'content-desc','mini-bench')
            menu = dump(directory,'fixture-agent-chat')
            if not any(n.get('content-desc')=='Close navigation menu' for n in menu.iter('node')):
                tap_node(menu,'content-desc','Agents and conversations')
                menu = dump(directory,'fixture-agent-menu')
        tap_node(menu,'text',title)
    time.sleep(1.5)
    selected = dump(directory,'selected-conversation')
    if not any(n.get('text')=='mini-bench' for n in selected.iter('node')):
        raise RuntimeError('fixture chat did not open')
    if any(n.get('content-desc')=='Stop' for n in selected.iter('node')):
        raise RuntimeError('fixture already active after selection')
    return None


def trial(args, scenario, index):
    wait_for_pixel()
    directory = args.output / f'{scenario}-{index}'
    directory.mkdir(parents=True, exist_ok=False)
    wifi = adb('shell', 'settings', 'get', 'global', 'wifi_on')
    data = adb('shell', 'settings', 'get', 'global', 'mobile_data')
    if wifi not in ('0', '1') or data not in ('0', '1'):
        raise RuntimeError('cannot determine original network state')
    actual_apk_hash = installed_app_hash()
    if actual_apk_hash != args.apk_sha256:
        raise RuntimeError('installed APK changed; refusing to send from an unverified client')
    metadata = dict(scenario=scenario, trial=index, wifi=wifi, data=data,apkSha256=actual_apk_hash)
    scope = json.loads(command(['ssh','-o','BatchMode=yes','graphiti','python3','-c',shlex.quote(REMOTE_AUDIT)]))
    metadata['fixtureScope'] = scope
    metadata['conversationId'] = scope['conversation_id']
    logcat = wrapper = clock_process = None
    video_pid = None
    log_handle = (directory / 'logcat.txt').open('w')
    wrapper_handle = (directory / 'wrapper.jsonl').open('w')
    tree = None
    try:
        select_conversation(directory, scope)
        logcat = run_tool(['adb', '-s', SERIAL, 'logcat', '-v', 'epoch', '-T', '1'], stdout=log_handle, stderr=subprocess.STDOUT, popen=True)
        wrapper = run_tool(['ssh', '-o', 'BatchMode=yes', 'graphiti', 'python3', '-u', '-c',
                                    shlex.quote(REMOTE_TAIL)], stdout=wrapper_handle, stderr=subprocess.STDOUT, popen=True)
        begin = wall_ms()
        phone = float(adb('shell', 'date', '+%s%3N'))
        end = wall_ms()
        metadata['phoneRttMs'] = end - begin
        metadata['phoneOffsetMs'] = phone - (begin + end) / 2
        clock_process = run_tool(['ssh','-o','BatchMode=yes','graphiti','python3','-u','-c',
            shlex.quote('import sys,time\nfor line in sys.stdin: print(time.time_ns()/1000000,flush=True)')],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,popen=True)
        clock_samples = []
        for _ in range(4):
            begin = wall_ms()
            clock_process.stdin.write('clock\n')
            clock_process.stdin.flush()
            if not select.select([clock_process.stdout],[],[],10)[0]:
                raise RuntimeError('mini clock exchange timed out')
            mini = float(clock_process.stdout.readline())
            end = wall_ms()
            clock_samples.append(dict(rttMs=end-begin,offsetMs=mini-(begin+end)/2))
        clock_process.stdin.close()
        clock_process.wait(timeout=10)
        best = min(clock_samples[1:],key=lambda sample:sample['rttMs'])
        metadata['clockSamples'] = clock_samples
        metadata['miniToPhoneOffsetMs'] = metadata['phoneOffsetMs'] - best['offsetMs']
        metadata['miniRttMs'] = best['rttMs']
        metadata['clockAligned'] = best['rttMs'] < 200 and metadata['phoneRttMs'] < 200
        metadata['simState'] = adb('shell','getprop','gsm.sim.state')
        time.sleep(1)
        if wrapper.poll() is not None or logcat.poll() is not None:
            raise RuntimeError('evidence collector exited before send')
        metadata['existingKeys'] = list({e['key'] for e in parse_log(directory / 'logcat.txt')})
        if not metadata['existingKeys']:
            pid = adb('shell','pidof',PACKAGE)
            gate = adb('logcat','-d','--pid',pid,'-s','Telemetry/CoreLoop:I')
            (directory/'capture-gate.log').write_text(gate)
            if 'gate enabled=true' not in gate:
                raise RuntimeError('root-debug row capture is not verified; restart the capture APK with LettaCoreLoop VERBOSE')
        adb('shell','rm','-f','/sdcard/core-loop.mp4')
        video_pid = adb('shell', 'screenrecord --time-limit 180 /sdcard/core-loop.mp4 >/sdcard/core-loop-screenrecord.log 2>&1 & echo $!')
        time.sleep(.3)
        if not video_pid.isdigit():
            raise RuntimeError('screenrecord PID missing')
        adb('shell','kill','-0',video_pid)
        metadata['videoStartMs'] = wall_ms() + metadata['phoneOffsetMs']
        metadata['sendMs'] = send(directory, f'[core-loop:{scenario}] trial-{index}', 'first')
        metadata['sendBeginPhoneMs'] = metadata['sendMs'] + metadata['phoneOffsetMs']
        time.sleep(3)
        perform_scenario_step(directory, metadata, scenario, index, wifi, data)
        # Wait through fixture completion, then capture post-terminal Stop clearance.
        deadline = time.monotonic() + SCENARIO_TIMEOUTS[scenario]
        while time.monotonic() < deadline:
            tree = dump(directory, 'latest')
            elapsed = wall_ms() - metadata['sendMs']
            if 'turn.finished_relayed' in (directory / 'wrapper.jsonl').read_text(errors='replace') and not any(n.get('content-desc') == 'Stop' for n in tree.iter('node')):
                metadata['stopGoneMs'] = wall_ms() + metadata['phoneOffsetMs']
                break
            time.sleep(1)
        time.sleep(6)
        tree = dump(directory, 'final')
        (directory / 'final.png').write_bytes(command(['adb','-s',SERIAL,'exec-out','screencap','-p']))
        worker = command(['ssh','-o','BatchMode=yes','graphiti','tail','-n','30','/opt/letta-test-server/bench-fixture/worker.log'])
        (directory / 'worker.jsonl').write_bytes(worker)
        model = command(['ssh','-o','BatchMode=yes','graphiti','tail','-c',
                         '+'+str(scope['modelLogOffset']+1),'/opt/letta-test-server/bench-fixture/model.log'])
        (directory / 'model.jsonl').write_bytes(model)
    finally:
        cleanup_errors = []
        try:
            metadata['restoredNetworking'] = restore_network(wifi, data)
        except Exception as error:
            cleanup_errors.append(str(error))
        if video_pid and video_pid.isdigit():
            try:
                adb('shell','kill','-2',video_pid)
                record_deadline = time.monotonic() + 10
                while time.monotonic() < record_deadline:
                    alive = run_tool(['adb','-s',SERIAL,'shell','kill','-0',video_pid],capture_output=True,timeout=10)
                    if alive.returncode != 0:
                        break
                    time.sleep(.25)
                else:
                    adb('shell','kill','-9',video_pid)
                    raise RuntimeError('screenrecord did not stop cleanly')
                metadata['recordStopVerified'] = True
                command(['adb','-s',SERIAL,'pull','/sdcard/core-loop.mp4',evidence_path(directory / 'screen.mp4', directory)])
            except Exception as error:
                cleanup_errors.append(str(error))
        try:
            recorder_log = command(['adb','-s',SERIAL,'exec-out','cat','/sdcard/core-loop-screenrecord.log'])
            (directory/'screenrecord.log').write_bytes(recorder_log)
        except Exception as error:
            cleanup_errors.append(str(error))
        for process in (logcat, wrapper, clock_process):
            try:
                stop_process(process)
            except Exception as error:
                cleanup_errors.append(str(error))
        log_handle.close()
        wrapper_handle.close()
        metadata['cleanupErrors'] = cleanup_errors
        (directory / 'metadata.json').write_text(json.dumps(metadata, indent=2))
        if cleanup_errors:
            raise RuntimeError('; '.join(cleanup_errors))
    if tree is None:
        raise RuntimeError('final UI missing')
    if any(n.get('content-desc')=='Stop' for n in tree.iter('node')):
        # Preserve the failed terminal snapshot, then reset only this owned test
        # app so a stranded spinner cannot invalidate every subsequent trial.
        metadata['recoveryAfterStuckSpinner'] = True
        adb('shell','am','force-stop',PACKAGE)
        adb('shell','am','start','-n',PACKAGE+'/com.letta.mobile.MainActivity')
        time.sleep(5)
        recovery = dump(directory,'recovery')
        metadata['recoveryStillShowsStop'] = any(n.get('content-desc')=='Stop' for n in recovery.iter('node'))
        (directory/'metadata.json').write_text(json.dumps(metadata,indent=2))
    return analyze_capture(directory, metadata, tree, args.region)


def analyze_final_pixels(directory, metadata, region, terminals):
    if terminals:
        seconds = (terminals[-1]['wallMs'] + metadata['miniToPhoneOffsetMs'] - metadata['videoStartMs']) / 1000
        analysis = run_tool(['python3', str(HERE/'analyze-settle.py'), evidence_path(directory/'screen.mp4', directory),
            evidence_path(directory/'pixels', directory), '--region', region_arg(region), '--settle-seconds', number_arg(seconds), '--post-seconds','5'],
            capture_output=True, timeout=300)
        (directory/'pixel-analysis.log').write_bytes(analysis.stdout + analysis.stderr)
        if analysis.returncode:
            raise RuntimeError('pixel analysis failed; see pixel-analysis.log')
        # MP4 plus timestamps remain authoritative; decoded PNGs are reproducible.
        shutil.rmtree(directory/'pixels'/'raw', ignore_errors=True)


def analyze_capture(directory, metadata, tree, explicit_region=None):
    # Derive a reply-only region from the final accessibility node before pixel scoring.
    scenario = metadata['scenario']
    region = explicit_region
    if not region:
        expected_part = 'DONE' if scenario == 'S2' else oracle.FIXED_REPLY.split('\n\n')[0]
        region = reply_region(tree,expected_part,120)
    if region:
        terminal_lines = [json.loads(line) for line in (directory/'wrapper.jsonl').read_text().splitlines() if line.startswith('{')]
        terminals = [e for e in terminal_lines if 'turn.finished_relayed' in e['line'] and f"conversationId={metadata['conversationId']} " in e['line']]
        analyze_final_pixels(directory, metadata, region, terminals)
        if has_partial_capture(scenario, terminals, metadata):
            partial_tree = ET.parse(directory/'before-interrupt.xml').getroot()
            metadata['partialRegion'] = reply_region(partial_tree,oracle.FIXED_REPLY[:30],70)
            if not metadata['partialRegion']:
                return normalize(directory,metadata,tree)
            seconds = (terminals[0]['wallMs'] + metadata['miniToPhoneOffsetMs'] - metadata['videoStartMs']) / 1000
            reference = (metadata['interruptReferenceMs'] - metadata['videoStartMs']) / 1000
            analysis = run_tool(['python3', str(HERE/'analyze-settle.py'), evidence_path(directory/'screen.mp4', directory),
                evidence_path(directory/'partial-pixels', directory), '--region', region_arg(metadata['partialRegion']), '--settle-seconds', number_arg(seconds),
                '--reference-seconds',number_arg(reference),'--post-seconds','5','--track-y','1000'],capture_output=True,timeout=300)
            (directory/'partial-pixel-analysis.log').write_bytes(analysis.stdout + analysis.stderr)
            if analysis.returncode:
                raise RuntimeError('partial pixel analysis failed; see partial-pixel-analysis.log')
            shutil.rmtree(directory/'partial-pixels'/'raw',ignore_errors=True)
    (directory/'metadata.json').write_text(json.dumps(metadata,indent=2))
    return normalize(directory, metadata, tree)


def rescore_captures(args):
    results = []
    for directory in sorted(args.output.glob('S[1-4]-*')):
        try:
            metadata = json.loads((directory/'metadata.json').read_text())
            for name in ('result','evidence','metadata'):
                source = directory/(name+'.json')
                backup = directory/(name+'-pre-rescore.json')
                if source.exists() and not backup.exists():
                    shutil.copy2(source,backup)
            tree = ET.parse(directory/'final.xml').getroot()
            result = analyze_capture(directory,metadata,tree,args.region)
        except Exception as error:
            result = dict(scenario=directory.name.split('-')[0],client='pixel2xl',status='FAIL',errors=[str(error)])
        (directory/'result.json').write_text(json.dumps(result,indent=2))
        results.append(result)
    if not results:
        raise RuntimeError('no captures to rescore')
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install',action='store_true',help='verify package and install the capture APK in place before trials')
    parser.add_argument('--apk',type=Path,default=HERE.parents[2]/'android-compose/app/build/outputs/apk/root/debug/app-root-debug.apk')
    parser.add_argument('--serial', choices=[SERIAL], help='required for captures so the ownership process is visible to other workers')
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument('--output', type=Path)
    destination.add_argument('--rescore', type=Path, help='rebuild verdicts from saved raw captures; no device access')
    parser.add_argument('--trials', type=int, default=3)
    parser.add_argument('--scenarios', nargs='+', choices=oracle.SCENARIOS, default=list(oracle.SCENARIOS))
    parser.add_argument('--region', help='x,y,width,height of stable reply ink')
    args = parser.parse_args()
    results = []
    if args.rescore:
        args.output = args.rescore
        results = rescore_captures(args)
    else:
        if args.serial != SERIAL:
            parser.error('fresh captures require --serial ' + SERIAL)
        wait_for_pixel()
        if args.trials < 3:
            parser.error('baseline requires at least three trials')
        if adb('get-state') != 'device':
            raise RuntimeError('authorized Pixel 2 XL unavailable')
        with args.apk.open('rb') as apk:
            digest = hashlib.sha256()
            for chunk in iter(lambda:apk.read(1024*1024),b''):
                digest.update(chunk)
        args.apk_sha256 = digest.hexdigest()
        if args.install:
            sdk = Path(os.environ.get('ANDROID_HOME','/opt/android-sdk'))
            candidates = sorted((sdk/'build-tools').glob('*/aapt'),reverse=True)
            if not candidates:
                raise RuntimeError('aapt required to verify in-place dev package')
            badging = command([str(candidates[0]),'dump','badging',str(args.apk)]).decode()
            if not re.search(r"package: name='com\.letta\.mobile\.dev'",badging):
                raise RuntimeError('refusing to install anything except com.letta.mobile.dev')
            adb('install','-r',str(args.apk))
            adb('shell','setprop','log.tag.LettaCoreLoop','VERBOSE')
            adb('shell','am','force-stop',PACKAGE)
            adb('shell','am','start','-n',PACKAGE+'/com.letta.mobile.MainActivity')
            time.sleep(5)
        if installed_app_hash()!=args.apk_sha256:
            raise RuntimeError('install the exact capture APK before running the baseline')
        args.output.mkdir(parents=True, exist_ok=False)
        def interrupted(signum, frame):
            raise KeyboardInterrupt(f'signal {signum}')
        signal.signal(signal.SIGTERM, interrupted)
        for scenario in args.scenarios:
            for index in range(1, args.trials + 1):
                try:
                    result = trial(args, scenario, index)
                except Exception as error:
                    result = dict(scenario=scenario, client='pixel2xl', status='FAIL', errors=[str(error)])
                (args.output/f'{scenario}-{index}').mkdir(exist_ok=True)
                (args.output/f'{scenario}-{index}'/'result.json').write_text(json.dumps(result, indent=2))
                results.append(result)
    (args.output/'summary.json').write_text(json.dumps(results, indent=2))
    print('| Scenario | Client | Pass | Fail | Stored ms | Stop ms | Correlation | Dip % |')
    print('| --- | --- | ---: | ---: | --- | --- | --- | --- |')
    for scenario in args.scenarios:
        rows = [r for r in results if r['scenario'] == scenario]
        metrics = ['; '.join(str(r.get(k, 'missing')) for r in rows) for k in
                   ('storedCopyLatencyMs','stopLatencyMs','minimumCorrelation','dipPercent')]
        print(f"| {scenario} | pixel2xl | {sum(r['status']=='PASS' for r in rows)} | {sum(r['status']!='PASS' for r in rows)} | " + ' | '.join(metrics) + ' |')
    return int(any(r['status'] != 'PASS' for r in results))


if __name__ == '__main__':
    raise SystemExit(main())
