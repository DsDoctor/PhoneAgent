package com.ds.phoneagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Foreground notification and a draggable entry point; it never holds a permanent wake lock. */
public final class OverlayService extends Service {
    public static final String ACTION_SHOW = "com.ds.phoneagent.SHOW_BUBBLE";
    public static final String ACTION_HIDE = "com.ds.phoneagent.HIDE_BUBBLE";
    public static final String ACTION_STOP_TASK = "com.ds.phoneagent.STOP_TASK";
    private static final String CHANNEL = "phone_agent_control";
    private static volatile OverlayService instance;
    private static boolean activityVisible;
    private WindowManager windowManager;
    private TextView bubble;
    private WindowManager.LayoutParams position;

    public static void setActivityVisible(boolean visible) {
        activityVisible = visible;
        OverlayService service = instance;
        if (service != null) service.updateBubble();
    }

    public static void start(Context context, String action) {
        context.startForegroundService(new Intent(context, OverlayService.class).setAction(action));
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "手机助手", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("进入手机助手或立即停止当前任务");
        notifications.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, OverlayService.class)
                .setAction(ACTION_STOP_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_phone_agent)
                .setContentTitle("PhoneAgent")
                .setContentText("点此进入助手 · 可随时停止当前任务")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "停止任务", stop).build())
                .build();
        startForeground(101, notification);
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
        boolean shouldShow = !activityVisible && Settings.canDrawOverlays(this)
                && preferences().getBoolean("bubble_enabled", true);
        if (!shouldShow) { removeBubble(); return; }
        if (bubble != null) return;
        bubble = new TextView(this);
        bubble.setText("P");
        bubble.setContentDescription("打开 PhoneAgent");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(22);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(23, 106, 85));
        background.setShape(GradientDrawable.OVAL);
        background.setStroke(dp(2), Color.rgb(213, 236, 225));
        bubble.setBackground(background);
        bubble.setElevation(dp(6));
        position = new WindowManager.LayoutParams(dp(50), dp(50), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.x = preferences().getInt("bubble_x", Math.max(0, getResources().getDisplayMetrics().widthPixels - dp(58)));
        position.y = preferences().getInt("bubble_y", dp(190));
        clampPosition();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
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
                    else {
                        view.performClick();
                        startActivity(new Intent(OverlayService.this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    }
                    return true;
                }
                return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
        });
        try { windowManager.addView(bubble, position); }
        catch (RuntimeException denied) { bubble = null; }
    }

    private void clampPosition() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        position.x = Math.max(0, Math.min(position.x, metrics.widthPixels - dp(50)));
        position.y = Math.max(dp(24), Math.min(position.y, metrics.heightPixels - dp(74)));
    }

    private void removeBubble() {
        if (bubble == null) return;
        try { windowManager.removeView(bubble); } catch (RuntimeException ignored) { }
        bubble = null;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        removeBubble();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
