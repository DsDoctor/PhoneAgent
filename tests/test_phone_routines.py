import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "app/src/main/assets/phone_routines.py"
spec = importlib.util.spec_from_file_location("phone_routines", MODULE)
routines = importlib.util.module_from_spec(spec)
spec.loader.exec_module(routines)
PACKAGE = "com.android.settings"


def screen(package=PACKAGE, text="设置", extra="private screen content"):
    return {"ok": True, "locked": False, "package": package,
            "nodes": [{"text": text, "description": ""}, {"text": extra}]}


def ready(package=PACKAGE, text="设置"):
    return {"ok": True, "status": "ready", "screen": screen(package, text)}


class Clock:
    def __init__(self):
        self.value = 1000.0
    def now(self):
        return self.value
    def wall(self):
        return 1800000000 + self.value
    def sleep(self, seconds):
        self.value += seconds


class RoutineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "routines"
        self.clock = Clock()
        self.store = self.new_store()

    def tearDown(self):
        self.temp.cleanup()

    def new_store(self):
        return routines.RoutineStore(self.root, self.clock.now, self.clock.wall, self.clock.sleep)

    def dispatch(self, name, args, call=None):
        return self.store.dispatch(name, args, call or self.no_call)

    def no_call(self, name, args):
        self.fail("Unexpected phone execution during validation: " + name)

    def open_receipt(self, store=None):
        return (store or self.store).record_action("open_app_ready", {"package_name": PACKAGE}, ready())

    def save_open_args(self, name="open_settings"):
        first = self.open_receipt()
        second = self.open_receipt()
        return {"name": name, "steps": [{"tool": "open_app_ready", "arguments": {"package_name": PACKAGE}}],
                "verify": {"package": PACKAGE, "text_contains": ["设置"]},
                "proofs": [{"step_receipts": [first], "verification_receipt": first},
                           {"step_receipts": [second], "verification_receipt": second}]}

    def save_guarded(self):
        proofs = []
        for _ in range(2):
            first = self.open_receipt()
            tap = self.store.record_action("tap", {"x": 50, "y": 100}, {"ok": True})
            final = self.store.record_action("read_screen", {}, screen(text="显示"))
            proofs.append({"step_receipts": [first, tap], "verification_receipt": final})
        args = {"name": "open_display", "steps": [
            {"tool": "open_app_ready", "arguments": {"package_name": PACKAGE}},
            {"tool": "tap", "arguments": {"x": 50, "y": 100}, "expect_package": PACKAGE}],
            "verify": {"package": PACKAGE, "text_contains": ["显示"]}, "proofs": proofs}
        result = self.dispatch("save_routine", args)
        self.assertTrue(result["ok"], result)
        return args

    def test_save_requires_two_real_matching_runs_and_lists_saved_summary(self):
        args = self.save_open_args()
        self.assertTrue(self.dispatch("save_routine", args)["ok"])
        listed = self.dispatch("list_routines", {})
        self.assertEqual(listed["routines"][0]["name"], "open_settings")
        self.assertEqual(listed["routines"][0]["step_count"], 1)
        stored = json.loads((self.root / "open_settings.json").read_text())
        self.assertEqual(stored["validation"]["successful_runs"], 2)
        self.assertEqual(stored["steps"][0]["arguments"]["timeout_ms"], 15000)
        disk = "\n".join(path.read_text() for path in self.root.iterdir())
        self.assertNotIn("private screen content", disk)
        self.assertNotIn("r_", (self.root / "open_settings.json").read_text())
        self.assertFalse(any(path.name.startswith(".write-") for path in self.root.iterdir()))

    def test_forged_duplicate_and_single_proofs_rejected(self):
        for variant in ("forged", "duplicate", "single"):
            args = self.save_open_args()
            if variant == "forged":
                args["proofs"][0]["step_receipts"] = ["r_forged"]
            elif variant == "duplicate":
                args["proofs"][1] = args["proofs"][0]
            else:
                args["proofs"] = args["proofs"][:1]
            self.assertFalse(self.dispatch("save_routine", args)["ok"], variant)
        self.assertFalse((self.root / "open_settings.json").exists())

    def test_changed_step_arguments_are_not_verified_by_other_receipts(self):
        args = self.save_open_args()
        args["steps"][0]["arguments"]["package_name"] = "com.example.other"
        self.assertFalse(self.dispatch("save_routine", args)["ok"])

    def test_expired_evidence_is_rejected(self):
        args = self.save_open_args()
        self.clock.sleep(routines.EVIDENCE_SECONDS + 1)
        self.assertFalse(self.dispatch("save_routine", args)["ok"])

    def test_proof_cannot_omit_typing_and_secret_is_not_persisted(self):
        first = self.open_receipt()
        self.assertIsNone(self.store.record_action("type_text", {"text": "do-not-store-this-secret"}, {"ok": True}))
        final = self.store.record_action("read_screen", {}, screen())
        second = self.open_receipt()
        args = {"name": "unsafe_omission", "steps": [{"tool": "open_app_ready", "arguments": {"package_name": PACKAGE}}],
                "verify": {"package": PACKAGE, "text_contains": ["设置"]},
                "proofs": [{"step_receipts": [first], "verification_receipt": final},
                           {"step_receipts": [second], "verification_receipt": second}]}
        self.assertFalse(self.dispatch("save_routine", args)["ok"])
        self.assertNotIn("do-not-store-this-secret", repr(self.store.history))
        for path in self.root.iterdir():
            self.assertNotIn("do-not-store-this-secret", path.read_text())

    def test_guards_mandatory_and_evidence_must_have_foreground(self):
        args = self.save_guarded()
        del args["steps"][1]["expect_package"]
        self.assertFalse(self.dispatch("save_routine", args)["ok"])
        args = self.save_guarded()
        args["steps"][1]["expect_package"] = "com.example.wrong"
        self.assertFalse(self.dispatch("save_routine", args)["ok"])

    def test_no_type_text_shell_or_recursive_routine_steps(self):
        for name in ("type_text", "shell", "run_routine", "save_routine", "open_app", "open_settings"):
            args = self.save_open_args()
            args["steps"][0] = {"tool": name, "arguments": {}}
            self.assertFalse(self.dispatch("save_routine", args)["ok"], name)

    def test_names_cannot_escape_directory(self):
        for name in ("../outside", "/tmp/outside", "a/b", "..", "a.json", "_hidden", "a" * 49):
            args = self.save_open_args()
            args["name"] = name
            self.assertFalse(self.dispatch("save_routine", args)["ok"], name)
            self.assertFalse(self.dispatch("run_routine", {"name": name})["ok"], name)
        self.assertFalse((Path(self.temp.name) / "outside").exists())

    def test_symlinks_are_not_followed(self):
        outside = Path(self.temp.name) / "outside.json"
        outside.write_text('{"secret":"outside-data"}')
        self.root.mkdir()
        (self.root / "unsafe.json").symlink_to(outside)
        result = self.dispatch("run_routine", {"name": "unsafe"})
        self.assertFalse(result["ok"])
        self.assertNotIn("outside-data", json.dumps(result))
        self.assertEqual(self.dispatch("list_routines", {})["routines"], [])
        redirected = Path(self.temp.name) / "redirected"
        redirected.symlink_to(self.root, target_is_directory=True)
        self.assertFalse(routines.RoutineStore(redirected).dispatch("list_routines", {}, self.no_call)["ok"])

    def test_saved_file_size_and_step_limits(self):
        self.root.mkdir()
        (self.root / "oversized.json").write_bytes(b" " * (routines.MAX_FILE_BYTES + 1))
        self.assertFalse(self.dispatch("run_routine", {"name": "oversized"})["ok"])
        args = self.save_open_args()
        args["steps"] *= routines.MAX_STEPS + 1
        self.assertFalse(self.dispatch("save_routine", args)["ok"])

    def test_non_regular_entries_are_rejected_without_blocking(self):
        self.root.mkdir()
        os.mkfifo(self.root / "pipe.json")
        result = self.dispatch("run_routine", {"name": "pipe"})
        self.assertFalse(result["ok"])
        self.assertIn("regular file", result["error"])

    def test_arguments_are_strict_and_numeric_values_bounded(self):
        for invalid in (float("nan"), float("inf"), -1, 20001, True):
            with self.assertRaises(ValueError):
                routines._arguments("tap", {"x": invalid, "y": 1})
        for arguments in ({"package_name": PACKAGE, "shell": "ignored"}, {"package_name": "../bad"},
                          {"package_name": PACKAGE, "timeout_ms": 60000}):
            with self.assertRaises(ValueError):
                routines._arguments("open_app_ready", arguments)

    def test_successful_execution_checks_guard_and_final_text(self):
        self.save_guarded()
        calls = []
        def call(name, arguments):
            calls.append((name, arguments))
            if name == "open_app_ready":
                return ready()
            if name == "tap":
                return {"ok": True}
            return screen(text="显示" if any(n == "tap" for n, _ in calls) else "设置")
        result = self.dispatch("run_routine", {"name": "open_display"}, call)
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["completed_steps"], 2)
        self.assertEqual([name for name, _ in calls], ["open_app_ready", "read_screen", "tap", "read_screen"])

    def test_foreground_mismatch_stops_before_tap(self):
        self.save_guarded()
        calls = []
        def call(name, arguments):
            calls.append(name)
            return ready() if name == "open_app_ready" else screen("com.example.wrong")
        result = self.dispatch("run_routine", {"name": "open_display"}, call)
        self.assertFalse(result["ok"])
        self.assertIn("Foreground", result["error"])
        self.assertEqual(calls, ["open_app_ready", "read_screen"])

    def test_tool_failure_stops_without_later_actions(self):
        self.save_guarded()
        calls = []
        def call(name, arguments):
            calls.append(name)
            return {"ok": False, "error": "permission denied"}
        result = self.dispatch("run_routine", {"name": "open_display"}, call)
        self.assertFalse(result["ok"])
        self.assertEqual(calls, ["open_app_ready"])
        self.assertEqual(result["completed_steps"], 0)

    def test_final_verification_fails_even_when_gesture_ok(self):
        self.save_guarded()
        calls = []
        def call(name, arguments):
            calls.append(name)
            if name == "open_app_ready":
                return ready()
            if name == "tap":
                return {"ok": True}
            return screen(text="设置")
        result = self.dispatch("run_routine", {"name": "open_display"}, call)
        self.assertFalse(result["ok"])
        self.assertIn("Final package/text", result["error"])
        self.assertEqual(calls.count("tap"), 1)
        self.assertLessEqual(calls.count("read_screen"), 14)

    def test_deadline_stops_before_next_step(self):
        self.save_guarded()
        calls = []
        def call(name, arguments):
            calls.append(name)
            self.clock.sleep(2)
            return ready()
        result = self.dispatch("run_routine", {"name": "open_display", "timeout_ms": 1000}, call)
        self.assertFalse(result["ok"])
        self.assertIn("deadline", result["error"])
        self.assertEqual(calls, ["open_app_ready"])

    def test_ready_candidate_counts_persist_across_sessions_without_screens(self):
        self.open_receipt()
        second_process = self.new_store()
        self.assertEqual(second_process.list()["candidates"], [])
        self.open_receipt(second_process)
        third_process = self.new_store()
        candidate = third_process.list()["candidates"][0]
        self.assertEqual(candidate["success_count"], 2)
        disk = (self.root / routines.CANDIDATE_FILE).read_text()
        self.assertNotIn("private screen content", disk)
        self.assertNotIn("设置", disk)
        self.assertNotIn("texts", disk)
        self.assertNotIn("r_", disk)

    def test_candidate_save_requires_fresh_current_session_final_verification(self):
        self.open_receipt()
        self.open_receipt()
        fresh = self.new_store()
        candidate = fresh.list()["candidates"][0]
        args = {"name": "from_candidate", "steps": [{"tool": "open_app_ready", "arguments": {"package_name": PACKAGE}}],
                "verify": {"package": PACKAGE, "text_contains": ["设置"]}, "candidate_id": candidate["candidate_id"],
                "verification_receipt": "r_fake"}
        self.assertFalse(fresh.dispatch("save_routine", args, self.no_call)["ok"])
        args["verification_receipt"] = fresh.record_action("read_screen", {}, screen())
        self.assertTrue(fresh.dispatch("save_routine", args, self.no_call)["ok"])
        fresh.record_action("home", {}, {"ok": True})
        args["name"] = "stale_candidate"
        self.assertFalse(fresh.dispatch("save_routine", args, self.no_call)["ok"])

    def test_candidate_counts_same_app_with_different_wait_budgets(self):
        self.store.record_action("open_app_ready", {"package_name": PACKAGE, "timeout_ms": 5000}, ready())
        second = self.new_store()
        second.record_action("open_app_ready", {"package_name": PACKAGE, "timeout_ms": 15000}, ready())
        candidates = second.list()["candidates"]
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0]["success_count"], 2)
        self.assertEqual(candidates[0]["arguments"]["timeout_ms"], 15000)

    def test_launch_acceptance_and_blocked_screen_are_not_success_receipts(self):
        for result in ({"ok": True}, {"ok": True, "status": "blocked", "screen": screen()},
                       {"ok": True, "status": "ready", "screen": screen("com.example.other")},
                       {"ok": True, "status": "ready", "screen": dict(screen(), locked=True)}):
            self.assertIsNone(self.store.record_action("open_app_ready", {"package_name": PACKAGE}, result))
        self.assertIsNone(self.store.record_action("open_app", {"package_name": PACKAGE}, {"ok": True}))
        self.assertEqual(self.store.list()["candidates"], [])

    def test_history_is_bounded(self):
        first = self.store.record_action("read_screen", {}, screen())
        for _ in range(300):
            self.store.record_action("read_screen", {}, screen())
        self.assertEqual(len(self.store.history), 256)
        with self.assertRaises(ValueError):
            self.store._receipt(first)


if __name__ == "__main__":
    unittest.main()
