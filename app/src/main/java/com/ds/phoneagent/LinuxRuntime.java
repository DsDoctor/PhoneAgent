package com.ds.phoneagent;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Private, headless Linux userland. No Termux or desktop application is required. */
public final class LinuxRuntime {
    public interface Progress { void update(String message); }
    private static final String RUNTIME_VERSION = "alpine-3.22.7-claude-2.1.311-v2";
    private final Context context;
    private final File base;
    private final File root;
    private final File home;
    private final File bridge;
    private final File temp;
    private final Object processLock = new Object();
    private final AtomicLong cancellationEpoch = new AtomicLong();
    private Process activeProcess;

    public LinuxRuntime(Context context) {
        this.context = context.getApplicationContext();
        base = new File(context.getFilesDir(), "linux");
        root = new File(base, "rootfs");
        home = new File(base, "home");
        bridge = new File(base, "phoneagent");
        temp = new File(base, "tmp");
    }

    public boolean isInstalled() {
        try {
            return RUNTIME_VERSION.equals(read(new File(root, ".phoneagent-runtime")).trim())
                    && new File(root, "usr/local/bin/claude").isFile()
                    && new File(root, "usr/bin/python3").exists();
        } catch (Exception ignored) { return false; }
    }

    /** Called on a worker thread. Assets are prepared on the development host. */
    public synchronized void install(Progress progress) throws Exception {
        final long operation = cancellationEpoch.get();
        mkdir(base); mkdir(home); mkdir(bridge); mkdir(temp);
        File previous = new File(base, "rootfs.previous");
        // Recover an interrupted directory switch before starting a fresh installation.
        if (!root.exists() && previous.exists() && !previous.renameTo(root))
            throw new IOException("无法恢复原有运行环境");
        checkCancelled(operation);
        if (isInstalled()) {
            updateBridge();
            updateDns();
            checkCancelled(operation);
            progress.update("运行环境已准备好");
            return;
        }
        progress.update("正在准备手机运行环境…");
        File archive = new File(context.getCacheDir(), "phoneagent-rootfs.tar.gz");
        File staging = new File(base, "rootfs.installing");
        deleteTree(staging);
        mkdir(staging);
        try {
            try (InputStream input = context.getAssets().open("runtime/rootfs.tar.gz");
                 OutputStream output = new FileOutputStream(archive)) {
                copyCancellable(input, output, operation);
            }
            checkCancelled(operation);
            progress.update("正在解包 Linux 和 Claude Code，首次需要一点时间…");
            Process extraction = new ProcessBuilder("/system/bin/toybox", "tar", "-xzf",
                    archive.getAbsolutePath(), "-C", staging.getAbsolutePath())
                    .redirectErrorStream(true).start();
            registerProcess(extraction, operation);
            String output = collect(extraction, 600, operation);
            if (extraction.exitValue() != 0) throw new IOException("解包失败：" + output);
            checkCancelled(operation);
            if (!new File(staging, "usr/local/bin/claude").isFile())
                throw new IOException("运行环境缺少 Claude Code");
            updateBridge();
            updateDns(staging);
            progress.update("正在检查 Claude Code…");
            // Validate the new directory before moving the existing environment.
            String installedVersion = version(staging, operation);
            if (!installedVersion.contains("2.1.311"))
                throw new IOException("Claude Code 启动检查失败：" + installedVersion);
            checkCancelled(operation);
            write(new File(staging, ".phoneagent-runtime"), RUNTIME_VERSION + "\n");
            deleteTree(previous);
            synchronized (processLock) {
                checkCancelled(operation);
                boolean hadRoot = root.exists();
                if (hadRoot && !root.renameTo(previous)) throw new IOException("无法保留原有运行环境");
                if (!staging.renameTo(root)) {
                    if (hadRoot && !previous.renameTo(root))
                        throw new IOException("无法启用运行环境，原环境保留于 rootfs.previous");
                    throw new IOException("无法启用运行环境");
                }
            }
            // A valid rootfs is now installed even if a later progress callback is cancelled.
            deleteTree(previous);
            progress.update("运行环境就绪：" + installedVersion.trim());
        } finally {
            archive.delete();
            // Never clean rootfs here: a failed or cancelled install must preserve it.
            deleteTree(staging);
        }
    }

    /** Non-blocking cancellation; the worker performs process reaping and staging cleanup. */
    public void cancel() {
        synchronized (processLock) {
            cancellationEpoch.incrementAndGet();
            if (activeProcess != null && activeProcess.isAlive()) activeProcess.destroy();
        }
    }

    public Process startClaude(String prompt, String baseUrl, String model, String apiKey,
                               int bridgePort, String bridgeToken) throws Exception {
        final long operation = cancellationEpoch.get();
        if (!isInstalled()) throw new IOException("请先准备运行环境");
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IOException("请先填写模型 API Key");
        updateBridge();
        updateDns();
        List<String> args = new ArrayList<>(Arrays.asList(
                "/usr/local/bin/claude", "-p", prompt,
                "--output-format", "stream-json", "--verbose",
                "--tools", "", "--allowedTools", "mcp__phone__*",
                "--permission-mode", "dontAsk", "--strict-mcp-config",
                "--mcp-config", "/opt/phoneagent/mcp.json", "--max-turns", "30",
                "--append-system-prompt",
                "你是用户手机上的简洁助手。请用中文回答，按用户要求通过 phone MCP 工具操作手机。"
                + "先观察界面，再执行动作，再读取界面确认实际结果。不要把工具请求成功说成任务完成。"
                + "任务开始先用 list_routines 查看可复用脚本和已验证操作；有合适脚本优先 run_routine。"
                + "打开应用优先用 open_app_ready，它在本地等待加载并返回最终 screen；直接利用这个界面继续决策，不必机械地再读一次。"
                + "遇到 blocked 或错误要依据返回的原因调整；同一动作两次没有进展时换方法或向用户说明，不能盲目重复。"
                + "反复使用的基础导航操作，只有实际重复跑通并核对最终界面后，才用 save_routine 和真实 routine_receipt 整理成可复用脚本。"
                + "优先保存打开应用、等待就绪、导航到稳定页面等短流程；使用清晰名称、前台应用检查和明确的最终验证条件。"
                + "不得编造成功凭据，不把临时广告、账号登录、密码验证码、支付或订单确认固化为基础脚本；脚本失败时依据实际 screen 处理。"
                + "每个任务最多30轮、5分钟，优先完成用户目标，有预算再整理已验证脚本，并简要告诉用户新增或复用了什么。"
                + "只有这些手机工具可用；不能执行电脑或终端操作。遇到权限或锁屏限制请直接说明。"));
        if (model != null && !model.trim().isEmpty()) {
            args.add("--model"); args.add(model.trim());
        }
        Map<String, String> environment = new HashMap<>();
        environment.put("ANTHROPIC_API_KEY", apiKey.trim());
        if (baseUrl != null && !baseUrl.trim().isEmpty())
            environment.put("ANTHROPIC_BASE_URL", baseUrl.trim());
        environment.put("PHONE_AGENT_PORT", Integer.toString(bridgePort));
        environment.put("PHONE_AGENT_TOKEN", bridgeToken);
        return start(args, environment, root, operation);
    }

    public String version() throws Exception {
        return version(root, cancellationEpoch.get());
    }

    private String version(File runtimeRoot, long operation) throws Exception {
        Process process = start(Arrays.asList("/usr/local/bin/claude", "--version"),
                Collections.<String, String>emptyMap(), runtimeRoot, operation);
        String result = collect(process, 45, operation).trim();
        if (process.exitValue() != 0) throw new IOException("Claude Code 无法启动：" + result);
        return result;
    }

    private Process start(List<String> command, Map<String, String> extra,
                          File runtimeRoot, long operation) throws IOException {
        checkCancelled(operation);
        mkdir(home); mkdir(bridge); mkdir(temp);
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        List<String> args = new ArrayList<>(Arrays.asList(nativeDir + "/libproot.so",
                "--kill-on-exit", "-r", runtimeRoot.getAbsolutePath(), "-i", "1000:1000",
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", home.getAbsolutePath() + ":/home/agent",
                "-b", bridge.getAbsolutePath() + ":/opt/phoneagent",
                "-b", temp.getAbsolutePath() + ":/tmp", "-w", "/home/agent",
                "/usr/bin/env", "-u", "LD_LIBRARY_PATH", "-u", "PROOT_LOADER",
                "-u", "PROOT_LOADER_32", "-u", "PROOT_TMP_DIR", "-u", "PROOT_NO_SECCOMP",
                "-u", "ANDROID_ROOT", "-u", "ANDROID_DATA"));
        // The host environment is a strict allowlist. Secrets remain environment entries,
        // never command-line arguments or files. Guest env removes Android linker settings.
        args.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("HOME", "/home/agent");
        env.put("USER", "agent");
        env.put("LOGNAME", "agent");
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "dumb");
        env.put("TMPDIR", "/tmp");
        env.put("TZ", "Asia/Shanghai");
        env.put("CI", "1");
        env.put("DISABLE_AUTOUPDATER", "1");
        env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        env.put("NODE_EXTRA_CA_CERTS", "/etc/ssl/certs/ca-certificates.crt");
        env.putAll(extra);
        env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("PROOT_LOADER", nativeDir + "/libproot-loader.so");
        env.put("PROOT_LOADER_32", nativeDir + "/libproot-loader32.so");
        env.put("PROOT_TMP_DIR", temp.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("ANDROID_ROOT", "/system");
        env.put("ANDROID_DATA", "/data");
        builder.directory(home);
        Process process = builder.start();
        registerProcess(process, operation);
        return process;
    }

    private void updateBridge() throws Exception {
        mkdir(bridge);
        for (String name : new String[]{"phone_mcp.py", "phone_routines.py"}) {
            try (InputStream input = context.getAssets().open(name);
                 OutputStream output = new FileOutputStream(new File(bridge, name))) {
                copy(input, output);
            }
        }
        JSONObject server = new JSONObject();
        server.put("command", "/usr/bin/python3");
        server.put("args", new JSONArray().put("/opt/phoneagent/phone_mcp.py"));
        JSONObject servers = new JSONObject().put("phone", server);
        write(new File(bridge, "mcp.json"), new JSONObject().put("mcpServers", servers).toString());
    }

    private void updateDns() throws IOException {
        updateDns(root);
    }

    private void updateDns(File runtimeRoot) throws IOException {
        StringBuilder dns = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork();
            LinkProperties properties = cm.getLinkProperties(network);
            if (properties != null) for (InetAddress address : properties.getDnsServers())
                dns.append("nameserver ").append(address.getHostAddress()).append('\n');
        } catch (Exception ignored) { }
        if (dns.length() == 0) dns.append("nameserver 223.5.5.5\nnameserver 1.1.1.1\n");
        write(new File(runtimeRoot, "etc/resolv.conf"), dns.toString());
    }

    private String collect(Process process, long seconds, long operation) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try { copy(process.getInputStream(), output); } catch (IOException ignored) { }
        }, "linux-output");
        reader.setDaemon(true); reader.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                checkCancelled(operation);
                if (System.nanoTime() >= deadline)
                    throw new IOException("运行环境操作超时：" + output.toString("UTF-8"));
            }
            checkCancelled(operation);
            reader.join(1000);
            return output.toString("UTF-8");
        } finally {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(1000, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException interrupted) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (processLock) {
                if (activeProcess == process) activeProcess = null;
            }
        }
    }

    private void registerProcess(Process process, long operation) throws IOException {
        synchronized (processLock) {
            if (cancellationEpoch.get() != operation) {
                process.destroyForcibly();
                throw new IOException("操作已停止");
            }
            activeProcess = process;
        }
    }

    private void checkCancelled(long operation) throws IOException {
        if (cancellationEpoch.get() != operation || Thread.currentThread().isInterrupted())
            throw new IOException("操作已停止");
    }

    private void copyCancellable(InputStream input, OutputStream output, long operation) throws IOException {
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled(operation);
            output.write(buffer, 0, count);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }
    private static String read(File path) throws IOException {
        try (InputStream input = new FileInputStream(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(input, out); return out.toString("UTF-8");
        }
    }
    private static void write(File path, String value) throws IOException {
        mkdir(path.getParentFile());
        try (OutputStream out = new FileOutputStream(path)) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
    }
    private static void mkdir(File path) throws IOException {
        if (!path.isDirectory() && !path.mkdirs()) throw new IOException("无法创建目录：" + path.getName());
    }
    private static void deleteTree(File path) throws IOException {
        if (!path.exists() && !java.nio.file.Files.isSymbolicLink(path.toPath())) return;
        if (!java.nio.file.Files.isSymbolicLink(path.toPath()) && path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!path.delete()) throw new IOException("无法清理运行环境：" + path.getName());
    }
}
