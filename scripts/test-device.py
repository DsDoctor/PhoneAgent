#!/usr/bin/env python3
"""Run one test on the connected device; preserve other accessibility services.

Huawei clears this app's accessibility permission when instrumentation restarts
the target process. Re-enable only PhoneAgent after the test app has started.
"""
import argparse
import subprocess
import time

COMPONENT = "com.ds.phoneagent/com.ds.phoneagent.PhoneAccessibilityService"


def adb(*args, capture=False):
    return subprocess.run(["adb", *args], check=True, text=True, capture_output=capture)


def enable_phone_tools():
    value = adb("shell", "settings", "get", "secure", "enabled_accessibility_services", capture=True).stdout.strip()
    services = [] if value in ("", "null") else value.split(":")
    services = [s for s in services if s not in (COMPONENT, "com.ds.phoneagent/.PhoneAccessibilityService")]
    # Restarting the process can leave the service in Huawei's crashed-services
    # set. Toggle only our component so the system actually binds it again.
    if services:
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services), capture=True)
    else:
        adb("shell", "settings", "delete", "secure", "enabled_accessibility_services", capture=True)
    time.sleep(0.3)
    services.append(COMPONENT)
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services), capture=True)
    adb("shell", "settings", "put", "secure", "accessibility_enabled", "1", capture=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("runtime", "tools", "loop", "controller", "input", "overlay", "pet", "routines", "longtask", "taskstore", "history", "resume", "markdown", "delete", "historyui"))
    args = parser.parse_args()
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP", capture=True)
    process = subprocess.Popen(["adb", "shell", "am", "instrument", "-w", "-e", "mode", args.mode,
                                "com.ds.phoneagent.tests/com.ds.phoneagent.TestInstrumentation"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    passed = False
    enabled = False
    try:
        for line in process.stdout:
            print(line, end="", flush=True)
            if not enabled and "Test started with MainActivity visible" in line:
                enable_phone_tools()
                enabled = True
            if line.strip() == "PASS " + args.mode:
                passed = True
        code = process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
        adb("shell", "am", "start", "-f", "0x24000000", "-n", "com.ds.phoneagent/.MainActivity", capture=True)
        enable_phone_tools()
    if not passed or code:
        raise SystemExit("Device test failed: " + args.mode)


if __name__ == "__main__":
    main()
