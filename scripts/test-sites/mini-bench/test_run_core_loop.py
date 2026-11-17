"""Driver safety and evidence parsing checks; never connects to a real device."""
import importlib.util
import json
import xml.etree.ElementTree as ET
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('driver', Path(__file__).with_name('run-core-loop.py'))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class DriverTest(unittest.TestCase):
    def test_adb_is_always_serial_restricted(self):
        with patch.object(driver, 'command', return_value=b'device\n') as command:
            self.assertEqual(driver.adb('get-state'), 'device')
        command.assert_called_once_with(['adb', '-s', '711KPAE0914240', 'get-state'])

    def test_restore_preserves_both_original_states(self):
        with patch.object(driver, 'adb', side_effect=['','','1','0']) as adb:
            restored = driver.restore_network('1', '0')
        self.assertEqual(restored,{'wifi_on':'1','mobile_data':'0'})
        self.assertEqual(adb.call_args_list[0].args, ('shell', 'svc', 'wifi', 'enable'))
        self.assertEqual(adb.call_args_list[1].args, ('shell', 'svc', 'data', 'disable'))

    def test_restore_attempts_data_even_if_wifi_restore_fails(self):
        with patch.object(driver, 'adb', side_effect=[RuntimeError('disconnected'), '', '1', '1']) as adb:
            with self.assertRaisesRegex(RuntimeError, 'NETWORK RESTORE FAILED'):
                driver.restore_network('1', '1')
        self.assertEqual(adb.call_count, 4)

    def test_parses_only_timestamped_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'logcat.txt'
            path.write_text('garbage\n         1234.567 1 2 I System.out: CORE_LOOP draw key=run-a identity=message-a stored=true assistant=true\n'
                            '1234.568 1 2 I System.out: CORE_LOOP resident key=run-a\n')
            events = driver.parse_log(path)
        self.assertEqual(len(events), 2)
        self.assertEqual(events[0]['ms'], 1234567)
        self.assertEqual(events[0]['stored'], 'true')
        self.assertEqual(events[1]['event'], 'resident')

    def test_complete_raw_observations_normalize_without_invented_frames(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            lines = ['0.090 1 2 I System.out: CORE_LOOP mount key=run-a',
                     '0.100 1 2 I System.out: CORE_LOOP draw key=run-a identity=message-a stored=false assistant=true']
            lines += [f'{ms / 1000:.3f} 1 2 I System.out: CORE_LOOP resident key=run-a' for ms in range(150, 5250, 50)]
            lines.append('0.230 1 2 I System.out: CORE_LOOP draw key=run-a identity=message-a stored=true assistant=true hashes=0')
            lines.append('0.250 1 2 I System.out: CORE_LOOP draw key=run-a identity=durable-run-alias stored=true assistant=true hashes=699287813')
            lines.sort(key=lambda line: float(line.split()[0]))
            (directory/'logcat.txt').write_text('\n'.join(lines))
            (directory/'wrapper.jsonl').write_text(json.dumps(dict(wallMs=150,line='input.accepted conversationId=fixture-conversation disposition=queued'))+'\n'+json.dumps(dict(wallMs=200,
                line='turn.finished_relayed conversationId=fixture-conversation stopReason=end_turn'))+'\n')
            (directory/'pixels').mkdir()
            (directory/'pixels'/'summary.json').write_text(json.dumps(dict(frames=100,
                minimum_template_correlation=1.0,dip_percent=0.0)))
            tree = ET.Element('hierarchy')
            ET.SubElement(tree,'node',text=driver.oracle.FIXED_REPLY)
            metadata = dict(scenario='S1',sendMs=0,phoneOffsetMs=0,miniToPhoneOffsetMs=0,
                clockAligned=True,existingKeys=[],stopGoneMs=300,conversationId='fixture-conversation')
            result = driver.normalize(directory,metadata,tree)
            self.assertEqual(result['status'],'PASS',result['errors'])
            self.assertEqual(result['storedCopyLatencyMs'],50)

    def test_reply_region_selects_newest_physical_reply_not_xml_order(self):
        tree = ET.Element('hierarchy')
        ET.SubElement(tree,'node',text='fixed reply',bounds='[10,500][200,650]')
        ET.SubElement(tree,'node',text='fixed reply',bounds='[10,100][200,250]')
        self.assertEqual(driver.reply_region(tree,'fixed reply',70),'10,500,190,70')
        self.assertIsNone(driver.reply_region(tree,'missing reply',70))

    def test_fresh_capture_requires_visible_serial_before_any_device_access(self):
        with tempfile.TemporaryDirectory() as temporary, patch('sys.argv',['driver','--output',temporary]), \
                patch.object(driver,'wait_for_pixel'), patch.object(driver,'adb') as adb:
            with self.assertRaises(SystemExit) as stopped:
                driver.main()
            self.assertEqual(stopped.exception.code,2)
            adb.assert_not_called()
