import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "app/src/main/assets/phone_mcp.py"
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
        self.assertEqual(names, {"read_screen", "home", "back", "open_settings", "open_app", "tap", "swipe", "type_text"})
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


if __name__ == "__main__":
    unittest.main()
