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
    private TextView state, output, capabilities;
    private EditText prompt, baseUrl, model, apiKey;
    private Button send, stop, prepare, control, bubble;
    private LinearLayout modelFields;
    private ScrollView contentScroll;
    private MonthlyCatView statePet;

    private final AgentController.Listener listener = new AgentController.Listener() {
        @Override public void onStatus(String text) {
            runOnUiThread(() -> { state.setText(text); refreshState(); });
        }
        @Override public void onOutput(String text) {
            runOnUiThread(() -> {
                output.setText(text == null || text.isEmpty() ? welcome() : text);
            });
        }
        @Override public void onFinished(boolean success, String message) {
            runOnUiThread(() -> {
                state.setText(message);
                refreshState();
            });
        }
        @Override public void onVisualState(AgentController.VisualState value) {
            runOnUiThread(() -> statePet.setState(value));
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
        if (savedState != null) prompt.setText(savedState.getString("draft", ""));
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
        outState.putString("draft", prompt.getText().toString());
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
        setup.addView(statusRow);
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
        content.addView(setup, spaced());

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
        content.addView(modelCard, spaced());

        LinearLayout conversation = card();
        TextView conversationTitle = text("任务记录", 14, MUTED);
        conversationTitle.setTypeface(Typeface.DEFAULT_BOLD);
        conversation.addView(conversationTitle);
        output = text(welcome(), 16, INK);
        output.setTextIsSelectable(true);
        output.setLineSpacing(dp(4), 1);
        output.setPadding(0, dp(12), 0, dp(4));
        conversation.addView(output);
        content.addView(conversation, spaced());

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
        stop.setOnClickListener(view -> { controller.stop(); refreshState(); });
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
        saveSettings();
        hideKeyboard();
        controller.run(task, endpoint, chosenModel, secret);
        refreshState();
        if (controller.isRunning()) {
            prompt.setText("");
            moveTaskToBack(true);
        }
    }

    private void refreshState() {
        if (controller == null || prepare == null) return;
        boolean running = controller.isRunning();
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
        send.setEnabled(!running);
        stop.setEnabled(running);
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
