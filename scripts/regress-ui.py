#!/usr/bin/env python3
"""Bounded, non-destructive UI regression for an already installed PhoneAgent.

    python3 scripts/regress-ui.py [--serial SERIAL] [--extended]

Requires Python 3.9+, adb, an unlocked device, a debuggable PhoneAgent install,
and its already-bound accessibility service. Does not build/install anything,
run instrumentation, toggle accessibility, submit a task, contact a model, or
click Delete/Save/Reset. Reports and screenshots go under ignored local/.

The default 240-second limit includes a reserved 40 seconds for cleanup.
Every ADB command has its own timeout (12 seconds by default). Missing history,
missing/short tables and pre-existing new-task drafts are explicit SKIPs.
--extended adds a landscape check and restores the exact rotation settings.
Raw UI XML, history text, drafts and connection values are never written to the
report. Settings screens are never captured. Existing app files are only read;
the script will report a changed hash, never overwrite user data to hide it.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from typing import Callable, Optional
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.ds.phoneagent"
FILES = ("files/task-history.json", "files/last-task.json", "shared_prefs/phoneagent.xml")
HINTS = {"", "想让月薪喵做什么？", "接下来，还想让我做什么？", "例如：打开系统设置"}
SAFE_CONTROLS = {"新任务", "打开最近任务", "连接与设置", "任务输入", "模型",
                 "展开执行过程", "收起执行过程"}
FORBIDDEN_CONTROLS = {"开始执行", "继续对话", "停止任务", "结束任务", "重置为待命", "删除此任务"}
ROTATION_KEYS = ("accelerometer_rotation", "user_rotation")


class Failure(RuntimeError):
    """Messages must contain structural diagnostics, never UI/config contents."""


class Skip(RuntimeError):
    pass


class CommandFailure(Failure):
    def __init__(self, label: str, code: int, output: bytes = b""):
        super().__init__(f"{label}: adb exited {code}")
        self.output = output  # Used only to distinguish an absent private file.


def require(condition: bool, message: str) -> None:
    if not condition:
        raise Failure(message)


@dataclass(frozen=True)
class Rect:
    left: int
    top: int
    right: int
    bottom: int

    @classmethod
    def parse(cls, value: str) -> "Rect":
        numbers = re.findall(r"-?\d+", value)
        require(len(numbers) == 4, "UI node has invalid bounds")
        return cls(*(int(n) for n in numbers))

    @property
    def width(self) -> int:
        return self.right - self.left

    @property
    def height(self) -> int:
        return self.bottom - self.top

    def point(self, x: float, y: float) -> tuple[int, int]:
        require(self.width > 12 and self.height > 12, "Touch target is clipped or too small")
        return (round(self.left + self.width * x), round(self.top + self.height * y))

    def list(self) -> list[int]:
        return [self.left, self.top, self.right, self.bottom]


@dataclass(repr=False)
class Node:
    kind: str
    desc: str = field(repr=False)
    text: str = field(repr=False)
    bounds: Rect
    enabled: bool
    clickable: bool
    focused: bool
    selected: bool
    scrollable: bool
    package: str
    children: list["Node"] = field(default_factory=list, repr=False)

    def walk(self):
        yield self
        for child in self.children:
            yield from child.walk()

    def __repr__(self):
        return f"Node(kind={self.kind!r}, bounds={self.bounds!r})"


@dataclass(repr=False)
class Ui:
    roots: list[Node]
    rotation: str

    @classmethod
    def parse(cls, raw: bytes) -> "Ui":
        start, end = raw.find(b"<?xml"), raw.rfind(b"</hierarchy>")
        require(start >= 0 and end >= start, "uiautomator returned no XML hierarchy")
        try:
            document = ET.fromstring(raw[start:end + len(b"</hierarchy>")])
        except ET.ParseError as error:
            raise Failure("uiautomator returned malformed XML") from error

        def node(element: ET.Element) -> Node:
            a = element.attrib
            return Node(a.get("class", ""), a.get("content-desc", ""), a.get("text", ""),
                        Rect.parse(a.get("bounds", "")), a.get("enabled") == "true",
                        a.get("clickable") == "true", a.get("focused") == "true",
                        a.get("selected") == "true", a.get("scrollable") == "true",
                        a.get("package", ""), [node(c) for c in element if c.tag == "node"])

        return cls([node(e) for e in document if e.tag == "node"], document.get("rotation", "0"))

    @property
    def nodes(self) -> list[Node]:
        return [n for root in self.roots for n in root.walk()]

    def find(self, desc: str, required: bool = True) -> Optional[Node]:
        found = [n for n in self.nodes if n.package == PACKAGE and n.desc == desc]
        if not found and not required:
            return None
        require(bool(found), f"Required UI control missing: {desc}")
        return found[0]

    @property
    def rows(self) -> list[Node]:
        return [n for n in self.nodes if n.package == PACKAGE and n.kind == "android.widget.FrameLayout"
                and n.clickable and n.desc.endswith("，查看任务")]

    @property
    def tables(self) -> list[Node]:
        return [n for n in self.nodes if n.package == PACKAGE and n.kind == "android.widget.HorizontalScrollView"
                and any(child.kind in {"android.widget.TableLayout", "android.widget.TableRow"} for child in n.walk())]

    @property
    def process_controls(self) -> list[Node]:
        return [n for n in self.nodes if n.package == PACKAGE and n.desc in {"展开执行过程", "收起执行过程"}]

    @property
    def scroll(self) -> Node:
        candidates = [n for n in self.nodes if n.package == PACKAGE and n.kind == "android.widget.ScrollView"]
        require(bool(candidates), "App content ScrollView is not visible")
        return max(candidates, key=lambda n: n.bounds.height * n.bounds.width)

    @property
    def settings_visible(self) -> bool:
        toggle = self.find("连接与设置", False)
        return bool(toggle and "∧" in toggle.text)

    @property
    def config_fields_visible(self) -> bool:
        return any(n.kind == "android.widget.EditText" and n.desc in {"API 地址", "模型", "API Key"}
                   for n in self.nodes if n.package == PACKAGE)

    def safe_summary(self) -> dict:
        """No text, descriptions from conversation rows, or field values."""
        return {"rotation": self.rotation, "app_nodes": sum(n.package == PACKAGE for n in self.nodes),
                "visible_history_rows": len(self.rows), "visible_horizontal_tables": len(self.tables)}


def cell_fingerprint(table: Node) -> str:
    # Used only in memory to detect actual horizontal movement; not emitted.
    data = [(n.text, n.bounds.left - table.bounds.left, n.bounds.right - table.bounds.left)
            for n in table.walk() if n.kind == "android.widget.TextView"]
    return hashlib.sha256(json.dumps(data, ensure_ascii=False).encode()).hexdigest()


def has_markdown_table(value: str) -> bool:
    for line in value.splitlines():
        cells = line.strip().strip("|").split("|")
        if len(cells) >= 2 and all(re.fullmatch(r":?-{3,}:?", cell.strip()) for cell in cells):
            return True
    return False


def draft_prefix(current: str, marker: str, original: str) -> Optional[str]:
    """Identify only our complete appended marker, including the literal-hint edge case."""
    if current == marker:
        return ""
    if original in HINTS and original and current == original + marker:
        return original
    return None


class Adb:
    def __init__(self, executable: str, serial: str, deadline: float, command_timeout: float):
        self.executable, self.serial = executable, serial
        self.deadline, self.command_timeout = deadline, command_timeout
        self.commands = 0
        self.command_seconds = 0.0

    def run(self, *args: str, label: str = "device command") -> bytes:
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, "UI regression deadline exhausted")
        timeout = min(self.command_timeout, remaining)
        started = time.monotonic()
        self.commands += 1
        try:
            result = subprocess.run([self.executable, "-s", self.serial, *args],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            raise Failure(f"{label}: adb timeout after {timeout:.1f}s") from error
        finally:
            self.command_seconds += time.monotonic() - started
        if result.returncode:
            raise CommandFailure(label, result.returncode, result.stdout + result.stderr)
        return result.stdout

    def shell(self, *args: str, label: str = "device command") -> str:
        return self.run("shell", *args, label=label).decode("utf-8", "replace")

    def ui(self) -> Ui:
        return Ui.parse(self.run("exec-out", "uiautomator", "dump", "/dev/tty", label="UI snapshot"))

    def read_private(self, path: str) -> Optional[bytes]:
        require(path in FILES, "Private-file read is outside the regression allowlist")
        try:
            return self.run("exec-out", "run-as", PACKAGE, "cat", path, label="private-file verification")
        except CommandFailure as error:
            if b"No such file or directory" in error.output:
                return None
            raise Failure("Private-file verification unavailable; use a debuggable installed build") from error


class Regression:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.started = time.monotonic()
        self.overall_deadline = self.started + args.timeout
        self.adb: Optional[Adb] = None
        self.output = Path(args.output).resolve() if args.output else ROOT / "local" / "ui-regression" / (
            datetime.now().strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6])
        self.output.mkdir(parents=True, exist_ok=False, mode=0o700)
        self.report = {"started_at": datetime.now(timezone.utc).isoformat(), "result": "RUNNING",
                       "mode": "extended" if args.extended else "default", "timeout_seconds": args.timeout,
                       "command_timeout_seconds": args.command_timeout, "cleanup_reserve_seconds": args.cleanup_timeout,
                       "steps": [], "screenshots": [], "model_calls": 0, "task_submissions": 0}
        self.baseline: Optional[dict] = None
        self.conversations: list[dict] = []
        self.original_page: Optional[tuple[str, Optional[str]]] = None
        self.original_settings = False
        self.original_draft: Optional[str] = None
        self.original_ime = False
        self.target: Optional[dict] = None
        self.owned_draft: Optional[dict] = None
        self.pending_process: Optional[dict] = None
        self.rotation: Optional[dict] = None
        self.ui_touched = False
        self.cleanup_errors: list[str] = []

    @contextmanager
    def step(self, name: str):
        started = time.monotonic()
        item = {"name": name, "status": "PASS"}
        try:
            yield item
        except Skip as error:
            item.update(status="SKIP", reason=str(error))
        except BaseException as error:
            item.update(status="FAIL", reason=self.safe_error(error))
            raise
        finally:
            item["seconds"] = round(time.monotonic() - started, 3)
            self.report["steps"].append(item)
            print(f"[{item['status']}] {name}: {item['seconds']:.2f}s" +
                  (f" — {item['reason']}" if item.get("reason") else ""), flush=True)
            self.save_report()

    @staticmethod
    def safe_error(error: BaseException) -> str:
        if isinstance(error, (Failure, Skip)):
            return str(error)
        if isinstance(error, KeyboardInterrupt):
            return "Interrupted by user; bounded cleanup requested"
        return f"Unexpected {type(error).__name__}; no raw device output recorded"

    def save_report(self):
        self.report["elapsed_seconds"] = round(time.monotonic() - self.started, 3)
        (self.output / "result.json").write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + "\n")

    def wait_ui(self, predicate: Callable[[Ui], bool], message: str, seconds: float = 9) -> Ui:
        end = min(self.adb.deadline, time.monotonic() + seconds)
        while time.monotonic() < end:
            ui = self.adb.ui()
            self.assert_idle_ui(ui)
            if predicate(ui):
                return ui
        raise Failure(message)

    @staticmethod
    def assert_idle_ui(ui: Ui):
        require(all(ui.find(action, False) is None for action in ("停止任务", "结束任务")),
                "An active task was observed; no further UI test actions allowed")

    def tap(self, node: Node):
        require(node.package == PACKAGE and node.enabled, "Tap target is outside PhoneAgent or disabled")
        require(node.desc not in FORBIDDEN_CONTROLS and node.text not in {
            "删除", "保存连接配置", "开始执行", "继续对话", "停止", "结束", "结束任务", "重置"}, "Refusing a state-changing task/config action")
        row = node.kind == "android.widget.FrameLayout" and node.clickable and node.desc.endswith("，查看任务")
        model_toggle = node.kind == "android.widget.Button" and node.text == "连接模型  ›"
        require(node.desc in SAFE_CONTROLS or row or model_toggle, "Tap target is not an allowed navigation/input control")
        x, y = node.bounds.point(.5, .5)
        self.ui_touched = True
        self.adb.shell("input", "tap", str(x), str(y), label="safe UI tap")

    def swipe(self, bounds: Rect, direction: str):
        points = {"left": (.84, .5, .16, .5), "right": (.16, .5, .84, .5),
                  "up": (.5, .82, .5, .22), "down": (.5, .22, .5, .82)}
        x1, y1, x2, y2 = points[direction]
        a, b = bounds.point(x1, y1), bounds.point(x2, y2)
        self.adb.shell("input", "swipe", *map(str, (*a, *b)), "450", label="bounded UI swipe")

    def screenshot(self, name: str, ui: Optional[Ui] = None):
        ui = ui or self.adb.ui()
        require(not ui.settings_visible and not ui.config_fields_visible,
                "Refusing screenshot of connection settings")
        path = self.output / (name + ".png")
        path.write_bytes(self.adb.run("exec-out", "screencap", "-p", label="UI screenshot"))
        self.report["screenshots"].append(path.name)

    def hashes(self) -> tuple[dict, dict]:
        contents = {name: self.adb.read_private(name) for name in FILES}
        digests = {name: hashlib.sha256(data).hexdigest() if data is not None else None
                   for name, data in contents.items()}
        return digests, contents

    def accessibility(self) -> dict:
        raw = self.adb.shell("dumpsys", "accessibility", label="accessibility preflight")
        bound = next((line.strip() for line in raw.splitlines() if "Bound services:" in line), "")
        binding = next((line.strip() for line in raw.splitlines() if "Binding services:" in line), "")
        crashed = next((line.strip() for line in raw.splitlines() if "Crashed services:" in line), "")
        ours = lambda value: "PhoneAgent" in value or PACKAGE in value
        state = {"phoneagent_bound": ours(bound), "phoneagent_binding": ours(binding),
                 "phoneagent_crashed": ours(crashed)}
        require(state["phoneagent_bound"] and not state["phoneagent_binding"] and not state["phoneagent_crashed"],
                "PhoneAgent accessibility is not stably bound; fix it in system settings, then rerun (no automatic toggle)")
        return state

    def ime_shown(self) -> bool:
        raw = self.adb.shell("dumpsys", "input_method", label="IME visibility")
        return bool(re.search(r"\bmInputShown=true\b", raw))

    def preflight(self, item: dict):
        executable = shutil.which(self.args.adb)
        require(executable is not None, "adb is not available on PATH")
        try:
            result = subprocess.run([executable, "devices"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=min(self.args.command_timeout, self.args.timeout))
        except subprocess.TimeoutExpired as error:
            raise Failure("ADB device enumeration timed out") from error
        require(result.returncode == 0, "ADB device enumeration failed")
        devices = [line.split() for line in result.stdout.decode("utf-8", "replace").splitlines()[1:] if line.strip()]
        ready = [parts[0] for parts in devices if len(parts) > 1 and parts[1] == "device"]
        if self.args.serial:
            require(self.args.serial in ready, "Selected device is unavailable/unauthorized/offline")
            serial = self.args.serial
        else:
            require(len(ready) == 1, "Connect exactly one authorized device or pass --serial")
            serial = ready[0]
        self.adb = Adb(executable, serial, self.overall_deadline - self.args.cleanup_timeout, self.args.command_timeout)
        self.report["serial"] = serial
        policy = self.adb.shell("dumpsys", "window", "policy", label="lock-screen preflight")
        require(not re.search(r"(?:mKeyguardShowing|isStatusBarKeyguard|mShowingLockscreen)=true", policy),
                "Device is locked; unlock it before running UI regression")
        power = self.adb.shell("dumpsys", "power", label="display preflight")
        require("mWakefulness=Asleep" not in power and "mWakefulness=Dozing" not in power,
                "Device display is asleep; wake and unlock it before running")
        item["accessibility"] = self.accessibility()
        self.baseline, contents = self.hashes()
        self.report["baseline_hashes"] = self.baseline
        try:
            history = json.loads(contents[FILES[0]]) if contents[FILES[0]] else {}
            last = json.loads(contents[FILES[1]]) if contents[FILES[1]] else {}
        except (ValueError, TypeError) as error:
            raise Failure("Existing history/last-task is not valid JSON; no UI actions performed") from error
        self.conversations = history.get("conversations", [])
        require(isinstance(self.conversations, list), "History has an invalid conversation list")
        active_states = {"running", "pausing", "paused", "waiting_user", "resuming"}
        require(last.get("state") not in active_states and not any(
            t.get("state") in active_states for c in self.conversations for t in c.get("turns", [])),
            "An active or pending task exists; finish it before UI regression")
        item["conversation_count"] = len(self.conversations)
        package = self.adb.shell("dumpsys", "package", PACKAGE, label="installed version")
        version = re.search(r"versionName=([^\s]+)", package)
        code = re.search(r"versionCode=(\d+)", package)
        require(bool(version and code), "PhoneAgent package/version is unavailable")
        item["version"] = version.group(1)
        item["version_code"] = int(code.group(1))
        ui = self.adb.ui()
        if not ui.find("打开最近任务", False):
            self.adb.shell("am", "start", "-f", "0x24000000", "-n", PACKAGE + "/.MainActivity", label="open installed app")
            ui = self.wait_ui(lambda value: value.find("打开最近任务", False) is not None, "PhoneAgent did not become visible")
        self.assert_idle_ui(ui)
        self.original_ime = self.ime_shown()
        self.original_settings = ui.settings_visible
        # A pre-existing settings editor is intentionally left alone; avoid losing its unsaved edits.
        require(not ui.settings_visible and not ui.config_fields_visible,
                "Close connection settings before running; unsaved configuration is not touched")
        self.original_draft = ui.find("任务输入").text
        history_button, new_button = ui.find("打开最近任务"), ui.find("新任务")
        if history_button.selected:
            self.original_page = ("history", None)
        elif new_button.selected:
            self.original_page = ("new", None)
        else:
            # Identify the current conversation by a unique visible user prompt, never log it.
            visible = {n.text for n in ui.nodes if n.kind == "android.widget.TextView" and n.package == PACKAGE}
            matches = [c for c in self.conversations if any(t.get("prompt") in visible for t in c.get("turns", []) if t.get("prompt"))]
            require(len(matches) == 1,
                    "Current conversation cannot be identified safely; open it at the top or select New task/History, then rerun")
            require(sum(c.get("title") == matches[0].get("title") for c in self.conversations) == 1,
                    "Original conversation has a duplicate title; select New task/History before regression so restoration is unambiguous")
            self.original_page = ("conversation", matches[0]["id"])
        item["original_page"] = self.original_page[0]

    def open_history(self) -> Ui:
        ui = self.adb.ui()
        self.assert_idle_ui(ui)
        self.tap(ui.find("打开最近任务"))
        return self.wait_ui(lambda value: value.find("打开最近任务").selected and not value.settings_visible,
                            "History entry did not select the recent-task list")

    def new_task(self) -> Ui:
        ui = self.adb.ui()
        self.assert_idle_ui(ui)
        self.tap(ui.find("新任务"))
        return self.wait_ui(lambda value: value.find("新任务").selected, "New-task navigation did not finish")

    def open_conversation(self, conversation: dict) -> Ui:
        title = conversation.get("title", "")
        require(sum(c.get("title") == title for c in self.conversations) == 1,
                "Duplicate conversation titles prevent unambiguous navigation; no row tapped")
        ui = self.open_history()
        for _ in range(7):
            candidates = [row for row in ui.rows if title and row.desc.startswith(title + "，")]
            if candidates:
                require(len(candidates) == 1, "Duplicate visible conversation titles prevent safe selection")
                self.tap(candidates[0])
                return self.wait_ui(lambda value: not value.find("打开最近任务").selected and not value.find("新任务").selected,
                                    "Existing conversation did not open")
            self.swipe(ui.scroll.bounds, "up")
            ui = self.adb.ui()
        raise Failure("Existing conversation could not be located within the bounded history search")

    def navigation(self, item: dict):
        ui = self.adb.ui()
        new, history, settings, scroll = ui.find("新任务"), ui.find("打开最近任务"), ui.find("连接与设置"), ui.scroll
        require(new.bounds.left < history.bounds.left and abs(new.bounds.top - history.bounds.top) < 16,
                "New-task/history controls are not in the expected top row")
        require(max(new.bounds.bottom, history.bounds.bottom) <= settings.bounds.top + 12
                and settings.bounds.bottom <= scroll.bounds.top + 12,
                "Connection/settings entry is not between navigation and scrolling conversation content")
        item["top_controls"] = {"new": new.bounds.list(), "history": history.bounds.list(), "settings": settings.bounds.list()}
        ui = self.open_history()
        if self.conversations:
            require(bool(ui.rows), "Saved conversations exist but the recent list contains no visible rows")
        else:
            require(not ui.rows, "Empty history unexpectedly displays conversation rows")
        self.screenshot("history", ui)

    def reveal(self, item: dict):
        if not self.conversations:
            raise Skip("No existing history; swipe-to-reveal is not exercised")
        ui = self.open_history()
        require(bool(ui.rows), "No history row available for a non-destructive swipe")
        row, count = ui.rows[0], len(ui.rows)
        self.target = self.conversations[0]
        header_before = ui.find("打开最近任务").bounds
        self.swipe(row.bounds, "left")
        ui = self.wait_ui(lambda value: value.find("删除此任务", False) is not None, "Left swipe did not reveal Delete")
        delete = ui.find("删除此任务")
        require(delete.enabled and delete.bounds.left > row.bounds.left + row.bounds.width / 2,
                "Revealed Delete control is not enabled on the right")
        require(ui.find("打开最近任务").selected and len(ui.rows) == count, "Swipe navigated away or changed the list")
        require(ui.find("打开最近任务").bounds == header_before, "Top navigation moved during the row swipe")
        self.screenshot("swipe", ui)
        self.swipe(row.bounds, "right")
        ui = self.wait_ui(lambda value: value.find("删除此任务", False) is None, "Right swipe did not close Delete")
        require(ui.find("打开最近任务").selected, "Closing a revealed row opened its detail")
        item["delete_clicked"] = False

    def process(self, item: dict):
        if not self.conversations:
            raise Skip("No existing conversation; detail/process checks are not exercised")
        self.target = self.target or self.conversations[0]
        ui = self.open_conversation(self.target)
        self.screenshot("detail", ui)
        controls = ui.process_controls
        if len(controls) != 1:
            raise Skip("Process disclosure requires exactly one visible turn control to preserve its original expansion state")
        original_open = controls[0].desc == "收起执行过程"
        self.pending_process = {"conversation": self.target, "open": original_open}
        self.tap(controls[0])
        desired = "展开执行过程" if original_open else "收起执行过程"
        ui = self.wait_ui(lambda value: bool(value.process_controls) and value.process_controls[0].desc == desired,
                          "The selected process disclosure did not toggle")
        self.tap(ui.process_controls[0])
        self.wait_ui(lambda value: bool(value.process_controls) and value.process_controls[0].desc == (
            "收起执行过程" if original_open else "展开执行过程"),
                     "Process disclosure did not restore its original state")
        self.pending_process = None
        item["original_expanded_state_restored"] = True

    def restore_process(self):
        if not self.pending_process:
            return
        pending = self.pending_process
        ui = self.open_conversation(pending["conversation"])
        require(bool(ui.process_controls), "Cannot find the tested process control to restore its expansion state")
        expected = "收起执行过程" if pending["open"] else "展开执行过程"
        if ui.process_controls[0].desc != expected:
            self.tap(ui.process_controls[0])
            self.wait_ui(lambda value: bool(value.process_controls) and value.process_controls[0].desc == expected,
                         "The tested process disclosure did not restore during cleanup")
        self.pending_process = None

    def table(self, item: dict):
        if not self.target:
            raise Skip("No existing conversation available for Markdown table inspection")
        if not any(has_markdown_table(t.get("result", "")) for t in self.target.get("turns", [])):
            raise Skip("Selected existing conversation has no Markdown table")
        ui = self.open_conversation(self.target)
        for _ in range(5):
            candidates = [n for n in ui.tables if n.bounds.height >= 100]
            if candidates:
                table = candidates[0]
                if not table.scrollable:
                    raise Skip("Visible Markdown table fits its width; horizontal overflow is not exercised")
                before, bounds, header = cell_fingerprint(table), table.bounds, ui.find("打开最近任务").bounds
                self.swipe(table.bounds, "left")
                ui = self.wait_ui(lambda value: bool(value.tables) and cell_fingerprint(value.tables[0]) != before,
                                  "Horizontal table swipe did not change visible cells")
                after = ui.tables[0]
                require(abs(after.bounds.top - bounds.top) < 12 and abs(after.bounds.bottom - bounds.bottom) < 12,
                        "Horizontal table swipe moved the outer conversation vertically")
                require(ui.find("打开最近任务").bounds == header, "Table interaction moved fixed top navigation")
                self.screenshot("table", ui)
                item["horizontal_cell_movement_observed"] = True
                return
            self.swipe(ui.scroll.bounds, "up")
            ui = self.adb.ui()
        raise Skip("A saved table exists but was not reachable in the bounded five-screen inspection")

    def draft(self, item: dict):
        if not self.target:
            raise Skip("No old conversation for draft isolation; no draft is written")
        ui = self.new_task()
        original = ui.find("任务输入").text
        if original not in HINTS:
            raise Skip("A pre-existing new-task draft is present; it is left untouched")
        self.tap(ui.find("任务输入"))
        ui = self.wait_ui(lambda value: bool(value.find("任务输入", False) and value.find("任务输入").focused),
                          "Task input did not acquire focus")
        require(not any(n.focused and n.kind == "android.widget.EditText" and n.desc != "任务输入" for n in ui.nodes),
                "A configuration field is focused; refusing draft input")
        marker = "731" + str(int(uuid.uuid4().hex[:8], 16)).zfill(10)
        self.owned_draft = {"marker": marker, "original": original}
        # Digits avoid Chinese IME composition. Afterwards a fresh touch restores the soft keyboard.
        self.adb.shell("input", "keyevent", "KEYCODE_MOVE_END", label="move within owned task draft")
        self.adb.shell("input", "text", marker, label="temporary numeric task draft")
        ui = self.adb.ui()
        expected = ui.find("任务输入").text
        prefix = draft_prefix(expected, marker, original)
        require(prefix is not None, "Temporary draft did not arrive intact; cleanup will check ownership before clearing")
        self.owned_draft["prefix"] = prefix
        ui = self.open_conversation(self.target)
        require(marker not in ui.find("任务输入").text, "New-task draft leaked into an old conversation")
        ui = self.new_task()
        require(ui.find("任务输入").text == expected, "New-task draft did not survive navigation")
        self.clean_draft()
        item["temporary_draft_restored"] = True

    def clean_draft(self):
        if not self.owned_draft:
            return
        owned = self.owned_draft
        ui = self.new_task()
        node = ui.find("任务输入")
        current = node.text
        if current in HINTS and current == owned["original"]:
            self.owned_draft = None
            return
        prefix = draft_prefix(current, owned["marker"], owned["original"])
        require(prefix is not None, "Temporary draft no longer matches the owned marker; left intact for user review")
        self.tap(node)
        ui = self.wait_ui(lambda value: value.find("任务输入").focused, "Cannot refocus owned task draft for cleanup")
        require(ui.find("任务输入").text == current, "Task draft changed before cleanup; no text removed")
        self.adb.shell("input", "keyevent", "KEYCODE_MOVE_END", *("KEYCODE_DEL" for _ in owned["marker"]),
                       label="remove only the owned numeric draft suffix")
        ui = self.adb.ui()
        remaining = ui.find("任务输入").text
        require(remaining == prefix or (not prefix and remaining in HINTS), "Owned draft cleanup did not restore its original content")
        self.owned_draft = None
        # Navigating away stashes the cleared value instead of leaving the marker in the draft map.
        self.open_history()

    def task_ime(self, item: dict):
        ui = self.new_task()
        baseline_bottom = ui.find("开始执行").bounds.bottom
        self.tap(ui.find("任务输入"))
        ui = self.wait_ui(lambda value: value.find("任务输入").focused, "Task composer did not focus")
        require(self.ime_shown(), "IME did not become visible for the task composer")
        prompt, send, reset = ui.find("任务输入"), ui.find("开始执行"), ui.find("重置为待命")
        require(prompt.bounds.bottom <= send.bounds.top and reset.bounds.height >= 24,
                "Task input/actions are clipped while the keyboard is visible")
        self.screenshot("keyboard", ui)
        item["prompt_bounds"], item["send_bounds"], item["reset_bounds"] = prompt.bounds.list(), send.bounds.list(), reset.bounds.list()
        if send.bounds.bottom >= baseline_bottom - 40:
            raise Skip("IME is visible but non-resizing/floating; occlusion cannot be proven from app accessibility bounds")
        item["composer_moved_above_soft_keyboard"] = True

    def settings_ime(self, item: dict):
        ui = self.adb.ui()
        self.tap(ui.find("连接与设置"))
        ui = self.wait_ui(lambda value: value.settings_visible, "Connection/settings panel did not open")
        toggled = False
        for _ in range(4):
            model = ui.find("模型", False)
            if model and model.bounds.height >= 50:
                self.tap(model)
                break
            toggle = next((n for n in ui.nodes if n.package == PACKAGE and n.kind == "android.widget.Button"
                           and n.text == "连接模型  ›"), None)
            if toggle and not ui.config_fields_visible and not toggled:
                self.tap(toggle)
                toggled = True
                ui = self.adb.ui()
                continue
            self.swipe(ui.scroll.bounds, "up")
            ui = self.adb.ui()
        else:
            raise Failure("Model field could not be reached in the bounded settings inspection")
        ui = self.wait_ui(lambda value: value.find("模型", False) is not None and value.find("模型").focused
                          and value.find("任务输入", False) is None, "Settings IME did not hide the task composer")
        require(self.ime_shown(), "IME did not become visible for the settings field")
        send = ui.find("开始执行", False) or ui.find("继续对话", False)
        reset = ui.find("重置为待命")
        require(send is not None and not send.enabled and reset.bounds.height >= 24,
                "Settings editing did not disable Send while keeping Reset visible")
        item.update(task_prompt_hidden=True, send_disabled=True, reset_visible=True, configuration_edited=False)
        self.open_history()  # Hides IME and collapses settings without saving.

    def extended(self, item: dict):
        if not self.args.extended:
            raise Skip("Landscape is opt-in; pass --extended")
        ui = self.new_task()
        saved = {key: self.adb.shell("settings", "get", "system", key, label="save rotation state").strip()
                 for key in ROTATION_KEYS}
        require(saved["accelerometer_rotation"] in {"null", "0", "1"}
                and saved["user_rotation"] in {"null", "0", "1", "2", "3"},
                "Unexpected rotation setting value; extended check makes no changes")
        self.rotation = saved
        self.adb.shell("settings", "put", "system", "accelerometer_rotation", "0", label="temporary landscape rotation")
        self.adb.shell("settings", "put", "system", "user_rotation", "1", label="temporary landscape rotation")
        ui = self.wait_ui(lambda value: value.rotation == "1", "Device did not enter requested landscape")
        self.tap(ui.find("任务输入"))
        ui = self.wait_ui(lambda value: value.find("任务输入").focused, "Landscape task composer did not focus")
        require(self.ime_shown(), "Landscape keyboard did not appear")
        for desc in ("任务输入", "开始执行", "重置为待命"):
            require(ui.find(desc).bounds.height >= 24, "Landscape task composer/action was clipped")
        brand = any(n.package == PACKAGE and n.kind == "android.widget.TextView" and n.text == "月薪喵" for n in ui.nodes)
        require(not brand, "Brand header was not collapsed in the short landscape window")
        self.screenshot("landscape-keyboard", ui)
        item["brand_hidden_in_short_window"] = True
        self.restore_rotation()

    def restore_rotation(self):
        if self.rotation is None:
            return
        for key, value in self.rotation.items():
            if value == "null":
                self.adb.shell("settings", "delete", "system", key, label="restore exact rotation state")
            else:
                self.adb.shell("settings", "put", "system", key, value, label="restore exact rotation state")
        restored = {key: self.adb.shell("settings", "get", "system", key, label="verify rotation restoration").strip()
                    for key in self.rotation}
        require(restored == self.rotation, "Original rotation settings did not restore exactly")
        self.rotation = None

    def restore_page(self):
        if not self.original_page:
            return
        kind, identity = self.original_page
        if kind == "history":
            ui = self.open_history()
        elif kind == "new":
            ui = self.new_task()
        else:
            conversation = next((c for c in self.conversations if c.get("id") == identity), None)
            require(conversation is not None, "Original conversation no longer exists in the initial snapshot")
            ui = self.open_conversation(conversation)
        actual = ui.find("任务输入").text
        require(actual == self.original_draft or (actual in HINTS and self.original_draft in HINTS),
                "Original page's draft did not restore; no replacement text was written")
        if self.original_ime:
            self.tap(ui.find("任务输入"))
            self.wait_ui(lambda value: value.find("任务输入").focused, "Original input focus could not be restored")

    def cleanup(self, item: dict):
        if not self.adb:
            return
        self.adb.deadline = min(self.overall_deadline, time.monotonic() + self.args.cleanup_timeout)
        # Each cleanup remains independent: a draft issue must not prevent restoring rotation or checking hashes.
        tasks = [("rotation", self.restore_rotation), ("owned draft", self.clean_draft),
                 ("process expansion", self.restore_process)]
        if self.ui_touched:
            tasks.append(("original app page", self.restore_page))
        for label, task in tasks:
            try:
                task()
            except BaseException as error:
                self.cleanup_errors.append(f"{label}: {self.safe_error(error)}")
        if self.baseline is not None:
            try:
                after, _ = self.hashes()
                item["final_hashes"] = after
                changed = [path for path in self.baseline if after[path] != self.baseline[path]]
                item["changed_paths"] = changed
                require(not changed, "History/last-task/preferences changed; no automatic data overwrite attempted")
            except BaseException as error:
                self.cleanup_errors.append("data verification: " + self.safe_error(error))
        try:
            item["accessibility"] = self.accessibility()
        except BaseException as error:
            self.cleanup_errors.append("final service state: " + self.safe_error(error))
        item["restoration_errors"] = list(self.cleanup_errors)
        require(not self.cleanup_errors, "Cleanup or final verification requires attention; see restoration_errors")

    def run(self) -> int:
        failed = False
        try:
            for name, method in (("preflight", self.preflight), ("top navigation and history", self.navigation),
                                 ("swipe reveal and close", self.reveal), ("existing conversation and process", self.process),
                                 ("Markdown horizontal table", self.table), ("draft isolation and cleanup", self.draft),
                                 ("task soft keyboard", self.task_ime), ("settings soft keyboard", self.settings_ime),
                                 ("extended landscape", self.extended)):
                with self.step(name) as item:
                    method(item)
        except BaseException as error:
            failed = True
            self.report["failure"] = self.safe_error(error)
        finally:
            try:
                with self.step("restore and verify") as item:
                    self.cleanup(item)
            except BaseException as error:
                failed = True
                self.report["cleanup_failure"] = self.safe_error(error)
            if self.adb:
                self.report["adb_commands"] = self.adb.commands
                self.report["adb_command_seconds"] = round(self.adb.command_seconds, 3)
            self.report["skipped_checks"] = [s["name"] for s in self.report["steps"] if s["status"] == "SKIP"]
            coverage_skips = [name for name in self.report["skipped_checks"] if name != "extended landscape"]
            self.report["result"] = "FAIL" if failed else "PASS_WITH_SKIPS" if coverage_skips else "PASS"
            self.save_report()
            lines = [f"PhoneAgent UI regression: {self.report['result']}",
                     f"Elapsed: {self.report['elapsed_seconds']:.2f}s; timeout: {self.args.timeout}s",
                     "No task submission, model call, record deletion, config save, instrumentation or accessibility toggle.", ""]
            lines += [f"{s['status']:4} {s['seconds']:7.2f}s  {s['name']}" + (": " + s["reason"] if s.get("reason") else "")
                      for s in self.report["steps"]]
            lines += ["", "Restores the original app page at its top; does not promise the prior scroll offset.",
                      "See result.json for explicit skips, timing, hashes and restoration errors."]
            (self.output / "report.txt").write_text("\n".join(lines) + "\n")
            print(f"{self.report['result']} — {self.report['elapsed_seconds']:.2f}s — {self.output}", flush=True)
        return 1 if failed else 0


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", help="Required if more than one authorized device is attached")
    parser.add_argument("--adb", default="adb", help="adb executable (default: PATH)")
    parser.add_argument("--timeout", type=float, default=240, help="Overall deadline including cleanup, seconds (default: 240)")
    parser.add_argument("--command-timeout", type=float, default=12, help="Per-ADB-command timeout, seconds (default: 12)")
    parser.add_argument("--cleanup-timeout", type=float, default=40, help="Time reserved within overall deadline for cleanup (default: 40)")
    parser.add_argument("--extended", action="store_true", help="Also check landscape and restore exact rotation settings")
    parser.add_argument("--output", help="New output directory (default: ignored local/ui-regression/<timestamp>)")
    args = parser.parse_args(argv)
    if args.timeout < 60 or args.cleanup_timeout < 15 or args.cleanup_timeout >= args.timeout - 15 or args.command_timeout <= 0:
        parser.error("Require timeout >= 60, 15 <= cleanup-timeout < timeout-15, and command-timeout > 0")
    return args


def main(argv=None) -> int:
    try:
        return Regression(parse_args(argv)).run()
    except (OSError, Failure) as error:
        print(f"FAIL: {type(error).__name__} while preparing local regression output", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
