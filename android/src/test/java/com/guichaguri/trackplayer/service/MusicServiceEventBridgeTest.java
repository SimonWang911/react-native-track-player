package com.guichaguri.trackplayer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.guichaguri.trackplayer.module.MusicEvents;
import com.guichaguri.trackplayer.service.player.AudioOutputController;
import com.guichaguri.trackplayer.service.player.AudioOutputCompatibilityEvent;
import com.guichaguri.trackplayer.service.player.PlaybackLifecycleController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.robolectric.Shadows.shadowOf;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MusicServiceEventBridgeTest {

    @Test
    public void recoveryBridgeDeliversExactStructuredCompatibilityPayloadOnce() {
        MusicService service = Robolectric.buildService(MusicService.class).get();
        LocalBroadcastManager broadcasts = LocalBroadcastManager.getInstance(service);
        List<Intent> received = new ArrayList<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                received.add(intent);
            }
        };
        broadcasts.registerReceiver(receiver, new IntentFilter(Utils.EVENT_INTENT));
        AudioOutputCompatibilityEvent event = new AudioOutputCompatibilityEvent(true, true);
        PlaybackRecoveryEventBridge bridge = new PlaybackRecoveryEventBridge(service);

        bridge.onCompatibilityChanged(event);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, received.size());
        Intent intent = received.get(0);
        assertEquals(
                MusicEvents.PLAYBACK_AUDIO_OUTPUT_COMPATIBILITY,
                intent.getStringExtra("event")
        );
        Bundle payload = intent.getBundleExtra("data");
        assertNotNull(payload);
        assertEquals("audio_sink_offload_failed", payload.getString("reason"));
        assertFalse(payload.getBoolean("effectiveAudioOffload"));
        assertEquals(true, payload.getBoolean("recovered"));
        assertEquals(true, payload.getBoolean("rebuilt"));

        broadcasts.unregisterReceiver(receiver);
    }

    @Test
    public void thirdSinkFailureEmitsOneTerminalPayloadWithoutAnotherRecoveryLoop() {
        MusicService service = Robolectric.buildService(MusicService.class).get();
        LocalBroadcastManager broadcasts = LocalBroadcastManager.getInstance(service);
        List<Intent> received = new ArrayList<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                received.add(intent);
            }
        };
        broadcasts.registerReceiver(receiver, new IntentFilter(Utils.EVENT_INTENT));

        ManualSerialQueue queue = new ManualSerialQueue();
        RecordingFactory factory = new RecordingFactory();
        RecordingOwner owner = new RecordingOwner();
        PlaybackLifecycleController controller = new PlaybackLifecycleController(
                queue,
                factory,
                owner,
                new PlaybackRecoveryEventBridge(service)
        );

        controller.setup(true);
        queue.runAll();
        controller.onAudioSinkError();
        queue.runAll();
        controller.onAudioSinkError();
        queue.runAll();
        controller.onAudioSinkError();
        queue.runAll();
        controller.onAudioSinkError();
        queue.runAll();
        shadowOf(Looper.getMainLooper()).idle();

        List<Intent> compatibilityEvents = eventsNamed(
                received,
                MusicEvents.PLAYBACK_AUDIO_OUTPUT_COMPATIBILITY
        );
        List<Intent> errors = eventsNamed(received, MusicEvents.PLAYBACK_ERROR);
        assertEquals(2, factory.players.size());
        assertEquals(2, compatibilityEvents.size());
        assertEquals(1, errors.size());
        assertEquals(1, factory.players.get(0).releaseCalls);
        assertEquals(0, factory.players.get(1).releaseCalls);
        assertTrue(compatibilityEvents.get(0).getBundleExtra("data").getBoolean("recovered"));
        assertFalse(compatibilityEvents.get(0).getBundleExtra("data").getBoolean("rebuilt"));
        assertTrue(compatibilityEvents.get(1).getBundleExtra("data").getBoolean("rebuilt"));

        Bundle payload = errors.get(0).getBundleExtra("data");
        assertNotNull(payload);
        assertEquals("audio_sink_offload_failed", payload.getString("code"));
        assertEquals("audio_output", payload.getString("domain"));
        assertEquals("audio_sink_offload_failed", payload.getString("reason"));
        assertFalse(payload.getBoolean("recoverable"));

        broadcasts.unregisterReceiver(receiver);
    }

    private static List<Intent> eventsNamed(List<Intent> events, String name) {
        List<Intent> matches = new ArrayList<>();
        for (Intent event : events) {
            if (name.equals(event.getStringExtra("event"))) matches.add(event);
        }
        return matches;
    }

    private static final class ManualSerialQueue implements PlaybackLifecycleController.SerialQueue {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void post(Runnable task) {
            tasks.add(task);
        }

        @Override
        public void clearPending() {
            tasks.clear();
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.remove().run();
        }
    }

    private static final class RecordingFactory implements PlaybackLifecycleController.PlayerFactory {
        final List<RecordingPlayer> players = new ArrayList<>();

        @Override
        public AudioOutputController.PlayerAdapter create(boolean audioOffloadEnabled) {
            RecordingPlayer player = new RecordingPlayer(audioOffloadEnabled);
            players.add(player);
            return player;
        }
    }

    private static final class RecordingOwner implements PlaybackLifecycleController.PlayerOwner {
        AudioOutputController.PlayerAdapter current;

        @Override
        public void install(AudioOutputController.PlayerAdapter player) {
            current = player;
        }

        @Override
        public void swap(
                AudioOutputController.PlayerAdapter oldPlayer,
                AudioOutputController.PlayerAdapter newPlayer
        ) {
            assertEquals(current, oldPlayer);
            current = newPlayer;
        }

        @Override
        public void clear(AudioOutputController.PlayerAdapter player) {
            if (current == player) current = null;
        }
    }

    private static final class RecordingPlayer implements AudioOutputController.PlayerAdapter {
        final List<Object> queue = new ArrayList<>();
        boolean audioOffloadEnabled;
        int repeatMode;
        float volume = 1F;
        float rate = 1F;
        boolean playWhenReady;
        int releaseCalls;

        RecordingPlayer(boolean audioOffloadEnabled) {
            this.audioOffloadEnabled = audioOffloadEnabled;
        }

        @Override
        public List<Object> getQueueSnapshot() {
            return new ArrayList<>(queue);
        }

        @Override
        public int getCurrentIndex() {
            return 0;
        }

        @Override
        public long getPositionMs() {
            return 0;
        }

        @Override
        public int getRepeatMode() {
            return repeatMode;
        }

        @Override
        public float getVolume() {
            return volume;
        }

        @Override
        public float getRate() {
            return rate;
        }

        @Override
        public void restoreQueue(List<Object> restoredQueue) {
            queue.clear();
            queue.addAll(restoredQueue);
        }

        @Override
        public void setRepeatMode(int repeatMode) {
            this.repeatMode = repeatMode;
        }

        @Override
        public void setVolume(float volume) {
            this.volume = volume;
        }

        @Override
        public void setRate(float rate) {
            this.rate = rate;
        }

        @Override
        public void seekTo(int index, long positionMs) {}

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            this.playWhenReady = playWhenReady;
        }

        @Override
        public boolean isAudioOffloadEnabled() {
            return audioOffloadEnabled;
        }

        @Override
        public void setAudioOffloadEnabled(boolean enabled) {
            audioOffloadEnabled = enabled;
        }

        @Override
        public void prepare() {}

        @Override
        public void release() {
            releaseCalls++;
        }
    }
}
