#!/usr/bin/env python3
"""Deterministic LOCAL Anthropic-protocol fixture. This is not an actual LLM.

Used only to prove the real mobile Claude Code loop can discover and invoke MCP
tools. It binds loopback and accepts only the disposable key `local-test-only`.
It never forwards requests, prints request bodies, or handles real credentials.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import time
from urllib.parse import urlsplit

SEQUENCE = ("home", "read_screen", "open_settings", "read_screen")
READY_SEQUENCE = ("home", "read_screen", "open_app_ready", "read_screen")
LONG_SEQUENCE = ("read_screen",) * 31
TEST_MODEL = "phoneagent-smoke-model"
READY_MODEL = "phoneagent-smoke-ready"
LONG_MODEL = "phoneagent-smoke-long"
SESSION_MODEL = "phoneagent-smoke-session"
TEST_KEY = "local-test-only"
SESSION_MARKER = re.compile(r"PHONEAGENT_SESSION_(START|CONTINUE)_([a-f0-9]{32})")


def message_text(message):
    """Only message text; tool results must not masquerade as user turns."""
    content = message.get("content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(block.get("text", "") for block in content
                         if isinstance(block, dict) and block.get("type") == "text"
                         and isinstance(block.get("text"), str))
    return ""


def session_response(request):
    """Validate real resumed messages, then run one read for the current turn.

    A second-turn success is impossible without both the seed user prompt and
    the first final assistant response. IDs are per test and per conversation;
    an old read_screen result cannot satisfy a new turn or another session.
    """
    messages = request.get("messages", [])
    user_markers = [(i, action, identity) for i, message in enumerate(messages)
                    if message.get("role") == "user"
                    for action, identity in SESSION_MARKER.findall(message_text(message))]
    if not user_markers:
        raise ValueError("Session fixture is missing its explicit test prompt")
    identities = {identity for _, _, identity in user_markers}
    if len(identities) != 1:
        raise ValueError("Session fixture detected conversation cross-contamination")
    current_index, action, identity = user_markers[-1]
    first_result = "SESSION_FIRST_" + identity
    assistant_markers = [(i, marker) for i, message in enumerate(messages)
                         if message.get("role") == "assistant"
                         for marker in re.findall(r"SESSION_(?:FIRST|SECOND)_([a-f0-9]{32})", message_text(message))]
    if any(marker != identity for _, marker in assistant_markers):
        raise ValueError("Assistant history belongs to a different conversation")
    if action == "CONTINUE":
        starts = [i for i, verb, value in user_markers if verb == "START" and value == identity]
        first_answers = [i for i, message in enumerate(messages)
                         if message.get("role") == "assistant" and first_result in message_text(message)]
        if not any(start < answer < current_index for start in starts for answer in first_answers):
            raise ValueError("Resume is missing the original user and assistant context in order")
    else:
        if any(verb == "CONTINUE" for _, verb, _ in user_markers):
            raise ValueError("A new session unexpectedly contains an earlier continuation")
    turn = "followup" if action == "CONTINUE" else "initial"
    tool_id = "toolu_session_" + identity + "_" + turn
    current_results = [result for result in tool_results(request) if result.get("tool_use_id") == tool_id]
    if any(result.get("is_error") for result in current_results):
        raise ValueError("Session fixture read_screen failed")
    if not current_results:
        candidates = [tool.get("name") for tool in request.get("tools", [])
                      if isinstance(tool, dict) and isinstance(tool.get("name"), str)
                      and (tool["name"] == "read_screen" or tool["name"].endswith("__read_screen"))]
        if len(candidates) != 1:
            raise ValueError("Session fixture needs exactly one real read_screen tool")
        content = [{"type": "text", "text": "PROCESS_ONLY_" + identity + "_" + turn + "：正在读取当前界面。"},
                   {"type": "tool_use", "id": tool_id, "name": candidates[0], "input": {}}]
        stop = "tool_use"
    else:
        marker = ("SESSION_SECOND_" if action == "CONTINUE" else "SESSION_FIRST_") + identity
        text = "# 本地会话验证\n\n**" + marker + "**\n\n- 当前轮已完成一次真实读屏\n- "
        text += "已验证首次用户和助手上下文，且没有其他会话内容" if action == "CONTINUE" else "已创建独立会话"
        text += "\n\n`read_screen`\n\n```text\n本地固定响应\n```\n\n[项目](https://github.com/DsDoctor/PhoneAgent)"
        content = [{"type": "text", "text": text}]
        stop = "end_turn"
    return {"id": "msg_session_" + identity + "_" + turn + ("_final" if current_results else "_tool"),
            "type": "message", "role": "assistant", "model": SESSION_MODEL, "content": content,
            "stop_reason": stop, "stop_sequence": None,
            "usage": {"input_tokens": 256, "output_tokens": 80}}


def tool_results(request):
    return [block for message in request.get("messages", [])
            if isinstance(message.get("content"), list)
            for block in message["content"]
            if isinstance(block, dict) and block.get("type") == "tool_result"]


def make_response(request):
    """Choose the next fixture operation from actual tool-result history."""
    if request.get("model") == SESSION_MODEL:
        return session_response(request)
    results = tool_results(request)
    step = len(results)
    errors = [result for result in results if result.get("is_error")]
    if errors:
        raise ValueError("The preceding phone tool returned an error; refusing a false success")
    model = request.get("model")
    sequence = {READY_MODEL: READY_SEQUENCE, LONG_MODEL: LONG_SEQUENCE}.get(model, SEQUENCE)
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
        text = ("31轮只读联调完成：已连续调用31次手机读屏工具。本结果来自固定本地测试响应，没有调用真实外部模型。"
                if model == LONG_MODEL else
                "本地联调完成：已返回桌面、读取界面、打开设置并再次读取界面。本结果来自固定测试响应，尚未验证真实外部模型。")
        block = {"type": "text", "text": text}
        stop = "end_turn"
    return {"id": f"msg_local_smoke_{step}", "type": "message", "role": "assistant",
            "model": model if model in (READY_MODEL, LONG_MODEL) else TEST_MODEL,
            "content": [block], "stop_reason": stop, "stop_sequence": None,
            "usage": {"input_tokens": 128 + step * 16, "output_tokens": 32}}


def events(message):
    initial = {**message, "content": [], "stop_reason": None,
               "usage": {**message["usage"], "output_tokens": 0}}
    yield "message_start", {"type": "message_start", "message": initial}
    for index, block in enumerate(message["content"]):
        if block["type"] == "tool_use":
            yield "content_block_start", {"type": "content_block_start", "index": index, "content_block": {**block, "input": {}}}
            yield "content_block_delta", {"type": "content_block_delta", "index": index,
                                          "delta": {"type": "input_json_delta", "partial_json": json.dumps(block["input"])}}
        else:
            yield "content_block_start", {"type": "content_block_start", "index": index,
                                          "content_block": {"type": "text", "text": ""}}
            yield "content_block_delta", {"type": "content_block_delta", "index": index,
                                          "delta": {"type": "text_delta", "text": block["text"]}}
        yield "content_block_stop", {"type": "content_block_stop", "index": index}
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
        operations = [block["name"] for block in message["content"] if block["type"] == "tool_use"]
        print("LOCAL FIXTURE: " + (", ".join(operations) if operations else "final response"), flush=True)
        if request.get("model") == SESSION_MODEL and "_followup_final" in message["id"]:
            print("LOCAL FIXTURE: resumed user + assistant context verified; conversation isolation verified", flush=True)
        # The long fixture performs only reads, with no artificial transition wait.
        time.sleep(0.02 if request.get("model") == LONG_MODEL else 0.35)
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
