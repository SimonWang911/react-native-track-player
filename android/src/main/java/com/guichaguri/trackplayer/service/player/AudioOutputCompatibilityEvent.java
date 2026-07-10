package com.guichaguri.trackplayer.service.player;

import android.os.Bundle;

public final class AudioOutputCompatibilityEvent {
    public static final String REASON_AUDIO_SINK_OFFLOAD_FAILED = "audio_sink_offload_failed";

    private final boolean recovered;
    private final boolean rebuilt;

    public AudioOutputCompatibilityEvent(boolean recovered, boolean rebuilt) {
        this.recovered = recovered;
        this.rebuilt = rebuilt;
    }

    public String getReason() {
        return REASON_AUDIO_SINK_OFFLOAD_FAILED;
    }

    public boolean isEffectiveAudioOffloadEnabled() {
        return false;
    }

    public boolean isRecovered() {
        return recovered;
    }

    public boolean isRebuilt() {
        return rebuilt;
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString("reason", getReason());
        bundle.putBoolean("effectiveAudioOffload", isEffectiveAudioOffloadEnabled());
        bundle.putBoolean("recovered", recovered);
        bundle.putBoolean("rebuilt", rebuilt);
        return bundle;
    }
}
