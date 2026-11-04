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
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile Listener listener;
    private volatile Process process;
    private volatile PhoneToolServer bridge;
    private volatile boolean running, cancelled;
    private volatile VisualState visualState = VisualState.IDLE;
    private final AtomicLong presentationEpoch = new AtomicLong();
    private String lastStatus = "先准备运行环境，再配置模型和手机权限。";
    private String lastOutput = "";

    private AgentController(Context context) {
        this.context = context;
        runtime = new LinuxRuntime(context);
        if (runtime.isInstalled()) lastStatus = "运行环境已就绪，连接模型后即可执行。";
        else visualState = VisualState.NEEDS_INPUT;
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
    /** Reset presentation only. A running task, its output, and saved settings are untouched. */
    public void resetToIdle() {
        main.post(() -> {
            synchronized (AgentController.this) {
                // Recheck here rather than relying on an earlier button-state snapshot.
                if (!canResetToIdle()) return;
                presentationEpoch.incrementAndGet();
                boolean ready = runtime.isInstalled();
                lastStatus = ready ? "已重置为待命，可以开始新任务。" : "运行环境尚未就绪，请先准备运行环境。";
                publishVisualState(ready ? VisualState.IDLE : VisualState.NEEDS_INPUT);
                Listener current = listener;
                if (current != null) current.onStatus(lastStatus);
            }
        });
    }
    private void setVisualState(VisualState value) {
        final long epoch = presentationEpoch.get();
        main.post(() -> { if (epoch == presentationEpoch.get()) publishVisualState(value); });
    }
    private void publishVisualState(VisualState value) {
        visualState = value;
        OverlayService.setPetState(value);
        Listener l = listener;
        if (l != null) l.onVisualState(value);
    }
    private void toolState(VisualState value, String message) {
        final long epoch = presentationEpoch.get();
        main.post(() -> {
            // A delayed tool callback must not replace a stop or terminal state.
            if (epoch != presentationEpoch.get() || !running || cancelled) return;
            publishVisualState(value);
            lastStatus = message;
            Listener l = listener;
            if (l != null) l.onStatus(message);
        });
    }
    private void status(String value) {
        final long epoch = presentationEpoch.get();
        main.post(() -> {
            if (epoch != presentationEpoch.get()) return;
            lastStatus = value; Listener l = listener; if (l != null) l.onStatus(value);
        });
    }
    private void output(String value) {
        final long epoch = presentationEpoch.get();
        main.post(() -> {
            if (epoch != presentationEpoch.get()) return;
            lastOutput = value; Listener l = listener; if (l != null) l.onOutput(value);
        });
    }
    private void finished(boolean ok, String message) {
        final long epoch = presentationEpoch.get();
        main.post(() -> {
            if (epoch != presentationEpoch.get()) return;
            lastStatus = message; Listener l = listener;
            if (l != null) { l.onStatus(message); l.onFinished(ok, message); }
        });
    }
    private void needsInput(String message) {
        setVisualState(VisualState.NEEDS_INPUT);
        finished(false, message);
    }
    private synchronized boolean begin() {
        if (running) { status("已有任务在运行，请先停止或等待完成。"); return false; }
        running = true; cancelled = false;
        context.startForegroundService(new Intent(context, OverlayService.class));
        return true;
    }
    private PowerManager.WakeLock wakeLock() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneAgent:task");
        lock.acquire(20 * 60 * 1000L);
        return lock;
    }
    public void installRuntime() {
        if (!begin()) return;
        setVisualState(VisualState.PREPARING);
        worker.execute(() -> {
            PowerManager.WakeLock lock = wakeLock();
            boolean ok = false;
            String message;
            try {
                runtime.install(value -> {
                    if (cancelled) throw new CancellationException();
                    status(value);
                });
                if (cancelled) throw new CancellationException();
                String version = runtime.version();
                if (cancelled) throw new CancellationException();
                ok = true;
                message = "运行环境就绪 · " + version.trim();
            } catch (Exception e) { message = cancelled ? "已停止准备。" : "准备失败：" + safeMessage(e); }
            finally { if (lock.isHeld()) lock.release(); running = false; }
            setVisualState(cancelled ? VisualState.STOPPED : ok ? VisualState.IDLE : VisualState.ERROR);
            finished(ok, message);
        });
    }
    public void run(String prompt, String baseUrl, String model, String apiKey) {
        if (prompt == null || prompt.trim().isEmpty()) { needsInput("先输入要手机完成的事情。"); return; }
        if (!isReady()) { needsInput("请先准备运行环境。"); return; }
        if (!PhoneAccessibilityService.isEnabled()) { needsInput("请先在系统设置中开启 PhoneAgent 无障碍服务。"); return; }
        try {
            URI url = new URI(baseUrl.trim());
            boolean local = "127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost());
            if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                || !("https".equals(url.getScheme()) || (local && "http".equals(url.getScheme())))) throw new Exception();
        } catch (Exception e) { needsInput("请填写兼容 Anthropic API 的 HTTPS 服务地址。"); return; }
        if (model.trim().isEmpty() || apiKey.trim().isEmpty()) { needsInput("请先填写模型名和 API Key。"); return; }
        if (!begin()) return;
        setVisualState(VisualState.THINKING);
        output("");
        worker.execute(() -> runTask(prompt.trim(), baseUrl.trim(), model.trim(), apiKey.trim()));
    }

    private void runTask(String prompt, String baseUrl, String model, String apiKey) {
        PowerManager.WakeLock lock = wakeLock();
        StringBuilder transcript = new StringBuilder();
        boolean ok = false, sawResult = false;
        String message = "任务未完成。";
        ScheduledFuture<?> deadline = timer.schedule(this::stop, 5, TimeUnit.MINUTES);
        try {
            bridge = new PhoneToolServer(new PhoneToolServer.Observer() {
                @Override public void onTool(String name) {
                    toolState(VisualState.ACTING, "正在操作手机：" + toolLabel(name));
                }
                @Override public void onToolFinished(String name) {
                    toolState(VisualState.THINKING, "正在思考下一步…");
                }
            });
            if (cancelled) throw new CancellationException();
            status("正在连接模型…");
            process = runtime.startClaude(prompt, baseUrl, model, apiKey, bridge.port(), bridge.token());
            if (cancelled) { process.destroy(); throw new CancellationException(); }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled) break;
                    try {
                        JSONObject event = new JSONObject(line);
                        String type = event.optString("type");
                        if (type.equals("assistant")) {
                            JSONObject msg = event.optJSONObject("message");
                            JSONArray content = msg == null ? null : msg.optJSONArray("content");
                            if (content != null) for (int i = 0; i < content.length(); i++) {
                                JSONObject part = content.optJSONObject(i);
                                if (part != null && "text".equals(part.optString("type"))) append(transcript, part.optString("text"), apiKey);
                            }
                        } else if (type.equals("result")) {
                            sawResult = true;
                            ok = !event.optBoolean("is_error", false) && "success".equals(event.optString("subtype", "success"));
                            String result = event.optString("result", "");
                            if (!result.isEmpty() && !transcript.toString().contains(result)) append(transcript, result, apiKey);
                            JSONArray errors = event.optJSONArray("errors");
                            if (errors != null) for (int i = 0; i < errors.length(); i++) append(transcript, errors.optString(i), apiKey);
                            message = ok ? "本次指令已结束。" : "本次指令未完成，请查看结果。";
                        }
                    } catch (Exception ignored) {
                        // CLI diagnostics are plain text. Never expose the configured key.
                        if (!line.trim().isEmpty()) append(transcript, line, apiKey);
                    }
                }
            }
            int exit = process.waitFor();
            if (!sawResult || exit != 0) { ok = false; message = "执行未完成（退出码 " + exit + "），请查看结果。"; }
        } catch (Exception e) {
            message = "执行失败：" + safeMessage(e).replace(apiKey, "[已隐藏]");
        } finally {
            deadline.cancel(false);
            PhoneToolServer server = bridge; bridge = null; if (server != null) server.close();
            Process child = process; process = null; if (child != null) child.destroy();
            if (lock.isHeld()) lock.release();
            running = false;
        }
        if (cancelled) { ok = false; message = "已停止任务（单次任务最长 5 分钟）。"; }
        setVisualState(cancelled ? VisualState.STOPPED : ok ? VisualState.SUCCESS : VisualState.ERROR);
        finished(ok, message);
    }

    private void append(StringBuilder target, String text, String key) {
        String safe = text.replace(key, "[已隐藏]");
        if (target.length() > 0) target.append("\n\n");
        target.append(safe);
        if (target.length() > 24000) target.delete(0, target.length() - 24000);
        output(target.toString());
    }
    public void stop() {
        if (!running) return;
        setVisualState(VisualState.STOPPING);
        cancelled = true;
        runtime.cancel();
        PhoneToolServer server = bridge; if (server != null) server.close();
        Process child = process; if (child != null) child.destroy();
        status("正在停止…");
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
            default: return name;
        }
    }
}
