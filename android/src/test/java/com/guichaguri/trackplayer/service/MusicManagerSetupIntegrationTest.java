package com.guichaguri.trackplayer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import com.guichaguri.trackplayer.service.player.AudioOutputController;
import com.guichaguri.trackplayer.service.player.ExoPlayback;
import com.guichaguri.trackplayer.service.player.PlaybackLifecycleController;
import com.guichaguri.trackplayer.service.player.PlaybackSetupSpec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MusicManagerSetupIntegrationTest {

    @Test
    public void concurrentRequestsUseTheCompleteFirstSetupSpecAtomically() {
        ManualSerialQueue queue = new ManualSerialQueue();
        List<PlaybackSetupSpec> createdSpecs = new ArrayList<>();
        RecordingOwner owner = new RecordingOwner();
        PlaybackLifecycleController lifecycleController = new PlaybackLifecycleController(
                queue,
                setupSpec -> {
                    createdSpecs.add(setupSpec);
                    return new RecordingPlayer(setupSpec.isAudioOffloadEnabled());
                },
                owner,
                new NoOpListener()
        );
        MusicService service = Robolectric.buildService(MusicService.class).get();
        MusicManager manager = new MusicManager(service, lifecycleController);
        RecordingSetupCallback firstCallback = new RecordingSetupCallback();
        RecordingSetupCallback secondCallback = new RecordingSetupCallback();
        Bundle first = options(true, false, false, 1.25, 2.5, 3.75, 4.5, 5.0);
        Bundle second = options(false, true, true, 11.0, 12.0, 13.0, 14.0, 15.0);

        manager.setupPlayback(first, firstCallback);
        first.putDouble("minBuffer", 99.0);
        manager.setupPlayback(second, secondCallback);
        queue.runAll();

        assertEquals(1, createdSpecs.size());
        PlaybackSetupSpec created = createdSpecs.get(0);
        assertTrue(created.isAudioOffloadEnabled());
        assertFalse(created.shouldAutoUpdateMetadata());
        assertFalse(created.shouldHandleAudioFocus());
        assertEquals(1_250, created.getMinBufferMs());
        assertEquals(2_500, created.getMaxBufferMs());
        assertEquals(3_750, created.getPlayBufferMs());
        assertEquals(4_500, created.getBackBufferMs());
        assertEquals(5_120L, created.getMaxCacheSizeBytes());
        assertEquals(1, firstCallback.successes);
        assertEquals(1, secondCallback.successes);
        assertSame(owner.current, firstCallback.player);
        assertSame(owner.current, secondCallback.player);
    }

    private static Bundle options(
            boolean audioOffload,
            boolean autoUpdateMetadata,
            boolean handleAudioFocus,
            double minBuffer,
            double maxBuffer,
            double playBuffer,
            double backBuffer,
            double maxCacheSize
    ) {
        Bundle options = new Bundle();
        options.putBoolean("audioOffload", audioOffload);
        options.putBoolean("autoUpdateMetadata", autoUpdateMetadata);
        options.putBoolean("handleAudioFocus", handleAudioFocus);
        options.putDouble("minBuffer", minBuffer);
        options.putDouble("maxBuffer", maxBuffer);
        options.putDouble("playBuffer", playBuffer);
        options.putDouble("backBuffer", backBuffer);
        options.putDouble("maxCacheSize", maxCacheSize);
        return options;
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
            current = newPlayer;
        }

        @Override
        public void clear(AudioOutputController.PlayerAdapter player) {
            if (current == player) current = null;
        }
    }

    private static final class NoOpListener implements PlaybackLifecycleController.Listener {
        @Override
        public void onCompatibilityChanged(
                com.guichaguri.trackplayer.service.player.AudioOutputCompatibilityEvent event
        ) {}

        @Override
        public void onUnrecoveredAudioSinkError() {}
    }

    private static final class RecordingSetupCallback implements PlaybackLifecycleController.SetupCallback {
        int successes;
        AudioOutputController.PlayerAdapter player;

        @Override
        public void onSuccess(AudioOutputController.PlayerAdapter player) {
            successes++;
            this.player = player;
        }

        @Override
        public void onFailure(RuntimeException error) {}
    }

    private static final class RecordingPlayer implements AudioOutputController.PlayerAdapter {
        private boolean audioOffloadEnabled;

        RecordingPlayer(boolean audioOffloadEnabled) {
            this.audioOffloadEnabled = audioOffloadEnabled;
        }

        @Override
        public List<Object> getQueueSnapshot() {
            return new ArrayList<>();
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
            return 0;
        }

        @Override
        public float getVolume() {
            return 1F;
        }

        @Override
        public float getRate() {
            return 1F;
        }

        @Override
        public void restoreQueue(List<Object> queue) {}

        @Override
        public void setRepeatMode(int repeatMode) {}

        @Override
        public void setVolume(float volume) {}

        @Override
        public void setRate(float rate) {}

        @Override
        public void seekTo(int index, long positionMs) {}

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {}

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
        public ExoPlayback.PlayerPhase getPlayerPhase() {
            return ExoPlayback.PlayerPhase.IDLE;
        }

        @Override
        public void release() {}
    }
}
