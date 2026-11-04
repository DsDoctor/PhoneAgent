"""Startup waiting must observe the target app and cannot dismiss consent or ads."""
import copy
import http.client
import importlib.util
import json
import os
from pathlib import Path
import sys
import threading
import time
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import Mock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "app/src/main/assets/phone_mcp.py"
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("phone_mcp_ready", SCRIPT)
mcp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mcp)


class Clock:
    def __init__(self):
        self.now = 1000.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


def screen(package="cn.damai", text="演出", view_id="cn.damai:id/home_tab"):
    return {"ok": True, "locked": False, "package": package, "nodes": [
        {"view_id": view_id, "text": text, "description": "", "bounds": [0, 0, 200, 100],
         "class": "android.widget.TextView", "clickable": True, "enabled": True}]}


class OpenAppReadyTests(unittest.TestCase):
    def setUp(self):
        # Startup unit tests must not write repeated-success counters to the host's HOME.
        store = Mock()
        store.record_action.return_value = None
        self.store_patch = patch.object(mcp, "get_routine_store", return_value=store)
        self.store_patch.start()
        self.addCleanup(self.store_patch.stop)

    def run_sequence(self, observations, timeout_ms=15000, launch=None):
        clock = Clock()
        calls = []
        pending = iter(observations)
        last = None

        def bridge(name, arguments, timeout=15):
            nonlocal last
            calls.append((name, arguments, timeout))
            self.assertGreater(timeout, 0)
            self.assertLessEqual(timeout, timeout_ms / 1000)
            if name == "open_app":
                return launch if launch is not None else {"ok": True}
            self.assertEqual(name, "read_screen")
            last = next(pending, last)
            if isinstance(last, Exception):
                raise last
            return copy.deepcopy(last)

        with patch.object(mcp, "invoke_bridge", side_effect=bridge), \
                patch.object(mcp.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(mcp.time, "sleep", side_effect=clock.sleep):
            result = mcp.invoke("open_app_ready", {"package_name": "cn.damai", "timeout_ms": timeout_ms})
        return result, calls, clock

    def test_empty_root_then_splash_then_stable_target(self):
        empty = {"ok": False, "locked": False, "error": "当前没有可读取的应用界面", "screen_width": 1080}
        splash = screen(text="", view_id="cn.damai:id/splash_image")
        ready = screen()
        result, calls, clock = self.run_sequence([empty, splash, ready, ready, ready])
        self.assertTrue(result["ok"])
        self.assertEqual(result["status"], "ready")
        self.assertEqual(result["screen"], ready)
        self.assertEqual(result["wait"]["poll_count"], 5)
        self.assertEqual(result["wait"]["stable_ms"], 1000)
        self.assertTrue(result["wait"]["seen_splash"])
        self.assertEqual(result["wait"]["splash_view_ids"], [])
        self.assertEqual([name for name, _, _ in calls], ["open_app"] + ["read_screen"] * 5)
        self.assertEqual(clock.sleeps, [0.5] * 4)

    def test_each_observed_damai_ad_id_blocks_even_with_readable_text(self):
        for view_id in mcp.DAMAI_SPLASH_IDS:
            with self.subTest(view_id=view_id):
                ad = screen(text="立即购买", view_id=view_id)
                result, calls, clock = self.run_sequence([ad], timeout_ms=1500)
                self.assertFalse(result["ok"])
                self.assertEqual(result["status"], "blocked")
                self.assertEqual(result["wait"]["reason"], "splash_visible")
                self.assertEqual(result["wait"]["splash_view_ids"], [view_id])
                self.assertEqual(result["screen"], ad)
                self.assertEqual(result["wait"]["elapsed_ms"], 1500)
                self.assertEqual({name for name, _, _ in calls}, {"open_app", "read_screen"})

    def test_wrong_package_never_uses_stale_target_screen(self):
        ready = screen()
        permission = screen(package="com.android.permissioncontroller", text="允许通知")
        result, calls, _ = self.run_sequence([ready, ready, permission], timeout_ms=2500)
        self.assertEqual(result["status"], "blocked")
        self.assertEqual(result["wait"]["reason"], "wrong_package")
        self.assertEqual(result["screen"], permission)
        self.assertNotIn("tap", [name for name, _, _ in calls])

    def test_wrong_package_resets_stability_window(self):
        ready = screen()
        other = screen(package="com.huawei.android.launcher")
        result, _, _ = self.run_sequence([ready, ready, other, ready, ready, ready])
        self.assertEqual(result["status"], "ready")
        self.assertEqual(result["wait"]["elapsed_ms"], 2500)
        self.assertEqual(result["wait"]["poll_count"], 6)

    def test_default_budget_is_fifteen_seconds_and_maximum_is_thirty(self):
        clock = Clock()
        with patch.object(mcp.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(mcp.time, "sleep", side_effect=clock.sleep), \
                patch.object(mcp, "invoke_bridge", side_effect=lambda name, args, timeout: {"ok": True} if name == "open_app" else screen(package="other.app")):
            result = mcp.invoke("open_app_ready", {"package_name": "cn.damai"})
        self.assertEqual(result["wait"]["timeout_ms"], 15000)
        self.assertEqual(result["wait"]["elapsed_ms"], 15000)
        self.assertTrue(all(delay <= 0.5 for delay in clock.sleeps))
        result, _, _ = self.run_sequence([screen(package="other.app")], timeout_ms=30000)
        self.assertEqual(result["wait"]["elapsed_ms"], 30000)

    def test_changing_target_screen_times_out_with_last_observation(self):
        frames = [screen(text=f"正在加载 {n}") for n in range(6)]
        result, _, _ = self.run_sequence(frames, timeout_ms=2500)
        self.assertEqual(result["status"], "blocked")
        self.assertEqual(result["wait"]["reason"], "screen_not_stable")
        self.assertEqual(result["screen"], frames[4])

    def test_slow_launch_consumes_the_same_wait_budget(self):
        clock = Clock()
        calls = []

        def slow_launch(name, arguments, timeout):
            calls.append((name, timeout))
            clock.now += 1.5
            return {"ok": True}

        with patch.object(mcp, "invoke_bridge", side_effect=slow_launch), \
                patch.object(mcp.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(mcp.time, "sleep", side_effect=clock.sleep):
            result = mcp.invoke("open_app_ready", {"package_name": "cn.damai", "timeout_ms": 1500})
        self.assertEqual(result["status"], "blocked")
        self.assertEqual(result["wait"]["elapsed_ms"], 1500)
        self.assertEqual(result["wait"]["poll_count"], 0)
        self.assertEqual(calls, [("open_app", 1.5)])

    def test_empty_target_nodes_do_not_count_as_readable(self):
        empty = {"ok": True, "locked": False, "package": "cn.damai", "nodes": []}
        result, _, _ = self.run_sequence([empty], timeout_ms=1000)
        self.assertEqual(result["status"], "blocked")
        self.assertEqual(result["wait"]["reason"], "empty_screen")

    def test_http_error_stops_immediately_and_preserves_last_screen(self):
        last = screen(view_id="cn.damai:id/splash_layout")
        failure = urllib.error.HTTPError("http://127.0.0.1/tool", 401, "Unauthorized", {}, None)
        result, calls, clock = self.run_sequence([last, failure])
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["wait"]["reason"], "bridge_http_error")
        self.assertEqual(result["wait"]["http_status"], 401)
        self.assertEqual(result["screen"], last)
        self.assertEqual(len(calls), 3)
        self.assertEqual(clock.sleeps, [0.5])

    def test_cancellation_bridge_close_stops_without_relaunch_or_retry(self):
        for failure in (urllib.error.URLError(ConnectionRefusedError("closed")), ConnectionResetError("cancelled")):
            with self.subTest(error=type(failure).__name__):
                result, calls, clock = self.run_sequence([failure])
                self.assertEqual(result["status"], "error")
                self.assertEqual(result["wait"]["reason"], "bridge_unavailable")
                self.assertEqual([name for name, _, _ in calls], ["open_app", "read_screen"])
                self.assertEqual(clock.sleeps, [])

    def test_truncated_http_response_preserves_structured_error_and_last_screen(self):
        last = screen(view_id="cn.damai:id/splash_image")
        result, calls, _ = self.run_sequence([last, http.client.IncompleteRead(b'{"ok":', 20)])
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["wait"]["reason"], "bridge_unavailable")
        self.assertEqual(result["screen"], last)
        self.assertEqual(len(calls), 3)

    def test_slow_stream_cannot_extend_whole_http_deadline(self):
        stop = threading.Event()

        class SlowHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                payload = json.dumps(screen()).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                try:
                    for value in payload:
                        self.wfile.write(bytes([value]))
                        self.wfile.flush()
                        if stop.wait(0.03):
                            break
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), SlowHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with patch.dict(os.environ, {"PHONE_AGENT_PORT": str(server.server_port), "PHONE_AGENT_TOKEN": "test-token"}):
                started = time.perf_counter()
                with self.assertRaises(TimeoutError):
                    mcp.invoke_bridge("read_screen", {}, timeout=0.2)
                elapsed = time.perf_counter() - started
            self.assertLess(elapsed, 0.7, "Socket activity must not keep extending the total deadline")
        finally:
            stop.set()
            server.shutdown()
            server.server_close()
            thread.join()

    def test_locked_phone_and_failed_launch_stop_without_other_actions(self):
        locked = {"ok": False, "locked": True, "error": "请解锁"}
        result, calls, _ = self.run_sequence([locked])
        self.assertEqual(result["status"], "blocked")
        self.assertEqual(result["wait"]["reason"], "locked")
        self.assertEqual(len(calls), 2)
        result, calls, _ = self.run_sequence([], launch={"ok": False, "error": "未安装"})
        self.assertEqual(result["wait"]["reason"], "launch_failed")
        self.assertEqual(len(calls), 1)

    def test_invalid_arguments_rejected_before_launch(self):
        invalid = [{"package_name": "cn.damai", "timeout_ms": value} for value in (0, 30001, True, 1500.0, "1500", None)]
        invalid += [{"package_name": "cn.damai;tap"}, {"package_name": ""}, {"package_name": "cn.damai", "tap": True}]
        with patch.object(mcp, "invoke_bridge") as bridge:
            for arguments in invalid:
                with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                    mcp.invoke("open_app_ready", arguments)
        bridge.assert_not_called()

    def test_mcp_blocked_result_is_structured_tool_error(self):
        value = {"ok": False, "status": "blocked", "screen": screen(), "wait": {"reason": "splash_visible"}}
        with patch.object(mcp, "open_app_ready", return_value=value):
            reply = mcp.handle({"id": 10, "method": "tools/call", "params": {"name": "open_app_ready", "arguments": {"package_name": "cn.damai"}}})
        self.assertTrue(reply["result"]["isError"])
        self.assertEqual(json.loads(reply["result"]["content"][0]["text"]), value)


if __name__ == "__main__":
    unittest.main()
