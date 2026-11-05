# Device smoke tests

For routine UI regression against an already-installed app, start with `python3 scripts/regress-ui.py`. It uses bounded ADB navigation without instrumentation, Claude, model calls or accessibility toggling. See [the fast regression guide](../../docs/testing.md) for coverage, timing reports and skips. The heavier modes below are selected integration tests, not a default all-modes checklist.

The legacy `pet` mode assumes `statePet` is a descendant of the scrollable content. Since 0.4.0 the pet is fixed in the header, so that mode must be updated before reuse. It is not part of the new default regression.

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

## Conversation history and migration

`history` uses unique cache directories, synthetic records and the actual `TaskHistoryStore`. It verifies the newest 20 of 25 conversations survive reconstruction in order, continuation appends a distinct turn while preserving the conversation/session identity, and prompt/process/result/terminal fields remain separate. A reconstructed running turn must become `interrupted` once without changing prior completed turns. It also models a crash after the same conversation's followup is saved but before `last-task.json` updates: Controller must show the interrupted followup, not the older successful turn. The legacy migration must preserve its complete transcript and original file, run once, and never invent a resumable CLI session. An isolated Controller reads the reconstructed history. No model, fixture server, real app history or user credentials are used.

```bash
python3 scripts/test-device.py history
```

## Deletion persistence and running-task protection

`delete` uses isolated cache-backed history and last-task files. It deletes the currently saved conversation, checks that only its linked last-task is cleared, confirms another conversation is unchanged, rebuilds Controller, then resets the remaining conversation and rebuilds again. That reset must persist acknowledgment of only the remaining turn and restore idle/setup presentation without changing its history or reviving the deleted record. It then deletes the final record and verifies empty history stays empty. A separate migrated legacy fixture must also remain deleted when an old last-task snapshot is supplied again. An isolated synthetic CLI session file is retained throughout; deleting app history must not remove CLI files.

The final lifecycle check launches the actual installed CLI against **only the unreachable local address `http://127.0.0.1:9`**, using the disposable test key. While that process is alive, deleting its active conversation must be rejected. Deleting an unrelated old fixture must leave the active ID, process reference, running state, presentation epoch and last-task record unchanged. The test then stops the local task and checks persistence. No fixture server, reversed port or real model is used. The installed runtime and accessibility service must be available. This local launch can leave one new test CLI session in the Linux home; Android user history and model settings are untouched.

```bash
python3 scripts/test-device.py delete
```

## Recent-task navigation and swipe-to-delete

`historyui` temporarily presents an isolated Controller with five synthetic conversations in the real MainActivity. It verifies the default view is not the recent-task list, the task-record button explicitly opens that list, and “连接与设置” and the history entry remain above the scrolling content. Opening recent tasks closes the settings panel. No connection field is read or changed.

Real `Instrumentation.sendPointerSync` touchscreen gestures run against those fixture rows. A predominantly vertical movement must neither open the conversation nor reveal/delete it; a left swipe exposes a Delete button on the right without opening or deleting; a right swipe closes it. A vertical gesture beginning on an already exposed Delete button must also cancel its pending click without deleting. Only a subsequent real tap on the exposed Delete button removes the selected fixture. The test reconstructs history to verify the deletion persists, then checks a normal tap on a closed row opens its detail and the task-record entry returns to the updated list. It never sends a task or contacts a model. The original Controller, selected view, drafts, expanded processes, setup visibility and scroll position are restored, and only the isolated fixture directory is removed.

Before repositioning rows between gestures, the harness sends ScrollView a local DOWN/CANCEL pair to end any previous fling, then scrolls to the fixture. `scrollTo` and `waitForIdleSync` alone do not stop Android's OverScroller. Touch coordinates come from local visible bounds plus the view's screen location. Passive event logs report fixture attachment/current identity, screen/viewport bounds, scroll offset and DOWN/MOVE/UP/CANCEL routing without recording page content.

```bash
python3 scripts/test-device.py historyui
```

## Interactive Controller lifecycle

`interaction` uses the actual Controller and phone-installed Claude Code with a separate local fixture. The bounded runner starts/stops the fixture, owns and cleans its reverse port, and compares user history/preferences/Linux HOME hashes before and after. It requires a serial and defaults to a 240-second total budget, including cleanup:

```bash
python3 scripts/test-interaction.py --serial DEVICE_SERIAL
```

To run the fixture manually, start a **fresh** instance for each run:

```bash
python3 tests/interaction_fixture.py --port 18081
```

In another terminal, with only the intended device selected:

```bash
adb reverse tcp:18081 tcp:18081
python3 scripts/test-device.py interaction
adb reverse --remove tcp:18081
```

It waits for native `AskUserQuestion`, rejects answers from another conversation or an outdated question ID, submits the actual answer, and requires the same CLI session to finish successfully. It checks that a submitted answer waits for the task UI to hide, including a deterministically queued hide/show race. A copy of the real waiting record and its native session is then reconstructed in another isolated store/HOME: it must remain `WAITING_USER`, create no process or model request until explicitly answered, and resume the original native question context in the same session. Another copy checks Stop/Reset while offline. A delayed fixture request exercises native pause, a closed phone bridge, old actions already queued for Android's main thread, and correction followed by continuation in the same process/session. A final live question is stopped and reset, requiring its process to be reaped and its output retained.

All test history, CLI session files, native memory, bridge files and temporary files live under one unique cache directory, which is removed afterwards. Only the installed rootfs is shared; this mode never calls runtime installation. User history, Linux HOME and model preferences are not written. The fixture's `/status` endpoint exposes only a request count and a delay-started flag to synchronize the pause test without guessing a sleep duration. No real model endpoint or key is used. This Controller test complements `scripts/probe-interaction.py`, whose separate isolated probe covers native file tools, native memory loading/writing, and raw transport protocol behavior.

## Real Claude session continuation

Start the updated local fixture and reverse port 18080 as described above, then run:

```bash
python3 scripts/test-device.py resume
```

`resume` runs the real installed Claude Code three times through the actual Controller: conversation A's first task, independent conversation B, then continuation of A after rebuilding the Controller. All requests use `phoneagent-smoke-session` and the disposable local key. Each test generates separate random conversation markers. The fixture permits the continuation's final response only when its Anthropic `messages` contain **both the first user prompt and the first assistant final response in order**, with no markers from B. Each turn must execute a fresh `read_screen` with its own exact tool-use ID; old tool counts cannot satisfy the new turn. The runtime supplies the saved real CLI session through `--resume`.

The test requires A's CLI session ID to remain unchanged, B's ID to differ, A to have two distinct turns without changing the first, B to remain unchanged, and the resumed conversation to move to the top of history. The fixture emits intermediate assistant prose followed by a Markdown final response; persisted `process`, `result` and `terminal` must remain separate, with no duplicated final prose in process. This is integration validation using fixed responses, not validation of an external model's reasoning.

Android history and last-task files live in an isolated cache directory. The actual Linux home gains **two new fixture CLI session files**, whose UUIDs are reported; use a pre-test backup if an entirely pristine Linux home is required. The test does not change HOME or user model settings. It temporarily renders the isolated Controller in the real MainActivity to check the two-conversation list, two native Markdown results for A, default-collapsed and expandable process, enabled “继续对话”, and switching to B without stale A content. It never clicks the send button and restores the original Controller, selection, draft and expanded-process state. The isolated history directory is removed afterwards.

Restart an already-running fixture server after modifying `tests/mock_anthropic.py`.

## Native Markdown and safe links

`markdown` constructs the actual `MarkdownView` on the UI thread and inspects its native children and spans. It checks heading hierarchy, bold/italic/strikethrough, inline monospace, lists, quotes, literal fenced code, a native table, horizontal code/table containers and an HTTPS URL span. Unsafe schemes, relative URLs, credentials in URLs, malformed ports, whitespace and backslashes must remain non-clickable; blocked-link labels stay readable. Replacing a result removes old nodes, while an identical result does not rebuild the tree. No link is clicked, remote resource loaded or model contacted.

```bash
python3 scripts/test-device.py markdown
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
