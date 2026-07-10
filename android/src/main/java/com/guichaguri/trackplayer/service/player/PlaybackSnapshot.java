package com.guichaguri.trackplayer.service.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlaybackSnapshot {

    public enum UserPlayIntent {
        PLAY,
        PAUSE
    }

    public interface StateAdapter {
        List<Object> getQueueSnapshot();
        int getCurrentIndex();
        long getPositionMs();
        int getRepeatMode();
        float getVolume();
        float getRate();
        void restoreQueue(List<Object> queue);
        void setRepeatMode(int repeatMode);
        void setVolume(float volume);
        void setRate(float rate);
        void seekTo(int index, long positionMs);
        void setPlayWhenReady(boolean playWhenReady);
    }

    private final List<Object> queue;
    private final int currentIndex;
    private final long positionMs;
    private final int repeatMode;
    private final float volume;
    private final float rate;
    private final UserPlayIntent userPlayIntent;

    private PlaybackSnapshot(
            List<Object> queue,
            int currentIndex,
            long positionMs,
            int repeatMode,
            float volume,
            float rate,
            UserPlayIntent userPlayIntent
    ) {
        this.queue = Collections.unmodifiableList(new ArrayList<>(queue));
        this.currentIndex = currentIndex;
        this.positionMs = positionMs;
        this.repeatMode = repeatMode;
        this.volume = volume;
        this.rate = rate;
        this.userPlayIntent = userPlayIntent;
    }

    public static PlaybackSnapshot capture(StateAdapter adapter, UserPlayIntent userPlayIntent) {
        return new PlaybackSnapshot(
                adapter.getQueueSnapshot(),
                adapter.getCurrentIndex(),
                adapter.getPositionMs(),
                adapter.getRepeatMode(),
                adapter.getVolume(),
                adapter.getRate(),
                userPlayIntent
        );
    }

    public void restore(StateAdapter adapter) {
        adapter.restoreQueue(new ArrayList<>(queue));
        adapter.setRepeatMode(repeatMode);
        adapter.setVolume(volume);
        adapter.setRate(rate);
        restorePositionAndIntent(adapter);
    }

    public void restorePositionAndIntent(StateAdapter adapter) {
        adapter.seekTo(currentIndex, positionMs);
        adapter.setPlayWhenReady(userPlayIntent == UserPlayIntent.PLAY);
    }
}
