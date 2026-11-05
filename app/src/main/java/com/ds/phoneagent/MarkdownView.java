package com.ds.phoneagent;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Native, selectable Markdown. Never loads HTML, scripts, images or remote resources. */
public final class MarkdownView extends LinearLayout {
    private static final int INK = Color.rgb(70, 51, 41);
    private static final int MUTED = Color.rgb(121, 99, 83);
    private static final int ACCENT = Color.rgb(174, 92, 67);
    private static final int BORDER = Color.rgb(225, 208, 195);
    private static final int CODE = Color.rgb(245, 237, 229);
    private static final int TABLE_HEADER = Color.rgb(244, 232, 221);
    // A table can otherwise multiply a short Markdown result into thousands of Views.
    private static final int MAX_TABLE_CELLS = 192;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong revision = new AtomicLong();
    private String rendered = null;
    private boolean renderedTruncated;

    public MarkdownView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setClipToPadding(false);
    }

    /** Call on any thread; queued older updates never replace a newer result. */
    public void setMarkdown(String markdown) {
        final long version = revision.incrementAndGet();
        final boolean truncated = markdown != null && markdown.length() > MarkdownParser.MAX_INPUT;
        final String bounded = MarkdownParser.bounded(markdown);
        Runnable update = () -> {
            if (version != revision.get()) return;
            if (bounded.equals(rendered) && truncated == renderedTruncated) return;
            rendered = bounded;
            renderedTruncated = truncated;
            render(MarkdownParser.parse(bounded), truncated);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) update.run(); else main.post(update);
    }

    private void render(MarkdownParser.Document document, boolean truncated) {
        removeAllViews();
        int tableCells = 0;
        for (MarkdownParser.Block block : document.blocks) {
            switch (block.kind) {
                case "heading": {
                    TextView heading = text(block.text, true);
                    heading.setTextSize(block.level == 1 ? 23 : block.level == 2 ? 20 : block.level == 3 ? 18 : 16);
                    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    // API 26 supports heading semantics through the accessibility delegate.
                    heading.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                        @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setCollectionItemInfo(android.view.accessibility.AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, 0, 1, true));
                        }
                    });
                    add(heading, getChildCount() == 0 ? 0 : 10, 6); break;
                }
                case "list": {
                    LinearLayout row = new LinearLayout(getContext());
                    row.setOrientation(HORIZONTAL);
                    row.setGravity(Gravity.TOP);
                    row.setPadding(dp(block.indent * 14), 0, 0, 0);
                    TextView marker = text(block.marker, false);
                    marker.setTextColor(ACCENT);
                    marker.setMinWidth(dp(22));
                    marker.setPadding(0, 0, dp(7), 0);
                    marker.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                    row.addView(marker, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                    TextView value = text(block.text, true);
                    row.addView(value, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                    add(row, 0, 5); break;
                }
                case "quote": {
                    LinearLayout quote = new LinearLayout(getContext());
                    quote.setOrientation(HORIZONTAL);
                    View bar = new View(getContext()); bar.setBackgroundColor(BORDER);
                    quote.addView(bar, new LinearLayout.LayoutParams(dp(3), LayoutParams.MATCH_PARENT));
                    TextView value = text(block.text, true);
                    value.setTextColor(MUTED);
                    value.setPadding(dp(11), dp(7), dp(9), dp(7));
                    value.setBackgroundColor(CODE);
                    quote.addView(value, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                    add(quote, 2, 9); break;
                }
                case "code": add(code(block.text), 2, 10); break;
                case "rule": {
                    View rule = new View(getContext()); rule.setBackgroundColor(BORDER);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
                    params.topMargin = dp(8); params.bottomMargin = dp(13);
                    addView(rule, params); break;
                }
                case "table": {
                    int count = block.rows.size() * block.alignments.size();
                    if (tableCells + count <= MAX_TABLE_CELLS) {
                        tableCells += count; add(table(block), 2, 10);
                    } else add(code(block.text), 2, 10);
                    break;
                }
                default: add(text(block.text, !block.kind.equals("plain")), 0, 9);
            }
        }
        if (truncated || document.truncated) {
            TextView notice = text("内容较长，此处显示前 48,000 字符。", false);
            notice.setTextSize(12); notice.setTextColor(MUTED); add(notice, 4, 4);
        }
    }

    private TextView text(String source, boolean markdown) {
        TextView value = new TextView(getContext());
        value.setTextSize(15);
        value.setTextColor(INK);
        value.setLinkTextColor(ACCENT);
        value.setIncludeFontPadding(false);
        value.setLineSpacing(dp(3), 1.08f);
        value.setTextIsSelectable(true);
        value.setHorizontallyScrolling(false);
        if (!markdown) { value.setText(source); return value; }
        MarkdownParser.Inline parsed = MarkdownParser.inline(source);
        SpannableStringBuilder result = new SpannableStringBuilder(parsed.text);
        boolean links = false;
        for (MarkdownParser.Span span : parsed.spans) {
            Object style = null;
            switch (span.kind) {
                case "bold": style = new StyleSpan(Typeface.BOLD); break;
                case "italic": style = new StyleSpan(Typeface.ITALIC); break;
                case "strike": style = new StrikethroughSpan(); break;
                case "code":
                    style = new TypefaceSpan("monospace");
                    result.setSpan(new BackgroundColorSpan(CODE), span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case "link":
                    String url = safeWebUrl(span.value);
                    if (url != null) { style = new WebLinkSpan(url); links = true; }
                    break;
            }
            if (style != null) result.setSpan(style, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        value.setText(result);
        if (links) value.setMovementMethod(LinkMovementMethod.getInstance());
        return value;
    }

    private HorizontalScrollView code(String source) {
        HorizontalScrollView scroll = horizontalScroll();
        scroll.setBackground(background(CODE, false));
        TextView value = text(source, false);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextSize(13);
        value.setLineSpacing(dp(2), 1.06f);
        value.setHorizontallyScrolling(true);
        value.setPadding(dp(12), dp(10), dp(12), dp(10));
        scroll.addView(value, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private HorizontalScrollView table(MarkdownParser.Block block) {
        HorizontalScrollView scroll = horizontalScroll();
        TableLayout table = new TableLayout(getContext());
        table.setStretchAllColumns(false);
        table.setShrinkAllColumns(false);
        for (int rowIndex = 0; rowIndex < block.rows.size(); rowIndex++) {
            TableRow row = new TableRow(getContext());
            List<String> cells = block.rows.get(rowIndex);
            for (int column = 0; column < block.alignments.size(); column++) {
                TextView cell = text(column < cells.size() ? cells.get(column) : "", true);
                cell.setTextSize(14);
                cell.setMinWidth(dp(86)); cell.setMaxWidth(dp(240));
                cell.setPadding(dp(10), dp(9), dp(10), dp(9));
                cell.setBackgroundColor(rowIndex == 0 ? TABLE_HEADER : Color.TRANSPARENT);
                if (rowIndex == 0) cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                int align = block.alignments.get(column);
                cell.setGravity(Gravity.TOP | (align == 0 ? Gravity.CENTER_HORIZONTAL : align == 1 ? Gravity.RIGHT : Gravity.LEFT));
                row.addView(cell, new TableRow.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
            }
            table.addView(row, new TableLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            if (rowIndex > 0) {
                View divider = new View(getContext());
                divider.setBackgroundColor(BORDER);
                table.addView(divider, new TableLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
        scroll.addView(table, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private HorizontalScrollView horizontalScroll() {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        return scroll;
    }
    private GradientDrawable background(int color, boolean border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        if (border) shape.setStroke(dp(1), BORDER); else shape.setCornerRadius(dp(7));
        return shape;
    }
    private void add(View child, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top); params.bottomMargin = dp(bottom); addView(child, params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Only explicit web destinations may become clickable spans; checked again on tap. */
    static String safeWebUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\\') return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return null;
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535 || uri.getPort() < -1) return null;
            // Android intent matching treats scheme names as case-sensitive.
            return scheme.toLowerCase(Locale.ROOT) + uri.toASCIIString().substring(scheme.length());
        } catch (URISyntaxException invalid) { return null; }
    }

    private static final class WebLinkSpan extends URLSpan {
        WebLinkSpan(String url) { super(url); }
        @Override public void onClick(View widget) {
            String url = safeWebUrl(getURL());
            if (url == null) return;
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { widget.getContext().startActivity(browser); }
            catch (ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(widget.getContext(), "暂时无法打开链接", Toast.LENGTH_SHORT).show();
            }
        }
        @Override public void updateDrawState(TextPaint ds) { super.updateDrawState(ds); ds.setColor(ACCENT); ds.setUnderlineText(true); }
    }
}
