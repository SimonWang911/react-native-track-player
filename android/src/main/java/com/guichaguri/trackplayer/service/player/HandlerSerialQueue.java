package com.guichaguri.trackplayer.service.player;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.guichaguri.trackplayer.service.Utils;

import java.util.ArrayDeque;
import java.util.function.Consumer;

public final class HandlerSerialQueue implements PlaybackLifecycleController.SerialQueue {
    private final Handler handler;
    private final Object token;
    private final Consumer<RuntimeException> exceptionHandler;
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean drainScheduled;
    private boolean draining;

    public HandlerSerialQueue(Handler handler, Object token) {
        this(
                handler,
                token,
                error -> Log.e(Utils.LOG, "Serialized playback operation failed", error)
        );
    }

    public HandlerSerialQueue(
            Handler handler,
            Object token,
            Consumer<RuntimeException> exceptionHandler
    ) {
        this.handler = handler;
        this.token = token;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public synchronized void post(Runnable task) {
        tasks.add(task);
        if (draining || drainScheduled) return;
        scheduleDrain();
    }

    @Override
    public synchronized void clearPending() {
        tasks.clear();
        handler.removeCallbacksAndMessages(token);
        drainScheduled = false;
    }

    private void drain() {
        Runnable task;
        synchronized (this) {
            drainScheduled = false;
            if (draining) return;
            task = tasks.poll();
            if (task == null) return;
            draining = true;
        }

        try {
            task.run();
        } catch (RuntimeException error) {
            reportException(error);
        } finally {
            synchronized (this) {
                draining = false;
                if (!tasks.isEmpty() && !drainScheduled) scheduleDrain();
            }
        }
    }

    private void scheduleDrain() {
        drainScheduled = true;
        handler.postAtTime(this::drain, token, SystemClock.uptimeMillis());
    }

    private void reportException(RuntimeException error) {
        try {
            exceptionHandler.accept(error);
        } catch (RuntimeException reportingError) {
            Log.e(Utils.LOG, "Serialized playback exception handler failed", reportingError);
        }
    }
}
