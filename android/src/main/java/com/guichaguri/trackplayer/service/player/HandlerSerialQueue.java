package com.guichaguri.trackplayer.service.player;

import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayDeque;

public final class HandlerSerialQueue implements PlaybackLifecycleController.SerialQueue {
    private final Handler handler;
    private final Object token;
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean drainScheduled;
    private boolean draining;

    public HandlerSerialQueue(Handler handler, Object token) {
        this.handler = handler;
        this.token = token;
    }

    @Override
    public synchronized void post(Runnable task) {
        tasks.add(task);
        if (draining || drainScheduled) return;
        drainScheduled = true;
        handler.postAtTime(this::drain, token, SystemClock.uptimeMillis());
    }

    @Override
    public synchronized void clearPending() {
        tasks.clear();
        handler.removeCallbacksAndMessages(token);
        if (!draining) drainScheduled = false;
    }

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) {
                drainScheduled = false;
                task = tasks.poll();
                if (task == null) {
                    draining = false;
                    return;
                }
                draining = true;
            }
            task.run();
        }
    }
}
