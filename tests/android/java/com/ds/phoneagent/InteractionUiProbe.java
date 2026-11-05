package com.ds.phoneagent;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread UI assertions against an isolated waiting Controller; never submits a task. */
final class InteractionUiProbe {
    static void assertExecutionStats(MainActivity activity, JSONObject actualRecord) throws Exception {
        check(Looper.myLooper() == Looper.getMainLooper(), "Run execution-stats UI assertions on the main thread");
        Method stats = method("executionStats", JSONObject.class);
        String modern = (String) stats.invoke(activity, actualRecord);
        check(modern.contains("累计执行轮次 " + actualRecord.getInt("num_turns") + "（Claude Code）")
                        && modern.contains("重试 " + actualRecord.getInt("api_retry_count"))
                        && modern.contains("含暂停/中断轮次") && !modern.contains("未累计"),
                "Modern execution stats did not render the persisted cumulative counters");
        String legacy = (String) stats.invoke(activity, new JSONObject().put("num_turns", 7));
        check(legacy.contains("旧版末段轮次：7（未累计）") && legacy.contains("旧版统计不完整"),
                "Legacy final-segment count was presented as a cumulative total");
        JSONObject partial = new JSONObject(actualRecord.toString());
        JSONObject ledger = partial.getJSONObject("execution_metrics");
        ledger.put("incomplete", true);
        ledger.getJSONObject("queries").put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", -1);
        String incomplete = (String) stats.invoke(activity, partial);
        check(incomplete.contains("统计不完整") && incomplete.contains("尚未结算"),
                "Incomplete/unsettled metrics lost their visible qualifications");
    }

    static void run(MainActivity activity, AgentController waitingController) throws Exception {
        run(activity, waitingController, null);
    }

    static void run(MainActivity activity, AgentController waitingController, File preview) throws Exception {
        check(Looper.myLooper() == Looper.getMainLooper(), "Run the interaction UI probe on the main thread");
        check(activity != null, "No visible MainActivity for interaction UI probe");
        JSONObject pending = waitingController.getPendingQuestion();
        check(waitingController.canAcceptInput() && pending != null, "UI probe needs an answerable native question");
        JSONObject beforePending = new JSONObject(pending.toString());
        AgentController.VisualState beforePhase = waitingController.getVisualState();
        String activeId = waitingController.getActiveConversationId();
        Snapshot original = new Snapshot(activity, waitingController);
        try {
            field(MainActivity.class, "controller").set(activity, waitingController);
            field(MainActivity.class, "selectedConversationId").set(activity, activeId);
            field(MainActivity.class, "showHistory").setBoolean(activity, false);
            original.drafts.clear(); original.questionTexts.clear(); original.questionChoices.clear();
            field(MainActivity.class, "renderedInteraction").set(activity, "");
            original.prompt.setText("");
            original.render.invoke(activity, true);
            original.refresh.invoke(activity);
            TextView send = (TextView) field(MainActivity.class, "send").get(activity);
            TextView stop = (TextView) field(MainActivity.class, "stop").get(activity);
            View panel = (View) field(MainActivity.class, "interactionPanel").get(activity);
            check("回答并继续".contentEquals(send.getText()) && send.isEnabled(), "Question UI lacks its explicit submit action");
            check("结束".contentEquals(stop.getText()) && stop.isEnabled(), "Waiting task cannot be ended");
            check(stop.getMaxLines() == 1 && send.getMaxLines() == 1,
                    "Task action buttons are allowed to wrap onto another line");
            check(stop.getLayoutParams().width == ViewGroup.LayoutParams.WRAP_CONTENT,
                    "The short End action lost its content-sized layout");
            TextView connection = (TextView) field(MainActivity.class, "connectionStatus").get(activity);
            check(PhoneAccessibilityService.getControlState(activity) == PhoneAccessibilityService.ControlState.CONNECTED
                            && connection.getContentDescription().toString().contains("手机控制已连接"),
                    "A bound accessibility service was rendered as missing configuration or disconnected");

            JSONArray nativeQuestions = pending.getJSONObject("input").getJSONArray("questions");
            JSONObject nativeQuestion = nativeQuestions.getJSONObject(0);
            check(nativeQuestions.length() == 1 && !nativeQuestion.optBoolean("multiSelect"),
                    "The native UI fixture must provide one single-choice question");
            String nativeText = nativeQuestion.getString("question");
            JSONArray nativeOptions = nativeQuestion.getJSONArray("options");
            List<RadioButton> radio = views(panel, RadioButton.class);
            check(radio.size() == nativeOptions.length() && radio.size() >= 2, "Native question options did not render");
            radio.get(0).performClick(); radio.get(1).performClick();
            int checked = 0;
            for (RadioButton option : radio) if (option.isChecked()) checked++;
            check(checked == 1 && radio.get(1).isChecked(), "Single-choice question retained multiple selections");
            EditText custom = describedInput(panel, nativeText + "，自由回答");
            custom.setText("UI 自由回答");
            Method collect = method("collectQuestionAnswers", JSONObject.class, String.class);
            JSONObject answers = (JSONObject) collect.invoke(activity, pending, "");
            String selectedLabel = nativeOptions.getJSONObject(1).getString("label");
            check(answers != null && answers.getString(nativeText).equals(selectedLabel + ", UI 自由回答"),
                    "Native selection/free answer did not preserve the original question key");
            assertStillWaiting(waitingController, beforePending, beforePhase);
            if (preview != null) saveQuestionPreview(panel, preview);

            // Separate view exercises multi-select without replacing the Controller's real question.
            JSONObject multiple = new JSONObject().put("question", "UI 多选测试问题").put("header", "多选")
                    .put("multiSelect", true).put("options", new JSONArray()
                            .put(new JSONObject().put("label", "Alpha").put("description", "第一个选项"))
                            .put(new JSONObject().put("label", "Beta").put("description", "第二个选项")));
            JSONObject synthetic = new JSONObject().put("id", "ui-probe-multiple").put("kind", "question")
                    .put("input", new JSONObject().put("questions", new JSONArray().put(multiple)));
            LinearLayout detached = new LinearLayout(activity);
            detached.setOrientation(LinearLayout.VERTICAL);
            method("addQuestion", LinearLayout.class, String.class, JSONObject.class, int.class, boolean.class)
                    .invoke(activity, detached, synthetic.getString("id"), multiple, 0, true);
            List<CheckBox> choices = views(detached, CheckBox.class);
            check(choices.size() == 2, "Multi-choice options did not render as checkboxes");
            choices.get(1).performClick(); choices.get(0).performClick();
            describedInput(detached, multiple.getString("question") + "，自由回答").setText("UI 多选补充");
            JSONObject combined = (JSONObject) collect.invoke(activity, synthetic, "");
            check(combined != null && "Alpha, Beta, UI 多选补充".equals(combined.getString(multiple.getString("question"))),
                    "Multi-choice answers did not use option order or retain free input");
            assertStillWaiting(waitingController, beforePending, beforePhase);

            original.prompt.setText("当前任务补充草稿");
            Method navigate = method("navigate", boolean.class, String.class);
            navigate.invoke(activity, true, "");
            original.prompt.setText("另一条新任务草稿");
            check("回到待处理任务".contentEquals(send.getText()), "History view offered to submit its draft into the active task");
            check(views(panel, RadioButton.class).isEmpty(), "Pending question was rendered in the wrong conversation");
            navigate.invoke(activity, false, activeId);
            check("当前任务补充草稿".contentEquals(original.prompt.getText()), "Returning to the active task lost its draft");
            check("另一条新任务草稿".equals(original.drafts.get("new-task")), "History/new-task draft was overwritten");
            List<RadioButton> returned = views(panel, RadioButton.class);
            check(returned.size() == nativeOptions.length() && returned.get(1).isChecked(), "Navigation lost the selected native answer");
            check("UI 自由回答".contentEquals(describedInput(panel, nativeText + "，自由回答").getText()),
                    "Navigation lost the custom native answer");
            Bundle saved = new Bundle();
            method("onSaveInstanceState", Bundle.class).invoke(activity, saved);
            String nativeKey = pending.getString("id") + "\n" + nativeText;
            check(saved.getBundle("question_choices") != null
                            && saved.getBundle("question_choices").getStringArrayList(nativeKey).contains(selectedLabel)
                            && "UI 自由回答".equals(saved.getBundle("question_texts").getString(nativeKey)),
                    "Rotation state did not preserve question choices and free input");
            check(saved.getBundle("drafts") != null
                            && "当前任务补充草稿".equals(saved.getBundle("drafts").getString(activeId))
                            && "另一条新任务草稿".equals(saved.getBundle("drafts").getString("new-task")),
                    "Rotation state mixed current-task and other-task drafts");
            assertStillWaiting(waitingController, beforePending, beforePhase);
        } finally { original.restore(); }
    }

    private static void assertStillWaiting(AgentController controller, JSONObject original, AgentController.VisualState phase) {
        JSONObject pending = controller.getPendingQuestion();
        check(controller.getVisualState() == phase && controller.canAcceptInput() && pending != null
                        && original.toString().equals(pending.toString()),
                "Editing or collecting a question answer submitted or changed the pending task");
    }

    /** Only the synthetic fixture's question panel is drawn; user settings/composer are excluded. */
    private static void saveQuestionPreview(View panel, File destination) throws Exception {
        int left = panel.getLeft(), top = panel.getTop(), right = panel.getRight(), bottom = panel.getBottom();
        int width = panel.getParent() instanceof View ? ((View) panel.getParent()).getWidth() : panel.getWidth();
        if (width <= 0) width = panel.getResources().getDisplayMetrics().widthPixels
                - Math.round(32 * panel.getResources().getDisplayMetrics().density);
        Bitmap bitmap = null;
        try {
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = panel.getMeasuredHeight();
            check(width > 0 && height > 0 && height <= 8192, "Question preview has invalid dimensions");
            panel.layout(0, 0, width, height);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.rgb(251, 246, 238)); panel.draw(canvas);
            try (FileOutputStream output = new FileOutputStream(destination)) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Could not write question preview");
            }
        } finally {
            panel.layout(left, top, right, bottom); panel.requestLayout();
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static final class Snapshot {
        final MainActivity activity;
        final AgentController originalController, fixtureController;
        final Object originalListener, fixtureListener;
        final boolean originalVisible, fixtureVisible, showHistory, editing, leaving, focus;
        final String selection, draft, setupText, stateText, unsavedOutput, unsavedId;
        final int setupVisibility, fieldsVisibility, scrollY, turnLimit, selectionStart, selectionEnd;
        final EditText prompt;
        final TextView setupToggle, state;
        final View setupPanel, modelFields, focused;
        final ScrollView scroll;
        final Method render, refresh;
        final Map<String, String> drafts, savedDrafts, questionTexts, savedQuestionTexts;
        final Map<String, Set<String>> questionChoices, savedQuestionChoices;
        final Set<String> expanded, savedExpanded;

        @SuppressWarnings("unchecked") Snapshot(MainActivity activity, AgentController fixture) throws Exception {
            this.activity = activity; fixtureController = fixture;
            originalController = (AgentController) field(MainActivity.class, "controller").get(activity);
            originalListener = field(AgentController.class, "listener").get(originalController);
            fixtureListener = field(AgentController.class, "listener").get(fixtureController);
            originalVisible = field(AgentController.class, "uiVisible").getBoolean(originalController);
            fixtureVisible = field(AgentController.class, "uiVisible").getBoolean(fixtureController);
            selection = (String) field(MainActivity.class, "selectedConversationId").get(activity);
            showHistory = field(MainActivity.class, "showHistory").getBoolean(activity);
            editing = field(MainActivity.class, "editingConnection").getBoolean(activity);
            leaving = field(MainActivity.class, "leavingForExecution").getBoolean(activity);
            focus = field(MainActivity.class, "focusActiveInput").getBoolean(activity);
            turnLimit = field(MainActivity.class, "visibleTurnLimit").getInt(activity);
            unsavedOutput = (String) field(MainActivity.class, "unsavedOutput").get(activity);
            unsavedId = (String) field(MainActivity.class, "unsavedConversationId").get(activity);
            prompt = (EditText) field(MainActivity.class, "prompt").get(activity); draft = prompt.getText().toString();
            selectionStart = prompt.getSelectionStart(); selectionEnd = prompt.getSelectionEnd();
            setupPanel = (View) field(MainActivity.class, "setupPanel").get(activity); setupVisibility = setupPanel.getVisibility();
            modelFields = (View) field(MainActivity.class, "modelFields").get(activity); fieldsVisibility = modelFields.getVisibility();
            setupToggle = (TextView) field(MainActivity.class, "setupToggle").get(activity); setupText = setupToggle.getText().toString();
            state = (TextView) field(MainActivity.class, "state").get(activity); stateText = state.getText().toString();
            scroll = (ScrollView) field(MainActivity.class, "contentScroll").get(activity); scrollY = scroll.getScrollY();
            focused = activity.getCurrentFocus();
            drafts = (Map<String, String>) field(MainActivity.class, "drafts").get(activity); savedDrafts = new HashMap<>(drafts);
            questionTexts = (Map<String, String>) field(MainActivity.class, "questionTextDrafts").get(activity);
            savedQuestionTexts = new HashMap<>(questionTexts);
            questionChoices = (Map<String, Set<String>>) field(MainActivity.class, "questionSelections").get(activity);
            savedQuestionChoices = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : questionChoices.entrySet())
                savedQuestionChoices.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            expanded = (Set<String>) field(MainActivity.class, "expandedProcesses").get(activity); savedExpanded = new HashSet<>(expanded);
            render = method("renderHistory", boolean.class); refresh = method("refreshState");
        }

        void restore() throws Exception {
            field(MainActivity.class, "controller").set(activity, originalController);
            field(MainActivity.class, "selectedConversationId").set(activity, selection);
            field(MainActivity.class, "showHistory").setBoolean(activity, showHistory);
            field(MainActivity.class, "editingConnection").setBoolean(activity, editing);
            field(MainActivity.class, "leavingForExecution").setBoolean(activity, leaving);
            field(MainActivity.class, "focusActiveInput").setBoolean(activity, focus);
            field(MainActivity.class, "visibleTurnLimit").setInt(activity, turnLimit);
            field(MainActivity.class, "unsavedOutput").set(activity, unsavedOutput);
            field(MainActivity.class, "unsavedConversationId").set(activity, unsavedId);
            drafts.clear(); drafts.putAll(savedDrafts);
            questionTexts.clear(); questionTexts.putAll(savedQuestionTexts);
            questionChoices.clear(); questionChoices.putAll(savedQuestionChoices);
            expanded.clear(); expanded.addAll(savedExpanded);
            field(AgentController.class, "listener").set(originalController, originalListener);
            field(AgentController.class, "listener").set(fixtureController, fixtureListener);
            field(AgentController.class, "uiVisible").setBoolean(originalController, originalVisible);
            field(AgentController.class, "uiVisible").setBoolean(fixtureController, fixtureVisible);
            field(MainActivity.class, "renderedInteraction").set(activity, "");
            prompt.setText(draft);
            if (selectionStart >= 0 && selectionEnd >= 0) prompt.setSelection(selectionStart, selectionEnd);
            render.invoke(activity, true); refresh.invoke(activity);
            setupPanel.setVisibility(setupVisibility); modelFields.setVisibility(fieldsVisibility);
            setupToggle.setText(setupText); state.setText(stateText);
            scroll.scrollTo(0, scrollY);
            if (focused != null && focused.isAttachedToWindow()) focused.requestFocus();
            OverlayService.setPetState(originalController.getVisualState());
        }
    }

    private static EditText describedInput(View root, String description) {
        for (EditText input : views(root, EditText.class)) if (description.contentEquals(input.getContentDescription())) return input;
        throw new AssertionError("Question free-input field is missing");
    }

    private static <T extends View> List<T> views(View root, Class<T> kind) {
        List<T> result = new ArrayList<>();
        if (kind.isInstance(root)) result.add(kind.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(views(group.getChildAt(i), kind));
        }
        return result;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }

    private static Method method(String name, Class<?>... arguments) throws Exception {
        Method result = MainActivity.class.getDeclaredMethod(name, arguments); result.setAccessible(true); return result;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
