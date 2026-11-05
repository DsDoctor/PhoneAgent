package com.ds.phoneagent;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Minimal, native UI. Runtime internals stay behind the task and setup controls. */
public final class MainActivity extends Activity {
    public static final String ACTION_EDIT_ACTIVE_TASK = "com.ds.phoneagent.EDIT_ACTIVE_TASK";
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
    private static final Pattern LEGACY_TURN_LINE = Pattern.compile("(?m)^[ \\t]*执行轮数[：:][ \\t]*(\\d+)[ \\t]*$");
    private static final Pattern METRICS_FOOTER_LINES = Pattern.compile("(?m)^(?:累计执行轮次：\\d+（Claude Code，含中断）"
            + "|模型回复：\\d+ 次 · 服务重试：\\d+ 次|工具调用：\\d+ 次 · 手机操作请求：\\d+ 次"
            + "|统计不完整，以上为已记录的计数。)[ \\t]*$");
    private AgentController controller;
    private SharedPreferences preferences;
    private TextView state, capabilities, connectionStatus, controlHelp;
    private EditText prompt, baseUrl, model, apiKey;
    private Button send, stop, prepare, control, bubble, historyButton, newTaskButton, setupToggle;
    private SwipeDeleteRow revealedRow;
    private LinearLayout modelFields, setupPanel, interactionPanel, historyPanel, composer;
    private String selectedConversationId = "";
    private boolean showHistory = false;
    private boolean editingConnection;
    private boolean leavingForExecution;
    private boolean focusActiveInput;
    private boolean observingControlState;
    private String renderedHistory = "";
    private String renderedInteraction = "";
    private long historyRenderVersion;
    private int visibleTurnLimit = 10;
    private String unsavedOutput = "", unsavedConversationId = "";
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String, String> questionTextDrafts = new HashMap<>();
    private final Map<String, Set<String>> questionSelections = new HashMap<>();
    private final Set<String> expandedProcesses = new HashSet<>();
    private ScrollView contentScroll;
    private MonthlyCatView statePet;
    private final Runnable connectionListener = () -> {
        if (observingControlState && !isFinishing() && !isDestroyed()) refreshState();
    };
    private final ContentObserver controlSettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange, Uri uri) { connectionListener.run(); }
    };

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
        // Close the action gate before an ordinary launcher entry exposes any controls.
        if (controller.isRunning()) controller.pauseForUser();
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
            Bundle questionTexts = savedState.getBundle("question_texts");
            if (questionTexts != null) for (String key : questionTexts.keySet())
                questionTextDrafts.put(key, questionTexts.getString(key, ""));
            Bundle questionChoices = savedState.getBundle("question_choices");
            if (questionChoices != null) for (String key : questionChoices.keySet()) {
                ArrayList<String> values = questionChoices.getStringArrayList(key);
                if (values != null) questionSelections.put(key, new LinkedHashSet<>(values));
            }
        } else {
            selectedConversationId = controller.getActiveConversationId();
            showHistory = false;
        }
        if (!selectedConversationId.isEmpty() && controller.getConversation(selectedConversationId) == null)
            selectedConversationId = "";
        setupPanel.setVisibility(!controller.isReady() || baseUrl.length() == 0 || model.length() == 0 ? View.VISIBLE : View.GONE);
        setupToggle.setText(setupPanel.getVisibility() == View.VISIBLE ? "连接与设置  ∧" : "连接与设置  ›");
        if (getIntent() != null && ACTION_EDIT_ACTIVE_TASK.equals(getIntent().getAction())) {
            openActiveTask(true);
            getIntent().setAction(null);
        }
        renderHistory(true);
        controller.setListener(listener);
    }

    @Override protected void onResume() {
        super.onResume();
        observeControlState();
        controller.setTaskUiVisible(true);
        if (!leavingForExecution && controller.isRunning()) {
            controller.pauseForUser();
            if (controller.hasActiveTask()) openActiveTask(false);
        }
        OverlayService.setActivityVisible(true);
        OverlayService.start(this, "");
        refreshState();
        focusActiveTaskInput();
    }

    @Override protected void onPause() {
        stopObservingControlState();
        OverlayService.setActivityVisible(false);
        controller.setTaskUiVisible(false);
        leavingForExecution = false;
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (controller.isRunning()) controller.pauseForUser();
        if (intent != null && ACTION_EDIT_ACTIVE_TASK.equals(intent.getAction())) {
            openActiveTask(true);
            intent.setAction(null);
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        stashDraft();
        Bundle savedDrafts = new Bundle();
        for (Map.Entry<String, String> entry : drafts.entrySet()) savedDrafts.putString(entry.getKey(), entry.getValue());
        outState.putBundle("drafts", savedDrafts);
        Bundle questionTexts = new Bundle();
        for (Map.Entry<String, String> entry : questionTextDrafts.entrySet()) questionTexts.putString(entry.getKey(), entry.getValue());
        outState.putBundle("question_texts", questionTexts);
        Bundle questionChoices = new Bundle();
        for (Map.Entry<String, Set<String>> entry : questionSelections.entrySet())
            questionChoices.putStringArrayList(entry.getKey(), new ArrayList<>(entry.getValue()));
        outState.putBundle("question_choices", questionChoices);
        outState.putString("draft", prompt.getText().toString());
        outState.putString("conversation", selectedConversationId);
        outState.putBoolean("history", showHistory);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        stopObservingControlState();
        controller.removeListener(listener);
        super.onDestroy();
    }

    private void observeControlState() {
        if (observingControlState) return;
        observingControlState = true;
        PhoneAccessibilityService.addConnectionListener(connectionListener);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false, controlSettingsObserver);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, controlSettingsObserver);
    }

    private void stopObservingControlState() {
        if (!observingControlState) return;
        observingControlState = false;
        PhoneAccessibilityService.removeConnectionListener(connectionListener);
        getContentResolver().unregisterContentObserver(controlSettingsObserver);
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
        connectionStatus = text("● 检查状态", 13, MUTED);
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
        interactionPanel = vertical();
        content.addView(interactionPanel);
        historyPanel = vertical();
        content.addView(historyPanel);

        LinearLayout setup = card();
        TextView setupTitle = text("手机控制", 16, INK);
        setupTitle.setTypeface(Typeface.DEFAULT_BOLD);
        setup.addView(setupTitle);
        capabilities = text("", 12, MUTED);
        capabilities.setPadding(0, dp(7), 0, dp(8));
        setup.addView(capabilities);
        controlHelp = text("", 12, MUTED);
        controlHelp.setPadding(0, 0, 0, dp(8));
        setup.addView(controlHelp);
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
        send.setSingleLine(true);
        send.setEllipsize(TextUtils.TruncateAt.END);
        send.setContentDescription("开始执行");
        stop = plainButton("结束");
        stop.setSingleLine(true);
        stop.setMinWidth(dp(64));
        stop.setMinimumWidth(dp(64));
        actions.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams stopSize = new LinearLayout.LayoutParams(-2, -2);
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
        prompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { refreshState(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        stop.setOnClickListener(view -> {
            if (controller.hasActiveTask() || controller.isRunning()) controller.stop();
            else controller.resetToIdle();
            refreshState();
        });
    }

    private void submit() {
        boolean active = controller.hasActiveTask();
        if (active && !viewingActiveTask()) { openActiveTask(true); focusActiveTaskInput(); return; }
        if (active && !controller.canAcceptInput()) { toast("正在等待手机操作暂停，请稍候。"); return; }
        String task = prompt.getText().toString().trim();
        JSONObject pending = active ? controller.getPendingQuestion() : null;
        JSONObject answers = pending == null ? null : collectQuestionAnswers(pending, task);
        if (pending != null && answers == null) return;
        String endpoint = baseUrl.getText().toString().trim();
        String chosenModel = model.getText().toString().trim();
        String secret = apiKey.getText().toString().trim();
        if (!active && task.isEmpty()) { toast("先输入想让手机完成的任务"); return; }
        if (!controller.isReady()) { toast("请先准备环境"); return; }
        if (PhoneAccessibilityService.getControlState(this) != PhoneAccessibilityService.ControlState.CONNECTED) {
            setupPanel.setVisibility(View.VISIBLE);
            setupToggle.setText("连接与设置  ∧");
            contentScroll.post(() -> contentScroll.smoothScrollTo(0, 0));
            refreshState();
            toast("手机控制尚未连接，请查看“连接与设置”中的提示。");
            return;
        }
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
        if (!active && !selectedConversationId.isEmpty() && !showHistory) {
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
        leavingForExecution = true;
        if (pending != null) {
            // For a single unanswered question the main input is also a complete free-form answer.
            JSONArray questions = pending.optJSONObject("input") == null ? null : pending.optJSONObject("input").optJSONArray("questions");
            String extra = questions != null && questions.length() == 1
                    && task.equals(answers.optString(questions.optJSONObject(0).optString("question"))) ? "" : task;
            controller.answerQuestion(selectedConversationId, pending.optString("id"), answers, extra, endpoint, chosenModel, secret);
        } else if (active) {
            controller.continuePaused(selectedConversationId, task, endpoint, chosenModel, secret);
        } else {
            controller.runInConversation(showHistory ? "" : selectedConversationId, task, endpoint, chosenModel, secret);
        }
        refreshState();
        boolean accepted = active ? !controller.canAcceptInput() : controller.isRunning();
        if (accepted) {
            drafts.remove(submittedDraft);
            if (pending != null) clearQuestionDrafts(pending.optString("id"));
            selectedConversationId = controller.getActiveConversationId();
            showHistory = false;
            prompt.setText("");
            renderHistory(true);
            moveTaskToBack(true);
        } else leavingForExecution = false;
    }

    private void refreshState() {
        if (controller == null || prepare == null) return;
        boolean running = controller.isRunning();
        boolean active = controller.hasActiveTask();
        statePet.setState(controller.getVisualState());
        boolean ready = controller.isReady();
        PhoneAccessibilityService.ControlState controlState = PhoneAccessibilityService.getControlState(this);
        boolean accessible = controlState == PhoneAccessibilityService.ControlState.CONNECTED;
        boolean overlay = Settings.canDrawOverlays(this) || accessible;
        String controlLabel, controlStatus, controlHint;
        switch (controlState) {
            case SYSTEM_DISABLED:
                controlLabel = "系统无障碍已关闭";
                controlStatus = "控制已关闭";
                controlHint = "系统无障碍总开关已关闭。请在系统设置中开启 PhoneAgent 手机控制；已保存的模型配置无需重填。";
                break;
            case NOT_AUTHORIZED:
                controlLabel = "手机控制未授权";
                controlStatus = "控制未授权";
                controlHint = "首次使用需要在系统设置中开启 PhoneAgent 手机控制。正常授权后会保留。";
                break;
            case DISCONNECTED:
                controlLabel = "手机控制已授权，服务未连接";
                controlStatus = "控制未连接";
                controlHint = "系统尚未连接手机控制服务。若一直未连接，请在系统设置中关闭后重新开启 PhoneAgent 手机控制。";
                break;
            default:
                controlLabel = "手机控制已连接";
                controlStatus = "已就绪";
                controlHint = "手机控制已连接，正常退出应用后无需重复授权；系统关闭权限后，需要重新开启。";
        }
        capabilities.setText((ready ? "环境已就绪" : "环境待准备") + "  ·  "
                + controlLabel + "  ·  "
                + (overlay ? "悬浮球可用" : "悬浮球待授权"));
        controlHelp.setText(controlHint);
        boolean configured = !baseUrl.getText().toString().trim().isEmpty()
                && !model.getText().toString().trim().isEmpty() && !apiKey.getText().toString().trim().isEmpty();
        boolean available = ready && accessible && configured;
        connectionStatus.setText("● " + (!ready ? "环境待准备" : !configured ? "模型待配置" : controlStatus));
        connectionStatus.setTextColor(available ? SUCCESS : MUTED);
        connectionStatus.setContentDescription((ready ? "运行环境已就绪" : "运行环境待准备") + "，"
                + (configured ? "模型配置完整" : "模型配置不完整") + "，" + controlLabel);
        prepare.setText(ready ? "环境已就绪" : "准备环境");
        prepare.setEnabled(!active && !running && !ready);
        control.setText(accessible ? "手机控制设置"
                : controlState == PhoneAccessibilityService.ControlState.DISCONNECTED ? "检查手机控制设置" : "开启手机控制");
        bubble.setText(!overlay ? "开启悬浮球权限" : preferences.getBoolean("bubble_enabled", true) ? "隐藏悬浮球" : "显示悬浮球");
        boolean continuing = !showHistory && !selectedConversationId.isEmpty();
        JSONObject selected = continuing ? controller.getConversation(selectedConversationId) : null;
        boolean canContinue = selected != null && selected.optBoolean("can_continue");
        String action = continuing ? "继续对话" : "开始执行";
        String hint = continuing ? "接下来，还想让我做什么？" : "想让月薪喵做什么？";
        boolean canSend = !running && (!continuing || canContinue);
        if (active) {
            if (!viewingActiveTask()) {
                action = "回到待处理任务";
                canSend = true;
            } else {
                JSONObject pending = controller.getPendingQuestion();
                action = pending != null ? "回答并继续" : prompt.getText().toString().trim().isEmpty() ? "继续执行" : "发送并继续";
                hint = pending != null ? "补充说明，或直接输入回答" : "补充、纠正任务，或直接继续";
                canSend = controller.canAcceptInput();
                if (!canSend) {
                    AgentController.VisualState phase = controller.getVisualState();
                    action = phase == AgentController.VisualState.RESUMING ? "继续中…"
                            : phase == AgentController.VisualState.RETRYING ? "重试中…"
                            : phase == AgentController.VisualState.STOPPING ? "结束中…"
                            : phase == AgentController.VisualState.PAUSING ? "暂停中…" : "执行中…";
                    hint = phase == AgentController.VisualState.PAUSING ? "等待当前操作暂停后即可输入" : "当前任务正在处理中";
                }
            }
        }
        send.setText(action);
        send.setContentDescription(action);
        send.setEnabled(!editingConnection && canSend);
        prompt.setHint(hint);
        stop.setText(active || running ? "结束" : "重置");
        stop.setContentDescription(active || running ? "结束任务" : "重置为待命");
        stop.setEnabled(active || running || controller.canResetToIdle());
        historyButton.setSelected(showHistory);
        styleButton(historyButton, showHistory);
        styleButton(newTaskButton, !showHistory);
        newTaskButton.setSelected(!showHistory && selectedConversationId.isEmpty());
        renderInteractionCard();
    }

    private boolean viewingActiveTask() {
        return !showHistory && !selectedConversationId.isEmpty()
                && selectedConversationId.equals(controller.getActiveConversationId());
    }

    private void openActiveTask(boolean focusInput) {
        String id = controller.getActiveConversationId();
        if (id.isEmpty() || controller.getConversation(id) == null) return;
        if (showHistory || !id.equals(selectedConversationId)) navigate(false, id);
        else { renderHistory(false); refreshState(); }
        setupPanel.setVisibility(View.GONE);
        setupToggle.setText("连接与设置  ›");
        contentScroll.scrollTo(0, 0);
        focusActiveInput |= focusInput;
    }

    private void focusActiveTaskInput() {
        if (!focusActiveInput) return;
        focusActiveInput = false;
        // Keep the choices visible on entry; the user can open the keyboard for a free answer.
        if (controller.getPendingQuestion() != null) return;
        prompt.post(() -> {
            if (isFinishing() || isDestroyed() || leavingForExecution || !viewingActiveTask()) return;
            prompt.requestFocus();
            prompt.setSelection(prompt.length());
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(prompt, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /** Separate from the transcript so incoming process updates never replace a question being edited. */
    private void renderInteractionCard() {
        if (interactionPanel == null) return;
        boolean active = controller.hasActiveTask();
        boolean selected = active && viewingActiveTask();
        JSONObject pending = selected ? controller.getPendingQuestion() : null;
        AgentController.VisualState phase = controller.getVisualState();
        boolean accepting = controller.canAcceptInput();
        String fingerprint = active + ":" + selected + ":" + controller.getActiveConversationId() + ":" + phase
                + ":" + accepting + ":" + (pending == null ? "" : pending.toString());
        if (fingerprint.equals(renderedInteraction)) return;
        boolean firstQuestion = pending != null && !renderedInteraction.contains(pending.toString());
        renderedInteraction = fingerprint;
        interactionPanel.removeAllViews();
        if (!active) return;
        LinearLayout card = card();
        if (!selected) {
            TextView title = text("还有一个任务待处理", 16, INK);
            title.setTypeface(Typeface.DEFAULT_BOLD); card.addView(title);
            TextView help = text("补充内容和问题回答需要回到当前任务提交。这里的草稿会保留。", 13, MUTED);
            help.setPadding(0, dp(8), 0, dp(10)); card.addView(help);
            Button back = button("回到待处理任务", false);
            back.setOnClickListener(view -> { openActiveTask(true); focusActiveTaskInput(); });
            card.addView(back, new LinearLayout.LayoutParams(-1, -2));
        } else if (pending != null) {
            TextView title = text("等你回答", 18, INK);
            title.setTypeface(Typeface.DEFAULT_BOLD); card.addView(title);
            TextView help = text("选择选项或填写自己的回答，再点下方“回答并继续”。", 13, MUTED);
            help.setPadding(0, dp(7), 0, dp(4)); card.addView(help);
            JSONObject input = pending.optJSONObject("input");
            JSONArray questions = input == null ? null : input.optJSONArray("questions");
            if (questions != null) for (int i = 0; i < questions.length(); i++) {
                JSONObject question = questions.optJSONObject(i);
                if (question != null) addQuestion(card, pending.optString("id"), question, i, accepting);
            }
        } else {
            String title = phase == AgentController.VisualState.PAUSED ? "任务已暂停"
                    : phase == AgentController.VisualState.RESUMING ? "正在继续任务"
                    : phase == AgentController.VisualState.RETRYING ? "模型服务异常，正在重试"
                    : phase == AgentController.VisualState.STOPPING ? "正在结束任务" : "正在暂停任务";
            String message = accepting ? "在下方补充或纠正任务，点“发送并继续”。不需要修改时，可以直接继续执行。"
                    : phase == AgentController.VisualState.RESUMING ? "正在准备继续执行。"
                    : phase == AgentController.VisualState.RETRYING ? "正在等待模型服务恢复，任务尚未结束。"
                    : phase == AgentController.VisualState.STOPPING ? "正在结束当前任务，请稍候。" : "正在等待已发出的手机操作结束，随后就能补充任务。";
            TextView heading = text(title, 18, INK);
            heading.setTypeface(Typeface.DEFAULT_BOLD); card.addView(heading);
            TextView help = text(message, 14, MUTED);
            help.setPadding(0, dp(8), 0, 0); card.addView(help);
        }
        interactionPanel.addView(card, spaced());
        if (firstQuestion) contentScroll.post(() -> {
            if (viewingActiveTask() && controller.getPendingQuestion() != null) contentScroll.smoothScrollTo(0, 0);
        });
    }

    private void addQuestion(LinearLayout card, String requestId, JSONObject question, int index, boolean accepting) {
        String questionText = question.optString("question");
        String key = questionKey(requestId, questionText);
        Set<String> choices = questionSelections.get(key);
        if (choices == null) { choices = new LinkedHashSet<>(); questionSelections.put(key, choices); }
        final Set<String> selectedChoices = choices;
        boolean multiple = question.optBoolean("multiSelect");
        String header = question.optString("header");
        TextView label = text((index + 1) + ". " + (header.isEmpty() ? "你的回答" : header)
                + (multiple ? " · 可多选" : " · 单选"), 12, MUTED);
        label.setPadding(0, dp(16), 0, dp(5)); card.addView(label);
        TextView title = text(questionText, 16, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextIsSelectable(true);
        title.setPadding(0, 0, 0, dp(7)); card.addView(title);
        LinearLayout options;
        if (multiple) options = vertical();
        else { RadioGroup group = new RadioGroup(this); group.setOrientation(LinearLayout.VERTICAL); options = group; }
        card.addView(options, new LinearLayout.LayoutParams(-1, -2));
        JSONArray values = question.optJSONArray("options");
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject option = values.optJSONObject(i);
            if (option == null) continue;
            String value = option.optString("label");
            String description = option.optString("description");
            CompoundButton choice = multiple ? new CheckBox(this) : new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(value + (description.isEmpty() ? "" : "\n" + description));
            choice.setTextSize(14); choice.setTextColor(INK);
            choice.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{ACCENT, MUTED}));
            choice.setPadding(dp(2), dp(10), dp(4), dp(10));
            choice.setMinHeight(dp(48)); choice.setEnabled(accepting);
            choice.setChecked(selectedChoices.contains(value));
            options.addView(choice, new LinearLayout.LayoutParams(-1, -2));
            choice.setOnCheckedChangeListener((button, checked) -> {
                if (multiple) { if (checked) selectedChoices.add(value); else selectedChoices.remove(value); }
                else if (checked) { selectedChoices.clear(); selectedChoices.add(value); }
            });
        }
        Button clear = plainButton("清空选项");
        clear.setTextSize(12); clear.setEnabled(accepting);
        clear.setContentDescription("清空第 " + (index + 1) + " 个问题的选项");
        clear.setOnClickListener(view -> {
            if (options instanceof RadioGroup) ((RadioGroup) options).clearCheck();
            else for (int i = 0; i < options.getChildCount(); i++)
                ((CompoundButton) options.getChildAt(i)).setChecked(false);
            selectedChoices.clear();
        });
        LinearLayout clearRow = horizontal(); clearRow.setGravity(Gravity.END);
        clearRow.addView(clear, new LinearLayout.LayoutParams(-2, -2)); card.addView(clearRow);
        EditText custom = new EditText(this);
        custom.setTextSize(15); custom.setTextColor(INK); custom.setHintTextColor(MUTED);
        custom.setHint("其他回答（可选）");
        custom.setContentDescription(questionText + "，自由回答");
        custom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        custom.setMinHeight(dp(48)); custom.setMaxLines(4);
        custom.setPadding(dp(11), dp(10), dp(11), dp(10));
        custom.setBackground(shape(BACKGROUND, BORDER, 10));
        custom.setText(questionTextDrafts.containsKey(key) ? questionTextDrafts.get(key) : "");
        custom.setEnabled(accepting);
        LinearLayout.LayoutParams inputSize = new LinearLayout.LayoutParams(-1, -2);
        inputSize.topMargin = dp(6); card.addView(custom, inputSize);
        custom.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (value.length() == 0) questionTextDrafts.remove(key); else questionTextDrafts.put(key, value.toString());
            }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    private String questionKey(String requestId, String question) { return requestId + "\n" + question; }

    private JSONObject collectQuestionAnswers(JSONObject pending, String freeText) {
        JSONObject input = pending.optJSONObject("input");
        JSONArray questions = input == null ? null : input.optJSONArray("questions");
        if (questions == null || questions.length() == 0) { toast("问题暂时无法显示，请结束任务后重试。"); return null; }
        JSONObject answers = new JSONObject();
        try {
            for (int i = 0; i < questions.length(); i++) {
                JSONObject question = questions.optJSONObject(i);
                if (question == null || question.optString("question").isEmpty()) {
                    toast("问题内容不完整，请结束任务后重试。"); return null;
                }
                String text = question.optString("question");
                String key = questionKey(pending.optString("id"), text);
                Set<String> selected = questionSelections.get(key);
                ArrayList<String> parts = new ArrayList<>();
                JSONArray options = question.optJSONArray("options");
                if (selected != null && options != null) for (int j = 0; j < options.length(); j++) {
                    JSONObject option = options.optJSONObject(j);
                    if (option != null && selected.contains(option.optString("label"))) parts.add(option.optString("label"));
                }
                String custom = questionTextDrafts.containsKey(key) ? questionTextDrafts.get(key).trim() : "";
                if (!custom.isEmpty()) parts.add(custom);
                String answer = TextUtils.join(", ", parts);
                if (answer.isEmpty() && questions.length() == 1) answer = freeText;
                if (answer.isEmpty()) { toast("请先回答第 " + (i + 1) + " 个问题，可选择选项或自由输入。"); return null; }
                answers.put(text, answer);
            }
        } catch (org.json.JSONException invalid) { toast("暂时无法整理回答，请重试。"); return null; }
        return answers;
    }

    private void clearQuestionDrafts(String requestId) {
        String prefix = requestId + "\n";
        questionTextDrafts.keySet().removeIf(key -> key.startsWith(prefix));
        questionSelections.keySet().removeIf(key -> key.startsWith(prefix));
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
                row.setDeleteEnabled(!(controller.hasActiveTask() && id.equals(controller.getActiveConversationId())));
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
            if (!detail.optBoolean("can_continue") && !(controller.hasActiveTask() && viewingActiveTask())
                    && !"running".equals(detail.optString("status"))) {
                TextView note = text(detail.optString("continue_unavailable_reason", "这段对话暂时无法继续，可以新建任务。"), 12, MUTED);
                note.setPadding(0, dp(2), 0, dp(14)); historyPanel.addView(note);
            }
        }
        if (!force) contentScroll.post(() -> {
            if (rendering != historyRenderVersion || isFinishing() || isDestroyed()) return;
            if (atEnd && !showHistory && !(controller.hasActiveTask() && viewingActiveTask())) contentScroll.fullScroll(View.FOCUS_DOWN);
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
                case "pausing": pending = "正在暂停手机操作，任务尚未结束。"; break;
                case "paused": pending = "任务已暂停，可以补充或纠正后继续。"; break;
                case "waiting_user": pending = "月薪喵在等你回答，任务尚未结束。"; break;
                case "resuming": pending = "正在继续任务，最终结果会显示在这里。"; break;
                case "stopped": pending = "这轮已停止，尚未生成最终结果。"; break;
                case "interrupted": pending = "这轮被中断，尚未收到最终结果。"; break;
                case "success": pending = "任务已结束，但没有返回结果正文。"; break;
                default: pending = "这轮没有生成最终结果，可查看执行过程与结束原因。";
            }
            TextView message = text(pending, 15, MUTED);
            message.setLineSpacing(dp(3), 1);
            message.setPadding(dp(2), dp(2), dp(2), dp(6)); response.addView(message);
        }
        String answer = turn.optString("interaction_answer");
        if (!answer.isEmpty()) {
            TextView reply = text("你的补充 / 回答\n" + answer, 14, INK);
            reply.setTextIsSelectable(true); reply.setLineSpacing(dp(3), 1);
            reply.setPadding(dp(10), dp(10), dp(10), dp(10));
            reply.setBackground(shape(USER_BUBBLE, Color.TRANSPARENT, 12));
            LinearLayout.LayoutParams replySize = new LinearLayout.LayoutParams(-1, -2);
            replySize.topMargin = dp(12); response.addView(reply, replySize);
        }
        String terminal = turn.optString("terminal");
        if (!terminal.isEmpty()) {
            // The old footer counted only the final CLI query. Present it with the metrics
            // below rather than leaving an ambiguous total in this otherwise factual footer.
            String presented = LEGACY_TURN_LINE.matcher(terminal.replace("【任务结束】\n", "")).replaceAll("").trim();
            if (turn.optJSONObject("execution_metrics") != null)
                presented = METRICS_FOOTER_LINES.matcher(presented).replaceAll("").trim();
            if (!presented.isEmpty()) {
                TextView footer = text(presented, 12, MUTED);
                footer.setTextIsSelectable(true); footer.setLineSpacing(dp(2), 1);
                footer.setPadding(dp(2), dp(12), dp(2), dp(2)); response.addView(footer);
            }
        } else if ("running".equals(turnState) || "pausing".equals(turnState) || "paused".equals(turnState)
                || "waiting_user".equals(turnState) || "resuming".equals(turnState)) {
            TextView status = text(turn.optString("status", "任务进行中…"), 12, ACCENT);
            status.setPadding(dp(2), dp(10), dp(2), dp(2)); response.addView(status);
        }
        TextView metrics = text(executionStats(turn), 12, MUTED);
        metrics.setTextIsSelectable(true); metrics.setLineSpacing(dp(2), 1);
        metrics.setPadding(dp(2), dp(10), dp(2), dp(2));
        response.addView(metrics, new LinearLayout.LayoutParams(-1, -2));
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

    /** Display-only migration: never treat a legacy final-query count as a task total. */
    private String executionStats(JSONObject turn) {
        JSONObject ledger = turn.optJSONObject("execution_metrics");
        if (ledger == null || ledger.optInt("version") != 1) {
            Integer count = LastTaskStore.optionalInt(turn, "num_turns");
            String lastSegment = count == null ? "" : String.valueOf(count);
            if (lastSegment.isEmpty()) {
                Matcher old = LEGACY_TURN_LINE.matcher(turn.optString("terminal"));
                if (old.find()) lastSegment = old.group(1);
            }
            return lastSegment.isEmpty() ? "旧版统计不完整"
                    : "旧版末段轮次：" + lastSegment + "（未累计） · 旧版统计不完整";
        }
        ExecutionMetrics metrics = ExecutionMetrics.fromRecord(turn);
        String note = "含暂停/中断轮次";
        if (metrics.isIncomplete()) note += " · 统计不完整";
        if (metrics.hasUnsettledQuery()) note += " · 尚未结算";
        return "累计执行轮次 " + metrics.engineTurns() + "（Claude Code）\n"
                + "回复 " + metrics.modelReplyCount() + " · 重试 " + metrics.apiRetryCount()
                + " · 手机请求 " + metrics.phoneActionCount() + " · 工具调用 " + metrics.toolCallCount()
                + "\n" + note;
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
        return "running".equals(state) || "pausing".equals(state) || "paused".equals(state)
                || "waiting_user".equals(state) || "resuming".equals(state) ? ACCENT : MUTED;
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
            case "pausing": return "暂停中";
            case "paused": return "已暂停";
            case "waiting_user": return "等你回答";
            case "resuming": return "继续中";
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
