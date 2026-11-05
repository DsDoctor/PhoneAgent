package com.ds.phoneagent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import java.io.InputStream;

/** Draws the original monthly-salary-cat atlas directly; frames keep one fixed canvas. */
public final class MonthlyCatView extends View {
    private static final int CELL_WIDTH = 192;
    private static final int CELL_HEIGHT = 208;
    private static Bitmap sharedAtlas;
    private static final int[] IDLE_MS = {280, 110, 110, 140, 140, 320};
    private static final int[] WAVE_MS = {140, 140, 140, 280};
    private static final int[] JUMP_MS = {140, 140, 140, 140, 280};
    private static final int[] FAILED_MS = {140, 140, 140, 140, 140, 140, 140, 240};
    private static final int[] WAIT_MS = {150, 150, 150, 150, 150, 260};
    private static final int[] RUN_MS = {120, 120, 120, 120, 120, 220};
    private static final int[] REVIEW_MS = {150, 150, 150, 150, 150, 280};
    private final Bitmap atlas;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final Rect visibleBounds = new Rect();
    private final RectF destination = new RectF();
    private final RectF pillBounds = new RectF();
    private AgentController.VisualState state = AgentController.VisualState.IDLE;
    private int frameIndex;
    private boolean attached;
    private boolean frameScheduled;
    private ViewTreeObserver scrollObserver;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = () -> {
        if (canAnimate()) scheduleFrame(); else stopFrames();
    };

    private final Runnable advanceFrame = new Runnable() {
        @Override public void run() {
            frameScheduled = false;
            if (!canAnimate()) return;
            frameIndex = (frameIndex + 1) % durations().length;
            invalidate();
            scheduleFrame();
        }
    };

    public MonthlyCatView(Context context) {
        super(context);
        atlas = loadAtlas(context);
        labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        setContentDescription("月薪喵 · 待命");
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setState(AgentController.VisualState value) {
        AgentController.VisualState next = value == null ? AgentController.VisualState.IDLE : value;
        if (state != next) {
            state = next;
            frameIndex = 0;
            stopFrames();
            invalidate();
        }
        setContentDescription("月薪喵 · " + label());
        scheduleFrame();
    }

    AgentController.VisualState getState() { return state; }
    int getFrameIndex() { return frameIndex; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        scrollObserver = getViewTreeObserver();
        scrollObserver.addOnScrollChangedListener(scrollListener);
        scheduleFrame();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        stopFrames();
        if (scrollObserver != null && scrollObserver.isAlive())
            scrollObserver.removeOnScrollChangedListener(scrollListener);
        scrollObserver = null;
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // View's constructor can invoke this before our field initializers.
        if (advanceFrame == null) return;
        if (canAnimate()) scheduleFrame(); else stopFrames();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (advanceFrame == null) return;
        if (canAnimate()) scheduleFrame(); else stopFrames();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        scheduleFrame();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float labelHeight = dp(17);
        float imageHeight = Math.max(0, getHeight() - labelHeight - dp(2));
        float scale = Math.min(getWidth() / (float) CELL_WIDTH, imageHeight / CELL_HEIGHT);
        float width = CELL_WIDTH * scale, height = CELL_HEIGHT * scale;
        float left = (getWidth() - width) / 2f;
        destination.set(left, imageHeight - height, left + width, imageHeight);
        if (atlas != null && scale > 0) {
            int row = row();
            source.set(frameIndex * CELL_WIDTH, row * CELL_HEIGHT,
                    (frameIndex + 1) * CELL_WIDTH, (row + 1) * CELL_HEIGHT);
            canvas.drawBitmap(atlas, source, destination, bitmapPaint);
        }

        String label = label();
        float pillWidth = Math.min(getWidth(), labelPaint.measureText(label) + dp(16));
        float top = getHeight() - labelHeight;
        pillBounds.set((getWidth() - pillWidth) / 2f, top, (getWidth() + pillWidth) / 2f, getHeight());
        pillPaint.setColor(state == AgentController.VisualState.ERROR ? Color.rgb(255, 227, 219) : Color.rgb(255, 246, 222));
        canvas.drawRoundRect(pillBounds, labelHeight / 2f, labelHeight / 2f, pillPaint);
        labelPaint.setColor(Color.rgb(92, 67, 53));
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float baseline = top + (labelHeight - metrics.bottom - metrics.top) / 2f;
        canvas.drawText(label, getWidth() / 2f, baseline, labelPaint);
        // Initial drawing starts after layout; the scroll listener also handles
        // reappearing views whose hardware display list is reused without onDraw.
        scheduleFrame();
    }

    private boolean canAnimate() {
        return attached && atlas != null && getWindowVisibility() == VISIBLE && isShown()
                && getWidth() > 0 && getHeight() > 0 && getGlobalVisibleRect(visibleBounds);
    }

    private void scheduleFrame() {
        if (advanceFrame != null && !frameScheduled && canAnimate()) {
            frameScheduled = true;
            postDelayed(advanceFrame, durations()[frameIndex]);
        }
    }

    private void stopFrames() {
        if (advanceFrame != null) removeCallbacks(advanceFrame);
        frameScheduled = false;
    }

    private int row() {
        switch (state) {
            case PREPARING: case ACTING: return 7;
            case THINKING: case PAUSED: case RESUMING: return 6;
            case SUCCESS: return 4;
            case NEEDS_INPUT: case STOPPING: case PAUSING: case WAITING_USER: return 8;
            case ERROR: return 5;
            case STOPPED: return 3;
            default: return 0;
        }
    }

    private int[] durations() {
        switch (row()) {
            case 3: return WAVE_MS;
            case 4: return JUMP_MS;
            case 5: return FAILED_MS;
            case 6: return WAIT_MS;
            case 7: return RUN_MS;
            case 8: return REVIEW_MS;
            default: return IDLE_MS;
        }
    }

    private String label() {
        switch (state) {
            case PREPARING: return "准备";
            case THINKING: return "思考";
            case ACTING: return "操作";
            case SUCCESS: return "完成";
            case NEEDS_INPUT: return "待配置";
            case ERROR: return "出错";
            case STOPPING: return "停止中";
            case STOPPED: return "已停止";
            case PAUSING: return "暂停中";
            case PAUSED: return "已暂停";
            case WAITING_USER: return "等你";
            case RESUMING: return "继续中";
            default: return "待命";
        }
    }

    private static synchronized Bitmap loadAtlas(Context context) {
        if (sharedAtlas != null) return sharedAtlas;
        try (InputStream input = context.getAssets().open("monthly_salary_cat.webp")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap loaded = BitmapFactory.decodeStream(input, null, options);
            if (loaded == null || loaded.getWidth() != 1536 || loaded.getHeight() != 2288) {
                Log.e("PhoneAgentPet", "Unexpected monthly cat sprite dimensions");
                if (loaded != null) loaded.recycle();
                return null;
            }
            sharedAtlas = loaded;
            return sharedAtlas;
        } catch (Exception error) {
            Log.e("PhoneAgentPet", "Could not load bundled monthly cat sprites");
            return null;
        }
    }

    private float dp(int value) { return value * getResources().getDisplayMetrics().density; }
}
