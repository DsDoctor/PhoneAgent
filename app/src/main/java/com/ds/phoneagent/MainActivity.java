package com.ds.phoneagent;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Minimal, native UI. Runtime internals stay behind the task and setup controls. */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(27, 46, 37);
    private static final int MUTED = Color.rgb(100, 118, 108);
    private static final int GREEN = Color.rgb(23, 106, 85);
    private static final String KEY_ALIAS = "phoneagent.api.key.v1";
    private AgentController controller;
    private SharedPreferences preferences;
    private TextView state, capabilities;
    private EditText prompt, baseUrl, model, apiKey;
    private Button send, stop, prepare, control, bubble;
    private LinearLayout modelFields, setupPanel, historyPanel;
    private String selectedConversationId = "";
    private boolean showHistory = true;
    private String renderedHistory = "";
    private long historyRenderVersion;
    private int visibleTurnLimit = 10;
    private String unsavedOutput = "", unsavedConversationId = "";
    private final Map<String, String> drafts = new HashMap<>();
    private final Set<String> expandedProcesses = new HashSet<>();
    private ScrollView contentScroll;
    private MonthlyCatView statePet;

    private final AgentController.Listener listener = new AgentController.Listener() {
        @Override public void onStatus(String text) {
            runOnUiThread(() -> { state.setText(text); refreshState(); });
        }
        @Override public void onOutput(String text) {
            runOnUiThread(() -> {
                boolean failed = text != null && text.contains("记录暂时无法保存");
                boolean hadFallback = !unsavedOutput.isEmpty();
                unsavedOutput = failed ? text : "";
                unsavedConversationId = failed ? controller.getActiveConversationId() : "";
                if (failed || hadFallback) renderHistory(true);
            });
        }
        @Override public void onHistoryChanged() {
            runOnUiThread(() -> { renderHistory(false); refreshState(); });
        }
        @Override public void onFinished(boolean success, String message) {
            runOnUiThread(() -> {
                state.setText(message);
                refreshState();
                renderHistory(false);
            });
        }
        @Override public void onVisualState(AgentController.VisualState value) {
            runOnUiThread(() -> { statePet.setState(value); refreshState(); });
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        preferences = getSharedPreferences("phoneagent", MODE_PRIVATE);
        controller = AgentController.get(this);
        createUi();
        baseUrl.setText(preferences.getString("base_url", ""));
        model.setText(preferences.getString("model", ""));
        apiKey.setText(loadKey());
        modelFields.setVisibility(baseUrl.length() == 0 || model.length() == 0 ? View.VISIBLE : View.GONE);
        if (savedState != null) {
            prompt.setText(savedState.getString("draft", ""));
            selectedConversationId = savedState.getString("conversation", "");
            showHistory = savedState.getBoolean("history", true);
            Bundle savedDrafts = savedState.getBundle("drafts");
            if (savedDrafts != null) for (String key : savedDrafts.keySet())
                drafts.put(key, savedDrafts.getString(key, ""));
        } else if (controller.isRunning()) {
            selectedConversationId = controller.getActiveConversationId();
            showHistory = false;
        }
        setupPanel.setVisibility(!controller.isReady() || baseUrl.length() == 0 || model.length() == 0 ? View.VISIBLE : View.GONE);
        renderHistory(true);
        controller.setListener(listener);
    }

    @Override protected void onResume() {
        super.onResume();
        OverlayService.setActivityVisible(true);
        OverlayService.start(this, "");
        refreshState();
    }

    @Override protected void onPause() {
        OverlayService.setActivityVisible(false);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        stashDraft();
        Bundle savedDrafts = new Bundle();
        for (Map.Entry<String, String> entry : drafts.entrySet()) savedDrafts.putString(entry.getKey(), entry.getValue());
        outState.putBundle("drafts", savedDrafts);
        outState.putString("draft", prompt.getText().toString());
        outState.putString("conversation", selectedConversationId);
        outState.putBoolean("history", showHistory);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        controller.removeListener(listener);
        super.onDestroy();
    }

    private void createUi() {
        LinearLayout page = vertical();
        page.setBackgroundColor(Color.rgb(244, 247, 245));
        page.setPadding(dp(20), dp(16), dp(20), dp(12));
        TextView title = text("PhoneAgent", 28, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);
        TextView subtitle = text("月薪喵 · 让手机替你动手", 14, MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        page.addView(subtitle);

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(false);
        contentScroll.setClipToPadding(false);
        page.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = vertical();
        contentScroll.addView(content);

        LinearLayout navigation = horizontal();
        Button history = button("任务记录", false);
        Button fresh = button("＋ 新任务", true);
        navigation.addView(history, weighted());
        navigation.addView(fresh, weighted());
        content.addView(navigation, spaced());
        history.setOnClickListener(view -> {
            hideKeyboard(); navigate(true, "");
            contentScroll.smoothScrollTo(0, 0);
        });
        fresh.setOnClickListener(view -> {
            navigate(false, "");
            contentScroll.smoothScrollTo(0, 0);
        });

        historyPanel = vertical();
        content.addView(historyPanel);
        Button setupToggle = button("设置与连接  ›", false);
        content.addView(setupToggle, spaced());
        setupPanel = vertical();
        content.addView(setupPanel);
        setupToggle.setOnClickListener(view -> setupPanel.setVisibility(
                setupPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        LinearLayout setup = card();
        LinearLayout statusRow = horizontal();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statePet = new MonthlyCatView(this);
        statePet.setState(controller.getVisualState());
        LinearLayout.LayoutParams petSize = new LinearLayout.LayoutParams(dp(72), dp(84));
        petSize.rightMargin = dp(12);
        statusRow.addView(statePet, petSize);
        state = text("先准备环境，再开启手机控制", 15, INK);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        statusRow.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout statusCard = card();
        statusCard.addView(statusRow);
        content.addView(statusCard, 1, spaced());
        capabilities = text("", 12, MUTED);
        capabilities.setPadding(0, dp(7), 0, dp(8));
        setup.addView(capabilities);
        LinearLayout setupRow = horizontal();
        prepare = button("准备环境", true);
        control = button("开启手机控制", false);
        setupRow.addView(prepare, weighted());
        setupRow.addView(control, weighted());
        setup.addView(setupRow);
        bubble = button("开启悬浮球", false);
        setup.addView(bubble, new LinearLayout.LayoutParams(-1, dp(46)));
        setupPanel.addView(setup, spaced());

        prepare.setOnClickListener(view -> {
            hideKeyboard();
            controller.installRuntime();
            refreshState();
        });
        control.setOnClickListener(view -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        bubble.setOnClickListener(view -> {
            if (!Settings.canDrawOverlays(this) && !PhoneAccessibilityService.isEnabled()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } else {
                boolean enabled = preferences.getBoolean("bubble_enabled", true);
                OverlayService.start(this, enabled ? OverlayService.ACTION_HIDE : OverlayService.ACTION_SHOW);
                preferences.edit().putBoolean("bubble_enabled", !enabled).apply();
                refreshState();
            }
        });

        LinearLayout modelCard = card();
        Button settings = button("连接模型  ›", false);
        settings.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        modelCard.addView(settings, new LinearLayout.LayoutParams(-1, dp(42)));
        modelFields = vertical();
        modelCard.addView(modelFields);
        TextView help = text("填写兼容 Anthropic API 的服务地址与模型。密钥加密保存在本机。", 12, MUTED);
        help.setPadding(0, dp(4), 0, dp(10));
        modelFields.addView(help);
        baseUrl = field("API 地址", "https://api.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, modelFields);
        model = field("模型", "输入服务商的模型名称", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, modelFields);
        apiKey = field("API Key", "输入密钥", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, modelFields);
        apiKey.setSaveEnabled(false);
        Button save = button("保存连接配置", false);
        modelFields.addView(save, new LinearLayout.LayoutParams(-1, dp(46)));
        save.setOnClickListener(view -> {
            saveSettings();
            hideKeyboard();
            modelFields.setVisibility(View.GONE);
        });
        settings.setOnClickListener(view -> modelFields.setVisibility(modelFields.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        setupPanel.addView(modelCard, spaced());

        LinearLayout composer = vertical();
        composer.setPadding(0, dp(12), 0, 0);
        prompt = new EditText(this);
        prompt.setHint("例如：打开系统设置");
        prompt.setTextSize(16);
        prompt.setTextColor(INK);
        prompt.setHintTextColor(MUTED);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setMinLines(2);
        prompt.setMaxLines(4);
        prompt.setGravity(Gravity.TOP | Gravity.LEFT);
        prompt.setPadding(dp(14), dp(12), dp(14), dp(12));
        prompt.setBackground(shape(Color.WHITE, Color.rgb(211, 223, 216), 14));
        composer.addView(prompt, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(6), 0, 0);
        send = button("开始执行", true);
        stop = button("停止", false);
        actions.addView(send, new LinearLayout.LayoutParams(0, dp(50), 3));
        LinearLayout.LayoutParams stopSize = new LinearLayout.LayoutParams(0, dp(50), 1);
        stopSize.leftMargin = dp(8);
        actions.addView(stop, stopSize);
        composer.addView(actions);
        TextView tip = text("执行时会回到手机界面，点悬浮球查看进度或停止。", 11, MUTED);
        tip.setPadding(0, dp(7), 0, 0);
        composer.addView(tip);
        page.addView(composer);
        setContentView(page);
        send.setOnClickListener(view -> submit());
        stop.setOnClickListener(view -> {
            if (controller.isRunning()) controller.stop();
            else controller.resetToIdle();
            refreshState();
        });
    }

    private void submit() {
        String task = prompt.getText().toString().trim();
        String endpoint = baseUrl.getText().toString().trim();
        String chosenModel = model.getText().toString().trim();
        String secret = apiKey.getText().toString().trim();
        if (task.isEmpty()) { toast("先输入想让手机完成的任务"); return; }
        if (!controller.isReady()) { toast("请先准备环境"); return; }
        if (!PhoneAccessibilityService.isEnabled()) { toast("请先开启 PhoneAgent 手机控制"); return; }
        if (endpoint.isEmpty() || chosenModel.isEmpty() || secret.isEmpty()) {
            setupPanel.setVisibility(View.VISIBLE);
            modelFields.setVisibility(View.VISIBLE);
            toast("请填写模型服务地址、模型名称和 API Key");
            return;
        }
        try {
            URI uri = new URI(endpoint);
            boolean local = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) throw new Exception();
        } catch (Exception invalid) {
            toast("请填写 HTTPS 服务地址；仅本机服务允许 HTTP。"); return;
        }
        if (!selectedConversationId.isEmpty() && !showHistory) {
            JSONObject conversation = controller.getConversation(selectedConversationId);
            if (conversation == null || !conversation.optBoolean("can_continue")) {
                toast(conversation == null ? "这段对话已不可用，请新建任务。"
                        : conversation.optString("continue_unavailable_reason", "这段对话暂时无法继续，请新建任务。"));
                return;
            }
        }
        saveSettings();
        hideKeyboard();
        String submittedDraft = draftKey();
        controller.runInConversation(showHistory ? "" : selectedConversationId, task, endpoint, chosenModel, secret);
        refreshState();
        if (controller.isRunning()) {
            drafts.remove(submittedDraft);
            selectedConversationId = controller.getActiveConversationId();
            showHistory = false;
            prompt.setText("");
            renderHistory(true);
            moveTaskToBack(true);
        }
    }

    private void refreshState() {
        if (controller == null || prepare == null) return;
        boolean running = controller.isRunning();
        statePet.setState(controller.getVisualState());
        boolean ready = controller.isReady();
        boolean accessible = PhoneAccessibilityService.isEnabled();
        boolean overlay = Settings.canDrawOverlays(this) || accessible;
        capabilities.setText((ready ? "环境已就绪" : "环境待准备") + "  ·  "
                + (accessible ? "手机控制已开启" : "手机控制未开启") + "  ·  "
                + (overlay ? "悬浮球可用" : "悬浮球待授权"));
        prepare.setText(ready ? "环境已就绪" : "准备环境");
        prepare.setEnabled(!running && !ready);
        control.setText(accessible ? "手机控制设置" : "开启手机控制");
        bubble.setText(!overlay ? "开启悬浮球权限" : preferences.getBoolean("bubble_enabled", true) ? "隐藏悬浮球" : "显示悬浮球");
        boolean continuing = !showHistory && !selectedConversationId.isEmpty();
        JSONObject selected = continuing ? controller.getConversation(selectedConversationId) : null;
        boolean canContinue = selected != null && selected.optBoolean("can_continue");
        send.setText(continuing ? "继续对话" : "开始执行");
        send.setEnabled(!running && (!continuing || canContinue));
        prompt.setHint(continuing ? "继续追问，或让月薪喵接着操作…" : "例如：打开大麦，看看北京的演唱会");
        stop.setText(running ? "停止" : "重置");
        stop.setEnabled(running || controller.canResetToIdle());
    }

    @Override public void onBackPressed() {
        if (!showHistory) {
            navigate(true, "");
            contentScroll.smoothScrollTo(0, 0);
        } else super.onBackPressed();
    }

    private void renderHistory(boolean force) {
        if (historyPanel == null) return;
        JSONObject detail = !showHistory && !selectedConversationId.isEmpty()
                ? controller.getConversation(selectedConversationId) : null;
        JSONArray conversations = showHistory ? controller.getConversations() : null;
        String fingerprint = showHistory + ":" + selectedConversationId + ":"
                + (showHistory ? conversations.toString() : detail == null ? "" : detail.toString())
                + ":" + expandedProcesses.toString();
        if (!force && fingerprint.equals(renderedHistory)) return;
        renderedHistory = fingerprint;
        long rendering = ++historyRenderVersion;
        int scrollY = contentScroll.getScrollY();
        boolean atEnd = contentScroll.getChildAt(0).getHeight() - contentScroll.getHeight() - scrollY < dp(80);
        historyPanel.removeAllViews();
        if (showHistory) {
            TextView label = text("最近任务 · 保留 20 个会话", 13, MUTED);
            label.setPadding(0, dp(2), 0, dp(12)); historyPanel.addView(label);
            if (conversations.length() == 0) {
                LinearLayout empty = card(); empty.addView(text(welcome(), 16, INK));
                historyPanel.addView(empty, spaced());
            }
            for (int i = 0; i < conversations.length(); i++) {
                JSONObject entry = conversations.optJSONObject(i);
                if (entry == null) continue;
                final String id = entry.optString("id");
                LinearLayout item = card();
                TextView title = text(entry.optString("title", "手机任务"), 17, INK);
                title.setTypeface(Typeface.DEFAULT_BOLD); title.setMaxLines(2); item.addView(title);
                TextView meta = text(date(entry.optLong("updated_at")) + "  ·  " + stateLabel(entry.optString("status"))
                        + "  ·  " + entry.optInt("turn_count", 1) + " 轮对话", 12, MUTED);
                meta.setPadding(0, dp(7), 0, dp(8)); item.addView(meta);
                String preview = entry.optString("last_result").replaceAll("[\\r\\n]+", " ");
                if (!preview.isEmpty()) {
                    TextView excerpt = text(preview, 14, MUTED); excerpt.setMaxLines(2); item.addView(excerpt);
                }
                TextView open = text("查看结果与过程  ›", 13, GREEN); open.setPadding(0, dp(9), 0, 0); item.addView(open);
                item.setContentDescription(entry.optString("title") + "，" + stateLabel(entry.optString("status")) + "，查看任务");
                item.setClickable(true); item.setFocusable(true);
                item.setOnClickListener(view -> openConversation(id));
                historyPanel.addView(item, spaced());
            }
        } else if (detail == null) {
            LinearLayout empty = card();
            TextView heading = text(selectedConversationId.isEmpty() ? "想让月薪喵做什么？" : "这段对话已不在近期记录中", 18, INK);
            heading.setTypeface(Typeface.DEFAULT_BOLD); empty.addView(heading);
            TextView help = text(selectedConversationId.isEmpty() ? "在下方输入任务，完成后可以继续追问。" : "点“新任务”开始一段新对话。", 14, MUTED);
            help.setPadding(0, dp(10), 0, 0); empty.addView(help); historyPanel.addView(empty, spaced());
        } else {
            TextView heading = text(detail.optString("title", "手机任务"), 20, INK);
            heading.setTypeface(Typeface.DEFAULT_BOLD); heading.setPadding(0, dp(2), 0, dp(12));
            historyPanel.addView(heading);
            JSONArray turns = detail.optJSONArray("turns");
            if (turns != null) {
                int start = Math.max(0, turns.length() - visibleTurnLimit);
                if (start > 0) {
                    Button older = button("显示更早对话（还有 " + start + " 轮）", false);
                    older.setOnClickListener(view -> { visibleTurnLimit += 10; renderHistory(true); });
                    historyPanel.addView(older, spaced());
                }
                for (int i = start; i < turns.length(); i++) {
                    JSONObject turn = turns.optJSONObject(i); if (turn == null) continue;
                    addTurn(turn, i + 1);
                }
            }
            if (selectedConversationId.equals(unsavedConversationId) && !unsavedOutput.isEmpty()) {
                LinearLayout fallback = card();
                TextView label = text("本轮记录暂未保存 · 请先复制", 14, GREEN);
                label.setTypeface(Typeface.DEFAULT_BOLD); fallback.addView(label);
                TextView available = text(unsavedOutput, 14, INK);
                available.setTextIsSelectable(true); available.setPadding(0, dp(10), 0, 0);
                fallback.addView(available); historyPanel.addView(fallback, spaced());
            }
            if (!detail.optBoolean("can_continue") && !"running".equals(detail.optString("status"))) {
                TextView note = text(detail.optString("continue_unavailable_reason", "这段对话暂时无法继续，可以新建任务。"), 12, MUTED);
                note.setPadding(0, dp(2), 0, dp(14)); historyPanel.addView(note);
            }
        }
        if (!force) contentScroll.post(() -> {
            if (rendering != historyRenderVersion || isFinishing() || isDestroyed()) return;
            if (atEnd && !showHistory) contentScroll.fullScroll(View.FOCUS_DOWN);
            else contentScroll.scrollTo(0, scrollY);
        });
    }

    private String draftKey() { return !showHistory && !selectedConversationId.isEmpty() ? selectedConversationId : "new-task"; }
    private void stashDraft() {
        if (prompt == null) return;
        String value = prompt.getText().toString();
        if (value.isEmpty()) drafts.remove(draftKey()); else drafts.put(draftKey(), value);
    }
    private void navigate(boolean history, String id) {
        stashDraft();
        showHistory = history; selectedConversationId = id;
        visibleTurnLimit = 10;
        prompt.setText(drafts.containsKey(draftKey()) ? drafts.get(draftKey()) : "");
        renderHistory(true); refreshState();
    }

    private void openConversation(String id) {
        hideKeyboard(); navigate(false, id);
        contentScroll.smoothScrollTo(0, 0);
    }

    private void addTurn(JSONObject turn, int number) {
        LinearLayout item = card();
        TextView meta = text("对话 " + number + "  ·  " + date(turn.optLong("started_at")) + "  ·  " + stateLabel(turn.optString("state")), 12, MUTED);
        item.addView(meta);
        TextView request = text(turn.optString("prompt", "旧版任务"), 16, INK);
        request.setTypeface(Typeface.DEFAULT_BOLD); request.setTextIsSelectable(true);
        request.setPadding(0, dp(10), 0, dp(12)); item.addView(request);
        String result = turn.optString("result");
        if (!result.isEmpty()) {
            TextView label = text("最终结果", 13, GREEN); label.setTypeface(Typeface.DEFAULT_BOLD); item.addView(label);
            MarkdownView rich = new MarkdownView(this); rich.setMarkdown(result);
            rich.setPadding(0, dp(8), 0, dp(6)); item.addView(rich);
            Button copy = button("复制结果", false);
            copy.setOnClickListener(view -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("任务结果", result)); toast("已复制 Markdown 结果");
            });
            item.addView(copy, new LinearLayout.LayoutParams(-1, dp(40)));
        } else {
            TextView pending = text("running".equals(turn.optString("state"))
                    ? "月薪喵正在处理，完成后结果会显示在这里。" : "这轮没有生成最终结果，可查看执行过程与结束原因。", 14, MUTED);
            pending.setPadding(0, dp(4), 0, dp(10)); item.addView(pending);
        }
        String process = turn.optString("process");
        final String turnId = turn.optString("id", selectedConversationId + "-" + number);
        boolean expanded = expandedProcesses.contains(turnId);
        Button processButton = button((expanded ? "收起执行过程  ∧" : "展开执行过程  ∨"), false);
        processButton.setContentDescription(expanded ? "收起执行过程" : "展开执行过程");
        LinearLayout.LayoutParams processSize = new LinearLayout.LayoutParams(-1, dp(42)); processSize.topMargin = dp(10);
        item.addView(processButton, processSize);
        processButton.setOnClickListener(view -> {
            if (!expandedProcesses.add(turnId)) expandedProcesses.remove(turnId);
            renderHistory(true);
        });
        if (expanded) {
            TextView steps = text(process.isEmpty() ? "暂无可显示的执行过程。" : process, 13, MUTED);
            steps.setTextIsSelectable(true); steps.setLineSpacing(dp(3), 1);
            steps.setPadding(0, dp(10), 0, dp(8)); item.addView(steps);
        }
        String terminal = turn.optString("terminal");
        if (!terminal.isEmpty()) {
            TextView footer = text(terminal.replace("【任务结束】\n", ""), 12, MUTED);
            footer.setTextIsSelectable(true); footer.setPadding(0, dp(12), 0, 0); item.addView(footer);
        } else if ("running".equals(turn.optString("state"))) {
            TextView status = text(turn.optString("status", "任务进行中…"), 12, GREEN);
            status.setPadding(0, dp(12), 0, 0); item.addView(status);
        }
        historyPanel.addView(item, spaced());
    }

    private String date(long time) {
        return time <= 0 ? "历史记录" : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(time));
    }
    private String stateLabel(String value) {
        switch (value) {
            case "running": return "进行中";
            case "success": return "已完成";
            case "stopped": return "已停止";
            case "interrupted": return "已中断";
            case "error": return "未完成";
            default: return "历史任务";
        }
    }

    private void saveSettings() {
        preferences.edit().putString("base_url", baseUrl.getText().toString().trim())
                .putString("model", model.getText().toString().trim()).apply();
        try {
            String value = apiKey.getText().toString();
            if (value.isEmpty()) {
                preferences.edit().remove("key_ciphertext").remove("key_iv").apply();
            } else {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
                byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
                preferences.edit().putString("key_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                        .putString("key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
            }
        } catch (Exception exception) {
            preferences.edit().remove("key_ciphertext").remove("key_iv").apply();
            toast("系统暂时无法加密保存密钥，本次仍可使用，重开后需重新填写。");
        }
    }

    private String loadKey() {
        String encrypted = preferences.getString("key_ciphertext", "");
        String iv = preferences.getString("key_iv", "");
        if (encrypted.isEmpty() || iv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            preferences.edit().remove("key_ciphertext").remove("key_iv").apply();
            return "";
        }
    }

    private SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private String welcome() { return "先试一个简单任务：\n\n“打开系统设置”\n\n助手会读取当前界面，完成操作后再检查结果。"; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(prompt.getWindowToken(), 0);
        prompt.clearFocus();
    }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private TextView text(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    private LinearLayout card() {
        LinearLayout layout = vertical(); layout.setPadding(dp(16), dp(14), dp(16), dp(14));
        layout.setBackground(shape(Color.WHITE, Color.rgb(225, 233, 228), 18)); return layout;
    }
    private Button button(String label, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : GREEN); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackground(shape(primary ? GREEN : Color.rgb(235, 244, 239), Color.TRANSPARENT, 11));
        return button;
    }
    private EditText field(String label, String hint, int inputType, LinearLayout parent) {
        TextView name = text(label, 12, MUTED); name.setPadding(0, dp(7), 0, dp(3)); parent.addView(name);
        EditText value = new EditText(this); value.setTextSize(14); value.setTextColor(INK); value.setHint(hint);
        value.setHintTextColor(Color.rgb(145, 155, 149)); value.setInputType(inputType); value.setSingleLine(true);
        value.setPadding(dp(10), dp(10), dp(10), dp(10));
        value.setBackground(shape(Color.rgb(248, 250, 249), Color.rgb(222, 231, 225), 8));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(46)); size.bottomMargin = dp(7);
        parent.addView(value, size); return value;
    }
    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke); return shape;
    }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(44), 1);
        size.setMargins(dp(2), dp(4), dp(2), dp(6)); return size;
    }
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.bottomMargin = dp(12); return size;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
