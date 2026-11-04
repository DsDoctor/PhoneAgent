# Device smoke tests

This separate test APK uses Android's built-in `Instrumentation` API. It adds no exported test endpoint to PhoneAgent. Run it on an unlocked device after installing the app and enabling its accessibility service.

Each test opens MainActivity, starts the app's foreground service and holds a bounded partial wake lock until cleanup. Runtime preparation therefore begins while the app is visible. Tools and loop tests bring other apps to the foreground; Huawei may still require allowing PhoneAgent to run in the background under its battery/app-launch settings. A partial wake lock alone is not a guarantee against vendor process freezing.

```bash
python3 scripts/build_android_tests.py
adb install -r build/android-tests/PhoneAgent-tests.apk
python3 scripts/test-device.py runtime
python3 scripts/test-device.py tools
```

`runtime` prepares the offline payload and checks the actual Claude Code executable's version. `tools` verifies the loopback binding, token authorization, unknown-tool rejection, Home, Settings, Back and screen reading. It does not alter a system setting. Existing Settings navigation stacks may remain after Back, so the test explicitly returns to Home at the end.

Use `scripts/test-device.py` rather than invoking instrumentation directly on Huawei. Instrumentation restarts the target process, which can leave its accessibility service marked as crashed. The runner toggles only PhoneAgent's service after the test has started, preserving other enabled services, and restores the app afterwards.

## Gestures and Chinese input

`input` stays inside PhoneAgent. It locates the lowest visible, non-password EditText in the bottom task composer, taps its center, enters `手机输入测试`, verifies the text by reading the actual screen, then clears the draft. It refuses to replace a pre-existing draft. Finally, it dismisses the keyboard and verifies a swipe callback inside MainActivity's scrollable content. No task is submitted, and no model setting or system setting is edited.

```bash
python3 scripts/test-device.py input
```

## Local loop test

`loop` runs the real phone-installed Claude Code CLI and real phone MCP tools against a deterministic local Anthropic-protocol fixture. **This verifies the integration, not an actual external model service or its reasoning quality.** The fixture chooses `home → read_screen → open_settings → read_screen` from the received tool-result history, using tool names actually advertised by Claude Code. The test asserts those calls occurred, the CLI emitted a successful final result, and the phone is displaying Settings.

Run the fixture on the development host, then reverse its loopback port to the phone:

```bash
python3 tests/mock_anthropic.py
```

In a second terminal:

```bash
adb reverse tcp:18080 tcp:18080
python3 scripts/test-device.py loop
adb reverse --remove tcp:18080
```

The fixture binds only `127.0.0.1` and accepts the disposable key `local-test-only`; never supply real credentials to it. The CLI run is limited to two minutes, and the test closes its phone bridge and process afterwards. Stop the fixture with Ctrl+C when finished.

## App controller test

With the same local fixture and reversed port running, `controller` uses the actual `AgentController.run` path used by the UI. It waits for a successful completion callback, checks that parsed assistant output contains the fixture's final text, confirms `isRunning()` is false and the process reference is cleared, and verifies the actual Settings screen.

It then starts another task against the deliberately unreachable loopback address `http://127.0.0.1:9` using only the disposable test key. After observing process startup (or at most three seconds), it calls Stop and requires a cancellation callback plus cleared running/process state within ten seconds. This checks cancellation without contacting an external service.

```bash
python3 scripts/test-device.py controller
```

As with `loop`, this validates integration and lifecycle handling, not a real external model.

Success ends with `result=PASS` and `INSTRUMENTATION_CODE: -1`. Failure reports an assertion/exception and `result=FAIL`. Inspect the result text; `adb shell` itself can return exit code 0 even when an instrumentation test failed.

Instrumentation restarts the target process. Do not run these tests while a model task is active. Reopen PhoneAgent after testing if needed; remove the test APK when finished:

```bash
adb uninstall com.ds.phoneagent.tests
```
