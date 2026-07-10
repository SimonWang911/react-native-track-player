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
        void onCompatibilityChanged(boolean recovered, boolean rebuilt);
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
            player = created;
            audioOutputController = new AudioOutputController(created);
            rebuildUsed = false;
            if (previous == null) {
                created.activate();
                playerOwner.install(created);
            } else {
                previous.release();
                created.activate();
                playerOwner.swap(previous, created);
            }
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
                listener.onCompatibilityChanged(true, false);
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
            snapshot.restore(rebuiltPlayer);
            rebuiltPlayer.endRecovery();
            oldPlayer.release();
            rebuiltPlayer.activate();
            playerOwner.swap(oldPlayer, rebuiltPlayer);
            player = rebuiltPlayer;
            audioOutputController = new AudioOutputController(rebuiltPlayer);
            listener.onCompatibilityChanged(true, true);
        } catch (RuntimeException error) {
            if (rebuiltPlayer != null) rebuiltPlayer.release();
            listener.onUnrecoveredAudioSinkError();
        }
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
