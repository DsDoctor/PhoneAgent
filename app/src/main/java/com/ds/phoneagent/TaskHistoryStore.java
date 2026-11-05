package com.ds.phoneagent;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Recent real Claude conversations. Only redacted, presentation-safe turn fields are accepted. */
final class TaskHistoryStore {
    private static final int MAX_CONVERSATIONS = 20;
    private final AtomicFile file;
    private JSONObject document = emptyDocument();
    private boolean loadFailed;

    TaskHistoryStore(Context context) { this(context.getFilesDir()); }
    TaskHistoryStore(File directory) {
        file = new AtomicFile(new File(directory, "task-history.json"));
        if (file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()) {
            try (FileInputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                JSONObject source = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (source.optInt("version") != 1) throw new IOException("Unsupported history version");
                JSONArray saved = source.optJSONArray("conversations");
                if (saved == null) throw new IOException("Invalid history");
                JSONArray clean = new JSONArray();
                for (int i = 0; i < saved.length(); i++) clean.put(normalizeConversation(saved.getJSONObject(i)));
                document.put("conversations", ordered(clean));
                document.put("legacy_migrated", source.optBoolean("legacy_migrated", false));
            } catch (Exception unavailable) { loadFailed = true; }
        }
    }

    synchronized void initialize(JSONObject previous) throws IOException {
        String before = document.toString();
        try {
            boolean changed = false;
            if (!document.optBoolean("legacy_migrated", false)) {
                if (previous != null && conversations().length() == 0) {
                    String id = UUID.randomUUID().toString();
                    String output = previous.optString("output");
                    int marker = output.lastIndexOf("【任务结束】");
                    JSONObject turn = new JSONObject(previous.toString());
                    turn.put("id", UUID.randomUUID().toString()).put("prompt", "旧任务（未保存原始指令）")
                            .put("process", marker >= 0 ? output.substring(0, marker).trim() : output)
                            .put("result", "").put("terminal", marker >= 0 ? output.substring(marker) : "");
                    JSONObject legacy = new JSONObject().put("id", id).put("title", "旧任务记录").put("legacy", true)
                            .put("created_at", previous.optLong("started_at", System.currentTimeMillis()))
                            .put("updated_at", previous.optLong("updated_at", System.currentTimeMillis()))
                            .put("turns", new JSONArray().put(turn));
                    conversations().put(normalizeConversation(legacy));
                }
                document.put("legacy_migrated", true);
                changed = true;
            }
            for (int i = 0; i < conversations().length(); i++) {
                JSONObject conversation = conversations().getJSONObject(i);
                JSONArray turns = conversation.getJSONArray("turns");
                for (int j = 0; j < turns.length(); j++) {
                    JSONObject turn = turns.getJSONObject(j);
                    if (LastTaskStore.isPauseTransition(turn.optString("state"))) {
                        long now = System.currentTimeMillis();
                        turn.put("state", "paused").put("reason", "process_paused")
                                .put("status", "应用已重启，任务保持暂停；补充指令后可继续。")
                                .put("updated_at", now).put("ended_at", 0);
                        conversation.put("updated_at", now);
                        changed = true;
                        continue;
                    }
                    if (!"running".equals(turn.optString("state"))) continue;
                    long now = System.currentTimeMillis();
                    String terminal = LastTaskStore.terminalFooter("中断", "应用重新启动时，上次任务尚未完成。",
                            LastTaskStore.optionalInt(turn, "exit_code"), LastTaskStore.optionalInt(turn, "num_turns"));
                    turn.put("state", "interrupted").put("reason", "process_interrupted")
                            .put("status", "上次任务在运行中被中断，已恢复之前的记录。")
                            .put("terminal", terminal)
                            .put("updated_at", now).put("ended_at", now);
                    String body = turn.optString("process") + (turn.optString("result").isEmpty() ? "" : "\n\n" + turn.optString("result"));
                    turn.put("output", LastTaskStore.tail(body + "\n\n" + terminal));
                    conversation.put("status", "interrupted").put("updated_at", now);
                    changed = true;
                }
                if (turns.length() > 0) conversation.put("status", turns.getJSONObject(turns.length() - 1).optString("state"));
            }
            if (changed) persist();
        } catch (Exception failure) { restore(before); throw io(failure); }
    }

    synchronized JSONArray summaries() {
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < conversations().length(); i++) {
                JSONObject full = conversations().getJSONObject(i);
                JSONArray turns = full.getJSONArray("turns");
                JSONObject last = turns.length() == 0 ? new JSONObject() : turns.getJSONObject(turns.length() - 1);
                result.put(new JSONObject().put("id", full.getString("id")).put("title", full.optString("title"))
                        .put("created_at", full.optLong("created_at")).put("updated_at", full.optLong("updated_at"))
                        .put("status", full.optString("status")).put("session_id", full.optString("session_id"))
                        .put("can_continue", LinuxRuntime.isSessionId(full.optString("session_id")))
                        .put("legacy", full.optBoolean("legacy", false)).put("turn_count", turns.length())
                        .put("last_result", clip(last.optString("result"), 240)));
            }
        } catch (Exception ignored) { }
        return result;
    }

    synchronized JSONObject getConversation(String id) {
        JSONObject found = find(id);
        try { return found == null ? null : new JSONObject(found.toString()); }
        catch (Exception unavailable) { return null; }
    }

    synchronized String latestId() { return conversations().length() == 0 ? "" : conversations().optJSONObject(0).optString("id"); }

    synchronized JSONObject beginTurn(String conversationId, String prompt) throws IOException {
        String before = document.toString();
        try {
            JSONObject conversation;
            long now = System.currentTimeMillis();
            if (conversationId == null || conversationId.isEmpty()) {
                conversation = new JSONObject().put("id", UUID.randomUUID().toString())
                        .put("title", clip(prompt.replaceAll("\\s+", " ").trim(), 60))
                        .put("session_id", "").put("created_at", now).put("updated_at", now).put("status", "running")
                        .put("legacy", false).put("turns", new JSONArray());
                conversations().put(conversation);
            } else {
                conversation = find(conversationId);
                if (conversation == null) throw new IOException("Conversation not found");
            }
            String turnId = UUID.randomUUID().toString();
            JSONObject turn = new JSONObject().put("id", turnId).put("prompt", prompt)
                    .put("process", "").put("result", "").put("terminal", "").put("output", "")
                    .put("state", "running").put("status", "正在连接模型…").put("reason", "")
                    .put("started_at", now).put("updated_at", now).put("ended_at", 0);
            conversation.getJSONArray("turns").put(normalizeTurn(turn));
            conversation.put("updated_at", now).put("status", "running");
            persist();
            return new JSONObject().put("conversation_id", conversation.getString("id")).put("turn_id", turnId)
                    .put("session_id", conversation.optString("session_id")).put("started_at", now);
        } catch (Exception failure) { restore(before); throw io(failure); }
    }

    synchronized void updateTurn(String conversationId, String turnId, JSONObject record) throws IOException {
        String before = document.toString();
        try {
            JSONObject conversation = find(conversationId);
            if (conversation == null) throw new IOException("Conversation not found");
            JSONArray turns = conversation.getJSONArray("turns");
            for (int i = 0; i < turns.length(); i++) {
                if (!turnId.equals(turns.getJSONObject(i).optString("id"))) continue;
                JSONObject previous = turns.getJSONObject(i);
                JSONObject incoming = new JSONObject(record.toString());
                // Progress-only updates retain interaction data; explicit JSON null clears it.
                if (!incoming.has("pending_question") && LastTaskStore.isSuspended(incoming.optString("state"))
                        && previous.has("pending_question")) incoming.put("pending_question", previous.get("pending_question"));
                if (!incoming.has("interaction_answer") && previous.has("interaction_answer"))
                    incoming.put("interaction_answer", previous.get("interaction_answer"));
                JSONObject clean = normalizeTurn(incoming);
                clean.put("id", turnId);
                turns.put(i, clean);
                conversation.put("updated_at", clean.optLong("updated_at")).put("status", clean.optString("state"));
                persist();
                return;
            }
            throw new IOException("Turn not found");
        } catch (Exception failure) { restore(before); throw io(failure); }
    }

    synchronized void bindSession(String conversationId, String sessionId) throws IOException {
        if (!LinuxRuntime.isSessionId(sessionId)) throw new IOException("Invalid session id");
        String before = document.toString();
        try {
            JSONObject conversation = find(conversationId);
            if (conversation == null) throw new IOException("Conversation not found");
            String existing = conversation.optString("session_id");
            if (!existing.isEmpty() && !existing.equalsIgnoreCase(sessionId)) throw new IOException("Session mismatch");
            conversation.put("session_id", sessionId.toLowerCase(Locale.US));
            persist();
        } catch (Exception failure) { restore(before); throw io(failure); }
    }

    /** Import only already-redacted evidence. Replacing an id is explicit and does not invent a CLI session. */
    synchronized String importConversation(JSONObject source) throws IOException {
        String before = document.toString();
        try {
            JSONObject clean = normalizeConversation(source);
            String id = clean.getString("id");
            JSONArray existing = conversations();
            boolean replaced = false;
            for (int i = 0; i < existing.length(); i++) {
                JSONObject current = existing.getJSONObject(i);
                if (id.equals(current.optString("id"))) { existing.put(i, clean); replaced = true; break; }
            }
            if (!replaced) existing.put(clean);
            document.put("legacy_migrated", true);
            persist();
            return id;
        } catch (Exception failure) { restore(before); throw io(failure); }
    }

    /** Remove only the App's record. The underlying Claude session is deliberately untouched. */
    synchronized boolean deleteConversation(String id) throws IOException {
        if (find(id) == null) return false;
        String before = document.toString();
        try {
            JSONArray retained = new JSONArray();
            for (int i = 0; i < conversations().length(); i++) {
                JSONObject conversation = conversations().getJSONObject(i);
                if (!id.equals(conversation.optString("id"))) retained.put(conversation);
            }
            document.put("conversations", retained);
            // Even an empty list is intentional after deletion, never a fresh legacy-migration target.
            document.put("legacy_migrated", true);
            persist();
            return true;
        } catch (Exception failure) { restore(before); throw io(failure); }
    }

    private JSONObject find(String id) {
        if (id == null) return null;
        for (int i = 0; i < conversations().length(); i++) {
            JSONObject value = conversations().optJSONObject(i);
            if (value != null && id.equals(value.optString("id"))) return value;
        }
        return null;
    }
    private JSONArray conversations() { return document.optJSONArray("conversations"); }
    private void restore(String snapshot) {
        try { document = new JSONObject(snapshot); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private void persist() throws IOException {
        if (loadFailed) throw new IOException("Existing conversation history could not be read");
        FileOutputStream output = null;
        try {
            document.put("conversations", ordered(conversations()));
            output = file.startWrite();
            output.write(document.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw io(failure);
        }
    }
    private static JSONArray ordered(JSONArray items) throws Exception {
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) ordered.add(items.getJSONObject(i));
        ordered.sort(Comparator.comparingLong((JSONObject value) -> value.optLong("updated_at")).reversed());
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(MAX_CONVERSATIONS, ordered.size()); i++) result.put(ordered.get(i));
        return result;
    }
    private static JSONObject normalizeConversation(JSONObject source) throws Exception {
        if (source == null) throw new IOException("Invalid conversation");
        String id = source.optString("id", UUID.randomUUID().toString());
        if (!LinuxRuntime.isSessionId(id)) throw new IOException("Invalid conversation id");
        String session = source.optString("session_id");
        if (!session.isEmpty() && !LinuxRuntime.isSessionId(session)) throw new IOException("Invalid session id");
        JSONArray raw = source.optJSONArray("turns"), turns = new JSONArray();
        if (raw != null) for (int i = 0; i < raw.length(); i++) turns.put(normalizeTurn(raw.getJSONObject(i)));
        String status = turns.length() == 0 ? "idle" : turns.getJSONObject(turns.length() - 1).optString("state");
        long now = System.currentTimeMillis();
        return new JSONObject().put("id", id.toLowerCase(Locale.US)).put("title", clip(source.optString("title", "任务会话"), 100))
                .put("session_id", session.toLowerCase(Locale.US)).put("created_at", source.optLong("created_at", now))
                .put("updated_at", source.optLong("updated_at", now)).put("status", status)
                .put("legacy", source.optBoolean("legacy", false)).put("turns", turns);
    }
    private static JSONObject normalizeTurn(JSONObject source) throws Exception {
        String state = source.optString("state");
        if (!LastTaskStore.validState(state)) throw new IOException("Invalid turn state");
        String id = source.optString("id", UUID.randomUUID().toString());
        if (!LinuxRuntime.isSessionId(id)) throw new IOException("Invalid turn id");
        JSONObject clean = new JSONObject().put("id", id).put("state", state);
        for (String key : new String[]{"prompt", "process", "result", "terminal", "output"}) clean.put(key, LastTaskStore.tail(source.optString(key)));
        clean.put("status", clip(source.optString("status"), 1024)).put("reason", clip(source.optString("reason"), 80));
        for (String key : new String[]{"started_at", "updated_at", "ended_at"}) clean.put(key, Math.max(0, source.optLong(key)));
        for (String key : new String[]{"exit_code", "num_turns"}) {
            Integer value = LastTaskStore.optionalInt(source, key);
            if (value != null) clean.put(key, value);
        }
        LastTaskStore.copyInteraction(source, clean);
        return clean;
    }
    private static JSONObject emptyDocument() {
        try { return new JSONObject().put("version", 1).put("legacy_migrated", false).put("conversations", new JSONArray()); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String clip(String value, int limit) { return value.length() > limit ? value.substring(0, limit) : value; }
    private static IOException io(Exception failure) { return failure instanceof IOException ? (IOException) failure : new IOException(failure); }
}
