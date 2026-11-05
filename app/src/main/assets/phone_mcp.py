#!/usr/bin/env python3
"""Minimal stdio MCP adapter. Android performs actions via a task-scoped localhost bridge."""
import json
import http.client
import os
import queue
import re
import socket
import sys
import threading
import time
import urllib.error
from phone_routines import ROUTINE_TOOLS, RoutineStore


def schema(properties=None, required=None):
    return {"type": "object", "properties": properties or {}, "required": required or [], "additionalProperties": False}


TOOLS = [
    {"name": "read_screen", "description": "Read the active Android UI: text, editable fields and clickable nodes with coordinates. Read again after actions to verify the outcome. Password fields are excluded.", "inputSchema": schema()},
    {"name": "home", "description": "Return to the Android home screen.", "inputSchema": schema()},
    {"name": "back", "description": "Press Android Back once.", "inputSchema": schema()},
    {"name": "open_settings", "description": "Open Android system settings.", "inputSchema": schema()},
    {"name": "open_app", "description": "Open an installed application by Android package name.", "inputSchema": schema({"package_name": {"type": "string"}}, ["package_name"])},
    {"name": "open_app_ready", "description": "Open an installed app and wait locally for its target-package screen to become readable and briefly stable. Prefer this over repeated open_app/read_screen calls when launching an app. Handles observed Damai splash/ad views by waiting for them to end naturally. Never taps ads or accepts permissions. Returns ready, blocked (with the last screen), or error. Read the returned screen to decide the next action; ready does not mean the user's business task is complete.", "inputSchema": schema({"package_name": {"type": "string"}, "timeout_ms": {"type": "integer", "minimum": 1000, "maximum": 30000, "default": 15000}}, ["package_name"])},
    {"name": "tap", "description": "Tap a visible coordinate from read_screen. Read the screen again to verify.", "inputSchema": schema({"x": {"type": "number"}, "y": {"type": "number"}}, ["x", "y"])},
    {"name": "swipe", "description": "Swipe between two screen coordinates.", "inputSchema": schema({"x1": {"type": "number"}, "y1": {"type": "number"}, "x2": {"type": "number"}, "y2": {"type": "number"}, "duration_ms": {"type": "integer", "minimum": 100, "maximum": 1500}}, ["x1", "y1", "x2", "y2"])},
    {"name": "type_text", "description": "Replace text in the currently focused editable field. Does not submit the form; password inputs are excluded.", "inputSchema": schema({"text": {"type": "string", "maxLength": 4000}}, ["text"])},
] + ROUTINE_TOOLS
KNOWN = {tool["name"] for tool in TOOLS}
ROUTINE_NAMES = {tool["name"] for tool in ROUTINE_TOOLS}
BRIDGE_TOOLS = {"read_screen", "home", "back", "open_settings", "open_app", "tap", "swipe", "type_text"}
_ROUTINE_STORE = None
_ACTION_CONTEXT = threading.local()
POLL_SECONDS = 0.5
STABLE_SECONDS = 1.0
DAMAI_SPLASH_IDS = frozenset({
    "cn.damai:id/splash_layout",
    "cn.damai:id/splash_image",
    "cn.damai:id/homepage_advert_pb",
})


class WaitBudgetExpired(Exception):
    pass


class ActionPaused(Exception):
    pass


def invoke(name, arguments):
    # One MCP tool may make many HTTP calls. Keep its first gate generation even
    # across local polling/settle sleeps; a resume must not revive old steps.
    _ACTION_CONTEXT.active = True
    _ACTION_CONTEXT.epoch = None
    _ACTION_CONTEXT.blocked = False
    try:
        return invoke_in_context(name, arguments)
    finally:
        _ACTION_CONTEXT.active = False
        _ACTION_CONTEXT.epoch = None
        _ACTION_CONTEXT.blocked = False


def invoke_in_context(name, arguments):
    if name not in KNOWN:
        raise ValueError("Unknown phone tool")
    store = get_routine_store()
    if name in ROUTINE_NAMES:
        value = store.dispatch(name, arguments, invoke_phone)
        if name == "run_routine":
            # Prevent a proof from silently omitting an intervening saved routine.
            attach_receipt(store, name, arguments, value)
        return value
    try:
        value = invoke_phone(name, arguments)
    except Exception:
        # The Android action may have happened before its connection failed. Leave
        # a failed/redacted marker so later proofs cannot omit the uncertain action.
        attach_receipt(store, name, arguments, {"ok": False})
        raise
    attach_receipt(store, name, arguments, value)
    return value


def get_routine_store():
    global _ROUTINE_STORE
    if _ROUTINE_STORE is None:
        # Tests can isolate the store without changing HOME or model configuration.
        _ROUTINE_STORE = RoutineStore(root=os.environ.get("PHONE_AGENT_ROUTINES_DIR") or None)
    return _ROUTINE_STORE


def attach_receipt(store, name, arguments, value):
    try:
        receipt = store.record_action(name, arguments, value)
        if receipt and isinstance(value, dict):
            value["routine_receipt"] = receipt
    except Exception:
        # A bookkeeping failure must not relabel a real phone action as unsuccessful.
        if isinstance(value, dict):
            value["routine_record_error"] = "未能记录脚本验证凭据；本次手机操作结果仍以 ok 为准。"


def invoke_phone(name, arguments):
    """Unrecorded child dispatcher for compound tools; routines cannot recurse."""
    if name == "open_app_ready":
        return open_app_ready(arguments)
    return invoke_bridge(name, arguments)


def invoke_bridge(name, arguments, timeout=15):
    """One existing Android bridge action; composite tools never reach the bridge."""
    if name not in BRIDGE_TOOLS:
        raise ValueError("Unknown Android bridge tool")
    contextual = getattr(_ACTION_CONTEXT, "active", False)
    if contextual and getattr(_ACTION_CONTEXT, "blocked", False):
        return {"ok": False, "error_code": "task_paused", "error": "任务已暂停或执行状态已改变；旧操作不会继续。"}
    expected_epoch = getattr(_ACTION_CONTEXT, "epoch", None) if contextual else None
    request_body = {"name": name, "arguments": arguments}
    if expected_epoch is not None:
        request_body["action_epoch"] = expected_epoch
    data = json.dumps(request_body, ensure_ascii=False).encode("utf-8")
    port = int(os.environ["PHONE_AGENT_PORT"])
    headers = {"Authorization": "Bearer " + os.environ["PHONE_AGENT_TOKEN"], "Content-Type": "application/json"}
    # Connect directly to loopback: never use an ambient proxy or follow redirects.
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    outcome = queue.Queue(maxsize=1)
    connected_socket = [None]

    def request():
        response = None
        try:
            connection.request("POST", "/tool", body=data, headers=headers)
            # HTTPConnection can detach its socket for a Connection: close response;
            # retain the reference so an absolute timeout can interrupt body reads.
            connected_socket[0] = connection.sock
            response = connection.getresponse()
            if response.status != 200:
                raise urllib.error.HTTPError(f"http://127.0.0.1:{port}/tool", response.status,
                                             "Android bridge HTTP error", response.headers, None)
            payload = response.read(1024 * 1024 + 1)
            if len(payload) > 1024 * 1024:
                raise ValueError("Android bridge response is too large")
            outcome.put((True, json.loads(payload)))
        except Exception as error:
            outcome.put((False, error))
        finally:
            if response is not None:
                response.close()
            connection.close()

    # A socket's idle timeout alone permits a slow stream to run indefinitely.
    # Bound the entire request (including headers/body), and interrupt its socket.
    worker = threading.Thread(target=request, name="phone-bridge", daemon=True)
    worker.start()
    try:
        succeeded, value = outcome.get(timeout=timeout)
    except queue.Empty:
        active_socket = connected_socket[0] or connection.sock
        if active_socket is not None:
            try:
                active_socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        raise TimeoutError("Android bridge request deadline exceeded") from None
    if not succeeded:
        raise value
    if not isinstance(value, dict):
        raise ValueError("Invalid Android bridge response")
    if contextual:
        epoch = value.get("action_epoch")
        # Legacy local fixtures omit the field. Once a real epoch is established,
        # a missing/malformed/different epoch cannot be silently accepted.
        mismatch = expected_epoch is not None and (type(epoch) is not int or epoch != expected_epoch)
        invalid = "action_epoch" in value and (type(epoch) is not int or epoch <= 0)
        if value.get("error_code") == "task_paused" or mismatch or invalid:
            _ACTION_CONTEXT.blocked = True
            return {"ok": False, "error_code": "task_paused", "error": "任务已暂停或执行状态已改变；旧操作不会继续。"}
        if expected_epoch is None and type(epoch) is int and epoch > 0:
            _ACTION_CONTEXT.epoch = epoch
    return value


def screen_readiness(screen, package_name):
    """Conservative, observation-only launch check; no coordinates or consent actions."""
    if screen.get("locked") is True:
        return "locked", None, []
    if screen.get("package") != package_name:
        return ("wrong_package" if screen.get("package") else "screen_unavailable"), None, []
    nodes = screen.get("nodes")
    if not isinstance(nodes, list) or not nodes:
        return "empty_screen", None, []
    nodes = [node for node in nodes if isinstance(node, dict)]
    splash_ids = sorted({node.get("view_id") for node in nodes
                         if isinstance(node.get("view_id"), str) and node["view_id"] in DAMAI_SPLASH_IDS})
    if package_name == "cn.damai" and splash_ids:
        return "splash_visible", None, splash_ids
    if screen.get("ok") is not True or "error" in screen:
        return "screen_unavailable", None, []
    # A bare root/container or progress indicator is not an actionable screen.
    readable = any(node.get("text") or node.get("description") or
                   (node.get("view_id") and (node.get("clickable") or node.get("editable") or node.get("scrollable")))
                   for node in nodes)
    if not readable:
        return "unreadable_screen", None, []
    # Ignore transport messages and focus bookkeeping, but retain visible content,
    # geometry and available actions so a changing/loading page cannot look stable.
    fields = ("view_id", "class", "text", "description", "bounds", "clickable", "editable", "scrollable", "enabled")
    fingerprint = tuple(sorted(json.dumps({key: node.get(key) for key in fields},
                                         sort_keys=True, ensure_ascii=False) for node in nodes))
    return "screen_not_stable", fingerprint, []


def open_app_ready(arguments):
    """Run bounded startup polling inside one MCP call, without extra model turns."""
    if not isinstance(arguments, dict) or set(arguments) - {"package_name", "timeout_ms"}:
        raise ValueError("open_app_ready expects package_name and optional timeout_ms")
    package_name = arguments.get("package_name")
    if not isinstance(package_name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", package_name):
        raise ValueError("Invalid Android package_name")
    timeout_ms = arguments.get("timeout_ms", 15000)
    if type(timeout_ms) is not int or not 1000 <= timeout_ms <= 30000:
        raise ValueError("timeout_ms must be an integer from 1000 to 30000")
    started = time.monotonic()
    deadline = started + timeout_ms / 1000.0
    launch = None
    last_screen = None
    poll_count = 0
    last_reason = "target_not_observed"
    last_splash_ids = []
    seen_splash = False
    previous_fingerprint = None
    stable_since = None
    stable_samples = 0

    def finish(status, reason, message, **details):
        wait = {"timeout_ms": timeout_ms, "elapsed_ms": round((time.monotonic() - started) * 1000),
                "poll_count": poll_count, "poll_interval_ms": round(POLL_SECONDS * 1000),
                "required_stable_ms": round(STABLE_SECONDS * 1000), "reason": reason,
                "observed_package": (last_screen or {}).get("package"),
                "seen_splash": seen_splash, "splash_view_ids": last_splash_ids}
        wait.update(details)
        return {"ok": status == "ready", "status": status, "package_name": package_name,
                "message": message, "launch": launch, "screen": last_screen, "wait": wait}

    def bridge_call(name, values):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise WaitBudgetExpired()
        value = invoke_bridge(name, values, timeout=remaining)
        if value.get("error_code") == "task_paused":
            raise ActionPaused()
        return value

    try:
        launch = bridge_call("open_app", {"package_name": package_name})
        if launch.get("ok") is not True or "error" in launch:
            return finish("error", "launch_failed", "系统未接受启动请求，请查看 launch 结果。")
        while time.monotonic() < deadline:
            # Always fetch a new observation after launch. Never reuse a prior task's
            # screen or accept another package while the target app is opening.
            poll_count += 1
            last_screen = bridge_call("read_screen", {})
            last_reason, fingerprint, last_splash_ids = screen_readiness(last_screen, package_name)
            seen_splash = seen_splash or bool(last_splash_ids)
            observed_at = time.monotonic()
            if last_reason == "locked":
                return finish("blocked", "locked", "手机处于锁屏状态，请由用户解锁后继续。")
            if fingerprint is None:
                previous_fingerprint = None
                stable_since = None
                stable_samples = 0
            elif fingerprint != previous_fingerprint:
                previous_fingerprint = fingerprint
                stable_since = observed_at
                stable_samples = 1
            else:
                stable_samples += 1
                if observed_at <= deadline and stable_samples >= 3 and observed_at - stable_since >= STABLE_SECONDS:
                    return finish("ready", "target_stable", "目标应用已显示可读且短暂稳定的界面，请依据 screen 继续。",
                                  stable_ms=round((observed_at - stable_since) * 1000))
            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(POLL_SECONDS, remaining))
        return finish("blocked", last_reason, "等待已到时限，尚未确认应用就绪；请查看最后 screen 决定下一步。")
    except ActionPaused:
        result = finish("error", "task_paused", "任务已暂停或执行状态已改变，已停止旧的应用等待流程。")
        result["error_code"] = "task_paused"
        return result
    except WaitBudgetExpired:
        return finish("blocked", last_reason, "等待已到时限，尚未确认应用就绪；请查看最后 screen 决定下一步。")
    except urllib.error.HTTPError as exc:
        code = exc.code
        exc.close()
        return finish("error", "bridge_http_error", "手机工具连接返回 HTTP 错误，已停止等待。", http_status=code)
    except TimeoutError:
        return finish("blocked", "bridge_timeout", "等待手机工具响应已超时；请查看最后 screen 决定下一步。")
    except (urllib.error.URLError, ConnectionError, OSError, http.client.HTTPException):
        # Closing the task-scoped bridge is how Controller cancellation reaches this
        # worker. Do not reopen/relaunch or retry after a failed bridge connection.
        return finish("error", "bridge_unavailable", "手机工具连接已关闭或不可用，已停止等待。")
    except (ValueError, TypeError):
        return finish("error", "invalid_bridge_response", "手机工具返回了无效结果，已停止等待。")


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
