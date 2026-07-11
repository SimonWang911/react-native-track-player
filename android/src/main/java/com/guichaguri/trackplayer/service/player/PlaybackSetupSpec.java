package com.guichaguri.trackplayer.service.player;

/**
 * Immutable configuration owned by one player generation.
 *
 * While setup is in flight, the lifecycle uses the first request's complete spec. Later requests
 * join its completion callbacks without changing any field used to create that generation.
 */
public final class PlaybackSetupSpec {
    private final boolean audioOffloadEnabled;
    private final boolean autoUpdateMetadata;
    private final boolean handleAudioFocus;
    private final int minBufferMs;
    private final int maxBufferMs;
    private final int playBufferMs;
    private final int backBufferMs;
    private final long maxCacheSizeBytes;

    public PlaybackSetupSpec(
            boolean audioOffloadEnabled,
            boolean autoUpdateMetadata,
            boolean handleAudioFocus,
            int minBufferMs,
            int maxBufferMs,
            int playBufferMs,
            int backBufferMs,
            long maxCacheSizeBytes
    ) {
        this.audioOffloadEnabled = audioOffloadEnabled;
        this.autoUpdateMetadata = autoUpdateMetadata;
        this.handleAudioFocus = handleAudioFocus;
        this.minBufferMs = minBufferMs;
        this.maxBufferMs = maxBufferMs;
        this.playBufferMs = playBufferMs;
        this.backBufferMs = backBufferMs;
        this.maxCacheSizeBytes = maxCacheSizeBytes;
    }

    public static PlaybackSetupSpec audioOffloadOnly(boolean audioOffloadEnabled) {
        return new PlaybackSetupSpec(
                audioOffloadEnabled,
                true,
                true,
                0,
                0,
                0,
                0,
                0
        );
    }

    public PlaybackSetupSpec withAudioOffloadEnabled(boolean enabled) {
        return new PlaybackSetupSpec(
                enabled,
                autoUpdateMetadata,
                handleAudioFocus,
                minBufferMs,
                maxBufferMs,
                playBufferMs,
                backBufferMs,
                maxCacheSizeBytes
        );
    }

    public boolean isAudioOffloadEnabled() {
        return audioOffloadEnabled;
    }

    public boolean shouldAutoUpdateMetadata() {
        return autoUpdateMetadata;
    }

    public boolean shouldHandleAudioFocus() {
        return handleAudioFocus;
    }

    public int getMinBufferMs() {
        return minBufferMs;
    }

    public int getMaxBufferMs() {
        return maxBufferMs;
    }

    public int getPlayBufferMs() {
        return playBufferMs;
    }

    public int getBackBufferMs() {
        return backBufferMs;
    }

    public long getMaxCacheSizeBytes() {
        return maxCacheSizeBytes;
    }
}
