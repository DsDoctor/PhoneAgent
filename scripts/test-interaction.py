#!/usr/bin/env python3
"""Bounded, fixture-only Controller regression; installed app and instrumentation APK required."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.ds.phoneagent"
COMPONENT = PACKAGE + "/" + PACKAGE + ".PhoneAccessibilityService"
spec = importlib.util.spec_from_file_location("interaction_fixture", ROOT / "tests/interaction_fixture.py")
fixture_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture_module)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--timeout", type=float, default=240)
    parser.add_argument("--stop-only", action="store_true", help="Run only the native waiting-question Stop/Reset regression")
    args = parser.parse_args()
    if not 90 <= args.timeout <= 240:
        parser.error("--timeout must be between 90 and 240 seconds, including cleanup")
    started = time.monotonic()
    deadline = started + args.timeout
    work_deadline = deadline - 35
    identity = uuid.uuid4().hex
    output = ROOT / "local" / ("interaction-test-" + identity)
    output.mkdir(mode=0o700)
    prefix = ["adb", "-s", args.serial]
    cleanup = False

    def adb(*command, check=True):
        remaining = (deadline if cleanup else work_deadline) - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Interaction regression budget exhausted")
        return subprocess.run(prefix + list(command), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=min(12, remaining), check=check)

    def snapshot():
        hashes = {}
        for path in ("files/task-history.json", "files/last-task.json", "shared_prefs/phoneagent.xml"):
            result = adb("exec-out", "run-as", PACKAGE, "sha256sum", path, check=False)
            parts = result.stdout.decode().split()
            if result.returncode or not parts or re.fullmatch(r"[0-9a-f]{64}", parts[0]) is None:
                if b"No such file" not in result.stderr + result.stdout:
                    raise RuntimeError("Cannot hash an expected user-state file")
                hashes[path] = "missing"
            else:
                hashes[path] = parts[0]
        # Hash every user-home file, then persist only the aggregate and count, never its contents/names.
        result = adb("exec-out", "run-as", PACKAGE, "sh", "-c",
                     "find files/linux/home -type f -exec sha256sum '{}' ';'")
        lines = sorted(result.stdout.splitlines())
        if any(re.match(rb"^[0-9a-f]{64}\s", line) is None for line in lines):
            raise RuntimeError("Cannot hash the Linux user home")
        hashes["linux_home_file_count"] = len(lines)
        hashes["linux_home_digest"] = hashlib.sha256(b"\n".join(lines)).hexdigest()
        return hashes

    def enable_phone_tools():
        result = adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
        current = result.stdout.decode().strip()
        services = [] if current in ("", "null") else current.split(":")
        services = [value for value in services if value not in (COMPONENT, PACKAGE + "/.PhoneAccessibilityService")]
        if services:
            adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services))
        else:
            adb("shell", "settings", "delete", "secure", "enabled_accessibility_services")
        time.sleep(.3)
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services + [COMPONENT]))
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")

    def task_document(path):
        result = adb("exec-out", "run-as", PACKAGE, "cat", path, check=False)
        if b"No such file" in result.stdout + result.stderr:
            return None
        try:
            document = json.loads(result.stdout)
            if result.returncode or not isinstance(document, dict):
                raise ValueError()
            return document
        except (ValueError, TypeError):
            raise RuntimeError("Cannot safely inspect persisted task state") from None

    fixture = fixture_module.Fixture()
    server = None
    process = None
    reversed_port = False
    before = None
    report = {"result": "FAIL", "serial": args.serial}
    lines = []
    errors = []
    try:
        active_states = {"running", "pausing", "paused", "waiting_user", "resuming"}
        current = task_document("files/last-task.json")
        history = task_document("files/task-history.json")
        if current is not None and current.get("state") in active_states:
            raise RuntimeError("An active or pending user task exists; regression refused")
        if history is not None and any(turn.get("state") in active_states
                for conversation in history.get("conversations", []) for turn in conversation.get("turns", [])):
            raise RuntimeError("Conversation history contains an active or pending task; regression refused")
        processes = adb("shell", "ps", "-A", "-o", "PID,NAME").stdout.decode()
        if any(re.search(r"\b(?:claude|proot)\b", line) for line in processes.splitlines()):
            raise RuntimeError("A CLI/runtime process already exists; regression refused")
        if any("tcp:18081" in line.split() for line in adb("reverse", "--list").stdout.decode().splitlines()):
            raise RuntimeError("Device port 18081 already has a reverse mapping; regression refused")
        before = snapshot()
        (output / "before.json").write_text(json.dumps(before, indent=2) + "\n")
        server = fixture.server()
        threading.Thread(target=server.serve_forever, daemon=True).start()
        adb("reverse", "tcp:18081", "tcp:" + str(server.server_address[1]))
        reversed_port = True
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        process = subprocess.Popen(prefix + ["shell", "am", "instrument", "-w", "-r", "-e", "mode", "interaction",
                                            "-e", "fixture_id", identity,
                                            "-e", "interaction_stage", "stop" if args.stop_only else "all",
                                            PACKAGE + ".tests/" + PACKAGE + ".TestInstrumentation"],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        events = queue.Queue()

        def reader():
            for line in process.stdout:
                events.put(line)
            events.put(None)

        threading.Thread(target=reader, daemon=True).start()
        enabled = False
        while time.monotonic() < work_deadline:
            try:
                line = events.get(timeout=min(.5, max(.01, work_deadline - time.monotonic())))
            except queue.Empty:
                continue
            if line is None:
                break
            lines.append(line)
            print(line, end="", flush=True)
            if not enabled and "Test started with MainActivity visible" in line:
                enable_phone_tools()
                enabled = True
        else:
            raise TimeoutError("Interaction regression exceeded its execution budget")
        process.wait(timeout=max(.1, min(5, work_deadline - time.monotonic())))
        transcript = "".join(lines)
        if process.returncode != 0 or "PASS interaction" not in transcript or "INSTRUMENTATION_CODE: -1" not in transcript:
            raise AssertionError("Instrumentation did not report a successful interaction result")
        report["result"] = "PASS"
    except Exception as error:
        errors.append(type(error).__name__ + ": " + str(error))
    finally:
        cleanup = True
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)
            try:
                adb("shell", "am", "force-stop", PACKAGE)
            except Exception as error:
                errors.append("Stop timed-out instrumentation: " + str(error))
        fixture.stop.set()
        if server is not None:
            server.shutdown()
            server.server_close()
        if reversed_port:
            try:
                adb("reverse", "--remove", "tcp:18081")
            except Exception as error:
                errors.append("Remove fixture mapping: " + str(error))
        if process is not None:
            try:
                preview = "cache/interaction-ui-card-" + identity + ".png"
                picture = adb("exec-out", "run-as", PACKAGE, "cat", preview, check=False)
                if picture.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                    (output / "ui-card.png").write_bytes(picture.stdout)
                    report["ui_preview"] = "ui-card.png"
                adb("exec-out", "run-as", PACKAGE, "rm", "-f", preview)
                adb("exec-out", "run-as", PACKAGE, "rm", "-rf", "cache/interaction-smoke-" + identity)
                adb("shell", "am", "start", "-f", "0x24000000", "-n", PACKAGE + "/.MainActivity")
                enable_phone_tools()
            except Exception as error:
                errors.append("Restore foreground app/accessibility: " + str(error))
        if before is not None:
            try:
                after = snapshot()
                (output / "after.json").write_text(json.dumps(after, indent=2) + "\n")
                report["user_files_unchanged"] = before == after
                if before != after:
                    errors.append("User history/preferences/Linux HOME hashes changed")
            except Exception as error:
                errors.append("Verify user files: " + str(error))
        if errors:
            report["result"] = "FAIL"
        report.update(elapsed_seconds=round(time.monotonic() - started, 2), errors=errors,
                      fixture_requests=fixture.requests)
        (output / "device.log").write_text("".join(lines))
        (output / "result.json").write_text(json.dumps(report, indent=2) + "\n")
        print(report["result"], output, flush=True)
    if report["result"] != "PASS":
        raise SystemExit("Interaction regression failed; inspect the local result.json")


if __name__ == "__main__":
    main()
