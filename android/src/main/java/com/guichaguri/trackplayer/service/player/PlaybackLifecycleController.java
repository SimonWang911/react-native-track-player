package com.guichaguri.trackplayer.service.player;

import java.util.ArrayList;
import java.util.List;

public final class PlaybackLifecycleController {

    public interface SerialQueue {
        void post(Runnable task);
        void clearPending();
    }

    public interface PlayerFactory {
        AudioOutputController.PlayerAdapter create(PlaybackSetupSpec setupSpec);
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

        default void onInternalError(String code, RuntimeException error) {}
    }

    public interface SetupCallback {
        void onSuccess(AudioOutputController.PlayerAdapter player);
        void onFailure(RuntimeException error);
    }

    private static final SetupCallback NO_OP_SETUP_CALLBACK = new SetupCallback() {
        @Override
        public void onSuccess(AudioOutputController.PlayerAdapter player) {}

        @Override
        public void onFailure(RuntimeException error) {}
    };

    private final SerialQueue serialQueue;
    private final PlayerFactory playerFactory;
    private final PlayerOwner playerOwner;
    private final Listener listener;
    private final List<SetupRequest> pendingSetupRequests = new ArrayList<>();
    private final List<AudioOutputController.PlayerAdapter> retainedCleanupPlayers =
            new ArrayList<>();

    private AudioOutputController.PlayerAdapter player;
    private PlaybackSetupSpec playerSetupSpec;
    private AudioOutputController audioOutputController;
    private PlaybackSnapshot.UserPlayIntent userPlayIntent = PlaybackSnapshot.UserPlayIntent.PAUSE;
    private boolean rebuildUsed;
    private boolean terminalErrorReported;
    private boolean setupInFlight;
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
        setup(PlaybackSetupSpec.audioOffloadOnly(audioOffloadEnabled), NO_OP_SETUP_CALLBACK);
    }

    public synchronized void setup(
            boolean audioOffloadEnabled,
            SetupCallback setupCallback
    ) {
        setup(PlaybackSetupSpec.audioOffloadOnly(audioOffloadEnabled), setupCallback);
    }

    public synchronized void setup(
            PlaybackSetupSpec setupSpec,
            SetupCallback setupCallback
    ) {
        SetupRequest setupRequest = new SetupRequest(setupCallback);
        if (destroyRequested) {
            deliverSetupFailure(
                    setupRequest,
                    new IllegalStateException("Playback lifecycle is destroyed")
            );
            return;
        }
        pendingSetupRequests.add(setupRequest);
        if (setupInFlight) return;
        setupInFlight = true;
        serialQueue.post(() -> {
            if (destroyRequested) {
                completePendingSetupFailure(new IllegalStateException("Playback lifecycle is destroyed"));
                return;
            }
            AudioOutputController.PlayerAdapter created = null;
            AudioOutputController.PlayerAdapter previous = player;
            try {
                created = playerFactory.create(setupSpec);
                created.activate();
                created.validateReady();
                if (previous == null) {
                    playerOwner.install(created);
                } else {
                    playerOwner.swap(previous, created);
                }
            } catch (RuntimeException error) {
                releaseFailedGeneration(created, error);
                completePendingSetupFailure(error);
                return;
            }
            player = created;
            playerSetupSpec = setupSpec;
            audioOutputController = new AudioOutputController(created);
            rebuildUsed = false;
            terminalErrorReported = false;
            if (previous != null) {
                try {
                    previous.release();
                } catch (RuntimeException error) {
                    retainForCleanup(previous);
                    reportInternalError("playback_generation_cleanup_failed", error);
                }
            }
            completePendingSetupSuccess(created);
        });
    }

    private void completePendingSetupSuccess(AudioOutputController.PlayerAdapter created) {
        List<SetupRequest> setupRequests;
        synchronized (this) {
            setupRequests = new ArrayList<>(pendingSetupRequests);
            pendingSetupRequests.clear();
            setupInFlight = false;
        }
        for (SetupRequest setupRequest : setupRequests) deliverSetupSuccess(setupRequest, created);
    }

    private void completePendingSetupFailure(RuntimeException error) {
        List<SetupRequest> setupRequests;
        synchronized (this) {
            setupRequests = new ArrayList<>(pendingSetupRequests);
            pendingSetupRequests.clear();
            setupInFlight = false;
        }
        for (SetupRequest setupRequest : setupRequests) deliverSetupFailure(setupRequest, error);
    }

    private void deliverSetupSuccess(
            SetupRequest setupRequest,
            AudioOutputController.PlayerAdapter created
    ) {
        try {
            setupRequest.success(created);
        } catch (RuntimeException error) {
            reportInternalError("setup_success_callback_failed", error);
        }
    }

    private void deliverSetupFailure(SetupRequest setupRequest, RuntimeException setupError) {
        try {
            setupRequest.failure(setupError);
        } catch (RuntimeException error) {
            reportInternalError("setup_failure_callback_failed", error);
        }
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
            if (terminalErrorReported) return;

            if (rebuildUsed) {
                reportTerminalAudioSinkError();
                return;
            }

            AudioOutputController.RecoveryAction action;
            try {
                action = audioOutputController.onAudioSinkError(userPlayIntent);
            } catch (RuntimeException error) {
                reportTerminalAudioSinkError();
                return;
            }
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
                reportTerminalAudioSinkError();
            }
        });
    }

    private void rebuildCurrentPlayer() {
        AudioOutputController.PlayerAdapter oldPlayer = player;
        AudioOutputController.PlayerAdapter rebuiltPlayer = null;
        try {
            PlaybackSnapshot snapshot = PlaybackSnapshot.capture(oldPlayer, userPlayIntent);
            PlaybackSetupSpec rebuiltSetupSpec = playerSetupSpec == null
                    ? PlaybackSetupSpec.audioOffloadOnly(false)
                    : playerSetupSpec.withAudioOffloadEnabled(false);
            rebuiltPlayer = playerFactory.create(rebuiltSetupSpec);
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
            playerSetupSpec = rebuiltSetupSpec;
            audioOutputController = AudioOutputController.compatibilityMode(rebuiltPlayer);
        } catch (RuntimeException error) {
            releaseFailedGeneration(rebuiltPlayer, error);
            reportTerminalAudioSinkError();
            return;
        }
        try {
            oldPlayer.release();
        } catch (RuntimeException error) {
            retainForCleanup(oldPlayer);
            reportInternalError("playback_generation_cleanup_failed", error);
            reportTerminalAudioSinkError();
            return;
        }
        listener.onCompatibilityChanged(new AudioOutputCompatibilityEvent(true, true));
    }

    private void reportTerminalAudioSinkError() {
        if (terminalErrorReported) return;
        terminalErrorReported = true;
        listener.onUnrecoveredAudioSinkError();
    }

    private void reportInternalError(String code, RuntimeException error) {
        try {
            listener.onInternalError(code, error);
        } catch (RuntimeException reportingError) {
            error.addSuppressed(reportingError);
        }
    }

    private void retainForCleanup(AudioOutputController.PlayerAdapter retainedPlayer) {
        synchronized (this) {
            for (AudioOutputController.PlayerAdapter existing : retainedCleanupPlayers) {
                if (existing == retainedPlayer) return;
            }
            retainedCleanupPlayers.add(retainedPlayer);
        }
    }

    private List<AudioOutputController.PlayerAdapter> takeRetainedCleanupPlayers() {
        synchronized (this) {
            List<AudioOutputController.PlayerAdapter> retained =
                    new ArrayList<>(retainedCleanupPlayers);
            retainedCleanupPlayers.clear();
            return retained;
        }
    }

    private void releaseDuringDestroy(
            AudioOutputController.PlayerAdapter playerToRelease,
            String errorCode
    ) {
        try {
            playerToRelease.release();
        } catch (RuntimeException error) {
            reportInternalError(errorCode, error);
        }
    }

    private static void releaseFailedGeneration(
            AudioOutputController.PlayerAdapter failedPlayer,
            RuntimeException failure
    ) {
        if (failedPlayer == null) return;
        try {
            failedPlayer.release();
        } catch (RuntimeException releaseError) {
            failure.addSuppressed(releaseError);
        }
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
        List<SetupRequest> cancelledSetups = new ArrayList<>(pendingSetupRequests);
        pendingSetupRequests.clear();
        setupInFlight = false;
        serialQueue.clearPending();
        serialQueue.post(() -> {
            RuntimeException destroyedError =
                    new IllegalStateException("Playback lifecycle is destroyed");
            for (SetupRequest setupRequest : cancelledSetups) {
                deliverSetupFailure(setupRequest, destroyedError);
            }
            AudioOutputController.PlayerAdapter current = player;
            if (current != null) {
                try {
                    playerOwner.clear(current);
                } catch (RuntimeException error) {
                    reportInternalError("playback_generation_owner_clear_failed", error);
                } finally {
                    player = null;
                    playerSetupSpec = null;
                    audioOutputController = null;
                }
                releaseDuringDestroy(current, "playback_generation_destroy_failed");
            }
            for (AudioOutputController.PlayerAdapter retained : takeRetainedCleanupPlayers()) {
                releaseDuringDestroy(retained, "playback_generation_cleanup_retry_failed");
            }
        });
    }

    private static final class SetupRequest {
        private final SetupCallback callback;
        private boolean completed;

        SetupRequest(SetupCallback callback) {
            this.callback = callback;
        }

        void success(AudioOutputController.PlayerAdapter player) {
            synchronized (this) {
                if (completed) return;
                completed = true;
            }
            callback.onSuccess(player);
        }

        void failure(RuntimeException error) {
            synchronized (this) {
                if (completed) return;
                completed = true;
            }
            callback.onFailure(error);
        }
    }
}
