package com.ds.phoneagent;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ScrollView;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Sideload-only test APK. No command receiver is added to the actual app. */
public final class TestInstrumentation extends Instrumentation {
    private String mode;
    private final StringBuilder report = new StringBuilder();
    private volatile MainActivity resumedMain;
    private volatile boolean mainResumed;

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity instanceof MainActivity) {
            resumedMain = (MainActivity) activity;
            mainResumed = true;
        }
    }

    @Override public void callActivityOnPause(Activity activity) {
        if (activity == resumedMain) mainResumed = false;
        super.callActivityOnPause(activity);
    }

    @Override public void onCreate(Bundle arguments) {
        mode = arguments == null ? "tools" : arguments.getString("mode", "tools");
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        PowerManager.WakeLock wakeLock = null;
        int resultCode = Activity.RESULT_CANCELED;
        try {
            Context target = getTargetContext();
            PowerManager power = (PowerManager) target.getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneAgent:device-test");
            wakeLock.acquire(15 * 60 * 1000L);
            runOnMainSync(() -> {
                target.startActivity(new Intent(target, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                target.startForegroundService(new Intent(target, OverlayService.class));
            });
            waitForIdleSync();
            waitForMainActivityVisible();
            log("Test started with MainActivity visible, foreground service and partial wake lock");
            if ("runtime".equals(mode)) runtime();
            else if ("tools".equals(mode)) tools();
            else if ("loop".equals(mode)) loop();
            else if ("controller".equals(mode)) controller();
            else if ("input".equals(mode)) input();
            else if ("overlay".equals(mode)) overlay();
            else if ("pet".equals(mode)) pet();
            else throw new AssertionError("Unknown test mode: " + mode);
            log("PASS " + mode);
            result.putString("stream", "\n" + report);
            result.putString("result", "PASS");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            String trace = Log.getStackTraceString(error);
            log("FAIL " + mode + "\n" + trace);
            result.putString("stream", "\n" + report);
            result.putString("result", "FAIL");
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
        finish(resultCode, result);
    }

    private void waitForMainActivityVisible() {
        long deadline = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean visible = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> {
                MainActivity activity = resumedMain;
                View decor = activity == null || activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                visible.set(mainResumed && activity != null && !activity.isFinishing() && !activity.isDestroyed()
                        && decor != null && decor.isShown() && decor.getWindowVisibility() == View.VISIBLE
                        && activity.hasWindowFocus());
            });
            if (visible.get()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("MainActivity did not become resumed, visible and focused after CLEAR_TOP launch");
    }

    private void runtime() throws Exception {
        LinuxRuntime runtime = new LinuxRuntime(getTargetContext());
        runtime.install(this::log);
        check(runtime.isInstalled(), "Runtime installation did not become ready");
        String version = runtime.version().trim();
        check(!version.isEmpty(), "Claude version output was empty");
        check(version.matches("(?s).*\\d+\\.\\d+\\.\\d+.*"), "Unexpected Claude version: " + version);
        log("Claude Code: " + version);
    }

    private void tools() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable PhoneAgent accessibility service before running tools tests");
        AtomicInteger observed = new AtomicInteger();
        try (PhoneToolServer bridge = new PhoneToolServer(name -> observed.incrementAndGet())) {
            Field field = PhoneToolServer.class.getDeclaredField("server");
            field.setAccessible(true);
            ServerSocket socket = (ServerSocket) field.get(bridge);
            check(socket.getInetAddress().isLoopbackAddress(), "Phone bridge is not loopback-only");
            log("Bridge binds loopback only");

            Response denied = request(bridge, "wrong-token", "home");
            check(denied.code == 401, "Unauthenticated call was not rejected: " + denied.code);
            check(observed.get() == 0, "Unauthenticated call reached the phone executor");
            Response unknown = request(bridge, bridge.token(), "shell");
            check(unknown.code == 400, "Unknown tool was not rejected: " + unknown.code);
            check(observed.get() == 0, "Unknown tool reached the phone executor");
            log("Invalid auth and unknown tool rejected before execution");

            Response outside = request(bridge, bridge.token(), "tap", new JSONObject().put("x", -1).put("y", -1));
            check(outside.code == 200 && !outside.body.optBoolean("ok"), "Out-of-screen gesture was not rejected");
            log("Out-of-screen gesture rejected");

            call(bridge, "home");
            String homePackage = resolve(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
            JSONObject home = waitForPackage(bridge, homePackage);
            check(!home.optBoolean("locked"), "Unlock the phone before running UI tests");
            check(home.optJSONArray("nodes") != null && home.getJSONArray("nodes").length() > 0,
                    "Home screen had no readable accessibility nodes");
            log("Home + read_screen: " + home.getString("package"));

            call(bridge, "open_settings");
            String settingsPackage = resolve(new Intent(Settings.ACTION_SETTINGS));
            JSONObject settings = waitForPackage(bridge, settingsPackage);
            check(settings.optJSONArray("nodes") != null && settings.getJSONArray("nodes").length() > 0,
                    "Settings screen had no readable accessibility nodes");
            log("Open settings + read_screen: " + settings.getString("package"));

            call(bridge, "back");
            // Existing settings activities can keep their own task stack. Confirm the
            // back request was accepted, then explicitly restore the home screen.
            SystemClock.sleep(500);
            JSONObject afterBack = call(bridge, "read_screen");
            check(!afterBack.optString("package").isEmpty(), "No active package after Back");
            log("Back accepted; active package: " + afterBack.getString("package"));
            call(bridge, "home");
            waitForPackage(bridge, homePackage);
        }
        log("Bridge closed after tests");
    }

    /** Real Claude Code + real phone tools, but a deterministic LOCAL test model. */
    private void loop() throws Exception {
        final long deadline = SystemClock.uptimeMillis() + 120000;
        long serviceDeadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable PhoneAgent accessibility service before loop test");
        LinuxRuntime runtime = new LinuxRuntime(getTargetContext());
        check(runtime.isInstalled(), "Run mode=runtime before mode=loop");
        List<String> calls = new CopyOnWriteArrayList<>();
        Process process = null;
        Thread reader = null;
        StringBuffer output = new StringBuffer();
        AtomicReference<JSONObject> finalResult = new AtomicReference<>();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        try (PhoneToolServer bridge = new PhoneToolServer(name -> {
            calls.add(name);
            log("Claude -> phone tool: " + name);
        })) {
            log("LOCAL TEST MODEL ONLY: this does not verify any external model service");
            process = runtime.startClaude("请先返回桌面并读取界面，然后打开系统设置并再次读取界面确认。",
                    "http://127.0.0.1:18080", "phoneagent-smoke-model", "local-test-only",
                    bridge.port(), bridge.token());
            final Process child = process;
            reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                            if (output.length() > 32000) output.delete(0, output.length() - 32000);
                        }
                        try {
                            JSONObject event = new JSONObject(line);
                            if ("result".equals(event.optString("type"))) finalResult.set(event);
                        } catch (Exception ignored) { }
                    }
                } catch (Throwable error) { readerFailure.set(error); }
            }, "loop-test-output");
            reader.setDaemon(true);
            reader.start();
            long remaining = Math.max(1, deadline - SystemClock.uptimeMillis());
            check(process.waitFor(remaining, TimeUnit.MILLISECONDS), "Claude loop exceeded 2 minutes\n" + output);
            reader.join(3000);
            check(process.exitValue() == 0, "Claude exited " + process.exitValue() + "\n" + output);
            check(readerFailure.get() == null, "Could not read Claude output: " + readerFailure.get());
            JSONObject result = finalResult.get();
            check(result != null, "Claude emitted no result event\n" + output);
            check(!result.optBoolean("is_error") && "success".equals(result.optString("subtype")),
                    "Claude loop did not succeed\n" + output);
            // Check the calls made by Claude before the test's own verification read.
            List<String> observed = new ArrayList<>(calls);
            String[] expected = {"home", "read_screen", "open_settings", "read_screen"};
            int matched = 0;
            for (String call : observed) if (matched < expected.length && expected[matched].equals(call)) matched++;
            check(matched == expected.length, "Claude did not execute the required tool sequence: " + observed + "\n" + output);
            JSONObject screen = waitForPackage(bridge, resolve(new Intent(Settings.ACTION_SETTINGS)));
            log("Actual screen verified: " + screen.getString("package"));
            log("Claude final result: " + result.optString("result"));
            log("Loop passed with deterministic local responses; external model remains unverified");
        } finally {
            if (process != null) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
            if (reader != null) reader.join(1000);
        }
    }

    /** Exercise the same parser/lifecycle/cancel path used by the app UI. */
    private void controller() throws Exception {
        long serviceDeadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable PhoneAgent accessibility service before controller test");
        AgentController controller = AgentController.get(getTargetContext());
        check(controller.isReady(), "Run mode=runtime before mode=controller");
        check(!controller.isRunning(), "An existing task is already running");
        ControllerProbe completed = new ControllerProbe();
        ControllerProbe stopped = new ControllerProbe();
        Field processField = AgentController.class.getDeclaredField("process");
        processField.setAccessible(true);
        try {
            log("Controller integration uses LOCAL fixed responses, not an external model");
            runOnMainSync(() -> {
                controller.setListener(completed);
                controller.run("请先返回桌面并读取界面，然后打开系统设置并再次读取界面确认。",
                        "http://127.0.0.1:18080", "phoneagent-smoke-model", "local-test-only");
            });
            check(completed.finished.await(120, TimeUnit.SECONDS),
                    "Controller did not finish in 2 minutes; status=" + completed.status.get() + "\n" + completed.output.get());
            check(completed.success.get(), "Controller reported failure: " + completed.message.get() + "\n" + completed.output.get());
            check(completed.output.get().contains("本地联调完成"), "Controller did not publish the parsed assistant output: " + completed.output.get());
            check(!controller.isRunning(), "Controller remained running after success callback");
            check(processField.get(controller) == null, "Controller retained a completed process");
            check(controller.getVisualState() == AgentController.VisualState.SUCCESS,
                    "Controller did not publish SUCCESS visual state: " + controller.getVisualState());
            assertStatesInOrder(completed, AgentController.VisualState.THINKING,
                    AgentController.VisualState.ACTING, AgentController.VisualState.THINKING,
                    AgentController.VisualState.SUCCESS);
            // Use an independent bridge only to verify the displayed package; it does
            // not participate in the Controller's model-driven execution.
            try (PhoneToolServer verification = new PhoneToolServer(name -> { })) {
                JSONObject screen = waitForPackage(verification, resolve(new Intent(Settings.ACTION_SETTINGS)));
                log("Controller output and actual Settings screen verified: " + screen.getString("package"));
            }

            runOnMainSync(() -> {
                controller.removeListener(completed);
                controller.setListener(stopped);
                controller.run("请读取当前界面。", "http://127.0.0.1:9",
                        "phoneagent-smoke-model", "local-test-only");
            });
            long launchDeadline = SystemClock.uptimeMillis() + 3000;
            while (controller.isRunning() && processField.get(controller) == null
                    && SystemClock.uptimeMillis() < launchDeadline) SystemClock.sleep(50);
            check(controller.isRunning(), "Unreachable-loopback task exited before Stop could be tested: " + stopped.message.get());
            log("Testing Stop with process present=" + (processField.get(controller) != null));
            runOnMainSync(controller::stop);
            check(stopped.finished.await(10, TimeUnit.SECONDS),
                    "Controller did not finish within 10 seconds of Stop; status=" + stopped.status.get());
            check(!stopped.success.get(), "Stopped task incorrectly reported success");
            check(stopped.message.get().contains("已停止"), "Stop callback did not report cancellation: " + stopped.message.get());
            check(!controller.isRunning(), "Controller remained running after Stop");
            check(processField.get(controller) == null, "Controller retained its process after Stop");
            check(controller.getVisualState() == AgentController.VisualState.STOPPED,
                    "Controller did not publish STOPPED visual state: " + controller.getVisualState());
            assertStatesInOrder(stopped, AgentController.VisualState.THINKING,
                    AgentController.VisualState.STOPPING, AgentController.VisualState.STOPPED);
            log("Controller Stop callback, running flag and process cleanup verified");
        } finally {
            runOnMainSync(() -> {
                controller.stop();
                controller.removeListener(completed);
                controller.removeListener(stopped);
            });
        }
    }

    /** Exercise real gestures and Chinese input only inside our own task composer. */
    private void input() throws Exception {
        long serviceDeadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable PhoneAgent accessibility service before input test");
        final String marker = "手机输入测试";
        try (PhoneToolServer bridge = new PhoneToolServer(name -> { })) {
            JSONObject screen = waitForPackage(bridge, getTargetContext().getPackageName());
            JSONObject composer = null;
            JSONArray nodes = screen.getJSONArray("nodes");
            int lowest = -1;
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                if (!node.optBoolean("editable") || !node.optBoolean("enabled")
                        || !node.optString("class").endsWith("EditText")
                        || node.optString("text").contains("密码输入框")) continue;
                JSONArray bounds = node.optJSONArray("bounds");
                if (bounds == null || bounds.length() != 4) continue;
                if (bounds.getInt(3) > lowest) { lowest = bounds.getInt(3); composer = node; }
            }
            check(composer != null, "No visible task input field found");
            JSONArray bounds = composer.getJSONArray("bounds");
            check(bounds.getInt(1) > screen.getInt("screen_height") / 2,
                    "Lowest editable field is not in the bottom composer; refusing to touch model settings");
            check(composer.optString("text").isEmpty() || composer.optString("text").contains("例如：打开系统设置"),
                    "Task composer already has a draft; refusing to replace it");
            boolean draftWritten = false;
            try {
                call(bridge, "tap", new JSONObject()
                        .put("x", (bounds.getInt(0) + bounds.getInt(2)) / 2)
                        .put("y", (bounds.getInt(1) + bounds.getInt(3)) / 2));
                SystemClock.sleep(400);
                call(bridge, "type_text", new JSONObject().put("text", marker));
                draftWritten = true;
                screen = waitForPackage(bridge, getTargetContext().getPackageName());
                check(hasEditableText(screen, marker), "Chinese task text was not visible after type_text");
                log("Tap callback and Chinese input verified in the task composer");
                call(bridge, "type_text", new JSONObject().put("text", ""));
                screen = waitForPackage(bridge, getTargetContext().getPackageName());
                check(!hasEditableText(screen, marker), "Test draft was not cleared");
                draftWritten = false;
                log("Test draft cleared");
            } finally {
                if (draftWritten) {
                    // Only clean up our own test marker, never an arbitrary focused
                    // model setting or another app's input field.
                    JSONObject current = call(bridge, "read_screen");
                    if (getTargetContext().getPackageName().equals(current.optString("package"))
                            && hasFocusedText(current, marker))
                        call(bridge, "type_text", new JSONObject().put("text", ""));
                }
            }
            call(bridge, "back");
            SystemClock.sleep(300);
            // If no IME was shown, Back can leave the activity. Bring our own app
            // back before selecting a swipe region; never swipe another app.
            runOnMainSync(() -> getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            waitForMainActivityVisible();
            screen = waitForPackage(bridge, getTargetContext().getPackageName());
            JSONArray scrollBounds = null;
            int largestArea = 0;
            nodes = screen.getJSONArray("nodes");
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                if (!node.optBoolean("scrollable") || !node.optString("class").endsWith("ScrollView")) continue;
                JSONArray candidate = node.getJSONArray("bounds");
                int area = (candidate.getInt(2) - candidate.getInt(0)) * (candidate.getInt(3) - candidate.getInt(1));
                if (area > largestArea) { largestArea = area; scrollBounds = candidate; }
            }
            check(scrollBounds != null, "No visible scrollable content area in MainActivity");
            int x = (scrollBounds.getInt(0) + scrollBounds.getInt(2)) / 2;
            int top = scrollBounds.getInt(1), bottom = scrollBounds.getInt(3);
            int start = top + (bottom - top) * 3 / 4;
            int end = top + (bottom - top) / 4;
            call(bridge, "swipe", new JSONObject().put("x1", x).put("y1", start)
                    .put("x2", x).put("y2", end).put("duration_ms", 400));
            waitForPackage(bridge, getTargetContext().getPackageName());
            log("Swipe completed in MainActivity's scrollable content; no task was submitted");
        }
    }

    private static boolean hasEditableText(JSONObject screen, String text) throws Exception {
        JSONArray nodes = screen.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node.optBoolean("editable") && text.equals(node.optString("text"))) return true;
        }
        return false;
    }

    /** Regression test for Huawei hiding ordinary overlays over system settings. */
    private void overlay() throws Exception {
        long serviceDeadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable PhoneAgent accessibility before overlay test");
        check(!AgentController.get(getTargetContext()).isRunning(), "Do not run overlay tests during an active task");
        try (PhoneToolServer bridge = new PhoneToolServer(name -> { })) {
            waitForPackage(bridge, getTargetContext().getPackageName());
            waitForBubble(false, "MainActivity");
            log("Bubble hidden while MainActivity is visible");

            call(bridge, "home");
            String homePackage = resolve(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
            waitForPackage(bridge, homePackage);
            waitForBubble(true, "Home");
            log("Home bubble is attached as TYPE_ACCESSIBILITY_OVERLAY");

            call(bridge, "open_settings");
            waitForPackage(bridge, resolve(new Intent(Settings.ACTION_SETTINGS)));
            BubbleState settingsBubble = waitForBubble(true, "System settings");
            log("Settings bubble is attached as TYPE_ACCESSIBILITY_OVERLAY; compositor visibility needs external verification");
            call(bridge, "tap", new JSONObject().put("x", settingsBubble.centerX).put("y", settingsBubble.centerY));
            waitForPackage(bridge, getTargetContext().getPackageName());
            waitForBubble(false, "MainActivity after bubble tap");
            log("Real tap on Settings bubble returned to MainActivity and hid the bubble");

            waitForIdleSync();
            SystemClock.sleep(350);
            clickControlSettings();
            waitForPackage(bridge, resolve(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            // Go directly Home, without resuming MainActivity, to reproduce the
            // stale activityVisible/suppressBubbleOnPause regression.
            call(bridge, "home");
            waitForPackage(bridge, homePackage);
            waitForBubble(true, "Home after accessibility settings");
            log("Bubble remains attached after control-settings -> direct Home; no model or settings values changed");
        }
    }

    private BubbleState waitForBubble(boolean attached, String stage) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 3000;
        BubbleState state = null;
        while (SystemClock.uptimeMillis() < deadline) {
            state = bubbleState();
            if (!attached && !state.attached) return state;
            if (attached && state.attached && state.shown && state.width > 0 && state.height > 0
                    && state.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) return state;
            SystemClock.sleep(150);
        }
        throw new AssertionError(stage + ": expected bubble attached=" + attached
                + (attached ? " with accessibility overlay type" : "") + ", got " + state);
    }

    /** Animate the real overlay view without running a model or changing Controller state. */
    private void pet() throws Exception {
        long serviceDeadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable PhoneAgent accessibility before pet test");
        check(!AgentController.get(getTargetContext()).isRunning(), "Do not run pet view tests during an active task");
        try (PhoneToolServer bridge = new PhoneToolServer(name -> { })) {
            call(bridge, "home");
            waitForPackage(bridge, resolve(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)));
            waitForBubble(true, "Pet on Home");
            AtomicReference<MonthlyCatView> reference = new AtomicReference<>();
            AtomicReference<Exception> failure = new AtomicReference<>();
            runOnMainSync(() -> {
                try {
                    Field instance = OverlayService.class.getDeclaredField("instance");
                    Field bubble = OverlayService.class.getDeclaredField("bubble");
                    instance.setAccessible(true); bubble.setAccessible(true);
                    Object view = bubble.get(instance.get(null));
                    if (!(view instanceof MonthlyCatView)) throw new IllegalStateException("Overlay is not a MonthlyCatView");
                    reference.set((MonthlyCatView) view);
                } catch (Exception error) { failure.set(error); }
            });
            if (failure.get() != null) throw failure.get();
            MonthlyCatView cat = reference.get();
            AtomicReference<AgentController.VisualState> original = new AtomicReference<>();
            runOnMainSync(() -> original.set(cat.getState()));
            AgentController.VisualState controllerBefore = AgentController.get(getTargetContext()).getVisualState();
            try {
                for (AgentController.VisualState state : AgentController.VisualState.values()) {
                    AtomicReference<AgentController.VisualState> observed = new AtomicReference<>();
                    AtomicInteger frame = new AtomicInteger(-1);
                    runOnMainSync(() -> {
                        cat.setState(state);
                        observed.set(cat.getState());
                        frame.set(cat.getFrameIndex());
                    });
                    check(observed.get() == state, "Pet view did not accept " + state);
                    check(frame.get() >= 0, "Pet view has invalid frame index for " + state);
                }
                log("MonthlyCatView accepts every VisualState");
                runOnMainSync(() -> cat.setState(AgentController.VisualState.THINKING));
                assertFramesAdvance(cat, "THINKING");
                runOnMainSync(() -> cat.setState(AgentController.VisualState.ACTING));
                assertFramesAdvance(cat, "ACTING");
                check(AgentController.get(getTargetContext()).getVisualState() == controllerBefore,
                        "Direct pet view test unexpectedly changed Controller state");
                log("Pet animation checked without a model call or any model-configuration access");
            } finally {
                runOnMainSync(() -> cat.setState(original.get()));
            }
            testMainPetScroll(bridge);
        }
    }

    private void testMainPetScroll(PhoneToolServer bridge) throws Exception {
        runOnMainSync(() -> getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        waitForMainActivityVisible();
        waitForPackage(bridge, getTargetContext().getPackageName());
        waitForIdleSync();
        SystemClock.sleep(350);
        AtomicReference<MainPetFixture> reference = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                MainActivity activity = resumedMain;
                if (activity == null) throw new IllegalStateException("No resumed MainActivity for scrolling test");
                Field petField = MainActivity.class.getDeclaredField("statePet");
                Field scrollField = MainActivity.class.getDeclaredField("contentScroll");
                petField.setAccessible(true); scrollField.setAccessible(true);
                MainPetFixture fixture = new MainPetFixture();
                fixture.pet = (MonthlyCatView) petField.get(activity);
                fixture.scroll = (ScrollView) scrollField.get(activity);
                fixture.content = fixture.scroll.getChildAt(0);
                fixture.minimumHeight = fixture.content.getMinimumHeight();
                fixture.scrollY = fixture.scroll.getScrollY();
                fixture.state = fixture.pet.getState();
                reference.set(fixture);
            } catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        MainPetFixture fixture = reference.get();
        try {
            runOnMainSync(() -> {
                fixture.pet.setState(AgentController.VisualState.THINKING);
                fixture.scroll.scrollTo(0, 0);
                Rect bounds = new Rect(0, 0, fixture.pet.getWidth(), fixture.pet.getHeight());
                ((ViewGroup) fixture.content).offsetDescendantRectToMyCoords(fixture.pet, bounds);
                fixture.hiddenScrollY = bounds.bottom + 32;
                int viewport = fixture.scroll.getHeight() - fixture.scroll.getPaddingTop() - fixture.scroll.getPaddingBottom();
                fixture.requiredContentHeight = viewport + fixture.hiddenScrollY;
                // Add only temporary blank scroll space when the current screen's
                // content is too short; do not alter any text, fields or preferences.
                fixture.content.setMinimumHeight(Math.max(fixture.minimumHeight, fixture.requiredContentHeight));
            });
            long layoutDeadline = SystemClock.uptimeMillis() + 2000;
            AtomicInteger height = new AtomicInteger();
            do {
                runOnMainSync(() -> height.set(fixture.content.getHeight()));
                if (height.get() >= fixture.requiredContentHeight) break;
                SystemClock.sleep(50);
            } while (SystemClock.uptimeMillis() < layoutDeadline);
            check(height.get() >= fixture.requiredContentHeight, "Scroll fixture did not finish layout");
            waitForPetVisibility(fixture.pet, true);
            assertFramesAdvance(fixture.pet, "MainActivity THINKING before scroll");
            runOnMainSync(() -> fixture.scroll.scrollTo(0, fixture.hiddenScrollY));
            waitForPetVisibility(fixture.pet, false);
            AtomicInteger hiddenFrame = new AtomicInteger();
            AtomicInteger laterFrame = new AtomicInteger();
            runOnMainSync(() -> hiddenFrame.set(fixture.pet.getFrameIndex()));
            SystemClock.sleep(400);
            runOnMainSync(() -> laterFrame.set(fixture.pet.getFrameIndex()));
            check(hiddenFrame.get() == laterFrame.get(), "Offscreen state cat kept advancing frames");
            runOnMainSync(() -> fixture.scroll.scrollTo(0, 0));
            waitForPetVisibility(fixture.pet, true);
            // Do not call setState/invalidate here: normal scrolling and drawing
            // must restart animation by themselves.
            assertFramesAdvance(fixture.pet, "MainActivity THINKING after scroll return");
            log("Actual MainActivity state cat resumes after scrolling out and back into view");
        } finally {
            runOnMainSync(() -> {
                fixture.pet.setState(fixture.state);
                fixture.content.setMinimumHeight(fixture.minimumHeight);
                fixture.scroll.scrollTo(0, fixture.scrollY);
            });
        }
    }

    private void waitForPetVisibility(MonthlyCatView cat, boolean expected) {
        long deadline = SystemClock.uptimeMillis() + 2000;
        AtomicBoolean visible = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> visible.set(cat.isShown() && cat.getGlobalVisibleRect(new Rect())));
            if (visible.get() == expected) return;
            SystemClock.sleep(60);
        }
        throw new AssertionError("MainActivity state cat visibility did not become " + expected);
    }

    private static final class MainPetFixture {
        MonthlyCatView pet;
        ScrollView scroll;
        View content;
        AgentController.VisualState state;
        int minimumHeight, scrollY, hiddenScrollY, requiredContentHeight;
    }

    private void assertFramesAdvance(MonthlyCatView cat, String label) {
        AtomicInteger frame = new AtomicInteger();
        runOnMainSync(() -> frame.set(cat.getFrameIndex()));
        int initial = frame.get();
        long deadline = SystemClock.uptimeMillis() + 1800;
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(60);
            runOnMainSync(() -> frame.set(cat.getFrameIndex()));
            if (frame.get() != initial) {
                log(label + " pet animation advanced from frame " + initial + " to " + frame.get());
                return;
            }
        }
        throw new AssertionError(label + " pet animation did not advance while attached and visible");
    }

    private static void assertStatesInOrder(ControllerProbe probe, AgentController.VisualState... expected) {
        int matched = 0;
        for (AgentController.VisualState state : probe.states)
            if (matched < expected.length && expected[matched] == state) matched++;
        check(matched == expected.length, "Controller visual-state callbacks missing/out of order: " + probe.states);
    }

    private BubbleState bubbleState() throws Exception {
        AtomicReference<BubbleState> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                Field instanceField = OverlayService.class.getDeclaredField("instance");
                Field bubbleField = OverlayService.class.getDeclaredField("bubble");
                Field positionField = OverlayService.class.getDeclaredField("position");
                instanceField.setAccessible(true); bubbleField.setAccessible(true); positionField.setAccessible(true);
                Object service = instanceField.get(null);
                View bubble = service == null ? null : (View) bubbleField.get(service);
                WindowManager.LayoutParams position = service == null ? null
                        : (WindowManager.LayoutParams) positionField.get(service);
                BubbleState state = new BubbleState();
                if (bubble != null) {
                    state.attached = bubble.isAttachedToWindow();
                    state.shown = bubble.isShown();
                    state.width = bubble.getWidth(); state.height = bubble.getHeight();
                    int[] location = new int[2]; bubble.getLocationOnScreen(location);
                    state.centerX = location[0] + state.width / 2;
                    state.centerY = location[1] + state.height / 2;
                    if (bubble.getLayoutParams() instanceof WindowManager.LayoutParams)
                        state.type = ((WindowManager.LayoutParams) bubble.getLayoutParams()).type;
                    else if (position != null) state.type = position.type;
                }
                result.set(state);
            } catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private void clickControlSettings() throws Exception {
        AtomicBoolean clicked = new AtomicBoolean();
        AtomicReference<Exception> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                MainActivity activity = resumedMain;
                if (activity == null) throw new IllegalStateException("No resumed MainActivity for control-button test");
                Field field = MainActivity.class.getDeclaredField("control");
                field.setAccessible(true);
                View control = (View) field.get(activity);
                if (control == null || !control.isEnabled())
                    throw new IllegalStateException("Phone-control settings button is not enabled");
                // This stage tests the settings-navigation/onPause regression, not
                // hit testing during the activity's return animation. Invoke the
                // actual button listener on the main thread deterministically.
                clicked.set(control.performClick());
            } catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        check(clicked.get(), "Phone-control settings button did not handle its click");
    }

    private static final class BubbleState {
        boolean attached, shown;
        int type = -1, centerX, centerY, width, height;
        @Override public String toString() {
            return "attached=" + attached + ", shown=" + shown + ", type=" + type
                    + ", size=" + width + "x" + height;
        }
    }

    private static boolean hasFocusedText(JSONObject screen, String text) throws Exception {
        JSONArray nodes = screen.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node.optBoolean("editable") && node.optBoolean("focused") && text.equals(node.optString("text"))) return true;
        }
        return false;
    }

    private String resolve(Intent intent) {
        ResolveInfo info = getTargetContext().getPackageManager().resolveActivity(intent, 0);
        check(info != null && info.activityInfo != null, "No activity handles " + intent.getAction());
        return info.activityInfo.packageName;
    }

    private JSONObject waitForPackage(PhoneToolServer bridge, String expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 3000;
        JSONObject screen = new JSONObject();
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(250);
            Response response = request(bridge, bridge.token(), "read_screen");
            check(response.code == 200, "Read screen HTTP status " + response.code);
            screen = response.body;
            if (screen.optBoolean("ok") && expected.equals(screen.optString("package"))) return screen;
        }
        throw new AssertionError("Expected active package " + expected + ", got " + screen.optString("package")
                + "; error=" + screen.optString("error"));
    }

    private JSONObject call(PhoneToolServer bridge, String tool) throws Exception {
        return call(bridge, tool, new JSONObject());
    }

    private JSONObject call(PhoneToolServer bridge, String tool, JSONObject args) throws Exception {
        Response response = request(bridge, bridge.token(), tool, args);
        check(response.code == 200, tool + " HTTP status: " + response.code);
        check(response.body.optBoolean("ok"), tool + " failed: " + response.body.optString("error"));
        return response.body;
    }

    private Response request(PhoneToolServer bridge, String token, String tool) throws Exception {
        return request(bridge, token, tool, new JSONObject());
    }

    private Response request(PhoneToolServer bridge, String token, String tool, JSONObject args) throws Exception {
        byte[] body = new JSONObject().put("name", tool).put("arguments", args).toString().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), bridge.port())) {
            socket.setSoTimeout(12000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /tool HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            InputStream input = socket.getInputStream();
            String status = httpLine(input);
            check(status != null && status.startsWith("HTTP/1.1 "), "Missing HTTP response");
            int code = Integer.parseInt(status.split(" ")[1]);
            String line;
            int length = -1;
            while (!(line = httpLine(input)).isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && "Content-Length".equalsIgnoreCase(line.substring(0, colon).trim()))
                    length = Integer.parseInt(line.substring(colon + 1).trim());
            }
            check(length >= 0 && length <= 2 * 1024 * 1024, "Invalid HTTP response Content-Length: " + length);
            byte[] response = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(response, offset, length - offset);
                if (count < 0) throw new EOFException("Response ended before Content-Length");
                offset += count;
            }
            // Do not wait for EOF: a rejected request can legitimately close with
            // unread request-body bytes and reset the socket after its full reply.
            return new Response(code, new JSONObject(new String(response, StandardCharsets.UTF_8)));
        }
    }

    private static String httpLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() >= 8192) throw new IOException("HTTP response header too long");
            if (value != '\r') bytes.write(value);
        }
        if (value == -1) throw new EOFException("Missing HTTP response header");
        return bytes.toString("US-ASCII");
    }

    private synchronized void log(String message) {
        report.append(message).append('\n');
        Bundle status = new Bundle();
        status.putString("stream", message + "\n");
        sendStatus(0, status);
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }

    private static final class Response {
        final int code;
        final JSONObject body;
        Response(int code, JSONObject body) { this.code = code; this.body = body; }
    }

    private static final class ControllerProbe implements AgentController.Listener {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();
        final AtomicReference<String> status = new AtomicReference<>("");
        final AtomicReference<String> output = new AtomicReference<>("");
        final AtomicReference<String> message = new AtomicReference<>("");
        final List<AgentController.VisualState> states = new CopyOnWriteArrayList<>();
        @Override public void onVisualState(AgentController.VisualState state) { states.add(state); }
        @Override public void onStatus(String value) { status.set(value); }
        @Override public void onOutput(String value) { output.set(value); }
        @Override public void onFinished(boolean ok, String value) {
            success.set(ok);
            message.set(value);
            finished.countDown();
        }
    }
}
