package com.guichaguri.trackplayer.service.player;

public final class AudioOutputController {

    public enum AudioOutputPhase {
        NORMAL,
        OFFLOAD_FAILED,
        COMPATIBILITY_MODE
    }

    public enum RecoveryAction {
        IN_INSTANCE_RECOVERED,
        REBUILD_REQUIRED,
        UNRECOVERED,
        IGNORED
    }

    public interface PlayerAdapter extends PlaybackSnapshot.StateAdapter {
        boolean isAudioOffloadEnabled();
        void setAudioOffloadEnabled(boolean enabled);
        void prepare();
        void release();
        default void activate() {}
        default void validateReady() {}
        default ExoPlayback.PlayerPhase getPlayerPhase() {
            return ExoPlayback.PlayerPhase.IDLE;
        }
        default void beginRecovery() {}
        default void endRecovery() {}
    }

    private final PlayerAdapter player;
    private boolean inInstanceRecoveryUsed;
    private AudioOutputPhase phase = AudioOutputPhase.NORMAL;

    public AudioOutputController(PlayerAdapter player) {
        this(player, AudioOutputPhase.NORMAL, false);
    }

    private AudioOutputController(
            PlayerAdapter player,
            AudioOutputPhase phase,
            boolean inInstanceRecoveryUsed
    ) {
        this.player = player;
        this.phase = phase;
        this.inInstanceRecoveryUsed = inInstanceRecoveryUsed;
    }

    public static AudioOutputController compatibilityMode(PlayerAdapter player) {
        return new AudioOutputController(player, AudioOutputPhase.COMPATIBILITY_MODE, true);
    }

    public boolean isEffectiveAudioOffloadEnabled() {
        return player.isAudioOffloadEnabled();
    }

    public AudioOutputPhase getPhase() {
        return phase;
    }

    public boolean hasUsedInInstanceRecovery() {
        return inInstanceRecoveryUsed;
    }

    public RecoveryAction onPlaybackError(String domain, String reason) {
        return RecoveryAction.IGNORED;
    }

    public RecoveryAction onAudioSinkError(PlaybackSnapshot.UserPlayIntent userPlayIntent) {
        if (!inInstanceRecoveryUsed && !player.isAudioOffloadEnabled()) {
            return RecoveryAction.IGNORED;
        }

        phase = AudioOutputPhase.OFFLOAD_FAILED;

        if (!inInstanceRecoveryUsed && player.isAudioOffloadEnabled()) {
            inInstanceRecoveryUsed = true;
            PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player, userPlayIntent);
            player.beginRecovery();
            try {
                player.setAudioOffloadEnabled(false);
                player.prepare();
                snapshot.restorePositionAndIntent(player);
            } finally {
                player.endRecovery();
            }
            phase = AudioOutputPhase.COMPATIBILITY_MODE;
            return RecoveryAction.IN_INSTANCE_RECOVERED;
        }

        if (inInstanceRecoveryUsed) {
            return RecoveryAction.REBUILD_REQUIRED;
        }

        return RecoveryAction.IGNORED;
    }
}
