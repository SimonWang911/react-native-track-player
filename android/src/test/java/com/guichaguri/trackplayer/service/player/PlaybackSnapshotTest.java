package com.guichaguri.trackplayer.service.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PlaybackSnapshotTest {

    @Test
    public void captureAndRestorePreservesCompletePlaybackState() {
        FakeStateAdapter source = new FakeStateAdapter();
        source.queue.addAll(Arrays.asList("first", "second", "third"));
        source.index = 1;
        source.positionMs = 42_500L;
        source.repeatMode = 2;
        source.volume = 0.65F;
        source.rate = 1.25F;

        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(
                source,
                PlaybackSnapshot.UserPlayIntent.PLAY
        );

        FakeStateAdapter restored = new FakeStateAdapter();
        snapshot.restore(restored);

        assertEquals(source.queue, restored.queue);
        assertEquals(1, restored.index);
        assertEquals(42_500L, restored.positionMs);
        assertEquals(2, restored.repeatMode);
        assertEquals(0.65F, restored.volume, 0.0001F);
        assertEquals(1.25F, restored.rate, 0.0001F);
        assertTrue(restored.playWhenReady);
    }

    @Test
    public void restorePositionAndIntentDoesNotRewriteQueueOrSettings() {
        FakeStateAdapter source = new FakeStateAdapter();
        source.queue.addAll(Arrays.asList("first", "second"));
        source.index = 1;
        source.positionMs = 9_000L;
        source.repeatMode = 1;
        source.volume = 0.5F;
        source.rate = 1.5F;

        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(
                source,
                PlaybackSnapshot.UserPlayIntent.PAUSE
        );

        FakeStateAdapter current = new FakeStateAdapter();
        current.queue.addAll(source.queue);
        current.repeatMode = source.repeatMode;
        current.volume = source.volume;
        current.rate = source.rate;
        snapshot.restorePositionAndIntent(current);

        assertEquals(source.queue, current.queue);
        assertEquals(1, current.index);
        assertEquals(9_000L, current.positionMs);
        assertEquals(1, current.repeatMode);
        assertEquals(0.5F, current.volume, 0.0001F);
        assertEquals(1.5F, current.rate, 0.0001F);
        assertFalse(current.playWhenReady);
    }

    static class FakeStateAdapter implements PlaybackSnapshot.StateAdapter {
        final List<Object> queue = new ArrayList<>();
        int index;
        long positionMs;
        int repeatMode;
        float volume = 1F;
        float rate = 1F;
        boolean playWhenReady;

        @Override
        public List<Object> getQueueSnapshot() {
            return new ArrayList<>(queue);
        }

        @Override
        public int getCurrentIndex() {
            return index;
        }

        @Override
        public long getPositionMs() {
            return positionMs;
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
        public void restoreQueue(List<Object> queue) {
            this.queue.clear();
            this.queue.addAll(queue);
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
        public void seekTo(int index, long positionMs) {
            this.index = index;
            this.positionMs = positionMs;
        }

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            this.playWhenReady = playWhenReady;
        }
    }
}
