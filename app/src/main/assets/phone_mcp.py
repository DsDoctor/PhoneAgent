#!/usr/bin/env python3
"""Minimal stdio MCP adapter. Android performs actions via a task-scoped localhost bridge."""
import json
import os
import sys
import urllib.request
import urllib.error


def schema(properties=None, required=None):
    return {"type": "object", "properties": properties or {}, "required": required or [], "additionalProperties": False}


TOOLS = [
    {"name": "read_screen", "description": "Read the active Android UI: text, editable fields and clickable nodes with coordinates. Read again after actions to verify the outcome. Password fields are excluded.", "inputSchema": schema()},
    {"name": "home", "description": "Return to the Android home screen.", "inputSchema": schema()},
    {"name": "back", "description": "Press Android Back once.", "inputSchema": schema()},
    {"name": "open_settings", "description": "Open Android system settings.", "inputSchema": schema()},
    {"name": "open_app", "description": "Open an installed application by Android package name.", "inputSchema": schema({"package_name": {"type": "string"}}, ["package_name"])},
    {"name": "tap", "description": "Tap a visible coordinate from read_screen. Read the screen again to verify.", "inputSchema": schema({"x": {"type": "number"}, "y": {"type": "number"}}, ["x", "y"])},
    {"name": "swipe", "description": "Swipe between two screen coordinates.", "inputSchema": schema({"x1": {"type": "number"}, "y1": {"type": "number"}, "x2": {"type": "number"}, "y2": {"type": "number"}, "duration_ms": {"type": "integer", "minimum": 100, "maximum": 1500}}, ["x1", "y1", "x2", "y2"])},
    {"name": "type_text", "description": "Replace text in the currently focused editable field. Does not submit the form; password inputs are excluded.", "inputSchema": schema({"text": {"type": "string", "maxLength": 4000}}, ["text"])},
]
KNOWN = {tool["name"] for tool in TOOLS}


def invoke(name, arguments):
    if name not in KNOWN:
        raise ValueError("Unknown phone tool")
    data = json.dumps({"name": name, "arguments": arguments}, ensure_ascii=False).encode("utf-8")
    port = int(os.environ["PHONE_AGENT_PORT"])
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/tool", data=data,
        headers={"Authorization": "Bearer " + os.environ["PHONE_AGENT_TOKEN"], "Content-Type": "application/json"},
        method="POST")
    # Ignore any ambient HTTP proxy for this private loopback connection.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=15) as response:
        return json.load(response)


def handle(message):
    if not isinstance(message, dict):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid request"}}
    if "id" not in message:
        return None
    method = message.get("method")
    if method == "initialize":
        result = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}}, "serverInfo": {"name": "phone", "version": "0.1.0"}}
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        params = message.get("params", {})
        try:
            if not isinstance(params, dict):
                raise ValueError("Invalid tool parameters")
            value = invoke(params.get("name"), params.get("arguments", {}))
            result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}], "isError": value.get("ok") is False or "error" in value}
        except Exception as exc:
            # Do not include headers or environment variables in error output.
            result = {"content": [{"type": "text", "text": "Phone tool failed: " + str(exc)}], "isError": True}
    else:
        return {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Method not found"}}
    return {"jsonrpc": "2.0", "id": message["id"], "result": result}


def main():
    for line in sys.stdin:
        try:
            message = json.loads(line)
            reply = handle(message)
        except (ValueError, TypeError):
            reply = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
        if reply is not None:
            print(json.dumps(reply, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
