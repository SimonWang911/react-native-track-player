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
        default void beginRecovery() {}
        default void endRecovery() {}
    }

    private final PlayerAdapter player;
    private boolean inInstanceRecoveryUsed;
    private AudioOutputPhase phase = AudioOutputPhase.NORMAL;

    public AudioOutputController(PlayerAdapter player) {
        this.player = player;
    }

    public boolean isEffectiveAudioOffloadEnabled() {
        return player.isAudioOffloadEnabled();
    }

    public AudioOutputPhase getPhase() {
        return phase;
    }

    public RecoveryAction onPlaybackError(String domain, String reason) {
        return RecoveryAction.IGNORED;
    }

    public RecoveryAction onAudioSinkError(PlaybackSnapshot.UserPlayIntent userPlayIntent) {
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
