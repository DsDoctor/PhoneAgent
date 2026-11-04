# Device smoke tests

This separate test APK uses Android's built-in `Instrumentation` API. It adds no exported test endpoint to PhoneAgent. Run it on an unlocked device after installing the app and enabling its accessibility service.

Each test opens MainActivity, starts the app's foreground service and holds a bounded partial wake lock until cleanup. Runtime preparation therefore begins while the app is visible. Tools and loop tests bring other apps to the foreground; Huawei may still require allowing PhoneAgent to run in the background under its battery/app-launch settings. A partial wake lock alone is not a guarantee against vendor process freezing.

The startup intent uses `NEW_TASK | CLEAR_TOP | SINGLE_TOP`, then checks that MainActivity is resumed, visible and focused before announcing test readiness. The runner's cleanup launch also clears activities above MainActivity; merely bringing an existing task forward could otherwise return to a system settings activity left at the top of that task.

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

The controller test also requires visual-state callbacks in the order `THINKING → ACTING → THINKING → SUCCESS` for the fixture task and `THINKING → STOPPING → STOPPED` for cancellation. Repeated states and additional tool rounds are allowed. It checks the final state exposed by `getVisualState()` rather than simulating or writing Controller state.

## Overlay and cat animation

`overlay` verifies that the bubble is hidden inside MainActivity, attached using `TYPE_ACCESSIBILITY_OVERLAY` on Home and Settings, and returns to the app when tapped. It then invokes the real phone-control button listener and goes directly Home from accessibility settings, exercising the previous stale-visibility regression. The Settings bubble is tapped with a real accessibility gesture; the control-button listener is invoked on the UI thread to avoid unrelated return-animation hit testing. Attached/view visibility alone does not prove compositor visibility; inspect the actual Settings window or `dumpsys window` as well.

`pet` uses the real `MonthlyCatView` on Home. It sets each view state, checks the getter, observes frame advancement for THINKING and ACTING, and restores the original view state. It then tests MainActivity's actual state cat with deterministic `ScrollView.scrollTo` calls: frames pause when fully clipped and resume after scrolling back, without calling `setState` to restart them. If the current content is too short, the test temporarily adds blank space via the content view's minimum height, then restores that height, scroll position and original view state. It never changes Controller state, edits user fields, reads model credentials or starts a model request.

```bash
python3 scripts/test-device.py overlay
python3 scripts/test-device.py pet
```

Success ends with `result=PASS` and `INSTRUMENTATION_CODE: -1`. Failure reports an assertion/exception and `result=FAIL`. Inspect the result text; `adb shell` itself can return exit code 0 even when an instrumentation test failed.

Instrumentation restarts the target process. Do not run these tests while a model task is active. Reopen PhoneAgent after testing if needed; remove the test APK when finished:

```bash
adb uninstall com.ds.phoneagent.tests
```
