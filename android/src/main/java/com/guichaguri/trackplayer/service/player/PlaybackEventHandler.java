package com.guichaguri.trackplayer.service.player;

import com.guichaguri.trackplayer.service.errors.StructuredPlaybackError;
import com.guichaguri.trackplayer.service.models.Track;

public interface PlaybackEventHandler {
    default void onTrackMetadataChanged(ExoPlayback<?> playback, Track track, boolean playing) {}
    default void onUserPlayIntentChanged(PlaybackSnapshot.UserPlayIntent userPlayIntent) {}
    default void onTrackUpdate(Integer previousIndex, long previousPosition, Integer nextIndex, Track next) {}
    default void onAudioFocusChange(boolean permanent, boolean paused, boolean ducking) {}
    default void onError(StructuredPlaybackError error) {}
    default void onPlay() {}
    default void onPause() {}
    default void onStop() {}
    default void onStateChange(int state) {}
    default void onEnd(Integer previousIndex, long previousPosition) {}
    default void onMetadataReceived(
            String source,
            String title,
            String url,
            String artist,
            String album,
            String date,
            String genre
    ) {}
    default void onAudioSinkError() {}
    default void onReset() {}
}
