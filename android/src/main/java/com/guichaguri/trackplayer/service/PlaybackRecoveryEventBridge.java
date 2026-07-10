package com.guichaguri.trackplayer.service;

import com.guichaguri.trackplayer.module.MusicEvents;
import com.guichaguri.trackplayer.service.errors.StructuredPlaybackError;
import com.guichaguri.trackplayer.service.player.AudioOutputCompatibilityEvent;
import com.guichaguri.trackplayer.service.player.PlaybackLifecycleController;

public final class PlaybackRecoveryEventBridge implements PlaybackLifecycleController.Listener {
    private final MusicService service;

    public PlaybackRecoveryEventBridge(MusicService service) {
        this.service = service;
    }

    @Override
    public void onCompatibilityChanged(AudioOutputCompatibilityEvent event) {
        service.emit(MusicEvents.PLAYBACK_AUDIO_OUTPUT_COMPATIBILITY, event.toBundle());
    }

    @Override
    public void onUnrecoveredAudioSinkError() {
        StructuredPlaybackError error = new StructuredPlaybackError(
                "audio_sink_offload_failed",
                "Audio output failed after compatibility recovery",
                "audio_output",
                "audio_sink_offload_failed",
                false
        );
        service.emit(MusicEvents.PLAYBACK_ERROR, error.toBundle());
    }
}
