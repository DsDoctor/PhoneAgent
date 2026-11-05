package com.ds.phoneagent;

import android.content.Context;
import android.util.AtomicFile;
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
    private final AtomicFile file;

    LastTaskStore(Context context) { this(context.getFilesDir()); }
    LastTaskStore(File directory) { file = new AtomicFile(new File(directory, "last-task.json")); }

    synchronized void write(JSONObject record) throws IOException {
        JSONObject safe = normalize(record);
        if (safe == null) throw new IOException("Invalid task record");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(safe.toString().getBytes(StandardCharsets.UTF_8));
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
                if (bytes.size() + count > 160000) return null;
                bytes.write(buffer, 0, count);
            }
            return normalize(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Exception unavailable) { return null; }
    }

    synchronized JSONObject recoverInterrupted() {
        JSONObject record = read();
        if (record == null || !"running".equals(record.optString("state"))) return record;
        try {
            long now = System.currentTimeMillis();
            record.put("state", "interrupted");
            record.put("acknowledged", false);
            record.put("status", "上次任务在运行中被中断，已恢复之前的记录。");
            record.put("reason", "process_interrupted");
            record.put("updated_at", now);
            record.put("ended_at", now);
            String previous = record.optString("output");
            String footer = terminalFooter("中断", "应用重新启动时，上次任务尚未完成。",
                    optionalInt(record, "exit_code"), optionalInt(record, "num_turns"));
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
        JSONObject record = read();
        if (record == null) return true;
        if ("running".equals(record.optString("state"))) return false;
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

    /** Whitelist fields so a future caller cannot accidentally persist a request/config object. */
    private static JSONObject normalize(JSONObject source) {
        if (source == null || source.optInt("version", 1) != 1) return null;
        String state = source.optString("state");
        if (!(state.equals("running") || state.equals("success") || state.equals("error")
                || state.equals("stopped") || state.equals("interrupted"))) return null;
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
            Integer exit = optionalInt(source, "exit_code"), turns = optionalInt(source, "num_turns");
            if (exit != null) result.put("exit_code", exit);
            if (turns != null) result.put("num_turns", turns);
            for (String field : new String[]{"conversation_id", "turn_id", "session_id"}) {
                String value = source.optString(field);
                if (LinuxRuntime.isSessionId(value)) result.put(field, value);
            }
            return result;
        } catch (Exception invalid) { return null; }
    }

    private static String bounded(String value, int limit) { return value.length() > limit ? value.substring(0, limit) : value; }
}
