"""Agent-side waiting policy; no phone, credentials or APK build required."""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import MagicMock, patch

loader = importlib.machinery.SourceFileLoader('bridgit_helper', str(Path(__file__).parents[1] / 'bridgit'))
spec = importlib.util.spec_from_loader(loader.name, loader)
helper = importlib.util.module_from_spec(spec)
loader.exec_module(helper)


def response(state, **extra):
    return 200, {}, json.dumps(dict(status=state, **extra)).encode()


class InteractionWaitTests(unittest.TestCase):
    def test_progression_caps_at_300_and_new_wait_resets(self):
        pending = [response('pending')] * 13
        with patch.object(helper, 'request', side_effect=pending + [response('answered')]) as call:
            helper.wait_interaction({}, 'i_1', 10000)
        holds = [int(c.args[2].split('wait=')[1]) for c in call.call_args_list]
        self.assertEqual(holds, [55, 55, 90, 120, 150, 180, 210, 240, 270, 300, 300, 300, 300, 300])
        self.assertEqual([c.kwargs['timeout'] for c in call.call_args_list], [n + 15 for n in holds])
        with patch.object(helper, 'request', return_value=response('answered')) as call:
            helper.wait_interaction({}, 'i_2', 3600)
        self.assertTrue(call.call_args.args[2].endswith('wait=55'))

    def test_answer_during_long_hold_returns_without_sleep_or_extra_request(self):
        answer = response('answered', answer={'button': 'yes'})
        with patch.object(helper, 'request', side_effect=[response('pending')] * 3 + [answer]) as call:
            result = helper.wait_interaction({}, 'i_1', 3600)
        self.assertEqual(result['answer'], {'button': 'yes'})
        self.assertEqual(call.call_count, 4)
        self.assertTrue(call.call_args.args[2].endswith('wait=120'))

    def test_terminal_outcomes(self):
        for state in ('answered', 'seen', 'dismissed', 'expired', 'cancelled'):
            with self.subTest(state=state), patch.object(helper, 'request', return_value=response(state)) as call:
                self.assertEqual(helper.wait_interaction({}, 'i_1', 3600)['status'], state)
                self.assertEqual(call.call_count, 1)

    def test_long_request_failure_falls_back_and_stays_at_55(self):
        failures = [TimeoutError(), ConnectionResetError(), (408, {}, b''), (504, {}, b''),
                    (422, {}, b'{"error":"invalid_parameter","parameter":"wait"}')]
        for failure in failures:
            with self.subTest(failure=failure), patch.object(helper, 'request', side_effect=[
                response('pending'), response('pending'), failure, response('pending'), response('answered')
            ]) as call:
                helper.wait_interaction({}, 'i_1', 3600)
                self.assertEqual([c.args[2].split('wait=')[1] for c in call.call_args_list],
                                 ['55', '55', '90', '55', '55'])

    def test_errors_are_not_retried(self):
        for status in (401, 403, 404, 422, 429, 500):
            with self.subTest(status=status), patch.object(helper, 'request', return_value=(status, {}, b'error')) as call:
                with self.assertRaises(RuntimeError):
                    helper.wait_interaction({}, 'i_1', 3600)
                self.assertEqual(call.call_count, 1)
        with patch.object(helper, 'request', side_effect=TimeoutError()) as call:
            with self.assertRaises(TimeoutError):
                helper.wait_interaction({}, 'i_1', 3600)
            self.assertEqual(call.call_count, 1)

    def test_fallback_failure_is_surfaced(self):
        with patch.object(helper, 'request', side_effect=[response('pending')] * 2 + [TimeoutError(), TimeoutError()]) as call:
            with self.assertRaises(TimeoutError):
                helper.wait_interaction({}, 'i_1', 3600)
            self.assertEqual(call.call_count, 4)

    def test_overall_deadline_limits_request_and_stops_without_delete(self):
        with patch.object(helper.time, 'monotonic', side_effect=[0, 0, 10]), patch.object(helper, 'request', return_value=response('pending')) as call:
            with self.assertRaisesRegex(TimeoutError, 'not cancelled'):
                helper.wait_interaction({}, 'i_1', 10)
            call.assert_called_once_with({}, 'GET', '/v1/interactions/i_1?wait=10', timeout=10)

    def test_unknown_status_is_surfaced(self):
        with patch.object(helper, 'request', return_value=response('unknown')):
            with self.assertRaises(ValueError):
                helper.wait_interaction({}, 'i_1', 3600)

    def test_invalid_deadlines_are_rejected_before_network(self):
        for timeout in ('0', '-1', 'nan', 'inf'):
            with self.subTest(timeout=timeout), contextlib.redirect_stderr(io.StringIO()), patch.object(helper, 'request') as call:
                with self.assertRaises(SystemExit):
                    helper.main(['wait-interaction', 'i_1', '--timeout', timeout])
                call.assert_not_called()

    def test_real_http_round_trip_and_existing_cli(self):
        paths = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                paths.append(self.path)
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(b'{"status":"answered","answer":{"button":"yes"}}')

            def log_message(self, *args):
                pass

        with HTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            cfg = {'BRIDGET_KEY': 'test-only', 'BRIDGET_HOST': '127.0.0.1', 'BRIDGET_PORT': str(server.server_port)}
            try:
                with patch.object(helper, 'userspace_tailscale', return_value=None), patch.object(helper, 'load_env', return_value=cfg):
                    output = io.StringIO()
                    with contextlib.redirect_stdout(output):
                        self.assertEqual(helper.main(['wait-interaction', 'i_1']), 0)
                    self.assertEqual(json.loads(output.getvalue())['answer']['button'], 'yes')
                    with contextlib.redirect_stdout(io.StringIO()):
                        self.assertEqual(helper.main(['GET', '/v1/health']), 0)
                self.assertEqual(paths, ['/v1/interactions/i_1?wait=55', '/v1/health'])
            finally:
                server.shutdown()
                thread.join()

    def test_userspace_transport_reads_and_cleans_up(self):
        process = MagicMock()
        process.poll.return_value = None
        process.stdout.fileno.return_value = 7
        with patch.object(helper, 'userspace_tailscale', return_value=['tailscale', 'nc']), patch.object(helper.subprocess, 'Popen', return_value=process), patch.object(helper.select, 'select', return_value=([7], [], [])), patch.object(helper.os, 'read', side_effect=[b'answer', b'']):
            self.assertEqual(helper.send({}, b'request', 'host', '8734', timeout=315), b'answer')
        process.kill.assert_called_once()
        process.wait.assert_called_once()
        process.stdout.close.assert_called_once()

    def test_userspace_timeout_also_reaps_process(self):
        process = MagicMock()
        process.poll.return_value = None
        with patch.object(helper, 'userspace_tailscale', return_value=['tailscale', 'nc']), patch.object(helper.subprocess, 'Popen', return_value=process), patch.object(helper.time, 'monotonic', side_effect=[0, 316]):
            with self.assertRaises(TimeoutError):
                helper.send({}, b'request', 'host', '8734', timeout=315)
        process.kill.assert_called_once()
        process.wait.assert_called_once()
        process.stdin.close.assert_called_once()

    def test_existing_chunked_json_response(self):
        raw = b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n'
        with patch.object(helper, 'send', return_value=raw):
            self.assertEqual(helper.request({'BRIDGET_KEY': 'test-only', 'BRIDGET_HOST': '127.0.0.1'}, 'GET', '/v1/health')[2], b'{}')


if __name__ == '__main__':
    unittest.main()
