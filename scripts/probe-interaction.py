#!/usr/bin/env python3
"""Probe the installed CLI against localhost, using a unique disposable Linux home.

Never reads model preferences or the user's Claude home, rebinds accessibility,
or installs instrumentation. Do not run alongside a user task or another device test.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import queue
import re
import shlex
import subprocess
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.ds.phoneagent"
spec = importlib.util.spec_from_file_location("interaction_fixture", ROOT / "tests/interaction_fixture.py")
fixture_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture_module)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--timeout", type=float, default=180)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--only-memory", action="store_true", help="Check native memory loading/writing without the other protocol rounds")
    modes.add_argument("--only-retry", action="store_true", help="Capture native HTTP 500 retry events without file, question or memory rounds")
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    identity = "interaction-probe-" + uuid.uuid4().hex
    output = ROOT / "local" / identity
    output.mkdir(mode=0o700)
    prefix = ["adb", "-s", args.serial]

    def adb(*command, data=None):
        return subprocess.run(prefix + list(command), input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              check=True, timeout=12).stdout

    def shell(command, data=None):
        return adb("shell", command, data=data)

    def app(command, data=None):
        return shell(shlex.join(["run-as", PACKAGE, "sh", "-c", command]), data=data)

    last = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/last-task.json"))
    if last.get("state") == "running":
        raise SystemExit("An active user task exists; probe refused")
    processes = adb("shell", "ps", "-A", "-o", "PID,NAME").decode()
    if any(re.search(r"\b(?:claude|proot)\b", line) for line in processes.splitlines()):
        raise SystemExit("A CLI/runtime process already exists; probe refused")
    base = adb("shell", "run-as", PACKAGE, "pwd").decode().strip()
    package = adb("shell", "dumpsys", "package", PACKAGE).decode()
    native = re.search(r"legacyNativeLibraryDir=(\S+)", package).group(1) + "/arm64"
    isolated = base + "/cache/" + identity
    fixture = fixture_module.Fixture()
    server = fixture.server()
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    process = None
    events, received = queue.Queue(), []
    try:
        app(shlex.join(["mkdir", "-p", isolated + "/home/.claude/projects/-home-agent/memory", isolated + "/tmp", isolated + "/bridge"]))
        app("cat > " + shlex.quote(isolated + "/bridge/mcp.json"), b'{"mcpServers":{}}')
        if not args.only_retry:
            app("cat > " + shlex.quote(isolated + "/home/.claude/projects/-home-agent/memory/MEMORY.md"),
                (fixture_module.MEMORY_MARKER + "\nThis is isolated probe memory.\n").encode())
            app("cat > " + shlex.quote(isolated + "/home/CLAUDE.md"), (fixture_module.CLAUDE_MD_MARKER + "\n").encode())
        adb("reverse", "tcp:" + str(port), "tcp:" + str(port))
        environment = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": "/home/agent", "USER": "agent", "LOGNAME": "agent",
                       "LANG": "C.UTF-8", "TERM": "dumb", "TMPDIR": "/tmp", "TZ": "Asia/Shanghai", "CI": "1",
                       "DISABLE_AUTOUPDATER": "1", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
                       "ANTHROPIC_API_KEY": fixture_module.KEY, "ANTHROPIC_BASE_URL": "http://127.0.0.1:" + str(port),
                       "LD_LIBRARY_PATH": native, "PROOT_LOADER": native + "/libproot-loader.so",
                       "PROOT_LOADER_32": native + "/libproot-loader32.so", "PROOT_TMP_DIR": isolated + "/tmp",
                       "PROOT_NO_SECCOMP": "1", "ANDROID_ROOT": "/system", "ANDROID_DATA": "/data"}
        clean_environment = ["/system/bin/env", "-i"] + [key + "=" + value for key, value in environment.items()]
        command = [native + "/libproot.so", "--kill-on-exit", "-r", base + "/files/linux/rootfs", "-i", "1000:1000",
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", isolated + "/home:/home/agent",
                    "-b", isolated + "/tmp:/tmp", "-b", isolated + "/bridge:/opt/phoneagent", "-w", "/home/agent",
                    "/usr/bin/env", "-u", "LD_LIBRARY_PATH", "-u", "PROOT_LOADER", "-u", "PROOT_LOADER_32",
                    "-u", "PROOT_TMP_DIR", "-u", "PROOT_NO_SECCOMP", "-u", "ANDROID_ROOT", "-u", "ANDROID_DATA",
                    "/usr/local/bin/claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                    "--replay-user-messages", "--permission-prompt-tool", "stdio", "--permission-mode", "default",
                    "--tools", "Read,Write,Edit,Glob,Grep,AskUserQuestion", "--allowedTools", "Read,Write,Edit,Glob,Grep",
                    "--strict-mcp-config", "--mcp-config", "/opt/phoneagent/mcp.json", "--model", "phoneagent-interaction-probe"]
        # Android package paths contain '='; env would otherwise parse the executable as an assignment.
        command = clean_environment + ["/system/bin/sh", "-c", "exec " + shlex.join(command)]
        launch = shlex.join(["run-as", PACKAGE, "sh", "-c", "echo $$ > " + shlex.quote(isolated + "/pid") + "; exec " + shlex.join(command)])
        process = subprocess.Popen(prefix + ["shell", launch], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1)

        def reader():
            for line in process.stdout:
                try:
                    event = json.loads(line)
                except ValueError:
                    event = {"diagnostic": line.strip()[:600]}
                received.append(event)
                events.put(event)
            events.put({"eof": True})

        threading.Thread(target=reader, daemon=True).start()

        def send(value):
            process.stdin.write(json.dumps(value) + "\n")
            process.stdin.flush()

        def wait(predicate, stage):
            while time.monotonic() < deadline:
                event = events.get(timeout=max(.1, deadline - time.monotonic()))
                print(stage, event.get("type", "diagnostic"), event.get("subtype", ""), flush=True)
                if event.get("eof"):
                    raise AssertionError("CLI exited during " + stage)
                if predicate(event):
                    return event
            raise TimeoutError(stage)

        def user(text):
            send({"type": "user", "message": {"role": "user", "content": text}, "parent_tool_use_id": None,
                  "session_id": "", "uuid": str(uuid.uuid4())})

        send({"type": "control_request", "request_id": "init-1", "request": {"subtype": "initialize"}})
        wait(lambda e: e.get("type") == "control_response", "initialize")
        if args.only_retry:
            user("PROBE_RETRY")
            completed = wait(lambda e: e.get("type") == "result", "native retry")
            retry_events = [event for event in received if event.get("type") == "system" and event.get("subtype") == "api_retry"]
            assert not completed.get("is_error") and "PROBE_RETRY_OK" in completed.get("result", ""), "Native retry did not finish successfully"
            assert fixture.retry_attempts == 2, "Fixture did not receive exactly one failed request and one retry"
            assert retry_events, "Native CLI did not emit system/api_retry without partial-message output"
            (output / "result.json").write_text(json.dumps({"result": "PASS", "partial_messages_enabled": False,
                    "api_retry_events": retry_events, "final_num_turns": completed.get("num_turns"),
                    "fixture_attempts": fixture.retry_attempts}, ensure_ascii=False, indent=2) + "\n")
            print("PASS native retry", output, flush=True)
            return
        if args.only_memory:
            user("PROBE_MEMORY")
            memory = wait(lambda e: e.get("type") == "result", "native memory")
            assert not memory.get("is_error") and "PROBE_MEMORY_OK" in memory.get("result", ""), "Native memory write failed"
            assert any(r["native_memory_loaded"] for r in fixture.requests), "Native MEMORY.md was not loaded"
            assert any(r["native_claude_md_loaded"] for r in fixture.requests), "Native CLAUDE.md was not loaded"
            assert b"probe-note.md" in app("cat " + shlex.quote(isolated + "/home/.claude/projects/-home-agent/memory/MEMORY.md"))
            assert b"test color is blue" in app("cat " + shlex.quote(isolated + "/home/.claude/projects/-home-agent/memory/probe-note.md"))
            (output / "result.json").write_text(json.dumps({"result": "PASS", "native_memory_loaded": True,
                  "native_claude_md_loaded": True, "native_memory_written": True, "requests": fixture.requests}, indent=2) + "\n")
            print("PASS native memory", output, flush=True)
            return
        user("PROBE_ASK")
        asked = wait(lambda e: e.get("type") == "control_request" and e.get("request", {}).get("tool_name") == "AskUserQuestion", "ask")
        request = asked["request"]
        send({"type": "control_response", "response": {"subtype": "success", "request_id": asked["request_id"],
              "response": {"behavior": "allow", "updatedInput": {**request["input"], "answers": {fixture_module.QUESTION: "Blue"}},
                           "toolUseID": request["tool_use_id"]}}})
        answer = wait(lambda e: e.get("type") == "result", "answer")
        assert not answer.get("is_error") and "PROBE_ASK_OK" in answer.get("result", ""), "Native question answer failed"
        user("PROBE_FILES")
        files = wait(lambda e: e.get("type") == "result", "files")
        assert not files.get("is_error") and "PROBE_FILES_OK" in files.get("result", ""), "Native file tools failed"
        user("PROBE_INTERRUPT")
        assert fixture.slow_started.wait(min(30, max(0, deadline - time.monotonic()))), "Slow request did not start"
        send({"type": "control_request", "request_id": "interrupt-1", "request": {"subtype": "interrupt", "cancel_queued": True}})
        wait(lambda e: e.get("type") == "control_response" and e.get("response", {}).get("request_id") == "interrupt-1", "interrupt")
        user("PROBE_AFTER_INTERRUPT")
        resumed = wait(lambda e: e.get("type") == "result" and "PROBE_AFTER_INTERRUPT_OK" in e.get("result", ""), "continue")
        assert not resumed.get("is_error") and process.poll() is None, "Interactive process did not survive"
        assert app("cat " + shlex.quote(isolated + "/home/probe.txt")).decode().strip() == "probe-two"
        (output / "result.json").write_text(json.dumps({"result": "PASS", "same_process": True,
                "native_memory_loaded": any(r["native_memory_loaded"] for r in fixture.requests),
                "requests": fixture.requests}, indent=2) + "\n")
        print("PASS", output, flush=True)
    finally:
        if process:
            try:
                # EOF lets a completed interactive CLI exit before its PRoot tracer is signalled.
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    identity = app("cat " + shlex.quote(isolated + "/pid")).decode().strip()
                    if identity.isdigit():
                        app("kill -TERM " + identity)
                    process.communicate(timeout=5)
                except Exception:
                    pass
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
        fixture.stop.set()
        server.shutdown()
        server.server_close()
        (output / "events.json").write_text(json.dumps(received, ensure_ascii=False, indent=2) + "\n")
        (output / "fixture.json").write_text(json.dumps(fixture.requests, indent=2) + "\n")
        adb("reverse", "--remove", "tcp:" + str(port))
        app(shlex.join(["rm", "-rf", isolated]))


if __name__ == "__main__":
    main()
