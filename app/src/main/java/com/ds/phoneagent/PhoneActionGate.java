package com.ds.phoneagent;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** A task-scoped generation gate. Closing it invalidates every previously captured action. */
final class PhoneActionGate {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> idleCallbacks = new ArrayList<>();
    private long epoch = 1;
    private boolean open;
    private int inFlight;
    private boolean idleCheckPosted;

    synchronized void close() {
        open = false;
        epoch++;
        postIdleCheck();
    }

    synchronized void open() {
        epoch++;
        open = true;
    }

    synchronized boolean isIdle() { return inFlight == 0; }

    synchronized long capture() { return open ? epoch : -1; }

    synchronized boolean isAllowed(long ticket) { return open && ticket > 0 && ticket == epoch; }

    /** Always runs on the main thread, after any dispatched gesture's real callback. */
    void whenIdle(Runnable callback) {
        if (callback == null) return;
        synchronized (this) {
            idleCallbacks.add(callback);
            postIdleCheck();
        }
    }

    // The executor holds this monitor through begin() and the actual dispatch. close()
    // therefore cannot return between a successful check and a newly dispatched action.
    synchronized boolean begin(long ticket) {
        if (!isAllowed(ticket)) return false;
        inFlight++;
        return true;
    }

    synchronized void finish() {
        if (inFlight <= 0) throw new IllegalStateException("No phone action is in flight");
        inFlight--;
        postIdleCheck();
    }

    private void postIdleCheck() {
        if (inFlight != 0 || idleCallbacks.isEmpty() || idleCheckPosted) return;
        idleCheckPosted = true;
        main.post(this::notifyIdle);
    }

    private void notifyIdle() {
        List<Runnable> callbacks;
        synchronized (this) {
            idleCheckPosted = false;
            if (inFlight != 0) return;
            callbacks = new ArrayList<>(idleCallbacks);
            idleCallbacks.clear();
        }
        // Phone dispatch and these callbacks share the main thread. Keep controller/UI
        // callbacks outside the monitor so they cannot invert the controller's lock order.
        for (Runnable callback : callbacks) {
            try { callback.run(); }
            catch (RuntimeException failure) { Log.e("PhoneActionGate", "Idle callback failed", failure); }
        }
    }
}
