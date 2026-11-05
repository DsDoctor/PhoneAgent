package com.ds.phoneagent;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Reaps one captured native child tree, including tracees reparented after PRoot exits. */
final class ProcessLifecycle {
    static final class CleanupException extends RuntimeException {
        CleanupException(String message) { super(message); }
    }
    private static final Map<Process, Tree> TREES = new WeakHashMap<>();
    private static final long ATTEMPT_MS = 6000;

    /** Call before closing stdin or sending any termination signal. Never signals a process. */
    static void capture(Process process) {
        if (process == null) return;
        Tree tree = tree(process);
        synchronized (tree) { refresh(tree); }
    }

    /** Worker-thread only. A failed bounded attempt retains its fingerprints for a safe retry. */
    static void terminate(Process process) {
        if (process == null) return;
        Tree tree = tree(process);
        boolean[] interrupted = {Thread.interrupted()};
        synchronized (tree) {
            while (tree.terminating) {
                try { tree.wait(100); } catch (InterruptedException stopped) { interrupted[0] = true; }
            }
            if (tree.terminated) {
                if (interrupted[0]) Thread.currentThread().interrupt();
                return;
            }
            tree.terminating = true;
        }
        try {
            long deadline = SystemClock.elapsedRealtime() + ATTEMPT_MS;
            synchronized (tree) { refresh(tree); }
            if (tree.root == null && process.isAlive()) throw new CleanupException("无法确认执行进程身份，未发送不确定的信号。");
            // Keep the tracer alive while its tracees receive their exit signals and are reaped.
            signalDescendants(tree, OsConstants.SIGTERM);
            long grace = Math.min(deadline, SystemClock.elapsedRealtime() + 350);
            while (SystemClock.elapsedRealtime() < grace && hasLiveDescendants(tree)) {
                sleep(50, interrupted);
                synchronized (tree) { refresh(tree); }
            }
            while (hasLiveDescendants(tree) && SystemClock.elapsedRealtime() < deadline - 1200) {
                signalDescendants(tree, OsConstants.SIGKILL);
                sleep(75, interrupted);
                synchronized (tree) { refresh(tree); }
            }
            if (hasLiveDescendants(tree)) throw new CleanupException("执行子进程尚未退出，保留追踪身份等待再次清理。");
            signal(tree.root, OsConstants.SIGTERM);
            long parentGrace = Math.min(deadline, SystemClock.elapsedRealtime() + 350);
            while (live(tree.root) && SystemClock.elapsedRealtime() < parentGrace) sleep(50, interrupted);
            while ((live(tree.root) || process.isAlive()) && SystemClock.elapsedRealtime() < deadline) {
                synchronized (tree) { refresh(tree); }
                // A child created during the grace period must be killed before its tracer.
                signalDescendants(tree, OsConstants.SIGKILL);
                if (!hasLiveDescendants(tree)) signal(tree.root, OsConstants.SIGKILL);
                try { process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException stopped) { interrupted[0] = true; }
            }
            synchronized (tree) { refresh(tree); }
            if (live(tree.root) || hasLiveDescendants(tree) || process.isAlive())
                throw new CleanupException("执行进程尚未完全退出，将继续清理；任务未宣告停止完成。");
            synchronized (tree) { tree.terminated = true; }
        } finally {
            synchronized (tree) { tree.terminating = false; tree.notifyAll(); }
            if (interrupted[0]) Thread.currentThread().interrupt();
        }
    }

    static int pid(Process process) {
        try { return ((Number) Process.class.getMethod("pid").invoke(process)).intValue(); }
        catch (Exception unavailable) {
            for (Class<?> kind = process.getClass(); kind != null; kind = kind.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = kind.getDeclaredField("pid");
                    field.setAccessible(true);
                    return ((Number) field.get(process)).intValue();
                } catch (Exception unsupported) { /* Try the parent implementation. */ }
            }
            return -1;
        }
    }

    private static Tree tree(Process process) {
        synchronized (TREES) {
            Tree found = TREES.get(process);
            if (found == null) {
                found = new Tree(process, pid(process));
                TREES.put(process, found);
            }
            return found;
        }
    }

    private static final class Tree {
        final int rootPid;
        final WeakReference<Process> original;
        Node root;
        final Map<Integer, Node> members = new LinkedHashMap<>();
        boolean terminating, terminated;
        Tree(Process process, int rootPid) { this.rootPid = rootPid; original = new WeakReference<>(process); }
    }

    private static final class Node {
        final int pid, ppid, tracer, uid;
        final long start;
        final char state;
        int depth;
        Node(int pid, int ppid, int tracer, int uid, long start, char state) {
            this.pid = pid; this.ppid = ppid; this.tracer = tracer; this.uid = uid;
            this.start = start; this.state = state;
        }
        boolean same(Node other) { return other != null && pid == other.pid && start == other.start && uid == other.uid; }
    }

    /** Only PPid/TracerPid links from still-matching captured members establish membership. */
    private static void refresh(Tree tree) {
        if (tree.terminated || tree.rootPid <= 0 || tree.rootPid == android.os.Process.myPid()) return;
        Map<Integer, Node> current = snapshot();
        if (tree.root == null) {
            Process original = tree.original.get();
            if (original == null || !original.isAlive()) return;
            Node root = read(tree.rootPid);
            if (root == null || root.uid != android.os.Process.myUid() || !original.isAlive()) return;
            tree.root = root;
            tree.members.put(root.pid, root);
            current.put(root.pid, root);
        }
        boolean changed;
        do {
            changed = false;
            for (Node candidate : current.values()) {
                if (candidate.pid == android.os.Process.myPid() || candidate.pid == tree.rootPid) continue;
                Node known = tree.members.get(candidate.pid);
                if (candidate.same(known)) continue;
                Node parent = tree.members.get(candidate.ppid);
                if (parent == null || !parent.same(current.get(parent.pid))) parent = tree.members.get(candidate.tracer);
                if (parent != null && parent.same(current.get(parent.pid))) {
                    candidate.depth = parent.depth + 1;
                    tree.members.put(candidate.pid, candidate);
                    changed = true;
                }
            }
        } while (changed);
    }

    private static Map<Integer, Node> snapshot() {
        Map<Integer, Node> found = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) throw new CleanupException("无法读取进程身份，未发送不确定的信号。");
        for (File entry : entries) {
            String name = entry.getName();
            if (!name.matches("[1-9][0-9]*")) continue;
            try {
                Node node = read(Integer.parseInt(name));
                if (node != null && node.uid == android.os.Process.myUid()) found.put(node.pid, node);
            } catch (NumberFormatException ignored) { }
        }
        return found;
    }

    private static Node read(int pid) {
        if (pid <= 0) return null;
        try {
            String stat;
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/stat"))) { stat = reader.readLine(); }
            if (stat == null) return null;
            // comm may itself contain whitespace or parentheses; fields begin after its final ')'.
            int end = stat.lastIndexOf(')');
            if (end < 0) return null;
            String[] fields = stat.substring(end + 1).trim().split("\\s+");
            if (fields.length < 20) return null;
            int uid = -1, tracer = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Uid:")) uid = Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                    else if (line.startsWith("TracerPid:")) tracer = Integer.parseInt(line.substring(10).trim());
                }
            }
            if (uid < 0) return null;
            return new Node(pid, Integer.parseInt(fields[1]), tracer, uid, Long.parseLong(fields[19]), fields[0].charAt(0));
        } catch (Exception unavailable) { return null; }
    }

    private static boolean live(Node expected) {
        Node now = current(expected);
        return expected != null && expected.same(now) && now.state != 'Z' && now.state != 'X';
    }

    private static Node current(Node expected) {
        if (expected == null) return null;
        Node value = read(expected.pid);
        if (value == null && new File("/proc/" + expected.pid).exists())
            throw new CleanupException("已记录进程的身份暂时不可读，未发送信号或宣告退出。");
        return value;
    }

    private static boolean hasLiveDescendants(Tree tree) {
        synchronized (tree) {
            for (Node node : tree.members.values()) if (node != tree.root && live(node)) return true;
            return false;
        }
    }

    private static void signalDescendants(Tree tree, int signal) {
        List<Node> nodes;
        synchronized (tree) { nodes = new ArrayList<>(tree.members.values()); }
        Collections.sort(nodes, (a, b) -> Integer.compare(b.depth, a.depth));
        for (Node node : nodes) if (node != tree.root) signal(node, signal);
    }

    private static void signal(Node expected, int signal) {
        if (expected == null || expected.pid == android.os.Process.myPid()) return;
        Node current = current(expected);
        if (!expected.same(current) || current.state == 'Z' || current.state == 'X') return;
        try { Os.kill(expected.pid, signal); }
        catch (ErrnoException failure) {
            if (failure.errno != OsConstants.ESRCH) throw new CleanupException("无法终止已确认的执行进程，将保留身份重试。");
        }
    }

    private static void sleep(long millis, boolean[] interrupted) {
        try { Thread.sleep(millis); } catch (InterruptedException stopped) { interrupted[0] = true; }
    }
}
