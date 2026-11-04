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

## Real MCP routines, without a model

`routines` launches the phone's actual Linux Python and `phone_mcp.py` over stdio. It discovers the real tools, executes `open_app_ready` for `com.android.settings` twice, collects real action and screen-verification receipts, saves one verified JSON routine, and runs it. A second MCP process then lists the saved routine and persisted repeated-launch candidate, proving persistence across processes. It verifies the actual Settings package and readable “设置” text, without changing a setting.

The MCP processes receive `PHONE_AGENT_ROUTINES_DIR=/home/agent/.phoneagent/routines-smoke-<unique-id>`. This isolated store is required to appear before the test performs actions; the default user routine/candidate store is never accessed. Cleanup removes only that unique test directory. HOME remains unchanged. This mode does not launch Claude Code, contact a model, read credentials, use the host mock server or require an ADB reverse port.

```bash
python3 scripts/test-device.py routines
```

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

Its successful task uses the dedicated fixture model `phoneagent-smoke-ready`: `home → read_screen → open_app_ready(package_name="com.android.settings", timeout_ms=8000) → read_screen`. The actual MCP readiness tool waits for Settings to become readable and stable, so a transient empty accessibility root during the launch is not mistaken for a completed transition. Tool errors still fail the fixture; no error is swallowed. The separate `loop` mode retains the original `phoneagent-smoke-model` sequence using `open_settings`.

It then starts another task against the deliberately unreachable loopback address `http://127.0.0.1:9` using only the disposable test key. After observing process startup (or at most three seconds), it calls Stop and requires a cancellation callback plus cleared running/process state within ten seconds. This checks cancellation without contacting an external service.

```bash
python3 scripts/test-device.py controller
```

As with `loop`, this validates integration and lifecycle handling, not a real external model.

The controller test also requires visual-state callbacks in the order `THINKING → ACTING → THINKING → SUCCESS` for the fixture task and `THINKING → STOPPING → STOPPED` for cancellation. Repeated states and additional tool rounds are allowed. It checks the final state exposed by `getVisualState()` rather than simulating or writing Controller state.

The same run covers resetting to idle without repeating those success/stop tasks: SUCCESS and STOPPED reset to IDLE while retaining `lastOutput`, and reset during the unreachable-loopback task must be a strict no-op. A third task uses the fixture-only model name `phoneagent-smoke-error`, which returns an intentional HTTP 400 and produces a real Controller ERROR. The test clicks MainActivity's existing stop button after it changes to an enabled “重置” action, then checks IDLE and retained output. It observes model-setting **change keys only**, never their values or credentials, and requires that no model setting changed. No product test endpoint or real model request is involved.

Each success, error and manual-stop outcome must now publish and persist a `【任务结束】` log with the expected state/reason and completion time. Reset must preserve output and all historical record fields; only `acknowledged` may change, and it must become true. Newly completed tasks must not already be acknowledged. The controlled HTTP 400 must appear as a safe status hint; the test does not require the original provider body to be displayed. A final task targets only unreachable loopback; the test terminates its CLI process directly, without calling Controller.stop, to verify that a missing normal CLI result still produces an ERROR final log with `cli_no_result`. These real test tasks become the app's most recent task record; they contain only local fixture output and no real model configuration.

## More than thirty tool turns

`longtask` uses the independent local fixture model `phoneagent-smoke-long`, which requests exactly 31 successive `read_screen` calls before its final response. Responses use a short 20 ms fixture interval because no app transition is needed. The test requires successful completion, at least 31 ACTING callbacks from real phone tools, and a final persisted log. It does not use or alter a real model service. The test harness retains its own three-minute failure timeout; this is not an app task limit.

Start the local fixture and reverse its port as above, then run:

```bash
python3 scripts/test-device.py longtask
```

## Recent-task persistence and restart recovery

`taskstore` uses a unique cache directory and synthetic records. It rebuilds LastTaskStore and an isolated Controller to verify terminal-state/output restoration. An acknowledged terminal error must restore IDLE (or NEEDS_INPUT because the isolated runtime is uninstalled), retain its historical error log and leave all historical fields unchanged. The test then simulates an abandoned `running` record. Recovery must mark it `interrupted` with reason `process_interrupted`, clear any obsolete acknowledgment, append a final log, expose ERROR with `isRunning=false`, and remain idempotent on another recovery. Only the temporary cache directory is removed; real app data is not cleared. This mode needs no model, fixture server or reversed port.

```bash
python3 scripts/test-device.py taskstore
```

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
