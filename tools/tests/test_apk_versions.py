import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'allocate-apk-version.py'
spec = importlib.util.spec_from_file_location('apk_versions', SCRIPT)
versions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(versions)


class ApkVersionsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / 'repo'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.repo)], check=True)
        self.counter = self.repo / 'apk-version.properties'
        self.counter.write_text('versionCode=20\nversionName=1.6.3\n')
        self.output = self.repo / 'build/version.properties'

    def test_each_build_reserves_both_numbers_and_clean_cannot_reset(self):
        versions.reserve(self.counter, self.output)
        self.assertEqual(versions.read_counter(self.output), (21, (1, 6, 4)))
        self.output.unlink()
        versions.reserve(self.counter, self.output)
        self.assertEqual(versions.read_counter(self.output), (22, (1, 6, 5)))

    def test_invocation_outputs_stay_immutable_after_another_build(self):
        first = self.repo / 'build/generated/apk-version/debug/invocation-a/version.properties'
        second = self.repo / 'build/generated/apk-version/debug/invocation-b/version.properties'
        versions.reserve(self.counter, first)
        versions.reserve(self.counter, second)
        self.assertEqual(versions.read_counter(first), (21, (1, 6, 4)))
        self.assertEqual(versions.read_counter(second), (22, (1, 6, 5)))

    def test_stale_checkout_is_reconciled_with_shared_reservation(self):
        versions.reserve(self.counter, self.output)
        self.counter.write_text('versionCode=18\nversionName=1.6.1\n')
        versions.reserve(self.counter, self.output)
        self.assertEqual(versions.read_counter(self.output), (22, (1, 6, 5)))

    def test_registered_worktrees_reconcile_code_and_name_independently(self):
        subprocess.run(['git', '-C', str(self.repo), 'add', 'apk-version.properties'], check=True)
        subprocess.run(['git', '-C', str(self.repo), '-c', 'user.name=Version test', '-c',
                        'user.email=version-test@example.invalid', 'commit', '-qm', 'fixture'], check=True)
        sibling = Path(self.temporary.name) / 'sibling'
        subprocess.run(['git', '-C', str(self.repo), 'worktree', 'add', '-qb', 'sibling', str(sibling)], check=True)
        (sibling / 'apk-version.properties').write_text('versionCode=25\nversionName=1.6.2\n')
        versions.reserve(self.counter, self.output)
        self.assertEqual(versions.read_counter(self.output), (26, (1, 6, 4)))
        versions.reserve(sibling / 'apk-version.properties', sibling / 'build/version.properties')
        self.assertEqual(versions.read_counter(sibling / 'build/version.properties'), (27, (1, 6, 5)))

    def test_parallel_builds_get_distinct_reservations(self):
        builds = [subprocess.Popen(['python3', str(SCRIPT), '--counter', str(self.counter),
                    '--output', str(self.repo / f'build/{index}.properties')],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE) for index in range(10)]
        for build in builds:
            stdout, stderr = build.communicate(timeout=20)
            self.assertEqual(build.returncode, 0, stderr.decode())
        codes = sorted(versions.read_counter(self.repo / f'build/{i}.properties')[0] for i in range(10))
        self.assertEqual(codes, list(range(21, 31)))

    def test_output_failure_keeps_reservation_and_never_reuses_it(self):
        self.output.parent.mkdir()
        self.output.mkdir()
        with self.assertRaises(OSError):
            versions.reserve(self.counter, self.output)
        self.assertEqual(versions.read_counter(self.counter)[0], 21)
        self.output.rmdir()
        versions.reserve(self.counter, self.output)
        self.assertEqual(versions.read_counter(self.output)[0], 22)

    def test_malformed_regressed_and_overflow_counters_fail_closed(self):
        for contents in ['versionCode=20\nversionName=1.2.0\n', 'versionCode=20\nversionName=bad\n',
                         'versionCode=2100000000\nversionName=1.6.3\n',
                         'versionCode=20\nversionName=1.6.2147483647\n']:
            with self.subTest(contents=contents):
                self.counter.write_text(contents)
                with self.assertRaises(ValueError):
                    versions.reserve(self.counter, self.output)
                self.assertFalse(self.output.exists())

    def test_missing_git_common_dir_does_not_fall_back(self):
        with patch.object(versions, 'git', side_effect=subprocess.CalledProcessError(1, 'git')):
            with self.assertRaises(subprocess.CalledProcessError):
                versions.reserve(self.counter, self.output)
        self.assertEqual(versions.read_counter(self.counter)[0], 20)

    def test_debug_and_release_share_the_execution_time_allocator(self):
        gradle = SCRIPT.parents[1] / 'app/build.gradle.kts'
        source = gradle.read_text()
        self.assertIn('onVariants(selector().all())', source)
        self.assertIn('tools/allocate-apk-version.py', source)
        self.assertIn('UUID.randomUUID()', source)
        self.assertIn('$apkVersionInvocation/version.properties', source)
        self.assertIn('notCompatibleWithConfigurationCache', source)
        self.assertIn('outputs.upToDateWhen { false }', source)
        self.assertIn('@org.gradle.work.DisableCachingByDefault', source)
        self.assertNotIn('bridgetVersionCode', source)


if __name__ == '__main__':
    unittest.main()
