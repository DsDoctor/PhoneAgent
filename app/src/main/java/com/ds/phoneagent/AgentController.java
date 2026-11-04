package com.ds.phoneagent;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns one task at a time; the actual agent loop belongs to Claude Code. */
public final class AgentController {
    public enum VisualState { IDLE, PREPARING, THINKING, ACTING, SUCCESS, NEEDS_INPUT, ERROR, STOPPING, STOPPED }
    public interface Listener {
        void onStatus(String status);
        void onOutput(String output);
        void onFinished(boolean success, String message);
        default void onVisualState(VisualState state) { }
    }
    private static AgentController instance;
    public static synchronized AgentController get(Context context) {
        if (instance == null) instance = new AgentController(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final LinuxRuntime runtime;
    private final LastTaskStore taskStore;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Listener listener;
    private volatile Process process;
    private volatile PhoneToolServer bridge;
    private volatile boolean running, cancelled, finishing;
    private volatile VisualState visualState = VisualState.IDLE;
    private final AtomicLong presentationEpoch = new AtomicLong();
    private volatile String lastStatus = "先准备运行环境，再配置模型和手机权限。";
    private volatile String lastOutput = "";

    private AgentController(Context context) {
        this.context = context;
        runtime = new LinuxRuntime(context);
        taskStore = new LastTaskStore(context);
        if (runtime.isInstalled()) lastStatus = "运行环境已就绪，连接模型后即可执行。";
        else visualState = VisualState.NEEDS_INPUT;
        JSONObject previous = taskStore.recoverInterrupted();
        if (previous != null) {
            lastStatus = previous.optString("status");
            lastOutput = previous.optString("output");
            String state = previous.optString("state");
            visualState = state.equals("success") ? VisualState.SUCCESS
                    : state.equals("stopped") ? VisualState.STOPPED : VisualState.ERROR;
            if (previous.optBoolean("acknowledged", false)) {
                boolean ready = runtime.isInstalled();
                visualState = ready ? VisualState.IDLE : VisualState.NEEDS_INPUT;
                lastStatus = ready ? "已重置为待命，可以开始新任务。" : "运行环境尚未就绪，请先准备运行环境。";
            }
        }
        // A persisted record can restore presentation, never a running process.
    }
    public void setListener(Listener listener) {
        this.listener = listener;
        main.post(() -> { if (this.listener == listener) {
            listener.onStatus(lastStatus); listener.onOutput(lastOutput); listener.onVisualState(visualState);
        } });
    }
    public void removeListener(Listener listener) { if (this.listener == listener) this.listener = null; }
    public boolean isRunning() { return running; }
    public boolean isReady() { return runtime.isInstalled(); }
    public VisualState getVisualState() { return visualState; }
    public boolean canResetToIdle() {
        if (running) return false;
        VisualState current = visualState;
        return current == VisualState.ERROR || current == VisualState.STOPPED
                || current == VisualState.SUCCESS || current == VisualState.NEEDS_INPUT;
    }
    /** Reset presentation only. The output and its persisted record remain available. */
    public void resetToIdle() {
        main.post(() -> {
            synchronized (AgentController.this) {
                if (!canResetToIdle()) return;
                presentationEpoch.incrementAndGet();
                boolean ready = runtime.isInstalled();
                lastStatus = ready ? "已重置为待命，可以开始新任务。" : "运行环境尚未就绪，请先准备运行环境。";
                if (!taskStore.acknowledge()) lastStatus += "（重置状态暂时无法保存）";
                publishVisualState(ready ? VisualState.IDLE : VisualState.NEEDS_INPUT);
                Listener current = listener;
                if (current != null) current.onStatus(lastStatus);
            }
        });
    }
    private void publishVisualState(VisualState value) {
        visualState = value;
        OverlayService.setPetState(value);
        Listener l = listener;
        if (l != null) l.onVisualState(value);
    }
    private void phase(long epoch, VisualState value, String message) {
        main.post(() -> {
            if (epoch != presentationEpoch.get() || !running || cancelled) return;
            lastStatus = message;
            publishVisualState(value);
            Listener l = listener;
            if (l != null) l.onStatus(message);
        });
    }
    private void output(long epoch, String value) {
        main.post(() -> {
            if (epoch != presentationEpoch.get() || !running) return;
            lastOutput = value;
            Listener l = listener;
            if (l != null) l.onOutput(value);
        });
    }
    /** Publish a terminal snapshot as one main-thread operation before accepting another task. */
    private void finished(long epoch, VisualState state, boolean ok, String message, String transcript) {
        main.post(() -> {
            synchronized (AgentController.this) {
                if (epoch != presentationEpoch.get()) return;
                running = false;
                lastStatus = message;
                if (transcript != null) lastOutput = transcript;
                publishVisualState(state);
                if (epoch != presentationEpoch.get()) return;
                Listener l = listener;
                if (l != null) {
                    l.onOutput(lastOutput);
                    if (epoch != presentationEpoch.get()) return;
                    l.onStatus(message);
                    if (epoch != presentationEpoch.get()) return;
                    l.onFinished(ok, message);
                }
            }
        });
    }
    private void needsInput(String message) {
        final long epoch = presentationEpoch.get();
        main.post(() -> {
            if (epoch != presentationEpoch.get() || running) return;
            lastStatus = message;
            publishVisualState(VisualState.NEEDS_INPUT);
            Listener l = listener;
            if (l != null) { l.onStatus(message); l.onFinished(false, message); }
        });
    }
    private synchronized long begin() {
        if (running) return -1;
        long epoch = presentationEpoch.incrementAndGet();
        running = true;
        cancelled = false;
        finishing = false;
        try { context.startForegroundService(new Intent(context, OverlayService.class)); }
        catch (RuntimeException failure) {
            running = false;
            needsInput("请回到 PhoneAgent 后重新开始。");
            return -1;
        }
        return epoch;
    }
    private PowerManager.WakeLock wakeLock() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneAgent:task");
        // Held only for the active operation and released in its finally block.
        lock.acquire();
        return lock;
    }
    public void installRuntime() {
        final long epoch = begin();
        if (epoch < 0) return;
        phase(epoch, VisualState.PREPARING, "正在准备运行环境…");
        worker.execute(() -> {
            PowerManager.WakeLock lock = null;
            boolean ok = false;
            String message;
            try {
                lock = wakeLock();
                runtime.install(value -> {
                    if (cancelled) throw new CancellationException();
                    phase(epoch, VisualState.PREPARING, value);
                });
                if (cancelled) throw new CancellationException();
                String version = runtime.version();
                if (cancelled) throw new CancellationException();
                ok = true;
                message = "运行环境就绪 · " + version.trim();
            } catch (Exception e) { message = cancelled ? "已停止准备。" : "准备失败：" + safeMessage(e); }
            finally { if (lock != null && lock.isHeld()) lock.release(); }
            finished(epoch, cancelled ? VisualState.STOPPED : ok ? VisualState.IDLE : VisualState.ERROR, ok, message, null);
        });
    }
    public void run(String prompt, String baseUrl, String model, String apiKey) {
        if (running) return;
        if (prompt == null || prompt.trim().isEmpty()) { needsInput("先输入要手机完成的事情。"); return; }
        if (!isReady()) { needsInput("请先准备运行环境。"); return; }
        if (!PhoneAccessibilityService.isEnabled()) { needsInput("请先在系统设置中开启 PhoneAgent 无障碍服务。"); return; }
        try {
            URI url = new URI(baseUrl.trim());
            boolean local = "127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost());
            if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                || !("https".equals(url.getScheme()) || (local && "http".equals(url.getScheme())))) throw new Exception();
        } catch (Exception e) { needsInput("请填写兼容 Anthropic API 的 HTTPS 服务地址。"); return; }
        if (model == null || apiKey == null || model.trim().isEmpty() || apiKey.trim().isEmpty()) {
            needsInput("请先填写模型名和 API Key。"); return;
        }
        final long epoch = begin();
        if (epoch < 0) return;
        phase(epoch, VisualState.THINKING, "正在连接模型…");
        worker.execute(() -> runTask(epoch, prompt.trim(), baseUrl.trim(), model.trim(), apiKey.trim()));
    }

    private void runTask(long epoch, String prompt, String baseUrl, String model, String apiKey) {
        PowerManager.WakeLock lock = null;
        TaskRun task = new TaskRun(epoch, apiKey, baseUrl);
        boolean ok = false, sawResult = false;
        Integer exitCode = null, turns = null;
        String reason = "cli_no_result", detail = "执行进程结束，但没有返回最终结果。";
        String errorCategory = "";
        Process child = null;
        PhoneToolServer server = null;
        task.progress("正在连接模型…");
        try {
            lock = wakeLock();
            if (cancelled) throw new CancellationException();
            server = new PhoneToolServer(new PhoneToolServer.Observer() {
                @Override public void onTool(String name) {
                    if (cancelled) return;
                    String label = "正在操作手机：" + toolLabel(name);
                    task.progress(label);
                    phase(epoch, VisualState.ACTING, label);
                }
                @Override public void onToolFinished(String name) {
                    phase(epoch, VisualState.THINKING, "正在思考下一步…");
                }
            });
            bridge = server;
            if (cancelled) throw new CancellationException();
            child = runtime.startClaude(prompt, baseUrl, model, apiKey, server.port(), server.token());
            process = child;
            if (cancelled) { terminate(child); throw new CancellationException(); }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled) break;
                    JSONObject event;
                    try { event = new JSONObject(line); }
                    catch (Exception notJson) {
                        // Diagnostics may contain request bodies. Keep only a controlled phase label.
                        if (!line.trim().isEmpty()) task.diagnostic();
                        continue;
                    }
                    String type = event.optString("type");
                    if (type.equals("assistant")) {
                        JSONObject msg = event.optJSONObject("message");
                        JSONArray content = msg == null ? null : msg.optJSONArray("content");
                        if (event.has("error") && !event.isNull("error")) {
                            errorCategory = knownErrorCategory(event.optString("error"));
                            task.progress("模型服务报告错误，正在等待执行结果。"); continue;
                        }
                        if (content != null) for (int i = 0; i < content.length(); i++) {
                            JSONObject part = content.optJSONObject(i);
                            if (part != null && "text".equals(part.optString("type"))) {
                                String text = part.optString("text");
                                if (text.trim().startsWith("API Error:") || text.trim().startsWith("Error:")) task.diagnostic();
                                else task.append(text);
                            }
                        }
                    } else if (type.equals("result")) {
                        sawResult = true;
                        turns = LastTaskStore.optionalInt(event, "num_turns");
                        String subtype = event.optString("subtype", "success");
                        ok = !event.optBoolean("is_error", false) && "success".equals(subtype);
                        reason = ok ? "success" : "error_result";
                        detail = ok ? "执行进程已返回最终结果。" : resultFailure(event, subtype, errorCategory);
                        // Error arrays and raw error results can include provider request bodies.
                        if (ok) task.appendResult(event.optString("result", ""));
                    } else if (type.equals("system") && event.optString("subtype").equals("init")) {
                        task.progress("模型已连接，正在处理任务…");
                    } else if (type.equals("error")) {
                        task.progress("执行进程报告错误，正在等待最终结果。");
                    }
                }
            }
            exitCode = child.waitFor();
            if (!sawResult) { ok = false; reason = "cli_no_result"; }
            else if (ok && exitCode != 0) {
                ok = false;
                reason = "cli_exit_error";
                detail = "执行进程异常退出。";
            }
        } catch (Exception failure) {
            ok = false;
            if (!sawResult) {
                reason = "cli_no_result";
                detail = child == null ? "执行进程未能启动，没有返回最终结果。" : "执行连接中断，没有收到最终结果。";
            } else {
                reason = "execution_error";
                detail = "收到模型结果后，执行进程发生异常。";
            }
        } finally {
            if (server != null) server.close();
            bridge = null;
            if (child != null) {
                if (child.isAlive()) terminate(child);
                try { exitCode = child.exitValue(); } catch (IllegalThreadStateException stillStopping) { }
            }
            process = null;
            if (lock != null && lock.isHeld()) lock.release();
        }
        synchronized (this) {
            // Once completion is being saved, a late Stop cannot change only half of its outcome.
            finishing = true;
            if (cancelled) { ok = false; reason = "user_stop"; detail = "用户手动停止了任务。"; }
        }
        if (!cancelled && !sawResult && !errorCategory.isEmpty()) detail += "\n错误类别：" + errorCategory;
        String state = cancelled ? "stopped" : ok ? "success" : "error";
        String message = cancelled ? "已停止任务，记录已保留。" : ok ? "本次指令已结束。" : "本次指令未完成，请查看任务记录。";
        String transcript = task.finish(state, message, reason, detail, exitCode, turns);
        finished(epoch, cancelled ? VisualState.STOPPED : ok ? VisualState.SUCCESS : VisualState.ERROR, ok, message, transcript);
    }

    /** Serializes output and persistence from the CLI reader and the tool observer. */
    private final class TaskRun {
        final long epoch, startedAt = System.currentTimeMillis();
        final String key, endpoint;
        String transcript = "", status = "正在连接模型…";
        boolean done, sawDiagnostic, saveFailed;
        TaskRun(long epoch, String key, String endpoint) { this.epoch = epoch; this.key = key; this.endpoint = endpoint; }
        synchronized void progress(String message) {
            if (done) return;
            status = message;
            append("【进度】" + message);
        }
        synchronized void diagnostic() {
            if (!sawDiagnostic) { sawDiagnostic = true; progress("执行进程返回诊断信息，正在等待最终结果。"); }
        }
        synchronized void appendResult(String text) {
            if (!transcript.contains(redact(text))) append(text);
        }
        synchronized void append(String text) {
            if (done || text == null || text.isEmpty()) return;
            transcript = LastTaskStore.tail(transcript + (transcript.isEmpty() ? "" : "\n\n") + redact(text));
            save("running", "", null, null, 0);
            output(epoch, transcript);
        }
        synchronized String finish(String state, String message, String reason, String detail, Integer exit, Integer turns) {
            done = true;
            status = message;
            String label = state.equals("success") ? "完成" : state.equals("stopped") ? "已停止" : "未完成";
            transcript = LastTaskStore.tail(transcript + "\n\n" + redact(LastTaskStore.terminalFooter(label, detail, exit, turns)));
            long ended = System.currentTimeMillis();
            save(state, reason, exit, turns, ended);
            if (saveFailed) {
                transcript = LastTaskStore.tail(transcript + "\n记录暂时无法保存，当前仍可查看。");
                save(state, reason, exit, turns, ended);
            }
            return transcript;
        }
        private void save(String state, String reason, Integer exit, Integer turns, long ended) {
            try {
                JSONObject record = new JSONObject().put("version", 1).put("state", state)
                        .put("status", redact(status)).put("output", transcript).put("reason", reason)
                        .put("started_at", startedAt).put("updated_at", System.currentTimeMillis()).put("ended_at", ended);
                if (exit != null) record.put("exit_code", exit);
                if (turns != null) record.put("num_turns", turns);
                taskStore.write(record);
                saveFailed = false;
            } catch (Exception unavailable) { saveFailed = true; }
        }
        private String redact(String text) {
            String safe = text.replace(key, "[已隐藏]").replace(endpoint, "[服务地址已隐藏]");
            return safe.replaceAll("(?i)((?:proxy-)?authorization[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\r\\n\\\"'}]+", "$1[已隐藏]")
                    .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[已隐藏]")
                    .replaceAll("(?i)((?:x-api-key|api[_-]?key)\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+", "$1[已隐藏]");
        }
    }

    public void stop() {
        final long epoch;
        synchronized (this) {
            if (!running || cancelled || finishing) return;
            epoch = presentationEpoch.get();
            cancelled = true;
        }
        main.post(() -> {
            if (epoch != presentationEpoch.get() || !running) return;
            lastStatus = "正在停止…";
            publishVisualState(VisualState.STOPPING);
            Listener l = listener;
            if (l != null) l.onStatus(lastStatus);
        });
        runtime.cancel();
        PhoneToolServer server = bridge;
        if (server != null) server.close();
        Process child = process;
        if (child != null) {
            // Bound cancellation cleanup, not task duration. Never block the main/UI thread.
            Thread cleanup = new Thread(() -> terminate(child), "phone-agent-stop");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }
    private static void terminate(Process child) {
        child.destroy();
        try {
            if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                child.waitFor(1500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); child.destroyForcibly(); }
        try { child.getInputStream().close(); } catch (IOException ignored) { }
    }
    private static String resultFailure(JSONObject event, String subtype, String category) {
        String detail;
        if ("error_max_turns".equals(subtype)) detail = "执行进程报告达到轮数限制。";
        else if ("error_max_budget_usd".equals(subtype)) detail = "执行进程报告达到预算限制。";
        else if ("error_during_execution".equals(subtype)) detail = "模型或工具执行过程中发生错误。";
        else detail = "执行进程返回了未完成结果。";
        if (subtype.matches("[a-z][a-z0-9_]{0,63}")) detail += "\nCLI 结果：" + subtype;
        StringBuilder diagnostics = new StringBuilder(event.optString("result", ""));
        JSONArray errors = event.optJSONArray("errors");
        if (errors != null) for (int i = 0; i < errors.length(); i++) diagnostics.append('\n').append(errors.optString(i));
        // Extract only a status number or an explicitly recognized error type, never the source body.
        Matcher http = Pattern.compile("(?i)(?:API\\s+Error\\s*:|HTTP(?:/\\d(?:\\.\\d)?)?\\s+)\\s*([45]\\d{2})\\b")
                .matcher(diagnostics);
        if (http.find()) detail += "\nHTTP 状态：" + http.group(1);
        if (category.isEmpty()) {
            Matcher kind = Pattern.compile("[\\\"']type[\\\"']\\s*:\\s*[\\\"']([a-z_]+)[\\\"']").matcher(diagnostics);
            while (kind.find() && category.isEmpty()) category = knownErrorCategory(kind.group(1));
        }
        if (!category.isEmpty()) detail += "\n错误类别：" + category;
        return detail;
    }
    private static String knownErrorCategory(String value) {
        switch (value) {
            case "authentication_failed": case "authentication_error":
            case "permission_error": case "invalid_request": case "invalid_request_error":
            case "not_found_error": case "rate_limit": case "rate_limit_error":
            case "overloaded_error": case "api_error": case "server_error":
                return value;
            default: return "";
        }
    }
    private static String safeMessage(Exception e) { String m = e.getMessage(); return m == null ? e.getClass().getSimpleName() : m; }
    private static String toolLabel(String name) {
        switch (name) {
            case "read_screen": return "读取界面";
            case "home": return "返回桌面";
            case "back": return "返回";
            case "open_settings": return "打开设置";
            case "open_app": return "打开应用";
            case "tap": return "点击";
            case "swipe": return "滑动";
            case "type_text": return "输入文字";
            default: return "执行手机工具";
        }
    }
}
