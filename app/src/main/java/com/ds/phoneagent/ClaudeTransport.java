package com.ds.phoneagent;

import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Writes the native Claude Code stream-json protocol; never consumes stdout. */
public final class ClaudeTransport implements Closeable {
    private final Process process;
    private final BufferedWriter input;
    private boolean closed;

    ClaudeTransport(Process process) {
        this.process = process;
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    public Process getProcess() { return process; }

    /** Wait for the matching successful control_response before sending the first user message. */
    public String initialize() throws IOException {
        return control(object("subtype", "initialize"));
    }

    /** Caller-chosen IDs permit registration before writing, avoiding a fast-response race. */
    public void initialize(String requestId) throws IOException {
        control(requestId, object("subtype", "initialize"));
    }

    public String sendUser(String text) throws IOException {
        if (text == null || text.trim().isEmpty()) throw new IOException("用户输入不能为空");
        String identity = UUID.randomUUID().toString();
        write(object("type", "user", "message", object("role", "user", "content", text),
                "parent_tool_use_id", JSONObject.NULL, "session_id", "", "uuid", identity));
        return identity;
    }

    /** ACK and the interrupted turn's result are separate events and may arrive in either order. */
    public String interrupt() throws IOException {
        return control(object("subtype", "interrupt", "cancel_queued", true));
    }

    public void interrupt(String requestId) throws IOException {
        control(requestId, object("subtype", "interrupt", "cancel_queued", true));
    }

    /** Answer keys are exact question strings; values are labels/free text or comma-separated selections. */
    public void answerQuestion(String requestId, JSONObject originalInput, JSONObject answers) throws IOException {
        if (originalInput == null || answers == null) throw new IOException("反问答案不完整");
        final JSONObject updated;
        try {
            updated = new JSONObject(originalInput.toString());
            updated.put("answers", new JSONObject(answers.toString()));
        } catch (Exception invalid) { throw new IOException("反问答案格式无效"); }
        reply(requestId, object("behavior", "allow", "updatedInput", updated));
    }

    /** Unknown tools must be refused, never implicitly granted by a generic permission callback. */
    public void denyTool(String requestId, String reason) throws IOException {
        reply(requestId, object("behavior", "deny", "message", reason == null ? "此工具未获允许。" : reason));
    }

    private String control(JSONObject request) throws IOException {
        String identity = UUID.randomUUID().toString();
        control(identity, request);
        return identity;
    }

    private void control(String requestId, JSONObject request) throws IOException {
        if (requestId == null || requestId.isEmpty()) throw new IOException("Claude 控制请求标识缺失");
        write(object("type", "control_request", "request_id", requestId, "request", request));
    }

    private void reply(String requestId, JSONObject response) throws IOException {
        if (requestId == null || requestId.isEmpty()) throw new IOException("反问请求标识缺失");
        write(object("type", "control_response", "response", object("subtype", "success",
                "request_id", requestId, "response", response)));
    }

    private synchronized void write(JSONObject value) throws IOException {
        if (closed || !process.isAlive()) throw new IOException("Claude 会话连接已关闭");
        input.write(value.toString());
        input.newLine();
        input.flush();
    }

    private static JSONObject object(Object... pairs) throws IOException {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        } catch (Exception invalid) { throw new IOException("Claude 控制消息格式无效"); }
        return value;
    }

    /** Closes stdin only. Controller remains responsible for stopping/reaping the process. */
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        input.close();
    }
}
