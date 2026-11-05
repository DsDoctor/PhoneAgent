"""Compound phone tools retain the initial Android action generation across child HTTP calls."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
sys.path.insert(0, str(ASSETS))
SPEC = importlib.util.spec_from_file_location("phone_mcp_epoch", ASSETS / "phone_mcp.py")
mcp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mcp)


@contextmanager
def bridge(responses):
    received = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            received.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
            body = json.dumps(responses[len(received) - 1]).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with patch.dict(os.environ, {"PHONE_AGENT_PORT": str(server.server_port), "PHONE_AGENT_TOKEN": "test-only"}):
            yield received
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class ActionEpochTests(unittest.TestCase):
    def test_ready_wait_stops_immediately_when_resumed_gate_has_a_new_epoch(self):
        responses = [{"ok": True, "action_epoch": 41},
                     {"ok": False, "error_code": "task_paused", "action_epoch": 42}]
        with bridge(responses) as received, patch.object(mcp, "get_routine_store", return_value=Mock()), \
                patch.object(mcp.time, "sleep") as sleep:
            result = mcp.invoke("open_app_ready", {"package_name": "com.android.settings"})
        self.assertEqual(result["error_code"], "task_paused")
        self.assertEqual([r.get("action_epoch") for r in received], [None, 41])
        self.assertEqual([r["name"] for r in received], ["open_app", "read_screen"])
        sleep.assert_not_called()

    def test_actual_routine_cannot_pick_up_a_new_gate_generation_between_steps(self):
        responses = [{"ok": True, "action_epoch": 7},
                     {"ok": False, "error_code": "task_paused", "action_epoch": 8},
                     {"ok": True, "action_epoch": 8}]
        with tempfile.TemporaryDirectory() as directory:
            store = mcp.RoutineStore(directory)
            Path(directory, "epoch_test.json").write_text(json.dumps({"version": 1, "name": "epoch_test", "description": "",
                "steps": [{"tool": "home", "arguments": {}, "settle_ms": 0}, {"tool": "home", "arguments": {}, "settle_ms": 0}],
                "verify": {"package": "com.android.settings", "text_contains": ["设置"]}}))
            with bridge(responses) as received, patch.object(mcp, "get_routine_store", return_value=store):
                result = mcp.invoke("run_routine", {"name": "epoch_test"})
                self.assertFalse(result["ok"])
                self.assertEqual(result["completed_steps"], 1)
                fresh = mcp.invoke("home", {})
                self.assertTrue(fresh["ok"])
            self.assertEqual([r.get("action_epoch") for r in received], [None, 7, None])

    def test_changed_success_response_is_rejected_and_no_third_http_call_is_sent(self):
        responses = [{"ok": True, "action_epoch": 10}, {"ok": True, "action_epoch": 11}]
        values = []

        def compound(name, arguments):
            for _ in range(3):
                values.append(mcp.invoke_bridge("home", {}))
            return values[-1]

        with bridge(responses) as received, patch.object(mcp, "get_routine_store", return_value=Mock()), \
                patch.object(mcp, "invoke_phone", side_effect=compound):
            result = mcp.invoke("home", {})
        self.assertEqual(result["error_code"], "task_paused")
        self.assertEqual(len(received), 2)
        self.assertTrue(values[0]["ok"])
        self.assertFalse(values[1]["ok"])
        self.assertFalse(getattr(mcp._ACTION_CONTEXT, "active", False))


if __name__ == "__main__":
    unittest.main()
