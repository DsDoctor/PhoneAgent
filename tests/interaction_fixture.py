"""Deterministic localhost Anthropic fixture for Claude's native interactive protocol."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import threading
import time

KEY = "local-interaction-probe-only"
QUESTION = "Which test color should be remembered?"
MEMORY_MARKER = "PHONEAGENT_NATIVE_MEMORY_20261105"
CLAUDE_MD_MARKER = "PHONEAGENT_NATIVE_CLAUDE_MD_20261105"


class Fixture:
    def __init__(self):
        self.requests = []
        self.slow_started = threading.Event()
        self.stop = threading.Event()

    def response(self, body):
        messages = body.get("messages", [])
        text = lambda m: m.get("content", "") if isinstance(m.get("content"), str) else "\n".join(
            b.get("text", "") for b in m.get("content", []) if b.get("type") == "text")
        # Claude may merge interrupted and replacement user messages into one API message.
        markers = [marker for m in messages if m.get("role") == "user"
                   for marker in re.findall(r"PROBE_(?:RECOVERED_ANSWER|AFTER_INTERRUPT|INTERRUPT|FILES|MEMORY|ASK)", text(m))]
        prompt = markers[-1] if markers else ""
        tools = {t["name"] for t in body.get("tools", [])}
        self.requests.append({"prompt": prompt, "tools": sorted(tools),
                              "native_memory_loaded": MEMORY_MARKER in json.dumps(body),
                              "native_claude_md_loaded": CLAUDE_MD_MARKER in json.dumps(body)})
        results = [b for m in messages if isinstance(m.get("content"), list)
                   for b in m["content"] if b.get("type") == "tool_result"]
        completed = {b.get("tool_use_id"): b for b in results}
        if prompt == "PROBE_RECOVERED_ANSWER":
            assert any(m.get("role") == "user" and "PROBE_ASK" in text(m) for m in messages), "Original question prompt was not resumed"
            assert any(m.get("role") == "assistant" and isinstance(m.get("content"), list)
                       and any(b.get("type") == "tool_use" and b.get("name") == "AskUserQuestion" for b in m["content"])
                       for m in messages), "Original native question context was not resumed"
            current = next(text(m) for m in reversed(messages) if m.get("role") == "user" and "PROBE_RECOVERED_ANSWER" in text(m))
            assert QUESTION in current and "Blue" in current, "Recovered answer was not supplied to the original session"
            block = {"type": "text", "text": "PROBE_RECOVERED_ANSWER_OK"}
        elif "PROBE_ASK" in prompt:
            if "probe_ask" not in completed:
                assert "AskUserQuestion" in tools, "AskUserQuestion was not advertised"
                block = {"type": "tool_use", "id": "probe_ask", "name": "AskUserQuestion", "input": {
                    "questions": [{"question": QUESTION, "header": "Color", "multiSelect": False,
                                   "options": [{"label": "Blue", "description": "Choose blue"},
                                               {"label": "Green", "description": "Choose green"}]}]}}
            else:
                result = completed["probe_ask"]
                assert not result.get("is_error") and "Blue" in json.dumps(result), "Native answer was not returned"
                block = {"type": "text", "text": "PROBE_ASK_OK"}
        elif prompt in {"PROBE_FILES", "PROBE_MEMORY"}:
            operations = [
                ("Write", {"file_path": "/home/agent/probe.txt", "content": "probe-one\n"}),
                ("Read", {"file_path": "/home/agent/probe.txt"}),
                ("Edit", {"file_path": "/home/agent/probe.txt", "old_string": "probe-one", "new_string": "probe-two"}),
                ("Glob", {"pattern": "probe.txt", "path": "/home/agent"}),
                ("Grep", {"pattern": "probe-two", "path": "/home/agent/probe.txt"}),
            ] if prompt == "PROBE_FILES" else [
                ("Write", {"file_path": "/home/agent/.claude/projects/-home-agent/memory/probe-note.md", "content": "---\nname: Probe preference\ndescription: Isolated synthetic preference\ntype: user\n---\nThe test color is blue.\n"}),
                ("Edit", {"file_path": "/home/agent/.claude/projects/-home-agent/memory/MEMORY.md", "old_string": "This is isolated probe memory.", "new_string": "This is isolated probe memory.\n- [Probe preference](probe-note.md)"}),
            ]
            prefix = "probe_file_" if prompt == "PROBE_FILES" else "probe_memory_"
            pending = next(((i, op) for i, op in enumerate(operations) if prefix + str(i) not in completed), None)
            for i in range(len(operations)):
                if prefix + str(i) in completed:
                    assert not completed[prefix + str(i)].get("is_error"), "Native file operation failed"
            if pending:
                i, (name, arguments) = pending
                assert name in tools, "Native file tool not advertised"
                block = {"type": "tool_use", "id": prefix + str(i), "name": name, "input": arguments}
            else:
                block = {"type": "text", "text": prompt + "_OK"}
        elif "PROBE_INTERRUPT" in prompt:
            self.slow_started.set()
            self.stop.wait(25)
            block = {"type": "text", "text": "PROBE_SLOW_RESPONSE"}
        elif "PROBE_AFTER_INTERRUPT" in prompt:
            block = {"type": "text", "text": "PROBE_AFTER_INTERRUPT_OK"}
        else:
            block = {"type": "text", "text": "PROBE_READY"}
        return {"id": "msg_probe_" + str(len(self.requests)), "type": "message", "role": "assistant",
                "model": body.get("model", "phoneagent-interaction-probe"), "content": [block],
                "stop_reason": "tool_use" if block["type"] == "tool_use" else "end_turn", "stop_sequence": None,
                "usage": {"input_tokens": 128, "output_tokens": 32}}

    def status(self):
        return {"slow_started": self.slow_started.is_set(), "request_count": len(self.requests)}

    def server(self, port=0):
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                if self.path != "/status":
                    self.send_error(404)
                    return
                self.respond(200, fixture.status())

            def do_POST(self):
                if self.headers.get("x-api-key") != KEY and self.headers.get("Authorization") != "Bearer " + KEY:
                    self.send_error(401)
                    return
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.path.split("?")[0].endswith("/count_tokens"):
                    self.respond(200, {"input_tokens": 128})
                    return
                try:
                    response = fixture.response(body)
                except Exception as error:
                    self.respond(400, {"type": "error", "error": {"type": "invalid_request_error", "message": str(error)}})
                    return
                if not body.get("stream"):
                    self.respond(200, response)
                    return
                initial = {**response, "content": [], "stop_reason": None}
                events = [("message_start", {"type": "message_start", "message": initial})]
                for index, block in enumerate(response["content"]):
                    start = {**block, "input": {}} if block["type"] == "tool_use" else {"type": "text", "text": ""}
                    delta = {"type": "input_json_delta", "partial_json": json.dumps(block["input"])} if block["type"] == "tool_use" else {"type": "text_delta", "text": block["text"]}
                    events.extend([("content_block_start", {"type": "content_block_start", "index": index, "content_block": start}),
                                   ("content_block_delta", {"type": "content_block_delta", "index": index, "delta": delta}),
                                   ("content_block_stop", {"type": "content_block_stop", "index": index})])
                events.extend([("message_delta", {"type": "message_delta", "delta": {"stop_reason": response["stop_reason"], "stop_sequence": None}, "usage": {"output_tokens": 32}}),
                               ("message_stop", {"type": "message_stop"})])
                data = b"".join(("event: " + event + "\ndata: " + json.dumps(value) + "\n\n").encode() for event, value in events)
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def respond(self, status, value):
                data = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        return ThreadingHTTPServer(("127.0.0.1", port), Handler)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()
    fixture = Fixture()
    server = fixture.server(args.port)
    print("Interaction fixture listening on 127.0.0.1:" + str(server.server_address[1]), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        fixture.stop.set()
        server.server_close()
