package com.ds.phoneagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Foreground notification and a draggable entry point; it never holds a permanent wake lock. */
public final class OverlayService extends Service {
    public static final String ACTION_SHOW = "com.ds.phoneagent.SHOW_BUBBLE";
    public static final String ACTION_HIDE = "com.ds.phoneagent.HIDE_BUBBLE";
    public static final String ACTION_STOP_TASK = "com.ds.phoneagent.STOP_TASK";
    private static final String CHANNEL = "phone_agent_control";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile OverlayService instance;
    private static boolean activityVisible;
    private WindowManager applicationWindowManager;
    private WindowManager windowManager;
    private MonthlyCatView bubble;
    private WindowManager.LayoutParams position;
    private AgentController.VisualState petState = AgentController.VisualState.IDLE;

    public static void setActivityVisible(boolean visible) {
        activityVisible = visible;
        OverlayService service = instance;
        if (service != null) service.updateBubble();
    }

    public static void start(Context context, String action) {
        context.startForegroundService(new Intent(context, OverlayService.class).setAction(action));
    }

    public static void setPetState(AgentController.VisualState value) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> setPetState(value));
            return;
        }
        OverlayService service = instance;
        if (service == null) return;
        AgentController.VisualState next = value == null ? AgentController.VisualState.IDLE : value;
        boolean changed = service.petState != next;
        service.petState = next;
        if (service.bubble != null) service.bubble.setState(service.petState);
        if (changed) service.updateNotification();
    }

    /** Accessibility lifecycle callbacks and Activity callbacks run on the main thread. */
    static void onAccessibilityConnectionChanged() {
        OverlayService service = instance;
        if (service == null) return;
        // Rebuild even when the same service object reconnects: its window token may change.
        service.removeBubble();
        service.updateBubble();
    }

    /** Package-private diagnostics for instrumentation; no externally callable endpoint. */
    static int currentWindowType() {
        OverlayService service = instance;
        return service == null || service.bubble == null || service.position == null ? 0 : service.position.type;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        petState = AgentController.get(this).getVisualState();
        applicationWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "手机助手", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("进入手机助手或立即停止当前任务");
        notifications.createNotificationChannel(channel);
        startForeground(101, notification());
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_EDIT_ACTIVE_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, OverlayService.class)
                .setAction(ACTION_STOP_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String message;
        switch (petState) {
            case PAUSING: message = "正在暂停 · 点此查看当前任务"; break;
            case PAUSED: message = "任务已暂停 · 点此补充或继续"; break;
            case WAITING_USER: message = "月薪喵在等你回答 · 点此查看问题"; break;
            case RESUMING: message = "正在继续任务 · 点此暂停并查看"; break;
            default: message = "点此进入助手 · 执行中进入会暂停任务";
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.monthly_cat_icon)
                .setContentTitle("PhoneAgent")
                .setContentText(message)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "结束任务", stop).build())
                .build();
    }

    private void updateNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(101, notification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_SHOW.equals(action)) preferences().edit().putBoolean("bubble_enabled", true).apply();
        else if (ACTION_HIDE.equals(action)) preferences().edit().putBoolean("bubble_enabled", false).apply();
        else if (ACTION_STOP_TASK.equals(action)) AgentController.get(this).stop();
        updateBubble();
        return START_NOT_STICKY;
    }

    private android.content.SharedPreferences preferences() { return getSharedPreferences("phoneagent", MODE_PRIVATE); }

    private void updateBubble() {
        WindowManager accessibilityWindowManager = PhoneAccessibilityService.overlayWindowManager();
        boolean accessibilityOverlay = accessibilityWindowManager != null;
        boolean shouldShow = !activityVisible && preferences().getBoolean("bubble_enabled", true)
                && (accessibilityOverlay || Settings.canDrawOverlays(this));
        if (!shouldShow) { removeBubble(); return; }
        WindowManager selectedManager = accessibilityOverlay ? accessibilityWindowManager : applicationWindowManager;
        int selectedType = accessibilityOverlay ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (bubble != null && windowManager == selectedManager && position.type == selectedType) return;
        removeBubble();
        addBubble(selectedManager, selectedType);
    }

    private void addBubble(WindowManager selectedManager, int selectedType) {
        windowManager = selectedManager;
        petState = AgentController.get(this).getVisualState();
        bubble = new MonthlyCatView(this);
        bubble.setState(petState);
        position = new WindowManager.LayoutParams(dp(72), dp(84), selectedType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.x = preferences().getInt("bubble_x", Math.max(0, getResources().getDisplayMetrics().widthPixels - dp(80)));
        position.y = preferences().getInt("bubble_y", dp(190));
        clampPosition();
        bubble.setOnClickListener(view -> AgentController.get(OverlayService.this).pauseForUser(() ->
                MAIN.post(() -> {
                    if (instance != OverlayService.this) return;
                    startActivity(new Intent(OverlayService.this, MainActivity.class)
                            .setAction(MainActivity.ACTION_EDIT_ACTIVE_TASK)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                })));
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (bubble != view || position == null || windowManager == null) return true;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY();
                    startX = position.x; startY = position.y; moved = false;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    int dx = Math.round(event.getRawX() - downX), dy = Math.round(event.getRawY() - downY);
                    moved |= Math.abs(dx) > dp(7) || Math.abs(dy) > dp(7);
                    if (moved) {
                        position.x = startX + dx; position.y = startY + dy;
                        clampPosition();
                        if (bubble != null) windowManager.updateViewLayout(bubble, position);
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (moved) preferences().edit().putInt("bubble_x", position.x).putInt("bubble_y", position.y).apply();
                    else view.performClick();
                    return true;
                }
                return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
        });
        try { windowManager.addView(bubble, position); }
        catch (RuntimeException denied) {
            removeBubble();
            // The accessibility service may disconnect while its window is being added.
            // A new LayoutParams avoids carrying its old token into the ordinary fallback.
            if (selectedType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && Settings.canDrawOverlays(this))
                addBubble(applicationWindowManager, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    private void clampPosition() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        position.x = Math.max(0, Math.min(position.x, metrics.widthPixels - position.width));
        position.y = Math.max(dp(24), Math.min(position.y, metrics.heightPixels - position.height - dp(24)));
    }

    private void removeBubble() {
        MonthlyCatView oldBubble = bubble;
        WindowManager oldManager = windowManager;
        bubble = null;
        position = null;
        windowManager = null;
        if (oldBubble != null && oldManager != null) {
            try { oldManager.removeViewImmediate(oldBubble); } catch (RuntimeException ignored) { }
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        removeBubble();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
