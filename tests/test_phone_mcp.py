import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "app/src/main/assets/phone_mcp.py"
sys.path.insert(0, str(SCRIPT.parent))
spec = importlib.util.spec_from_file_location("phone_mcp", SCRIPT)
mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcp)


class McpTests(unittest.TestCase):
    def test_stdio_handshake_and_notification(self):
        messages = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05"}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        ]
        result = subprocess.run([sys.executable, str(SCRIPT)], input="\n".join(map(json.dumps, messages)) + "\n", text=True, capture_output=True, check=True)
        replies = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertEqual([reply["id"] for reply in replies], [1, 2])
        self.assertEqual(replies[0]["result"]["serverInfo"]["name"], "phone")
        names = {tool["name"] for tool in replies[1]["result"]["tools"]}
        self.assertEqual(names, {"read_screen", "home", "back", "open_settings", "open_app", "open_app_ready", "tap", "swipe", "type_text", "list_routines", "save_routine", "run_routine"})
        self.assertNotIn("shell", names)

    def test_bridge_preserves_chinese_text_and_auth_and_bypasses_proxy(self):
        received = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received["auth"] = self.headers.get("Authorization")
                received["body"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps({"ok": True, "message": "输入成功"}, ensure_ascii=False).encode())

            def log_message(self, *_):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with patch.dict(os.environ, {"PHONE_AGENT_PORT": str(server.server_port), "PHONE_AGENT_TOKEN": "test-token", "http_proxy": "http://127.0.0.1:1"}):
                reply = mcp.handle({"id": 3, "method": "tools/call", "params": {"name": "type_text", "arguments": {"text": "测试手机输入"}}})
            self.assertFalse(reply["result"]["isError"])
            self.assertEqual(received["auth"], "Bearer test-token")
            self.assertEqual(received["body"]["arguments"]["text"], "测试手机输入")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_bridge_failure_is_a_tool_error(self):
        with patch.object(mcp, "invoke", side_effect=OSError("connection closed")):
            reply = mcp.handle({"id": 4, "method": "tools/call", "params": {"name": "home"}})
        self.assertTrue(reply["result"]["isError"])

    def test_unknown_tool_is_rejected_before_network(self):
        reply = mcp.handle({"id": 5, "method": "tools/call", "params": {"name": "shell"}})
        self.assertTrue(reply["result"]["isError"])
        self.assertIn("Unknown phone tool", reply["result"]["content"][0]["text"])

    def test_invalid_request_does_not_crash(self):
        self.assertEqual(mcp.handle([])["error"]["code"], -32600)
        reply = mcp.handle({"id": 6, "method": "tools/call", "params": []})
        self.assertTrue(reply["result"]["isError"])

    def test_routines_integrate_receipts_persistence_and_unrecorded_child_steps(self):
        screen = {"ok": True, "package": "com.android.settings", "locked": False,
                  "nodes": [{"text": "设置", "description": "私人界面内容"}]}

        def action(name, arguments):
            if name == "open_app_ready":
                return {"ok": True, "status": "ready", "screen": screen}
            self.assertEqual(name, "read_screen")
            return dict(screen)

        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(os.environ, {"PHONE_AGENT_ROUTINES_DIR": directory}), \
                patch.object(mcp, "_ROUTINE_STORE", None), \
                patch.object(mcp, "invoke_phone", side_effect=action):
            first = mcp.invoke("open_app_ready", {"package_name": "com.android.settings"})
            second = mcp.invoke("open_app_ready", {"package_name": "com.android.settings"})
            self.assertNotEqual(first["routine_receipt"], second["routine_receipt"])
            store = mcp.get_routine_store()
            self.assertEqual(store.root, Path(directory))
            self.assertEqual(len(store.history), 2)
            candidates = mcp.invoke("list_routines", {})["candidates"]
            self.assertEqual(len(candidates), 1)
            self.assertEqual(candidates[0]["success_count"], 2)
            persisted = Path(directory, ".open_successes.json").read_text()
            self.assertNotIn("私人界面内容", persisted)
            self.assertNotIn("routine_receipt", persisted)
            verification = mcp.invoke("read_screen", {})["routine_receipt"]
            saved = mcp.invoke("save_routine", {
                "name": "open_settings_ready", "steps": [{"tool": "open_app_ready", "arguments": {"package_name": "com.android.settings"}}],
                "verify": {"package": "com.android.settings", "text_contains": ["设置"]},
                "candidate_id": candidates[0]["candidate_id"], "verification_receipt": verification})
            self.assertTrue(saved["ok"], saved)
            count_before = len(store.history)
            result = mcp.invoke("run_routine", {"name": "open_settings_ready"})
            self.assertTrue(result["ok"], result)
            self.assertEqual(len(store.history), count_before + 1)
            self.assertEqual(store.history[-1]["tool"], "run_routine")
            self.assertIsNone(store.history[-1]["receipt"])
            self.assertEqual(mcp.invoke("list_routines", {})["candidates"][0]["success_count"], 2)
            mcp._ROUTINE_STORE = None
            reloaded = mcp.invoke("list_routines", {})
            self.assertEqual(reloaded["routines"][0]["name"], "open_settings_ready")
            self.assertEqual(reloaded["candidates"][0]["success_count"], 2)
            self.assertEqual(len(mcp.get_routine_store().history), 0)

    def test_receipt_failure_does_not_relabel_real_action_or_leak_exception(self):
        with patch.object(mcp, "invoke_phone", return_value={"ok": True}), \
                patch.object(mcp, "get_routine_store") as get_store:
            get_store.return_value.record_action.side_effect = OSError("private exception text")
            value = mcp.invoke("home", {})
        self.assertTrue(value["ok"])
        self.assertIn("routine_record_error", value)
        self.assertNotIn("private exception text", json.dumps(value))

    def test_routine_child_dispatch_cannot_recurse(self):
        with self.assertRaisesRegex(ValueError, "Unknown Android bridge tool"):
            mcp.invoke_phone("run_routine", {"name": "nested"})


if __name__ == "__main__":
    unittest.main()
