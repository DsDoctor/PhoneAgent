package com.ds.phoneagent;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;

/** Reveal first, delete only on an explicit button click. No deletion occurs during a gesture. */
public final class SwipeDeleteRow extends FrameLayout {
    // App-local custom action IDs, distinct from the platform's standard accessibility actions.
    public static final int ACTION_SHOW_DELETE = 0x7f0f0301;
    public static final int ACTION_HIDE_DELETE = 0x7f0f0302;
    private static final int NONE = 0, HORIZONTAL = 1, VERTICAL = 2, BLOCKED = 3;
    private final View content;
    private final Button deleteButton;
    private final Runnable openAction, deleteAction;
    private final int revealWidth, touchSlop;
    private Runnable onReveal;
    private boolean revealed, deleteEnabled = true, gestureActive, openedAtDown;
    private int direction = NONE, pointerId = -1;
    private float downX, downY, startOffset;

    public SwipeDeleteRow(Context context, View content, Runnable open, Runnable delete) {
        super(context);
        if (content == null) throw new IllegalArgumentException("Content view is required");
        if (content.getParent() != null) throw new IllegalArgumentException("Content view already has a parent");
        this.content = content;
        this.openAction = open;
        this.deleteAction = delete;
        revealWidth = dp(88);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(true);
        setClipToPadding(true);
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        deleteButton = new Button(context);
        deleteButton.setText("删除");
        deleteButton.setContentDescription("删除此任务");
        deleteButton.setTextSize(15);
        deleteButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        deleteButton.setTextColor(Color.WHITE);
        deleteButton.setAllCaps(false);
        deleteButton.setMinWidth(0);
        deleteButton.setMinimumWidth(0);
        deleteButton.setMinHeight(0);
        deleteButton.setMinimumHeight(0);
        // Material Button's default elevation must not paint it above the sliding content.
        deleteButton.setStateListAnimator(null);
        deleteButton.setElevation(0);
        deleteButton.setTranslationZ(0);
        deleteButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable red = new GradientDrawable();
        red.setColor(Color.rgb(187, 45, 51));
        red.setCornerRadius(dp(14));
        deleteButton.setBackground(red);
        addView(deleteButton, new FrameLayout.LayoutParams(revealWidth, LayoutParams.MATCH_PARENT, Gravity.RIGHT));

        // The wrapper owns opening the detail; a cancelled swipe cannot click the card underneath.
        content.setOnClickListener(null);
        content.setClickable(false);
        content.setFocusable(false);
        CharSequence description = content.getContentDescription();
        if (description != null && description.length() > 0) {
            setContentDescription(description);
            content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        addView(content, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setOnClickListener(view -> {
            if (revealed || content.getTranslationX() < 0) close();
            else if (openAction != null) openAction.run();
        });
        deleteButton.setOnClickListener(view -> {
            if (!deleteEnabled || !revealed || gestureActive) return;
            close();
            if (deleteAction != null) deleteAction.run();
        });
        updateDeleteAccess();

        setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (deleteEnabled && !revealed) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_SHOW_DELETE, "显示删除按钮"));
                if (revealed) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_HIDE_DELETE, "收起删除按钮"));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (action == ACTION_SHOW_DELETE) {
                    if (!deleteEnabled) return false;
                    endGesture();
                    settle(true, false);
                    return true;
                }
                if (action == ACTION_HIDE_DELETE) { close(); return true; }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
    }

    /** Fired once when a closed row becomes revealed; the owner may close every other row here. */
    public void setOnReveal(Runnable callback) { onReveal = callback; }
    public boolean isRevealed() { return revealed; }
    public View getContentView() { return content; }
    public Button getDeleteButton() { return deleteButton; }
    public int getRevealWidth() { return getWidth() > 0 ? Math.min(revealWidth, getWidth()) : revealWidth; }

    /** Closing is immediate, including disabling and hiding the deletion accessibility target. */
    public void close() {
        endGesture();
        settle(false, false);
    }

    public void setDeleteEnabled(boolean enabled) {
        deleteEnabled = enabled;
        if (!enabled) close();
        else updateDeleteAccess();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(event);
                // Only the exposed button receives its own DOWN. Tapping the visible card closes it.
                return revealed && (!deleteEnabled || event.getX() < getWidth() - getRevealWidth());
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                blockGesture();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() != 1) { blockGesture(); return true; }
                chooseDirection(event);
                // Intercept a vertical move only to cancel a child button's pending click.
                // We never disallow the outer ScrollView for the vertical direction.
                return direction != NONE;
            case MotionEvent.ACTION_UP:
                chooseDirection(event);
                releaseParent();
                if (direction != NONE || !inside(event.getX(), event.getY())) {
                    close();
                    return true;
                }
                // A real child-button click is now allowed; no swipe is in progress.
                gestureActive = false;
                return false;
            case MotionEvent.ACTION_CANCEL:
                close();
                return false;
            default:
                return direction == HORIZONTAL || direction == BLOCKED;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!gestureActive) beginGesture(event);
                setPressed(gestureActive && direction == NONE);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                blockGesture();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() != 1) { blockGesture(); return true; }
                chooseDirection(event);
                if (direction == HORIZONTAL) drag(event);
                return true;
            case MotionEvent.ACTION_UP: {
                if (!gestureActive) { setPressed(false); releaseParent(); return true; }
                chooseDirection(event);
                if (direction == HORIZONTAL) {
                    drag(event);
                    int index = event.findPointerIndex(pointerId);
                    float dx = index >= 0 ? event.getX(index) - downX : 0;
                    boolean open = deleteEnabled && -content.getTranslationX() >= getRevealWidth() * 0.45f;
                    if (openedAtDown && dx > touchSlop) open = false;
                    endGesture();
                    settle(open, true);
                } else if (direction == NONE && inside(event.getX(), event.getY())) {
                    endGesture();
                    performClick();
                } else close();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                close();
                return true;
            default:
                return gestureActive;
        }
    }

    private void beginGesture(MotionEvent event) {
        content.animate().cancel();
        // Complete any old settling animation before deciding which surface was touched.
        content.setTranslationX(revealed ? -getRevealWidth() : 0);
        pointerId = event.getPointerId(0);
        downX = event.getX(0); downY = event.getY(0);
        startOffset = content.getTranslationX();
        openedAtDown = revealed;
        direction = event.getPointerCount() == 1 ? NONE : BLOCKED;
        gestureActive = true;
        if (direction == BLOCKED) blockGesture();
    }

    private void chooseDirection(MotionEvent event) {
        if (!gestureActive || direction != NONE) return;
        int index = event.findPointerIndex(pointerId);
        if (index < 0) { blockGesture(); return; }
        float dx = Math.abs(event.getX(index) - downX), dy = Math.abs(event.getY(index) - downY);
        if (dx > touchSlop && dx > dy * 1.2f) {
            direction = HORIZONTAL;
            setPressed(false);
            deleteButton.setPressed(false);
            // A reveal drag never leaves a previously exposed button clickable under the finger.
            deleteButton.setEnabled(false);
            deleteButton.setClickable(false);
            deleteButton.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        } else if (dy > touchSlop) {
            direction = VERTICAL;
            setPressed(false);
            releaseParent();
        }
    }

    private void drag(MotionEvent event) {
        int index = event.findPointerIndex(pointerId);
        if (index < 0) { blockGesture(); return; }
        float offset = deleteEnabled ? Math.max(-getRevealWidth(), Math.min(0, startOffset + event.getX(index) - downX)) : 0;
        content.setTranslationX(offset);
        deleteButton.setVisibility(offset < 0 ? VISIBLE : INVISIBLE);
    }

    private void blockGesture() {
        endGesture();
        settle(false, false);
        direction = BLOCKED;
    }

    private void endGesture() {
        gestureActive = false;
        pointerId = -1;
        direction = NONE;
        setPressed(false);
        deleteButton.setPressed(false);
        releaseParent();
    }

    private void settle(boolean open, boolean animate) {
        open = open && deleteEnabled;
        boolean newlyRevealed = open && !revealed;
        revealed = open;
        content.animate().cancel();
        float target = open ? -getRevealWidth() : 0;
        // Delete is armed only after a completed reveal gesture or the accessibility action.
        updateDeleteAccess();
        if (animate && isAttachedToWindow()) content.animate().translationX(target).setDuration(140).start();
        else content.setTranslationX(target);
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        if (newlyRevealed && onReveal != null) onReveal.run();
    }

    private void updateDeleteAccess() {
        boolean available = revealed && deleteEnabled;
        deleteButton.setVisibility(available ? VISIBLE : INVISIBLE);
        deleteButton.setEnabled(available);
        deleteButton.setClickable(available);
        deleteButton.setFocusable(available);
        deleteButton.setImportantForAccessibility(available ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void releaseParent() {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }
    private boolean inside(float x, float y) { return x >= 0 && y >= 0 && x < getWidth() && y < getHeight(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldWidth > 0 && width != oldWidth) close();
    }
    @Override protected void onDetachedFromWindow() {
        close();
        super.onDetachedFromWindow();
    }
}
