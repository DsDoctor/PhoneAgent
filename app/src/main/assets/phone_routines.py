"""Verified JSON phone routines. No shell, eval, imports from MCP, or arbitrary paths.

call_tool(name, arguments) must execute a real phone tool and have its own bounded
request timeout. The executor checks its deadline before/after every call and
never starts another action after the deadline; an in-flight phone call can take
up to its own timeout to return.
"""
import json
import math
import os
import re
import secrets
import stat
import time
from collections import deque
from contextlib import contextmanager
from pathlib import Path

NAME_RE = re.compile(r"[a-z][a-z0-9_-]{0,47}\Z")
PACKAGE_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+\Z")
SAFE_TOOLS = {"home", "back", "read_screen", "open_app_ready", "tap", "swipe"}
GUARDED_TOOLS = {"back", "tap", "swipe"}
MUTATING_TOOLS = {"home", "back", "open_app", "open_settings", "open_app_ready", "tap", "swipe", "type_text", "run_routine"}
MAX_STEPS = 12
MAX_FILE_BYTES = 32768
MAX_ROUTINES = 32
EVIDENCE_SECONDS = 600
CANDIDATE_SECONDS = 30 * 24 * 3600
CANDIDATE_FILE = ".open_successes.json"

STEP_SCHEMA = {"type": "object", "properties": {
    "tool": {"type": "string", "enum": sorted(SAFE_TOOLS)},
    "arguments": {"type": "object"},
    "expect_package": {"type": "string"},
    "settle_ms": {"type": "integer", "minimum": 0, "maximum": 1500},
}, "required": ["tool", "arguments"], "additionalProperties": False}
VERIFY_SCHEMA = {"type": "object", "properties": {
    "package": {"type": "string"},
    "text_contains": {"type": "array", "items": {"type": "string", "minLength": 1, "maxLength": 80}, "minItems": 1, "maxItems": 5},
}, "required": ["package", "text_contains"], "additionalProperties": False}
PROOF_SCHEMA = {"type": "object", "properties": {
    "step_receipts": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": MAX_STEPS},
    "verification_receipt": {"type": "string"},
}, "required": ["step_receipts", "verification_receipt"], "additionalProperties": False}
ROUTINE_TOOLS = [
    {"name": "list_routines", "description": "List saved verified phone routines and recent open_app_ready candidates that succeeded at least twice across tasks. Candidate counts contain no screen text. Use saved routines before repeating the same basic operation.",
     "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "save_routine", "description": "Save only a verified JSON phone routine. Supply two non-overlapping successful runs using actual routine_receipt IDs, OR one repeated open_app_ready candidate_id plus a fresh verification_receipt. Each proof must match every step and final package/text assertions; guarded actions need a preceding observed foreground package. Never save typing, secrets, shell commands or untested steps.",
     "inputSchema": {"type": "object", "properties": {
         "name": {"type": "string", "pattern": "^[a-z][a-z0-9_-]{0,47}$"},
         "description": {"type": "string", "maxLength": 160},
         "steps": {"type": "array", "items": STEP_SCHEMA, "minItems": 1, "maxItems": MAX_STEPS},
         "verify": VERIFY_SCHEMA,
         "proofs": {"type": "array", "items": PROOF_SCHEMA, "minItems": 2, "maxItems": 2},
         "candidate_id": {"type": "string"},
         "verification_receipt": {"type": "string"},
     }, "required": ["name", "steps", "verify"], "additionalProperties": False}},
    {"name": "run_routine", "description": "Run a previously verified basic-phone JSON routine. Before back/tap/swipe, verify the foreground package; stop on the first failure. Verify final package and readable text. Does not run shell/code or recursively invoke routines.",
     "inputSchema": {"type": "object", "properties": {
         "name": {"type": "string"},
         "timeout_ms": {"type": "integer", "minimum": 1000, "maximum": 90000, "default": 60000},
     }, "required": ["name"], "additionalProperties": False}},
]


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _object(value, allowed, required=()):
    _require(isinstance(value, dict), "Expected an object")
    _require(set(value) <= set(allowed) and set(required) <= set(value), "Unexpected or missing fields")
    return value


def _integer(value, low, high):
    _require(type(value) is int and low <= value <= high, "Integer outside allowed bounds")
    return value


def _package(value):
    _require(isinstance(value, str) and len(value) <= 200 and PACKAGE_RE.fullmatch(value), "Invalid Android package")
    return value


def _name(value):
    _require(isinstance(value, str) and NAME_RE.fullmatch(value), "Invalid routine name")
    return value


def _arguments(tool, value):
    _require(tool in SAFE_TOOLS, "This tool cannot be saved in a routine")
    value = dict(_object(value, {"open_app_ready": {"package_name", "timeout_ms"}, "tap": {"x", "y"},
                                "swipe": {"x1", "y1", "x2", "y2", "duration_ms"}}.get(tool, set())))
    if tool == "open_app_ready":
        _require("package_name" in value, "Missing package_name")
        return {"package_name": _package(value["package_name"]), "timeout_ms": _integer(value.get("timeout_ms", 15000), 1000, 30000)}
    if tool in {"tap", "swipe"}:
        coordinates = ("x", "y") if tool == "tap" else ("x1", "y1", "x2", "y2")
        for key in coordinates:
            number = value.get(key)
            _require(type(number) in (int, float) and math.isfinite(number) and 0 <= number <= 20000, "Invalid gesture coordinate")
        if tool == "swipe":
            value["duration_ms"] = _integer(value.get("duration_ms", 400), 100, 1500)
    return value


def _steps(value):
    _require(isinstance(value, list) and 1 <= len(value) <= MAX_STEPS, "Routine needs 1-12 steps")
    result = []
    for item in value:
        _object(item, {"tool", "arguments", "expect_package", "settle_ms"}, {"tool", "arguments"})
        tool = item["tool"]
        arguments = _arguments(tool, item["arguments"])
        step = {"tool": tool, "arguments": arguments,
                "settle_ms": _integer(item.get("settle_ms", 350 if tool in MUTATING_TOOLS and tool != "open_app_ready" else 0), 0, 1500)}
        if tool in GUARDED_TOOLS:
            _require("expect_package" in item, "back/tap/swipe require expect_package")
        if "expect_package" in item:
            step["expect_package"] = _package(item["expect_package"])
        result.append(step)
    return result


def _verify(value):
    _object(value, {"package", "text_contains"}, {"package", "text_contains"})
    package = _package(value["package"])
    texts = value["text_contains"]
    _require(isinstance(texts, list) and 1 <= len(texts) <= 5, "Verification needs 1-5 readable text assertions")
    _require(all(isinstance(t, str) and t.strip() and len(t) <= 80 for t in texts), "Invalid verification text")
    return {"package": package, "text_contains": list(texts)}


def _screen(value):
    if not isinstance(value, dict) or value.get("ok") is not True or value.get("locked") is True:
        return None
    package = value.get("package")
    if not isinstance(package, str) or not PACKAGE_RE.fullmatch(package):
        return None
    texts = []
    nodes = value.get("nodes", [])
    if isinstance(nodes, list):
        for node in nodes[:180]:
            if not isinstance(node, dict):
                continue
            for key in ("text", "description"):
                text = node.get(key)
                if isinstance(text, str) and text and text != "[密码输入框]":
                    texts.append(text[:400])
    return {"package": package, "texts": texts}


def _matches(screen, verify):
    return bool(screen and screen["package"] == verify["package"] and
                all(any(expected in text for text in screen["texts"]) for expected in verify["text_contains"]))


class RoutineStore:
    def __init__(self, root=None, clock=None, wall_clock=None, sleeper=None):
        self.root = Path(root) if root is not None else Path.home() / ".phoneagent" / "routines"
        self.clock = clock or time.monotonic
        self.wall_clock = wall_clock or time.time
        self.sleep = sleeper or time.sleep
        self.history = deque(maxlen=256)
        self.sequence = 0
        self.last_screen = None
        self.last_screen_at = 0

    @contextmanager
    def _directory(self):
        _require(not self.root.is_symlink() and not self.root.parent.is_symlink(), "Routine directory cannot be a symlink")
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            yield descriptor
        finally:
            os.close(descriptor)

    def _load(self, directory, filename):
        descriptor = os.open(filename, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(descriptor, "rb") as source:
            _require(stat.S_ISREG(os.fstat(source.fileno()).st_mode), "Routine entry must be a regular file")
            content = source.read(MAX_FILE_BYTES + 1)
        _require(len(content) <= MAX_FILE_BYTES, "Routine file is too large")
        return json.loads(content)

    def _write(self, directory, filename, value):
        content = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        _require(len(content) <= MAX_FILE_BYTES, "Routine file is too large")
        temporary = ".write-" + secrets.token_hex(12)
        try:
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
            with os.fdopen(descriptor, "wb") as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, filename, src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
        finally:
            try:
                os.unlink(temporary, dir_fd=directory)
            except FileNotFoundError:
                pass

    def _candidates(self, directory):
        try:
            data = self._load(directory, CANDIDATE_FILE)
        except FileNotFoundError:
            return []
        _require(isinstance(data, dict) and data.get("version") == 1 and isinstance(data.get("items"), list), "Invalid candidate store")
        now = self.wall_clock()
        valid = []
        for item in data["items"][:32]:
            if not isinstance(item, dict):
                continue
            try:
                normalized = _arguments("open_app_ready", item.get("arguments"))
                count = _integer(item.get("success_count"), 1, 10000)
                stamp = item.get("last_verified_at")
                _require(type(stamp) in (int, float) and math.isfinite(stamp) and 0 <= now - stamp <= CANDIDATE_SECONDS, "Expired candidate")
                identity = item.get("candidate_id")
                _require(isinstance(identity, str) and re.fullmatch(r"c_[0-9a-f]{32}", identity), "Invalid candidate id")
                valid.append({"candidate_id": identity, "tool": "open_app_ready", "arguments": normalized,
                              "success_count": count, "last_verified_at": stamp})
            except (ValueError, TypeError):
                continue
        return valid

    def _remember_open(self, arguments):
        with self._directory() as directory:
            items = self._candidates(directory)
            previous = next((item for item in items
                             if item["arguments"]["package_name"] == arguments["package_name"]), None)
            if previous is None:
                previous = {"candidate_id": "c_" + secrets.token_hex(16), "tool": "open_app_ready",
                            "arguments": arguments, "success_count": 0}
                items.append(previous)
            else:
                # Repeatedly opening the same app is the same basic operation even
                # when callers choose different wait budgets. Keep the larger
                # successful budget in the suggested reusable step.
                previous["arguments"]["timeout_ms"] = max(previous["arguments"]["timeout_ms"], arguments["timeout_ms"])
            previous["success_count"] = min(10000, previous["success_count"] + 1)
            previous["last_verified_at"] = self.wall_clock()
            items.sort(key=lambda item: item["last_verified_at"], reverse=True)
            self._write(directory, CANDIDATE_FILE, {"version": 1, "items": items[:32]})

    def record_action(self, name, arguments, result):
        """Record actual top-level calls; never trust receipt IDs supplied by the model.

        Full screen evidence stays in this process. Only repeated ready-app package
        counts are persisted. Unsupported actions leave a redacted marker so proofs
        cannot silently omit typing or other mutations.
        """
        self.sequence += 1
        now = self.clock()
        before = self.last_screen if now - self.last_screen_at <= 30 else None
        record = {"seq": self.sequence, "at": now, "tool": name, "receipt": None}
        try:
            normalized = _arguments(name, arguments)
            _require(isinstance(result, dict) and result.get("ok") is True, "Tool did not succeed")
            after = _screen(result) if name == "read_screen" else None
            if name == "open_app_ready":
                after = _screen(result.get("screen"))
                _require(result.get("status") == "ready" and after and after["package"] == normalized["package_name"], "App was not verified ready")
            if name == "read_screen":
                _require(after is not None, "No readable foreground screen")
            receipt = "r_" + secrets.token_hex(16)
            record.update(receipt=receipt, arguments=normalized, before=before, after=after)
            if after is not None:
                self.last_screen = after
                self.last_screen_at = now
            elif name in MUTATING_TOOLS:
                self.last_screen = None
            if name == "open_app_ready":
                # Persistence failure must not relabel an already executed phone action.
                try:
                    self._remember_open(normalized)
                except (OSError, ValueError, TypeError):
                    pass
        except (ValueError, TypeError, KeyError):
            if name in MUTATING_TOOLS or name == "read_screen":
                self.last_screen = None
        self.history.append(record)
        return record["receipt"]

    def _receipt(self, identity):
        _require(isinstance(identity, str), "Missing actual tool receipt")
        record = next((entry for entry in self.history if entry.get("receipt") == identity), None)
        _require(record is not None and self.clock() - record["at"] <= EVIDENCE_SECONDS, "Receipt is unknown or expired; run the steps again")
        return record

    def _proof(self, steps, verify, proof):
        _object(proof, {"step_receipts", "verification_receipt"}, {"step_receipts", "verification_receipt"})
        references = proof["step_receipts"]
        _require(isinstance(references, list) and len(references) == len(steps), "Proof must reference every step")
        records = [self._receipt(identity) for identity in references]
        for step, record in zip(steps, records):
            _require(record["tool"] == step["tool"] and record["arguments"] == step["arguments"], "Proof does not match the saved step")
            if "expect_package" in step:
                _require(record.get("before") and record["before"]["package"] == step["expect_package"], "Proof lacks the expected foreground package before action")
        sequence = [record["seq"] for record in records]
        _require(sequence == sorted(set(sequence)), "Step receipts must be distinct and ordered")
        verification = self._receipt(proof["verification_receipt"])
        _require(verification["seq"] >= sequence[-1] and _matches(verification.get("after"), verify), "Final package/text verification has not succeeded")
        referenced_mutations = {record["seq"] for record in records if record["tool"] in MUTATING_TOOLS}
        actual_mutations = {entry["seq"] for entry in self.history
                            if sequence[0] <= entry["seq"] <= verification["seq"] and entry["tool"] in MUTATING_TOOLS}
        _require(referenced_mutations == actual_mutations, "Proof omitted an intervening phone action")
        return sequence[0], verification["seq"]

    def save(self, arguments):
        _object(arguments, {"name", "description", "steps", "verify", "proofs", "candidate_id", "verification_receipt"}, {"name", "steps", "verify"})
        name = _name(arguments["name"])
        description = arguments.get("description", "")
        _require(isinstance(description, str) and len(description) <= 160, "Description is too long")
        steps, verify = _steps(arguments["steps"]), _verify(arguments["verify"])
        with self._directory() as directory:
            if "candidate_id" in arguments:
                _require("proofs" not in arguments and len(steps) == 1 and steps[0]["tool"] == "open_app_ready"
                         and "expect_package" not in steps[0], "Candidate proof is only for a single ready-app step")
                candidate = next((item for item in self._candidates(directory) if item["candidate_id"] == arguments["candidate_id"]), None)
                _require(candidate is not None and candidate["success_count"] >= 2 and candidate["arguments"] == steps[0]["arguments"], "Candidate has not succeeded twice with these parameters")
                _require(verify["package"] == steps[0]["arguments"]["package_name"], "Final package differs from the ready app")
                recent = self._receipt(arguments.get("verification_receipt"))
                _require(_matches(recent.get("after"), verify), "Fresh package/text verification is required")
                _require(not any(entry["seq"] > recent["seq"] and entry["tool"] in MUTATING_TOOLS for entry in self.history), "Phone changed after verification")
                proof_source = "repeated_ready_app"
            else:
                proofs = arguments.get("proofs")
                _require("verification_receipt" not in arguments and isinstance(proofs, list) and len(proofs) == 2, "Two actual successful runs are required")
                first, second = (self._proof(steps, verify, proof) for proof in proofs)
                _require(first[1] < second[0], "Successful runs must be distinct and chronological")
                proof_source = "two_observed_runs"
            filenames = [filename for filename in os.listdir(directory) if filename.endswith(".json") and NAME_RE.fullmatch(filename[:-5])]
            _require(name + ".json" in filenames or len(filenames) < MAX_ROUTINES, "Routine limit reached")
            routine = {"version": 1, "name": name, "description": description, "steps": steps, "verify": verify,
                       "created_at": self.wall_clock(), "validation": {"successful_runs": 2, "source": proof_source}}
            self._write(directory, name + ".json", routine)
        return {"ok": True, "name": name, "step_count": len(steps), "message": "已保存经过验证的手机操作脚本。"}

    def list(self):
        routines = []
        with self._directory() as directory:
            for filename in sorted(os.listdir(directory)):
                if not filename.endswith(".json") or not NAME_RE.fullmatch(filename[:-5]):
                    continue
                try:
                    routine = self._routine(directory, filename[:-5])
                    routines.append({"name": routine["name"], "description": routine["description"],
                                     "step_count": len(routine["steps"]), "verify": routine["verify"]})
                except (OSError, ValueError, TypeError, KeyError):
                    continue
                if len(routines) >= MAX_ROUTINES:
                    break
            candidates = [item for item in self._candidates(directory) if item["success_count"] >= 2]
        return {"ok": True, "routines": routines, "candidates": candidates[:8]}

    def _routine(self, directory, name):
        routine = self._load(directory, _name(name) + ".json")
        _require(isinstance(routine, dict) and routine.get("version") == 1 and routine.get("name") == name, "Invalid routine file")
        routine["steps"] = _steps(routine.get("steps"))
        routine["verify"] = _verify(routine.get("verify"))
        _require(isinstance(routine.get("description"), str) and len(routine["description"]) <= 160, "Invalid routine description")
        return routine

    def run(self, arguments, call_tool):
        _object(arguments, {"name", "timeout_ms"}, {"name"})
        name = _name(arguments["name"])
        timeout_ms = _integer(arguments.get("timeout_ms", 60000), 1000, 90000)
        with self._directory() as directory:
            routine = self._routine(directory, name)
        deadline = self.clock() + timeout_ms / 1000
        completed = 0
        def call(tool, params):
            _require(self.clock() < deadline, "Routine deadline reached")
            params = dict(params)
            if tool == "open_app_ready":
                remaining = int((deadline - self.clock()) * 1000)
                _require(remaining >= 1000, "Routine deadline reached")
                params["timeout_ms"] = min(params.get("timeout_ms", 15000), remaining)
            result = call_tool(tool, params)
            _require(self.clock() <= deadline, "Routine deadline reached")
            _require(isinstance(result, dict) and result.get("ok") is True, "Phone tool failed: " + str(result.get("error", tool) if isinstance(result, dict) else tool)[:200])
            if tool == "open_app_ready":
                screen = _screen(result.get("screen"))
                _require(result.get("status") == "ready" and screen and screen["package"] == params["package_name"], "App did not become ready")
            return result
        try:
            for index, step in enumerate(routine["steps"]):
                if "expect_package" in step:
                    screen = _screen(call("read_screen", {}))
                    _require(screen and screen["package"] == step["expect_package"], "Foreground package does not match expect_package; stopped before action")
                call(step["tool"], step["arguments"])
                completed += 1
                if step["settle_ms"]:
                    _require(self.clock() + step["settle_ms"] / 1000 <= deadline, "Routine deadline reached")
                    self.sleep(step["settle_ms"] / 1000)
            # A gesture's ok result is insufficient. Poll briefly for the final UI.
            verification_deadline = min(deadline, self.clock() + 3)
            while True:
                screen = _screen(call("read_screen", {}))
                if _matches(screen, routine["verify"]):
                    return {"ok": True, "name": name, "completed_steps": completed, "verification": routine["verify"],
                            "message": "脚本完成，已验证最终应用与界面文字。"}
                _require(self.clock() + 0.25 < verification_deadline, "Final package/text verification failed")
                self.sleep(0.25)
        except Exception as error:
            return {"ok": False, "name": name, "completed_steps": completed, "error": str(error)[:300],
                    "message": "脚本已停止；请读取当前界面后再决定下一步。"}

    def dispatch(self, name, arguments, call_tool):
        try:
            if name == "list_routines":
                _object(arguments, set())
                return self.list()
            if name == "save_routine":
                return self.save(arguments)
            if name == "run_routine":
                return self.run(arguments, call_tool)
            raise ValueError("Unknown routine tool")
        except (OSError, ValueError, TypeError, KeyError) as error:
            return {"ok": False, "error": str(error)[:300]}
