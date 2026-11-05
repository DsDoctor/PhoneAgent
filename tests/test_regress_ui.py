"""Host-only safety/timeout tests. Never starts adb or touches a device."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch


PATH = Path(__file__).resolve().parents[1] / "scripts" / "regress-ui.py"
SPEC = importlib.util.spec_from_file_location("phoneagent_regress_ui", PATH)
ui = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ui
SPEC.loader.exec_module(ui)


def node(desc="任务输入", text="", kind="android.widget.EditText", **kwargs):
    return ui.Node(kind, desc, text, ui.Rect(10, 20, 210, 100), True, True, True,
                   False, False, ui.PACKAGE, **kwargs)


class RegressionSafetyTests(unittest.TestCase):
    def runner(self, temp):
        args = ui.parse_args(["--output", str(Path(temp) / "result")])
        result = ui.Regression(args)
        result.adb = Mock()
        result.adb.deadline = time.monotonic() - 1
        return result

    def test_xml_parser_keeps_sensitive_fields_out_of_repr_and_summary(self):
        raw = b'''noise<?xml version="1.0"?><hierarchy rotation="0"><node class="android.widget.FrameLayout"
            package="com.ds.phoneagent" bounds="[0,0][1080,2388]"><node class="android.widget.EditText"
            package="com.ds.phoneagent" content-desc="API Key" text="DO_NOT_LOG_SECRET" enabled="true"
            focused="true" bounds="[10,20][500,100]" /></node></hierarchy>status'''
        parsed = ui.Ui.parse(raw)
        self.assertTrue(parsed.config_fields_visible)
        self.assertNotIn("DO_NOT_LOG_SECRET", repr(parsed.nodes))
        self.assertNotIn("DO_NOT_LOG_SECRET", json.dumps(parsed.safe_summary()))

    def test_invalid_xml_never_leaks_its_body_in_exception(self):
        with self.assertRaises(ui.Failure) as raised:
            ui.Ui.parse(b"DO_NOT_LOG_SECRET")
        self.assertNotIn("DO_NOT_LOG_SECRET", str(raised.exception))

    def test_code_horizontal_scroll_is_not_misreported_as_a_table(self):
        code = node(desc="", kind="android.widget.HorizontalScrollView", children=[node(desc="", text="code")])
        table = node(desc="", kind="android.widget.HorizontalScrollView", children=[node(desc="", kind="android.widget.TableLayout")])
        parsed = ui.Ui([code, table], "0")
        self.assertEqual(parsed.tables, [table])
        self.assertTrue(ui.has_markdown_table("Name | State\n--- | :---:"))

    def test_multiple_visible_process_controls_are_skipped_without_toggling(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = self.runner(temp)
            runner.conversations = [{"id": "a", "title": "one"}]
            runner.open_conversation = Mock(return_value=ui.Ui([
                node("展开执行过程", kind="android.widget.Button"),
                node("收起执行过程", kind="android.widget.Button")], "0"))
            runner.screenshot = Mock()
            runner.tap = Mock()
            with self.assertRaises(ui.Skip):
                runner.process({})
            runner.tap.assert_not_called()
            self.assertIsNone(runner.pending_process)

    def test_duplicate_titles_anywhere_prevent_a_wrong_conversation_tap(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = self.runner(temp)
            runner.conversations = [{"id": "a", "title": "duplicate"}, {"id": "b", "title": "duplicate"}]
            runner.open_history = Mock()
            with self.assertRaises(ui.Failure):
                runner.open_conversation(runner.conversations[0])
            runner.open_history.assert_not_called()

    def test_all_device_commands_use_selected_serial_and_remaining_deadline(self):
        bridge = ui.Adb("/test/adb", "exact-serial", 106, 12)
        completed = subprocess.CompletedProcess([], 0, stdout=b"ok", stderr=b"")
        with patch.object(ui.time, "monotonic", return_value=101), patch.object(ui.subprocess, "run", return_value=completed) as run:
            self.assertEqual(bridge.run("shell", "dumpsys", "accessibility"), b"ok")
        self.assertEqual(run.call_args.args[0][:3], ["/test/adb", "-s", "exact-serial"])
        self.assertEqual(run.call_args.kwargs["timeout"], 5)

    def test_expired_deadline_does_not_start_a_command(self):
        bridge = ui.Adb("adb", "test", 1, 12)
        with patch.object(ui.time, "monotonic", return_value=2), patch.object(ui.subprocess, "run") as run:
            with self.assertRaises(ui.Failure):
                bridge.run("shell", "input", "tap", "1", "1")
        run.assert_not_called()

    def test_command_timeout_and_error_do_not_include_raw_output(self):
        bridge = ui.Adb("adb", "test", time.monotonic() + 30, 2)
        with patch.object(ui.subprocess, "run", side_effect=subprocess.TimeoutExpired("secret command", 2, output=b"SECRET")):
            with self.assertRaises(ui.Failure) as raised:
                bridge.run("shell", "dumpsys", "window", label="UI check")
        self.assertNotIn("SECRET", str(raised.exception))
        with patch.object(ui.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"SECRET", b"SECRET")):
            with self.assertRaises(ui.CommandFailure) as raised:
                bridge.run("shell", "dumpsys", "window", label="UI check")
        self.assertNotIn("SECRET", str(raised.exception))

    def test_submit_delete_save_and_reset_can_never_be_tapped(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = self.runner(temp)
            for desc, text in [("开始执行", "开始执行"), ("继续对话", "继续对话"),
                               ("删除此任务", "删除"), ("重置为待命", "重置"),
                               ("", "保存连接配置"), ("停止任务", "停止")]:
                with self.assertRaises(ui.Failure):
                    runner.tap(node(desc, text, "android.widget.Button"))
            runner.adb.shell.assert_not_called()

    def test_draft_ownership_accepts_only_complete_known_suffix(self):
        self.assertEqual(ui.draft_prefix("73112345", "73112345", "想让月薪喵做什么？"), "")
        self.assertEqual(ui.draft_prefix("想让月薪喵做什么？73112345", "73112345", "想让月薪喵做什么？"), "想让月薪喵做什么？")
        for text in ("user draft", "73112345changed", "changed73112345", "731123"):
            self.assertIsNone(ui.draft_prefix(text, "73112345", ""))

    def test_changed_draft_is_never_deleted_during_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = self.runner(temp)
            runner.owned_draft = {"marker": "73112345", "original": ""}
            runner.new_task = Mock(return_value=ui.Ui([node(text="user-modified")], "0"))
            with self.assertRaises(ui.Failure):
                runner.clean_draft()
            runner.adb.shell.assert_not_called()
            self.assertIsNotNone(runner.owned_draft)

    def test_cleanup_has_new_budget_and_continues_after_one_restore_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = self.runner(temp)
            runner.baseline = {name: "same" for name in ui.FILES}
            runner.ui_touched = True
            runner.restore_rotation = Mock(side_effect=ui.Failure("rotation failure"))
            runner.clean_draft = Mock()
            runner.restore_process = Mock()
            runner.restore_page = Mock()
            runner.hashes = Mock(return_value=(runner.baseline, {}))
            runner.accessibility = Mock(return_value={"phoneagent_bound": True})
            item = {}
            with self.assertRaises(ui.Failure):
                runner.cleanup(item)
            self.assertGreater(runner.adb.deadline, time.monotonic())
            self.assertLessEqual(runner.adb.deadline, runner.overall_deadline)
            runner.clean_draft.assert_called_once()
            runner.restore_process.assert_called_once()
            runner.restore_page.assert_called_once()
            runner.hashes.assert_called_once()
            self.assertEqual(len(item["restoration_errors"]), 1)

    def test_keyboard_interrupt_runs_cleanup_and_records_failure_timing(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = self.runner(temp)
            runner.adb = None
            runner.preflight = Mock(side_effect=KeyboardInterrupt())
            runner.cleanup = Mock()
            with patch("sys.stdout", new=io.StringIO()):
                self.assertEqual(runner.run(), 1)
            runner.cleanup.assert_called_once()
            report = json.loads((runner.output / "result.json").read_text())
            self.assertEqual(report["result"], "FAIL")
            self.assertEqual(report["steps"][0]["status"], "FAIL")
            self.assertIn("seconds", report["steps"][0])
            self.assertEqual(report["steps"][-1]["name"], "restore and verify")

    def test_skipped_coverage_is_not_reported_as_full_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            runner = self.runner(temp)
            runner.adb = None
            for name in ("preflight", "navigation", "process", "table", "draft", "task_ime", "settings_ime", "extended", "cleanup"):
                setattr(runner, name, Mock())
            runner.reveal = Mock(side_effect=ui.Skip("No existing history"))
            with patch("sys.stdout", new=io.StringIO()):
                self.assertEqual(runner.run(), 0)
            self.assertEqual(runner.report["result"], "PASS_WITH_SKIPS")
            self.assertIn("swipe reveal and close", runner.report["skipped_checks"])


if __name__ == "__main__":
    unittest.main()
