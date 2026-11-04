package com.ds.phoneagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A small phone-tool executor. No sockets or exported command receiver. */
public final class PhoneAccessibilityService extends AccessibilityService {
    private static volatile PhoneAccessibilityService connected;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        OverlayService.onAccessibilityConnectionChanged();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent intent) {
        disconnectOverlay();
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        disconnectOverlay();
        super.onDestroy();
    }

    public static boolean isEnabled() { return connected != null; }

    /** Main-thread only: this service's WindowManager carries its accessibility token. */
    static WindowManager overlayWindowManager() {
        PhoneAccessibilityService service = connected;
        return service == null ? null : (WindowManager) service.getSystemService(WINDOW_SERVICE);
    }

    private void disconnectOverlay() {
        // A stale instance must not disconnect a newer service instance.
        if (connected != this) return;
        connected = null;
        OverlayService.onAccessibilityConnectionChanged();
    }

    /** Call from a worker thread; gesture results reflect the system callback. */
    public static JSONObject execute(String tool, JSONObject arguments) {
        PhoneAccessibilityService service = connected;
        if (service == null) return error("手机控制服务未开启，请在无障碍设置中启用 PhoneAgent。");
        if (Looper.myLooper() == Looper.getMainLooper()) return error("手机工具必须从工作线程调用。");
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        service.main.post(() -> {
            try {
                service.perform(tool, args, value -> {
                    result.set(value);
                    finished.countDown();
                });
            } catch (Exception exception) {
                result.set(error(exception.getClass().getSimpleName() + ": " + exception.getMessage()));
                finished.countDown();
            }
        });
        try {
            if (!finished.await(8, TimeUnit.SECONDS)) return error("等待手机操作结果超时，请重新读取界面确认状态。");
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return error("手机操作已中断。");
        }
    }

    private interface Completion { void complete(JSONObject result); }

    private void perform(String tool, JSONObject args, Completion completion) throws Exception {
        switch (tool) {
            case "read_screen":
                completion.complete(readScreen());
                return;
            case "home":
                completion.complete(actionResult(performGlobalAction(GLOBAL_ACTION_HOME), "已请求返回桌面，请读屏确认。"));
                return;
            case "back":
                completion.complete(actionResult(performGlobalAction(GLOBAL_ACTION_BACK), "已请求返回，请读屏确认。"));
                return;
            case "open_settings":
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                completion.complete(success("已请求打开系统设置，请读屏确认。"));
                return;
            case "open_app": {
                String packageName = args.optString("package_name", args.optString("package", ""));
                if (packageName.isEmpty()) { completion.complete(error("缺少 package_name。")); return; }
                Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch == null) { completion.complete(error("没有找到可启动的应用：" + packageName)); return; }
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                completion.complete(success("已请求打开应用，请读屏确认。"));
                return;
            }
            case "tap": {
                float x = (float) args.getDouble("x"), y = (float) args.getDouble("y");
                if (!insideScreen(x, y)) { completion.complete(error("点击坐标不在屏幕内。")); return; }
                Path path = new Path(); path.moveTo(x, y);
                gesture(path, 70, completion);
                return;
            }
            case "swipe": {
                float x1 = (float) args.getDouble("x1"), y1 = (float) args.getDouble("y1");
                float x2 = (float) args.getDouble("x2"), y2 = (float) args.getDouble("y2");
                if (!insideScreen(x1, y1) || !insideScreen(x2, y2)) {
                    completion.complete(error("滑动坐标不在屏幕内。")); return;
                }
                long duration = Math.max(100, Math.min(3000, args.optLong("duration_ms", 400)));
                Path path = new Path(); path.moveTo(x1, y1); path.lineTo(x2, y2);
                gesture(path, duration, completion);
                return;
            }
            case "type_text":
                completion.complete(typeText(args.getString("text")));
                return;
            default:
                completion.complete(error("未知手机工具：" + tool));
        }
    }

    private void gesture(Path path, long duration, Completion completion) {
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        boolean accepted = dispatchGesture(description, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) {
                completion.complete(success("系统已完成手势，请重新读取界面检查目标结果。"));
            }
            @Override public void onCancelled(GestureDescription gesture) {
                completion.complete(error("系统取消了手势，可能发生了手动触摸或其他操作。"));
            }
        }, main);
        if (!accepted) completion.complete(error("系统未接受手势。"));
    }

    private boolean insideScreen(float x, float y) {
        DisplayMetrics metrics = new DisplayMetrics();
        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        return !Float.isNaN(x) && !Float.isNaN(y) && x >= 0 && y >= 0 && x < metrics.widthPixels && y < metrics.heightPixels;
    }

    private JSONObject readScreen() throws Exception {
        JSONObject result = success("当前界面；坐标单位为屏幕像素。");
        DisplayMetrics metrics = new DisplayMetrics();
        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        result.put("screen_width", metrics.widthPixels);
        result.put("screen_height", metrics.heightPixels);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        result.put("locked", keyguard != null && keyguard.isKeyguardLocked());
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            result.put("ok", false);
            result.put("error", "当前没有可读取的应用界面；请解锁手机并打开目标界面。");
            return result;
        }
        try {
            result.put("package", string(root.getPackageName()));
            JSONArray nodes = new JSONArray();
            collect(root, nodes, 0, new int[]{0});
            result.put("nodes", nodes);
            result.put("truncated", nodes.length() >= 180);
            return result;
        } finally { root.recycle(); }
    }

    private void collect(AccessibilityNodeInfo node, JSONArray nodes, int depth, int[] visited) throws Exception {
        if (depth > 28 || nodes.length() >= 180 || visited[0]++ >= 800) return;
        if (node.isVisibleToUser()) {
            String text = node.isPassword() ? "[密码输入框]" : string(node.getText());
            String description = node.isPassword() ? "" : string(node.getContentDescription());
            if (!text.isEmpty() || !description.isEmpty() || node.isClickable() || node.isEditable() || node.isScrollable()) {
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    JSONObject value = new JSONObject();
                    value.put("text", truncate(text, 400));
                    value.put("description", truncate(description, 250));
                    value.put("class", string(node.getClassName()));
                    value.put("view_id", node.getViewIdResourceName());
                    value.put("bounds", new JSONArray(new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom}));
                    value.put("clickable", node.isClickable());
                    value.put("editable", node.isEditable());
                    value.put("scrollable", node.isScrollable());
                    value.put("enabled", node.isEnabled());
                    value.put("focused", node.isFocused());
                    nodes.put(value);
                }
            }
        }
        for (int index = 0; index < node.getChildCount() && nodes.length() < 180 && visited[0] < 800; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { collect(child, nodes, depth + 1, visited); }
            finally { child.recycle(); }
        }
    }

    private JSONObject typeText(String text) throws Exception {
        if (text.length() > 12000) return error("输入内容过长，请分成较短的文本。");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return error("没有可输入的应用界面。");
        AccessibilityNodeInfo focused = null;
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null || !focused.isEditable() || !focused.isEnabled()) {
                return error("请先点击需要输入的文本框，再调用 type_text。");
            }
            if (focused.isPassword()) return error("密码输入框需要你手动输入。");
            Bundle data = new Bundle();
            data.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return actionResult(focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, data), "已设置输入框文本，请读屏确认。");
        } finally {
            if (focused != null) focused.recycle();
            root.recycle();
        }
    }

    private static String string(CharSequence text) { return text == null ? "" : text.toString(); }
    private static String truncate(String text, int max) { return text.length() <= max ? text : text.substring(0, max) + "…"; }
    private static JSONObject actionResult(boolean ok, String message) { return ok ? success(message) : error("系统未接受操作。"); }
    private static JSONObject success(String message) {
        JSONObject result = new JSONObject();
        try { result.put("ok", true); result.put("message", message); } catch (Exception ignored) { }
        return result;
    }
    private static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try { result.put("ok", false); result.put("error", message); } catch (Exception ignored) { }
        return result;
    }
}
