package com.ds.phoneagent;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One bounded, app-private task transcript. Callers supply only redacted, displayable text. */
final class LastTaskStore {
    static final int OUTPUT_LIMIT = 24000;
    static final int INTERACTION_ANSWER_LIMIT = 8000;
    static final int QUESTION_BYTES_LIMIT = 16384;
    private static final int RECORD_BYTES_LIMIT = 256000;
    private final AtomicFile file;

    LastTaskStore(Context context) { this(context.getFilesDir()); }
    LastTaskStore(File directory) { file = new AtomicFile(new File(directory, "last-task.json")); }

    synchronized void write(JSONObject record) throws IOException {
        JSONObject safe = normalize(record);
        if (safe == null) throw new IOException("Invalid task record");
        byte[] encoded = safe.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > RECORD_BYTES_LIMIT) throw new IOException("Task record is too large");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(encoded);
            file.finishWrite(output);
        } catch (IOException failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }

    synchronized JSONObject read() {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > RECORD_BYTES_LIMIT) return null;
                bytes.write(buffer, 0, count);
            }
            return normalize(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Exception unavailable) { return null; }
    }

    /** Compare and delete under the same lock so deleting older history cannot erase a newer task. */
    synchronized boolean clearIfUnchanged(JSONObject expected) throws IOException {
        JSONObject current = read();
        if (expected == null || current == null || !current.toString().equals(expected.toString())) return false;
        file.delete();
        File base = file.getBaseFile();
        if (base.exists() || new File(base.getPath() + ".bak").exists() || new File(base.getPath() + ".new").exists())
            throw new IOException("无法清理上一任务记录，请重试。");
        return true;
    }

    /** Roll back a failed history deletion without replacing a task started in the meantime. */
    synchronized void restoreIfEmpty(JSONObject previous) throws IOException {
        File base = file.getBaseFile();
        if (!base.exists() && !new File(base.getPath() + ".bak").exists() && !new File(base.getPath() + ".new").exists()) write(previous);
    }

    synchronized JSONObject recoverInterrupted() {
        JSONObject record = read();
        if (record == null) return null;
        String state = record.optString("state");
        if (isPauseTransition(state)) {
            try {
                record.put("state", "paused").put("acknowledged", false)
                        .put("status", "应用已重启，任务保持暂停；补充指令后可继续。")
                        .put("reason", "process_paused").put("updated_at", System.currentTimeMillis()).put("ended_at", 0);
                try { write(record); } catch (IOException unavailable) { /* Keep the recovered pause visible. */ }
            } catch (Exception invalid) { return null; }
            return record;
        }
        if (!"running".equals(state)) return record;
        try {
            long now = System.currentTimeMillis();
            record.put("state", "interrupted");
            record.put("acknowledged", false);
            record.put("status", "上次任务在运行中被中断，已恢复之前的记录。");
            record.put("reason", "process_interrupted");
            record.put("updated_at", now);
            record.put("ended_at", now);
            String previous = record.optString("output");
            boolean hasMetrics = record.has("execution_metrics");
            String footer = terminalFooter("中断", "应用重新启动时，上次任务尚未完成。",
                    optionalInt(record, "exit_code"), hasMetrics ? null : optionalInt(record, "num_turns"));
            if (hasMetrics) footer += "\n" + ExecutionMetrics.fromRecord(record).summary();
            record.put("output", tail(previous + (previous.isEmpty() ? "" : "\n\n") + footer));
            try { write(record); }
            catch (IOException unavailable) {
                record.put("output", tail(record.optString("output") + "\n记录暂时无法保存，当前仍可查看。"));
            }
        } catch (Exception ignored) { return null; }
        return record;
    }

    /** Acknowledge the presentation without changing the historical task outcome or text. */
    synchronized boolean acknowledge() {
        return acknowledge(null);
    }

    /** After deletion, the selected remaining turn can provide an explicit acknowledgement record. */
    synchronized boolean acknowledge(JSONObject fallback) {
        JSONObject record = read();
        if (record == null) {
            File base = file.getBaseFile();
            if (base.exists() || new File(base.getPath() + ".bak").exists() || new File(base.getPath() + ".new").exists()) return false;
            if (fallback == null) return true;
            record = normalize(fallback);
            if (record == null) return false;
        }
        if ("running".equals(record.optString("state")) || isSuspended(record.optString("state"))) return false;
        try {
            record.put("acknowledged", true);
            write(record);
            return true;
        } catch (Exception unavailable) { return false; }
    }

    static Integer optionalInt(JSONObject record, String field) {
        Object value = record.opt(field);
        if (!(value instanceof Number)) return null;
        long number = ((Number) value).longValue();
        if (number < 0 || number > Integer.MAX_VALUE) return null;
        return (int) number;
    }

    static String terminalFooter(String state, String reason, Integer exitCode, Integer turns) {
        StringBuilder footer = new StringBuilder("【任务结束】\n状态：").append(state).append("\n原因：").append(reason);
        if (exitCode != null) footer.append("\n退出码：").append(exitCode);
        if (turns != null) footer.append("\n执行轮数：").append(turns);
        return footer.toString();
    }

    static String tail(String text) {
        if (text.length() <= OUTPUT_LIMIT) return text;
        int start = text.length() - OUTPUT_LIMIT;
        if (Character.isLowSurrogate(text.charAt(start))) start++;
        return text.substring(start);
    }

    static boolean isPauseTransition(String state) { return state.equals("pausing") || state.equals("resuming"); }
    static boolean isSuspended(String state) { return state.equals("paused") || state.equals("waiting_user") || isPauseTransition(state); }
    static boolean validState(String state) {
        return state.equals("running") || state.equals("success") || state.equals("error")
                || state.equals("stopped") || state.equals("interrupted") || isSuspended(state);
    }

    /** Preserve exact question strings: they are native answer-map keys and must never be truncated. */
    static void copyInteraction(JSONObject source, JSONObject target) throws Exception {
        Object raw = source.opt("pending_question");
        if (raw != null && raw != JSONObject.NULL) {
            if (!(raw instanceof JSONObject)) throw new IOException("Invalid pending question");
            JSONObject pending = (JSONObject) raw;
            String id = exactString(pending, "id", 200, false);
            if (!"question".equals(pending.optString("kind"))) throw new IOException("Invalid interaction kind");
            JSONObject input = pending.optJSONObject("input");
            JSONArray questions = input == null ? null : input.optJSONArray("questions");
            if (questions == null || questions.length() < 1 || questions.length() > 4) throw new IOException("Invalid question count");
            JSONArray cleanQuestions = new JSONArray();
            for (int i = 0; i < questions.length(); i++) {
                JSONObject question = questions.getJSONObject(i);
                JSONObject clean = new JSONObject().put("question", exactString(question, "question", 2000, false))
                        .put("header", exactString(question, "header", 200, true))
                        .put("multiSelect", question.optBoolean("multiSelect", false));
                JSONArray options = question.optJSONArray("options");
                if (options == null || options.length() < 2 || options.length() > 4) throw new IOException("Invalid question options");
                JSONArray cleanOptions = new JSONArray();
                for (int j = 0; j < options.length(); j++) {
                    JSONObject option = options.getJSONObject(j);
                    cleanOptions.put(new JSONObject().put("label", exactString(option, "label", 200, false))
                            .put("description", exactString(option, "description", 1000, true)));
                }
                clean.put("options", cleanOptions);
                cleanQuestions.put(clean);
            }
            JSONObject clean = new JSONObject().put("id", id).put("kind", "question")
                    .put("input", new JSONObject().put("questions", cleanQuestions));
            if (clean.toString().getBytes(StandardCharsets.UTF_8).length > QUESTION_BYTES_LIMIT)
                throw new IOException("Pending question is too large");
            target.put("pending_question", clean);
        }
        Object answer = source.opt("interaction_answer");
        if (answer != null && answer != JSONObject.NULL) {
            if (!(answer instanceof String) || ((String) answer).length() > INTERACTION_ANSWER_LIMIT)
                throw new IOException("Interaction answer is too large or invalid");
            target.put("interaction_answer", answer);
        }
    }

    private static String exactString(JSONObject source, String field, int limit, boolean optional) throws IOException {
        Object raw = source.opt(field);
        if (optional && (raw == null || raw == JSONObject.NULL)) return "";
        if (!(raw instanceof String) || ((String) raw).length() > limit || (!optional && ((String) raw).trim().isEmpty()))
            throw new IOException("Invalid pending question field");
        return (String) raw;
    }

    /** Whitelist fields so a future caller cannot accidentally persist a request/config object. */
    private static JSONObject normalize(JSONObject source) {
        if (source == null || source.optInt("version", 1) != 1) return null;
        String state = source.optString("state");
        if (!validState(state)) return null;
        try {
            JSONObject result = new JSONObject();
            result.put("version", 1);
            result.put("state", state);
            result.put("acknowledged", source.optBoolean("acknowledged", false));
            result.put("status", bounded(source.optString("status"), 1024));
            result.put("output", tail(source.optString("output")));
            result.put("reason", bounded(source.optString("reason"), 80));
            result.put("started_at", Math.max(0, source.optLong("started_at")));
            result.put("updated_at", Math.max(0, source.optLong("updated_at")));
            result.put("ended_at", Math.max(0, source.optLong("ended_at")));
            Integer exit = optionalInt(source, "exit_code");
            if (exit != null) result.put("exit_code", exit);
            ExecutionMetrics.copyFields(source, result);
            for (String field : new String[]{"conversation_id", "turn_id", "session_id"}) {
                String value = source.optString(field);
                if (LinuxRuntime.isSessionId(value)) result.put(field, value);
            }
            copyInteraction(source, result);
            return result;
        } catch (Exception invalid) { return null; }
    }

    private static String bounded(String value, int limit) { return value.length() > limit ? value.substring(0, limit) : value; }
}
