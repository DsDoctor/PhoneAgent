package com.ds.phoneagent;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.TableLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Sideload-only test APK. No command receiver is added to the actual app. */
public final class TestInstrumentation extends Instrumentation {
    private String mode;
    private String interactionFixtureId;
    private boolean interactionStopOnly;
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
        interactionFixtureId = arguments == null ? "" : arguments.getString("fixture_id", "");
        if (!interactionFixtureId.matches("[0-9a-f]{32}")) interactionFixtureId = UUID.randomUUID().toString().replace("-", "");
        interactionStopOnly = arguments != null && "stop".equals(arguments.getString("interaction_stage", ""));
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
            else if ("routines".equals(mode)) routines();
            else if ("longtask".equals(mode)) longTask();
            else if ("taskstore".equals(mode)) taskStore();
            else if ("history".equals(mode)) history();
            else if ("resume".equals(mode)) resume();
            else if ("markdown".equals(mode)) markdown();
            else if ("delete".equals(mode)) deleteHistory();
            else if ("historyui".equals(mode)) historyUi();
            else if ("interaction".equals(mode)) interaction();
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

    /** Real Linux Python/MCP and phone actions, with no model process or model configuration. */
    private void routines() throws Exception {
        long serviceDeadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable PhoneAgent accessibility before routines test");
        check(!AgentController.get(getTargetContext()).isRunning(), "Do not test routines during an active task");
        LinuxRuntime runtime = new LinuxRuntime(getTargetContext());
        check(runtime.isInstalled(), "Run mode=runtime before mode=routines");
        runtime.install(this::log); // Refresh bundled MCP modules without reinstalling a ready rootfs.
        String suffix = UUID.randomUUID().toString();
        String name = "codex_test_" + suffix.substring(0, 8);
        String folder = "routines-smoke-" + suffix;
        File isolated = new File(getTargetContext().getFilesDir(), "linux/home/.phoneagent/" + folder);
        check(!isolated.exists(), "Unique routines test directory already exists");
        long deadline = SystemClock.uptimeMillis() + 120000;
        try (PhoneToolServer bridge = new PhoneToolServer(tool -> { })) {
            Map<String, String> environment = new HashMap<>();
            environment.put("PHONE_AGENT_PORT", Integer.toString(bridge.port()));
            environment.put("PHONE_AGENT_TOKEN", bridge.token());
            environment.put("PHONE_AGENT_ROUTINES_DIR", "/home/agent/.phoneagent/" + folder);
            JSONObject appArguments = new JSONObject().put("package_name", "com.android.settings").put("timeout_ms", 8000);
            try (McpClient first = new McpClient(startMcp(runtime, environment), deadline)) {
                first.initialize();
                first.requireTools("open_app_ready", "read_screen", "list_routines", "save_routine", "run_routine");
                JSONObject empty = first.tool("list_routines", new JSONObject());
                check(empty.getJSONArray("routines").length() == 0, "Isolated routine store was not empty");
                check(isolated.isDirectory(), "MCP did not honor PHONE_AGENT_ROUTINES_DIR; refusing to continue");
                JSONArray proofs = new JSONArray();
                for (int i = 0; i < 2; i++) {
                    JSONObject opened = first.tool("open_app_ready", appArguments);
                    check("ready".equals(opened.optString("status")), "Settings did not become ready");
                    check("com.android.settings".equals(opened.getJSONObject("screen").optString("package")),
                            "Ready result did not observe Settings");
                    String actionReceipt = opened.getString("routine_receipt");
                    JSONObject observed = first.tool("read_screen", new JSONObject());
                    check("com.android.settings".equals(observed.optString("package")), "Proof did not observe Settings");
                    check(screenContainsText(observed, "设置"), "Proof did not contain Settings text");
                    proofs.put(new JSONObject().put("step_receipts", new JSONArray().put(actionReceipt))
                            .put("verification_receipt", observed.getString("routine_receipt")));
                }
                JSONObject saved = first.tool("save_routine", new JSONObject().put("name", name)
                        .put("description", "Isolated device MCP smoke test")
                        .put("steps", new JSONArray().put(new JSONObject().put("tool", "open_app_ready").put("arguments", appArguments)))
                        .put("verify", new JSONObject().put("package", "com.android.settings").put("text_contains", new JSONArray().put("设置")))
                        .put("proofs", proofs));
                check(name.equals(saved.optString("name")), "MCP saved an unexpected routine name");
                JSONObject executed = first.tool("run_routine", new JSONObject().put("name", name).put("timeout_ms", 20000));
                check(executed.optInt("completed_steps") == 1, "Saved routine did not complete its single step");
                JSONObject actual = first.tool("read_screen", new JSONObject());
                check("com.android.settings".equals(actual.optString("package")) && screenContainsText(actual, "设置"),
                        "Routine did not leave the actual Settings screen visible");
                log("Real MCP verified two launches, saved the isolated routine, and executed it successfully");
            }
            try (McpClient second = new McpClient(startMcp(runtime, environment), deadline)) {
                second.initialize();
                JSONObject listed = second.tool("list_routines", new JSONObject());
                JSONArray routines = listed.getJSONArray("routines");
                boolean found = false;
                for (int i = 0; i < routines.length(); i++)
                    if (name.equals(routines.getJSONObject(i).optString("name"))) found = true;
                check(found, "A fresh MCP process could not list the saved routine");
                JSONArray candidates = listed.getJSONArray("candidates");
                boolean candidateFound = false;
                for (int i = 0; i < candidates.length(); i++) {
                    JSONObject candidate = candidates.getJSONObject(i);
                    JSONObject arguments = candidate.optJSONObject("arguments");
                    if (arguments != null && "com.android.settings".equals(arguments.optString("package_name"))
                            && candidate.optInt("success_count") >= 2) candidateFound = true;
                }
                check(candidateFound, "A fresh MCP process lost the repeated-launch candidate count");
                log("Fresh MCP process listed the persisted routine and repeated-launch candidate");
            }
        } finally {
            runtime.cancel();
            File expectedParent = new File(getTargetContext().getFilesDir(), "linux/home/.phoneagent");
            check(isolated.getName().equals(folder) && isolated.getParentFile().equals(expectedParent),
                    "Refusing to clean an unexpected routine path");
            deleteTestTree(isolated);
        }
        check(!isolated.exists(), "Isolated routines test directory was not removed");
        log("Only the unique test store was removed; user routines and candidates were never accessed");
    }

    private Process startMcp(LinuxRuntime runtime, Map<String, String> environment) throws Exception {
        Field root = LinuxRuntime.class.getDeclaredField("root");
        Field epoch = LinuxRuntime.class.getDeclaredField("cancellationEpoch");
        Method start = LinuxRuntime.class.getDeclaredMethod("start", List.class, Map.class, File.class, long.class);
        root.setAccessible(true); epoch.setAccessible(true); start.setAccessible(true);
        try {
            return (Process) start.invoke(runtime, Arrays.asList("/usr/bin/python3", "/opt/phoneagent/phone_mcp.py"),
                    environment, (File) root.get(runtime), ((AtomicLong) epoch.get(runtime)).get());
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }

    private static boolean screenContainsText(JSONObject screen, String expected) throws Exception {
        JSONArray nodes = screen.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node.optString("text").contains(expected) || node.optString("description").contains(expected)) return true;
        }
        return false;
    }

    private static void deleteTestTree(File path) throws IOException {
        boolean link = java.nio.file.Files.isSymbolicLink(path.toPath());
        if (!path.exists() && !link) return;
        if (!link && path.isDirectory()) {
            File[] children = path.listFiles();
            if (children == null) throw new IOException("Cannot inspect isolated test directory");
            for (File child : children) deleteTestTree(child);
        }
        if (!path.delete()) throw new IOException("Cannot remove isolated test artifact: " + path.getName());
    }

    private static final class McpClient implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final Thread reader;
        private final LinkedBlockingQueue<JSONObject> replies = new LinkedBlockingQueue<>();
        private final StringBuffer diagnostics = new StringBuffer();
        private final long deadline;
        private int sequence;

        McpClient(Process process, long deadline) {
            this.process = process;
            this.deadline = deadline;
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        try { replies.offer(new JSONObject(line)); }
                        catch (Exception ignored) {
                            synchronized (diagnostics) {
                                diagnostics.append(line).append('\n');
                                if (diagnostics.length() > 4000) diagnostics.delete(0, diagnostics.length() - 4000);
                            }
                        }
                    }
                } catch (IOException failure) { diagnostics.append(failure.getClass().getSimpleName()); }
            }, "device-mcp-output");
            reader.setDaemon(true);
            reader.start();
        }

        void initialize() throws Exception {
            JSONObject result = rpc("initialize", new JSONObject().put("protocolVersion", "2024-11-05")
                    .put("capabilities", new JSONObject()).put("clientInfo", new JSONObject().put("name", "device-smoke").put("version", "1")));
            check(result.has("serverInfo"), "MCP initialize did not return server information");
            write(new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
        }

        void requireTools(String... expected) throws Exception {
            JSONArray tools = rpc("tools/list", new JSONObject()).getJSONArray("tools");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < tools.length(); i++) names.add(tools.getJSONObject(i).getString("name"));
            for (String name : expected) check(names.contains(name), "Real MCP did not advertise " + name);
        }

        JSONObject tool(String name, JSONObject arguments) throws Exception {
            JSONObject result = rpc("tools/call", new JSONObject().put("name", name).put("arguments", arguments));
            JSONArray content = result.getJSONArray("content");
            JSONObject value = null;
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) {
                    try { value = new JSONObject(block.getString("text")); } catch (Exception ignored) { }
                    if (value != null) break;
                }
            }
            check(value != null, "MCP " + name + " did not return a JSON result");
            check(!result.optBoolean("isError") && value.optBoolean("ok"), "MCP " + name + " failed: " + value.optString("error"));
            return value;
        }

        private JSONObject rpc(String method, JSONObject params) throws Exception {
            int id = ++sequence;
            write(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params));
            long responseDeadline = Math.min(deadline, SystemClock.uptimeMillis() + 35000);
            while (SystemClock.uptimeMillis() < responseDeadline) {
                JSONObject reply = replies.poll(Math.min(500, Math.max(1, responseDeadline - SystemClock.uptimeMillis())), TimeUnit.MILLISECONDS);
                if (reply == null) {
                    if (!process.isAlive()) throw new IOException("MCP process exited before " + method + ": " + diagnostics);
                    continue;
                }
                if (reply.optInt("id", -1) != id) continue;
                check(!reply.has("error"), "MCP protocol rejected " + method + ": " + reply.optJSONObject("error"));
                return reply.getJSONObject("result");
            }
            throw new IOException("MCP timed out during " + method + ": " + diagnostics);
        }

        private void write(JSONObject message) throws IOException {
            writer.write(message.toString());
            writer.newLine();
            writer.flush();
        }

        @Override public void close() throws Exception {
            try { writer.close(); } catch (IOException ignored) { }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            reader.join(1000);
        }
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
        ControllerProbe failed = new ControllerProbe();
        ControllerProbe noResult = new ControllerProbe();
        List<String> changedModelKeys = new CopyOnWriteArrayList<>();
        SharedPreferences settings = getTargetContext().getSharedPreferences("phoneagent", Context.MODE_PRIVATE);
        SharedPreferences.OnSharedPreferenceChangeListener configurationObserver = (preferences, key) -> {
            // Observe change notifications only: never read model values or credentials.
            if (key == null || key.equals("base_url") || key.equals("model") || key.equals("key_ciphertext") || key.equals("key_iv"))
                changedModelKeys.add(key == null ? "<clear>" : key);
        };
        settings.registerOnSharedPreferenceChangeListener(configurationObserver);
        Field processField = AgentController.class.getDeclaredField("process");
        processField.setAccessible(true);
        try {
            log("Controller integration uses LOCAL fixed responses, not an external model");
            runOnMainSync(() -> {
                controller.setListener(completed);
                controller.run("请先返回桌面并读取界面，然后打开系统设置并再次读取界面确认。",
                        "http://127.0.0.1:18080", "phoneagent-smoke-ready", "local-test-only");
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
            assertFinalTaskLog(controller, completed, "success", "success");
            // Use an independent bridge only to verify the displayed package; it does
            // not participate in the Controller's model-driven execution.
            try (PhoneToolServer verification = new PhoneToolServer(name -> { })) {
                JSONObject screen = waitForPackage(verification, resolve(new Intent(Settings.ACTION_SETTINGS)));
                log("Controller output and actual Settings screen verified: " + screen.getString("package"));
            }
            assertTerminalReset(controller, completed, "SUCCESS");

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
            assertRunningResetRejected(controller, stopped);
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
            assertFinalTaskLog(controller, stopped, "stopped", "user_stop");
            log("Controller Stop callback, running flag and process cleanup verified");
            assertTerminalReset(controller, stopped, "STOPPED");

            runOnMainSync(() -> {
                controller.removeListener(stopped);
                controller.setListener(failed);
                controller.run("本地错误恢复测试，不需要操作手机。", "http://127.0.0.1:18080",
                        "phoneagent-smoke-error", "local-test-only");
            });
            check(failed.finished.await(90, TimeUnit.SECONDS), "Intentional local error task did not finish");
            check(!failed.success.get() && controller.getVisualState() == AgentController.VisualState.ERROR,
                    "Local fixture did not produce Controller ERROR: " + failed.message.get());
            check(failed.output.get().contains("HTTP 状态：400"),
                    "Local HTTP 400 did not produce a safe status hint in the final log");
            assertFinalTaskLog(controller, failed, "error", "error_result");
            assertErrorResetButton(controller);

            runOnMainSync(() -> {
                controller.setListener(noResult);
                controller.run("本地CLI中断日志测试，不需要操作手机。", "http://127.0.0.1:9",
                        "phoneagent-smoke-model", "local-test-only");
            });
            long processDeadline = SystemClock.uptimeMillis() + 5000;
            Process interrupted = null;
            while (SystemClock.uptimeMillis() < processDeadline && controller.isRunning()) {
                interrupted = (Process) processField.get(controller);
                if (interrupted != null && interrupted.isAlive()) break;
                SystemClock.sleep(50);
            }
            check(interrupted != null && interrupted.isAlive(), "No live CLI process for missing-result fault injection");
            interrupted.destroyForcibly(); // Deliberately not Controller.stop(): no user-stop flag.
            check(noResult.finished.await(15, TimeUnit.SECONDS), "Controller did not finish after CLI termination");
            check(!noResult.success.get() && !controller.isRunning(), "Terminated CLI incorrectly remained active/succeeded");
            assertFinalTaskLog(controller, noResult, "error", "cli_no_result");
            assertTerminalReset(controller, noResult, "CLI_NO_RESULT");
            check(changedModelKeys.isEmpty(), "Controller/reset modified model configuration keys: " + changedModelKeys);
            log("SUCCESS, STOPPED and UI ERROR reset preserved output and model configuration; running reset was rejected");
        } finally {
            runOnMainSync(() -> {
                controller.stop();
                controller.removeListener(completed);
                controller.removeListener(stopped);
                controller.removeListener(failed);
                controller.removeListener(noResult);
            });
            settings.unregisterOnSharedPreferenceChangeListener(configurationObserver);
        }
    }

    private void assertRunningResetRejected(AgentController controller, ControllerProbe probe) throws Exception {
        check(controller.isRunning() && !controller.canResetToIdle(), "Running task incorrectly permits reset");
        String beforeOutput = controllerOutput(controller);
        AgentController.VisualState beforeState = controller.getVisualState();
        int beforeFinished = probe.finishedCallbacks.get();
        runOnMainSync(controller::resetToIdle);
        // A second main-thread barrier waits for reset's posted no-op to execute.
        runOnMainSync(() -> { });
        check(controller.isRunning(), "Reset interrupted a running task");
        check(controller.getVisualState() == beforeState, "Reset changed a running task's visual state");
        check(beforeOutput.equals(controllerOutput(controller)), "Reset changed a running task's output");
        check(probe.finishedCallbacks.get() == beforeFinished, "Running reset fabricated a completion callback");
        log("Running task rejects reset without changing task, state or output");
    }

    private void assertTerminalReset(AgentController controller, ControllerProbe probe, String source) throws Exception {
        check(!controller.isRunning() && controller.canResetToIdle(), source + " should permit reset");
        String beforeOutput = controllerOutput(controller);
        String beforeRecord = lastTaskSnapshot();
        int beforeFinished = probe.finishedCallbacks.get();
        runOnMainSync(controller::resetToIdle);
        waitForControllerState(controller, AgentController.VisualState.IDLE);
        check(!controller.isRunning(), source + " reset started a task");
        check(beforeOutput.equals(controllerOutput(controller)), source + " reset changed the retained output");
        check(probe.finishedCallbacks.get() == beforeFinished, source + " reset fabricated a completion callback");
        check(beforeRecord.equals(lastTaskSnapshot()), source + " reset modified the persisted task record");
        requireAcknowledgedRecord(source);
        log(source + " reset to IDLE while retaining task output");
    }

    private void assertErrorResetButton(AgentController controller) throws Exception {
        String beforeOutput = controllerOutput(controller);
        String beforeRecord = lastTaskSnapshot();
        runOnMainSync(() -> getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        waitForMainActivityVisible();
        AtomicReference<Exception> failure = new AtomicReference<>();
        // The test owned the single Listener while collecting the CLI result. Hand
        // it back to MainActivity's actual Listener before exercising its button.
        runOnMainSync(() -> {
            try {
                Field listener = MainActivity.class.getDeclaredField("listener");
                listener.setAccessible(true);
                controller.setListener((AgentController.Listener) listener.get(resumedMain));
            } catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        runOnMainSync(() -> { });
        AtomicBoolean clicked = new AtomicBoolean();
        runOnMainSync(() -> {
            try {
                Field field = MainActivity.class.getDeclaredField("stop");
                field.setAccessible(true);
                TextView stop = (TextView) field.get(resumedMain);
                if (!stop.isShown() || !stop.isEnabled() || !"重置".contentEquals(stop.getText()))
                    throw new IllegalStateException("ERROR state did not expose an enabled reset action on the stop button");
                clicked.set(stop.performClick());
            } catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        check(clicked.get(), "Reset button did not handle click");
        waitForControllerState(controller, AgentController.VisualState.IDLE);
        check(!controller.isRunning(), "UI reset started a task");
        check(beforeOutput.equals(controllerOutput(controller)), "UI ERROR reset changed retained output");
        check(beforeRecord.equals(lastTaskSnapshot()), "UI ERROR reset modified the persisted task record");
        requireAcknowledgedRecord("UI ERROR");
        AtomicBoolean enabled = new AtomicBoolean(true);
        runOnMainSync(() -> {
            try {
                Field field = MainActivity.class.getDeclaredField("stop");
                field.setAccessible(true);
                enabled.set(((View) field.get(resumedMain)).isEnabled());
            } catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        check(!enabled.get(), "Reset action remains enabled after reaching IDLE");
        log("Actual stop/reset button changed ERROR to IDLE without clearing task output");
    }

    private void assertFinalTaskLog(AgentController controller, ControllerProbe probe, String state, String reason) throws Exception {
        String output = controllerOutput(controller);
        check(output.contains("【任务结束】"), state + " did not publish a final task log");
        check(output.equals(probe.output.get()), state + " final log was not delivered to the listener");
        JSONObject record = new LastTaskStore(getTargetContext()).read();
        check(record != null, state + " task record was not persisted");
        check(state.equals(record.optString("state")), "Persisted task state mismatch: " + record.optString("state"));
        check(reason.equals(record.optString("reason")), "Persisted termination reason mismatch: " + record.optString("reason"));
        check(!record.optBoolean("acknowledged"), "A newly finished task was already marked acknowledged");
        check(record.optLong("started_at") > 0 && record.optLong("ended_at") > 0
                        && record.optLong("ended_at") >= record.optLong("started_at"),
                "Final task record has no valid completion time");
        check(output.equals(record.optString("output")), "Persisted task output differs from final UI output");
        for (String forbidden : new String[]{"prompt", "model", "base_url", "baseUrl", "api_key", "apiKey", "key"})
            check(!record.has(forbidden), "Task record unexpectedly includes configuration field: " + forbidden);
        log("Final task log persisted: " + state + " / " + reason);
    }

    private String lastTaskSnapshot() throws Exception {
        JSONObject record = new LastTaskStore(getTargetContext()).read();
        check(record != null, "No task record to preserve across reset");
        return historicalRecordSnapshot(record);
    }

    private static String historicalRecordSnapshot(JSONObject record) throws Exception {
        JSONObject historical = new JSONObject(record.toString());
        historical.remove("acknowledged");
        return historical.toString();
    }

    private void requireAcknowledgedRecord(String source) {
        JSONObject record = new LastTaskStore(getTargetContext()).read();
        check(record != null && record.optBoolean("acknowledged"), source + " reset did not persist acknowledgment");
    }

    /** More than the old thirty-turn limit, using only read_screen and a local fixture. */
    private void longTask() throws Exception {
        long serviceDeadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable accessibility before longtask test");
        AgentController controller = AgentController.get(getTargetContext());
        check(controller.isReady() && !controller.isRunning(), "Runtime must be ready and idle");
        try (PhoneToolServer verification = new PhoneToolServer(name -> { })) {
            waitForPackage(verification, getTargetContext().getPackageName());
        }
        ControllerProbe probe = new ControllerProbe();
        try {
            runOnMainSync(() -> {
                controller.setListener(probe);
                controller.run("执行本地31轮只读联调，每轮读取当前界面，最后报告完成。",
                        "http://127.0.0.1:18080", "phoneagent-smoke-long", "local-test-only");
            });
            check(probe.finished.await(180, TimeUnit.SECONDS), "31-read fixture did not finish within the test's own timeout");
            check(probe.success.get() && !controller.isRunning(), "31-read task did not complete: " + probe.message.get());
            check(probe.output.get().contains("31轮只读联调完成"), "CLI did not reach the fixture's final response");
            int actions = 0;
            for (AgentController.VisualState state : probe.states)
                if (state == AgentController.VisualState.ACTING) actions++;
            check(actions >= 31, "Observed fewer than 31 actual phone-tool executions: " + actions);
            assertFinalTaskLog(controller, probe, "success", "success");
            log("Real Claude Code completed at least 31 read-only tool turns with the local fixture");
        } finally {
            runOnMainSync(() -> { controller.stop(); controller.removeListener(probe); });
        }
    }

    /** File-backed restart/recovery checks in a unique cache directory, not user task data. */
    private void taskStore() throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "taskstore-smoke-" + UUID.randomUUID());
        check(!directory.exists(), "Unique task-store test directory already exists");
        Context isolated = new ContextWrapper(getTargetContext()) {
            @Override public File getFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
        ControllerProbe restoredProbe = new ControllerProbe();
        ControllerProbe acknowledgedProbe = new ControllerProbe();
        ControllerProbe interruptedProbe = new ControllerProbe();
        AgentController restored = null, acknowledged = null, interrupted = null;
        try {
            LastTaskStore store = new LastTaskStore(directory);
            long now = System.currentTimeMillis();
            String successOutput = "本地持久化测试输出\n\n【任务结束】\n状态：完成\n原因：success";
            JSONObject complete = new JSONObject().put("version", 1).put("state", "success")
                    .put("status", "本地测试已完成").put("output", successOutput)
                    .put("started_at", now - 2000).put("updated_at", now - 1000).put("ended_at", now - 1000)
                    .put("reason", "success").put("exit_code", 0).put("num_turns", 32);
            store.write(complete);
            JSONObject reread = new LastTaskStore(directory).read();
            check(reread != null && successOutput.equals(reread.optString("output")), "Rebuilt store lost the saved output");
            restored = constructIsolatedController(isolated);
            AgentController restoredController = restored;
            runOnMainSync(() -> restoredController.setListener(restoredProbe));
            runOnMainSync(() -> { });
            check(!restored.isRunning() && restored.getVisualState() == AgentController.VisualState.SUCCESS,
                    "Rebuilt Controller did not restore completed task state");
            check(successOutput.equals(restoredProbe.output.get()), "Rebuilt Controller did not replay retained output");
            check(complete.getString("status").equals(restoredProbe.status.get()), "Rebuilt Controller did not replay retained status");
            log("Isolated store and Controller restored the completed task");

            String errorOutput = "本地错误测试输出\n\n【任务结束】\n状态：未完成\n原因：error_result";
            JSONObject error = new JSONObject(complete.toString()).put("state", "error")
                    .put("status", "本地错误测试已结束").put("output", errorOutput)
                    .put("reason", "error_result").put("exit_code", 1).put("acknowledged", false);
            store.write(error);
            String historicalError = historicalRecordSnapshot(store.read());
            check(store.acknowledge(), "Isolated store could not acknowledge the terminal error");
            JSONObject acknowledgedRecord = new LastTaskStore(directory).read();
            check(acknowledgedRecord != null && acknowledgedRecord.optBoolean("acknowledged"), "Acknowledgment did not survive store reconstruction");
            check(historicalError.equals(historicalRecordSnapshot(acknowledgedRecord)),
                    "Acknowledgment changed historical task fields beyond its flag");
            acknowledged = constructIsolatedController(isolated);
            AgentController acknowledgedController = acknowledged;
            runOnMainSync(() -> acknowledgedController.setListener(acknowledgedProbe));
            runOnMainSync(() -> { });
            AgentController.VisualState acknowledgedState = acknowledged.getVisualState();
            check(!acknowledged.isRunning() && (acknowledgedState == AgentController.VisualState.IDLE
                            || acknowledgedState == AgentController.VisualState.NEEDS_INPUT),
                    "Rebuilt acknowledged Controller restored the old error presentation");
            check(errorOutput.equals(acknowledgedProbe.output.get()), "Acknowledged restoration lost the historical error log");
            check(historicalError.equals(historicalRecordSnapshot(new LastTaskStore(directory).read())),
                    "Acknowledged Controller restoration rewrote historical task fields");
            log("Acknowledged terminal error restores idle/setup presentation while retaining its historical log");

            String runningOutput = "本地进行中记录，等待模拟进程重启";
            store.write(new JSONObject().put("version", 1).put("state", "running").put("status", "测试运行中")
                    .put("output", runningOutput).put("started_at", now).put("updated_at", now)
                    .put("ended_at", 0).put("reason", "").put("acknowledged", true));
            interrupted = constructIsolatedController(isolated);
            AgentController interruptedController = interrupted;
            runOnMainSync(() -> interruptedController.setListener(interruptedProbe));
            runOnMainSync(() -> { });
            check(!interrupted.isRunning() && interrupted.getVisualState() == AgentController.VisualState.ERROR,
                    "Rebuilt Controller did not mark an abandoned running task as interrupted");
            JSONObject recovered = new LastTaskStore(directory).read();
            check(recovered != null && "interrupted".equals(recovered.optString("state"))
                    && "process_interrupted".equals(recovered.optString("reason")), "Running record was not recovered as interrupted");
            check(!recovered.optBoolean("acknowledged"), "Interrupted recovery retained an obsolete acknowledgment");
            check(recovered.optLong("ended_at") >= recovered.optLong("started_at") && recovered.optLong("ended_at") > 0,
                    "Interrupted recovery did not record its completion time");
            String recoveredOutput = recovered.getString("output");
            check(recoveredOutput.startsWith(runningOutput) && recoveredOutput.contains("【任务结束】"),
                    "Interrupted recovery lost earlier output or omitted its final log");
            check(recoveredOutput.equals(interruptedProbe.output.get()), "Interrupted recovery was not replayed to the UI listener");
            JSONObject recoveredAgain = new LastTaskStore(directory).recoverInterrupted();
            check(recoveredAgain != null && recoveredOutput.equals(recoveredAgain.optString("output")),
                    "Repeated recovery appended duplicate final logs");
            log("Abandoned running record recovered once as interrupted, with a final log and no active task");
        } finally {
            AgentController first = restored, second = interrupted, third = acknowledged;
            runOnMainSync(() -> {
                if (first != null) first.removeListener(restoredProbe);
                if (second != null) second.removeListener(interruptedProbe);
                if (third != null) third.removeListener(acknowledgedProbe);
            });
            deleteTestTree(directory);
        }
        log("Only the isolated task-store cache directory was removed");
    }

    /** Exercise retention, migration and interrupted recovery in cache-only history stores. */
    private void history() throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "history-smoke-" + UUID.randomUUID());
        check(!directory.exists(), "Unique history test directory already exists");
        try {
            long now = System.currentTimeMillis();
            File retentionDirectory = new File(directory, "retention");
            TaskHistoryStore retention = new TaskHistoryStore(retentionDirectory);
            retention.initialize(null);
            List<String> identities = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                String id = UUID.randomUUID().toString();
                identities.add(id);
                JSONObject turn = syntheticTurn("success", "历史测试 " + i, "过程 " + i,
                        "结果 " + i, now - 30000 + i * 1000L);
                retention.importConversation(new JSONObject().put("id", id).put("title", "历史测试 " + i)
                        .put("created_at", now - 30000 + i * 1000L).put("updated_at", now - 30000 + i * 1000L)
                        .put("turns", new JSONArray().put(turn)));
            }
            JSONArray summaries = new TaskHistoryStore(retentionDirectory).summaries();
            check(summaries.length() == 20, "History did not retain exactly the most recent 20 conversations");
            for (int i = 0; i < 20; i++)
                check(identities.get(24 - i).equals(summaries.getJSONObject(i).getString("id")),
                        "History retention/order mismatch at " + i);
            for (int i = 0; i < 5; i++)
                check(retention.getConversation(identities.get(i)) == null, "Evicted oldest conversation remains available");
            log("Recent 20 conversation limit and newest-first reconstruction verified");

            File turnsDirectory = new File(directory, "turns");
            TaskHistoryStore turns = new TaskHistoryStore(turnsDirectory);
            turns.initialize(null);
            JSONObject first = turns.beginTurn(null, "第一轮本地测试指令");
            String conversationId = first.getString("conversation_id");
            String sessionId = UUID.randomUUID().toString();
            turns.bindSession(conversationId, sessionId);
            JSONObject firstTurn = turns.getConversation(conversationId).getJSONArray("turns").getJSONObject(0);
            firstTurn.put("state", "success").put("process", "中间过程标记")
                    .put("result", "# 最终结果标记").put("terminal", "【任务结束】\n状态：完成")
                    .put("output", "中间过程标记\n# 最终结果标记\n【任务结束】\n状态：完成")
                    .put("reason", "success").put("ended_at", now).put("updated_at", now)
                    .put("api_key", "fixture-field-must-not-persist").put("base_url", "http://fixture.invalid");
            turns.updateTurn(conversationId, first.getString("turn_id"), firstTurn);
            JSONObject second = turns.beginTurn(conversationId, "继续第一轮本地测试指令");
            check(conversationId.equals(second.getString("conversation_id")) && sessionId.equals(second.getString("session_id")),
                    "Continuation changed conversation/session identity");
            check(!first.getString("turn_id").equals(second.getString("turn_id")), "Continuation reused a prior turn ID");
            TaskHistoryStore reconstructed = new TaskHistoryStore(turnsDirectory);
            JSONArray savedTurns = reconstructed.getConversation(conversationId).getJSONArray("turns");
            check(savedTurns.length() == 2 && "第一轮本地测试指令".equals(savedTurns.getJSONObject(0).optString("prompt"))
                            && "继续第一轮本地测试指令".equals(savedTurns.getJSONObject(1).optString("prompt")),
                    "Reconstruction lost separate prompts or turns");
            JSONObject savedFirst = savedTurns.getJSONObject(0);
            check("中间过程标记".equals(savedFirst.getString("process"))
                            && "# 最终结果标记".equals(savedFirst.getString("result"))
                            && savedFirst.getString("terminal").contains("【任务结束】"),
                    "Reconstruction merged process/result/terminal fields");
            check(!savedFirst.has("api_key") && !savedFirst.has("base_url"), "History persisted configuration fields");
            JSONObject detached = reconstructed.getConversation(conversationId);
            detached.put("title", "must-not-mutate-store");
            check(!"must-not-mutate-store".equals(reconstructed.getConversation(conversationId).optString("title")),
                    "History returns mutable internal conversation state");
            reconstructed.initialize(null);
            JSONObject recoveredTurn = reconstructed.getConversation(conversationId).getJSONArray("turns").getJSONObject(1);
            check("interrupted".equals(recoveredTurn.optString("state"))
                            && "process_interrupted".equals(recoveredTurn.optString("reason"))
                            && recoveredTurn.optLong("ended_at") > 0 && recoveredTurn.optString("result").isEmpty()
                            && recoveredTurn.optString("terminal").contains("【任务结束】"),
                    "Running history turn was not recovered as interrupted with a separate terminal record");
            String recoveredSnapshot = reconstructed.getConversation(conversationId).toString();
            TaskHistoryStore recoveredAgain = new TaskHistoryStore(turnsDirectory);
            recoveredAgain.initialize(null);
            check(recoveredSnapshot.equals(recoveredAgain.getConversation(conversationId).toString()),
                    "Repeated initialization modified the recovered conversation");
            check(savedFirst.toString().equals(recoveredAgain.getConversation(conversationId).getJSONArray("turns").getJSONObject(0).toString()),
                    "Recovering the latest running turn changed an earlier completed turn");
            log("Separate prompts, results and process; continuation identity; idempotent running-turn recovery verified");

            File legacyDirectory = new File(directory, "legacy");
            LastTaskStore legacy = new LastTaskStore(legacyDirectory);
            String legacyOutput = "旧版本保留的完整输出\n第二行\n\n【任务结束】\n状态：完成\n原因：success";
            legacy.write(new JSONObject().put("version", 1).put("state", "success").put("status", "旧任务完成")
                    .put("output", legacyOutput).put("reason", "success").put("acknowledged", true)
                    .put("started_at", now - 2000).put("updated_at", now - 1000).put("ended_at", now - 1000));
            String legacyBefore = legacy.read().toString();
            TaskHistoryStore migrated = new TaskHistoryStore(legacyDirectory);
            migrated.initialize(legacy.read());
            JSONArray migratedSummaries = migrated.summaries();
            check(migratedSummaries.length() == 1, "Legacy last-task did not become one conversation");
            JSONObject migratedSummary = migratedSummaries.getJSONObject(0);
            check(migratedSummary.optBoolean("legacy") && migratedSummary.optString("session_id").isEmpty()
                            && !migratedSummary.optBoolean("can_continue"), "Legacy migration invented a resumable CLI session");
            JSONObject migratedTurn = migrated.getConversation(migratedSummary.getString("id")).getJSONArray("turns").getJSONObject(0);
            check(legacyOutput.equals(migratedTurn.optString("output")), "Legacy migration lost the original transcript");
            check(migratedTurn.optString("process").contains("旧版本保留的完整输出\n第二行")
                            && migratedTurn.optString("terminal").contains("【任务结束】"), "Legacy migration lost process/footer text");
            check(legacyBefore.equals(new LastTaskStore(legacyDirectory).read().toString()), "History migration modified legacy last-task data");
            TaskHistoryStore migratedAgain = new TaskHistoryStore(legacyDirectory);
            migratedAgain.initialize(legacy.read());
            check(migratedAgain.summaries().length() == 1, "Legacy initialization duplicated the old conversation");
            log("Legacy last-task migrated once without lost text, altered source or invented session");

            Context isolated = isolatedFilesContext(turnsDirectory);
            AgentController restored = constructIsolatedController(isolated);
            check(!restored.isRunning(), "Reconstructed history Controller revived a running process");
            JSONObject restoredConversation = restored.getConversation(conversationId);
            check(restoredConversation != null && restoredConversation.getJSONArray("turns").length() == 2,
                    "Controller reconstruction did not expose persisted history");

            File handoffDirectory = new File(directory, "interrupted-followup");
            TaskHistoryStore handoff = new TaskHistoryStore(handoffDirectory);
            handoff.initialize(null);
            JSONObject oldStarted = handoff.beginTurn(null, "断点前第一轮");
            String handoffId = oldStarted.getString("conversation_id");
            JSONObject oldTurn = syntheticTurn("success", "断点前第一轮", "旧过程", "旧成功结果", now - 1000)
                    .put("id", oldStarted.getString("turn_id"));
            handoff.updateTurn(handoffId, oldStarted.getString("turn_id"), oldTurn);
            JSONObject oldLastTask = new JSONObject(oldTurn.toString()).put("version", 1)
                    .put("conversation_id", handoffId).put("turn_id", oldStarted.getString("turn_id"));
            new LastTaskStore(handoffDirectory).write(oldLastTask);
            JSONObject newStarted = handoff.beginTurn(handoffId, "已落盘但尚未更新last-task的追问");
            JSONObject runningFollowup = handoff.getConversation(handoffId).getJSONArray("turns").getJSONObject(1)
                    .put("process", "新追问已启动").put("output", "新追问已启动");
            handoff.updateTurn(handoffId, newStarted.getString("turn_id"), runningFollowup);
            AgentController handoffRestored = constructIsolatedController(isolatedFilesContext(handoffDirectory));
            check(!handoffRestored.isRunning() && handoffRestored.getVisualState() == AgentController.VisualState.ERROR,
                    "Old successful last-task hid an interrupted new turn in the same conversation");
            JSONArray handoffTurns = handoffRestored.getConversation(handoffId).getJSONArray("turns");
            check(handoffTurns.length() == 2 && "success".equals(handoffTurns.getJSONObject(0).optString("state"))
                            && "interrupted".equals(handoffTurns.getJSONObject(1).optString("state"))
                            && "process_interrupted".equals(handoffTurns.getJSONObject(1).optString("reason")),
                    "Same-conversation crash recovery lost the old result or did not interrupt the new turn");
            check(controllerOutput(handoffRestored).contains("新追问已启动")
                            && controllerOutput(handoffRestored).contains("【任务结束】")
                            && !controllerOutput(handoffRestored).contains("旧成功结果"),
                    "Controller replayed the stale old success instead of the interrupted followup");
            log("Crash between new same-conversation turn and last-task update restores interrupted followup, preserving old success");
        } finally {
            deleteTestTree(directory);
        }
        log("Only isolated history cache directories were removed; no model or user history was used");
    }

    private JSONObject syntheticTurn(String state, String prompt, String process, String result, long time) throws Exception {
        String terminal = "【任务结束】\n状态：完成\n原因：success";
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("state", state).put("prompt", prompt)
                .put("process", process).put("result", result).put("terminal", terminal)
                .put("output", process + "\n" + result + "\n" + terminal).put("status", "本地测试已完成")
                .put("reason", "success").put("started_at", time - 1).put("updated_at", time).put("ended_at", time);
    }

    private Context isolatedFilesContext(File directory) {
        return new ContextWrapper(getTargetContext()) {
            @Override public File getFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
    }

    /** Real native transport, but fixture-only credentials and entirely disposable Linux HOME/history. */
    private void interaction() throws Exception {
        awaitInteraction(() -> PhoneAccessibilityService.isEnabled(), 15000, "PhoneAgent accessibility did not bind");
        AgentController userController = AgentController.get(getTargetContext());
        check(!userController.isRunning() && !userController.hasActiveTask(), "Do not test while a user task is active");
        check(!interactionFixtureStatus().optBoolean("slow_started"), "Restart the interaction fixture before this mode");
        File directory = new File(getTargetContext().getCacheDir(), "interaction-smoke-" + interactionFixtureId);
        check(!directory.exists() && directory.mkdirs(), "Cannot create unique interaction test directory");
        File historyDirectory = new File(directory, "history");
        File recoveryDirectory = new File(directory, "recovery");
        File stopRecoveryDirectory = new File(directory, "stop-recovery");
        LinuxRuntime runtime = new LinuxRuntime(getTargetContext(), new File(getTargetContext().getFilesDir(), "linux/rootfs"),
                new File(directory, "runtime"));
        LinuxRuntime recoveryRuntime = new LinuxRuntime(getTargetContext(), new File(getTargetContext().getFilesDir(), "linux/rootfs"),
                new File(directory, "recovery-runtime"));
        check(runtime.isInstalled(), "Prepare the installed runtime before mode=interaction; this mode never installs it");
        AgentController controller = new AgentController(isolatedFilesContext(historyDirectory), runtime);
        AgentController restored = null;
        AgentController stoppedRecovery = null;
        List<String> changedModelKeys = new CopyOnWriteArrayList<>();
        SharedPreferences settings = getTargetContext().getSharedPreferences("phoneagent", Context.MODE_PRIVATE);
        SharedPreferences.OnSharedPreferenceChangeListener observer = (preferences, key) -> {
            if (key == null || key.equals("base_url") || key.equals("model") || key.equals("key_ciphertext") || key.equals("key_iv"))
                changedModelKeys.add(key == null ? "<clear>" : key);
        };
        settings.registerOnSharedPreferenceChangeListener(observer);
        try {
            assertCancelledRuntimeRegistration(runtime);
            if (interactionStopOnly) {
                assertLiveQuestionStop(controller);
                return;
            }
            PhoneActionGate isolatedGate = new PhoneActionGate();
            isolatedGate.open();
            long generation = isolatedGate.capture();
            check(isolatedGate.isAllowed(generation), "Opened test gate rejected its current action ticket");
            // Model an action captured before pause but not yet dispatched on Android's main thread.
            Class<?> pendingActionClass = Class.forName("com.ds.phoneagent.PhoneAccessibilityService$PendingAction");
            Constructor<?> actionConstructor = pendingActionClass.getDeclaredConstructor(PhoneAccessibilityService.class,
                    String.class, JSONObject.class, PhoneActionGate.class, long.class);
            actionConstructor.setAccessible(true);
            Object service = accessibleField(PhoneAccessibilityService.class, "connected").get(null);
            Object queued = actionConstructor.newInstance(service, "read_screen", new JSONObject(), isolatedGate, generation);
            isolatedGate.close(); isolatedGate.open();
            check(!isolatedGate.isAllowed(generation) && isolatedGate.isAllowed(isolatedGate.capture()),
                    "Reopened gate revived a stale action generation or rejected its new generation");
            runOnMainSync((Runnable) queued);
            AtomicReference<?> queuedResult = (AtomicReference<?>) accessibleField(pendingActionClass, "result").get(queued);
            JSONObject rejected = (JSONObject) queuedResult.get();
            check(rejected != null && !rejected.optBoolean("ok") && "task_paused".equals(rejected.optString("error_code")),
                    "An already queued old-generation action executed after the gate reopened");
            ControllerProbe questionProbe = new ControllerProbe();
            startInteraction(controller, questionProbe, "PROBE_ASK");
            awaitInteraction(() -> controller.getVisualState() == AgentController.VisualState.WAITING_USER
                            && controller.canAcceptInput() && controller.getPendingQuestion() != null,
                    120000, "Native question did not reach WAITING_USER");
            String questionConversationId = controller.getActiveConversationId();
            JSONObject pending = controller.getPendingQuestion();
            String pendingId = pending.getString("id");
            String question = pending.getJSONObject("input").getJSONArray("questions").getJSONObject(0).getString("question");
            check("Which test color should be remembered?".equals(question), "Native question content was not preserved");
            JSONObject waitingConversation = controller.getConversation(questionConversationId);
            String questionSession = waitingConversation.getString("session_id");
            check(LinuxRuntime.isSessionId(questionSession) && runtime.hasClaudeSession(questionSession),
                    "Waiting question has no real isolated Claude session");
            check(controller.hasActiveTask() && questionProbe.finishedCallbacks.get() == 0,
                    "Waiting for a question prematurely completed the task");
            AtomicReference<Throwable> uiFailure = new AtomicReference<>();
            runOnMainSync(() -> {
                try {
                    InteractionUiProbe.run(resumedMain, controller, new File(getTargetContext().getCacheDir(),
                            "interaction-ui-card-" + interactionFixtureId + ".png"));
                } catch (Throwable failure) { uiFailure.set(failure); }
            });
            if (uiFailure.get() != null) throw new AssertionError("Native question UI regression failed", uiFailure.get());
            waitForIdleSync();
            log("Native question cards preserved single/multi/free answers, separate drafts, and rotation state without submitting");
            awaitInteraction(() -> {
                JSONObject value = new LastTaskStore(historyDirectory).read();
                return value != null && "waiting_user".equals(value.optString("state"));
            }, 5000, "WAITING_USER was not persisted");
            // Snapshot actual saved waiting state before answering; recovery must not invent a question.
            new TaskHistoryStore(recoveryDirectory).importConversation(waitingConversation);
            new LastTaskStore(recoveryDirectory).write(new LastTaskStore(historyDirectory).read());
            new TaskHistoryStore(stopRecoveryDirectory).importConversation(waitingConversation);
            new LastTaskStore(stopRecoveryDirectory).write(new LastTaskStore(historyDirectory).read());
            File sourceSession = new File(directory, "runtime/home/.claude/projects/-home-agent/" + questionSession + ".jsonl");
            File savedSession = new File(directory, "recovery-runtime/home/.claude/projects/-home-agent/" + questionSession + ".jsonl");
            check(savedSession.getParentFile().mkdirs(), "Could not create recovery-session directory");
            byte[] sessionSnapshot = readInteractionSession(sourceSession);
            check(new String(sessionSnapshot, StandardCharsets.UTF_8).contains("AskUserQuestion"),
                    "Native waiting session did not persist the pending question before snapshot");
            try (FileOutputStream output = new FileOutputStream(savedSession)) { output.write(sessionSnapshot); }
            JSONObject answers = new JSONObject().put(question, "Blue");
            runOnMainSync(() -> controller.answerQuestion(UUID.randomUUID().toString(), pendingId, answers, "",
                    INTERACTION_URL, INTERACTION_MODEL, INTERACTION_KEY));
            check(controller.canAcceptInput() && pendingId.equals(controller.getPendingQuestion().optString("id")),
                    "Answer from another conversation changed the pending question");
            runOnMainSync(() -> controller.answerQuestion(questionConversationId, "stale-question-id", answers, "",
                    INTERACTION_URL, INTERACTION_MODEL, INTERACTION_KEY));
            check(controller.canAcceptInput() && pendingId.equals(controller.getPendingQuestion().optString("id")),
                    "Stale question ID changed the pending question");
            Process questionProcess = interactionProcess(controller);
            int beforeAnswerRequests = interactionFixtureStatus().getInt("request_count");
            runOnMainSync(() -> controller.setTaskUiVisible(true));
            runOnMainSync(() -> controller.answerQuestion(questionConversationId, pendingId, answers, "",
                    INTERACTION_URL, INTERACTION_MODEL, INTERACTION_KEY));
            check(!controller.canAcceptInput(), "Accepted answer did not close input synchronously");
            PhoneActionGate questionGate = (PhoneActionGate) accessibleField(AgentController.class, "actionGate").get(controller);
            waitForIdleSync();
            SystemClock.sleep(300);
            check(controller.getVisualState() == AgentController.VisualState.RESUMING
                            && !questionGate.isAllowed(questionGate.capture())
                            && beforeAnswerRequests == interactionFixtureStatus().getInt("request_count"),
                    "Answer resumed execution before its task UI was hidden");
            java.util.concurrent.ExecutorService controls = (java.util.concurrent.ExecutorService)
                    accessibleField(AgentController.class, "controls").get(controller);
            CountDownLatch controlBlocked = new CountDownLatch(1), releaseControls = new CountDownLatch(1);
            controls.execute(() -> {
                controlBlocked.countDown();
                try { releaseControls.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            check(controlBlocked.await(5, TimeUnit.SECONDS), "Could not synchronize the UI visibility race");
            try {
                runOnMainSync(() -> { controller.setTaskUiVisible(false); controller.setTaskUiVisible(true); });
            } finally { releaseControls.countDown(); }
            controls.submit(() -> { }).get(5, TimeUnit.SECONDS);
            check(controller.getVisualState() == AgentController.VisualState.RESUMING
                            && !questionGate.isAllowed(questionGate.capture())
                            && beforeAnswerRequests == interactionFixtureStatus().getInt("request_count"),
                    "A queued continuation executed after the task UI became visible again");
            runOnMainSync(() -> controller.setTaskUiVisible(false));
            awaitInteractionResult(controller, questionProbe, "PROBE_ASK_OK");
            JSONObject answered = controller.getConversation(questionConversationId);
            check(questionSession.equals(answered.getString("session_id")), "Answer changed the Claude session");
            check(questionProcess != null && !questionProcess.isAlive(), "Completed question process was not reaped");
            check(answered.toString().contains("Blue") && answered.toString().contains("interaction_answer"),
                    "The submitted answer was not preserved in the task history");
            check(controller.getPendingQuestion() == null, "Completed question remains pending");
            log("Native AskUserQuestion waited, rejected unrelated/stale answers, and continued the original session");

            restored = new AgentController(isolatedFilesContext(recoveryDirectory), recoveryRuntime);
            final AgentController recovered = restored;
            ControllerProbe recoveryProbe = new ControllerProbe();
            runOnMainSync(() -> recovered.setListener(recoveryProbe));
            check(recovered.getVisualState() == AgentController.VisualState.WAITING_USER && recovered.hasActiveTask()
                            && recovered.canAcceptInput() && !recovered.isRunning(),
                    "Reconstructed WAITING_USER did not remain a dormant active task");
            check(interactionProcess(recovered) == null && pendingId.equals(recovered.getPendingQuestion().optString("id")),
                    "Reconstructed question launched a process or lost its pending request");
            check(questionSession.equals(recovered.getConversation(questionConversationId).getString("session_id")),
                    "Reconstructed question lost the original session");
            int beforeRecoveryRequests = interactionFixtureStatus().getInt("request_count");
            waitForIdleSync();
            SystemClock.sleep(300);
            check(beforeRecoveryRequests == interactionFixtureStatus().getInt("request_count") && interactionProcess(recovered) == null,
                    "Reconstruction automatically contacted the fixture or resumed execution");
            runOnMainSync(() -> recovered.answerQuestion(questionConversationId, pendingId, answers, "PROBE_RECOVERED_ANSWER",
                    INTERACTION_URL, INTERACTION_MODEL, INTERACTION_KEY));
            awaitInteractionResult(recovered, recoveryProbe, "PROBE_RECOVERED_ANSWER_OK");
            check(questionSession.equals(recovered.getConversation(questionConversationId).getString("session_id")),
                    "Recovered answer created a different CLI session");
            log("Recovered native pending session resumed with its original question context and explicit user answer");

            stoppedRecovery = new AgentController(isolatedFilesContext(stopRecoveryDirectory), runtime);
            final AgentController stoppedRestored = stoppedRecovery;
            runOnMainSync(stoppedRestored::stop);
            awaitInteraction(() -> !stoppedRestored.hasActiveTask() && stoppedRestored.getVisualState() == AgentController.VisualState.STOPPED,
                    10000, "Ending a restored waiting task did not stop it");
            check(stoppedRestored.getPendingQuestion() == null, "Stopping restored waiting state retained the question");
            String recoveredOutput = controllerOutput(stoppedRestored);
            runOnMainSync(stoppedRestored::resetToIdle);
            waitForIdleSync();
            check(stoppedRestored.getVisualState() == AgentController.VisualState.IDLE
                            && recoveredOutput.equals(controllerOutput(stoppedRestored)),
                    "Reset after a restored waiting task lost output or failed to reach IDLE");
            log("Persisted WAITING_USER reconstructed without a process/request; Stop and Reset retained its record");

            ControllerProbe pausedProbe = new ControllerProbe();
            startInteraction(controller, pausedProbe, "PROBE_INTERRUPT");
            awaitInteraction(() -> {
                try { return interactionFixtureStatus().optBoolean("slow_started"); }
                catch (Exception failure) { return false; }
            }, 120000, "The deterministic slow request did not start");
            Process pausedProcess = interactionProcess(controller);
            String pausedConversationId = controller.getActiveConversationId();
            String pausedSession = controller.getConversation(pausedConversationId).getString("session_id");
            PhoneActionGate gate = (PhoneActionGate) accessibleField(AgentController.class, "actionGate").get(controller);
            long oldTicket = gate.capture();
            check(gate.isAllowed(oldTicket), "Active task gate was unexpectedly closed");
            CountDownLatch openedAfterPause = new CountDownLatch(1);
            runOnMainSync(() -> controller.pauseForUser(openedAfterPause::countDown));
            check(!gate.isAllowed(oldTicket), "Pause did not close the action gate immediately");
            check(openedAfterPause.await(15, TimeUnit.SECONDS), "Pause never reached its safe UI callback");
            awaitInteraction(() -> controller.canAcceptInput() && controller.getVisualState() == AgentController.VisualState.PAUSED,
                    15000, "Pause did not wait for native interrupt completion");
            check(controller.hasActiveTask() && pausedProbe.finishedCallbacks.get() == 0,
                    "Pause incorrectly completed the active task");
            check(interactionProcess(controller) == pausedProcess && pausedProcess != null && pausedProcess.isAlive(),
                    "Native pause destroyed the interactive process");
            JSONObject pausedRecord = latestInteractionTurn(controller, pausedConversationId);
            int interruptedTurns = pausedRecord.getInt("num_turns");
            check(interruptedTurns > 0, "Paused native query did not persist its engine turns");
            assertMetricQueryTotal(pausedRecord, 1, interruptedTurns);
            PhoneToolServer pausedBridge = (PhoneToolServer) accessibleField(AgentController.class, "bridge").get(controller);
            int statesBeforeBlockedTool = pausedProbe.states.size();
            Response blocked = request(pausedBridge, pausedBridge.token(), "read_screen");
            check(!blocked.body.optBoolean("ok"), "Paused bridge allowed a phone operation");
            waitForIdleSync();
            check(pausedProbe.states.size() == statesBeforeBlockedTool, "Rejected phone operation changed presentation state");
            runOnMainSync(() -> controller.continuePaused(pausedConversationId, "PROBE_AFTER_INTERRUPT",
                    INTERACTION_URL, INTERACTION_MODEL, INTERACTION_KEY));
            check(!controller.canAcceptInput(), "Accepted continuation did not close input synchronously");
            check(!gate.isAllowed(oldTicket), "Reopening the gate revived a pre-pause action ticket");
            awaitInteractionResult(controller, pausedProbe, "PROBE_AFTER_INTERRUPT_OK");
            check(pausedSession.equals(controller.getConversation(pausedConversationId).getString("session_id")),
                    "Pause/correction started a different Claude session");
            JSONObject resumedRecord = latestInteractionTurn(controller, pausedConversationId);
            check(resumedRecord.getInt("num_turns") == interruptedTurns + 1,
                    "Continuation overwrote the interrupted query's engine turns with the final query's single turn");
            assertMetricQueryTotal(resumedRecord, 2, interruptedTurns + 1);
            log("Pause/correction accumulated native query turns: " + interruptedTurns + " + 1 = " + resumedRecord.getInt("num_turns"));
            log("Pause closed the bridge, rejected old action generation, and continued the same native session after correction");

            ControllerProbe retryProbe = new ControllerProbe();
            startInteraction(controller, retryProbe, "PROBE_RETRY");
            awaitInteractionResult(controller, retryProbe, "PROBE_RETRY_OK");
            JSONObject retryRecord = latestInteractionTurn(controller, controller.getActiveConversationId());
            check(retryRecord.getInt("num_turns") == 1 && retryRecord.getInt("api_retry_count") == 1
                            && retryRecord.getInt("model_reply_count") == 1,
                    "HTTP 500 retry did not persist one engine turn, one retry and one model reply");
            check(retryRecord.getInt("tool_call_count") == 0 && retryRecord.getInt("phone_action_count") == 0,
                    "A model-only retry task invented a tool call or phone action");
            String retryProcess = retryRecord.getString("process");
            check(retryProcess.contains("500") && retryProcess.contains("重试"), "The HTTP 500 retry was hidden from the saved process");
            check(retryProbe.states.contains(AgentController.VisualState.RETRYING), "The native retry did not publish RETRYING state");
            assertMetricQueryTotal(retryRecord, 1, 1);
            AtomicReference<Throwable> statsFailure = new AtomicReference<>();
            runOnMainSync(() -> {
                try { InteractionUiProbe.assertExecutionStats(resumedMain, retryRecord); }
                catch (Throwable failure) { statsFailure.set(failure); }
            });
            if (statsFailure.get() != null) throw new AssertionError("Execution stats UI regression failed", statsFailure.get());
            log("HTTP 500 retry published RETRYING and a visible process hint; persisted turns=1, retries=1, model replies=1");

            assertLiveQuestionStop(controller);
            check(changedModelKeys.isEmpty(), "Interaction test changed user model configuration keys");
            check(!new TaskHistoryStore(historyDirectory).getConversation(questionConversationId).toString().contains(INTERACTION_KEY),
                    "Fixture credential leaked into stored conversation");
            log("Stop cancelled a live pending question, reaped its process, and Reset preserved output; user model settings unchanged");
        } finally {
            AgentController finalRestored = restored;
            AgentController finalStoppedRecovery = stoppedRecovery;
            runOnMainSync(() -> {
                controller.stop();
                if (finalRestored != null) finalRestored.stop();
                if (finalStoppedRecovery != null) finalStoppedRecovery.stop();
                OverlayService.setPetState(userController.getVisualState());
            });
            runtime.cancel();
            recoveryRuntime.cancel();
            settings.unregisterOnSharedPreferenceChangeListener(observer);
            shutdownTestController(controller);
            if (restored != null) shutdownTestController(restored);
            if (stoppedRecovery != null) shutdownTestController(stoppedRecovery);
            deleteTestTree(directory);
        }
        check(!directory.exists(), "Disposable interaction runtime/history was not removed");
    }

    private static final String INTERACTION_URL = "http://127.0.0.1:18081";
    private static final String INTERACTION_MODEL = "phoneagent-interaction-probe";
    private static final String INTERACTION_KEY = "local-interaction-probe-only";

    private JSONObject latestInteractionTurn(AgentController controller, String conversationId) throws Exception {
        JSONArray turns = controller.getConversation(conversationId).getJSONArray("turns");
        check(turns.length() > 0, "Interaction conversation has no persisted turn");
        return turns.getJSONObject(turns.length() - 1);
    }

    private void assertMetricQueryTotal(JSONObject turn, int expectedQueries, int expectedTotal) throws Exception {
        JSONObject ledger = turn.getJSONObject("execution_metrics");
        JSONObject queries = ledger.getJSONObject("queries");
        check(queries.length() == expectedQueries && !ledger.optBoolean("incomplete"),
                "Native query metrics are missing, duplicated or incomplete");
        int total = 0;
        java.util.Iterator<String> keys = queries.keys();
        while (keys.hasNext()) {
            int count = queries.getInt(keys.next());
            check(count >= 0, "A completed/paused query remained unsettled in the replay ledger");
            total += count;
        }
        check(total == expectedTotal && turn.getInt("num_turns") == total, "Engine turn total differs from its settled native queries");
    }

    private void assertCancelledRuntimeRegistration(LinuxRuntime runtime) throws Exception {
        Process child = new ProcessBuilder("/system/bin/sleep", "30").start();
        Method register = LinuxRuntime.class.getDeclaredMethod("registerProcess", Process.class, long.class);
        register.setAccessible(true);
        AtomicLong epoch = (AtomicLong) accessibleField(LinuxRuntime.class, "cancellationEpoch").get(runtime);
        boolean rejected = false;
        try {
            try { register.invoke(runtime, child, epoch.get() - 1); }
            catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof IOException, "Cancelled runtime registration returned an unexpected failure");
                rejected = true;
            }
            check(rejected && !child.isAlive(), "Cancelled runtime registration returned before its child was reaped");
            log("Cancelled runtime startup reaped its exact child before rejecting registration");
        } finally {
            if (child.isAlive()) {
                Method pid = AgentController.class.getDeclaredMethod("childPid", Process.class);
                pid.setAccessible(true);
                int identity = (Integer) pid.invoke(null, child);
                if (identity > 0) android.os.Process.killProcess(identity);
                check(child.waitFor(5, TimeUnit.SECONDS), "Could not clean isolated startup-cancellation child");
            }
        }
    }

    private void assertLiveQuestionStop(AgentController controller) throws Exception {
        ControllerProbe probe = new ControllerProbe();
        startInteraction(controller, probe, "PROBE_ASK");
        awaitInteraction(() -> controller.getVisualState() == AgentController.VisualState.WAITING_USER
                && controller.canAcceptInput(), 120000, "Native stop-test question did not wait");
        Process child = interactionProcess(controller);
        Method pidMethod = AgentController.class.getDeclaredMethod("childPid", Process.class);
        pidMethod.setAccessible(true);
        int childPid = child == null ? -1 : (Integer) pidMethod.invoke(null, child);
        log("Before live question Stop: process=" + (child != null) + ", alive=" + (child != null && child.isAlive())
                + ", running=" + controller.isRunning() + ", active=" + controller.hasActiveTask()
                + ", class=" + (child == null ? "none" : child.getClass().getName()) + ", exact_child_pid=" + childPid);
        check(childPid > 0, "The native child PID could not be resolved safely");
        Map<String, String> originalNativeIdentities = logNativeProcessIdentities("before Stop", processTable());
        long stopStarted = SystemClock.uptimeMillis();
        runOnMainSync(controller::stop);
        check(probe.finished.await(15, TimeUnit.SECONDS), "Stop during live question did not finish");
        awaitInteraction(() -> !controller.isRunning() && !controller.hasActiveTask(), 5000,
                "Live question retained active task state after Stop");
        log("After live question Stop: state=" + controller.getVisualState() + ", cancelled="
                + accessibleField(AgentController.class, "cancelled").getBoolean(controller) + ", finishing="
                + accessibleField(AgentController.class, "finishing").getBoolean(controller) + ", success=" + probe.success.get()
                + ", pending=" + (controller.getPendingQuestion() != null) + ", process=" + (interactionProcess(controller) != null)
                + ", original_process_alive=" + (child != null && child.isAlive())
                + ", stop_elapsed_ms=" + (SystemClock.uptimeMillis() - stopStarted) + ", message=" + probe.message.get());
        check(controller.getVisualState() == AgentController.VisualState.STOPPED, "Live question Stop did not publish STOPPED");
        check(!probe.success.get(), "Live question Stop published success");
        check(controller.getPendingQuestion() == null, "Live question Stop retained its pending question");
        check(interactionProcess(controller) == null, "Live question Stop retained its process reference");
        check(child != null, "Stop test had already lost its live native process before Stop");
        check(!child.isAlive(), "Live question Stop did not reap its original process");
        String processes = processTable();
        java.util.regex.Matcher leftovers = java.util.regex.Pattern.compile("(?m)^.*\\s(?:claude|(?:lib)?proot(?:\\.so)?)\\s*$").matcher(processes);
        boolean nativeRemains = false;
        boolean sawZombie = false;
        while (leftovers.find()) {
            String line = leftovers.group().trim(), pid = line.split("\\s+")[0];
            String identity = nativeProcessIdentity(pid);
            boolean zombie = identity.startsWith("state=Z") || identity.startsWith("state=X");
            sawZombie |= zombie;
            nativeRemains |= !zombie && new File("/proc/" + pid).exists();
            log("Native process present before instrumentation cleanup: " + line + "; " + identity
                    + "; same_starttime=" + (!processStarttime(identity).isEmpty()
                    && processStarttime(identity).equals(processStarttime(originalNativeIdentities.get(pid)))));
        }
        check(!nativeRemains,
                "An executable native Claude/PRoot process remains before instrumentation cleanup");
        if (sawZombie) {
            long reapDeadline = SystemClock.uptimeMillis() + 1000;
            boolean listed;
            do {
                SystemClock.sleep(100);
                listed = java.util.regex.Pattern.compile("(?m)^.*\\s(?:claude|(?:lib)?proot(?:\\.so)?)\\s*$")
                        .matcher(processTable()).find();
            } while (listed && SystemClock.uptimeMillis() < reapDeadline);
            log("Non-executable native zombies after bounded kernel-reaping wait: " + listed);
        }
        log("Stopped native child is reaped and no executable Claude/PRoot process remains before instrumentation cleanup");
        String output = controllerOutput(controller);
        runOnMainSync(controller::resetToIdle);
        waitForIdleSync();
        check(controller.getVisualState() == AgentController.VisualState.IDLE && output.equals(controllerOutput(controller)),
                "Live question Reset lost output");
    }

    private String processTable() throws Exception {
        Process listing = new ProcessBuilder("/system/bin/ps", "-A", "-o", "PID,PPID,STAT,NAME").redirectErrorStream(true).start();
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(listing.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        check(listing.waitFor(5, TimeUnit.SECONDS) && listing.exitValue() == 0, "Could not check stopped native child processes");
        return result.toString();
    }

    private Map<String, String> logNativeProcessIdentities(String stage, String table) {
        Map<String, String> result = new HashMap<>();
        java.util.regex.Matcher processes = java.util.regex.Pattern.compile("(?m)^.*\\s(?:claude|(?:lib)?proot(?:\\.so)?)\\s*$").matcher(table);
        while (processes.find()) {
            String line = processes.group().trim(), pid = line.split("\\s+")[0];
            String identity = nativeProcessIdentity(pid);
            result.put(pid, identity);
            log("Native process " + stage + ": " + line + "; " + identity);
        }
        return result;
    }

    private String nativeProcessIdentity(String pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/stat"))) {
            String value = reader.readLine();
            String[] fields = value.substring(value.lastIndexOf(')') + 2).split("\\s+");
            return "state=" + fields[0] + ", ppid=" + fields[1] + ", starttime=" + fields[19];
        } catch (Exception gone) { return "unavailable"; }
    }

    private String processStarttime(String value) {
        if (value == null || !value.contains("starttime=")) return "";
        return value.substring(value.indexOf("starttime=") + 10);
    }

    private void startInteraction(AgentController controller, ControllerProbe probe, String prompt) {
        runOnMainSync(() -> {
            controller.setListener(probe);
            controller.run(prompt, INTERACTION_URL, INTERACTION_MODEL, INTERACTION_KEY);
        });
    }

    private void awaitInteractionResult(AgentController controller, ControllerProbe probe, String expected) throws Exception {
        check(probe.finished.await(120, TimeUnit.SECONDS), "Interaction task timed out: " + probe.status.get());
        check(probe.success.get() && probe.output.get().contains(expected),
                "Interaction fixture failed: " + probe.message.get() + "\n" + probe.output.get());
        check(!controller.isRunning() && !controller.hasActiveTask()
                && controller.getVisualState() == AgentController.VisualState.SUCCESS, "Completed interaction retained active state");
    }

    private Process interactionProcess(AgentController controller) throws Exception {
        return (Process) accessibleField(AgentController.class, "process").get(controller);
    }

    private void awaitInteraction(BooleanSupplier ready, long timeout, String failure) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (!ready.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        check(ready.getAsBoolean(), failure);
    }

    private JSONObject interactionFixtureStatus() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(INTERACTION_URL + "/status").openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) != -1) {
                check(output.size() + count < 4096, "Fixture status response exceeded its bound");
                output.write(buffer, 0, count);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    private byte[] readInteractionSession(File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                check(output.size() + count <= 2 * 1024 * 1024, "Synthetic native session exceeded snapshot limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /** Three real CLI invocations: A, B, then --resume A after rebuilding Controller. */
    private void resume() throws Exception {
        long serviceDeadline = SystemClock.uptimeMillis() + 15000;
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Enable accessibility before resume test");
        AgentController userController = AgentController.get(getTargetContext());
        check(!userController.isRunning(), "Do not test resume while a user task is running");
        LinuxRuntime runtime = new LinuxRuntime(getTargetContext());
        check(runtime.isInstalled(), "Run mode=runtime before mode=resume");
        File directory = new File(getTargetContext().getCacheDir(), "resume-smoke-" + UUID.randomUUID());
        check(!directory.exists(), "Unique resume test directory already exists");
        Context isolated = isolatedFilesContext(directory);
        String identityA = UUID.randomUUID().toString().replace("-", "");
        String identityB = UUID.randomUUID().toString().replace("-", "");
        List<String> changedModelKeys = new CopyOnWriteArrayList<>();
        SharedPreferences settings = getTargetContext().getSharedPreferences("phoneagent", Context.MODE_PRIVATE);
        SharedPreferences.OnSharedPreferenceChangeListener observer = (preferences, key) -> {
            if (key == null || key.equals("base_url") || key.equals("model") || key.equals("key_ciphertext") || key.equals("key_iv"))
                changedModelKeys.add(key == null ? "<clear>" : key);
        };
        settings.registerOnSharedPreferenceChangeListener(observer);
        AgentController firstController = new AgentController(isolated, runtime);
        AgentController secondController = null;
        try {
            log("LOCAL SESSION FIXTURE: original user/assistant context and conversation isolation are checked by the server");
            ControllerProbe firstProbe = runSessionTurn(firstController, null, "PHONEAGENT_SESSION_START_" + identityA,
                    "SESSION_FIRST_" + identityA);
            String conversationA = firstController.getActiveConversationId();
            JSONObject firstConversation = firstController.getConversation(conversationA);
            String sessionA = firstConversation.getString("session_id");
            check(LinuxRuntime.isSessionId(sessionA) && runtime.hasClaudeSession(sessionA),
                    "First task did not bind a real persisted Claude CLI session");
            JSONArray firstTurns = firstConversation.getJSONArray("turns");
            check(firstTurns.length() == 1, "First task did not create exactly one turn");
            assertSeparatedSessionTurn(firstTurns.getJSONObject(0), identityA, false);
            check(firstProbe.historyCallbacks.get() > 0, "First turn never notified history listeners");
            String originalFirstTurn = firstTurns.getJSONObject(0).toString();
            log("First fixture conversation created a real CLI session: " + sessionA);

            runSessionTurn(firstController, null, "PHONEAGENT_SESSION_START_" + identityB,
                    "SESSION_FIRST_" + identityB);
            String conversationB = firstController.getActiveConversationId();
            JSONObject secondConversation = firstController.getConversation(conversationB);
            String sessionB = secondConversation.getString("session_id");
            check(!conversationA.equals(conversationB) && !sessionA.equals(sessionB)
                            && LinuxRuntime.isSessionId(sessionB) && runtime.hasClaudeSession(sessionB),
                    "Independent task reused the first conversation or CLI session");
            assertSeparatedSessionTurn(secondConversation.getJSONArray("turns").getJSONObject(0), identityB, false);
            String untouchedB = secondConversation.toString();
            check(firstController.getConversations().length() == 2, "Independent tasks did not create two conversations");
            log("Second fixture conversation created its own CLI session: " + sessionB);

            secondController = new AgentController(isolated, runtime);
            check(!secondController.isRunning() && secondController.getConversations().length() == 2,
                    "Controller reconstruction lost conversations or revived a task");
            ControllerProbe resumedProbe = runSessionTurn(secondController, conversationA,
                    "PHONEAGENT_SESSION_CONTINUE_" + identityA, "SESSION_SECOND_" + identityA);
            JSONObject resumedConversation = secondController.getConversation(conversationA);
            JSONArray resumedTurns = resumedConversation.getJSONArray("turns");
            check(sessionA.equals(resumedConversation.getString("session_id")), "Continuation changed the Claude CLI session ID");
            check(resumedTurns.length() == 2, "Continuation did not append exactly one turn to the selected conversation");
            check(originalFirstTurn.equals(resumedTurns.getJSONObject(0).toString()), "Continuation rewrote its first completed turn");
            check(!resumedTurns.getJSONObject(0).getString("id").equals(resumedTurns.getJSONObject(1).getString("id")),
                    "Continuation reused the first turn ID");
            assertSeparatedSessionTurn(resumedTurns.getJSONObject(1), identityA, true);
            check(untouchedB.equals(secondController.getConversation(conversationB).toString()),
                    "Resuming A modified independent conversation B");
            check(secondController.getConversations().length() == 2
                            && conversationA.equals(secondController.getConversations().getJSONObject(0).getString("id")),
                    "Resumed conversation was duplicated or not moved to the top of history");
            check(resumedProbe.historyCallbacks.get() > 0 && resumedProbe.states.contains(AgentController.VisualState.ACTING),
                    "Continuation lacked history updates or a fresh phone-tool execution");
            TaskHistoryStore persisted = new TaskHistoryStore(directory);
            JSONObject persistedExpected = new JSONObject(resumedConversation.toString());
            persistedExpected.remove("can_continue"); persistedExpected.remove("continue_unavailable_reason");
            check(persistedExpected.toString().equals(persisted.getConversation(conversationA).toString()),
                    "Continued conversation did not survive store reconstruction");
            check(changedModelKeys.isEmpty(), "Resume changed user model configuration keys: " + changedModelKeys);
            log("Real CLI resumed A after B and Controller reconstruction; fixture proved original user + assistant context, fresh read and no cross-session content");
            assertConversationUi(secondController, conversationA, conversationB, identityA, identityB);
        } finally {
            AgentController finalSecond = secondController;
            runOnMainSync(() -> {
                firstController.stop();
                if (finalSecond != null) finalSecond.stop();
                OverlayService.setPetState(userController.getVisualState());
            });
            settings.unregisterOnSharedPreferenceChangeListener(observer);
            shutdownTestController(firstController);
            if (secondController != null) shutdownTestController(secondController);
            deleteTestTree(directory);
        }
        log("Android user history and model settings were untouched; fixture CLI session IDs above identify the two new Linux session files");
    }

    private ControllerProbe runSessionTurn(AgentController controller, String conversationId,
                                           String prompt, String resultMarker) throws Exception {
        ControllerProbe probe = new ControllerProbe();
        try {
            runOnMainSync(() -> {
                controller.setListener(probe);
                if (conversationId == null) controller.run(prompt, "http://127.0.0.1:18080", "phoneagent-smoke-session", "local-test-only");
                else controller.runInConversation(conversationId, prompt, "http://127.0.0.1:18080", "phoneagent-smoke-session", "local-test-only");
            });
            check(probe.finished.await(120, TimeUnit.SECONDS), "Session fixture task timed out: " + probe.status.get());
            check(probe.success.get() && !controller.isRunning(), "Session fixture task failed: " + probe.message.get() + "\n" + probe.output.get());
            check(probe.output.get().contains(resultMarker), "Session fixture did not reach its validated final response");
            check(controller.getVisualState() == AgentController.VisualState.SUCCESS, "Session task did not finish in SUCCESS");
            return probe;
        } finally {
            runOnMainSync(() -> controller.removeListener(probe));
        }
    }

    private void assertSeparatedSessionTurn(JSONObject turn, String identity, boolean resumed) throws Exception {
        String processMarker = "PROCESS_ONLY_" + identity + (resumed ? "_followup" : "_initial");
        String resultMarker = (resumed ? "SESSION_SECOND_" : "SESSION_FIRST_") + identity;
        String process = turn.getString("process"), result = turn.getString("result"), terminal = turn.getString("terminal");
        check("success".equals(turn.optString("state")), "Persisted fixture turn did not succeed");
        check(process.contains(processMarker), "Intermediate assistant text was not saved in process");
        check(result.contains(resultMarker) && result.contains("# 本地会话验证") && result.contains("**") && result.contains("```"),
                "Structured CLI final result or its Markdown was lost");
        check(!result.contains("PROCESS_ONLY_") && !result.contains("【任务结束】"),
                "Final result contains intermediate process or local terminal text");
        check(!process.contains(resultMarker), "Final assistant response was duplicated into the process section");
        check(terminal.contains("【任务结束】") && !terminal.contains(resultMarker) && !terminal.contains("PROCESS_ONLY_"),
                "Terminal record is not separate from model content");
    }

    private void shutdownTestController(AgentController controller) throws Exception {
        for (String name : new String[]{"worker", "controls"}) {
            Field field;
            try { field = AgentController.class.getDeclaredField(name); }
            catch (NoSuchFieldException unavailable) { continue; }
            field.setAccessible(true);
            java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) field.get(controller);
            executor.shutdown();
            check(executor.awaitTermination(15, TimeUnit.SECONDS), "Isolated Controller " + name + " did not finish during cleanup");
        }
    }

    /** Temporarily render isolated history through the real Activity; never send a UI prompt. */
    private void assertConversationUi(AgentController isolatedController, String conversationA, String conversationB,
                                      String identityA, String identityB) throws Exception {
        waitForMainActivityVisible();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            MainActivity activity = resumedMain;
            Field controllerField = null, selectedField = null, historyField = null, expandedField = null;
            Method render = null, refresh = null;
            AgentController previousController = null;
            String previousSelection = null, previousDraft = null;
            boolean previousHistory = true;
            Set<String> previousExpanded = null;
            EditText prompt = null;
            ScrollView scroll = null;
            int previousScroll = 0;
            try {
                controllerField = accessibleField(MainActivity.class, "controller");
                selectedField = accessibleField(MainActivity.class, "selectedConversationId");
                historyField = accessibleField(MainActivity.class, "showHistory");
                expandedField = accessibleField(MainActivity.class, "expandedProcesses");
                previousController = (AgentController) controllerField.get(activity);
                previousSelection = (String) selectedField.get(activity);
                previousHistory = historyField.getBoolean(activity);
                @SuppressWarnings("unchecked") Set<String> expanded = (Set<String>) expandedField.get(activity);
                previousExpanded = new HashSet<>(expanded);
                prompt = (EditText) accessibleField(MainActivity.class, "prompt").get(activity);
                previousDraft = prompt.getText().toString();
                scroll = (ScrollView) accessibleField(MainActivity.class, "contentScroll").get(activity);
                previousScroll = scroll.getScrollY();
                render = MainActivity.class.getDeclaredMethod("renderHistory", boolean.class); render.setAccessible(true);
                refresh = MainActivity.class.getDeclaredMethod("refreshState"); refresh.setAccessible(true);
                Method open = MainActivity.class.getDeclaredMethod("openConversation", String.class); open.setAccessible(true);
                controllerField.set(activity, isolatedController);
                selectedField.set(activity, ""); historyField.setBoolean(activity, true); expanded.clear();
                render.invoke(activity, true);
                View historyPanel = (View) accessibleField(MainActivity.class, "historyPanel").get(activity);
                String listText = renderedText(historyPanel);
                check(listText.contains("PHONEAGENT_SESSION_START_" + identityA)
                                && listText.contains("PHONEAGENT_SESSION_START_" + identityB),
                        "Real Activity history list did not render both isolated conversations");
                open.invoke(activity, conversationA);
                check(collectViews(historyPanel, MarkdownView.class).size() == 2, "A detail did not render two native Markdown results");
                String detailA = renderedText(historyPanel);
                check(detailA.contains("SESSION_FIRST_" + identityA) && detailA.contains("SESSION_SECOND_" + identityA)
                                && !detailA.contains("SESSION_FIRST_" + identityB), "A detail lost turns or included B content");
                check(!detailA.contains("PROCESS_ONLY_"), "Execution process was expanded by default");
                List<TextView> textViews = collectViews(historyPanel, TextView.class);
                List<TextView> expandButtons = new ArrayList<>();
                for (TextView view : textViews) if ("展开执行过程".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))
                    expandButtons.add(view);
                check(expandButtons.size() == 2, "A detail did not expose two collapsed process controls");
                check(expandButtons.get(0).performClick(), "Process control could not be clicked");
                check(renderedText(historyPanel).contains("PROCESS_ONLY_" + identityA + "_initial"),
                        "Expanding process did not show the first turn's intermediate text");
                TextView send = (TextView) accessibleField(MainActivity.class, "send").get(activity);
                check("继续对话".contentEquals(send.getText()) && send.isEnabled(), "A detail did not expose enabled Continue action");
                open.invoke(activity, conversationB);
                check(collectViews(historyPanel, MarkdownView.class).size() == 1, "B detail did not render its one native Markdown result");
                String detailB = renderedText(historyPanel);
                check(detailB.contains("SESSION_FIRST_" + identityB) && !detailB.contains("SESSION_FIRST_" + identityA)
                                && !detailB.contains("SESSION_SECOND_" + identityA), "Switching detail mixed conversation content");
            } catch (Throwable error) { failure.set(error); }
            finally {
                try {
                    if (previousController != null) controllerField.set(activity, previousController);
                    if (previousSelection != null) selectedField.set(activity, previousSelection);
                    if (historyField != null) historyField.setBoolean(activity, previousHistory);
                    if (previousExpanded != null) {
                        @SuppressWarnings("unchecked") Set<String> expanded = (Set<String>) expandedField.get(activity);
                        expanded.clear(); expanded.addAll(previousExpanded);
                    }
                    if (render != null) render.invoke(activity, true);
                    if (refresh != null) refresh.invoke(activity);
                    if (prompt != null && previousDraft != null) prompt.setText(previousDraft);
                    if (scroll != null) scroll.scrollTo(0, previousScroll);
                } catch (Throwable cleanup) { if (failure.get() == null) failure.set(cleanup); }
            }
        });
        if (failure.get() != null) throw new AssertionError("Isolated real-Activity history rendering failed", failure.get());
        log("Real Activity lists separate conversations, renders multiple Markdown results, collapses process and routes Continue; user view/draft restored");
    }

    private static Field accessibleField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static <T extends View> List<T> collectViews(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(collectViews(group.getChildAt(i), type));
        }
        return result;
    }

    private static String renderedText(View root) {
        StringBuilder result = new StringBuilder();
        for (TextView view : collectViews(root, TextView.class)) result.append(view.getText()).append('\n');
        return result.toString();
    }

    /** Delete only cache-backed fixture history; never delete a real user's conversation. */
    private void deleteHistory() throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "delete-smoke-" + UUID.randomUUID());
        check(!directory.exists(), "Unique deletion test directory already exists");
        AgentController userController = AgentController.get(getTargetContext());
        check(!userController.isRunning(), "Do not test deletion during a user task");
        long serviceWaitStarted = SystemClock.uptimeMillis();
        long serviceDeadline = serviceWaitStarted + 15000;
        log("Waiting for the runner to bind accessibility before delete lifecycle fixtures");
        while (!PhoneAccessibilityService.isEnabled() && SystemClock.uptimeMillis() < serviceDeadline)
            SystemClock.sleep(200);
        check(PhoneAccessibilityService.isEnabled(), "Accessibility did not bind within 15 seconds before delete fixtures");
        log("Accessibility bound before delete fixtures after " + (SystemClock.uptimeMillis() - serviceWaitStarted) + " ms");
        List<String> changedKeys = new CopyOnWriteArrayList<>();
        SharedPreferences settings = getTargetContext().getSharedPreferences("phoneagent", Context.MODE_PRIVATE);
        SharedPreferences.OnSharedPreferenceChangeListener observer = (preferences, key) -> changedKeys.add(key == null ? "<clear>" : key);
        settings.registerOnSharedPreferenceChangeListener(observer);
        AgentController runningController = null;
        ControllerProbe runningProbe = new ControllerProbe();
        try {
            long now = System.currentTimeMillis();
            File offlineDirectory = new File(directory, "offline");
            Context offlineContext = isolatedFilesContext(offlineDirectory);
            TaskHistoryStore store = new TaskHistoryStore(offlineDirectory);
            store.initialize(null);
            JSONObject older = fixtureConversation("保留旧记录", now - 2000);
            JSONObject latest = fixtureConversation("删除当前记录", now - 1000);
            String olderId = store.importConversation(older), latestId = store.importConversation(latest);
            String olderBefore = store.getConversation(olderId).toString();
            String cliSession = UUID.randomUUID().toString();
            store.bindSession(latestId, cliSession);
            File fakeSession = new File(offlineDirectory, "linux/home/.claude/projects/-home-agent/" + cliSession + ".jsonl");
            check(fakeSession.getParentFile().mkdirs(), "Could not make isolated CLI session fixture directory");
            try (FileOutputStream output = new FileOutputStream(fakeSession)) {
                output.write("{\"fixture\":\"preserve-cli-session\"}\n".getBytes(StandardCharsets.UTF_8));
            }
            long sessionLength = fakeSession.length();
            writeFixtureLastTask(offlineDirectory, store.getConversation(latestId));
            AgentController deleting = constructIsolatedController(offlineContext);
            check(latestId.equals(deleting.getActiveConversationId()), "Latest fixture was not the active persisted conversation");
            check(deleting.deleteConversation(latestId), "Delete API did not remove an existing current conversation");
            runOnMainSync(() -> { });
            check(deleting.getConversation(latestId) == null && deleting.getConversations().length() == 1,
                    "Deleted current conversation remains in Controller history");
            check(new LastTaskStore(offlineDirectory).read() == null, "Deleting the last-task conversation retained a resurrection source");
            check(fakeSession.isFile() && fakeSession.length() == sessionLength, "History deletion altered the separate CLI session file");
            TaskHistoryStore afterDelete = new TaskHistoryStore(offlineDirectory);
            afterDelete.initialize(null);
            check(olderBefore.equals(afterDelete.getConversation(olderId).toString()), "Deleting one conversation modified unrelated history");
            AgentController rebuilt = constructIsolatedController(offlineContext);
            check(rebuilt.getConversation(latestId) == null && rebuilt.getConversations().length() == 1
                            && olderId.equals(rebuilt.getActiveConversationId()), "Deleted current conversation revived after Controller reconstruction");
            String remainingOutput = controllerOutput(rebuilt);
            runOnMainSync(rebuilt::resetToIdle);
            runOnMainSync(() -> { });
            JSONObject resetRecord = new LastTaskStore(offlineDirectory).read();
            check(resetRecord != null && resetRecord.optBoolean("acknowledged")
                            && olderId.equals(resetRecord.optString("conversation_id"))
                            && remainingOutput.equals(resetRecord.optString("output")),
                    "Reset after deletion did not acknowledge the remaining conversation without losing its output");
            check(olderBefore.equals(new TaskHistoryStore(offlineDirectory).getConversation(olderId).toString()),
                    "Reset after deletion changed the remaining historical conversation");
            rebuilt = constructIsolatedController(offlineContext);
            check((rebuilt.getVisualState() == AgentController.VisualState.IDLE
                            || rebuilt.getVisualState() == AgentController.VisualState.NEEDS_INPUT)
                            && remainingOutput.equals(controllerOutput(rebuilt)) && rebuilt.getConversation(latestId) == null,
                    "Reset after deletion did not survive Controller reconstruction, or revived the deleted record");
            log("Reset after deleting latest record acknowledges only the remaining conversation and stays idle/setup after reconstruction");
            check(!rebuilt.deleteConversation(latestId), "Deleting an absent ID did not return false");
            check(rebuilt.deleteConversation(olderId), "Could not delete the last remaining history record");
            runOnMainSync(() -> { });
            AgentController empty = constructIsolatedController(offlineContext);
            check(empty.getConversations().length() == 0 && empty.getActiveConversationId().isEmpty()
                            && controllerOutput(empty).isEmpty(), "Deleting all records revived history or retained an old task presentation");
            check(empty.getVisualState() == AgentController.VisualState.IDLE
                            || empty.getVisualState() == AgentController.VisualState.NEEDS_INPUT, "Empty deleted history did not restore idle/setup state");
            check(fakeSession.isFile() && fakeSession.length() == sessionLength, "Deleting all app history also removed CLI session evidence");
            log("Current/last-task deletion persists, unrelated record stays byte-equivalent, empty history does not revive, CLI file stays intact");

            File legacyDirectory = new File(directory, "legacy");
            JSONObject legacyTask = syntheticTurn("success", "", "旧记录过程", "旧记录结果", now - 1000).put("version", 1);
            new LastTaskStore(legacyDirectory).write(legacyTask);
            AgentController legacyController = constructIsolatedController(isolatedFilesContext(legacyDirectory));
            JSONArray legacyHistory = legacyController.getConversations();
            check(legacyHistory.length() == 1 && legacyHistory.getJSONObject(0).optBoolean("legacy"), "Legacy fixture did not migrate");
            check(legacyController.deleteConversation(legacyHistory.getJSONObject(0).getString("id")), "Could not delete migrated legacy conversation");
            runOnMainSync(() -> { });
            AgentController legacyRebuilt = constructIsolatedController(isolatedFilesContext(legacyDirectory));
            check(legacyRebuilt.getConversations().length() == 0 && new LastTaskStore(legacyDirectory).read() == null,
                    "Deleted migrated legacy record revived from last-task.json");
            // Even an old supplied snapshot cannot remigrate after an explicit empty history was saved.
            TaskHistoryStore legacyStore = new TaskHistoryStore(legacyDirectory);
            legacyStore.initialize(legacyTask);
            check(legacyStore.summaries().length() == 0, "Deleted empty history forgot its one-time migration marker");
            log("Deleted legacy migration remains deleted even when an old last-task snapshot is supplied again");

            check(PhoneAccessibilityService.isEnabled(), "Accessibility disconnected after the deletion persistence fixtures");
            LinuxRuntime runtime = new LinuxRuntime(getTargetContext());
            check(runtime.isInstalled(), "Run mode=runtime before mode=delete");
            File runningDirectory = new File(directory, "running");
            TaskHistoryStore runningStore = new TaskHistoryStore(runningDirectory);
            runningStore.initialize(null);
            String removable = runningStore.importConversation(fixtureConversation("运行时可删除的旧记录", now - 2000));
            runningController = new AgentController(isolatedFilesContext(runningDirectory), runtime);
            AgentController active = runningController;
            runOnMainSync(() -> {
                active.setListener(runningProbe);
                active.run("仅用于删除生命周期测试，等待本地不可达服务。", "http://127.0.0.1:9", "phoneagent-smoke-model", "local-test-only");
            });
            Field processField = accessibleField(AgentController.class, "process");
            long launchDeadline = SystemClock.uptimeMillis() + 5000;
            Process process = null;
            while (active.isRunning() && SystemClock.uptimeMillis() < launchDeadline) {
                process = (Process) processField.get(active);
                if (process != null && process.isAlive()) break;
                SystemClock.sleep(50);
            }
            check(active.isRunning() && process != null && process.isAlive(), "No real CLI process for running deletion protection");
            String activeId = active.getActiveConversationId();
            long epoch = ((AtomicLong) accessibleField(AgentController.class, "presentationEpoch").get(active)).get();
            boolean rejected = false;
            try { active.deleteConversation(activeId); }
            catch (IOException expected) { rejected = true; }
            check(rejected && active.getConversation(activeId) != null, "Deletion did not reject the currently running conversation");
            check(active.deleteConversation(removable), "Deleting unrelated old history was blocked by a running task");
            runOnMainSync(() -> { });
            check(active.isRunning() && activeId.equals(active.getActiveConversationId())
                            && processField.get(active) == process && process.isAlive()
                            && ((AtomicLong) accessibleField(AgentController.class, "presentationEpoch").get(active)).get() == epoch,
                    "Deleting unrelated history disturbed the running task/process/epoch");
            check(active.getConversation(removable) == null && runningProbe.finishedCallbacks.get() == 0,
                    "Deleting old history left it present or fabricated task completion");
            JSONObject activeTask = new LastTaskStore(runningDirectory).read();
            check(activeTask != null && activeId.equals(activeTask.optString("conversation_id")) && "running".equals(activeTask.optString("state")),
                    "Deleting old history cleared or changed the active last-task record");
            runOnMainSync(active::stop);
            check(runningProbe.finished.await(15, TimeUnit.SECONDS) && !active.isRunning(), "Running-delete fixture did not stop cleanly");
            check(!runningProbe.success.get(), "Unreachable-loopback deletion fixture unexpectedly succeeded");
            TaskHistoryStore runningRebuilt = new TaskHistoryStore(runningDirectory);
            runningRebuilt.initialize(null);
            check(runningRebuilt.getConversation(removable) == null && runningRebuilt.getConversation(activeId) != null,
                    "Old-history deletion did not persist alongside the stopped current task");
            check(changedKeys.isEmpty(), "Deletion touched user preference keys: " + changedKeys);
            log("Running conversation rejects deletion; deleting older history preserves process, active identity, epoch and current record");
        } finally {
            AgentController active = runningController;
            runOnMainSync(() -> {
                if (active != null) { active.stop(); active.removeListener(runningProbe); }
                OverlayService.setPetState(userController.getVisualState());
            });
            settings.unregisterOnSharedPreferenceChangeListener(observer);
            if (runningController != null) shutdownTestController(runningController);
            deleteTestTree(directory);
        }
    }

    private JSONObject fixtureConversation(String title, long time) throws Exception {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("title", title)
                .put("created_at", time).put("updated_at", time).put("session_id", "")
                .put("turns", new JSONArray().put(syntheticTurn("success", title, "本地过程 " + title, "# 本地结果\n\n" + title, time)));
    }

    private void writeFixtureLastTask(File directory, JSONObject conversation) throws Exception {
        JSONArray turns = conversation.getJSONArray("turns");
        JSONObject last = turns.getJSONObject(turns.length() - 1);
        new LastTaskStore(directory).write(new JSONObject(last.toString()).put("version", 1)
                .put("conversation_id", conversation.getString("id")).put("turn_id", last.getString("id"))
                .put("session_id", conversation.optString("session_id")));
    }

    /** Real Activity navigation and touchscreen swipe-to-reveal, backed only by isolated fixtures. */
    private void historyUi() throws Exception {
        File directory = new File(getTargetContext().getCacheDir(), "history-ui-smoke-" + UUID.randomUUID());
        check(!directory.exists(), "Unique history UI test directory already exists");
        check(!AgentController.get(getTargetContext()).isRunning(), "Do not test history UI during a user task");
        TaskHistoryStore store = new TaskHistoryStore(directory);
        store.initialize(null);
        long now = System.currentTimeMillis();
        List<String> identities = new ArrayList<>();
        for (int i = 0; i < 5; i++) identities.add(store.importConversation(fixtureConversation("滑动删除测试 " + i, now - i * 1000L)));
        writeFixtureLastTask(directory, store.getConversation(identities.get(0)));
        AgentController isolated = new AgentController(isolatedFilesContext(directory), new LinuxRuntime(getTargetContext()));
        AtomicReference<HistoryUiSnapshot> saved = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            runOnMainSync(() -> {
                try {
                    HistoryUiSnapshot snapshot = new HistoryUiSnapshot(resumedMain);
                    saved.set(snapshot);
                    check(!snapshot.historyField.getBoolean(new MainActivity()), "Fresh Activity unexpectedly defaults to the recent-task list");
                    snapshot.controllerField.set(resumedMain, isolated);
                    snapshot.drafts.clear(); snapshot.expanded.clear();
                    snapshot.selectedField.set(resumedMain, ""); snapshot.historyField.setBoolean(resumedMain, false);
                    snapshot.prompt.setText("");
                    snapshot.setupPanel.setVisibility(View.GONE); snapshot.setupToggle.setText("连接与设置  ›");
                    snapshot.render.invoke(resumedMain, true); snapshot.refresh.invoke(resumedMain);
                    check(renderedText(snapshot.historyPanel).contains("想让月薪喵做什么"), "Default new-task view is not distinct from the history list");
                    check(!hasScrollAncestor(snapshot.historyButton) && !hasScrollAncestor(snapshot.setupToggle),
                            "Task history or connection/settings entry is inside scrolling content");
                    check("打开最近任务".contentEquals(snapshot.historyButton.getContentDescription())
                                    && "连接与设置".contentEquals(snapshot.setupToggle.getContentDescription()),
                            "Top navigation lacks explicit history/settings semantics");
                    check(snapshot.setupToggle.performClick(), "Connection/settings entry did not respond");
                    check(snapshot.setupPanel.getVisibility() == View.VISIBLE, "Connection/settings entry did not reveal settings");
                    check(snapshot.historyButton.performClick(), "Task-record entry did not respond");
                    check(snapshot.historyField.getBoolean(resumedMain) && snapshot.setupPanel.getVisibility() == View.GONE
                                    && snapshot.historyButton.isSelected(), "Task-record entry did not clearly switch to recent list and close setup");
                    check(collectViews(snapshot.historyPanel, SwipeDeleteRow.class).size() == 5,
                            "Recent task list did not contain the five isolated fixtures");
                } catch (Throwable error) { failure.set(error); }
            });
            rethrowUiFailure(failure);
            waitForIdleSync();
            HistoryUiSnapshot snapshot = saved.get();
            Rect settingsBefore = globalBounds(snapshot.setupToggle);
            Rect historyBefore = globalBounds(snapshot.historyButton);
            runOnMainSync(() -> snapshot.scroll.scrollTo(0, snapshot.scroll.getChildAt(0).getHeight()));
            waitForIdleSync();
            check(settingsBefore.equals(globalBounds(snapshot.setupToggle)) && historyBefore.equals(globalBounds(snapshot.historyButton)),
                    "Top history/settings entries moved when content scrolled");
            runOnMainSync(() -> {
                snapshot.historyButton.performClick();
            });
            positionHistoryRows(snapshot);
            SwipeDeleteRow first = firstSwipeRow(snapshot.historyPanel);
            StringBuffer touchEvidence = new StringBuffer();
            runOnMainSync(() -> {
                first.setOnTouchListener((view, event) -> { appendTouchEvidence(touchEvidence, "row", event); return false; });
                first.getContentView().setOnTouchListener((view, event) -> { appendTouchEvidence(touchEvidence, "content", event); return false; });
                first.getDeleteButton().setOnTouchListener((view, event) -> { appendTouchEvidence(touchEvidence, "delete", event); return false; });
            });
            Rect firstBounds = visibleBounds(first);
            check(!first.isRevealed() && !first.getDeleteButton().isEnabled(), "Delete action was exposed before a horizontal swipe");
            int beforeCount = isolated.getConversations().length();
            touchGesture(firstBounds.left + firstBounds.width() * .7f, firstBounds.centerY(),
                    firstBounds.left + firstBounds.width() * .68f, Math.min(firstBounds.bottom - 8, firstBounds.centerY() + firstBounds.height() * .35f));
            waitForIdleSync();
            check(!first.isRevealed() && isolated.getConversations().length() == beforeCount,
                    "Predominantly vertical gesture revealed or deleted a conversation");
            check(historyMode(snapshot), "Vertical list gesture opened a conversation");
            logSwipeEvidence("after vertical", snapshot, first, touchEvidence);
            positionHistoryRows(snapshot);
            firstBounds = visibleBounds(first);
            logSwipeEvidence("before left swipe", snapshot, first, touchEvidence);
            touchGesture(firstBounds.left + firstBounds.width() * .82f, firstBounds.centerY(),
                    firstBounds.left + firstBounds.width() * .18f, firstBounds.centerY());
            waitForIdleSync();
            logSwipeEvidence("after left swipe", snapshot, first, touchEvidence);
            check(first.isRevealed() && first.getDeleteButton().isEnabled(), "Left swipe did not reveal the right-side Delete action");
            check(historyMode(snapshot) && isolated.getConversations().length() == beforeCount,
                    "Left swipe opened or deleted the conversation without a Delete tap");
            Rect deleteBounds = visibleBounds(first.getDeleteButton());
            check(deleteBounds.right >= firstBounds.right - 4 && deleteBounds.centerX() > firstBounds.centerX(),
                    "Revealed Delete action is not on the right side of its row");
            touchGesture(deleteBounds.centerX(), deleteBounds.top + deleteBounds.height() * .25f,
                    deleteBounds.centerX(), deleteBounds.top + deleteBounds.height() * .75f);
            waitForIdleSync();
            check(historyMode(snapshot) && isolated.getConversations().length() == beforeCount
                            && isolated.getConversation(identities.get(0)) != null,
                    "Vertical movement starting on exposed Delete accidentally deleted or opened the conversation");
            runOnMainSync(first::close);
            positionHistoryRows(snapshot);
            firstBounds = visibleBounds(first);
            touchGesture(firstBounds.left + firstBounds.width() * .82f, firstBounds.centerY(),
                    firstBounds.left + firstBounds.width() * .18f, firstBounds.centerY());
            waitForIdleSync();
            check(first.isRevealed(), "Could not re-reveal Delete after cancelling a vertical Delete-region gesture");
            touchGesture(firstBounds.left + firstBounds.width() * .18f, firstBounds.centerY(),
                    firstBounds.left + firstBounds.width() * .80f, firstBounds.centerY());
            waitForIdleSync();
            check(!first.isRevealed() && !first.getDeleteButton().isEnabled()
                            && historyMode(snapshot) && isolated.getConversations().length() == beforeCount,
                    "Right swipe did not close the Delete action without opening/deleting");
            firstBounds = visibleBounds(first);
            touchGesture(firstBounds.left + firstBounds.width() * .82f, firstBounds.centerY(),
                    firstBounds.left + firstBounds.width() * .18f, firstBounds.centerY());
            waitForIdleSync();
            check(first.isRevealed() && isolated.getConversation(identities.get(0)) != null, "Reveal itself removed the fixture conversation");
            deleteBounds = visibleBounds(first.getDeleteButton());
            touchTap(deleteBounds.centerX(), deleteBounds.centerY());
            waitForIdleSync();
            check(isolated.getConversation(identities.get(0)) == null && isolated.getConversations().length() == beforeCount - 1,
                    "Tapping the revealed Delete action did not delete exactly the chosen fixture");
            check(historyMode(snapshot), "Deleting from recent list unexpectedly navigated into a conversation");
            check(new LastTaskStore(directory).read() == null, "UI deletion left the fixture's associated last-task record");
            TaskHistoryStore rebuilt = new TaskHistoryStore(directory);
            rebuilt.initialize(null);
            check(rebuilt.getConversation(identities.get(0)) == null && rebuilt.summaries().length() == 4,
                    "UI-deleted fixture revived when history was reconstructed");
            positionHistoryRows(snapshot);
            SwipeDeleteRow remaining = firstSwipeRow(snapshot.historyPanel);
            Rect remainingBounds = visibleBounds(remaining);
            touchTap(remainingBounds.centerX(), remainingBounds.centerY());
            waitForIdleSync();
            check(!historyMode(snapshot), "Ordinary tap on a closed row did not open its conversation");
            runOnMainSync(() -> snapshot.historyButton.performClick());
            waitForIdleSync();
            check(historyMode(snapshot) && collectViews(snapshot.historyPanel, SwipeDeleteRow.class).size() == 4,
                    "Task-record entry did not return from detail to the updated recent list");
            log("Real touch: vertical movement leaves list intact; left swipe only reveals right-side Delete; right swipe closes; only Delete tap removes fixture");
            log("Fresh view, explicit task-record navigation, fixed top connection/settings, persistence and ordinary row tap verified");
        } finally {
            runOnMainSync(() -> {
                try { if (saved.get() != null) saved.get().restore(); }
                catch (Throwable error) { failure.set(error); }
            });
            deleteTestTree(directory);
            rethrowUiFailure(failure);
        }
    }

    private static boolean hasScrollAncestor(View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof ScrollView) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private boolean historyMode(HistoryUiSnapshot snapshot) throws Exception {
        AtomicBoolean value = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { value.set(snapshot.historyField.getBoolean(resumedMain)); } catch (Throwable error) { failure.set(error); } });
        rethrowUiFailure(failure);
        return value.get();
    }

    private SwipeDeleteRow firstSwipeRow(View root) {
        AtomicReference<SwipeDeleteRow> result = new AtomicReference<>();
        runOnMainSync(() -> {
            List<SwipeDeleteRow> rows = collectViews(root, SwipeDeleteRow.class);
            check(!rows.isEmpty(), "No swipeable fixture rows were rendered");
            result.set(rows.get(0));
        });
        return result.get();
    }

    private Rect globalBounds(View view) {
        Rect result = new Rect();
        runOnMainSync(() -> {
            int[] location = new int[2]; view.getLocationOnScreen(location);
            result.set(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        });
        return result;
    }

    private Rect visibleBounds(View view) {
        Rect result = new Rect();
        AtomicBoolean visible = new AtomicBoolean();
        runOnMainSync(() -> {
            visible.set(view.getLocalVisibleRect(result));
            int[] location = new int[2]; view.getLocationOnScreen(location);
            result.offset(location[0], location[1]);
        });
        check(visible.get() && result.width() > 20 && result.height() > 20, "Fixture touch target is not sufficiently visible");
        return result;
    }

    /** Cancel any previous ScrollView fling before repositioning a fixture, without a timed sleep. */
    private void positionHistoryRows(HistoryUiSnapshot snapshot) {
        runOnMainSync(() -> {
            long now = SystemClock.uptimeMillis();
            float x = snapshot.scroll.getWidth() / 2f, y = snapshot.scroll.getHeight() / 2f;
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, x, y, 0);
            try {
                // ScrollView's own ACTION_DOWN aborts its OverScroller; CANCEL cannot click any row.
                snapshot.scroll.onTouchEvent(down);
                snapshot.scroll.onTouchEvent(cancel);
            } finally { down.recycle(); cancel.recycle(); }
            snapshot.scroll.scrollTo(0, snapshot.historyPanel.getTop());
        });
        waitForIdleSync();
    }

    private static void appendTouchEvidence(StringBuffer evidence, String surface, MotionEvent event) {
        int action = event.getActionMasked();
        // DOWN/UP/CANCEL prove hit routing; first MOVE proves the raw/local coordinate relation.
        if (action == MotionEvent.ACTION_MOVE && evidence.indexOf(surface + ":MOVE") >= 0) return;
        String name = action == MotionEvent.ACTION_DOWN ? "DOWN" : action == MotionEvent.ACTION_UP ? "UP"
                : action == MotionEvent.ACTION_CANCEL ? "CANCEL" : action == MotionEvent.ACTION_MOVE ? "MOVE" : Integer.toString(action);
        evidence.append(surface).append(':').append(name).append(" raw=")
                .append(Math.round(event.getRawX())).append(',').append(Math.round(event.getRawY()))
                .append(" local=").append(Math.round(event.getX())).append(',').append(Math.round(event.getY())).append("; ");
    }

    private void logSwipeEvidence(String stage, HistoryUiSnapshot snapshot, SwipeDeleteRow row, StringBuffer touches) {
        AtomicReference<String> geometry = new AtomicReference<>();
        runOnMainSync(() -> {
            Rect local = new Rect(), global = new Rect(), viewport = new Rect();
            row.getLocalVisibleRect(local); row.getGlobalVisibleRect(global); snapshot.scroll.getGlobalVisibleRect(viewport);
            int[] screen = new int[2]; row.getLocationOnScreen(screen);
            List<SwipeDeleteRow> current = collectViews(snapshot.historyPanel, SwipeDeleteRow.class);
            geometry.set("attached=" + row.isAttachedToWindow() + ", currentFirst=" + (!current.isEmpty() && current.get(0) == row)
                    + ", scrollY=" + snapshot.scroll.getScrollY() + ", screen=" + Arrays.toString(screen)
                    + ", localVisible=" + local + ", globalVisible=" + global + ", viewport=" + viewport
                    + ", translation=" + row.getContentView().getTranslationX() + ", revealed=" + row.isRevealed()
                    + ", deleteEnabled=" + row.getDeleteButton().isEnabled());
        });
        log("Swipe evidence " + stage + ": " + geometry.get() + "; events=" + touches);
        touches.setLength(0);
    }

    private void touchGesture(float startX, float startY, float endX, float endY) {
        long down = SystemClock.uptimeMillis();
        sendTouch(down, MotionEvent.ACTION_DOWN, startX, startY);
        for (int i = 1; i <= 12; i++) {
            SystemClock.sleep(16);
            sendTouch(down, MotionEvent.ACTION_MOVE, startX + (endX - startX) * i / 12,
                    startY + (endY - startY) * i / 12);
        }
        sendTouch(down, MotionEvent.ACTION_UP, endX, endY);
    }

    private void touchTap(float x, float y) {
        long down = SystemClock.uptimeMillis();
        sendTouch(down, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(45);
        sendTouch(down, MotionEvent.ACTION_UP, x, y);
    }

    private void sendTouch(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { sendPointerSync(event); } finally { event.recycle(); }
    }

    private static void rethrowUiFailure(AtomicReference<Throwable> failure) {
        if (failure.get() != null) throw new AssertionError("History UI fixture failed", failure.get());
    }

    private final class HistoryUiSnapshot {
        final MainActivity activity;
        final Field controllerField, selectedField, historyField;
        final Method render, refresh;
        final AgentController controller;
        final String selection, draft, setupText, unsavedOutput, unsavedId;
        final boolean showHistory;
        final int setupVisibility, scrollY, turnLimit;
        final EditText prompt;
        final TextView historyButton, setupToggle;
        final View historyPanel, setupPanel;
        final ScrollView scroll;
        final Map<String, String> drafts, savedDrafts;
        final Set<String> expanded, savedExpanded;
        @SuppressWarnings("unchecked") HistoryUiSnapshot(MainActivity value) throws Exception {
            activity = value;
            controllerField = accessibleField(MainActivity.class, "controller");
            selectedField = accessibleField(MainActivity.class, "selectedConversationId");
            historyField = accessibleField(MainActivity.class, "showHistory");
            controller = (AgentController) controllerField.get(activity);
            selection = (String) selectedField.get(activity); showHistory = historyField.getBoolean(activity);
            prompt = (EditText) accessibleField(MainActivity.class, "prompt").get(activity); draft = prompt.getText().toString();
            historyButton = (TextView) accessibleField(MainActivity.class, "historyButton").get(activity);
            setupToggle = (TextView) accessibleField(MainActivity.class, "setupToggle").get(activity); setupText = setupToggle.getText().toString();
            historyPanel = (View) accessibleField(MainActivity.class, "historyPanel").get(activity);
            setupPanel = (View) accessibleField(MainActivity.class, "setupPanel").get(activity); setupVisibility = setupPanel.getVisibility();
            scroll = (ScrollView) accessibleField(MainActivity.class, "contentScroll").get(activity); scrollY = scroll.getScrollY();
            drafts = (Map<String, String>) accessibleField(MainActivity.class, "drafts").get(activity); savedDrafts = new HashMap<>(drafts);
            expanded = (Set<String>) accessibleField(MainActivity.class, "expandedProcesses").get(activity); savedExpanded = new HashSet<>(expanded);
            turnLimit = accessibleField(MainActivity.class, "visibleTurnLimit").getInt(activity);
            unsavedOutput = (String) accessibleField(MainActivity.class, "unsavedOutput").get(activity);
            unsavedId = (String) accessibleField(MainActivity.class, "unsavedConversationId").get(activity);
            render = MainActivity.class.getDeclaredMethod("renderHistory", boolean.class); render.setAccessible(true);
            refresh = MainActivity.class.getDeclaredMethod("refreshState"); refresh.setAccessible(true);
        }
        void restore() throws Exception {
            controllerField.set(activity, controller); selectedField.set(activity, selection); historyField.setBoolean(activity, showHistory);
            drafts.clear(); drafts.putAll(savedDrafts); expanded.clear(); expanded.addAll(savedExpanded);
            accessibleField(MainActivity.class, "visibleTurnLimit").setInt(activity, turnLimit);
            accessibleField(MainActivity.class, "unsavedOutput").set(activity, unsavedOutput);
            accessibleField(MainActivity.class, "unsavedConversationId").set(activity, unsavedId);
            render.invoke(activity, true); refresh.invoke(activity);
            prompt.setText(draft); setupPanel.setVisibility(setupVisibility); setupToggle.setText(setupText);
            scroll.scrollTo(0, scrollY);
            OverlayService.setPetState(controller.getVisualState());
        }
    }

    /** Native Markdown presentation and inert unsafe destinations; no link is clicked. */

    /** Native Markdown presentation and inert unsafe destinations; no link is clicked. */
    private void markdown() throws Exception {
        String source = "# 一级标题\n\n## 二级标题\n\n普通段落 **加粗**、*斜体*、~~删除~~ 和 `行内代码`。\n\n"
                + "- 无序条目\n1. 有序条目\n\n> 引用内容\n\n```text\n**代码保留符号**\n第二行\n```\n\n"
                + "| 名称 | 状态 |\n| --- | --- |\n| 月薪喵 | 可用 |\n\n"
                + "[安全项目](https://github.com/DsDoctor/PhoneAgent) "
                + "[脚本](javascript:alert(1)) [文件](file:///data/local/tmp/test) "
                + "[意图](intent://example/#Intent;end) [内嵌](data:text/html,test) "
                + "[相对](/local) [凭据](https://user:password@example.com/)";
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                MarkdownView view = new MarkdownView(getTargetContext());
                view.setMarkdown(source);
                List<TextView> texts = collectViews(view, TextView.class);
                String rendered = renderedText(view);
                check(rendered.contains("一级标题") && rendered.contains("二级标题") && rendered.contains("引用内容")
                                && rendered.contains("无序条目") && rendered.contains("有序条目") && rendered.contains("月薪喵"),
                        "Markdown lost heading/list/quote/table content");
                boolean heading = false, bold = false, italic = false, strike = false, inlineCode = false, fencedCode = false;
                int linkCount = 0, clickableCount = 0;
                for (TextView text : texts) {
                    CharSequence content = text.getText();
                    if ("一级标题".contentEquals(content)) {
                        heading = text.getTypeface() != null && text.getTypeface().isBold() && text.getTextSize() > 15 * getTargetContext().getResources().getDisplayMetrics().scaledDensity;
                    }
                    if (content.toString().contains("**代码保留符号**")) {
                        fencedCode = Typeface.MONOSPACE.equals(text.getTypeface()) && text.getParent() instanceof HorizontalScrollView;
                        if (content instanceof Spanned)
                            check(((Spanned) content).getSpans(0, content.length(), StyleSpan.class).length == 0,
                                    "Fenced code was interpreted as inline Markdown");
                    }
                    if (!(content instanceof Spanned)) continue;
                    Spanned spanned = (Spanned) content;
                    for (StyleSpan span : spanned.getSpans(0, content.length(), StyleSpan.class)) {
                        String covered = content.subSequence(spanned.getSpanStart(span), spanned.getSpanEnd(span)).toString();
                        if (span.getStyle() == Typeface.BOLD && "加粗".equals(covered)) bold = true;
                        if (span.getStyle() == Typeface.ITALIC && "斜体".equals(covered)) italic = true;
                    }
                    for (StrikethroughSpan span : spanned.getSpans(0, content.length(), StrikethroughSpan.class))
                        if ("删除".contentEquals(content.subSequence(spanned.getSpanStart(span), spanned.getSpanEnd(span)))) strike = true;
                    for (TypefaceSpan span : spanned.getSpans(0, content.length(), TypefaceSpan.class))
                        if ("monospace".equals(span.getFamily()) && "行内代码".contentEquals(content.subSequence(spanned.getSpanStart(span), spanned.getSpanEnd(span)))) inlineCode = true;
                    for (URLSpan span : spanned.getSpans(0, content.length(), URLSpan.class)) {
                        linkCount++;
                        check("https://github.com/DsDoctor/PhoneAgent".equals(span.getURL()), "Unsafe destination became a URLSpan");
                    }
                    clickableCount += spanned.getSpans(0, content.length(), ClickableSpan.class).length;
                }
                check(heading && bold && italic && strike && inlineCode && fencedCode,
                        "Native Markdown styles missing: heading=" + heading + ", bold=" + bold + ", italic=" + italic
                                + ", strike=" + strike + ", inlineCode=" + inlineCode + ", fencedCode=" + fencedCode);
                check(collectViews(view, TableLayout.class).size() == 1 && collectViews(view, HorizontalScrollView.class).size() >= 2,
                        "Table/code did not use native horizontal scrolling containers");
                check(linkCount == 1 && clickableCount == 1, "Markdown created unexpected clickable destinations");
                for (String label : new String[]{"脚本", "文件", "意图", "内嵌", "相对", "凭据"})
                    check(rendered.contains(label), "Blocked-link label was lost: " + label);
                for (String url : new String[]{"javascript:alert(1)", "file:///data/local/tmp/test", "intent://example/#Intent;end",
                        "data:text/html,test", "/relative", "//example.com/path", "https://user:password@example.com/",
                        "https://example.com:65536/", "https://example.com\\path", "https://example.com/\npath", "https:///missing-host"})
                    check(MarkdownView.safeWebUrl(url) == null, "Unsafe URL passed the native-link validator: " + url);
                check(MarkdownView.safeWebUrl("http://example.com/path?q=1#part") != null
                                && MarkdownView.safeWebUrl("https://example.com/path") != null, "Ordinary absolute web URLs were rejected");
                View firstChild = view.getChildAt(0);
                view.setMarkdown(source);
                check(firstChild == view.getChildAt(0), "Identical Markdown unnecessarily rebuilt the view tree");
                view.setMarkdown("新的结果 **已更新**");
                check(renderedText(view).contains("新的结果 已更新") && !renderedText(view).contains("一级标题"),
                        "Replacing Markdown retained stale previous result nodes");
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Native Markdown regression", failure.get());
        log("Native headings, inline styles, lists, quote, fenced code, table and safe link spans verified; no link opened or resource fetched");
    }

    private AgentController constructIsolatedController(Context context) throws Exception {
        Constructor<AgentController> constructor = AgentController.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        try { return constructor.newInstance(context); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    private void waitForControllerState(AgentController controller, AgentController.VisualState expected) {
        long deadline = SystemClock.uptimeMillis() + 2000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (controller.getVisualState() == expected) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Controller state did not reach " + expected + ": " + controller.getVisualState());
    }

    private String controllerOutput(AgentController controller) throws Exception {
        Field field = AgentController.class.getDeclaredField("lastOutput");
        field.setAccessible(true);
        AtomicReference<String> value = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try { value.set((String) field.get(controller)); }
            catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        return value.get();
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
        final AtomicInteger finishedCallbacks = new AtomicInteger();
        final AtomicInteger historyCallbacks = new AtomicInteger();
        final List<AgentController.VisualState> states = new CopyOnWriteArrayList<>();
        @Override public void onVisualState(AgentController.VisualState state) { states.add(state); }
        @Override public void onStatus(String value) { status.set(value); }
        @Override public void onOutput(String value) { output.set(value); }
        @Override public void onHistoryChanged() { historyCallbacks.incrementAndGet(); }
        @Override public void onFinished(boolean ok, String value) {
            finishedCallbacks.incrementAndGet();
            success.set(ok);
            message.set(value);
            finished.countDown();
        }
    }
}
