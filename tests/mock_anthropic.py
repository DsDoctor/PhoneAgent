#!/usr/bin/env python3
"""Deterministic LOCAL Anthropic-protocol fixture. This is not an actual LLM.

Used only to prove the real mobile Claude Code loop can discover and invoke MCP
tools. It binds loopback and accepts only the disposable key `local-test-only`.
It never forwards requests, prints request bodies, or handles real credentials.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import time
from urllib.parse import urlsplit

SEQUENCE = ("home", "read_screen", "open_settings", "read_screen")
READY_SEQUENCE = ("home", "read_screen", "open_app_ready", "read_screen")
TEST_MODEL = "phoneagent-smoke-model"
READY_MODEL = "phoneagent-smoke-ready"
TEST_KEY = "local-test-only"


def tool_results(request):
    return [block for message in request.get("messages", [])
            if isinstance(message.get("content"), list)
            for block in message["content"]
            if isinstance(block, dict) and block.get("type") == "tool_result"]


def make_response(request):
    """Choose the next fixture operation from actual tool-result history."""
    results = tool_results(request)
    step = len(results)
    errors = [result for result in results if result.get("is_error")]
    if errors:
        raise ValueError("The preceding phone tool returned an error; refusing a false success")
    sequence = READY_SEQUENCE if request.get("model") == READY_MODEL else SEQUENCE
    if step < len(sequence):
        operation = sequence[step]
        candidates = [tool.get("name") for tool in request.get("tools", [])
                      if isinstance(tool, dict) and isinstance(tool.get("name"), str)
                      and (tool["name"] == operation or tool["name"].endswith("__" + operation))]
        if len(candidates) != 1:
            names = [tool.get("name") for tool in request.get("tools", []) if isinstance(tool, dict)]
            raise ValueError(f"Expected one MCP {operation} tool; advertised names: {names}")
        arguments = {"package_name": "com.android.settings", "timeout_ms": 8000} if operation == "open_app_ready" else {}
        block = {"type": "tool_use", "id": f"toolu_local_smoke_{step}", "name": candidates[0], "input": arguments}
        stop = "tool_use"
    else:
        block = {"type": "text", "text": "本地联调完成：已返回桌面、读取界面、打开设置并再次读取界面。本结果来自固定测试响应，尚未验证真实外部模型。"}
        stop = "end_turn"
    return {"id": f"msg_local_smoke_{step}", "type": "message", "role": "assistant",
            "model": READY_MODEL if request.get("model") == READY_MODEL else TEST_MODEL,
            "content": [block], "stop_reason": stop, "stop_sequence": None,
            "usage": {"input_tokens": 128 + step * 16, "output_tokens": 32}}


def events(message):
    initial = {**message, "content": [], "stop_reason": None,
               "usage": {**message["usage"], "output_tokens": 0}}
    yield "message_start", {"type": "message_start", "message": initial}
    block = message["content"][0]
    if block["type"] == "tool_use":
        yield "content_block_start", {"type": "content_block_start", "index": 0, "content_block": {**block, "input": {}}}
        yield "content_block_delta", {"type": "content_block_delta", "index": 0,
                                      "delta": {"type": "input_json_delta", "partial_json": json.dumps(block["input"])}}
    else:
        yield "content_block_start", {"type": "content_block_start", "index": 0,
                                      "content_block": {"type": "text", "text": ""}}
        yield "content_block_delta", {"type": "content_block_delta", "index": 0,
                                      "delta": {"type": "text_delta", "text": block["text"]}}
    yield "content_block_stop", {"type": "content_block_stop", "index": 0}
    yield "message_delta", {"type": "message_delta", "delta": {"stop_reason": message["stop_reason"], "stop_sequence": None},
                            "usage": {"output_tokens": message["usage"]["output_tokens"]}}
    yield "message_stop", {"type": "message_stop"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        # Avoid logging request URLs, headers, keys, prompts or screen content.
        pass

    def send_json(self, code, content):
        data = json.dumps(content, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def fail(self, code, message):
        self.send_json(code, {"type": "error", "error": {"type": "invalid_request_error", "message": message}})

    def do_POST(self):
        if self.headers.get("x-api-key") != TEST_KEY and self.headers.get("Authorization") != "Bearer " + TEST_KEY:
            self.fail(401, "This fixture accepts only the disposable local test key")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4 * 1024 * 1024:
                self.fail(413, "Invalid fixture body length")
                return
            request = json.loads(self.rfile.read(length))
        except (ValueError, TypeError):
            self.fail(400, "Malformed JSON")
            return
        path = urlsplit(self.path).path
        if path == "/v1/messages/count_tokens":
            self.send_json(200, {"input_tokens": 128})
            return
        if path != "/v1/messages":
            self.fail(404, "Unsupported fixture endpoint")
            return
        if request.get("model") == "phoneagent-smoke-error":
            print("LOCAL FIXTURE: intentional error for reset regression", flush=True)
            self.fail(400, "Intentional local fixture failure for reset regression")
            return
        try:
            message = make_response(request)
        except ValueError as error:
            print("Fixture rejected request: " + str(error), flush=True)
            self.fail(400, str(error))
            return
        block = message["content"][0]
        print("LOCAL FIXTURE: " + (block["name"] if block["type"] == "tool_use" else "final response"), flush=True)
        # Give Android a short transition window before requesting the next action.
        time.sleep(0.35)
        if not request.get("stream"):
            self.send_json(200, message)
            return
        body = b"".join(("event: " + event + "\ndata: " + json.dumps(data, ensure_ascii=False) + "\n\n").encode("utf-8")
                        for event, data in events(message))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        self.close_connection = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18080)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Deterministic LOCAL fixture listening on 127.0.0.1:{args.port}; no external model is used.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
