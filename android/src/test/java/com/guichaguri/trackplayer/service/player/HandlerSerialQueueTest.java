package com.guichaguri.trackplayer.service.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class HandlerSerialQueueTest {

    @Test
    public void thrownTaskIsReportedAndLaterTasksStillDrain() {
        List<RuntimeException> errors = new ArrayList<>();
        List<String> operations = new ArrayList<>();
        HandlerSerialQueue queue = new HandlerSerialQueue(
                new Handler(Looper.getMainLooper()),
                new Object(),
                errors::add
        );

        queue.post(() -> {
            operations.add("failing");
            throw new IllegalStateException("task failed");
        });
        queue.post(() -> operations.add("terminal"));
        queue.post(() -> operations.add("destroy"));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, errors.size());
        assertEquals("task failed", errors.get(0).getMessage());
        assertEquals(3, operations.size());
        assertTrue(operations.indexOf("failing") < operations.indexOf("terminal"));
        assertTrue(operations.indexOf("terminal") < operations.indexOf("destroy"));
    }
}
