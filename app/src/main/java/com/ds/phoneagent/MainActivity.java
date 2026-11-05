package com.ds.phoneagent;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
    private static final int BACKGROUND = Color.rgb(251, 246, 238);
    private static final int INK = Color.rgb(70, 51, 41);
    private static final int MUTED = Color.rgb(121, 99, 83);
    private static final int ACCENT = Color.rgb(174, 92, 67);
    private static final int USER_BUBBLE = Color.rgb(243, 221, 207);
    private static final int ASSISTANT = Color.rgb(255, 252, 247);
    private static final int BORDER = Color.rgb(234, 223, 213);
    private static final int SOFT = Color.rgb(243, 232, 221);
    private static final int SUCCESS = Color.rgb(88, 112, 40);
    private static final int ERROR = Color.rgb(151, 67, 45);
    private static final String KEY_ALIAS = "phoneagent.api.key.v1";
    private AgentController controller;
    private SharedPreferences preferences;
    private TextView state, capabilities, connectionStatus;
    private EditText prompt, baseUrl, model, apiKey;
    private Button send, stop, prepare, control, bubble, historyButton, newTaskButton, setupToggle;
    private SwipeDeleteRow revealedRow;
    private LinearLayout modelFields, setupPanel, historyPanel, composer;
    private String selectedConversationId = "";
    private boolean showHistory = false;
    private boolean editingConnection;
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
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
            showHistory = savedState.getBoolean("history", false);
            Bundle savedDrafts = savedState.getBundle("drafts");
            if (savedDrafts != null) for (String key : savedDrafts.keySet())
                drafts.put(key, savedDrafts.getString(key, ""));
        } else {
            selectedConversationId = controller.getActiveConversationId();
            showHistory = false;
        }
        if (!selectedConversationId.isEmpty() && controller.getConversation(selectedConversationId) == null)
            selectedConversationId = "";
        setupPanel.setVisibility(!controller.isReady() || baseUrl.length() == 0 || model.length() == 0 ? View.VISIBLE : View.GONE);
        setupToggle.setText(setupPanel.getVisibility() == View.VISIBLE ? "连接与设置  ∧" : "连接与设置  ›");
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
        page.setFocusableInTouchMode(true);
        page.setBackgroundColor(BACKGROUND);
        page.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = vertical();
        TextView title = text("月薪喵", 28, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        brand.addView(title);
        TextView subtitle = text("PhoneAgent · 让手机替你动手", 12, MUTED);
        subtitle.setPadding(0, dp(2), 0, dp(4));
        brand.addView(subtitle);
        state = text("先准备环境，再开启手机控制", 12, MUTED);
        state.setMaxLines(2);
        state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        state.setLineSpacing(dp(2), 1);
        brand.addView(state);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        statePet = new MonthlyCatView(this);
        statePet.setState(controller.getVisualState());
        LinearLayout.LayoutParams petSize = new LinearLayout.LayoutParams(dp(72), dp(84));
        petSize.leftMargin = dp(10);
        header.addView(statePet, petSize);
        LinearLayout.LayoutParams headerSize = new LinearLayout.LayoutParams(-1, -2);
        headerSize.bottomMargin = dp(10);
        page.addView(header, headerSize);

        LinearLayout navigation = horizontal();
        historyButton = button("任务记录", false);
        historyButton.setContentDescription("打开最近任务");
        newTaskButton = button("＋ 新任务", true);
        newTaskButton.setContentDescription("新任务");
        navigation.addView(newTaskButton, weighted());
        navigation.addView(historyButton, weighted());
        page.addView(navigation);
        historyButton.setOnClickListener(view -> openHistory());
        newTaskButton.setOnClickListener(view -> {
            hideKeyboard(); navigate(false, "");
            contentScroll.scrollTo(0, 0);
        });

        LinearLayout connectionRow = horizontal();
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);
        connectionStatus = text("● 待配置", 13, MUTED);
        connectionStatus.setPadding(dp(4), dp(8), dp(10), dp(8));
        connectionRow.addView(connectionStatus, new LinearLayout.LayoutParams(-2, -2));
        View separator = new View(this);
        separator.setBackgroundColor(BORDER);
        connectionRow.addView(separator, new LinearLayout.LayoutParams(dp(1), dp(20)));
        setupToggle = plainButton("连接与设置  ›");
        setupToggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setupToggle.setContentDescription("连接与设置");
        connectionRow.addView(setupToggle, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(connectionRow, new LinearLayout.LayoutParams(-1, -2));
        page.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(false);
        contentScroll.setClipToPadding(false);
        contentScroll.setPadding(0, dp(12), 0, dp(8));
        page.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = vertical();
        contentScroll.addView(content);
        setupPanel = vertical();
        content.addView(setupPanel);
        setupToggle.setOnClickListener(view -> {
            hideKeyboard();
            boolean opening = setupPanel.getVisibility() != View.VISIBLE;
            setupPanel.setVisibility(opening ? View.VISIBLE : View.GONE);
            setupToggle.setText(opening ? "连接与设置  ∧" : "连接与设置  ›");
            if (opening) contentScroll.post(() -> contentScroll.smoothScrollTo(0, 0));
        });
        historyPanel = vertical();
        content.addView(historyPanel);

        LinearLayout setup = card();
        TextView setupTitle = text("手机控制", 16, INK);
        setupTitle.setTypeface(Typeface.DEFAULT_BOLD);
        setup.addView(setupTitle);
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
        setup.addView(bubble, new LinearLayout.LayoutParams(-1, -2));
        setupPanel.addView(setup, spaced());
        prepare.setOnClickListener(view -> {
            hideKeyboard(); controller.installRuntime(); refreshState();
        });
        control.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
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
        Button settings = plainButton("连接模型  ›");
        settings.setTypeface(Typeface.DEFAULT_BOLD);
        settings.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        modelCard.addView(settings, new LinearLayout.LayoutParams(-1, -2));
        modelFields = vertical();
        modelCard.addView(modelFields);
        TextView help = text("填写兼容 Anthropic API 的服务地址与模型。密钥加密保存在本机。", 12, MUTED);
        help.setPadding(0, dp(4), 0, dp(10));
        modelFields.addView(help);
        baseUrl = field("API 地址", "https://api.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, modelFields);
        model = field("模型", "输入服务商的模型名称", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, modelFields);
        apiKey = field("API Key", "输入密钥", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, modelFields);
        apiKey.setSaveEnabled(false);
        Button save = button("保存连接配置", true);
        modelFields.addView(save, new LinearLayout.LayoutParams(-1, -2));
        save.setOnClickListener(view -> {
            saveSettings(); hideKeyboard(); modelFields.setVisibility(View.GONE); refreshState();
        });
        settings.setOnClickListener(view -> {
            boolean opening = modelFields.getVisibility() != View.VISIBLE;
            if (!opening) hideKeyboard();
            modelFields.setVisibility(opening ? View.VISIBLE : View.GONE);
        });
        setupPanel.addView(modelCard, spaced());

        composer = vertical();
        composer.setPadding(0, dp(10), 0, 0);
        composer.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        prompt = new EditText(this);
        prompt.setHint("想让月薪喵做什么？");
        prompt.setContentDescription("任务输入");
        prompt.setTextSize(16);
        prompt.setTextColor(INK);
        prompt.setHintTextColor(MUTED);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setMinLines(1);
        prompt.setMaxLines(3);
        prompt.setMinHeight(dp(52));
        prompt.setGravity(Gravity.TOP | Gravity.START);
        prompt.setPadding(dp(14), dp(12), dp(14), dp(12));
        prompt.setBackground(shape(ASSISTANT, BORDER, 16));
        LinearLayout.LayoutParams promptSize = new LinearLayout.LayoutParams(-1, -2);
        promptSize.topMargin = dp(10);
        composer.addView(prompt, promptSize);
        LinearLayout actions = horizontal();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(7), 0, 0);
        send = button("开始执行", true);
        send.setContentDescription("开始执行");
        stop = plainButton("停止");
        actions.addView(send, new LinearLayout.LayoutParams(0, -2, 3));
        LinearLayout.LayoutParams stopSize = new LinearLayout.LayoutParams(0, -2, 1);
        stopSize.leftMargin = dp(8);
        actions.addView(stop, stopSize);
        composer.addView(actions);
        page.addView(composer);
        setContentView(page);
        page.requestFocus();
        final boolean[] keyboardShown = {false};
        final Rect visibleWindow = new Rect();
        page.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            boolean keyboard;
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets insets = page.getRootWindowInsets();
                keyboard = insets != null && insets.isVisible(WindowInsets.Type.ime());
            } else {
                page.getWindowVisibleDisplayFrame(visibleWindow);
                keyboard = page.getRootView().getHeight() - visibleWindow.bottom > dp(120);
            }
            if (keyboard != keyboardShown[0]) {
                keyboardShown[0] = keyboard;
                subtitle.setVisibility(keyboard ? View.GONE : View.VISIBLE);
                state.setVisibility(keyboard ? View.GONE : View.VISIBLE);
                LinearLayout.LayoutParams pet = (LinearLayout.LayoutParams) statePet.getLayoutParams();
                pet.width = dp(keyboard ? 48 : 72); pet.height = dp(keyboard ? 58 : 84);
                statePet.setLayoutParams(pet);
            }
            int headerVisibility = page.getHeight() > 0 && page.getHeight() < dp(400) ? View.GONE : View.VISIBLE;
            if (header.getVisibility() != headerVisibility) header.setVisibility(headerVisibility);
            // A focused settings field retains focus after Back closes the IME, so track the IME too.
            boolean editingSettings = baseUrl.hasFocus() || model.hasFocus() || apiKey.hasFocus();
            boolean editing = keyboard && editingSettings;
            if (editing != editingConnection) {
                editingConnection = editing;
                prompt.setVisibility(editing ? View.GONE : View.VISIBLE);
                // Keep Stop available while an active task's connection settings are being edited.
                refreshState();
            }
        });
        for (EditText field : new EditText[]{baseUrl, model, apiKey})
            field.setOnFocusChangeListener((view, focused) -> page.post(page::requestLayout));
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
            setupToggle.setText("连接与设置  ∧");
            modelFields.setVisibility(View.VISIBLE);
            contentScroll.post(() -> contentScroll.smoothScrollTo(0, 0));
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
        boolean configured = baseUrl.length() > 0 && model.length() > 0 && apiKey.length() > 0;
        connectionStatus.setText(ready && accessible && configured ? "● 已就绪" : "● 待配置");
        connectionStatus.setTextColor(ready && accessible && configured ? SUCCESS : MUTED);
        connectionStatus.setContentDescription(ready && accessible && configured
                ? "运行环境、手机控制与模型配置已就绪" : "手机控制或模型连接待配置");
        prepare.setText(ready ? "环境已就绪" : "准备环境");
        prepare.setEnabled(!running && !ready);
        control.setText(accessible ? "手机控制设置" : "开启手机控制");
        bubble.setText(!overlay ? "开启悬浮球权限" : preferences.getBoolean("bubble_enabled", true) ? "隐藏悬浮球" : "显示悬浮球");
        boolean continuing = !showHistory && !selectedConversationId.isEmpty();
        JSONObject selected = continuing ? controller.getConversation(selectedConversationId) : null;
        boolean canContinue = selected != null && selected.optBoolean("can_continue");
        send.setText(continuing ? "继续对话" : "开始执行");
        send.setContentDescription(continuing ? "继续对话" : "开始执行");
        send.setEnabled(!editingConnection && !running && (!continuing || canContinue));
        prompt.setHint(continuing ? "接下来，还想让我做什么？" : "想让月薪喵做什么？");
        stop.setText(running ? "停止" : "重置");
        stop.setContentDescription(running ? "停止任务" : "重置为待命");
        stop.setEnabled(running || controller.canResetToIdle());
        historyButton.setSelected(showHistory);
        styleButton(historyButton, showHistory);
        styleButton(newTaskButton, !showHistory);
        newTaskButton.setSelected(!showHistory && selectedConversationId.isEmpty());
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
        if (revealedRow != null) { revealedRow.close(); revealedRow = null; }
        historyPanel.removeAllViews();
        if (showHistory) {
            TextView label = text("最近任务 · 左滑可删除", 13, MUTED);
            label.setPadding(0, dp(2), 0, dp(12)); historyPanel.addView(label);
            if (conversations.length() == 0) {
                LinearLayout empty = card(); empty.addView(text("暂无任务记录，点“新任务”开始。", 16, INK));
                historyPanel.addView(empty, spaced());
            }
            for (int i = 0; i < conversations.length(); i++) {
                JSONObject entry = conversations.optJSONObject(i);
                if (entry == null) continue;
                final String id = entry.optString("id");
                LinearLayout item = card();
                TextView title = text(entry.optString("title", "手机任务"), 17, INK);
                title.setTypeface(Typeface.DEFAULT_BOLD); title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END); item.addView(title);
                TextView meta = text(date(entry.optLong("updated_at")) + "  ·  " + stateLabel(entry.optString("status"))
                        + "  ·  " + entry.optInt("turn_count", 1) + " 轮对话", 12, MUTED);
                meta.setPadding(0, dp(7), 0, dp(8)); item.addView(meta);
                String preview = entry.optString("last_result").replaceAll("[\\r\\n]+", " ");
                if (!preview.isEmpty()) {
                    TextView excerpt = text(preview, 14, MUTED); excerpt.setMaxLines(2);
                    excerpt.setEllipsize(TextUtils.TruncateAt.END); item.addView(excerpt);
                }
                TextView open = text("查看对话  ›", 13, ACCENT); open.setPadding(0, dp(9), 0, 0); item.addView(open);
                item.setContentDescription(entry.optString("title") + "，" + stateLabel(entry.optString("status")) + "，查看任务");
                SwipeDeleteRow row = new SwipeDeleteRow(this, item, () -> openConversation(id), () -> deleteConversation(id));
                row.setTag(id);
                row.setDeleteEnabled(!(controller.isRunning() && id.equals(controller.getActiveConversationId())));
                row.setOnReveal(() -> {
                    if (revealedRow != null && revealedRow != row) revealedRow.close();
                    revealedRow = row;
                });
                historyPanel.addView(row, spaced());
            }
        } else if (detail == null) {
            LinearLayout empty = card();
            TextView heading = text(selectedConversationId.isEmpty() ? "你好，我是月薪喵。" : "这段对话已不在近期记录中", 18, INK);
            heading.setTypeface(Typeface.DEFAULT_BOLD); empty.addView(heading);
            TextView help = text(selectedConversationId.isEmpty() ? "在下方输入任务，完成后可以继续追问。需要配置时，打开上方“连接与设置”。" : "点“新任务”开始一段新对话。", 14, MUTED);
            help.setPadding(0, dp(10), 0, 0); empty.addView(help); historyPanel.addView(empty, spaced());
        } else {
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
                TextView label = text("本轮记录暂未保存 · 请先复制", 14, ERROR);
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

    private void openHistory() {
        hideKeyboard();
        setupPanel.setVisibility(View.GONE);
        setupToggle.setText("连接与设置  ›");
        navigate(true, "");
        contentScroll.scrollTo(0, 0);
    }

    private void deleteConversation(String id) {
        try {
            if (!controller.deleteConversation(id)) { renderHistory(true); return; }
            drafts.remove(id);
            if (id.equals(unsavedConversationId)) { unsavedConversationId = ""; unsavedOutput = ""; }
            if (id.equals(selectedConversationId)) {
                selectedConversationId = ""; showHistory = true;
                prompt.setText(drafts.containsKey("new-task") ? drafts.get("new-task") : "");
            }
            renderHistory(true); refreshState();
            toast("已删除任务记录");
        } catch (java.io.IOException unavailable) {
            toast(unavailable.getMessage() == null ? "暂时无法删除，请重试。" : unavailable.getMessage());
        }
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
        String turnState = turn.optString("state");
        TextView timestamp = text(messageDate(turn.optLong("started_at")), 12, MUTED);
        timestamp.setGravity(Gravity.CENTER);
        timestamp.setPadding(0, dp(8), 0, dp(14));
        historyPanel.addView(timestamp, new LinearLayout.LayoutParams(-1, -2));

        TextView request = text(turn.optString("prompt", "旧版任务"), 17, INK);
        request.setTextIsSelectable(true);
        request.setLineSpacing(dp(3), 1);
        request.setPadding(dp(15), dp(12), dp(15), dp(12));
        request.setBackground(shape(USER_BUBBLE, Color.TRANSPARENT, 20));
        addAligned(request, 0.86f, true, 0, 14);

        LinearLayout sender = horizontal();
        sender.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.monthly_cat_icon);
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams avatarSize = new LinearLayout.LayoutParams(dp(34), dp(38));
        avatarSize.rightMargin = dp(7);
        sender.addView(avatar, avatarSize);
        TextView name = text("月薪喵", 16, INK);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        sender.addView(name, new LinearLayout.LayoutParams(-2, -2));
        TextView badge = text(("success".equals(turnState) ? "✓ " : "") + stateLabel(turnState), 12, stateColor(turnState));
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        badge.setBackground(shape(stateFill(turnState), Color.TRANSPARENT, 12));
        LinearLayout.LayoutParams badgeSize = new LinearLayout.LayoutParams(-2, -2);
        badgeSize.leftMargin = dp(9);
        sender.addView(badge, badgeSize);
        addAligned(sender, 0.94f, false, 0, 7);

        LinearLayout response = vertical();
        response.setPadding(dp(12), dp(12), dp(12), dp(10));
        response.setBackground(shape(ASSISTANT, BORDER, 18));
        String process = turn.optString("process");
        final String turnId = turn.optString("id", selectedConversationId + "-" + number);
        boolean expanded = expandedProcesses.contains(turnId);
        Button processButton = button("执行过程 · " + stateLabel(turnState) + (expanded ? "  ∧" : "  ›"), false);
        processButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        processButton.setTextSize(14);
        processButton.setContentDescription(expanded ? "收起执行过程" : "展开执行过程");
        response.addView(processButton, new LinearLayout.LayoutParams(-1, -2));
        processButton.setOnClickListener(view -> {
            if (!expandedProcesses.add(turnId)) expandedProcesses.remove(turnId);
            renderHistory(true);
        });
        if (expanded) {
            TextView steps = text(process.isEmpty() ? "暂无可显示的执行过程。" : process, 13, MUTED);
            steps.setTextIsSelectable(true); steps.setLineSpacing(dp(3), 1);
            steps.setPadding(dp(2), dp(10), dp(2), dp(4)); response.addView(steps);
        }
        LinearLayout.LayoutParams lineSize = new LinearLayout.LayoutParams(-1, dp(1));
        lineSize.topMargin = dp(10); lineSize.bottomMargin = dp(10);
        response.addView(divider(), lineSize);
        String result = turn.optString("result");
        if (!result.isEmpty()) {
            MarkdownView rich = new MarkdownView(this);
            rich.setMarkdown(result);
            response.addView(rich, new LinearLayout.LayoutParams(-1, -2));
        } else {
            String pending;
            switch (turnState) {
                case "running": pending = "月薪喵正在处理，最终结果会显示在这里。"; break;
                case "stopped": pending = "这轮已停止，尚未生成最终结果。"; break;
                case "interrupted": pending = "这轮被中断，尚未收到最终结果。"; break;
                case "success": pending = "任务已结束，但没有返回结果正文。"; break;
                default: pending = "这轮没有生成最终结果，可查看执行过程与结束原因。";
            }
            TextView message = text(pending, 15, MUTED);
            message.setLineSpacing(dp(3), 1);
            message.setPadding(dp(2), dp(2), dp(2), dp(6)); response.addView(message);
        }
        String terminal = turn.optString("terminal");
        if (!terminal.isEmpty()) {
            TextView footer = text(terminal.replace("【任务结束】\n", ""), 12, MUTED);
            footer.setTextIsSelectable(true); footer.setLineSpacing(dp(2), 1);
            footer.setPadding(dp(2), dp(12), dp(2), dp(2)); response.addView(footer);
        } else if ("running".equals(turnState)) {
            TextView status = text(turn.optString("status", "任务进行中…"), 12, ACCENT);
            status.setPadding(dp(2), dp(10), dp(2), dp(2)); response.addView(status);
        }
        if (!result.isEmpty()) {
            LinearLayout copyRow = horizontal();
            copyRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            Button copy = plainButton("复制结果");
            copy.setTextSize(13);
            copy.setContentDescription("复制结果");
            copy.setOnClickListener(view -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("任务结果", result)); toast("已复制 Markdown 结果");
            });
            copyRow.addView(copy, new LinearLayout.LayoutParams(-2, -2));
            response.addView(copyRow, new LinearLayout.LayoutParams(-1, -2));
        }
        addAligned(response, 0.94f, false, 0, 22);
    }

    /** Weight-based bubbles stay proportional after rotation and with larger system text. */
    private void addAligned(View bubble, float fraction, boolean right, int top, int bottom) {
        LinearLayout row = horizontal();
        View gutter = new View(this);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, 0, 1 - fraction);
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(0, -2, fraction);
        if (right) { row.addView(gutter, gap); row.addView(bubble, body); }
        else { row.addView(bubble, body); row.addView(gutter, gap); }
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(top); size.bottomMargin = dp(bottom);
        historyPanel.addView(row, size);
    }

    private int stateColor(String state) {
        if ("success".equals(state)) return SUCCESS;
        if ("error".equals(state) || "interrupted".equals(state)) return ERROR;
        return "running".equals(state) ? ACCENT : MUTED;
    }
    private int stateFill(String state) {
        if ("success".equals(state)) return Color.rgb(237, 239, 216);
        if ("error".equals(state) || "interrupted".equals(state)) return Color.rgb(249, 230, 218);
        return SOFT;
    }
    private String messageDate(long time) {
        if (time <= 0) return "历史对话";
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA);
        String date = day.format(new Date(time)).equals(day.format(new Date())) ? "今天" : new SimpleDateFormat("MM-dd", Locale.CHINA).format(new Date(time));
        return date + " · " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(time));
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
        View focused = getCurrentFocus();
        if (focused != null) focused.clearFocus();
        prompt.clearFocus();
        if (composer != null) composer.setVisibility(View.VISIBLE);
    }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private TextView text(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    private LinearLayout card() {
        LinearLayout layout = vertical(); layout.setPadding(dp(14), dp(13), dp(14), dp(13));
        layout.setBackground(shape(ASSISTANT, BORDER, 17)); return layout;
    }
    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label); button.setAllCaps(false); button.setSingleLine(false); button.setTextSize(16);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setStateListAnimator(null); button.setElevation(0); button.setTranslationZ(0);
        styleButton(button, primary);
        return button;
    }
    private void styleButton(Button button, boolean primary) {
        int disabledFill = primary ? Color.rgb(219, 202, 188) : Color.rgb(239, 230, 220);
        StateListDrawable backgrounds = new StateListDrawable();
        backgrounds.addState(new int[]{-android.R.attr.state_enabled}, shape(disabledFill, Color.TRANSPARENT, 13));
        backgrounds.addState(new int[]{android.R.attr.state_pressed}, shape(primary ? Color.rgb(145, 71, 49) : Color.rgb(230, 211, 195), Color.TRANSPARENT, 13));
        backgrounds.addState(new int[]{}, shape(primary ? ACCENT : SOFT, Color.TRANSPARENT, 13));
        button.setBackground(backgrounds);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(132, 113, 98), primary ? Color.WHITE : INK}));
    }
    private Button plainButton(String label) {
        Button button = button(label, false);
        StateListDrawable backgrounds = new StateListDrawable();
        backgrounds.addState(new int[]{android.R.attr.state_pressed}, shape(SOFT, Color.TRANSPARENT, 12));
        backgrounds.addState(new int[]{}, shape(Color.TRANSPARENT, Color.TRANSPARENT, 12));
        button.setBackground(backgrounds);
        return button;
    }
    private View divider() {
        View line = new View(this); line.setBackgroundColor(BORDER); return line;
    }
    private EditText field(String label, String hint, int inputType, LinearLayout parent) {
        TextView name = text(label, 12, MUTED); name.setPadding(0, dp(7), 0, dp(3)); parent.addView(name);
        EditText value = new EditText(this); value.setTextSize(14); value.setTextColor(INK); value.setHint(hint);
        value.setHintTextColor(MUTED); value.setInputType(inputType); value.setSingleLine(true);
        value.setContentDescription(label); value.setMinHeight(dp(48));
        value.setPadding(dp(10), dp(10), dp(10), dp(10));
        value.setBackground(shape(BACKGROUND, BORDER, 10));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.bottomMargin = dp(7);
        parent.addView(value, size); return value;
    }
    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke); return shape;
    }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMargins(dp(3), dp(3), dp(3), dp(5)); return size;
    }
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.bottomMargin = dp(12); return size;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
