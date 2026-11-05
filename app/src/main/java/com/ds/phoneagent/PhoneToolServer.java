package com.ds.phoneagent;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** A per-task authenticated loopback bridge. Never listens on the LAN. */
final class PhoneToolServer implements Closeable {
    interface Observer {
        void onTool(String name);
        default void onToolFinished(String name) { }
    }
    private static final Set<String> TOOLS = new HashSet<>(Arrays.asList(
        "read_screen", "home", "back", "open_settings", "open_app", "tap", "swipe", "type_text"));
    private final ServerSocket server;
    private final String token;
    private final Observer observer;
    private final PhoneActionGate gate;
    private final Thread thread;
    private volatile boolean closed;
    private volatile Socket active;

    PhoneToolServer(Observer observer) throws IOException {
        this(observer, openedGate());
    }

    PhoneToolServer(Observer observer, PhoneActionGate gate) throws IOException {
        this.observer = observer;
        this.gate = java.util.Objects.requireNonNull(gate);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder text = new StringBuilder();
        for (byte b : random) text.append(String.format("%02x", b & 255));
        token = text.toString();
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::serve, "phone-tools");
        thread.setDaemon(true);
        thread.start();
    }
    int port() { return server.getLocalPort(); }
    String token() { return token; }

    private static PhoneActionGate openedGate() {
        PhoneActionGate gate = new PhoneActionGate();
        gate.open();
        return gate;
    }

    private void serve() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                active = socket;
                // Capture before any blocking reads: a request received before a pause
                // cannot become new work merely because its body arrived after resume.
                long ticket = gate.capture();
                socket.setSoTimeout(5000);
                handle(socket, ticket);
            } catch (Exception ignored) {
                // A malformed/abandoned local request must not kill the listener.
            } finally { active = null; }
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (bytes.size() >= 4096) throw new IOException("Header too long");
            if (b != '\r') bytes.write(b);
        }
        if (b == -1 && bytes.size() == 0) throw new EOFException();
        return bytes.toString("US-ASCII");
    }

    private void handle(Socket socket, long ticket) throws Exception {
        InputStream in = socket.getInputStream();
        String requestLine = line(in);
        int length = -1;
        String auth = "";
        int headers = 0;
        for (;;) {
            String header = line(in);
            if (header.isEmpty()) break;
            if (++headers > 30) throw new IOException("Too many headers");
            int colon = header.indexOf(':');
            if (colon < 0) continue;
            String name = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(value);
            if (name.equalsIgnoreCase("Authorization")) auth = value;
        }
        if (!java.security.MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8))) {
            respond(socket, 401, new JSONObject().put("error", "Unauthorized"));
            return;
        }
        if (!requestLine.equals("POST /tool HTTP/1.1") && !requestLine.equals("POST /tool HTTP/1.0")) {
            respond(socket, 404, new JSONObject().put("error", "Not found"));
            return;
        }
        if (length < 0 || length > 65536) {
            respond(socket, 413, new JSONObject().put("error", "Invalid body length"));
            return;
        }
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(bytes, offset, length - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
        JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        String name = body.optString("name");
        if (!TOOLS.contains(name)) {
            respond(socket, 400, new JSONObject().put("error", "Unknown tool"));
            return;
        }
        if (closed) return;
        if (body.has("action_epoch")) {
            Object supplied = body.opt("action_epoch");
            if (!(supplied instanceof Number) || ((Number) supplied).doubleValue() != ((Number) supplied).longValue()) {
                respond(socket, 400, new JSONObject().put("ok", false).put("error", "Invalid action_epoch"));
                return;
            }
            if (((Number) supplied).longValue() != ticket) {
                respond(socket, 200, paused(ticket));
                return;
            }
        }
        if (!gate.isAllowed(ticket)) {
            respond(socket, 200, paused(ticket));
            return;
        }
        try {
            observer.onTool(name);
            JSONObject args = body.optJSONObject("arguments");
            JSONObject result;
            try { result = PhoneAccessibilityService.execute(name, args == null ? new JSONObject() : args, gate, ticket); }
            catch (Exception ex) { result = new JSONObject().put("ok", false).put("error", ex.getMessage()); }
            result.put("action_epoch", ticket);
            respond(socket, 200, result);
        } finally {
            observer.onToolFinished(name);
        }
    }

    private static JSONObject paused(long ticket) throws org.json.JSONException {
        return new JSONObject().put("ok", false).put("error", "任务已暂停，当前手机操作未执行；请等待用户回答或继续任务。")
                .put("error_code", "task_paused").put("action_epoch", ticket);
    }

    private static void respond(Socket socket, int code, JSONObject result) throws IOException {
        byte[] body = result.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + code + " Response\r\nContent-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    @Override public void close() {
        closed = true;
        gate.close();
        thread.interrupt();
        try { server.close(); } catch (IOException ignored) {}
        Socket socket = active;
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
}
