package com.ds.phoneagent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Per-App-task counters. IDs, not prompts/tool arguments, form the bounded replay ledger. */
final class ExecutionMetrics {
    static final int MAX_IDENTITIES = 2048;
    private static final String FIELD = "execution_metrics";
    private static final String[] COUNTERS = {"num_turns", "model_reply_count", "api_retry_count",
            "tool_call_count", "phone_action_count"};
    private final Map<String, Integer> queries = new LinkedHashMap<>();
    private final Set<String> models = new LinkedHashSet<>(), retries = new LinkedHashSet<>(),
            tools = new LinkedHashSet<>(), phoneActions = new LinkedHashSet<>();
    private int turns, modelReplies, apiRetries, toolCalls, phoneActionCount;
    private String activeQuery = "";
    private boolean incomplete;

    ExecutionMetrics() { }

    /** Legacy counts are lower bounds; their old final-query num_turns was never a task total. */
    static ExecutionMetrics fromRecord(JSONObject record) {
        ExecutionMetrics result = new ExecutionMetrics();
        if (record == null) return result;
        result.turns = count(record, COUNTERS[0]);
        result.modelReplies = count(record, COUNTERS[1]);
        result.apiRetries = count(record, COUNTERS[2]);
        result.toolCalls = count(record, COUNTERS[3]);
        result.phoneActionCount = count(record, COUNTERS[4]);
        JSONObject ledger = record.optJSONObject(FIELD);
        if (ledger == null || ledger.optInt("version") != 1) {
            result.incomplete = true;
            return result;
        }
        result.incomplete = ledger.optBoolean("incomplete", false);
        JSONObject queryValues = ledger.optJSONObject("queries");
        if (queryValues != null) {
            Iterator<String> keys = queryValues.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object raw = queryValues.opt(key);
                if (!validDigest(key) || !(raw instanceof Number)
                        || ((Number) raw).doubleValue() != ((Number) raw).intValue()
                        || ((Number) raw).intValue() < -1 || !result.room()) {
                    result.incomplete = true;
                    continue;
                }
                result.queries.put(key, ((Number) raw).intValue());
            }
        }
        result.load(ledger.optJSONArray("models"), result.models);
        result.load(ledger.optJSONArray("retries"), result.retries);
        result.load(ledger.optJSONArray("tools"), result.tools);
        result.load(ledger.optJSONArray("phone_actions"), result.phoneActions);
        String active = ledger.optString("active_query");
        if (result.queries.containsKey(active)) result.activeQuery = active;
        else if (!active.isEmpty()) result.incomplete = true;
        // A damaged counter must not drop below the evidence already in its ledger.
        long queryTotal = 0;
        for (int value : result.queries.values()) if (value >= 0) queryTotal += value;
        result.turns = result.atLeast(result.turns, queryTotal);
        result.modelReplies = result.atLeast(result.modelReplies, result.models.size());
        result.apiRetries = result.atLeast(result.apiRetries, result.retries.size());
        result.toolCalls = result.atLeast(result.toolCalls, result.tools.size());
        result.phoneActionCount = result.atLeast(result.phoneActionCount, result.phoneActions.size());
        return result;
    }

    /** Persist this snapshot before sendUser writes the same caller-chosen UUID to stdin. */
    synchronized boolean beginQuery(String userMessageId) {
        if (empty(userMessageId)) return markIncomplete();
        String id = digest(userMessageId);
        if (queries.containsKey(id)) {
            if (id.equals(activeQuery)) return false;
            activeQuery = id;
            return true;
        }
        if (!room()) return markIncomplete();
        queries.put(id, -1);
        activeQuery = id;
        return true;
    }

    /** Call before result pause/finish branching; a paused result settles its original query too. */
    synchronized boolean observe(JSONObject event) {
        if (event == null) return false;
        String type = event.optString("type");
        if ("result".equals(type)) return result(event);
        if ("system".equals(type) && "api_retry".equals(event.optString("subtype")))
            return recordApiRetry(identity(event, event.optString("uuid")));
        if (!"assistant".equals(type) || event.optBoolean("isApiErrorMessage", false)
                || event.has("error") && !event.isNull("error")
                || event.has("parent_tool_use_id") && !event.isNull("parent_tool_use_id")) return false;
        JSONObject message = event.optJSONObject("message");
        if (message == null) return false;
        boolean changed = false;
        String modelId = identity(event, message.optString("id"));
        int added = add(models, modelId);
        if (added == 1) { modelReplies = increment(modelReplies, 1); changed = true; }
        else if (added == -1) changed = true;
        JSONArray content = message.optJSONArray("content");
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null || !"tool_use".equals(part.optString("type"))) continue;
            added = add(tools, identity(event, part.optString("id")));
            if (added == 1) { toolCalls = increment(toolCalls, 1); changed = true; }
            else if (added == -1) changed = true;
        }
        return changed;
    }

    /** Use wire api_retry UUIDs only; native api_error twins have different UUIDs. */
    synchronized boolean recordApiRetry(String stableEventId) {
        int added = add(retries, stableEventId);
        if (added == 1) apiRetries = increment(apiRetries, 1);
        return added != 0;
    }

    /** Count bridge actions actually entered, using an ID retained for that operation. */
    synchronized boolean recordPhoneAction(String stableActionId) {
        int added = add(phoneActions, stableActionId);
        if (added == 1) phoneActionCount = increment(phoneActionCount, 1);
        return added != 0;
    }

    synchronized int engineTurns() { return turns; }
    synchronized int modelReplyCount() { return modelReplies; }
    synchronized int apiRetryCount() { return apiRetries; }
    synchronized int toolCallCount() { return toolCalls; }
    synchronized int phoneActionCount() { return phoneActionCount; }
    synchronized boolean isIncomplete() { return incomplete; }
    synchronized boolean hasUnsettledQuery() { return queries.containsValue(-1); }

    /** Shared by live completion and crash recovery so unsettled queries are never called complete. */
    synchronized String summary() {
        return "累计执行轮次：" + turns + "（Claude Code，含中断）"
                + "\n模型回复：" + modelReplies + " 次 · 服务重试：" + apiRetries + " 次"
                + "\n工具调用：" + toolCalls + " 次 · 手机操作请求：" + phoneActionCount + " 次"
                + (incomplete || hasUnsettledQuery() ? "\n统计不完整，以上为已记录的计数。" : "");
    }

    synchronized void writeTo(JSONObject target) {
        try {
            target.put(COUNTERS[0], turns).put(COUNTERS[1], modelReplies).put(COUNTERS[2], apiRetries)
                    .put(COUNTERS[3], toolCalls).put(COUNTERS[4], phoneActionCount);
            JSONObject queryValues = new JSONObject();
            for (Map.Entry<String, Integer> entry : queries.entrySet()) queryValues.put(entry.getKey(), entry.getValue());
            target.put(FIELD, new JSONObject().put("version", 1).put("incomplete", incomplete)
                    .put("active_query", activeQuery).put("queries", queryValues)
                    .put("models", array(models)).put("retries", array(retries))
                    .put("tools", array(tools)).put("phone_actions", array(phoneActions)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /** Store whitelist: normalize the ledger and never retain arbitrary request/session objects. */
    static void copyFields(JSONObject source, JSONObject target) {
        if (source.has(FIELD)) { fromRecord(source).writeTo(target); return; }
        try {
            // Do not silently migrate old records into a complete, zero-based ledger.
            for (String field : COUNTERS) {
                Integer value = LastTaskStore.optionalInt(source, field);
                if (value != null) target.put(field, value);
            }
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /** A legacy progress-only caller may omit metrics; omission must not erase durable counts. */
    static void preserveIfMissing(JSONObject previous, JSONObject incoming) {
        if (incoming.has(FIELD) || !previous.has(FIELD)) return;
        copyFields(previous, incoming);
    }

    private boolean result(JSONObject event) {
        Integer value = LastTaskStore.optionalInt(event, "num_turns");
        if (value == null) return markIncomplete();
        String user = event.optString("user_message_uuid");
        JSONArray users = event.optJSONArray("user_message_uuids");
        if (empty(user) && users != null && users.length() == 1) user = users.optString(0);
        // Prefer a stable result UUID over the mutable active query when an old producer omits correlation.
        String id = !empty(user) ? digest(user) : !empty(event.optString("uuid"))
                ? digest("result:" + identity(event, event.optString("uuid"))) : "";
        if (id.isEmpty()) return markIncomplete();
        if (empty(user) && !activeQuery.isEmpty()) incomplete = true;
        Integer previous = queries.get(id);
        if (previous == null && !room()) return markIncomplete();
        if (previous != null && previous >= value) return false;
        turns = increment(turns, value - Math.max(0, previous == null ? 0 : previous));
        queries.put(id, value);
        if (id.equals(activeQuery)) activeQuery = "";
        return true;
    }

    /** 1 = new evidence; 0 = replay; -1 = newly marked incomplete. No ID eviction/recounting. */
    private int add(Set<String> set, String rawId) {
        if (empty(rawId)) return markIncomplete() ? -1 : 0;
        String id = digest(rawId);
        if (set.contains(id)) return 0;
        if (!room()) return markIncomplete() ? -1 : 0;
        set.add(id);
        return 1;
    }
    private boolean room() { return queries.size() + models.size() + retries.size() + tools.size() + phoneActions.size() < MAX_IDENTITIES; }
    private boolean markIncomplete() { boolean changed = !incomplete; incomplete = true; return changed; }
    private int increment(int value, int delta) {
        long sum = (long) value + delta;
        if (sum > Integer.MAX_VALUE) { incomplete = true; return Integer.MAX_VALUE; }
        return (int) sum;
    }
    private int atLeast(int value, long minimum) {
        if (value >= minimum) return value;
        incomplete = true;
        return (int) Math.min(Integer.MAX_VALUE, minimum);
    }
    private void load(JSONArray values, Set<String> destination) {
        if (values == null) { incomplete = true; return; }
        for (int i = 0; i < values.length(); i++) {
            String id = values.optString(i);
            if (!validDigest(id)) { incomplete = true; continue; }
            if (destination.contains(id)) continue;
            if (!room()) { incomplete = true; break; }
            destination.add(id);
        }
    }
    private static JSONArray array(Set<String> values) { JSONArray result = new JSONArray(); for (String value : values) result.put(value); return result; }
    private static int count(JSONObject record, String field) { Integer value = LastTaskStore.optionalInt(record, field); return value == null ? 0 : value; }
    private static String identity(JSONObject event, String id) {
        if (empty(id)) return "";
        return event.optString("session_id", event.optString("sessionId")) + ":" + id;
    }
    private static boolean empty(String value) { return value == null || value.isEmpty() || "null".equals(value); }
    private static boolean validDigest(String value) { return value != null && value.matches("[0-9a-f]{32}"); }
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray(), result = new char[32];
            for (int i = 0; i < 16; i++) { result[i * 2] = hex[(bytes[i] & 255) >>> 4]; result[i * 2 + 1] = hex[bytes[i] & 15]; }
            return new String(result);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
