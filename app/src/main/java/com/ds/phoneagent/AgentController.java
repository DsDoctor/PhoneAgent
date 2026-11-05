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
    public enum VisualState { IDLE, PREPARING, THINKING, ACTING, SUCCESS, NEEDS_INPUT, ERROR, STOPPING, STOPPED, PAUSING, PAUSED, WAITING_USER, RESUMING, RETRYING }
    public interface Listener {
        void onStatus(String status);
        void onOutput(String output);
        void onFinished(boolean success, String message);
        default void onVisualState(VisualState state) { }
        default void onHistoryChanged() { }
    }
    private static AgentController instance;
    public static synchronized AgentController get(Context context) {
        if (instance == null) instance = new AgentController(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final LinuxRuntime runtime;
    private final LastTaskStore taskStore;
    private final TaskHistoryStore historyStore;
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
    private volatile String activeConversationId = "";
    private final PhoneActionGate actionGate = new PhoneActionGate();
    private final ExecutorService controls = Executors.newSingleThreadExecutor();
    private volatile ClaudeTransport transport;
    private volatile TaskRun activeTask;
    private volatile JSONObject pendingQuestion;
    private volatile boolean pauseRequested, turnSettled, initialized, userSent, uiVisible, interruptAcknowledged;
    private volatile String initializeId = "", interruptId = "";
    private Runnable deferredResume;
    private PowerManager.WakeLock taskWakeLock;
    private Runnable taskTicker;

    private AgentController(Context context) {
        this(context, new LinuxRuntime(context));
    }
    AgentController(Context context, LinuxRuntime runtime) {
        this.context = context;
        this.runtime = runtime;
        taskStore = new LastTaskStore(context);
        historyStore = new TaskHistoryStore(context);
        if (runtime.isInstalled()) lastStatus = "运行环境已就绪，连接模型后即可执行。";
        else visualState = VisualState.NEEDS_INPUT;
        JSONObject previous = taskStore.recoverInterrupted();
        try { historyStore.initialize(previous); }
        catch (IOException unavailable) { /* The previous task stays visible; new runs report storage failure. */ }
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
        String previousId = previous == null ? "" : previous.optString("conversation_id");
        activeConversationId = historyStore.getConversation(previousId) != null ? previousId : historyStore.latestId();
        JSONObject newest = historyStore.getConversation(historyStore.latestId());
        JSONArray latestTurns = newest == null ? null : newest.optJSONArray("turns");
        JSONObject latestTurn = latestTurns == null || latestTurns.length() == 0
                ? null : latestTurns.optJSONObject(latestTurns.length() - 1);
        if (latestTurn != null && (previous == null || (!latestTurn.optString("id").equals(previous.optString("turn_id"))
                && newest.optLong("updated_at") > previous.optLong("updated_at")))) {
            // Recover a crash between persisting the new prompt and updating last-task.json.
            activeConversationId = newest.optString("id");
            lastStatus = latestTurn.optString("status");
            lastOutput = latestTurn.optString("output");
            String state = latestTurn.optString("state");
            visualState = state.equals("success") ? VisualState.SUCCESS
                    : state.equals("stopped") ? VisualState.STOPPED : VisualState.ERROR;
        }
        // A persisted interaction restores a waiting UI, never starts a model request.
        JSONObject restored = historyStore.getConversation(activeConversationId);
        JSONArray restoredTurns = restored == null ? null : restored.optJSONArray("turns");
        JSONObject restoredTurn = restoredTurns == null || restoredTurns.length() == 0 ? null
                : restoredTurns.optJSONObject(restoredTurns.length() - 1);
        if (restoredTurn != null && isPendingState(restoredTurn.optString("state"))) {
            pendingQuestion = copy(restoredTurn.optJSONObject("pending_question"));
            pauseRequested = pendingQuestion == null;
            turnSettled = true;
            visualState = pendingQuestion == null ? VisualState.PAUSED : VisualState.WAITING_USER;
            lastStatus = pendingQuestion == null ? "任务已暂停，输入补充后继续。" : "正在等你回答，回答后继续原任务。";
        }
    }
    public void setListener(Listener listener) {
        this.listener = listener;
        main.post(() -> { if (this.listener == listener) {
            listener.onStatus(lastStatus); listener.onOutput(lastOutput); listener.onVisualState(visualState);
            listener.onHistoryChanged();
        } });
    }
    public void removeListener(Listener listener) { if (this.listener == listener) this.listener = null; }
    public boolean isRunning() { return running && !isSuspended(); }
    public boolean hasActiveTask() { return running || isSuspended(); }
    private boolean isSuspended() {
        return pauseRequested || pendingQuestion != null || visualState == VisualState.RESUMING;
    }
    private static boolean isPendingState(String state) {
        return state.equals("paused") || state.equals("waiting_user") || state.equals("pausing") || state.equals("resuming");
    }
    private static JSONObject copy(JSONObject value) {
        try { return value == null ? null : new JSONObject(value.toString()); }
        catch (Exception invalid) { return null; }
    }
    public synchronized boolean canAcceptInput() {
        return !cancelled && actionGate.isIdle() && (!running || initialized && transport != null && transport.getProcess().isAlive()) && (visualState == VisualState.WAITING_USER && pendingQuestion != null
                || visualState == VisualState.PAUSED && (turnSettled || process == null));
    }
    public JSONObject getPendingQuestion() { return copy(pendingQuestion); }
    public synchronized void setTaskUiVisible(boolean visible) {
        uiVisible = visible;
        if (visible && hasActiveTask() && activeTask != null) actionGate.close();
        if (!visible && deferredResume != null) {
            Runnable next = deferredResume;
            deferredResume = null;
            dispatchWhenHidden(next);
        }
    }
    private synchronized void dispatchWhenHidden(Runnable action) {
        if (uiVisible) { deferredResume = action; return; }
        controls.execute(() -> {
            synchronized (AgentController.this) {
                if (uiVisible) { deferredResume = action; return; }
                action.run();
            }
        });
    }
    public boolean isReady() { return runtime.isInstalled(); }
    public VisualState getVisualState() { return visualState; }
    public String getActiveConversationId() { return activeConversationId; }
    public JSONArray getConversations() {
        JSONArray summaries = historyStore.summaries();
        for (int i = 0; i < summaries.length(); i++) addAvailability(summaries.optJSONObject(i));
        return summaries;
    }
    public JSONObject getConversation(String id) {
        JSONObject conversation = historyStore.getConversation(id);
        addAvailability(conversation);
        return conversation;
    }
    public synchronized String importConversation(JSONObject conversation) throws IOException {
        if (hasActiveTask()) throw new IOException("请等待当前任务结束后导入会话。");
        String id = historyStore.importConversation(conversation);
        if (activeConversationId.isEmpty()) activeConversationId = id;
        historyChanged(presentationEpoch.get());
        return id;
    }

    /** Delete App history only; a running selected conversation and all CLI session files are retained. */
    public synchronized boolean deleteConversation(String id) throws IOException {
        JSONObject conversation = historyStore.getConversation(id);
        if (conversation == null) return false;
        if (hasActiveTask() && id.equals(activeConversationId)) throw new IOException("这段对话正在运行，请先停止任务再删除。");

        JSONObject previous = taskStore.read();
        boolean clearedLast = false;
        if (isLastTaskOf(previous, conversation)) clearedLast = taskStore.clearIfUnchanged(previous);
        try {
            if (!historyStore.deleteConversation(id)) {
                if (clearedLast) taskStore.restoreIfEmpty(previous);
                return false;
            }
        } catch (IOException failure) {
            if (clearedLast) {
                try { taskStore.restoreIfEmpty(previous); }
                catch (IOException restoreFailure) { failure.addSuppressed(restoreFailure); }
            }
            throw failure;
        }

        boolean changePresentation = !hasActiveTask() && (id.equals(activeConversationId) || clearedLast);
        final long epoch;
        if (changePresentation) {
            epoch = presentationEpoch.incrementAndGet();
            activeConversationId = historyStore.latestId();
            JSONObject next = historyStore.getConversation(activeConversationId);
            JSONArray turns = next == null ? null : next.optJSONArray("turns");
            JSONObject turn = turns == null || turns.length() == 0 ? null : turns.optJSONObject(turns.length() - 1);
            if (turn == null) {
                lastOutput = "";
                lastStatus = runtime.isInstalled() ? "已删除任务记录，可以开始新任务。" : "任务记录已删除，请先准备运行环境。";
                visualState = runtime.isInstalled() ? VisualState.IDLE : VisualState.NEEDS_INPUT;
            } else {
                lastOutput = turn.optString("output");
                lastStatus = turn.optString("status");
                String state = turn.optString("state");
                visualState = state.equals("success") ? VisualState.SUCCESS
                        : state.equals("stopped") ? VisualState.STOPPED : VisualState.ERROR;
            }
        } else epoch = presentationEpoch.get();
        main.post(() -> {
            if (epoch != presentationEpoch.get()) return;
            Listener l = listener;
            if (changePresentation) {
                publishVisualState(visualState);
                if (epoch != presentationEpoch.get()) return;
                if (l != null) {
                    l.onStatus(lastStatus);
                    if (epoch != presentationEpoch.get()) return;
                    l.onOutput(lastOutput);
                }
            }
            if (epoch == presentationEpoch.get() && l != null) l.onHistoryChanged();
        });
        return true;
    }

    private static boolean isLastTaskOf(JSONObject previous, JSONObject conversation) {
        if (previous == null || conversation == null) return false;
        String linkedId = previous.optString("conversation_id");
        if (!linkedId.isEmpty()) return linkedId.equals(conversation.optString("id"));
        // Migrated pre-conversation records have no id; match their unchanged original evidence.
        JSONArray turns = conversation.optJSONArray("turns");
        if (turns == null) return false;
        for (int i = 0; i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i);
            if (turn != null && turn.optLong("started_at") == previous.optLong("started_at")
                    && turn.optString("output").equals(previous.optString("output"))) return true;
        }
        return false;
    }
    private void addAvailability(JSONObject conversation) {
        if (conversation == null) return;
        String session = conversation.optString("session_id");
        String explanation = "";
        if (!LinuxRuntime.isSessionId(session)) explanation = "这条旧任务没有保存可恢复的会话，请新建任务。";
        else if (!runtime.hasClaudeSession(session)) explanation = "原会话文件已丢失，无法继续；请新建任务。";
        else if (hasActiveTask()) explanation = "请等待当前任务结束后继续。";
        try { conversation.put("can_continue", explanation.isEmpty()).put("continue_unavailable_reason", explanation); }
        catch (Exception ignored) { }
    }
    private void historyChanged(long epoch) {
        main.post(() -> {
            if (epoch != presentationEpoch.get()) return;
            Listener l = listener;
            if (l != null) l.onHistoryChanged();
        });
    }
    public boolean canResetToIdle() {
        if (hasActiveTask()) return false;
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
                if (!acknowledgeCurrentPresentation()) lastStatus += "（重置状态暂时无法保存）";
                publishVisualState(ready ? VisualState.IDLE : VisualState.NEEDS_INPUT);
                Listener current = listener;
                if (current != null) current.onStatus(lastStatus);
            }
        });
    }

    private boolean acknowledgeCurrentPresentation() {
        JSONObject conversation = historyStore.getConversation(activeConversationId);
        JSONArray turns = conversation == null ? null : conversation.optJSONArray("turns");
        JSONObject turn = turns == null || turns.length() == 0 ? null : turns.optJSONObject(turns.length() - 1);
        if (turn == null) return taskStore.acknowledge();
        try {
            JSONObject fallback = new JSONObject().put("version", 1).put("state", turn.optString("state"))
                    .put("status", turn.optString("status")).put("output", turn.optString("output"))
                    .put("reason", turn.optString("reason")).put("conversation_id", activeConversationId)
                    .put("turn_id", turn.optString("id")).put("session_id", conversation.optString("session_id"));
            for (String field : new String[]{"started_at", "updated_at", "ended_at"}) fallback.put(field, turn.optLong(field));
            for (String field : new String[]{"exit_code", "num_turns"}) {
                Integer value = LastTaskStore.optionalInt(turn, field);
                if (value != null) fallback.put(field, value);
            }
            ExecutionMetrics.copyFields(turn, fallback);
            return taskStore.acknowledge(fallback);
        } catch (Exception unavailable) { return false; }
    }
    private void publishVisualState(VisualState value) {
        visualState = value;
        OverlayService.setPetState(value);
        Listener l = listener;
        if (l != null) l.onVisualState(value);
    }
    private void phase(long epoch, VisualState value, String message) {
        main.post(() -> {
            synchronized (AgentController.this) {
                if (epoch != presentationEpoch.get() || !running || cancelled || isSuspended()) return;
                lastStatus = message;
                publishVisualState(value);
                Listener l = listener;
                if (l != null) l.onStatus(message);
            }
        });
    }
    private void output(long epoch, String value) {
        main.post(() -> {
            if (epoch != presentationEpoch.get() || !hasActiveTask()) return;
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
                actionGate.close();
                if (taskTicker != null) main.removeCallbacks(taskTicker);
                taskTicker = null;
                running = false;
                pauseRequested = false;
                pendingQuestion = null;
                activeTask = null;
                deferredResume = null;
                lastStatus = message;
                if (transcript != null) lastOutput = transcript;
                publishVisualState(state);
                if (epoch != presentationEpoch.get()) return;
                Listener l = listener;
                if (l != null) {
                    l.onHistoryChanged();
                    if (epoch != presentationEpoch.get()) return;
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
            if (epoch != presentationEpoch.get() || hasActiveTask()) return;
            lastStatus = message;
            publishVisualState(VisualState.NEEDS_INPUT);
            Listener l = listener;
            if (l != null) { l.onStatus(message); l.onFinished(false, message); }
        });
    }
    private synchronized long begin() {
        if (hasActiveTask()) return -1;
        long epoch = presentationEpoch.incrementAndGet();
        running = true;
        cancelled = false;
        finishing = false;
        pauseRequested = false; pendingQuestion = null; turnSettled = false;
        initialized = false; userSent = false; initializeId = ""; interruptId = "";
        actionGate.close();
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
        runInConversation("", prompt, baseUrl, model, apiKey);
    }
    public void runInConversation(String conversationId, String prompt, String baseUrl, String model, String apiKey) {
        if (hasActiveTask()) return;
        if (prompt == null || prompt.trim().isEmpty()) { needsInput("先输入要手机完成的事情。"); return; }
        if (!isReady()) { needsInput("请先准备运行环境。"); return; }
        if (PhoneAccessibilityService.getControlState(context) != PhoneAccessibilityService.ControlState.CONNECTED) { needsInput("手机控制尚未连接，请在系统设置中开启 PhoneAgent 并等待连接。"); return; }
        try {
            URI url = new URI(baseUrl.trim());
            boolean local = "127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost());
            if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                || !("https".equals(url.getScheme()) || (local && "http".equals(url.getScheme())))) throw new Exception();
        } catch (Exception e) { needsInput("请填写兼容 Anthropic API 的 HTTPS 服务地址。"); return; }
        if (model == null || apiKey == null || model.trim().isEmpty() || apiKey.trim().isEmpty()) {
            needsInput("请先填写模型名和 API Key。"); return;
        }
        String resumeSession = "";
        if (conversationId != null && !conversationId.isEmpty()) {
            JSONObject existing = getConversation(conversationId);
            if (existing == null) { needsInput("这条任务记录已不存在，请新建任务。"); return; }
            if (!existing.optBoolean("can_continue")) { needsInput(existing.optString("continue_unavailable_reason")); return; }
            resumeSession = existing.optString("session_id");
        }
        final long epoch = begin();
        if (epoch < 0) return;
        final TaskRun task;
        try {
            JSONObject started = historyStore.beginTurn(conversationId, redactText(prompt.trim(), apiKey.trim(), baseUrl.trim()));
            activeConversationId = started.getString("conversation_id");
            task = new TaskRun(epoch, apiKey.trim(), baseUrl.trim(), started, prompt.trim());
        } catch (Exception unavailable) {
            running = false;
            needsInput("无法保存任务会话，请检查手机存储后重试。");
            return;
        }
        activeTask = task;
        startTaskTicker(task);
        final String session = resumeSession;
        task.progress(session.isEmpty() ? "正在连接模型…" : "正在恢复原会话…");
        historyChanged(epoch);
        phase(epoch, VisualState.THINKING, "正在连接模型…");
        worker.execute(() -> runTask(task, prompt.trim(), baseUrl.trim(), model.trim(), apiKey.trim(), session));
    }

    private synchronized void acquireTaskWakeLock() {
        if (taskWakeLock == null) taskWakeLock = wakeLock();
    }
    private synchronized void releaseTaskWakeLock() {
        if (taskWakeLock != null && taskWakeLock.isHeld()) taskWakeLock.release();
        taskWakeLock = null;
    }
    private void interactionState(VisualState state, String message) {
        visualState = state; // Synchronous: the input immediately stops accepting duplicate submissions.
        lastStatus = message;
        long epoch = presentationEpoch.get();
        main.post(() -> {
            if (epoch != presentationEpoch.get() || visualState != state) return;
            publishVisualState(state);
            Listener l = listener;
            if (l != null) { l.onStatus(lastStatus); l.onHistoryChanged(); }
        });
    }
    public void pauseForUser() { pauseForUser(null); }
    public synchronized void pauseForUser(Runnable afterPhoneIdle) {
        if (!hasActiveTask() || cancelled || finishing || running && activeTask == null) {
            if (afterPhoneIdle != null) main.post(afterPhoneIdle);
            return;
        }
        actionGate.close();
        if (pendingQuestion == null && !pauseRequested) {
            pauseRequested = true;
            deferredResume = null;
            releaseTaskWakeLock();
            turnSettled = !userSent;
            interactionState(turnSettled ? VisualState.PAUSED : VisualState.PAUSING,
                    turnSettled ? "任务已暂停，输入补充后继续。" : "正在暂停，等待当前操作结束…");
            TaskRun task = activeTask;
            if (task != null) task.hold(turnSettled ? "paused" : "pausing", lastStatus, null);
            ClaudeTransport channel = transport;
            if (initialized && userSent && !turnSettled && channel != null) requestInterrupt(channel);
        }
        final long epoch = presentationEpoch.get();
        actionGate.whenIdle(() -> {
            synchronized (AgentController.this) {
                if (epoch != presentationEpoch.get() || cancelled) return;
                if (activeTask != null) settlePauseIfReady(activeTask);
                historyChanged(epoch);
            }
        });
        if (afterPhoneIdle != null) actionGate.whenIdle(afterPhoneIdle);
    }
    private void requestInterrupt(ClaudeTransport channel) {
        if (!interruptId.isEmpty()) return;
        interruptAcknowledged = false;
        interruptId = java.util.UUID.randomUUID().toString();
        String requestId = interruptId;
        controls.execute(() -> {
            try { channel.interrupt(requestId); }
            catch (IOException failure) { Process child = channel.getProcess(); terminate(child); }
        });
    }
    private void settlePauseIfReady(TaskRun task) {
        if (pauseRequested && turnSettled && actionGate.isIdle() && (interruptId.isEmpty() || interruptAcknowledged)) {
            interactionState(VisualState.PAUSED, "任务已暂停，输入补充后继续。");
            task.hold("paused", lastStatus, null);
        }
    }
    public synchronized void continuePaused(String conversationId, String text, String baseUrl, String model, String apiKey) {
        if (!canAcceptInput() || pendingQuestion != null || !activeConversationId.equals(conversationId)) return;
        String correction = text == null ? "" : text.trim();
        String prompt = correction.isEmpty() ? "用户确认继续原任务。请重新读取手机当前界面再决定下一步。"
                : "用户暂停任务并补充了要求：\n" + correction + "\n请依据此要求继续原任务，先重新读取手机当前界面，不要复用暂停前的坐标。";
        resumeInteraction(prompt, correction.isEmpty() ? "继续原任务" : correction, null, null, baseUrl, model, apiKey);
    }
    public synchronized void answerQuestion(String conversationId, String requestId, JSONObject answers, String freeText,
                                            String baseUrl, String model, String apiKey) {
        if (!canAcceptInput() || pendingQuestion == null || !activeConversationId.equals(conversationId)
                || !pendingQuestion.optString("id").equals(requestId)) return;
        JSONObject question = copy(pendingQuestion);
        JSONObject input = question.optJSONObject("input");
        JSONArray questions = input == null ? null : input.optJSONArray("questions");
        if (questions == null || questions.length() == 0) return;
        JSONObject validated = new JSONObject();
        StringBuilder description = new StringBuilder();
        String extra = freeText == null ? "" : freeText.trim();
        try {
            for (int i = 0; i < questions.length(); i++) {
                String key = questions.getJSONObject(i).getString("question");
                String answer = answers == null ? "" : answers.optString(key).trim();
                if (answer.isEmpty() && questions.length() == 1) { answer = extra; extra = ""; }
                if (answer.isEmpty() || answer.length() > 8000) return;
                validated.put(key, answer);
                if (description.length() > 0) description.append("\n");
                description.append(key).append("：").append(answer);
            }
            if (!extra.isEmpty()) {
                String key = questions.getJSONObject(questions.length() - 1).getString("question");
                validated.put(key, validated.getString(key) + "\n用户补充：" + extra);
                description.append("\n补充：").append(extra);
            }
        } catch (Exception invalid) { return; }
        if (description.length() > 8000) return;
        String prompt = "上次任务暂停等待用户回答。以下是原问题和用户回答，请据此继续原任务：\n" + description;
        resumeInteraction(prompt, description.toString(), question, validated, baseUrl, model, apiKey);
    }
    /** Called under the Controller lock; queued until the task UI is actually hidden. */
    private void resumeInteraction(String prompt, String answer, JSONObject question, JSONObject answers,
                                   String baseUrl, String model, String apiKey) {
        if (process == null && (!isReady() || baseUrl == null || model == null || apiKey == null
                || model.trim().isEmpty() || apiKey.trim().isEmpty())) return;
        final ClaudeTransport channel = transport;
        final TaskRun task;
        try {
            if (activeTask == null) activeTask = restoreTask(apiKey, baseUrl);
            task = activeTask;
            if (task == null) return;
            if (channel == null && (!LinuxRuntime.isSessionId(task.sessionId) || !runtime.hasClaudeSession(task.sessionId))) {
                interactionState(visualState, "原会话文件不可用，无法继续；你仍可结束此任务。");
                return;
            }
        } catch (Exception unavailable) { return; }
        interactionState(VisualState.RESUMING, "正在继续原任务…");
        task.recordAnswer(answer);
        task.hold("resuming", lastStatus, question);
        Runnable resume = () -> {
            synchronized (AgentController.this) {
                if (cancelled || visualState != VisualState.RESUMING) return;
                if (question != null && (pendingQuestion == null
                        || !question.optString("id").equals(pendingQuestion.optString("id")))) return;
                pauseRequested = false; pendingQuestion = null; turnSettled = false; interruptId = "";
                task.hold("running", "正在继续原任务…", null);
                actionGate.open();
                interactionState(VisualState.THINKING, "正在继续原任务…");
                running = true;
                try {
                    acquireTaskWakeLock();
                    if (channel != null && channel == transport && channel.getProcess().isAlive()) {
                        boolean firstUser = !userSent;
                        userSent = true;
                        if (question != null) {
                            channel.answerQuestion(question.optString("id"), question.optJSONObject("input"), answers);
                            task.activity(VisualState.THINKING, "回答已提交，正在等待模型响应…");
                        } else task.sendUser(channel, firstUser ? "原任务：" + task.prompt + "\n" + prompt : prompt);
                    } else {
                        initialized = false; userSent = false;
                        worker.execute(() -> runTask(task, prompt, baseUrl.trim(), model.trim(), apiKey.trim(), task.sessionId));
                    }
                } catch (Exception unavailable) {
                    pauseRequested = true; turnSettled = true;
                    pendingQuestion = copy(question);
                    actionGate.close(); releaseTaskWakeLock();
                    interactionState(question == null ? VisualState.PAUSED : VisualState.WAITING_USER,
                            "继续失败，连接已中断；输入内容和任务记录已保留。");
                    task.hold(question == null ? "paused" : "waiting_user", lastStatus, question);
                    if (process != null) terminate(process);
                }
            }
        };
        dispatchWhenHidden(resume);
    }
    private TaskRun restoreTask(String key, String endpoint) throws Exception {
        JSONObject conversation = historyStore.getConversation(activeConversationId);
        JSONArray turns = conversation == null ? null : conversation.optJSONArray("turns");
        if (turns == null || turns.length() == 0) return null;
        JSONObject turn = turns.getJSONObject(turns.length() - 1);
        JSONObject started = new JSONObject().put("conversation_id", activeConversationId)
                .put("turn_id", turn.getString("id")).put("session_id", conversation.optString("session_id"))
                .put("started_at", turn.getLong("started_at"));
        TaskRun task = new TaskRun(presentationEpoch.get(), key == null ? "" : key, endpoint == null ? "" : endpoint,
                started, turn.optString("prompt"));
        task.processText = turn.optString("process"); task.finalResult = turn.optString("result");
        task.interactionAnswer = turn.optString("interaction_answer");
        task.metrics = ExecutionMetrics.fromRecord(turn);
        startTaskTicker(task);
        task.pending = copy(pendingQuestion); task.storedState = pendingQuestion == null ? "paused" : "waiting_user";
        task.rebuildOutput();
        return task;
    }
    private void runTask(TaskRun task, String prompt, String baseUrl, String model, String apiKey, String resumeSession) {
        long epoch = task.epoch;
        boolean ok = false, sawResult = false;
        Integer exitCode = null;
        String reason = "cli_no_result", detail = "执行连接中断，没有收到最终结果。", errorCategory = "";
        Process child = null;
        PhoneToolServer server = null;
        ClaudeTransport channel = null;
        try {
            synchronized (this) { if (!isSuspended()) acquireTaskWakeLock(); }
            if (cancelled) throw new CancellationException();
            server = new PhoneToolServer(new PhoneToolServer.Observer() {
                @Override public void onTool(String name) {
                    if (cancelled || isSuspended()) return;
                    String label = "正在操作手机：" + toolLabel(name);
                    task.phoneAction();
                    task.activity(VisualState.ACTING, label);
                }
                @Override public void onToolFinished(String name) { task.activity(VisualState.THINKING, "手机工具已返回，正在等待下一步…"); }
            }, actionGate);
            bridge = server;
            if (cancelled) throw new CancellationException();
            channel = runtime.startInteractiveClaude(baseUrl, model, apiKey, server.port(), server.token(), resumeSession);
            child = channel.getProcess();
            synchronized (this) {
                transport = channel; process = child;
                initializeId = java.util.UUID.randomUUID().toString();
            }
            if (cancelled) throw new CancellationException();
            channel.initialize(initializeId);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled) break;
                    JSONObject event;
                    try { event = new JSONObject(line); }
                    catch (Exception notJson) { if (!line.trim().isEmpty()) task.diagnostic(); continue; }
                    String type = event.optString("type");
                    task.observeEvent(event); // Include interrupted results before the pause branch consumes them.
                    if (type.equals("result") || type.equals("system") && event.optString("subtype").equals("init"))
                        task.captureSession(event.optString("session_id"), resumeSession);
                    if (type.equals("control_response")) {
                        JSONObject response = event.optJSONObject("response");
                        if (response != null && initializeId.equals(response.optString("request_id"))) {
                            if (!"success".equals(response.optString("subtype"))) throw new IOException("Claude initialization failed");
                            synchronized (this) {
                                initialized = true;
                                if (!pauseRequested && !cancelled && !userSent) {
                                    final ClaudeTransport readyChannel = channel;
                                    dispatchWhenHidden(() -> {
                                        if (cancelled || pauseRequested || userSent || readyChannel != transport) return;
                                        try {
                                            actionGate.open(); userSent = true;
                                            task.sendUser(readyChannel, prompt);
                                        } catch (IOException unavailable) { terminate(readyChannel.getProcess()); }
                                    });
                                } else if (pauseRequested) settlePauseIfReady(task);
                            }
                        } else if (response != null && !interruptId.isEmpty()
                                && interruptId.equals(response.optString("request_id"))) {
                            synchronized (this) {
                                if (!"success".equals(response.optString("subtype"))) throw new IOException("Claude interrupt failed");
                                interruptAcknowledged = true;
                                settlePauseIfReady(task);
                            }
                        }
                    } else if (type.equals("control_request")) {
                        JSONObject request = event.optJSONObject("request");
                        String requestId = event.optString("request_id");
                        if (request != null && "can_use_tool".equals(request.optString("subtype"))) {
                            JSONObject input = request.optJSONObject("input");
                            JSONArray questions = input == null ? null : input.optJSONArray("questions");
                            JSONObject candidate = new JSONObject().put("id", requestId).put("kind", "question").put("input", copy(input));
                            boolean validQuestion = true;
                            try { LastTaskStore.copyInteraction(new JSONObject().put("pending_question", candidate), new JSONObject()); }
                            catch (IOException invalid) { validQuestion = false; }
                            synchronized (this) {
                                if (validQuestion && !pauseRequested && pendingQuestion == null && "AskUserQuestion".equals(request.optString("tool_name"))
                                        && questions != null && questions.length() > 0 && questions.length() <= 4
                                        && input.toString().length() <= 16000) {
                                    actionGate.close(); releaseTaskWakeLock();
                                    pendingQuestion = candidate;
                                    interactionState(VisualState.WAITING_USER, "月薪喵有问题需要你确认，回答后继续。");
                                    task.hold("waiting_user", lastStatus, pendingQuestion);
                                    actionGate.whenIdle(() -> historyChanged(epoch));
                                } else channel.denyTool(requestId, pauseRequested ? "用户已暂停任务，请等待新的用户输入。" : "此工具未开放或问题格式无效。");
                            }
                        }
                    } else if (type.equals("control_cancel_request")) {
                        synchronized (this) {
                            if (pendingQuestion != null && pendingQuestion.optString("id").equals(event.optString("request_id"))) {
                                pendingQuestion = null; pauseRequested = true; deferredResume = null;
                                turnSettled = false;
                                actionGate.close(); releaseTaskWakeLock();
                                interactionState(VisualState.PAUSING, "原问题已取消，正在暂停任务…");
                                task.hold("pausing", lastStatus, null);
                                requestInterrupt(channel);
                            }
                        }
                    } else if (type.equals("assistant")) {
                        JSONObject msg = event.optJSONObject("message");
                        JSONArray content = msg == null ? null : msg.optJSONArray("content");
                        if (event.has("error") && !event.isNull("error")) {
                            errorCategory = knownErrorCategory(event.optString("error")); task.diagnostic(); continue;
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
                        synchronized (this) {
                            turnSettled = true;
                            if (pauseRequested || pendingQuestion != null) {
                                releaseTaskWakeLock();
                                if (pendingQuestion == null) {
                                    settlePauseIfReady(task);
                                }
                                continue;
                            }
                            actionGate.close();
                            finishing = true; // A later cat click must not reopen an already completed turn.
                        }
                        sawResult = true;
                        String subtype = event.optString("subtype", "success");
                        ok = !event.optBoolean("is_error", false) && "success".equals(subtype);
                        reason = ok ? "success" : "error_result";
                        detail = ok ? "执行进程已返回最终结果。" : resultFailure(event, subtype, errorCategory);
                        if (ok) task.appendResult(event.optString("result", ""));
                        break; // Interactive CLI deliberately keeps stdin open after a turn.
                    } else if (type.equals("system") && event.optString("subtype").equals("init")) {
                        if (!isSuspended()) task.activity(VisualState.THINKING, "模型已连接，正在等待响应…");
                    }
                }
            }
        } catch (Exception failure) {
            ok = false;
            if (failure instanceof LinuxRuntime.MissingSessionException) {
                reason = "session_missing"; detail = "原会话文件已丢失，无法继续；没有创建新会话。";
            } else if (failure instanceof SessionMismatchException) {
                reason = "session_mismatch"; detail = "执行进程返回的会话与原会话不符，已停止执行。";
            }
        } finally {
            synchronized (this) { initialized = false; }
            if (child != null) captureChild(child);
            if (server != null) server.close();
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            if (child != null) {
                boolean alreadyExited = !child.isAlive();
                terminate(child); // Retained descendant fingerprints also cover an already-exited tracer.
                if (alreadyExited) try { exitCode = child.exitValue(); } catch (IllegalThreadStateException ignored) { }
            }
            synchronized (this) {
                bridge = null; process = null; transport = null; initialized = false;
                releaseTaskWakeLock();
            }
        }
        synchronized (this) {
            if (!cancelled && isSuspended() && !reason.equals("session_mismatch")) {
                running = false; turnSettled = true;
                // The next explicit input uses --resume. No automatic model call on process death/restart.
                interactionState(pendingQuestion == null ? VisualState.PAUSED : VisualState.WAITING_USER,
                        "执行连接已关闭，任务已保留；确认继续时将恢复原会话。");
                task.hold(pendingQuestion == null ? "paused" : "waiting_user", lastStatus, pendingQuestion);
                return;
            }
            finishing = true;
            if (cancelled) { ok = false; reason = "user_stop"; detail = "用户手动停止了任务。"; }
        }
        if (!cancelled && !sawResult && !resumeSession.isEmpty() && !task.sessionConfirmed
                && !reason.equals("session_missing") && !reason.equals("session_mismatch")) {
            reason = "resume_failed"; detail = "原会话未能恢复，执行进程没有返回最终结果；没有创建新会话。";
        }
        if (!cancelled && !sawResult && !errorCategory.isEmpty()) detail += "\n错误类别：" + errorCategory;
        String state = cancelled ? "stopped" : ok ? "success" : "error";
        String message = cancelled ? "已停止任务，记录已保留。" : ok ? "本次指令已结束。" : "本次指令未完成，请查看任务记录。";
        String transcript = task.finish(state, message, reason, detail, exitCode);
        finished(epoch, cancelled ? VisualState.STOPPED : ok ? VisualState.SUCCESS : VisualState.ERROR, ok, message, transcript);
    }

    /** UI heartbeat only; no model call and no storage write every second. */
    private void startTaskTicker(TaskRun task) {
        main.post(() -> {
            if (taskTicker != null) main.removeCallbacks(taskTicker);
            taskTicker = new Runnable() {
                @Override public void run() {
                    synchronized (AgentController.this) {
                        if (activeTask != task || task.epoch != presentationEpoch.get()) return;
                        // The CLI reader may enter WAITING_USER while the heartbeat runs.
                        // Publish under the same lock so an old wait status cannot replace it.
                        if (running && !cancelled && !finishing && !isSuspended()) {
                            String text = task.liveStatus();
                            lastStatus = text;
                            Listener current = listener;
                            if (current != null) current.onStatus(text);
                            task.recordLongWait();
                        }
                    }
                    main.postDelayed(this, 1000);
                }
            };
            main.post(taskTicker);
        });
    }

    /** Serializes output and persistence from the CLI reader and the tool observer. */
    private final class TaskRun {
        final long epoch, startedAt;
        final String key, endpoint, conversationId, turnId, prompt;
        String transcript = "", processText = "", finalResult = "", terminalText = "", sessionId;
        String status = "正在连接模型…", storedState = "running", interactionAnswer = "";
        JSONObject pending;
        ExecutionMetrics metrics = new ExecutionMetrics();
        final java.util.Map<String, String> activeTools = new java.util.HashMap<>();
        VisualState activityPhase = VisualState.THINKING;
        String activityText = "正在连接模型…";
        long activityAt = android.os.SystemClock.elapsedRealtime(), waitingAt = activityAt, retryAt, lastWaitLogAt = activityAt;
        int retryAttempt, retryHttp;
        boolean done, sawDiagnostic, saveFailed, sessionConfirmed;
        TaskRun(long epoch, String key, String endpoint, JSONObject started, String prompt) throws Exception {
            this.epoch = epoch; this.key = key; this.endpoint = endpoint;
            this.conversationId = started.getString("conversation_id");
            this.turnId = started.getString("turn_id");
            this.sessionId = started.optString("session_id");
            this.startedAt = started.getLong("started_at");
            this.prompt = LastTaskStore.tail(redact(prompt));
        }
        synchronized void captureSession(String value, String requested) throws IOException {
            if (!LinuxRuntime.isSessionId(value)) return;
            String normalized = value.toLowerCase(java.util.Locale.US);
            if ((!requested.isEmpty() && !requested.equalsIgnoreCase(normalized))
                    || (!sessionId.isEmpty() && !sessionId.equals(normalized))) throw new SessionMismatchException();
            sessionConfirmed = true;
            if (normalized.equals(sessionId)) return;
            historyStore.bindSession(conversationId, normalized);
            sessionId = normalized;
            save(storedState, "", null, 0);
        }
        synchronized void progress(String message) { activity(VisualState.THINKING, message); }
        synchronized void activity(VisualState value, String message) {
            if (done || !storedState.equals("running") || cancelled || isSuspended()) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (value == VisualState.ACTING) waitingAt = 0;
            else if (waitingAt == 0) waitingAt = now;
            activityAt = now; lastWaitLogAt = now;
            activityPhase = value; activityText = message; status = message;
            if (value != VisualState.RETRYING) retryAt = 0;
            append("【进度】" + message);
            phase(epoch, value, message);
        }
        synchronized void sendUser(ClaudeTransport channel, String text) throws IOException {
            String identity = java.util.UUID.randomUUID().toString();
            metrics.beginQuery(identity);
            activeTools.clear();
            waitingAt = android.os.SystemClock.elapsedRealtime();
            activity(VisualState.THINKING, "正在等待模型响应…");
            save(storedState, "", null, 0);
            channel.sendUser(text, identity);
        }
        synchronized void phoneAction() {
            metrics.recordPhoneAction(java.util.UUID.randomUUID().toString());
            save(storedState, "", null, 0);
        }
        synchronized void observeEvent(JSONObject event) {
            if (done) return;
            if (metrics.observe(event)) save(storedState, "", null, 0);
            String type = event.optString("type");
            if ("result".equals(type)) activeTools.clear();
            if ("system".equals(type) && "api_retry".equals(event.optString("subtype"))) {
                if (!storedState.equals("running") || cancelled || isSuspended()) return;
                long now = android.os.SystemClock.elapsedRealtime();
                retryAttempt = Math.max(1, event.optInt("attempt", 1));
                int http = event.optInt("error_status"); retryHttp = http >= 400 && http <= 599 ? http : 0;
                long delay = Math.max(0, Math.min(3600000, event.optLong("retry_delay_ms")));
                if (waitingAt == 0) waitingAt = now;
                activityAt = now; lastWaitLogAt = now; retryAt = now + delay;
                activityPhase = VisualState.RETRYING;
                activityText = "模型服务异常" + (retryHttp == 0 ? "" : "（HTTP " + retryHttp + "）") + "，第 " + retryAttempt + " 次重试";
                status = liveStatus();
                append("【重试】" + status + "；可点月薪喵暂停。");
                phase(epoch, VisualState.RETRYING, status);
                return;
            }
            JSONObject message = event.optJSONObject("message");
            JSONArray content = message == null ? null : message.optJSONArray("content");
            if ("assistant".equals(type) && content != null && (!event.has("error") || event.isNull("error"))) {
                boolean tool = false;
                for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    if (part == null || !"tool_use".equals(part.optString("type"))) continue;
                    String identity = part.optString("id"), name = part.optString("name");
                    String label = toolLabel(name.startsWith("mcp__phone__") ? name.substring(12) : name);
                    if (!activeTools.containsKey(identity)) {
                        activeTools.put(identity, label);
                        activity(VisualState.ACTING, "正在执行工具：" + label);
                    }
                    tool = true;
                }
                if (!tool && activeTools.isEmpty()) activity(VisualState.THINKING, "已收到模型响应，正在处理回复…");
            } else if ("user".equals(type) && content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    if (part == null || !"tool_result".equals(part.optString("type"))) continue;
                    String label = activeTools.remove(part.optString("tool_use_id"));
                    if (label != null) {
                        boolean verifiedRoutine = "运行已保存脚本".equals(label) && routineWasVerified(part);
                        String outcome = verifiedRoutine ? "脚本完成，已验证预期界面。" : label + (part.optBoolean("is_error") ? "报告失败。" : "已返回。");
                        append("【工具】" + outcome);
                        if (activeTools.isEmpty()) activity(VisualState.THINKING,
                                verifiedRoutine ? "脚本已完成验证，正在等待模型整理结果…" : "工具已返回，正在等待模型响应…");
                    }
                }
            } else if ("tool_progress".equals(type)) {
                String label = activeTools.get(event.optString("tool_use_id"));
                if (label != null && activityPhase != VisualState.ACTING)
                    activity(VisualState.ACTING, "正在执行工具：" + label);
            }
        }
        private boolean routineWasVerified(JSONObject result) {
            if (result.optBoolean("is_error")) return false;
            try {
                Object content = result.opt("content");
                String text = content instanceof String ? (String) content : "";
                if (content instanceof JSONArray) {
                    JSONArray blocks = (JSONArray) content;
                    if (blocks.length() != 1) return false;
                    text = blocks.getJSONObject(0).optString("text");
                }
                if (text.length() > 4096) return false;
                JSONObject value = new JSONObject(text);
                return value.optBoolean("ok") && value.optJSONObject("verification") != null;
            } catch (Exception notRoutineResult) { return false; }
        }
        synchronized String liveStatus() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (activityPhase == VisualState.RETRYING) {
                long seconds = Math.max(0, (retryAt - now + 999) / 1000);
                return activityText + (seconds > 0 ? "，" + seconds + " 秒后重试" : "，正在重新请求")
                        + " · 已等待 " + Math.max(0, (now - waitingAt) / 1000) + " 秒";
            }
            long seconds = Math.max(0, (now - activityAt) / 1000);
            return activityText + (seconds >= 5 ? " · 已等待 " + seconds + " 秒" : "");
        }
        synchronized void recordLongWait() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (done || !storedState.equals("running") || now - lastWaitLogAt < 30000) return;
            lastWaitLogAt = now;
            append("【等待】" + liveStatus() + "；可暂停后补充或结束任务。");
        }
        synchronized void diagnostic() {
            if (!sawDiagnostic) { sawDiagnostic = true; progress("执行进程返回诊断信息，正在等待最终结果。"); }
        }
        synchronized void appendResult(String text) {
            if (done || text == null) return;
            finalResult = LastTaskStore.tail(redact(text));
            // The CLI also emits its final prose as an assistant event; move that same text to Results.
            if (!finalResult.isEmpty()) {
                if (processText.equals(finalResult)) processText = "";
                else if (processText.endsWith("\n\n" + finalResult))
                    processText = processText.substring(0, processText.length() - finalResult.length() - 2);
            }
            rebuildOutput();
            save(storedState, "", null, 0);
            output(epoch, transcript);
        }
        synchronized void append(String text) {
            if (done || text == null || text.isEmpty()) return;
            processText = LastTaskStore.tail(processText + (processText.isEmpty() ? "" : "\n\n") + redact(text));
            rebuildOutput();
            save(storedState, "", null, 0);
            output(epoch, transcript);
        }
        synchronized void hold(String state, String message, JSONObject question) {
            if (done) return;
            storedState = state; status = message; pending = copy(question);
            save(state, "", null, 0);
        }
        synchronized void recordAnswer(String answer) {
            interactionAnswer = interactionAnswer + (interactionAnswer.isEmpty() ? "" : "\n\n") + redact(answer);
            if (interactionAnswer.length() > 8000) interactionAnswer = interactionAnswer.substring(interactionAnswer.length() - 8000);
            append("【用户补充】" + answer);
        }
        synchronized String finish(String state, String message, String reason, String detail, Integer exit) {
            done = true;
            pending = null; storedState = state;
            status = message;
            String label = state.equals("success") ? "完成" : state.equals("stopped") ? "已停止" : "未完成";
            terminalText = redact(LastTaskStore.terminalFooter(label, detail, exit, null)) + "\n" + metrics.summary();
            rebuildOutput();
            long ended = System.currentTimeMillis();
            save(state, reason, exit, ended);
            if (saveFailed) {
                terminalText += "\n记录暂时无法保存，当前仍可查看。";
                rebuildOutput();
                save(state, reason, exit, ended);
            }
            return transcript;
        }
        private void rebuildOutput() {
            StringBuilder combined = new StringBuilder(processText);
            for (String part : new String[]{finalResult, terminalText}) {
                if (part.isEmpty()) continue;
                if (combined.length() > 0) combined.append("\n\n");
                combined.append(part);
            }
            transcript = LastTaskStore.tail(combined.toString());
        }
        private void save(String state, String reason, Integer exit, long ended) {
            saveFailed = false;
            try {
                JSONObject record = new JSONObject().put("version", 1).put("state", state)
                        .put("status", redact(status)).put("output", transcript).put("reason", reason)
                        .put("conversation_id", conversationId).put("turn_id", turnId).put("session_id", sessionId)
                        .put("started_at", startedAt).put("updated_at", System.currentTimeMillis()).put("ended_at", ended);
                record.put("pending_question", pending == null ? JSONObject.NULL : new JSONObject(redact(pending.toString())));
                record.put("interaction_answer", interactionAnswer);
                if (exit != null) record.put("exit_code", exit);
                metrics.writeTo(record);
                try { taskStore.write(record); } catch (IOException unavailable) { saveFailed = true; }
                JSONObject turn = new JSONObject(record.toString()).put("id", turnId).put("prompt", prompt)
                        .put("process", processText).put("result", finalResult).put("terminal", terminalText);
                try { historyStore.updateTurn(conversationId, turnId, turn); }
                catch (IOException unavailable) { saveFailed = true; }
            } catch (Exception unavailable) { saveFailed = true; }
            historyChanged(epoch);
        }
        private String redact(String text) { return redactText(text, key, endpoint); }
    }

    private static final class SessionMismatchException extends IOException { }
    private static String redactText(String text, String key, String endpoint) {
        String safe = text;
        if (key != null && !key.isEmpty()) safe = safe.replace(key, "[已隐藏]");
        if (endpoint != null && !endpoint.isEmpty()) safe = safe.replace(endpoint, "[服务地址已隐藏]");
        return safe.replaceAll("(?i)((?:proxy-)?authorization[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\r\\n\\\"'}]+", "$1[已隐藏]")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[已隐藏]")
                .replaceAll("(?i)((?:x-api-key|api[_-]?key)\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+", "$1[已隐藏]");
    }

    public void stop() {
        final long epoch;
        synchronized (this) {
            if (!hasActiveTask() || cancelled || finishing) return;
            epoch = presentationEpoch.get();
            cancelled = true;
            deferredResume = null;
            actionGate.close();
            releaseTaskWakeLock();
            if (!running) {
                try {
                    if (activeTask == null) activeTask = restoreTask("", "");
                    String transcript = activeTask == null ? lastOutput : activeTask.finish("stopped", "已停止任务，记录已保留。",
                            "user_stop", "用户手动停止了任务。", null);
                    finished(epoch, VisualState.STOPPED, false, "已停止任务，记录已保留。", transcript);
                } catch (Exception unavailable) {
                    finished(epoch, VisualState.STOPPED, false, "已停止任务，记录暂时无法保存。", lastOutput);
                }
                return;
            }
        }
        main.post(() -> {
            if (epoch != presentationEpoch.get() || !running) return;
            lastStatus = "正在停止…";
            publishVisualState(VisualState.STOPPING);
            Listener l = listener;
            if (l != null) l.onStatus(lastStatus);
        });
        PhoneToolServer server = bridge;
        if (server != null) server.close();
        Process child = process;
        ClaudeTransport channel = transport;
        if (child != null && channel != null) {
            Thread cleanup = new Thread(() -> {
                captureChild(child);
                // Cancel the native question/turn before EOF so Claude does not interpret
                // a closed permission channel as an unanswered tool to retry.
                try { channel.interrupt(); } catch (IOException ignored) { }
                try { channel.close(); } catch (IOException ignored) { }
                terminate(child);
            }, "phone-agent-stop");
            cleanup.setDaemon(true);
            cleanup.start();
        } else runtime.cancel(); // Also invalidates a launch that has not registered its child yet.
    }
    private static void captureChild(Process child) {
        for (;;) {
            try { ProcessLifecycle.capture(child); return; }
            catch (ProcessLifecycle.CleanupException waiting) { android.os.SystemClock.sleep(1000); }
        }
    }
    private static void terminate(Process child) {
        for (;;) {
            try { ProcessLifecycle.terminate(child); return; }
            catch (ProcessLifecycle.CleanupException waiting) { android.os.SystemClock.sleep(1000); }
        }
    }
    private static int childPid(Process child) { return ProcessLifecycle.pid(child); }
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
            case "Read": return "读取文件";
            case "Write": return "写入文件";
            case "Edit": return "修改文件";
            case "Glob": return "查找文件";
            case "Grep": return "搜索文件内容";
            case "AskUserQuestion": return "等待用户回答";
            case "list_routines": return "查看已保存脚本";
            case "save_routine": return "保存已验证脚本";
            case "run_routine": return "运行已保存脚本";
            case "open_app_ready": return "打开应用并等待就绪";
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
