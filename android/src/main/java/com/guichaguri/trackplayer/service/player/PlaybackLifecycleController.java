package com.guichaguri.trackplayer.service.player;

public final class PlaybackLifecycleController {

    public interface SerialQueue {
        void post(Runnable task);
        void clearPending();
    }

    public interface PlayerFactory {
        AudioOutputController.PlayerAdapter create(boolean audioOffloadEnabled);
    }

    public interface PlayerOwner {
        void install(AudioOutputController.PlayerAdapter player);
        void swap(
                AudioOutputController.PlayerAdapter oldPlayer,
                AudioOutputController.PlayerAdapter newPlayer
        );
        void clear(AudioOutputController.PlayerAdapter player);
    }

    public interface Listener {
        void onCompatibilityChanged(AudioOutputCompatibilityEvent event);
        void onUnrecoveredAudioSinkError();
    }

    private final SerialQueue serialQueue;
    private final PlayerFactory playerFactory;
    private final PlayerOwner playerOwner;
    private final Listener listener;

    private AudioOutputController.PlayerAdapter player;
    private AudioOutputController audioOutputController;
    private PlaybackSnapshot.UserPlayIntent userPlayIntent = PlaybackSnapshot.UserPlayIntent.PAUSE;
    private boolean rebuildUsed;
    private boolean destroyRequested;

    public PlaybackLifecycleController(
            SerialQueue serialQueue,
            PlayerFactory playerFactory,
            PlayerOwner playerOwner,
            Listener listener
    ) {
        this.serialQueue = serialQueue;
        this.playerFactory = playerFactory;
        this.playerOwner = playerOwner;
        this.listener = listener;
    }

    public synchronized void setup(boolean audioOffloadEnabled) {
        if (destroyRequested) return;
        serialQueue.post(() -> {
            if (destroyRequested) return;
            AudioOutputController.PlayerAdapter created = playerFactory.create(audioOffloadEnabled);
            AudioOutputController.PlayerAdapter previous = player;
            try {
                created.activate();
                created.validateReady();
                if (previous == null) {
                    playerOwner.install(created);
                } else {
                    playerOwner.swap(previous, created);
                }
            } catch (RuntimeException error) {
                created.release();
                throw error;
            }
            player = created;
            audioOutputController = new AudioOutputController(created);
            rebuildUsed = false;
            if (previous != null) previous.release();
        });
    }

    public synchronized void setUserPlayIntent(PlaybackSnapshot.UserPlayIntent userPlayIntent) {
        this.userPlayIntent = userPlayIntent;
    }

    public synchronized void onPlaybackError(String domain, String reason) {
        if (destroyRequested) return;
        serialQueue.post(() -> {
            if (destroyRequested || audioOutputController == null) return;
            audioOutputController.onPlaybackError(domain, reason);
        });
    }

    public synchronized void onAudioSinkError() {
        if (destroyRequested) return;
        serialQueue.post(() -> {
            if (destroyRequested || audioOutputController == null || player == null) return;

            if (rebuildUsed) {
                listener.onUnrecoveredAudioSinkError();
                return;
            }

            AudioOutputController.RecoveryAction action =
                    audioOutputController.onAudioSinkError(userPlayIntent);
            if (action == AudioOutputController.RecoveryAction.IN_INSTANCE_RECOVERED) {
                listener.onCompatibilityChanged(new AudioOutputCompatibilityEvent(true, false));
                return;
            }
            if (action == AudioOutputController.RecoveryAction.REBUILD_REQUIRED && !rebuildUsed) {
                rebuildUsed = true;
                rebuildCurrentPlayer();
                return;
            }
            if (action == AudioOutputController.RecoveryAction.REBUILD_REQUIRED
                    || action == AudioOutputController.RecoveryAction.UNRECOVERED) {
                listener.onUnrecoveredAudioSinkError();
            }
        });
    }

    private void rebuildCurrentPlayer() {
        AudioOutputController.PlayerAdapter oldPlayer = player;
        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(oldPlayer, userPlayIntent);
        AudioOutputController.PlayerAdapter rebuiltPlayer = null;
        try {
            rebuiltPlayer = playerFactory.create(false);
            rebuiltPlayer.beginRecovery();
            try {
                snapshot.restore(rebuiltPlayer);
            } finally {
                rebuiltPlayer.endRecovery();
            }
            rebuiltPlayer.activate();
            rebuiltPlayer.validateReady();
            playerOwner.swap(oldPlayer, rebuiltPlayer);
            player = rebuiltPlayer;
            audioOutputController = AudioOutputController.compatibilityMode(rebuiltPlayer);
        } catch (RuntimeException error) {
            if (rebuiltPlayer != null) rebuiltPlayer.release();
            listener.onUnrecoveredAudioSinkError();
            return;
        }
        oldPlayer.release();
        listener.onCompatibilityChanged(new AudioOutputCompatibilityEvent(true, true));
    }

    public synchronized PlaybackSnapshot.UserPlayIntent getUserPlayIntent() {
        return userPlayIntent;
    }

    public synchronized ExoPlayback.PlayerPhase getPlayerPhase() {
        return player == null ? ExoPlayback.PlayerPhase.IDLE : player.getPlayerPhase();
    }

    public synchronized AudioOutputController.AudioOutputPhase getAudioOutputPhase() {
        return audioOutputController == null
                ? AudioOutputController.AudioOutputPhase.NORMAL
                : audioOutputController.getPhase();
    }

    public synchronized boolean isEffectiveAudioOffloadEnabled() {
        return audioOutputController != null && audioOutputController.isEffectiveAudioOffloadEnabled();
    }

    public synchronized boolean hasUsedInInstanceRecovery() {
        return audioOutputController != null && audioOutputController.hasUsedInInstanceRecovery();
    }

    public synchronized boolean hasUsedRebuildRecovery() {
        return rebuildUsed;
    }

    public synchronized void destroy() {
        if (destroyRequested) return;
        destroyRequested = true;
        serialQueue.clearPending();
        serialQueue.post(() -> {
            AudioOutputController.PlayerAdapter current = player;
            if (current == null) return;
            playerOwner.clear(current);
            current.release();
            player = null;
            audioOutputController = null;
        });
    }
}
