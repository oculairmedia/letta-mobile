import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ci'))
from clean_arch import findings, review_changes


class CleanArchitectureTest(unittest.TestCase):
    def test_comparison_requires_a_commit_id(self):
        with self.assertRaisesRegex(ValueError, 'base commit SHA'):
            review_changes('main')

    def test_wire_and_typed_branches(self):
        def rules(code):
            return [rule for rule, _ in findings('android-compose/sharedUI/F.kt', code)]
        self.assertEqual(rules('val tag = "agent_task"'), ['task-wire-tag'])
        self.assertEqual(rules('fun label(process: AppServerBackgroundProcess) = when(process.type) {}'), ['raw-process-type'])
        self.assertEqual(rules('// "agent_task"\nwhen (process.kind) { Unknown -> fallback() }'), [])
        self.assertEqual(list(findings('android-compose/sharedLogic/transport/Codec.kt', '"agent_task" -> AgentTask')), [])

    def test_state_only_in_policy_scopes(self):
        source = 'import androidx.compose.runtime.mutableStateOf as state'
        path = 'android-compose/sharedLogic/transport/F.kt'
        self.assertEqual(len(list(findings(path, source))), 1)
        self.assertEqual(list(findings(path, '/* '+source+' */')), [])
        self.assertEqual(list(findings(path.replace('transport', 'presentation'), source)), [])
