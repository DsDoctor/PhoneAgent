package com.ds.phoneagent;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
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
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                target.startForegroundService(new Intent(target, OverlayService.class));
            });
            waitForIdleSync();
            SystemClock.sleep(400);
            log("Test started with MainActivity visible, foreground service and partial wake lock");
            if ("runtime".equals(mode)) runtime();
            else if ("tools".equals(mode)) tools();
            else if ("loop".equals(mode)) loop();
            else if ("controller".equals(mode)) controller();
            else if ("input".equals(mode)) input();
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
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
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
        @Override public void onStatus(String value) { status.set(value); }
        @Override public void onOutput(String value) { output.set(value); }
        @Override public void onFinished(boolean ok, String value) {
            success.set(ok);
            message.set(value);
            finished.countDown();
        }
    }
}
